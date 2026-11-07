import http, { unwrap } from './http';
import type { ApiResponse, DocumentInfo } from '../types/api';

export function listDocuments(projectId?: number): Promise<DocumentInfo[]> {
  return http
    .get<never, ApiResponse<DocumentInfo[]>>('/api/knowledge/documents', {
      params: projectId ? { projectId } : undefined
    })
    .then(unwrap);
}

export function uploadDocument(formData: FormData): Promise<{ id: number; message?: string }> {
  return http.post<never, ApiResponse<{ id: number; message?: string }>>('/api/knowledge/upload', formData).then(unwrap);
}

export function deleteDocument(id: number): Promise<void> {
  return http.delete<never, ApiResponse<void>>(`/api/knowledge/documents/${id}`).then(unwrap);
}

/** 重新处理失败文档：后端先清空残留分片和向量，再做一次全量重建 */
export function retryDocument(id: number): Promise<void> {
  return http.post<never, ApiResponse<void>>(`/api/knowledge/documents/${id}/retry`).then(unwrap);
}
