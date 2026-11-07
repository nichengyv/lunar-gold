package com.capsule.agentkey.service;

import com.capsule.agentkey.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 批次 B：CommandService 安全加固回归测试。
 * 四个新校验：解释器 eval 拦截 / 禁止相对脚本 / git 危险子命令 / 系统敏感路径。
 */
class CommandServiceTest {

    private CommandService svc;
    private Path tempDir;

    @BeforeEach
    void setup() throws Exception {
        tempDir = Files.createTempDirectory("cmdtest-");
        tempDir.toFile().deleteOnExit();

        // WorkspaceFileService 只有 @Value 字段，默认构造器就能用
        WorkspaceFileService ws = new WorkspaceFileService();
        svc = new CommandService(ws);

        // 反射把 @Value 字段设好（Spring 外 new 的话字段都是默认值）
        Field f = CommandService.class.getDeclaredField("enabled");
        f.setAccessible(true);
        f.set(svc, true);

        f = CommandService.class.getDeclaredField("allowedRaw");
        f.setAccessible(true);
        f.set(svc, "npm,npx,node,pnpm,yarn,git,mvn,java,python,python3,go,dotnet,cargo,make,cmake,tsc,vite");
    }

    // ======== 校验 1：解释器 eval 参数 ========

    @Test
    void node_eveal参数直接拒绝() {
        assertThrows(BusinessException.class,
                () -> svc.run(tempDir.toString(), "node -e \"require('child_process').execSync('whoami')\"",
                        null, null, new AtomicBoolean()),
                "eval 参数应被拒绝");
    }

    @Test
    void python_eveal参数直接拒绝() {
        assertThrows(BusinessException.class,
                () -> svc.run(tempDir.toString(), "python3 -c \"import os; os.system('whoami')\"",
                        null, null, new AtomicBoolean()));
    }

    @Test
    void ruby_eveal参数直接拒绝() {
        assertThrows(BusinessException.class,
                () -> svc.run(tempDir.toString(), "ruby -e 'system(\"whoami\")'",
                        null, null, new AtomicBoolean()));
    }

    // ======== 校验 2：禁止相对脚本 ========

    @Test
    void 执行工作区相对脚本直接拒绝() {
        // 即使程序本身在白名单里（假设 allowed 里有 bash），相对路径也拒绝
        // 这里 node 执行 ./script.js 绕过不了白名单对 node -e 的拦截
        assertThrows(BusinessException.class,
                () -> svc.run(tempDir.toString(), "./evil.py",
                        null, null, new AtomicBoolean()));
    }

    // ======== 校验 3：git 危险子命令 ========

    @Test
    void git_push_force拒绝() {
        assertThrows(BusinessException.class,
                () -> svc.run(tempDir.toString(), "git push --force origin main",
                        null, null, new AtomicBoolean()));
    }

    @Test
    void git_push_force_with_lease拒绝() {
        assertThrows(BusinessException.class,
                () -> svc.run(tempDir.toString(), "git push --force-with-lease origin main",
                        null, null, new AtomicBoolean()));
    }

    @Test
    void git_reset_hard拒绝() {
        assertThrows(BusinessException.class,
                () -> svc.run(tempDir.toString(), "git reset --hard HEAD~1",
                        null, null, new AtomicBoolean()));
    }

    @Test
    void git_checkout_force拒绝() {
        assertThrows(BusinessException.class,
                () -> svc.run(tempDir.toString(), "git checkout -f main",
                        null, null, new AtomicBoolean()));
    }

    @Test
    void git_clean拒绝() {
        assertThrows(BusinessException.class,
                () -> svc.run(tempDir.toString(), "git clean -fd",
                        null, null, new AtomicBoolean()));
    }

    @Test
    void git_revert拒绝() {
        assertThrows(BusinessException.class,
                () -> svc.run(tempDir.toString(), "git revert HEAD",
                        null, null, new AtomicBoolean()));
    }

    @Test
    void git_am拒绝() {
        assertThrows(BusinessException.class,
                () -> svc.run(tempDir.toString(), "git am patch.mbox",
                        null, null, new AtomicBoolean()));
    }

    // ======== 校验 4：系统敏感路径 ========

    @Test
    void 非允许工具带Windows盘符拒绝() {
        // python 带 C:\ 路径
        assertThrows(BusinessException.class,
                () -> svc.run(tempDir.toString(), "python C:\\Windows\\System32\\notepad.exe",
                        null, null, new AtomicBoolean()));
    }

    @Test
    void 非允许工具带etc路径拒绝() {
        assertThrows(BusinessException.class,
                () -> svc.run(tempDir.toString(), "python /etc/passwd",
                        null, null, new AtomicBoolean()));
    }

    // ======== 合法命令不应被误伤 ========

    @Test
    void 合法git_status不被拦截() {
        // 找不到 git 程序所以会抛"找不到可执行程序"，但不会抛我们的安全异常
        try {
            svc.run(tempDir.toString(), "git status", null, null, new AtomicBoolean());
        } catch (BusinessException e) {
            // 预期的找不到 git 程序（测试环境可能没装 git）
            // 但不应该是 eval / git 危险 / 敏感路径 / 相对脚本 相关的错误
            String msg = e.getMessage();
            assert !msg.contains("eval")
                    && !msg.contains("不允许执行工作区内的相对脚本")
                    && !msg.contains("git") && !msg.contains("不在允许范围内")
                    && !msg.contains("系统敏感路径")
                    : "合法 git status 被安全校验误伤: " + msg;
        }
    }

    @Test
    void 合法git_push不带force不被拦截() {
        try {
            svc.run(tempDir.toString(), "git push origin main", null, null, new AtomicBoolean());
        } catch (BusinessException e) {
            String msg = e.getMessage();
            assert !msg.contains("不在允许范围内")
                    : "合法 git push 不带 --force 被误伤: " + msg;
        }
    }

    @Test
    void 合法python执行本地脚本不被eval拦截() {
        try {
            svc.run(tempDir.toString(), "python main.py", null, null, new AtomicBoolean());
        } catch (BusinessException e) {
            String msg = e.getMessage();
            assert !msg.contains("eval")
                    : "合法 python main.py 被 eval 校验误伤: " + msg;
        }
    }

    @Test
    void 子进程环境只保留白名单变量() throws Exception {
        ProcessBuilder pb = new ProcessBuilder("unused");
        pb.environment().put("JWT_SECRET", "must-not-leak");
        pb.environment().put("DB_PASSWORD", "must-not-leak");
        pb.environment().put("PR01_TEST_PATH", "must-not-leak");
        pb.environment().put("TMPDIR", tempDir.toString());

        java.lang.reflect.Method method = CommandService.class.getDeclaredMethod(
                "sanitizeEnvironment", ProcessBuilder.class);
        method.setAccessible(true);
        method.invoke(svc, pb);

        assert !pb.environment().containsKey("JWT_SECRET");
        assert !pb.environment().containsKey("DB_PASSWORD");
        assert !pb.environment().containsKey("PR01_TEST_PATH");
        assert tempDir.toString().equals(pb.environment().get("TMPDIR"));
    }
}
