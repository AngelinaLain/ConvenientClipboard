<script setup lang="ts">
import {computed, onMounted, onUnmounted, watch} from 'vue'
import {useRoute} from 'vue-router'
import {ArrowUpRight, ClipboardCopy, Copy, Link2, Save, Timer} from 'lucide-vue-next'
import {MAX_CLIPBOARD_LENGTH, useClipboard} from '../useClipboard'

const route = useRoute()
const shared = computed(() => typeof route.params.id === 'string')
const {draft, customId, customIdError, entry, loading, saving, expired, error, copying, copyMessage, copyFailed, countdown,
    save, load, reset, copyEntry, checkExpiration, dispose} = useClipboard()
const expiresAt = computed(() => entry.value ? new Date(entry.value.expiresAt).toLocaleTimeString('zh-CN', {hour12: false}) : '')

watch(() => route.params.id, id => {
    if (typeof id === 'string') void load(id)
    else reset()
}, {immediate: true})

onMounted(() => {
    window.addEventListener('focus', checkExpiration)
    window.addEventListener('pageshow', checkExpiration)
    document.addEventListener('visibilitychange', checkExpiration)
})
onUnmounted(() => {
    window.removeEventListener('focus', checkExpiration)
    window.removeEventListener('pageshow', checkExpiration)
    document.removeEventListener('visibilitychange', checkExpiration)
    dispose()
})
</script>

<template>
  <div class="clipboard-page page-width">
    <section class="clipboard-card" aria-labelledby="clipboard-title">
      <div class="clipboard-heading">
        <span class="clipboard-icon"><ClipboardCopy :size="24" :stroke-width="1.6"/></span>
        <div>
          <h1 id="clipboard-title">{{ shared ? '分享的粘贴板' : '粘贴板' }}</h1>
          <p>无需登录，保存后即可通过链接分享文本。</p>
        </div>
      </div>

      <div v-if="loading" class="empty-state" role="status"><span class="spinner"></span>正在读取粘贴板…</div>
      <div v-else-if="expired || (shared && error)" class="empty-state clipboard-unavailable">
        <Timer :size="38" :stroke-width="1.4"/>
        <h2>{{ expired ? '粘贴板已到期' : '无法打开粘贴板' }}</h2>
        <p v-if="expired" role="status">内容已销毁，分享链接已失效。</p>
        <template v-else>
          <p class="inline-error" role="alert">{{ error }}</p>
          <p>链接可能已过期或不存在。</p>
          <button class="text-button" @click="load(String(route.params.id))">重新读取</button>
        </template>
        <RouterLink v-if="shared" to="/zt" class="button button-outline">新建粘贴板</RouterLink>
        <button v-else class="button button-outline" @click="reset">新建粘贴板</button>
      </div>

      <template v-else-if="entry">
        <div class="clipboard-retention"><Timer :size="16"/><span>剩余 <strong>{{ countdown }}</strong> · {{ expiresAt }} 到期销毁</span></div>
        <label class="clipboard-field">分享内容
          <textarea class="clipboard-textarea" :value="entry.content" readonly spellcheck="false" aria-label="分享内容"/>
        </label>
        <div v-if="!shared" class="clipboard-link-result">
          <p class="clipboard-saved" role="status"><Link2 :size="15"/>已保存，分享链接已生成</p>
          <label class="clipboard-field">分享链接
            <input :value="entry.shareUrl" readonly aria-label="分享链接" @focus="($event.target as HTMLInputElement).select()">
          </label>
        </div>
        <p v-if="copyMessage" :class="['clipboard-copy-feedback', {'inline-error': copyFailed}]" role="status">{{ copyMessage }}</p>
        <div class="clipboard-actions">
          <RouterLink v-if="shared" to="/zt" class="text-link">我也要分享文本 →</RouterLink>
          <button v-else class="text-button" @click="reset">新建粘贴板</button>
          <div class="clipboard-action-buttons">
            <a v-if="!shared" :href="entry.shareUrl" target="_blank" rel="noopener noreferrer" class="button button-outline">打开链接<ArrowUpRight :size="16"/></a>
            <button class="button button-primary" :disabled="copying" @click="copyEntry(!shared)"><Copy :size="16"/>{{ shared ? '复制内容' : '复制链接' }}</button>
          </div>
        </div>
      </template>

      <form v-else @submit.prevent="save">
        <label class="clipboard-field" for="clipboard-content">文本内容</label>
        <textarea id="clipboard-content" v-model="draft" class="clipboard-textarea" :maxlength="MAX_CLIPBOARD_LENGTH"
                  :disabled="saving" required placeholder="在这里输入或粘贴要分享的内容…" spellcheck="false" aria-describedby="clipboard-limit"/>
        <p id="clipboard-limit" class="clipboard-limit">{{ draft.length.toLocaleString('zh-CN') }} / 100,000 字符</p>
        <div class="clipboard-custom-link">
          <label class="clipboard-field" for="clipboard-custom-id">自定义链接<span class="muted">可选</span>
            <input id="clipboard-custom-id" v-model="customId" :disabled="saving" placeholder="例如 team-note"
                   autocomplete="off" autocapitalize="off" spellcheck="false" :aria-invalid="!!customIdError"
                   :aria-describedby="customIdError ? 'clipboard-custom-help clipboard-custom-error' : 'clipboard-custom-help'">
          </label>
          <p id="clipboard-custom-help" class="clipboard-custom-help">留空自动生成随机链接。自定义名称为 3–64 位英文字母、数字、下划线或短横线，以字母或数字开头，区分大小写；有效 10 分钟，到期后可重新使用。</p>
          <p v-if="customId.trim()" class="clipboard-link-preview">链接预览 <span>/zt/{{ customId.trim() }}</span></p>
          <p v-if="customIdError" id="clipboard-custom-error" class="clipboard-custom-error" role="alert">{{ customIdError }}</p>
        </div>
        <div class="clipboard-retention"><Timer :size="17"/><span>固定保留 <strong>10 分钟</strong><small>从保存成功起计时，到期自动销毁，所有人可访问。</small></span></div>
        <p v-if="error" class="notice error" role="alert">{{ error }}</p>
        <div class="clipboard-actions">
          <span class="muted">生成链接后即可发送给他人</span>
          <button class="button button-primary" :disabled="saving || !draft.trim()"><Save :size="16"/>{{ saving ? '正在保存…' : '保存' }}</button>
        </div>
      </form>
    </section>
  </div>
</template>
