import { createRouter, createWebHistory } from 'vue-router';
import { useAuthStore } from '../stores/auth';

const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/login',
      name: 'login',
      component: () => import('../views/LoginView.vue'),
      meta: { public: true }
    },
    {
      path: '/',
      component: () => import('../layouts/AppLayout.vue'),
      redirect: '/chat',
      children: [
        { path: 'chat', name: 'chat', component: () => import('../views/ChatView.vue'), meta: { title: '对话' } },
        { path: 'knowledge', name: 'knowledge', component: () => import('../views/KnowledgeView.vue'), meta: { title: '我的资料' } },
        { path: 'keys', name: 'keys', component: () => import('../views/KeysView.vue'), meta: { title: '模型服务' } },
        { path: 'profile', name: 'profile', component: () => import('../views/ProfileView.vue'), meta: { title: '账号与安全' } },
        { path: 'settings', name: 'settings', component: () => import('../views/SettingsView.vue'), meta: { title: '设置' } }
      ]
    },
    { path: '/:pathMatch(.*)*', redirect: '/chat' }
  ]
});

router.beforeEach((to) => {
  const auth = useAuthStore();
  if (!to.meta.public && !auth.isLoggedIn) {
    return { name: 'login' };
  }
  if (to.name === 'login' && auth.isLoggedIn) {
    return { name: 'chat' };
  }
  return true;
});

export default router;
