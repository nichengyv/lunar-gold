<template>
  <div class="auth-overlay">
    <div class="auth-card">
      <h2><Icon name="bot" :size="22" /> AgentKey Pro</h2>

      <div v-if="mode !== 'reset'" class="input-group">
        <label>用户名</label>
        <input v-model.trim="username" type="text" placeholder="请输入用户名">
      </div>

      <div v-if="mode !== 'login'" class="input-group">
        <label>邮箱</label>
        <input v-model.trim="email" type="email" placeholder="test@example.com">
      </div>

      <div v-if="mode !== 'login'" class="input-group">
        <label>邮箱验证码</label>
        <div class="code-row">
          <input v-model.trim="emailCode" type="text" maxlength="6" placeholder="6 位数字验证码">
          <button class="btn btn-secondary code-btn" :disabled="sending || countdown > 0" @click="sendCode">
            {{ countdown > 0 ? `${countdown}s` : '获取验证码' }}
          </button>
        </div>
      </div>

      <div class="input-group">
        <label>{{ mode === 'reset' ? '新密码' : '密码' }}</label>
        <input
          v-model="password"
          type="password"
          :placeholder="mode === 'reset' ? '至少8位，含大小写字母、数字和特殊字符' : '请输入密码'"
          @keyup.enter="submit"
        >
      </div>

      <button class="btn btn-primary" :disabled="loading" @click="submit">
        {{ submitLabel }}
      </button>
      <button v-if="mode !== 'reset'" class="btn btn-secondary" @click="switchMode(mode === 'login' ? 'register' : 'login')">
        {{ mode === 'login' ? '还没有账号？去注册' : '已有账号？去登录' }}
      </button>
      <div class="mode-links">
        <button v-if="mode === 'login'" class="text-btn" @click="switchMode('reset')">忘记密码？</button>
        <button v-if="mode === 'reset'" class="text-btn" @click="switchMode('login')">返回登录</button>
      </div>

      <div class="error-msg" :class="{ success: successMsg }">{{ successMsg || errorMsg }}</div>
      <div class="auth-hint">默认账号: testuser2 / Test@1234</div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onUnmounted, ref } from 'vue';
import { useRouter } from 'vue-router';
import { useAuthStore } from '../stores/auth';
import Icon from '../components/Icon.vue';

type Mode = 'login' | 'register' | 'reset';

const router = useRouter();
const auth = useAuthStore();

const mode = ref<Mode>('login');
const username = ref('');
const password = ref('');
const email = ref('');
const emailCode = ref('');
const loading = ref(false);
const sending = ref(false);
const countdown = ref(0);
const errorMsg = ref('');
const successMsg = ref('');
let timer: number | undefined;

const submitLabel = computed(() => {
  if (mode.value === 'register') return '注 册';
  if (mode.value === 'reset') return '重置密码';
  return '登 录';
});

function switchMode(next: Mode) {
  mode.value = next;
  emailCode.value = '';
  errorMsg.value = '';
  successMsg.value = '';
}

function clearTimer() {
  if (timer !== undefined) {
    window.clearInterval(timer);
    timer = undefined;
  }
}

/** 发送验证码后倒计时 60 秒，与后端同场景同邮箱的冷却时间对齐 */
function startCountdown() {
  clearTimer();
  countdown.value = 60;
  timer = window.setInterval(() => {
    countdown.value -= 1;
    if (countdown.value <= 0) clearTimer();
  }, 1000);
}

async function sendCode() {
  if (!email.value) {
    errorMsg.value = '请先输入邮箱';
    return;
  }
  sending.value = true;
  errorMsg.value = '';
  successMsg.value = '';
  try {
    await auth.sendEmailCode(email.value, mode.value === 'register' ? 'REGISTER' : 'RESET');
    startCountdown();
    // 找回密码不提示邮箱是否存在，避免被用来探测注册账号
    successMsg.value = mode.value === 'register'
      ? '验证码已发送，请查收邮箱'
      : '若该邮箱已注册，验证码已发送，请查收';
  } catch (e) {
    errorMsg.value = (e as Error).message;
  } finally {
    sending.value = false;
  }
}

async function submit() {
  errorMsg.value = '';
  successMsg.value = '';

  if (mode.value === 'reset') {
    if (!email.value || !emailCode.value || !password.value) {
      errorMsg.value = '请填写邮箱、验证码和新密码';
      return;
    }
    loading.value = true;
    try {
      await auth.resetPassword(email.value, emailCode.value, password.value);
      successMsg.value = '密码已重置，请用新密码登录';
      setTimeout(() => {
        password.value = '';
        emailCode.value = '';
        switchMode('login');
      }, 1200);
    } catch (e) {
      errorMsg.value = (e as Error).message;
    } finally {
      loading.value = false;
    }
    return;
  }

  if (!username.value || !password.value) {
    errorMsg.value = '请输入用户名和密码';
    return;
  }
  if (mode.value === 'register' && (!email.value || !emailCode.value)) {
    errorMsg.value = '请填写邮箱和邮箱验证码';
    return;
  }

  loading.value = true;
  try {
    if (mode.value === 'register') {
      await auth.register(username.value, password.value, email.value, emailCode.value);
      successMsg.value = '注册成功，请登录';
      setTimeout(() => {
        password.value = '';
        emailCode.value = '';
        switchMode('login');
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

onUnmounted(clearTimer);
</script>

<style scoped>
.code-row {
    display: flex;
    gap: 8px;
}

.code-row input {
    flex: 1;
    min-width: 0;
}

.code-btn {
    width: auto;
    margin-top: 0;
    padding: 0 14px;
    white-space: nowrap;
    font-size: var(--fs-sm);
}

.code-btn:disabled {
    opacity: 0.6;
    cursor: not-allowed;
}

.mode-links {
    text-align: center;
    margin-top: 10px;
}

.text-btn {
    background: none;
    border: none;
    padding: 0;
    color: var(--text-secondary);
    font-size: var(--fs-sm);
    cursor: pointer;
    text-decoration: underline;
}

.text-btn:hover {
    color: var(--primary);
}
</style>
