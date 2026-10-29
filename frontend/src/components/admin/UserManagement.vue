<script setup>
import { computed, onMounted, ref } from 'vue'
import { ROLE_LABELS, useAuthStore } from '../../stores/auth'
import { createUser, updateUser, listUsers, setUserStatus } from '../../api/admin'
import { userPayload } from './userForm.js'
import { message, promptDialog } from '../ui/feedback'
import SButton from '../ui/SButton.vue'
import SInput from '../ui/SInput.vue'
import SSelect from '../ui/SSelect.vue'
import SDialog from '../ui/SDialog.vue'
import STag from '../ui/STag.vue'
import SEmpty from '../ui/SEmpty.vue'
import SBanner from '../ui/SBanner.vue'

const emit = defineEmits(['summary'])
const auth = useAuthStore()
const users = ref([])
const loading = ref(false)
const error = ref('')
const dialog = ref(false)
const editing = ref(null)
const saving = ref(false)
const statusBusy = ref(null)
const formError = ref('')
const form = ref({})
const self = computed(() => editing.value !== null && String(editing.value) === String(auth.user?.id))
const roles = Object.entries(ROLE_LABELS).map(([value, label]) => ({ value, label }))
const statuses = [{ value: 'active', label: '正常' }, { value: 'disabled', label: '禁用' }]

async function loadUsers() {
  if (loading.value) return
  loading.value = true
  error.value = ''
  try {
    const response = await listUsers()
    users.value = response.data
    emit('summary', { total: users.value.length, disabled: users.value.filter(user => user.status === 'disabled').length })
  } catch (failure) {
    error.value = failure.message
    emit('summary', null)
  } finally {
    loading.value = false
  }
}
function openCreate() {
  if (saving.value) return
  editing.value = null
  form.value = { username: '', real_name: '', campus_id: '', role: 'student', status: 'active', phone: '', email: '', password: '', reason: '' }
  formError.value = ''
  dialog.value = true
}
function openEdit(user) {
  if (saving.value) return
  editing.value = user.id
  form.value = Object.fromEntries(['username', 'real_name', 'campus_id', 'role', 'status', 'phone', 'email'].map(key => [key, user[key] ?? '']))
  form.value.password = ''
  form.value.reason = ''
  formError.value = ''
  dialog.value = true
}
async function saveUser() {
  if (saving.value) return
  formError.value = ''
  let payload
  try { payload = userPayload(form.value, editing.value === null) }
  catch (failure) { formError.value = failure.message; return }
  saving.value = true
  try {
    const response = editing.value === null ? await createUser(payload) : await updateUser(editing.value, payload)
    if (String(response.data.id) === String(auth.user?.id)) auth.setUser(response.data)
    dialog.value = false
    message.success('账号已保存')
    await loadUsers()
  } catch (failure) {
    formError.value = failure.message
  } finally {
    saving.value = false
  }
}
async function toggleStatus(user) {
  if (statusBusy.value !== null || String(user.id) === String(auth.user?.id)) return
  const status = user.status === 'active' ? 'disabled' : 'active'
  let reason
  try {
    const result = await promptDialog(`请填写${status === 'active' ? '启用' : '禁用'}「${user.username}」的理由`, '账号状态变更', { inputPattern: /\S/, inputErrorMessage: '操作理由不能为空' })
    reason = result.value.trim()
  } catch { return }
  statusBusy.value = user.id
  try {
    await setUserStatus(user.id, status, reason)
    message.success('账号状态已更新')
    await loadUsers()
  } catch (failure) {
    error.value = failure.message
  } finally {
    statusBusy.value = null
  }
}
onMounted(loadUsers)
defineExpose({ loadUsers, openCreate })
</script>

