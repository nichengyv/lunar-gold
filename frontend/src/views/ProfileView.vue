<template>
  <div class="page">
    <div class="page-container narrow">
      <div class="card">
        <h3><Icon name="user" :size="16" /> 个人信息</h3>
        <div class="form-row">
          <label>用户名</label>
          <input v-model="username" type="text" readonly>
        </div>
        <div class="form-row">
          <label>邮箱</label>
          <input v-model="email" type="email">
        </div>
        <button class="btn-small btn-small-primary" @click="onUpdate">保存修改</button>
      </div>

      <div class="card">
        <h3><Icon name="lock" :size="16" /> 修改密码</h3>
        <div class="form-row">
          <label>原密码</label>
          <input v-model="oldPwd" type="password">
        </div>
        <div class="form-row">
          <label>新密码</label>
          <input v-model="newPwd" type="password" placeholder="至少8位，含大小写+数字+特殊字符">
        </div>
        <div class="form-row">
          <label>确认新密码</label>
          <input v-model="confirmPwd" type="password">
        </div>
        <button class="btn-small btn-small-primary" :disabled="changing" @click="onChange">更新密码</button>
        <span class="status-text" :class="{ error: statusIsError }">{{ status }}</span>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
import * as authApi from '../api/auth';
import { encryptPassword } from '../utils/crypto';
import { useAuthStore } from '../stores/auth';
import Icon from '../components/Icon.vue';

const router = useRouter();
const auth = useAuthStore();

const username = ref('');
const email = ref('');
const oldPwd = ref('');
const newPwd = ref('');
const confirmPwd = ref('');
const status = ref('');
const statusIsError = ref(false);
const changing = ref(false);

onMounted(async () => {
  try {
    const me = await authApi.getMe();
    username.value = me.username || '';
    email.value = me.email || '';
  } catch (e) {
    console.error('加载个人信息失败', e);
  }
});

function onUpdate() {
  ElMessage.success('保存成功（演示）');
}

function setStatus(msg: string, isError = false) {
  status.value = msg;
  statusIsError.value = isError;
}

async function onChange() {
  if (!oldPwd.value || !newPwd.value) {
    setStatus('密码不能为空', true);
    return;
  }
  if (newPwd.value !== confirmPwd.value) {
    setStatus('两次输入不一致', true);
    return;
  }
  changing.value = true;
  try {
    // 改密后后端会撤销该用户所有 token，需重新登录
    const oldEnc = await encryptPassword(oldPwd.value);
    const newEnc = await encryptPassword(newPwd.value);
    await authApi.changePassword({ oldPassword: oldEnc, newPassword: newEnc });
    setStatus('修改成功，请重新登录');
    oldPwd.value = '';
    newPwd.value = '';
    confirmPwd.value = '';
    setTimeout(async () => {
      await auth.clearLocal();
      router.push('/login');
    }, 1500);
  } catch (e) {
    setStatus((e as Error).message, true);
  } finally {
    changing.value = false;
  }
}
</script>

<style scoped>
.narrow {
  max-width: 600px;
}
</style>
