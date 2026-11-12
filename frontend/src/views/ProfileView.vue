<script setup>
import { usePagination } from '../composables/usePagination'
import SPagination from '../components/ui/SPagination.vue'
import { ref, computed, onMounted, watch } from 'vue'
import { fetchProfile, updateProfile, changePassword } from '../api/auth'
import { contactPayload } from '../components/admin/userForm.js'
import { message } from '../components/ui/feedback'
import SInput from '../components/ui/SInput.vue'
import SButton from '../components/ui/SButton.vue'
import SDialog from '../components/ui/SDialog.vue'
import { useAuthStore } from '../stores/auth'
import { useAvatarStore } from '../stores/avatar'
import { getCreditOverview } from '../api/credit'
import SEmpty from '../components/ui/SEmpty.vue'
import RemindersPanel from '../components/RemindersPanel.vue'
import AppealsPanel from '../components/AppealsPanel.vue'
import RestrictionsPanel from '../components/RestrictionsPanel.vue'
import ViolationsPanel from '../components/ViolationsPanel.vue'
import SBanner from '../components/ui/SBanner.vue'

const auth = useAuthStore()
const overview = ref(null)
const creditLoading = ref(false), creditError = ref('')
async function loadCredit() {
  creditLoading.value = true
  creditError.value = ''
  try { const response = await getCreditOverview(); overview.value = response.data }
  catch (failure) { creditError.value = failure.message }
  finally { creditLoading.value = false }
}
const contacts = ref({ phone: '', email: '' })
const profileLoading = ref(false)
const profileError = ref('')
const contactError = ref('')
const contactSuccess = ref('')
const savingContacts = ref(false)
async function loadProfile() {
  if (profileLoading.value) return
  profileLoading.value = true
  profileError.value = ''
  try {
    const response = await fetchProfile()
    auth.setUser(response.data)
    contacts.value = { phone: response.data.phone || '', email: response.data.email || '' }
  } catch (failure) {
    profileError.value = failure.message
  } finally {
    profileLoading.value = false
  }
}
async function saveContacts() {
  if (savingContacts.value) return
  contactError.value = ''
  contactSuccess.value = ''
  if (!contacts.value.phone.trim()) { contactError.value = '联系电话必填'; return }
  let data
  try { data = contactPayload(contacts.value) }
  catch (failure) { contactError.value = failure.message; return }
  savingContacts.value = true
  try {
    const response = await updateProfile(data)
    auth.setUser(response.data)
    contacts.value = { phone: response.data.phone || '', email: response.data.email || '' }
    contactSuccess.value = '联系方式已保存'
  } catch (failure) {
    contactError.value = failure.message
  } finally {
    savingContacts.value = false
  }
}

const passwordDialog = ref(false)
const passwordSaving = ref(false)
const passwordError = ref('')
const passwordForm = ref({ current_password: '', new_password: '', confirm_password: '' })
function openPassword() {
  passwordForm.value = { current_password: '', new_password: '', confirm_password: '' }
  passwordError.value = ''
  passwordDialog.value = true
}
async function savePassword() {
  if (passwordSaving.value) return
  const { current_password, new_password, confirm_password } = passwordForm.value
  if (!current_password) { passwordError.value = '请输入当前密码'; return }
  if (new_password.length < 6 || new_password.length > 64) { passwordError.value = '新密码须为6至64个字符'; return }
  if (new_password !== confirm_password) { passwordError.value = '两次输入的新密码不一致'; return }
  if (new_password === current_password) { passwordError.value = '新密码不能与当前密码相同'; return }
  passwordSaving.value = true
  passwordError.value = ''
  try {
    const response = await changePassword({ current_password, new_password })
    auth.setUser(response.data)
    passwordDialog.value = false
    message.success('登录密码已修改')
  } catch (failure) {
    passwordError.value = failure.message
  } finally {
    passwordSaving.value = false
  }
}

onMounted(async () => {
  await loadProfile()
  if (!auth.isOrdinary) return
  await loadCredit()
})
const creditLogs = computed(() => overview.value?.logs || [])
const { page, pages, pageItems } = usePagination(creditLogs, null)

