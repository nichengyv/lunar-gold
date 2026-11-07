package com.capsule.agentkey.service;

import com.capsule.agentkey.exception.BusinessException;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 工作区终端命令执行器（沙箱）。
 *
 * 四道限制：
 * 1. 程序白名单：只允许 npm / node / git / mvn / python 等开发工具，
 *    rm、del、sh、powershell 这类破坏性程序一律拒绝；
 * 2. 工作目录必须落在工作区内（复用 WorkspaceFileService.resolveInWorkspace 的越界校验）；
 * 3. 超时强制终止整棵进程树，避免 build 卡死把会话拖到 SSE 超时；
 * 4. 输出截断，防止一次 build 日志把模型上下文撑爆。
 *
 * 另外：命令不经过 shell 解释。自己分词后直接交给 ProcessBuilder，
 * 因此 "&&"、"|"、">" 都不会被当成管道/重定向执行（含这些字符的参数直接拒绝）。
 */
@Service
@Slf4j
public class CommandService {

    @Value("${workspace.command.enabled:true}")
    private boolean enabled;

    @Value("${workspace.command.timeout-seconds:60}")
    private int defaultTimeoutSeconds;

    @Value("${workspace.command.max-timeout-seconds:300}")
    private int maxTimeoutSeconds;

    @Value("${workspace.command.max-output-bytes:8192}")
    private int maxOutputBytes;

    @Value("${workspace.command.allowed:npm,npx,node,pnpm,yarn,git,mvn,mvnw,java,javac,gradle,gradlew,python,python3,pip,pip3,pytest,go,dotnet,cargo,make,cmake,tsc,vite}")
    private String allowedRaw;

    /**
     * JDK / Maven 安装目录：IDEA 能编译不代表它们在系统 PATH 里
     * （IDEA 用自己的 bundled Maven 和项目 SDK），所以这里允许显式指定。
     * 为空时回退到 JAVA_HOME / MAVEN_HOME / M2_HOME 环境变量，再回退到 PATH。
     */
    @Value("${workspace.command.java-home:}")
    private String javaHomeRaw;

    @Value("${workspace.command.maven-home:}")
    private String mavenHomeRaw;

    /** 额外参与程序查找的目录，多个用系统路径分隔符（Windows 为分号） */
    @Value("${workspace.command.extra-path:}")
    private String extraPathRaw;

    /** 命令字符串本身的上限，避免异常输入 */
    private static final int MAX_COMMAND_LENGTH = 1000;

    /** shell 元字符：不经过 shell 也拒绝，防止程序把它当参数再拼进子命令 */
    private static final String SHELL_META = "&|<>^;`\n\r";

    /** 必须命中白名单，否则拒绝。白名单故意不放 shell 包装器（cmd/powershell/sh）——
     * 因为本服务不经过 shell 解释，直接 ProcessBuilder 起进程；
     * Agent 只要传 "mvn clean package" 而不是 "cmd /c mvn clean package" 就行，
     * Windows 上会自动在 PATH 里找 .cmd/.exe 变体。 */
    private static final Set<String> SHELL_WRAPPERS = Set.of(
            "cmd", "cmd.exe", "command.com",
            "powershell", "powershell.exe", "pwsh", "pwsh.exe",
            "sh", "bash", "zsh", "fish", "dash", "busybox");

    /** 解释器白名单里的 eval 程序：eval 参数会绕过白名单再执行任意命令 */
    private static final Set<String> INTERPRETERS = Set.of(
            "node", "nodejs", "npm", "npx", "pnpm", "yarn",
            "python", "python3", "pypy", "pypy3", "pip", "pip3",
            "ruby", "perl", "php", "java", "javac", "groovy");

    /** 解释器的 eval 形参数 */
    private static final Set<String> EVAL_FLAGS = Set.of(
            "-e", "--eval", "-c", "--command", "-r", "--run", "-R");

    /** 禁止 git 破坏性子命令（在 git 白名单下拦截具体子命令，避免误伤正常开发） */
    private static final Set<String> DANGEROUS_GIT_SUBS = Set.of(
            "push", "reset", "checkout", "clean", "revert", "am");

