import { createApp } from 'vue';
import { createPinia } from 'pinia';
import ElementPlus from 'element-plus';
import 'element-plus/dist/index.css';
import 'element-plus/theme-chalk/dark/css-vars.css';
import App from './App.vue';
import router from './router';
import './styles/tokens.css';
import './styles/style.css';
import { initializeExperienceDiagnostics, setExperienceDiagnosticsAccount } from './utils/experienceDiagnostics';
import { useThemeStore } from './stores/theme';

const app = createApp(App);
setExperienceDiagnosticsAccount(localStorage.getItem('username') || '');
const pinia = createPinia();
app.use(pinia);
app.use(router);
app.use(ElementPlus);
// P5-10-4：挂载前写入主题与减少动效属性，避免首屏动画闪烁
useThemeStore(pinia).init();
initializeExperienceDiagnostics(app);
app.mount('#app');