<template>
  <section class="card">
    <div class="card-title-row"><h3>用户管理</h3><div class="row-actions"><SButton size="sm" :loading="loading" @click="loadUsers">刷新用户</SButton><SButton size="sm" variant="primary" @click="openCreate">创建账号</SButton></div></div>
    <p v-if="loading" role="status" class="muted">正在加载校园账号…</p>
    <SBanner v-if="error" type="error" :title="error"><SButton size="sm" :disabled="loading" @click="loadUsers">重试</SButton></SBanner>
    <div v-if="!loading && !error" class="table-wrap"><div class="table-scroll" tabindex="0" aria-label="校园用户列表，可左右滑动"><table class="table account-table">
      <thead><tr><th>用户名 / 姓名</th><th>校园编号</th><th>角色</th><th>联系方式</th><th>状态</th><th>操作</th></tr></thead>
      <tbody><tr v-for="user in users" :key="user.id">
        <td><b>{{ user.username }}</b><div>{{ user.real_name }}</div></td><td>{{ user.campus_id || '未登记' }}</td>
        <td><STag :type="user.role === 'admin' ? 'danger' : 'primary'">{{ ROLE_LABELS[user.role] || '未知角色' }}</STag></td>
        <td><div>{{ user.phone || '—' }}</div><div>{{ user.email || '—' }}</div></td>
        <td><STag :type="user.status === 'active' ? 'success' : 'neutral'">{{ user.status === 'active' ? '正常' : '禁用' }}</STag></td>
        <td><div class="row-actions"><SButton size="sm" @click="openEdit(user)">编辑账号</SButton><SButton v-if="String(user.id) !== String(auth.user?.id)" size="sm" :loading="statusBusy === user.id" :disabled="statusBusy !== null" @click="toggleStatus(user)">{{ user.status === 'active' ? '禁用账号' : '启用账号' }}</SButton><span v-else class="muted">当前账号</span></div></td>
      </tr></tbody>
    </table><SEmpty v-if="!users.length" description="暂无校园账号，可创建学生或教职工账号" /></div></div>
    <SDialog v-model="dialog" :title="editing === null ? '创建校园账号' : '编辑校园账号'" width="660px" :close-on-click-modal="!saving" :show-close="!saving">
      <form id="account-form" class="account-form" @submit.prevent="saveUser">
        <p v-if="formError" role="alert" class="form-error">{{ formError }}</p>
        <label>用户名<SInput v-model="form.username" required minlength="3" maxlength="50" autocomplete="off" /></label>
        <label>姓名<SInput v-model="form.real_name" required maxlength="50" /></label>
        <label>校园编号<SInput v-model="form.campus_id" :required="editing === null" maxlength="20" placeholder="遗留未知编号可暂留空" /></label>
        <label>角色<SSelect v-model="form.role" :options="roles" :disabled="self" /></label>
        <label>状态<SSelect v-model="form.status" :options="statuses" :disabled="self" /></label>
        <label>联系电话<SInput v-model="form.phone" maxlength="30" type="tel" /></label>
        <label>邮箱<SInput v-model="form.email" maxlength="254" type="email" /></label>
        <label>{{ editing === null ? '初始口令' : '新口令（留空不修改）' }}<SInput v-model="form.password" type="password" show-password :required="editing === null" minlength="6" maxlength="64" autocomplete="new-password" /></label>
        <label class="full-width">操作理由<SInput v-model="form.reason" type="textarea" required maxlength="1000" /></label>
        <p v-if="self" class="muted full-width">当前管理员不能自行禁用或降级。</p>
      </form>
      <template #footer><SButton :disabled="saving" @click="dialog = false">取消</SButton><SButton type="submit" form="account-form" variant="primary" :loading="saving">保存账号</SButton></template>
    </SDialog>
  </section>
</template>

<style scoped>
.account-table { min-width: 760px; }
.account-form { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 16px; }
.account-form label { display: grid; gap: 7px; font-size: var(--fs-caption); color: var(--text-2); }
.full-width, .form-error { grid-column: 1 / -1; }
.form-error { color: var(--red-strong); }
@media (max-width: 560px) { .account-form { grid-template-columns: 1fr; } }
</style>
