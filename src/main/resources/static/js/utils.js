/**
 * 通用工具函数
 */

/**
 * HTML 转义，防止 XSS
 */
export function escapeHtml(text) {
    if (!text) return '';
    const div = document.createElement('div');
    div.textContent = text;
    return div.innerHTML;
}

/**
 * 主题管理
 */
export const Theme = {
    /**
     * 设置主题
     * @param {boolean} isDark 
     */
    set(isDark) {
        document.documentElement.setAttribute('data-theme', isDark ? 'dark' : 'light');
        const hljsTheme = document.getElementById('hljs-theme');
        if (hljsTheme) {
            hljsTheme.href = isDark
                ? 'https://cdnjs.cloudflare.com/ajax/libs/highlight.js/11.9.0/styles/github-dark.min.css'
                : 'https://cdnjs.cloudflare.com/ajax/libs/highlight.js/11.9.0/styles/github.min.css';
        }
        const toggle = document.getElementById('darkModeToggle');
        if (toggle) toggle.checked = isDark;
        localStorage.setItem('theme', isDark ? 'dark' : 'light');
    },

    /**
     * 切换主题
     */
    toggle() {
        const isDark = document.documentElement.getAttribute('data-theme') !== 'dark';
        this.set(isDark);
        return isDark;
    },

    /**
     * 初始化主题（从 localStorage 读取）
     */
    init() {
        const saved = localStorage.getItem('theme') || 'light';
        this.set(saved === 'dark');
    }
};

/**
 * RSA 加密密码
 * @param {string} password 明文密码
 * @param {string} baseUrl  API 基础地址
 * @returns {Promise<string>} 加密后的 Base64
 */
export async function encryptPassword(password, baseUrl) {//这里的JSEncrypt
    //向后端发起请求拿取公钥,fetch就是前端访问后端的方法
    const resp = await fetch(`${baseUrl}/api/auth/public-key`);
    if (!resp.ok) throw new Error('获取公钥失败');
    const result = await resp.json();
    //兼容两种格式，取出公钥
    const publicKey = result.data ? result.data.publicKey : result.publicKey;

    //用JSEncrypt库加密
    const encryptor = new JSEncrypt();//创建加密器
    encryptor.setPublicKey(publicKey);//设置后端给的密钥
    const encrypted = encryptor.encrypt(password);//用公钥加密明文密码，输出promise（内部数据string类型，base64）
    if (!encrypted) throw new Error('密码加密失败');
    return encrypted;
}

/**
 * 格式化日期时间（简化版）
 * @param {string} isoString 
 * @returns {string}
 */
export function formatDateTime(isoString) {
    if (!isoString) return '-';
    return isoString.substring(0, 16).replace('T', ' ');
}

/**
 * 复制文本到剪贴板
 * @param {string} text 
 * @returns {Promise<void>}
 */
export async function copyToClipboard(text) {
    await navigator.clipboard.writeText(text);
}