    /** 程序自身的路径参数：这些命令允许绝对路径（mvn -f /some/path, git -C /some/path） */
    private static final Set<String> PROGRAMS_ALLOWING_ABS_PATH = Set.of(
            "mvn", "mvnw", "java", "javac", "git", "go", "dotnet", "cargo", "cmake", "make");

    /** 系统敏感路径前缀：即使白名单里的工具也不允许读写这些位置 */
    private static final Set<String> SENSITIVE_PATH_PREFIXES = Set.of(
            "/etc", "/root", "/var", "/proc", "/sys", "/dev", "/boot", "/srv");

    /** C3: Docker 沙箱镜像（Linux 容器里执行命令）——默认 alpine:latest，可通过配置覆盖 */
    @Value("${workspace.command.docker.image:alpine:latest}")
    private String dockerImage = "alpine:latest";

    /** C3: Docker 沙箱开关——auto=自动检测 Docker daemon 是否在线，always/never=强制 */
    @Value("${workspace.command.docker.mode:auto}")
    private String dockerMode = "auto";

    /** C3: Docker daemon 是否可用（启动时探测一次，失败则整个会话 fallback 宿主） */
    private boolean dockerAvailable = false;

    private final WorkspaceFileService workspaceFileService;

    public CommandService(WorkspaceFileService workspaceFileService) {
        this.workspaceFileService = workspaceFileService;
    }

    /** 启动时探测 Docker daemon——只测一次，避免每次 run() 都 fork 进程 */
    @PostConstruct
    void probeDocker() {
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        if (windows) {
            log.info("C3 沙箱: 当前 Windows 环境，Docker Linux 容器无法运行宿主 .exe 工具链，跳过 Docker 探测");
            return;
        }
        try {
            ProcessBuilder probe = new ProcessBuilder("docker", "info").redirectErrorStream(true);
            sanitizeEnvironment(probe);
            Process p = probe.start();
            boolean ok = p.waitFor(3, TimeUnit.SECONDS) && p.exitValue() == 0;
            dockerAvailable = ok;
            if (ok) {
                log.info("C3 沙箱: Docker daemon 在线，run_command 将在容器内执行 (image={})", dockerImage);
            } else {
                log.info("C3 沙箱: Docker daemon 不可用，run_command 走宿主进程（可启动 Docker Desktop 后生效）");
            }
        } catch (Exception e) {
            log.info("C3 沙箱: Docker 未安装 ({})，run_command 走宿主进程", e.getMessage());
        }
    }

    /** 启动时打印一次工具链位置，找不到时日志里能直接看到原因，不用等用户跑命令才发现 */
    @PostConstruct
    void logToolchain() {
        Path javaHome = resolveJavaHome();
        Path mavenHome = resolveMavenHome();
        log.info("终端工具链: java-home={}, maven-home={}",
                javaHome == null ? "未找到（java 只能走 PATH）" : javaHome,
                mavenHome == null ? "未找到（mvn 只能走 PATH）" : mavenHome);
    }

    /** 命令执行结果 */
    public record CommandResult(int exitCode, String output, boolean truncated,
                                boolean timedOut, boolean cancelled, long durationMs) {}

