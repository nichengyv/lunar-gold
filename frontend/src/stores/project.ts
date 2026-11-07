import { defineStore } from 'pinia';
import * as projectApi from '../api/project';
import * as workspaceApi from '../api/workspace';
import type { ProjectInfo } from '../types/api';

const STORAGE_KEY = 'currentProjectId';

/**
 * 项目（工作区）store：资料与会话都归属于当前项目。
 * 用户只需要选「项目」，不需要理解 RAG / 知识库这些工程概念。
 */
export const useProjectStore = defineStore('project', {
  state: () => {
    const saved = Number(localStorage.getItem(STORAGE_KEY));
    return {
      projects: [] as ProjectInfo[],
      currentProjectId: saved > 0 ? saved : (null as number | null)
    };
  },

  getters: {
    currentProject(state): ProjectInfo | undefined {
      return state.projects.find((p) => p.id === state.currentProjectId);
    }
  },

  actions: {
    /** 拉取项目列表；本地记住的项目已不存在时回落到默认项目 */
    async load() {
      this.projects = await projectApi.listProjects();
      const exists = this.projects.some((p) => p.id === this.currentProjectId);
      if (!exists) {
        const fallback = this.projects.find((p) => p.defaultFlag) || this.projects[0];
        this.setCurrent(fallback ? fallback.id : null);
      }
    },

    setCurrent(id: number | null) {
      this.currentProjectId = id;
      if (id == null) {
        localStorage.removeItem(STORAGE_KEY);
      } else {
        localStorage.setItem(STORAGE_KEY, String(id));
      }
    },

    async create(name: string) {
      const created = await projectApi.createProject(name);
      await this.load();
      this.setCurrent(created.id);
      return created;
    },

    /** 保存当前项目的自定义指令；空字符串 = 清空 */
    async updateInstruction(instruction: string) {
      if (!this.currentProjectId) return;
      const updated = await projectApi.updateProjectInstruction(this.currentProjectId, instruction);
      //更新本地缓存，避免重新拉一次列表
      const idx = this.projects.findIndex((p) => p.id === updated.id);
      if (idx >= 0) this.projects[idx] = updated;
      return updated;
    },

    /** 设置当前项目默认工作权限；空字符串 = 取消设置（新会话按可执行处理） */
    async updateDefaultPermission(permission: string) {
      if (!this.currentProjectId) return;
      const updated = await projectApi.updateProjectDefaultPermission(this.currentProjectId, permission);
      const idx = this.projects.findIndex((p) => p.id === updated.id);
      if (idx >= 0) this.projects[idx] = updated;
      return updated;
    },

    /** 删除工作区：删除后重新拉列表，当前工作区被删时自动回落到默认工作区 */
    async remove(id: number) {
      await projectApi.deleteProject(id);
      await this.load();
    },

    /** 绑定工作区磁盘文件夹 */
    async setWorkspacePath(path: string) {
      if (!this.currentProjectId) return;
      await workspaceApi.setWorkspacePath(this.currentProjectId, path);
      // 更新本地缓存
      const idx = this.projects.findIndex((p) => p.id === this.currentProjectId);
      if (idx >= 0) this.projects[idx].workspacePath = path;
    }
  }
});
