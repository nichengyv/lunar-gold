<template>
  <div class="auth-overlay">
    <div class="auth-card">
      <h2><Icon name="bot" :size="22" /> AgentKey Pro</h2>
      <div class="input-group">
        <label>用户名</label>
        <input v-model.trim="username" type="text" placeholder="请输入用户名">
      </div>
      <div class="input-group">
        <label>密码</label>
        <input v-model="password" type="password" placeholder="请输入密码" @keyup.enter="submit">
      </div>
      <div v-if="isRegisterMode" class="input-group">
        <label>邮箱</label>
        <input v-model.trim="email" type="email" placeholder="test@example.com">
      </div>
      <button class="btn btn-primary" :disabled="loading" @click="submit">
        {{ isRegisterMode ? '注 册' : '登 录' }}
      </button>
      <button class="btn btn-secondary" @click="toggleMode">
        {{ isRegisterMode ? '已有账号？去登录' : '还没有账号？去注册' }}
      </button>
      <div class="error-msg" :class="{ success: successMsg }">{{ successMsg || errorMsg }}</div>
      <div class="auth-hint">默认账号: testuser2 / Test@1234</div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue';
import { useRouter } from 'vue-router';
import { useAuthStore } from '../stores/auth';
import Icon from '../components/Icon.vue';

const router = useRouter();
const auth = useAuthStore();

const username = ref('');
const password = ref('');
const email = ref('');
const isRegisterMode = ref(false);
const loading = ref(false);
const errorMsg = ref('');
const successMsg = ref('');

function toggleMode() {
  isRegisterMode.value = !isRegisterMode.value;
  errorMsg.value = '';
  successMsg.value = '';
}

async function submit() {
  if (!username.value || !password.value) {
    errorMsg.value = '请输入用户名和密码';
    return;
  }
  loading.value = true;
  errorMsg.value = '';
  successMsg.value = '';
  try {
    if (isRegisterMode.value) {
      const finalEmail = email.value || username.value + '@example.com';
      await auth.register(username.value, password.value, finalEmail);
      successMsg.value = '注册成功，请登录';
      setTimeout(() => {
        isRegisterMode.value = false;
        successMsg.value = '';
      }, 1200);
    } else {
      await auth.login(username.value, password.value);
      router.push('/chat');
    }
  } catch (e) {
    errorMsg.value = (e as Error).message;
  } finally {
    loading.value = false;
  }
}
</script>
