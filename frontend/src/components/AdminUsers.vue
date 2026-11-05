<script setup lang="ts">
import {computed, onMounted, reactive, ref} from 'vue'
import {useRouter} from 'vue-router'
import {ChevronLeft, ChevronRight, KeyRound, Pencil, Plus, RefreshCw, Search, Trash2, Users, X} from 'lucide-vue-next'
import {api, date, resetCsrf, session, size} from '../api'
import type {AdminUser, Page} from '../types'

const users = ref<AdminUser[]>([]);
const page = ref(0);
const pageSize = 20;
const total = ref(0);
const query = ref('');
const loading = ref(false);
const busy = ref(false);
const error = ref('');
const success = ref('');
const modal = ref<'create' | 'edit' | 'password' | 'delete' | null>(null);
const selected = ref<AdminUser | null>(null)
const form = reactive({username: '', password: '', role: 'USER' as 'USER' | 'ADMIN', enabled: true})
const router = useRouter()
const pageCount = computed(() => Math.max(1, Math.ceil(total.value / pageSize)))

async function load() {
  loading.value = true;
  error.value = '';
  try {
    const result = await api<Page<AdminUser>>(`/admin/users?page=${page.value}&size=${pageSize}&query=${encodeURIComponent(query.value.trim())}`);
    users.value = result.items;
    total.value = result.total;
    if (page.value >= pageCount.value) {
      page.value = pageCount.value - 1;
      await load()
    }
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    loading.value = false
  }
}

function search() {
  page.value = 0;
  void load()
}

function changePage(value: number) {
  page.value = value;
  void load()
}

function open(mode: NonNullable<typeof modal.value>, user?: AdminUser) {
  error.value = '';
  success.value = '';
  selected.value = user || null;
  Object.assign(form, {
    username: user?.username || '',
    password: '',
    role: user?.role || 'USER',
    enabled: user?.enabled ?? true
  });
  modal.value = mode
}

function close() {
  if (busy.value) return;
  modal.value = null;
  form.password = '';
  error.value = ''
}

async function save() {
  if (busy.value) return;
  busy.value = true;
  error.value = '';
  success.value = ''
  try {
    if (modal.value === 'create') await api('/admin/users', {
      method: 'POST',
      body: JSON.stringify({username: form.username.trim(), password: form.password, role: form.role})
    })
    else if (modal.value === 'edit') await api(`/admin/users/${selected.value!.id}`, {
      method: 'PATCH',
      body: JSON.stringify({role: form.role, enabled: form.enabled})
    })
    else if (modal.value === 'password') {
      await api(`/admin/users/${selected.value!.id}/password`, {
        method: 'POST',
        body: JSON.stringify({password: form.password})
      })
      if (selected.value!.id === session.user?.id) {
        session.user = null;
        resetCsrf();
        form.password = '';
        modal.value = null;
        await router.replace({path: '/admin/login', query: {reauth: 'password-reset'}});
        return
      }
    } else if (modal.value === 'delete') await api(`/admin/users/${selected.value!.id}`, {method: 'DELETE'})
    success.value = modal.value === 'delete' ? '用户已删除' : modal.value === 'password' ? '密码已重置，请通过安全渠道告知用户新密码' : '用户设置已保存'
    modal.value = null;
    form.password = '';
    await load()
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    busy.value = false
  }
}