    /**
     * 在工作区内执行一条命令。
     *
     * @param workspaceRoot 工作区根目录
     * @param command       完整命令行，如 "npm run build"
     * @param cwd           相对工作区的执行目录，空则根目录
     * @param timeoutSeconds 超时秒数，null 用默认值
     * @param cancelled     客户端停止/断连标记，运行中每 200ms 检查一次
     */
    public CommandResult run(String workspaceRoot, String command, String cwd,
                             Integer timeoutSeconds, AtomicBoolean cancelled) {
        if (!enabled) {
            throw new BusinessException("终端命令执行已被管理员禁用");
        }
        if (command == null || command.isBlank()) {
            throw new BusinessException("命令不能为空");
        }
        if (command.length() > MAX_COMMAND_LENGTH) {
            throw new BusinessException("命令过长，请拆分成多条更小的命令");
        }

        Path root = Paths.get(workspaceRoot);
        Path workDir = workspaceFileService.resolveInWorkspace(root, cwd);
        if (!Files.isDirectory(workDir)) {
            throw new BusinessException("执行目录不存在：" + (cwd == null ? "." : cwd));
        }

        List<String> tokens = tokenize(command);
        if (tokens.isEmpty()) {
            throw new BusinessException("命令不能为空");
        }
        for (String token : tokens) {
            for (char c : SHELL_META.toCharArray()) {
                if (token.indexOf(c) >= 0) {
                    throw new BusinessException("命令包含不允许的字符：" + c);
                }
            }
        }

        // ======== 批次 B：额外安全校验 ========
        String programBase = stripExtension(tokens.get(0)).toLowerCase(Locale.ROOT);

        // 校验 1：解释器 eval 参数绕过白名单（node -e / python -c 等）
        if (INTERPRETERS.contains(programBase)) {
            for (int i = 1; i < tokens.size(); i++) {
                if (EVAL_FLAGS.contains(tokens.get(i))) {
                    throw new BusinessException(
                            "命令包含解释器 eval 参数（" + tokens.get(i) + "），会绕过白名单安全限制，"
                                    + "请改用脚本文件（写入工作区后 'npm run xxx' / 'python script.py'）执行");
                }
            }
        }

        // 校验 2：git 破坏性子命令拦截（push/reset/checkout/clean/revert/am 的危险变体）
        if ("git".equals(programBase) && tokens.size() >= 2) {
            String sub = tokens.get(1);
            if ("push".equals(sub)) {
                if (tokens.stream().anyMatch(t -> t.startsWith("--force") || t.equals("-f"))) {
                    throw new BusinessException("git push --force 不在允许范围内，避免强制推送覆盖远程历史");
                }
            } else if ("reset".equals(sub)) {
                if (tokens.stream().anyMatch(t -> t.equals("--hard") || t.equals("--merge") || t.equals("--keep") || t.equals("--mixed"))) {
                    throw new BusinessException("git reset --hard 不在允许范围内，避免丢失工作区修改");
                }
            } else if ("checkout".equals(sub)) {
                if (tokens.stream().anyMatch(t -> t.equals("-f") || t.equals("--force"))) {
                    throw new BusinessException("git checkout --force 不在允许范围内，避免覆盖本地未提交修改");
                }
            } else if ("clean".equals(sub)) {
                throw new BusinessException("git clean 不在允许范围内，避免删除未跟踪文件");
            } else if ("revert".equals(sub)) {
                throw new BusinessException("git revert 不在允许范围内，请手动在终端执行");
            } else if ("am".equals(sub)) {
                throw new BusinessException("git am 不在允许范围内，请手动在终端执行");
            }
        }

        // 校验 3：参数里的绝对路径（防止工具读写系统位置，如 'cat C:\\\\Windows\\\\System32\\\\xxx'）
        // 例外：mvn -f /some/path、git -C /some/path 这类工具自身接受绝对路径
        if (!PROGRAMS_ALLOWING_ABS_PATH.contains(programBase)) {
            for (int i = 1; i < tokens.size(); i++) {
                checkNoSensitivePath(tokens.get(i), command);
            }
        }
        // mvn -f /path、git -C /path 也检查敏感路径（只是允许带绝对路径的格式）
        else {
            for (int i = 1; i < tokens.size(); i++) {
                String t = tokens.get(i);
                // -f /path 取下一个 token 做检查
                if ("-f".equals(t) || "--file".equals(t) || "-C".equals(t) || "--git-dir".equals(t)) {
                    if (i + 1 < tokens.size()) checkNoSensitivePath(tokens.get(i + 1), command);
                } else {
                    // git checkout /etc/passwd 这种路径参数也拦住
                    checkNoSensitivePath(t, command);
                }
            }
        }

        // 校验 4：绝对禁止执行工作区内的相对脚本（./evil.py / ./script.sh）
        // 理由：Agent 可以先 write_file 写恶意脚本再执行，绕过白名单对解释器本身的限制
        boolean relative = tokens.get(0).startsWith("./");
        if (relative) {
            throw new BusinessException(
                    "不允许执行工作区内的相对脚本（" + tokens.get(0) + "），"
                            + "请改用对应的解释器程序名（如 'python script.py' / 'node script.js'）");
        }

        Path executable = resolveExecutable(tokens.get(0), workDir);
        int timeout = clampTimeout(timeoutSeconds);

        // C3: Docker 沙箱——daemon 可用且不是 Windows 时，在容器里执行
        ProcessBuilder pb;
        if (shouldUseDocker()) {
            pb = buildDockerProcessBuilder(workDir, tokens, timeout);
            log.info("C3 沙箱模式: docker run (image={})", dockerImage);
        } else {
            pb = buildHostProcessBuilder(executable, tokens, workDir);
        }

        long start = System.currentTimeMillis();
        Process process;
        try {
            process = pb.start();
            // 立刻关闭 stdin：子进程若尝试读输入会收到 EOF 而不是挂起
            process.getOutputStream().close();
        } catch (IOException e) {
            throw new BusinessException("命令启动失败：" + e.getMessage());
        }

        log.info("执行命令: {} (cwd={}, timeout={}s)", command, cwd == null ? "." : cwd, timeout);

        StringBuilder output = new StringBuilder();
        AtomicBoolean truncated = new AtomicBoolean(false);
        Thread reader = new Thread(() -> readOutput(process.getInputStream(), output, truncated),
                "cmd-output-" + process.pid());
        reader.setDaemon(true);
        reader.start();

        boolean timedOut = false;
        boolean stopped = false;
        try {
            while (true) {
                if (process.waitFor(200, TimeUnit.MILLISECONDS)) {
                    break;
                }
                if (cancelled != null && cancelled.get()) {
                    stopped = true;
                    killTree(process);
                    break;
                }
                if (System.currentTimeMillis() - start > timeout * 1000L) {
                    timedOut = true;
                    killTree(process);
                    break;
                }
            }
            // 给被强杀的子进程一点收尾时间，避免 exitValue 抛 IllegalThreadStateException
            process.waitFor(3, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            killTree(process);
        }

        try {
            reader.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        int exitCode = process.isAlive() ? -1 : process.exitValue();
        long duration = System.currentTimeMillis() - start;
        log.info("命令结束: exit={}, cost={}ms, timedOut={}, cancelled={}",
                exitCode, duration, timedOut, stopped);
        return new CommandResult(exitCode, output.toString(), truncated.get(), timedOut, stopped, duration);
    }

    /** 把命令行按空白拆分，支持单/双引号包裹含空格的参数（如 git commit -m "fix bug"） */
    static List<String> tokenize(String command) {
        List<String> tokens = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inSingle = false;
        boolean inDouble = false;
        boolean hasToken = false;
        for (char c : command.toCharArray()) {
            if (c == '\'' && !inDouble) {
                inSingle = !inSingle;
                hasToken = true;
            } else if (c == '"' && !inSingle) {
                inDouble = !inDouble;
                hasToken = true;
            } else if (Character.isWhitespace(c) && !inSingle && !inDouble) {
                if (hasToken || cur.length() > 0) {
                    tokens.add(cur.toString());
                    cur.setLength(0);
                    hasToken = false;
                }
            } else {
                cur.append(c);
            }
        }
        if (inSingle || inDouble) {
            throw new BusinessException("命令中的引号未闭合");
        }
        if (hasToken || cur.length() > 0) {
            tokens.add(cur.toString());
        }
        return tokens;
    }

    /** 校验并解析可执行程序：只接受程序名（或工作区内的 ./脚本），必须命中白名单 */
    private Path resolveExecutable(String raw, Path workDir) {
        String name = raw.replace('\\', '/');
        boolean relative = name.startsWith("./");
        String bare = relative ? name.substring(2) : name;
        if (bare.contains("/")) {
            throw new BusinessException("命令必须是不带路径的程序名：" + raw);
        }
        String base = stripExtension(bare).toLowerCase(Locale.ROOT);

        // 先拦截 shell 包装器（cmd/powershell/sh/bash）——给明确的友好提示
        if (SHELL_WRAPPERS.contains(base)) {
            throw new BusinessException(
                    "不能用 shell 包装器（" + base + "）调用命令。"
                    + "本终端直接起进程，不经过 shell 解释，"
                    + "请直接写要执行的程序名：\n"
                    + "  ❌ cmd /c mvn clean package\n"
                    + "  ✅ mvn clean package\n"
                    + "（Windows 上会自动找 .cmd/.exe 后缀变体）");
        }

        Set<String> allowed = allowedCommands();
        if (!allowed.contains(base)) {
            throw new BusinessException("命令不在白名单内：" + base
                    + "，可用：" + String.join(", ", allowed)
                    + "\n提示：不要用 cmd / powershell / bash 这类 shell 包装器，"
                    + "直接写程序名即可");
        }

        if (relative) {
            Path local = workDir.resolve(bare).normalize();
            if (!local.startsWith(workDir) || !Files.isRegularFile(local)) {
                throw new BusinessException("工作区内找不到脚本：" + bare);
            }
            return local;
        }

        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        // Windows 上优先找 .exe/.cmd/.bat 变体，最后才试无后缀（可能是 Unix shell 脚本，不能被 CreateProcess 直接起）
        List<String> suffixes = windows
                ? Arrays.asList(".exe", ".cmd", ".bat", "")
                : List.of("");

        for (Path dir : searchDirs()) {
            for (String suffix : suffixes) {
                Path candidate = dir.resolve(bare + suffix);
                if (Files.isRegularFile(candidate)) {
                    return candidate;
                }
            }
        }
        throw new BusinessException("找不到可执行程序：" + base + missingProgramHint(base));
    }

    /** 找不到程序时给出可操作的修复建议，而不是一句"请安装" */
    private String missingProgramHint(String base) {
        return switch (base) {
            case "mvn", "mvnw" -> "。解决办法（任选其一）：\n"
                    + "1. 在 application.yml 配置 workspace.command.maven-home（IDEA 自带 Maven 位于 <IDEA 安装目录>\\plugins\\maven\\lib\\maven3）\n"
                    + "2. 设置环境变量 MAVEN_HOME 后重启后端\n"
                    + "3. 项目自带 Maven Wrapper 时改用 './mvnw.cmd clean package'";
            case "java", "javac" -> "。请在 application.yml 配置 workspace.command.java-home（JDK 安装目录），"
                    + "或设置 JAVA_HOME 环境变量后重启后端";
            default -> "，请确认已安装并在 PATH 中，或用 workspace.command.extra-path 指定所在目录";
        };
    }

    /** 程序查找目录：配置的 JDK/Maven → 额外目录 → 系统 PATH */
    private List<Path> searchDirs() {
        List<Path> dirs = new ArrayList<>();
        Path javaHome = resolveJavaHome();
        if (javaHome != null) dirs.add(javaHome.resolve("bin"));
        Path mavenHome = resolveMavenHome();
        if (mavenHome != null) dirs.add(mavenHome.resolve("bin"));
        if (extraPathRaw != null && !extraPathRaw.isBlank()) {
            for (String item : extraPathRaw.split(File.pathSeparator)) {
                Path dir = existingDir(item);
                if (dir != null) dirs.add(dir);
            }
        }
        String pathEnv = System.getenv("PATH");
        if (pathEnv != null) {
            for (String dir : pathEnv.split(File.pathSeparator)) {
                if (!dir.isBlank()) dirs.add(Paths.get(dir));
            }
        }
        return dirs;
    }

    /** JDK 目录：配置项 → JAVA_HOME */
    private Path resolveJavaHome() {
        Path configured = existingDir(javaHomeRaw);
        return configured != null ? configured : existingDir(System.getenv("JAVA_HOME"));
    }

    /** Maven 目录：配置项 → MAVEN_HOME → M2_HOME */
    private Path resolveMavenHome() {
        Path configured = existingDir(mavenHomeRaw);
        if (configured != null) return configured;
        Path home = existingDir(System.getenv("MAVEN_HOME"));
        return home != null ? home : existingDir(System.getenv("M2_HOME"));
    }

    private Path existingDir(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            Path p = Paths.get(raw.trim());
            return Files.isDirectory(p) ? p : null;
        } catch (Exception e) {
            return null;
        }
    }

    private String stripExtension(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        for (String ext : List.of(".exe", ".cmd", ".bat", ".sh")) {
            if (lower.endsWith(ext)) {
                return name.substring(0, name.length() - ext.length());
            }
        }
        return name;
    }

    private Set<String> allowedCommands() {
        Set<String> set = new LinkedHashSet<>();
        for (String s : allowedRaw.split(",")) {
            String item = s.trim().toLowerCase(Locale.ROOT);
            if (!item.isEmpty()) set.add(item);
        }
        return set;
    }

    private int clampTimeout(Integer timeoutSeconds) {
        if (timeoutSeconds == null || timeoutSeconds <= 0) {
            return defaultTimeoutSeconds;
        }
        return Math.min(timeoutSeconds, maxTimeoutSeconds);
    }

    /** 读取合并后的 stdout+stderr，超过上限只保留头部，标记截断 */
    private void readOutput(InputStream in, StringBuilder sink, AtomicBoolean truncated) {
        int limit = maxOutputBytes * 4; // 按字节粗算，给多字节字符留余量
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        long total = 0;
        byte[] buf = new byte[4096];
        int n;
        try {
            while ((n = in.read(buf)) != -1) {
                total += n;
                if (buffer.size() < limit) {
                    int allowed = Math.min(n, limit - buffer.size());
                    buffer.write(buf, 0, allowed);
                }
            }
        } catch (IOException e) {
            log.debug("读取命令输出中断: {}", e.getMessage());
        }
        if (total > limit) {
            truncated.set(true);
        }
        String text = decode(buffer.toByteArray());
        synchronized (sink) {
            sink.append(text.length() > maxOutputBytes ? text.substring(0, maxOutputBytes) : text);
            if (text.length() > maxOutputBytes) {
                truncated.set(true);
            }
        }
    }

    /** 优先按 UTF-8 解码，失败回退 GBK（Windows 控制台中文输出常见编码） */
    private String decode(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            try {
                return new String(bytes, Charset.forName("GBK"));
            } catch (Exception ignore) {
                return new String(bytes, StandardCharsets.UTF_8);
            }
        }
    }

