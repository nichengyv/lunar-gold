import http, { unwrap } from './http';
import type { ApiResponse, ProjectInfo } from '../types/api';

export function listProjects(): Promise<ProjectInfo[]> {
  return http.get<never, ApiResponse<ProjectInfo[]>>('/api/projects').then(unwrap);
}

export function createProject(name: string, description?: string): Promise<ProjectInfo> {
  return http.post<never, ApiResponse<ProjectInfo>>('/api/projects', { name, description }).then(unwrap);
}

/** 更新项目自定义指令（空字符串 = 清空） */
export function updateProjectInstruction(id: number, instruction: string): Promise<ProjectInfo> {
  return http
    .patch<never, ApiResponse<ProjectInfo>>(`/api/projects/${id}/instruction`, { instruction })
    .then(unwrap);
}

/** 设置项目默认工作权限（空字符串 = 取消设置），新会话未显式选择时继承 */
export function updateProjectDefaultPermission(id: number, permission: string): Promise<ProjectInfo> {
  return http
    .patch<never, ApiResponse<ProjectInfo>>(`/api/projects/${id}/default-permission`, { permission })
    .then(unwrap);
}

/** 删除工作区：其下会话与资料一并删除，磁盘上的代码文件夹不受影响 */
export function deleteProject(id: number): Promise<void> {
  return http.delete<never, ApiResponse<void>>(`/api/projects/${id}`).then(unwrap);
}