onMounted(load)
</script>
<template>
  <div class="users-admin">
    <div class="section-toolbar"><p>管理账号、角色、启停状态和密码。</p>
      <button class="button button-primary" @click="open('create')">
        <Plus :size="17"/>
        创建用户
      </button>
    </div>
    <div v-if="error && !modal" class="notice error" role="alert">{{ error }}</div>
    <div v-if="success" class="notice success" role="status">{{ success }}</div>
    <section class="panel">
      <div class="panel-header"><h2>全部用户 <span class="count-badge">{{ total }}</span></h2>
        <button class="text-button" :disabled="loading" @click="load">
          <RefreshCw :size="15"/>
          刷新
        </button>
      </div>
      <form class="search-bar" @submit.prevent="search"><label class="search-input">
        <Search :size="16"/>
        <input v-model="query" maxlength="100" placeholder="搜索用户名" aria-label="搜索用户名"></label>
        <button class="button button-small button-outline" :disabled="loading">搜索</button>
      </form>
      <div v-if="loading" class="empty-state"><span class="spinner"></span>正在读取用户…</div>
      <div v-else-if="!users.length" class="empty-state">
        <Users :size="38"/>
        <h3>{{ query ? '没有匹配的用户' : '还没有用户' }}</h3>
        <p>{{ query ? '换一个关键词试试。' : '可使用上方按钮创建用户。' }}</p></div>
      <div v-else class="table-scroll">
        <table>
          <thead>
          <tr>
            <th>用户名</th>
            <th>角色 / 状态</th>
            <th>文件 / 占用</th>
            <th>创建时间</th>
            <th>操作</th>
          </tr>
          </thead>
          <tbody>
          <tr v-for="user in users" :key="user.id">
            <td><strong class="user-name">{{ user.username }}</strong><small v-if="user.id === session.user?.id"
                                                                             class="current-user">当前账号</small></td>
            <td>
              <div class="stacked-badges"><span class="badge"
                                                :class="user.role === 'ADMIN' ? 'badge-teal' : 'badge-neutral'">{{
                  user.role === 'ADMIN' ? '管理员' : '普通用户'
                }}</span><span class="badge" :class="user.enabled ? 'badge-teal' : 'badge-danger'">{{
                  user.enabled ? '已启用' : '已停用'
                }}</span></div>
            </td>
            <td class="nowrap">{{ user.fileCount }} 个 / {{ size(user.storageBytes) }}</td>
            <td class="nowrap muted">{{ date(user.createdAt) }}</td>
            <td>
              <div class="table-actions">
                <button class="icon-button" aria-label="编辑用户角色和状态" title="编辑角色和状态"
                        @click="open('edit', user)">
                  <Pencil :size="16"/>
                </button>
                <button class="icon-button" aria-label="重置用户密码" title="重置密码" @click="open('password', user)">
                  <KeyRound :size="16"/>
                </button>
                <button class="icon-button danger" aria-label="删除用户" title="删除用户" @click="open('delete', user)">
                  <Trash2 :size="16"/>
                </button>
              </div>
            </td>
          </tr>
          </tbody>
        </table>
      </div>
      <div class="pagination"><span>共 {{ total }} 位用户 · 第 {{ page + 1 }} / {{ pageCount }} 页</span>
        <div>
          <button class="icon-button" aria-label="上一页用户" :disabled="loading || page === 0"
                  @click="changePage(page - 1)">
            <ChevronLeft :size="18"/>
          </button>
          <button class="icon-button" aria-label="下一页用户" :disabled="loading || page + 1 >= pageCount"
                  @click="changePage(page + 1)">
            <ChevronRight :size="18"/>
          </button>
        </div>
      </div>
    </section>
    <p class="page-note">不能删除当前账号或仍有文件的用户。系统会保护最后一位启用的管理员，避免失去管理权限。</p>
  </div>
  <div v-if="modal" class="modal-backdrop" @click.self="close">
    <section class="modal" role="dialog" aria-modal="true" aria-labelledby="user-modal-title">
      <div class="modal-title"><h2 id="user-modal-title">{{
          modal === 'create' ? '创建用户' : modal === 'edit' ? '编辑用户' : modal === 'password' ? '重置密码' : '删除用户？'
        }}</h2>
        <button class="icon-button" aria-label="关闭用户对话框" :disabled="busy" @click="close">
          <X :size="19"/>
        </button>
      </div>
      <form @submit.prevent="save">
        <template v-if="modal === 'create'"><label class="field">用户名<input v-model="form.username" required
                                                                              minlength="3" maxlength="32"
                                                                              pattern="[A-Za-z0-9_]+" autocomplete="off"
                                                                              placeholder="3–32 位字母、数字或下划线"></label>
        </template>
        <p v-else class="dialog-username">{{ selected?.username }}</p>
        <template v-if="modal === 'create' || modal === 'edit'"><label class="field">角色<select v-model="form.role">
          <option value="USER">普通用户</option>
          <option value="ADMIN">管理员</option>
        </select><small>管理员可以管理所有文件、用户和存储配置。</small></label><label v-if="modal === 'edit'"
                                                                                     class="checkbox-field user-enabled"><input
            v-model="form.enabled" type="checkbox">启用账号</label></template>
        <label v-if="modal === 'create' || modal === 'password'"
               class="field">{{ modal === 'password' ? '新密码' : '初始密码' }}<input v-model="form.password"
                                                                                      type="password" required
                                                                                      minlength="8" maxlength="72"
                                                                                      autocomplete="new-password"
                                                                                      placeholder="输入至少 8 位的新密码"><small>最多
          72 个 UTF-8 字节。密码仅用于本次提交，关闭后清空。</small></label>
        <p v-if="modal === 'delete'" class="dialog-copy">确定删除此账号？仅无文件的用户可以删除，此操作无法撤销。</p>
        <div v-if="error" class="notice error" role="alert">{{ error }}</div>
        <div class="modal-actions">
          <button type="button" class="button button-outline" :disabled="busy" @click="close">取消</button>
          <button class="button" :class="modal === 'delete' ? 'button-danger' : 'button-primary'" :disabled="busy">
            {{ busy ? '正在处理…' : modal === 'delete' ? '确认删除' : modal === 'password' ? '重置密码' : '保存用户' }}
          </button>
        </div>
      </form>
    </section>
  </div>
</template>