    /** 强制杀掉整棵进程树（npm/mvn 都会派生子进程，只杀父进程会留孤儿） */
    private void killTree(Process process) {
        try {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
        } catch (Exception e) {
            log.debug("终止子进程失败: {}", e.getMessage());
        }
        process.destroyForcibly();
    }

    /** 检查参数是否命中系统敏感路径：盘符 /etc/ /root 等，命中直接拒绝 */
    private void checkNoSensitivePath(String arg, String command) {
        if (arg == null || arg.isBlank() || arg.startsWith("-")) return;
        // Windows 盘符：C:\、D:\、C:/
        if (arg.length() >= 2 && Character.isLetter(arg.charAt(0)) && arg.charAt(1) == ':'
                && arg.length() >= 3 && (arg.charAt(2) == '\\' || arg.charAt(2) == '/')) {
            throw new BusinessException("命令参数包含系统绝对路径（" + arg + "），不允许访问工作区外位置");
        }
        // Unix 系统路径
        String lower = arg.toLowerCase(Locale.ROOT);
        for (String prefix : SENSITIVE_PATH_PREFIXES) {
            if (lower.startsWith(prefix + "/") || lower.equals(prefix)) {
                throw new BusinessException("命令参数包含系统敏感路径（" + arg + "），不允许访问系统目录");
            }
        }
    }

