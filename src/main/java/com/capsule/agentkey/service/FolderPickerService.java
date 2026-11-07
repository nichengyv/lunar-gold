package com.capsule.agentkey.service;

import com.capsule.agentkey.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * 调用 Windows 自带的文件夹选择窗口，返回用户选中的绝对路径。
 *
 * 为什么不是 Swing 的 JFileChooser：那是 Java 自己画的界面，外观和系统不一致。
 * 这里通过 PowerShell 调用 Windows Shell 的 IFileDialog（FOS_PICKFOLDERS），
 * 弹出的是资源管理器同款窗口——和浏览器 <input type="file"> 上传文件时弹的是同一个系统对话框。
 *
 * 为什么不能纯前端实现：浏览器出于安全限制，<input webkitdirectory> 只给相对路径，
 * File System Access API 也只给目录句柄，拿不到 D:\xxx 这种绝对路径，无法存进数据库。
 */
@Service
@Slf4j
public class FolderPickerService {

    /** 等待用户选完的最长时间（秒）：可能要翻很久目录 */
    private static final long WAIT_SECONDS = 300;

    /** PowerShell 脚本：编译一段 C# 调用 IFileDialog，输出 'OK:<path>' 或 'CANCEL' */
    private static final String PS_SCRIPT = """
            $ErrorActionPreference = 'Stop'
            $OutputEncoding = [System.Text.Encoding]::UTF8
            $ProgressPreference = 'SilentlyContinue'
            # 禁 PowerShell 的 <progress> XML 进度——Application.DoEvents() 在 PS 主机里会触发，
            # 之前没抑制导致 Java 侧把 progress XML 当成 stderr 解析成"异常"
            Add-Type -ReferencedAssemblies System.Windows.Forms,System.Drawing -TypeDefinition @'
            using System;
            using System.Runtime.InteropServices;
            using System.Windows.Forms;

            [ComImport, Guid("42f85136-db7e-439c-85f1-e4075d135fc8"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
            public interface IFileDialog {
                [PreserveSig] int Show(IntPtr parent);
                void SetFileTypes(uint cFileTypes, IntPtr rgFilterSpec);
                void SetFileTypeIndex(uint iFileType);
                void GetFileTypeIndex(out uint piFileType);
                void Advise(IntPtr pfde, out uint pdwCookie);
                void Unadvise(uint dwCookie);
                void SetOptions(uint fos);
                void GetOptions(out uint pfos);
                void SetDefaultFolder(IShellItem psi);
                void SetFolder(IShellItem psi);
                void GetFolder(out IShellItem ppsi);
                void GetCurrentSelection(out IShellItem ppsi);
                void SetFileName([MarshalAs(UnmanagedType.LPWStr)] string name);
                void GetFileName([MarshalAs(UnmanagedType.LPWStr)] out string name);
                void SetTitle([MarshalAs(UnmanagedType.LPWStr)] string title);
                void SetOkButtonLabel([MarshalAs(UnmanagedType.LPWStr)] string text);
                void SetFileNameLabel([MarshalAs(UnmanagedType.LPWStr)] string label);
                void GetResult(out IShellItem ppsi);
                void AddPlace(IShellItem psi, int fdap);
                void SetDefaultExtension([MarshalAs(UnmanagedType.LPWStr)] string ext);
                void Close(int hr);
                void SetClientGuid(ref Guid guid);
                void ClearClientData();
                void SetFilter(IntPtr filter);
            }

            [ComImport, Guid("43826d1e-e718-42ee-bc55-a1e261c37bfe"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
            public interface IShellItem {
                void BindToHandler(IntPtr pbc, ref Guid bhid, ref Guid riid, out IntPtr ppv);
                void GetParent(out IShellItem ppsi);
                void GetDisplayName(uint sigdnName, [MarshalAs(UnmanagedType.LPWStr)] out string ppszName);
                void GetAttributes(uint sfgaoMask, out uint psfgaoAttribs);
                void Compare(IShellItem psi, uint hint, out int piOrder);
            }

            public static class NativeFolderPicker {
                [DllImport("ole32.dll", CharSet = CharSet.Unicode, PreserveSig = false)]
                static extern void CoCreateInstance(ref Guid clsid, IntPtr outer, uint clsctx, ref Guid iid, [MarshalAs(UnmanagedType.Interface)] out object ppv);

                // 让窗口强制置顶到最前——Windows 10/11 对没有前台权限的进程会拒绝 SetForegroundWindow，
                // 所以先用 AttachThreadInput 抢占前台线程，再 BringWindowToTop，最后 Show/SwitchToThisWindow
                [DllImport("user32.dll")] static extern bool SetForegroundWindow(IntPtr hWnd);
                [DllImport("user32.dll")] static extern bool BringWindowToTop(IntPtr hWnd);
                [DllImport("user32.dll")] static extern bool ShowWindow(IntPtr hWnd, int nCmdShow);
                [DllImport("user32.dll")] static extern IntPtr GetForegroundWindow();
                [DllImport("user32.dll")] static extern uint GetWindowThreadProcessId(IntPtr hWnd, out uint lpdwProcessId);
                [DllImport("user32.dll")] static extern bool AttachThreadInput(uint idAttach, uint idAttachTo, bool fAttach);
                [DllImport("kernel32.dll")] static extern uint GetCurrentThreadId();
                [DllImport("user32.dll")] static extern bool AllowSetForegroundWindow(int dwProcessId);

                // DPI 相关：powershell.exe 默认 DPI 不感知，系统会把窗口按位图拉伸，
                // 在 125%/150% 缩放下整个对话框发虚；浏览器是 DPI 感知的，所以上传窗口清晰。
                [DllImport("user32.dll")] static extern bool SetProcessDpiAwarenessContext(IntPtr value);
                [DllImport("shcore.dll")] static extern int SetProcessDpiAwareness(int value);
                [DllImport("user32.dll")] static extern bool SetProcessDPIAware();

                const uint CLSCTX_INPROC_SERVER = 1;
                const uint FOS_PICKFOLDERS = 0x20;
                const uint FOS_FORCEFILESYSTEM = 0x40;
                const uint FOS_PATHMUSTEXIST = 0x800;
                const uint FOS_NOCHANGEDIR = 0x8;
                const uint SIGDN_FILESYSPATH = 0x80058000;
                const int ERROR_CANCELLED = unchecked((int)0x800704C7);
                const int SW_SHOW = 5;

                static bool dpiReady;

                /** 必须在创建任何窗口之前执行，否则设置无效；依次尝试新 API 到旧 API */
                static void EnableDpiAwareness() {
                    if (dpiReady) return;
                    dpiReady = true;
                    try { if (SetProcessDpiAwarenessContext(new IntPtr(-4))) return; } catch { }
                    try { if (SetProcessDpiAwareness(2) == 0) return; } catch { }
                    try { SetProcessDPIAware(); } catch { }
                }

                /** 把窗口强制置顶：AttachThreadInput 拿前台焦点 → Show/BringTop → SetForeground */
                static void ForceTopmost(IntPtr hwnd) {
                    uint discard;
                    uint foregroundThread = GetWindowThreadProcessId(GetForegroundWindow(), out discard);
                    uint myThread = GetCurrentThreadId();
                    bool attached = false;
                    if (foregroundThread != 0 && foregroundThread != myThread) {
                        attached = AttachThreadInput(foregroundThread, myThread, true);
                    }
                    try {
                        ShowWindow(hwnd, SW_SHOW);
                        BringWindowToTop(hwnd);
                        SetForegroundWindow(hwnd);
                    } finally {
                        if (attached) AttachThreadInput(foregroundThread, myThread, false);
                    }
                }

                public static string Pick(string title) {
                    EnableDpiAwareness();

                    Guid clsid = new Guid("DC1C5A9C-E88A-4DDE-A5A1-60F82A20AEF7");
                    Guid iid = new Guid("42f85136-db7e-439c-85f1-e4075d135fc8");
                    object obj;
                    CoCreateInstance(ref clsid, IntPtr.Zero, CLSCTX_INPROC_SERVER, ref iid, out obj);
                    IFileDialog dialog = (IFileDialog)obj;

                    uint options;
                    dialog.GetOptions(out options);
                    dialog.SetOptions(options | FOS_PICKFOLDERS | FOS_FORCEFILESYSTEM | FOS_PATHMUSTEXIST | FOS_NOCHANGEDIR);
                    dialog.SetTitle(title);
                    dialog.SetOkButtonLabel("选择此文件夹");

                    // 对话框需要一个所有者窗口才能稳定地显示在最前面：
                    // 创建一个隐藏但可见的 Form（ShowInTaskbar=false, TopMost=true），
                    // 先激活它 → AttachThreadInput 拿前台 → 再 Show 对话框，确保它不会被浏览器/IDE 挡住
                    Form owner = new Form();
                    owner.TopMost = true;
                    owner.ShowInTaskbar = false;
                    owner.FormBorderStyle = FormBorderStyle.None;
                    owner.StartPosition = FormStartPosition.Manual;
                    owner.Location = new System.Drawing.Point(-32000, -32000);
                    owner.Size = new System.Drawing.Size(1, 1);
                    owner.Opacity = 0;
                    try {
                        owner.Show();
                        Application.DoEvents();
                        ForceTopmost(owner.Handle);
                        Application.DoEvents();
                        int hr = dialog.Show(owner.Handle);
                        if (hr == ERROR_CANCELLED) return null;
                        if (hr != 0) Marshal.ThrowExceptionForHR(hr);

                        IShellItem item;
                        dialog.GetResult(out item);
                        if (item == null) return null;
                        string path;
                        item.GetDisplayName(SIGDN_FILESYSPATH, out path);
                        return path;
                    } finally {
                        owner.Close();
                        Marshal.ReleaseComObject(dialog);
                    }
                }
            }
            '@
            try {
                $picked = [NativeFolderPicker]::Pick('选择工作区文件夹')
                if ([string]::IsNullOrEmpty($picked)) { [Console]::Out.Write('CANCEL') }
                else { [Console]::Out.Write('OK:' + $picked) }
                exit 0
            } catch {
                [Console]::Out.Write('ERR:' + $_.Exception.Message)
                exit 1
            }
            """;