// 头像：后端暂无上传接口，先保存在本机浏览器；导航与正文共用同一 store。
const avatarInput = ref(null)
const avatarError = ref('')
const avatarInitial = computed(() => (auth.user?.real_name || '?').slice(0, 1))
const avatarStore = useAvatarStore()
watch(() => auth.user?.id, () => avatarStore.load(), { immediate: true })
function pickAvatar() {
  avatarError.value = ''
  avatarInput.value?.click()
}
function onAvatarFile(event) {
  const file = event.target.files?.[0]
  event.target.value = ''
  if (!file) return
  if (!/^image\/(png|jpeg|webp)$/.test(file.type)) { avatarError.value = '请选择 PNG、JPEG 或 WebP 图片'; return }
  if (file.size > 2 * 1024 * 1024) { avatarError.value = '头像不能超过 2MB'; return }
  const reader = new FileReader()
  reader.onload = () => {
    const value = String(reader.result || '')
    if (!value.startsWith('data:image/')) { avatarError.value = '图片读取失败'; return }
    avatarStore.setAvatar(value)
  }
  reader.onerror = () => { avatarError.value = '图片读取失败' }
  reader.readAsDataURL(file)
}
function resetAvatar() {
  avatarStore.reset()
  avatarError.value = ''
}

const profileTabs = [
  { key: 'restrictions', label: '资格与限制', title: '本人预约限制与当前资格' },
  { key: 'violations', label: '违规记录', title: '本人独立违规记录' },
  { key: 'appeals', label: '申诉进度', title: '本人申诉进度' },
  { key: 'reminders', label: '提醒', title: '本人提醒（不计违规）' },
  { key: 'credit', label: '信用流水', title: '历史信用流水（只读）' }
]
const profileTab = ref('restrictions')
const mountedProfileTabs = ref(new Set(['restrictions']))
function selectProfileTab(key) {
  profileTab.value = key
  if (!mountedProfileTabs.value.has(key)) {
    mountedProfileTabs.value = new Set([...mountedProfileTabs.value, key])
  }
}
</script>