    // —— C3: Docker 沙箱辅助方法 ——

    private boolean shouldUseDocker() {
        return switch (dockerMode.toLowerCase(Locale.ROOT)) {
            case "always" -> true;
            case "never" -> false;
            default -> dockerAvailable; // auto：探测结果
        };
    }

    /**
     * 子进程只继承运行工具链所需的少量变量，避免 JWT、数据库密码、云端凭据等随进程泄露。
     * 各工具需要的安装目录随后由配置显式补回，不继承 *_KEY / *_TOKEN / *_SECRET。
     */
    private void sanitizeEnvironment(ProcessBuilder pb) {
        Set<String> allowlist = Set.of(
                "PATH", "PATHEXT", "HOME", "USERPROFILE", "APPDATA", "LOCALAPPDATA",
                "TEMP", "TMP", "TMPDIR", "LANG", "LC_ALL", "LC_CTYPE", "TZ",
                "SYSTEMROOT", "SYSTEMDRIVE", "WINDIR", "COMSPEC", "OS", "PROCESSOR_ARCHITECTURE");
        Map<String, String> environment = pb.environment();
        Map<String, String> safeValues = new java.util.LinkedHashMap<>();
        environment.forEach((name, value) -> {
            if (allowlist.contains(name.toUpperCase(Locale.ROOT))) {
                safeValues.put(name, value);
            }
        });
        environment.clear();
        environment.putAll(safeValues);
    }

