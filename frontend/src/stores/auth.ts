import { defineStore } from 'pinia';
import * as authApi from '../api/auth';
import * as desktopApi from '../api/desktop';
import { unwrap } from '../api/http';
import { encryptPassword } from '../utils/crypto';

const TOKEN_KEY = 'token';
const USERNAME_KEY = 'username';

export const useAuthStore = defineStore('auth', {
  state: () => ({
    token: localStorage.getItem(TOKEN_KEY) || '',
    username: localStorage.getItem(USERNAME_KEY) || ''
  }),
  getters: {
    isLoggedIn: (state) => !!state.token
  },
  actions: {
    async login(username: string, password: string) {
      const encrypted = await encryptPassword(password);
      const resp = await authApi.login({ username, encryptedPassword: encrypted });
      const data = unwrap(resp);
      this.token = data.token;
      this.username = username;
      localStorage.setItem(TOKEN_KEY, data.token);
      localStorage.setItem(USERNAME_KEY, username);
    },
    async register(username: string, password: string, email: string, emailCode: string) {
      const encrypted = await encryptPassword(password);
      await authApi.register({ username, encryptedPassword: encrypted, email, emailCode });
    },
    /** 发送邮箱验证码（注册 / 找回密码共用） */
    async sendEmailCode(email: string, scene: 'REGISTER' | 'RESET') {
      await authApi.sendEmailCode({ email, scene });
    },
    /**
     * 桌面端自动换取本地令牌：单用户模式没有登录页，
     * 浏览器开发模式下由后端 /api/desktop/token 引导（桌面壳则自己注入）。
     * 失败时返回 false，让路由继续走原有登录分支。
     */
    async bootstrapDesktopToken(): Promise<boolean> {
      try {
        const token = await desktopApi.getDesktopToken();
        if (!token) return false;
        this.token = token;
        this.username = 'local';
        localStorage.setItem(TOKEN_KEY, token);
        localStorage.setItem(USERNAME_KEY, 'local');
        return true;
      } catch {
        return false;
      }
    },
    /** 邮箱验证码找回密码 */
    async resetPassword(email: string, emailCode: string, newPassword: string) {
      const encrypted = await encryptPassword(newPassword);
      await authApi.resetPassword({ email, emailCode, encryptedPassword: encrypted });
    },
    /** 登出：先调后端撤销 token（失败不阻塞），再清本地凭证 */
    async logout() {
      try {
        await authApi.logout();
      } catch {
        /* token 已失效等场景忽略 */
      }
      this.clearLocal();
    },
    /** 仅清本地凭证（401 时由拦截器调用，此时调后端无意义） */
    clearLocal() {
      this.token = '';
      this.username = '';
      localStorage.removeItem(TOKEN_KEY);
      localStorage.removeItem(USERNAME_KEY);
    }
  }
});
