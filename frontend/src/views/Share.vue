<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ArrowDownToLine, File, Link2, ShieldCheck, Timer } from 'lucide-vue-next'
import { api, date, size } from '../api'
import type { FileInfo } from '../types'
const route = useRoute(); const file = ref<FileInfo>(); const loading = ref(true); const error = ref('')
onMounted(async () => { try { file.value = await api<FileInfo>(`/shares/${encodeURIComponent(String(route.params.token))}`) } catch (e) { error.value = (e as Error).message } finally { loading.value = false } })
</script>
<template><div class="share-page"><section class="share-card"><div class="eyebrow"><Link2 :size="16" />有人与你分享了一个文件</div><div v-if="loading" class="empty-state"><span class="spinner"></span>正在读取分享…</div><div v-else-if="error" class="empty-state"><Timer :size="42" /><h1>暂时无法打开文件</h1><p class="inline-error" role="alert">{{ error }}</p><p>链接可能已过期或文件已被删除。</p><RouterLink to="/" class="button button-outline">返回首页</RouterLink></div><template v-else-if="file"><span class="share-file-icon"><File :size="43" :stroke-width="1.4" /></span><h1 class="share-filename">{{ file.originalName }}</h1><p class="muted">{{ size(file.sizeBytes) }}<span class="footer-dot">·</span>{{ file.contentType || '文件' }}</p><div class="share-retention"><Timer :size="16" />{{ file.permanent ? '此文件已永久保存' : `${date(file.expiresAt)} 到期` }}</div><a :href="`/api/shares/${encodeURIComponent(String(route.params.token))}/download`" class="button button-primary full-width"><ArrowDownToLine :size="18" />下载文件</a><p class="share-warning"><ShieldCheck :size="14" />仅下载你信任的文件，打开前请确认来源。</p></template></section><RouterLink to="/" class="text-link">我也要分享文件 →</RouterLink></div></template>
