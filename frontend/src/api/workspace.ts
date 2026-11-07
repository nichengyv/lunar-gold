import http, { unwrap } from './http';
import type { ApiResponse, FileNode } from '../types/api';

/** 绑定工作区文件夹 */
export function setWorkspacePath(projectId: number, path: string): Promise<unknown> {
  return http
    .post<never, ApiResponse<unknown>>(`/api/projects/${projectId}/workspace/set-path`, { path })
    .then(unwrap);
}

/**
 * 弹出系统原生的文件夹选择窗口，返回选中目录的绝对路径；取消返回 null。
 * 后端会一直等到窗口关闭才响应，所以这里必须关掉 axios 的 30s 超时。
 */
export function pickFolder(): Promise<string | null> {
  return http
    .post<never, ApiResponse<string | null>>('/api/workspace/pick-folder', null, { timeout: 0 })
    .then(unwrap);
}

/** 列出工作区目录 */
export function listWorkspaceFiles(projectId: number, path = ''): Promise<FileNode[]> {
  return http
    .get<never, ApiResponse<FileNode[]>>(`/api/projects/${projectId}/workspace/files`, {
      params: { path }
    })
    .then(unwrap);
}

/** 读取工作区文件内容 */
export function readWorkspaceFile(projectId: number, path: string): Promise<string> {
  return http
    .get<never, ApiResponse<string>>(`/api/projects/${projectId}/workspace/file`, {
      params: { path }
    })
    .then(unwrap);
}

/** 写入/覆盖工作区文件（编辑器保存用） */
export function writeWorkspaceFile(projectId: number, path: string, content: string): Promise<unknown> {
  return http
    .post<never, ApiResponse<unknown>>(`/api/projects/${projectId}/workspace/file`, { path, content })
    .then(unwrap);
}

/** 新建空文件或空目录（资源管理器用） */
export function createWorkspaceEntry(
  projectId: number,
  path: string,
  directory: boolean
): Promise<unknown> {
  return http
    .post<never, ApiResponse<unknown>>(`/api/projects/${projectId}/workspace/create`, { path, directory })
    .then(unwrap);
}

/** 重命名/移动工作区内的文件或目录 */
export function renameWorkspaceEntry(projectId: number, from: string, to: string): Promise<unknown> {
  return http
    .post<never, ApiResponse<unknown>>(`/api/projects/${projectId}/workspace/rename`, { from, to })
    .then(unwrap);
}

/** 删除工作区内的文件或目录（目录递归删除） */
export function deleteWorkspaceEntry(projectId: number, path: string): Promise<unknown> {
  return http
    .delete<never, ApiResponse<unknown>>(`/api/projects/${projectId}/workspace/entry`, { params: { path } })
    .then(unwrap);
}

/** 按文件名搜索工作区文件（@ 引用选择器用，最多 15 条；空关键字返回空列表） */
export function searchWorkspaceFiles(projectId: number, q: string): Promise<FileNode[]> {
  return http
    .get<never, ApiResponse<FileNode[]>>(`/api/projects/${projectId}/workspace/search-files`, {
      params: { q }
    })
    .then(unwrap);
}
