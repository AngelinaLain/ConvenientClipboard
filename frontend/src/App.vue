<script setup lang="ts">
import {ref} from 'vue'
import {useRouter} from 'vue-router'
import {ArrowUpRight, Layers2, LogOut, ShieldCheck} from 'lucide-vue-next'
import {logout, session} from './api'

const router = useRouter();
const error = ref('');
const busy = ref(false)

async function signOut() {
  busy.value = true;
  try {
    await logout();
    await router.push('/')
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    busy.value = false
  }
}
</script>
<template>
  <div class="site-shell">
    <header class="site-header">
      <RouterLink to="/" class="brand"><span class="brand-mark"><Layers2 :size="23"/></span><span>一个文件分享页面<small>angelina-lain</small></span>
      </RouterLink>
      <nav aria-label="主导航">
        <RouterLink to="/" class="nav-link">上传文件</RouterLink>
        <RouterLink v-if="session.user" to="/files" class="nav-link">我的文件</RouterLink>
        <RouterLink v-if="session.user?.role === 'ADMIN'" to="/admin" class="nav-link admin-link">
          <ShieldCheck :size="15"/>
          管理后台
        </RouterLink>
        <template v-if="session.user"><span class="username">{{ session.user.username }}</span>
          <button class="icon-button" aria-label="退出登录" :disabled="busy" @click="signOut">
            <LogOut :size="18"/>
          </button>
        </template>
        <RouterLink v-else to="/login" class="button button-small button-dark">登录 / 注册
          <ArrowUpRight :size="15"/>
        </RouterLink>
      </nav>
    </header>
    <div v-if="error" class="notice error global-notice" role="alert">{{ error }}
      <button class="text-button" @click="error = ''">关闭</button>
    </div>
    <main>
      <RouterView/>
    </main>
    <footer class="site-footer"><span>文件默认保留 {{
        session.config.retentionDays
      }} 天<RouterLink to="/admin/login">管理入口<ArrowUpRight :size="12"/></RouterLink></span></footer>
  </div>
</template>
