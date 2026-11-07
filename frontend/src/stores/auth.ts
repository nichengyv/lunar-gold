import { defineStore } from 'pinia';
import * as authApi from '../api/auth';
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
    async register(username: string, password: string, email: string) {
      const encrypted = await encryptPassword(password);
      await authApi.register({ username, encryptedPassword: encrypted, email });
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
