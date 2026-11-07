import http, { unwrap } from './http';
import type { ApiResponse, KeyInfo } from '../types/api';

export function listKeys(): Promise<KeyInfo[]> {
  return http.get<never, ApiResponse<KeyInfo[]>>('/api/keys').then(unwrap);
}

export function saveKey(body: { provider: string; apiKey: string; alias: string }): Promise<void> {
  return http.post<never, ApiResponse<void>>('/api/keys', body).then(unwrap);
}

export function testKey(body: { provider: string; apiKey: string }): Promise<void> {
  return http.post<never, ApiResponse<void>>('/api/keys/test', body).then(unwrap);
}

export function deleteKey(id: number): Promise<void> {
  return http.delete<never, ApiResponse<void>>(`/api/keys/${id}`).then(unwrap);
}
