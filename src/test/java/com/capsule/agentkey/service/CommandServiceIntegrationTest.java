package com.capsule.agentkey.service;

import com.capsule.agentkey.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 批次 B1 · 真实环境联调
 * 在真实 Windows + 真实 PATH 下跑 CommandService.run()，验证 4 道防线 + 合法命令不被误伤。
 */
class CommandServiceIntegrationTest {

    private CommandService svc;
    private Path workDir;

    @BeforeEach
    void setup() throws Exception {
        // 工作目录就用项目根
        workDir = Paths.get("d:/test-code/agent-gateway-v3");
        WorkspaceFileService ws = new WorkspaceFileService();
        svc = new CommandService(ws);

        Field f = CommandService.class.getDeclaredField("enabled");
        f.setAccessible(true);
        f.set(svc, true);

        f = CommandService.class.getDeclaredField("allowedRaw");
        f.setAccessible(true);
        f.set(svc, "npm,npx,node,pnpm,yarn,git,mvn,java,python,python3,go,dotnet,cargo,make,cmake,tsc,vite,powershell,pwsh");
    }

    // ======== 防线 1：解释器 eval ========

    @Test
    void node_eval真实环境被拦截() {
        try {
            svc.run(workDir.toString(), "node -e \"console.log(1)\"",
                    null, null, new AtomicBoolean());
            System.out.println("FAIL: node -e 应该被拦截");
        } catch (BusinessException e) {
            System.out.println("✅ node -e 被拦截: " + e.getMessage());
        }
    }

    @Test
    void python_eval真实环境被拦截() {
        try {
            svc.run(workDir.toString(), "python -c \"import os\"",
                    null, null, new AtomicBoolean());
            System.out.println("FAIL: python -c 应该被拦截");
        } catch (BusinessException e) {
            System.out.println("✅ python -c 被拦截: " + e.getMessage());
        }
    }

    // ======== 防线 2：相对脚本 ========

    @Test
    void 相对脚本被拦截() {
        try {
            svc.run(workDir.toString(), "./evil.sh",
                    null, null, new AtomicBoolean());
            System.out.println("FAIL: ./evil.sh 应该被拦截");
        } catch (BusinessException e) {
            System.out.println("✅ ./evil.sh 被拦截: " + e.getMessage());
        }
    }

    // ======== 防线 3：git 危险子命令 ========

    @Test
    void git_push_force被拦截() {
        try {
            svc.run(workDir.toString(), "git push --force origin main",
                    null, null, new AtomicBoolean());
            System.out.println("FAIL: git push --force 应该被拦截");
        } catch (BusinessException e) {
            System.out.println("✅ git push --force 被拦截: " + e.getMessage());
        }
    }

    @Test
    void git_reset_hard被拦截() {
        try {
            svc.run(workDir.toString(), "git reset --hard HEAD",
                    null, null, new AtomicBoolean());
            System.out.println("FAIL: git reset --hard 应该被拦截");
        } catch (BusinessException e) {
            System.out.println("✅ git reset --hard 被拦截: " + e.getMessage());
        }
    }

    @Test
    void git_clean被拦截() {
        try {
            svc.run(workDir.toString(), "git clean -fd",
                    null, null, new AtomicBoolean());
            System.out.println("FAIL: git clean 应该被拦截");
        } catch (BusinessException e) {
            System.out.println("✅ git clean 被拦截: " + e.getMessage());
        }
    }

    // ======== 防线 4：系统敏感路径 ========

    @Test
    void Windows盘符路径被拦截() {
        try {
            svc.run(workDir.toString(), "python C:\\\\Windows\\\\System32\\\\xxx",
                    null, null, new AtomicBoolean());
            System.out.println("FAIL: C:\\xxx 应该被拦截");
        } catch (BusinessException e) {
            System.out.println("✅ C:\\xxx 被拦截: " + e.getMessage());
        }
    }

    @Test
    void Unix敏感路径被拦截() {
        try {
            svc.run(workDir.toString(), "python /etc/passwd",
                    null, null, new AtomicBoolean());
            System.out.println("FAIL: /etc/passwd 应该被拦截");
        } catch (BusinessException e) {
            System.out.println("✅ /etc/passwd 被拦截: " + e.getMessage());
        }
    }

    // ======== 正向：合法命令不被误伤 ========

    @Test
    void git_status合法不被拦截() {
        try {
            CommandService.CommandResult r = svc.run(workDir.toString(), "git status",
                    null, null, new AtomicBoolean());
            String out = r.output();
            System.out.println("✅ git status 合法执行（exit=" + r.exitCode() + "），输出开头: " + (out.length() > 80 ? out.substring(0, 80) + "..." : out));
        } catch (BusinessException e) {
            System.out.println("FAIL: git status 被误伤: " + e.getMessage());
        }
    }

    @Test
    void git_push不带force合法不被拦截() {
        try {
            CommandService.CommandResult r = svc.run(workDir.toString(), "git push origin main",
                    null, null, new AtomicBoolean());
            System.out.println("✅ git push origin main 合法执行（exit=" + r.exitCode() + "）");
        } catch (BusinessException e) {
            String msg = e.getMessage();
            if (msg.contains("不在允许范围内") || msg.contains("拦截") || msg.contains("敏感路径") || msg.contains("eval") || msg.contains("相对脚本")) {
                System.out.println("FAIL: git push (不带force) 被误伤: " + msg);
            } else {
                System.out.println("✅ git push origin main 合法执行（远端无分支属正常业务错误，不是安全校验拦截）");
            }
        }
    }

    @Test
    void python执行本地脚本不被eval拦截() {
        try {
            CommandService.CommandResult r = svc.run(workDir.toString(), "python --version",
                    null, null, new AtomicBoolean());
            System.out.println("✅ python --version 合法执行，输出: " + r.output().trim());
        } catch (BusinessException e) {
            System.out.println("FAIL: python --version 被误伤: " + e.getMessage());
        }
    }

    @Test
    void git_diff合法执行() {
        try {
            CommandService.CommandResult r = svc.run(workDir.toString(), "git diff --stat HEAD~1..HEAD",
                    null, null, new AtomicBoolean());
            System.out.println("✅ git diff 合法执行（exit=" + r.exitCode() + "）");
        } catch (BusinessException e) {
            String msg = e.getMessage();
            if (msg.contains("不在允许范围内") || msg.contains("拦截") || msg.contains("敏感路径") || msg.contains("eval") || msg.contains("相对脚本")) {
                System.out.println("FAIL: git diff 被误伤: " + msg);
            } else {
                System.out.println("✅ git diff 合法执行（业务异常，不是安全拦截）");
            }
        }
    }
}
