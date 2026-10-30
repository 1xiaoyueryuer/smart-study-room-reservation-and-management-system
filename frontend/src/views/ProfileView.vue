<script setup>
import { usePagination } from '../composables/usePagination'
import SPagination from '../components/ui/SPagination.vue'
import { ref, computed, onMounted } from 'vue'
import { fetchProfile, updateProfile } from '../api/auth'
import { contactPayload } from '../components/admin/userForm.js'
import SInput from '../components/ui/SInput.vue'
import SButton from '../components/ui/SButton.vue'
import { useAuthStore } from '../stores/auth'
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

onMounted(async () => {
  await loadProfile()
  if (!auth.isOrdinary) return
  await loadCredit()
})
const creditLogs = computed(() => overview.value?.logs || [])
const { page, pages, pageItems } = usePagination(creditLogs, null)
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
          {{ (auth.user?.real_name || auth.user?.username || '?').slice(0, 1) }}
        </div>
        <div class="profile-name">{{ auth.user?.real_name || auth.user?.username }}</div>
        <div class="profile-sub">@{{ auth.user?.username }}</div>
        <div style="margin-top: 10px">
          <span class="role-pill" :class="auth.isAdmin ? 'role-pill--admin' : 'role-pill--student'">
            {{ auth.roleLabel }}
          </span>
        </div>
      </section>

      <!-- 基本信息 -->
      <section class="card responsive-compact">
        <div class="card-title-row"><h3>基本信息</h3></div>
        <div class="info-row"><span>姓名</span><b>{{ auth.user?.real_name || '—' }}</b></div>
        <div class="info-row"><span>用户名</span><b>{{ auth.user?.username || '—' }}</b></div>
        <div class="info-row"><span>校园编号</span><b>{{ auth.user?.campus_id || '未登记' }}</b></div>
        <div class="info-row"><span>角色</span><b>{{ auth.roleLabel }}</b></div>
        <div class="info-row"><span>账号状态</span><b :class="{ 'status-off': auth.user?.status !== 'active' }">{{ auth.user?.status === 'active' ? '正常' : '已禁用' }}</b></div>
      </section>

      <section class="card responsive-compact">
        <div class="card-title-row"><h3>本人联系方式</h3></div>
        <p v-if="profileLoading" class="muted" role="status">正在读取最新资料…</p>
        <SBanner v-else-if="profileError" type="error" :title="profileError"><SButton size="sm" @click="loadProfile">重试读取</SButton></SBanner>
        <form v-else class="contact-form" @submit.prevent="saveContacts">
          <p class="muted">校园身份由管理员维护；您只能修改本人的联系电话和邮箱。</p>
          <label>联系电话<SInput v-model="contacts.phone" type="tel" maxlength="30" autocomplete="tel" /></label>
          <label>邮箱<SInput v-model="contacts.email" type="email" maxlength="254" autocomplete="email" /></label>
          <p v-if="contactError" role="alert" class="contact-error">{{ contactError }}</p>
          <p v-if="contactSuccess" role="status">{{ contactSuccess }}</p>
          <SButton type="submit" variant="primary" :loading="savingContacts">保存联系方式</SButton>
        </form>
      </section>

      <section v-if="auth.isOrdinary" class="card responsive-compact">
        <div class="card-title-row"><h3>历史信用资料（只读）</h3><SButton size="sm" :loading="creditLoading" @click="loadCredit">刷新历史资料</SButton></div>
        <p v-if="creditLoading" role="status">正在读取历史信用资料…</p>
        <SBanner v-else-if="creditError" type="error" :title="creditError"><SButton size="sm" @click="loadCredit">重试历史查询</SButton></SBanner>
        <template v-else-if="overview">
          <p class="score-num">{{ overview.score }}</p><p class="muted">历史信用分仅作溯源，不再新增奖惩或触发低分限制；预约资格由服务端复核，不能由分数推断。</p>
          <p v-if="overview.legacy_banned_until || overview.banned_until" class="muted">历史限制截止记录：{{ overview.legacy_banned_until || overview.banned_until }}（Asia/Shanghai）；这里只保留历史值，当前是否受限请查看预约限制与当前资格。</p>
          <p v-else class="muted">无旧限制截止记录；这不代表其他预约条件已经满足。</p>
        </template>
      </section>
    </div>

    <div v-if="auth.isOrdinary" class="split-right">
      <RestrictionsPanel /><ViolationsPanel /><AppealsPanel /><RemindersPanel />
      <section class="card">
        <div class="card-title-row">
          <h3>历史信用流水（只读）</h3>
          <span v-if="!creditLoading && !creditError && overview" class="muted">已读取 {{ overview.logs.length }} 条历史记录</span>
        </div>
        <p v-if="creditLoading" role="status">正在读取历史流水…</p><p v-else-if="creditError" class="muted">历史流水未读取成功，请使用左侧重试按钮。</p>
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
  </div>
</template>

<style scoped>
.contact-form { display: grid; gap: 14px; }
.contact-form label { display: grid; gap: 7px; font-size: var(--fs-caption); color: var(--text-2); }
.contact-error { color: var(--red-strong); }


.credit-table { min-width: 640px; }


.profile-card {
  text-align: center;
  background: var(--surface-2);
}
.avatar-lg {
  width: 76px;
  height: 76px;
  border-radius: var(--r-2xl);
  background: var(--primary-weak);
  color: var(--primary-active);
  display: grid;
  place-items: center;
  font-size: 32px;
  font-weight: 700;
  margin: 6px auto 14px;
}
.profile-name {
  font-size: 18px;
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
  padding: 10px 0;
  border-bottom: 1px solid var(--hairline);
  font-size: var(--fs-body-sm);
}
.info-row:last-child { border-bottom: none; }
.info-row span { color: var(--text-3); }
.info-row b { color: var(--text-1); font-weight: 600; }
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
