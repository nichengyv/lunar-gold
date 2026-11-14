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

const app = createApp(App);
setExperienceDiagnosticsAccount(localStorage.getItem('username') || '');
app.use(createPinia());
app.use(router);
app.use(ElementPlus);
initializeExperienceDiagnostics(app);
app.mount('#app');