    /**
     * 弹出 Windows 原生文件夹选择窗口，阻塞直到用户选完或关闭。
     * @return 选中的绝对路径；用户取消返回 null
     */
    public String pickDirectory() {
        if (!isWindows()) {
            throw new BusinessException("原生文件夹选择窗口只支持 Windows，请手动填写路径");
        }
        if (!java.awt.GraphicsEnvironment.isHeadless()) {
            // 有图形界面才可能弹窗；headless 在 AgentkeyApplication 里已通过 setHeadless(false) 关闭
            log.debug("图形环境可用，开始弹出系统文件夹选择窗口");
        } else {
            throw new BusinessException("当前运行环境没有图形界面（headless），请手动填写路径");
        }

        Process process;
        try {
            // -EncodedCommand 用 base64(UTF-16LE) 传脚本：不写临时 .ps1（避免执行策略拦截），
            // 也绕开多层引号转义
            String encoded = Base64.getEncoder()
                    .encodeToString(PS_SCRIPT.getBytes(StandardCharsets.UTF_16LE));
            ProcessBuilder pb = new ProcessBuilder(
                    powershellPath(), "-NoProfile", "-NonInteractive", "-STA", "-WindowStyle", "Hidden",
                    "-EncodedCommand", encoded);
            pb.redirectErrorStream(true);
            process = pb.start();
            process.getOutputStream().close();
        } catch (IOException e) {
            throw new BusinessException("无法启动系统文件夹选择窗口：" + e.getMessage());
        }

        StringBuilder out = new StringBuilder();
        Thread reader = new Thread(() -> {
            try (InputStream in = process.getInputStream()) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) != -1) {
                    out.append(new String(buf, 0, n, StandardCharsets.UTF_8));
                }
            } catch (IOException e) {
                log.debug("读取选择窗口输出失败: {}", e.getMessage());
            }
        }, "folder-picker-output");
        reader.setDaemon(true);
        reader.start();

        try {
            if (!process.waitFor(WAIT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new BusinessException("等待选择文件夹超时，请重试");
            }
            reader.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new BusinessException("选择文件夹被中断");
        }

        String result = out.toString().trim();
        if (result.startsWith("ERR:")) {
            throw new BusinessException("无法打开系统文件夹窗口：" + result.substring(4).trim()
                    + "，请手动填写路径");
        }
        if (result.isEmpty() || "CANCEL".equals(result)) {
            return null;
        }
        if (!result.startsWith("OK:")) {
            throw new BusinessException("系统文件夹窗口返回异常：" + abbreviate(result));
        }
        String path = result.substring(3).trim();
        log.info("系统文件夹选择结果: {}", path);
        return path.isEmpty() ? null : path;
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    /** 优先用 System32 下的 powershell.exe，避免 PATH 被裁剪时找不到 */
    private String powershellPath() {
        String root = System.getenv("SystemRoot");
        if (root != null && !root.isBlank()) {
            java.nio.file.Path p = java.nio.file.Paths.get(root,
                    "System32", "WindowsPowerShell", "v1.0", "powershell.exe");
            if (java.nio.file.Files.isRegularFile(p)) {
                return p.toString();
            }
        }
        return "powershell.exe";
    }

    private String abbreviate(String s) {
        return s.length() > 200 ? s.substring(0, 200) + "…" : s;
    }
}
