'use strict';

/**
 * 桌面壳 preload：只暴露最少窗口控制 API 给渲染层。
 * titleBarStyle: 'hidden' 下原生控制按钮由 titleBarOverlay 自绘，
 * 前端只需查询窗口状态（是否最大化）来更新 UI。
 */
const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('lunarWin', {
  /** 窗口是否处于最大化 */
  isMaximized: () => ipcRenderer.invoke('win:is-maximized'),
  /** 监听最大化状态变化：callback(true/false) */
  onMaximizeChange: (callback) => {
    const handler = (_e, maximized) => callback(maximized);
    ipcRenderer.on('win:maximize-changed', handler);
    return () => ipcRenderer.removeListener('win:maximize-changed', handler);
  }
});