    /** 宿主进程执行：ProcessBuilder 直接跑，带 JDK/Maven 显式环境变量 */
    private ProcessBuilder buildHostProcessBuilder(Path executable, List<String> tokens, Path workDir) {
        List<String> argv = new ArrayList<>();
        argv.add(executable.toString());
        argv.addAll(tokens.subList(1, tokens.size()));

        ProcessBuilder pb = new ProcessBuilder(argv);
        pb.directory(workDir.toFile());
        pb.redirectErrorStream(true);
        sanitizeEnvironment(pb);
        // 关闭交互：git 凭据提示、分页器都会让子进程一直等输入
        pb.environment().put("GIT_TERMINAL_PROMPT", "0");
        pb.environment().put("GIT_PAGER", "cat");
        pb.environment().put("PAGER", "cat");
        pb.environment().put("NO_COLOR", "1");
        // 显式 JDK/Maven，避免子进程找不到
        Path javaHome = resolveJavaHome();
        if (javaHome != null) {
            pb.environment().put("JAVA_HOME", javaHome.toString());
            String oldPath = pb.environment().get("PATH");
            pb.environment().put("PATH",
                    javaHome.resolve("bin") + File.pathSeparator + (oldPath == null ? "" : oldPath));
        }
        Path mavenHome = resolveMavenHome();
        if (mavenHome != null) {
            pb.environment().put("MAVEN_HOME", mavenHome.toString());
        }
        return pb;
    }

