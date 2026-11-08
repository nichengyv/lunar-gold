import { createRouter, createWebHistory } from 'vue-router';
import { useAuthStore } from '../stores/auth';
import { useMetaStore } from '../stores/meta';
import type { Capability } from '../types/api';

const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/login',
      name: 'login',
      component: () => import('../views/LoginView.vue'),
      meta: { public: true }
    },
    // 下载页公开可见：未登录用户也能了解桌面端能力
    {
      path: '/download',
      name: 'download',
      component: () => import('../views/DownloadView.vue'),
      meta: { public: true, capability: 'DOWNLOAD' }
    },
    {
      path: '/',
      component: () => import('../layouts/AppLayout.vue'),
      redirect: '/chat',
      children: [
        {
          path: 'chat',
          name: 'chat',
          component: () => import('../views/ChatView.vue'),
          meta: { title: '对话', capability: 'CHAT' }
        },
        {
          path: 'knowledge',
          name: 'knowledge',
          component: () => import('../views/KnowledgeView.vue'),
          meta: { title: '我的资料', capability: 'KNOWLEDGE' }
        },
        { path: 'keys', name: 'keys', component: () => import('../views/KeysView.vue'), meta: { title: '模型服务' } },
        { path: 'profile', name: 'profile', component: () => import('../views/ProfileView.vue'), meta: { title: '账号与安全' } },
        { path: 'settings', name: 'settings', component: () => import('../views/SettingsView.vue'), meta: { title: '设置' } }
      ]
    },
    { path: '/:pathMatch(.*)*', redirect: '/chat' }
  ]
});

router.beforeEach(async (to) => {
  const auth = useAuthStore();
  const meta = useMetaStore();
  // 能力声明决定入口是否可达；meta 是公开接口，登录前也要先拿到
  await meta.load();
  // 桌面端为本地单用户：没有登录页，先自动换取本地令牌
  if (meta.mode === 'DESKTOP' && !auth.isLoggedIn) {
    await auth.bootstrapDesktopToken();
  }
  if (!to.meta.public && !auth.isLoggedIn) {
    return { name: 'login' };
  }
  if (to.name === 'login' && auth.isLoggedIn) {
    return { name: 'chat' };
  }
  const required = to.meta.capability as Capability | undefined;
  if (required && !meta.has(required)) {
    return auth.isLoggedIn ? { name: 'chat' } : { name: 'login' };
  }
  return true;
});

export default router;
