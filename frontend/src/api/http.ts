import axios, { AxiosResponse } from 'axios';
import { ElMessage } from 'element-plus';
import type { ApiResponse } from '../types/api';

/**
 * axios 实例：自动附带 Bearer Token、统一解包 ApiResponse、
 * 401 时清除本地凭证并跳转登录页。
 * 注意：SSE 流式接口不经过 axios，直接用 fetch（见 api/sse.ts）。
 */
const http = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || '',
  timeout: 30000
});

http.interceptors.request.use((config) => {
  const token = localStorage.getItem('token');
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});

http.interceptors.response.use(
  // 统一解包：调用方拿到的直接是 ApiResponse（SSE 流不经过 axios，见 api/sse.ts）
  (resp) => resp.data as unknown as AxiosResponse,
  (error) => {
    const status = error.response?.status;
    if (status === 401) {
      localStorage.removeItem('token');
      localStorage.removeItem('username');
      ElMessage.error('登录已过期，请重新登录');
      // 延迟跳转，让提示先显示出来
      setTimeout(() => {
        window.location.href = '/login';
      }, 600);
    }
    //优先用后端返回的面向用户的文案；拿不到时也别抛 "Network Error" 这类技术词
    const msg: string =
      error.response?.data?.message ||
      (error.code === 'ECONNABORTED'
        ? '等待时间有点久，请稍后再试'
        : '网络连接不太稳定，请检查网络后重试');
    return Promise.reject(new Error(msg));
  }
);

/** 业务码校验：code !== 200 抛异常，成功返回 data */
export function unwrap<T>(resp: ApiResponse<T>): T {
  if (resp.code !== 200) {
    throw new Error(resp.message || '操作没有成功，请稍后再试');
  }
  return resp.data;
}

export default http;