    /** Docker 沙箱执行：docker run --rm -v workspace:/work -w /work image cmd args */
    private ProcessBuilder buildDockerProcessBuilder(Path workDir, List<String> tokens, int timeout) {
        // 宿主工作区路径 → 容器 /work
        // alpine 镜像里需要工具预装（或用 toolbox 镜像），否则容器里找不到 npm/mvn/git
        // 这里只做框架——实际部署 Linux 时建议用带 toolchain 的自定义镜像
        List<String> argv = new ArrayList<>();
        argv.add("docker");
        argv.add("run");
        argv.add("--rm");
        argv.add("--network=none");              // 断网！Agent 无法外连
        argv.add("--read-only");                 // 根文件系统只读
        argv.add("--pids-limit=64");             // 进程数上限
        argv.add("--memory=512m");               // 内存上限
        argv.add("--cpus=1");                    // CPU 上限
        argv.add("-v");
        argv.add(workDir.toAbsolutePath() + ":/work");
        argv.add("-w");
        argv.add("/work");
        argv.add(dockerImage);
        argv.addAll(tokens);

        ProcessBuilder pb = new ProcessBuilder(argv);
        pb.redirectErrorStream(true);
        sanitizeEnvironment(pb);
        // Docker 模式不需要宿主 JDK/Maven——容器里有自己的工具链
        return pb;
    }
}