<template>
  <div v-reveal class="page-view">
    <header class="view-heading" data-page-title>
      <h1>个人中心</h1>
      <p class="heading-sub">账户联系方式、本人违规与历史信用资料</p>
    </header>
    <div class="split profile-split" :class="{ 'profile-admin': auth.isAdmin }">
    <div class="split-left">
      <!-- 用户卡片 -->
      <section class="card profile-card responsive-compact">
        <div class="avatar-lg">
          <img v-if="avatarStore.data" :src="avatarStore.data" alt="本人头像" />
          <span v-else>{{ avatarInitial }}</span>
        </div>
        <div class="profile-name">{{ auth.user?.real_name || '未登记' }}</div>
        <div style="margin-top: 10px">
          <span class="role-pill" :class="auth.isAdmin ? 'role-pill--admin' : 'role-pill--student'">
            {{ auth.roleLabel }}
          </span>
        </div>
        <div class="avatar-actions">
          <SButton size="sm" @click="pickAvatar">更换头像</SButton>
          <SButton v-if="avatarStore.data" size="sm" variant="ghost" @click="resetAvatar">恢复默认</SButton>
        </div>
        <input ref="avatarInput" class="sr-only" type="file" accept="image/png,image/jpeg,image/webp" @change="onAvatarFile" />
        <p v-if="avatarError" class="avatar-error">{{ avatarError }}</p>
      </section>

      <!-- 基本信息 + 联系方式与登录密码 -->
      <section class="card responsive-compact profile-combined-card">
        <div class="card-title-row"><h3>基本信息</h3><SButton size="sm" type="submit" form="profile-form" :loading="savingContacts" :disabled="profileLoading||!!profileError">保存资料</SButton></div>
        <div class="profile-combined">
          <div class="profile-info">
            <div class="info-row"><span>姓名</span><span class="info-value">{{ auth.user?.real_name || '未登记' }}</span></div>
            <div class="info-row"><span>学号</span><span class="info-value">{{ auth.user?.campus_id || auth.user?.student_no || '未登记' }}</span></div>
            <div class="info-row"><span>角色</span><span class="info-value">{{ auth.roleLabel }}</span></div>
            <div class="info-row"><span>账号状态</span><span class="info-value" :class="{ 'status-off': auth.user?.status !== 'active' }">{{ auth.user?.status === 'active' ? '正常' : '已禁用' }}</span></div>
            <div v-if="auth.isOrdinary" class="info-row credit-row">
              <span>历史信用（只读）</span>
              <span class="credit-inline">
                <b>{{ overview?.score ?? '—' }}</b>
                <SButton size="sm" variant="ghost" :loading="creditLoading" @click="loadCredit">刷新</SButton>
              </span>
            </div>
            <p v-if="creditError" class="credit-error">历史信用读取失败；不影响当前预约资格。</p>
          </div>

          <div class="profile-edit">
            <p v-if="profileLoading" class="muted" role="status">正在读取最新资料…</p>
            <SBanner v-else-if="profileError" type="error" :title="profileError"><SButton size="sm" @click="loadProfile">重试读取</SButton></SBanner>
            <form id="profile-form" v-else class="contact-form" @submit.prevent="saveContacts">
              <div class="password-field">
                <div class="password-row">
                  <span class="field-label">密码 <span class="req">*</span><span class="field-hint-inline">（初次使用必须修改，之后可在此再次修改）</span></span>
                  <SButton size="sm" type="button" @click="openPassword">修改密码</SButton>
                </div>
              </div>
              <label><span class="field-label">联系电话 <span class="req">*</span></span><SInput v-model="contacts.phone" type="tel" maxlength="30" autocomplete="tel" /></label>
              <label>邮箱（选填）<SInput v-model="contacts.email" type="email" maxlength="254" autocomplete="email" /></label>
              <p v-if="contactError" role="alert" class="contact-error">{{ contactError }}</p>
              <p v-if="contactSuccess" role="status">{{ contactSuccess }}</p>
            </form>
          </div>
        </div>
      </section>

    </div>

    <div v-if="auth.isOrdinary" class="split-right profile-records">
      <nav class="profile-tabs" role="tablist" aria-label="本人记录">
        <button v-for="tab in profileTabs" :key="tab.key" type="button" role="tab" :aria-selected="profileTab===tab.key" :class="{active:profileTab===tab.key}" :title="tab.title" @click="selectProfileTab(tab.key)">{{tab.label}}</button>
      </nav>
      <RestrictionsPanel v-if="mountedProfileTabs.has('restrictions')" v-show="profileTab==='restrictions'" />
      <ViolationsPanel v-if="mountedProfileTabs.has('violations')" v-show="profileTab==='violations'" />
      <AppealsPanel v-if="mountedProfileTabs.has('appeals')" v-show="profileTab==='appeals'" />
      <RemindersPanel v-if="mountedProfileTabs.has('reminders')" v-show="profileTab==='reminders'" />
      <section v-if="mountedProfileTabs.has('credit')" v-show="profileTab==='credit'" class="card">
        <div class="card-title-row">
          <h3>历史信用流水（只读）</h3>
          <SButton size="sm" :loading="creditLoading" @click="loadCredit">刷新</SButton>
        </div>
        <p v-if="creditLoading" role="status">正在读取历史流水…</p><p v-else-if="creditError" class="muted">历史流水未读取成功，请点击右上角刷新重试。</p>
        <div v-list-motion="pageItems.map(row => row.id + row.status).join()" class="table-wrap">
          <div class="table-scroll" tabindex="0" aria-label="信用分流水表格，可左右滑动">
            <table class="table credit-table">
              <thead>
                <tr>
                  <th style="width:180px">时间</th>
                  <th style="width:100px">变动</th>
                  <th style="min-width:240px">事由</th>
                  <th style="width:110px">关联预约</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="row in pageItems" :key="row.id">
                  <td class="num">{{ row.created_at }}</td>
                  <td>
                    <span :class="row.delta > 0 ? 'up' : 'down'">
                      {{ row.delta > 0 ? '+' + row.delta : row.delta }}
                    </span>
                  </td>
                  <td>{{ row.reason }}</td>
                  <td class="num">#{{ row.reservation_id ?? '—' }}</td>
                </tr>
              </tbody>
            </table>
            <SEmpty v-if="!creditLoading && !creditError && overview && !overview.logs.length" description="尚无已保存的历史信用流水" />
          </div>
        </div>
        <SPagination v-model="page" :pages="pages" :total="creditLogs.length" />
      </section>
    </div>
    </div>
    <SDialog v-model="passwordDialog" title="修改登录密码" width="420px" :show-close="!passwordSaving" :close-on-click-modal="!passwordSaving">
      <form id="password-form" class="password-form" @submit.prevent="savePassword">
        <label>当前密码<SInput v-model="passwordForm.current_password" type="password" show-password autocomplete="current-password" /></label>
        <label>新密码<SInput v-model="passwordForm.new_password" type="password" show-password autocomplete="new-password" /></label>
        <label>确认新密码<SInput v-model="passwordForm.confirm_password" type="password" show-password autocomplete="new-password" /></label>
        <SBanner v-if="passwordError" type="error" :title="passwordError" />
      </form>
      <template #footer>
        <SButton :disabled="passwordSaving" @click="passwordDialog = false">取消</SButton>
        <SButton type="submit" form="password-form" variant="primary" :loading="passwordSaving">保存新密码</SButton>
      </template>
    </SDialog>
  </div>
</template>

