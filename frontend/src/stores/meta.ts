import { defineStore } from 'pinia';
import * as metaApi from '../api/meta';
import type { Capability, MetaInfo } from '../types/api';

/**
 * 部署能力 store：应用启动时拉取一次 /api/meta，之后所有入口只按能力渲染。
 * 拉取失败时不隐藏任何入口（后端仍会按能力门控返回 404），避免网络抖动导致整站不可用。
 */
export const useMetaStore = defineStore('meta', {
  state: () => ({
    info: null as MetaInfo | null,
    loaded: false,
    loading: null as Promise<MetaInfo | null> | null
  }),
  getters: {
    mode: (state) => state.info?.mode ?? null,
    downloadEnabled: (state) => state.info?.download.enabled ?? false,
    latestDesktopVersion: (state) => state.info?.download.latestVersion ?? null
  },
  actions: {
    /** 幂等加载：并发调用复用同一个请求，成功后不再重复拉取 */
    async load(): Promise<MetaInfo | null> {
      if (this.loaded) return this.info;
      if (this.loading) return this.loading;
      this.loading = metaApi
        .getMeta()
        .then((info) => {
          this.info = info;
          return info;
        })
        .catch(() => null)
        .finally(() => {
          this.loaded = true;
          this.loading = null;
        });
      return this.loading;
    },
    has(capability: Capability): boolean {
      if (!this.info) return true;
      return this.info.capabilities.includes(capability);
    }
  }
});
