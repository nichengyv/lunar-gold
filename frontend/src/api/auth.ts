import http, { unwrap } from './http';
import type { ApiResponse, UserInfo } from '../types/api';

export function getPublicKey(): Promise<string> {
  return http.get<never, ApiResponse<{ publicKey: string } | { publicKey: string }>>('/api/auth/public-key')
    .then((resp) => {
      // 兼容信封与裸对象两种返回格式
      const data = resp as unknown as { data?: { publicKey?: string }; publicKey?: string };
      return data.data?.publicKey ?? data.publicKey ?? '';
    });
}

export function login(body: { username: string; encryptedPassword: string }): Promise<ApiResponse<{ token: string }>> {
  return http.post<never, ApiResponse<{ token: string }>>('/api/auth/login', body);
}

export function register(body: { username: string; encryptedPassword: string; email: string; emailCode: string }): Promise<ApiResponse<void>> {
  return http.post<never, ApiResponse<void>>('/api/auth/register', body);
}

/** 发送邮箱验证码：scene=REGISTER 注册，RESET 找回密码 */
export function sendEmailCode(body: { email: string; scene: 'REGISTER' | 'RESET' }): Promise<ApiResponse<void>> {
  return http.post<never, ApiResponse<void>>('/api/auth/email-code', body);
}

export function resetPassword(body: { email: string; emailCode: string; encryptedPassword: string }): Promise<ApiResponse<void>> {
  return http.post<never, ApiResponse<void>>('/api/auth/reset-password', body);
}

export function logout(): Promise<ApiResponse<void>> {
  return http.post<never, ApiResponse<void>>('/api/auth/logout');
}

export function getMe(): Promise<UserInfo> {
  return http.get<never, ApiResponse<UserInfo>>('/api/auth/me').then(unwrap);
}

export function changePassword(body: { oldPassword: string; newPassword: string }): Promise<ApiResponse<void>> {
  return http.post<never, ApiResponse<void>>('/api/auth/change-password', body);
}