<style scoped>
.contact-form { display: grid; gap: 0; }
.contact-form > label, .password-field { padding: 8px 0 12px; border-bottom: 1px solid var(--hairline); }
.contact-form > label:last-of-type { border-bottom: none; }
.contact-form label { display: grid; gap: 7px; font-size: var(--fs-body-sm); color: var(--text-2); }
.profile-combined { display: flex; flex-direction: column; gap: 0; }
.profile-edit { min-width: 0; }
.password-field { display: grid; gap: 7px; }
.field-label { font-size: var(--fs-body-sm); color: var(--text-2); }
.password-row { display: flex; align-items: center; justify-content: space-between; gap: 12px; flex-wrap: wrap; }
.field-hint { margin: 0; color: var(--text-4); font-size: var(--fs-caption); line-height: 1.6; }
.field-hint-inline { color: var(--text-4); font-size: var(--fs-caption); font-weight: 400; }
.req { color: var(--red); }
.password-form { display: grid; gap: 12px; }
.password-form label { display: grid; gap: 7px; }
@media (max-width: 900px) { .profile-combined { grid-template-columns: 1fr; } }
.contact-error { color: var(--red-strong); }
.profile-records { display: flex; flex-direction: column; gap: 16px; }
.profile-tabs { display: flex; flex-wrap: wrap; gap: 6px; padding: 6px; background: var(--surface); border: 1px solid var(--border); border-radius: var(--r-lg); }
.profile-tabs button {
  all: unset;
  flex: 1 1 0;
  box-sizing: border-box;
  text-align: center;
  padding: 9px 12px;
  border-radius: var(--r-md);
  color: var(--text-2);
  font-size: 13px;
  cursor: pointer;
  white-space: nowrap;
}
.profile-tabs button:hover { background: var(--surface-2); color: var(--text-1); }
.profile-tabs button.active { background: var(--primary-weak); color: var(--primary-active); font-weight: 600; }


.credit-table { min-width: 640px; }


.profile-card {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  text-align: center;
  background: radial-gradient(120% 100% at 50% 0%, var(--primary-faint) 0%, var(--surface-2) 72%);
  padding: 28px 20px;
}
.avatar-lg {
  width: 104px;
  height: 104px;
  border-radius: 26px;
  background: var(--primary-weak);
  color: var(--primary-active);
  display: grid;
  place-items: center;
  font-size: 42px;
  font-weight: 700;
  margin: 0 0 18px;
  overflow: hidden;
  box-shadow: 0 0 0 6px var(--surface), 0 0 0 7px var(--primary-weak), var(--shadow-2);
}
.avatar-lg img { width: 100%; height: 100%; object-fit: cover; display: block; }
.avatar-actions { display: flex; justify-content: center; gap: 8px; margin-top: 12px; flex-wrap: wrap; }
.avatar-error { color: var(--red-strong); font-size: var(--fs-caption); margin-top: 8px; }
.credit-inline { display: inline-flex; align-items: center; gap: 8px; }
.credit-error { color: var(--red-strong); font-size: var(--fs-caption); margin-top: 6px; }
.profile-name {
  font-size: 20px;
  font-weight: 650;
  color: var(--text-1);
  letter-spacing: -.01em;
}
.profile-sub {
  font-size: var(--fs-caption);
  color: var(--text-3);
  margin-top: 3px;
}
.role-pill {
  display: inline-flex;
  font-size: 11px;
  font-weight: 500;
  line-height: 1;
  padding: 4px 10px;
  border-radius: var(--r-full);
}
.role-pill--student {
  background: var(--primary-weak);
  color: var(--primary-active);
}
.role-pill--admin {
  background: var(--red-weak);
  color: var(--red-strong);
}

.info-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 8px 0;
  border-bottom: 1px solid var(--hairline);
  font-size: var(--fs-body-sm);
}
.info-row span { color: var(--text-3); }
.info-row b { color: var(--text-1); font-weight: 600; }
.info-value { color: var(--text-1); font-weight: 400; }
.info-value.status-off { color: var(--red-strong); }
.info-row b.status-off { color: var(--red-strong); }

.credit-box {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 8px 0 4px;
}
.score-num {
  font-size: 30px;
  font-weight: 700;
  line-height: 1;
  font-variant-numeric: tabular-nums;
}
.score-label {
  font-size: var(--fs-caption);
  color: var(--text-3);
  margin-top: 3px;
}
.rule-tip {
  margin-top: 14px;
  padding: 10px 12px;
  background: var(--surface-3);
  border-radius: var(--r-lg);
  font-size: var(--fs-caption);
  color: var(--text-3);
  line-height: 1.6;
  width: 100%;
  box-sizing: border-box;
  text-align: center;
}

.up   { color: var(--green-strong); font-weight: 700; font-variant-numeric: tabular-nums; }
.down { color: var(--red-strong); font-weight: 700; font-variant-numeric: tabular-nums; }
</style>
