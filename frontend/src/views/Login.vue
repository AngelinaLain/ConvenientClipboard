<script setup lang="ts">
import {computed, onMounted, onUnmounted, ref, watch} from 'vue'
import {useRoute, useRouter} from 'vue-router'
import {ArrowRight, ShieldCheck} from 'lucide-vue-next'
import {authenticate, logout, refreshPublicConfig, session} from '../api'

const props = defineProps<{ admin?: boolean }>();
const route = useRoute();
const router = useRouter();
const mode = ref<'login' | 'register'>('login');
const username = ref('');
const password = ref('');
const busy = ref(false);
const error = ref('')
const title = computed(() => props.admin ? '管理后台登录' : mode.value === 'login' ? '欢迎回来' : '创建你的账号')
const passwordReset = computed(() => route.query.reauth === 'password-reset')

watch(() => session.config.registrationEnabled, enabled => {
  if (!enabled) mode.value = 'login'
}, {flush: 'sync'})

async function refreshConfig() {
  try {
    await refreshPublicConfig()
  } catch (e) {
    error.value = `无法读取注册设置：${(e as Error).message}`
  }
}
onMounted(() => {
  void refreshConfig()
  window.addEventListener('focus', refreshConfig)
})
onUnmounted(() => window.removeEventListener('focus', refreshConfig))

async function submit() {
  if (busy.value) return;
  busy.value = true;
  error.value = '';
  try {
    const requestedMode = props.admin ? 'login' : mode.value;
    if (requestedMode === 'register' && !(await refreshPublicConfig()).registrationEnabled)
      throw new Error('当前已关闭注册，请使用已有账号登录或联系管理员');
    await authenticate(requestedMode, username.value.trim(), password.value);
    if (props.admin && session.user?.role !== 'ADMIN') {
      await logout();
      throw new Error('此账号没有管理员权限，请使用管理员账号')
    }
    ;const next = typeof route.query.next === 'string' && route.query.next.startsWith('/') && !route.query.next.startsWith('//') ? route.query.next : props.admin ? '/admin' : '/files';
    await router.push(next)
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    busy.value = false
  }
}
</script>
<template>
  <div class="auth-page">
    <section class="auth-card">
      <div class="eyebrow">
<!--        <ShieldCheck :size="16"/>-->
        {{ admin ? '仅限管理员访问' : '' }}
      </div>
      <h1>{{ title }}</h1>
      <p class="muted">{{ admin ? '登录以管理文件、存储与系统配置。' : '登录后可管理上传的文件，并选择永久保存。' }}</p>
      <div v-if="!admin && session.config.registrationEnabled" class="tab-bar">
        <button :class="{ active: mode === 'login' }" @click="mode = 'login'; error = ''">登录</button>
        <button :class="{ active: mode === 'register' }" @click="mode = 'register'; error = ''">注册</button>
      </div>
      <p v-if="!admin && !session.config.registrationEnabled" class="notice info" role="status">当前已关闭注册，请使用已有账号登录或联系管理员。</p>
      <div v-if="passwordReset" class="notice success" role="status">密码已重置，请使用新密码重新登录。</div>
      <form @submit.prevent="submit"><label class="field">用户名<input v-model="username" required
                                                                       autocomplete="username" minlength="3"
                                                                       maxlength="32"
                                                                       :pattern="mode === 'register' ? '[A-Za-z0-9_]+' : undefined"
                                                                       placeholder="输入用户名"></label><label
          class="field">密码<input v-model="password" type="password" required
                                   :autocomplete="mode === 'register' ? 'new-password' : 'current-password'"
                                   :minlength="mode === 'register' ? 8 : 1" maxlength="72"
                                   :placeholder="mode === 'register' ? '至少 8 位字符' : '输入密码'"></label>
        <p v-if="mode === 'register'" class="field-help">用户名使用 3–32 位字母、数字或下划线。密码至少 8 位，最多 72 个
          UTF-8 字节。</p>
        <p v-if="error" class="notice error" role="alert">{{ error }}</p>
        <button class="button button-primary full-width" :disabled="busy">
          {{ busy ? '正在处理…' : mode === 'register' && !admin ? '创建账号' : '登录' }}
          <ArrowRight :size="17"/>
        </button>
      </form>
      <RouterLink to="/" class="auth-back">返回上传首页</RouterLink>
    </section>
  </div>
</template>
