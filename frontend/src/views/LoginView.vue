<script setup>
import { reactive, ref, computed } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { message } from '../components/ui/feedback'
import { login, updateProfile, changePassword } from '../api/auth'
import { useAuthStore } from '../stores/auth'
import AppIcon from '../components/AppIcon.vue'
import SButton from '../components/ui/SButton.vue'
import SInput from '../components/ui/SInput.vue'
import { SYSTEM_NAME, SYSTEM_NAME_ZH } from '../config/branding'

const router = useRouter()
const route = useRoute()
const auth = useAuthStore()

const loading = ref(false)
const loginError = ref('')
const setupVisible = ref(false)
const setupSaving = ref(false)
const setupError = ref('')
const showSetup = computed(() => setupVisible.value || !!auth.user?.must_change_password)

const loginForm = reactive({ username: '202600001', password: '123456' })
const setupForm = reactive({ current_password: '', phone: '', email: '', new_password: '', confirm_password: '' })

async function onLogin() {
  if (loading.value) return
  if (!loginForm.username || !loginForm.password) {
    message.warning('请输入学号与密码')
    return
  }
  loginError.value = ''
  loading.value = true
  try {
    const resp = await login(loginForm)
    auth.setLogin(resp.data)
    if (resp.data.user?.must_change_password) {
      setupForm.current_password = loginForm.password
      setupForm.phone = resp.data.user.phone || ''
      setupForm.email = resp.data.user.email || ''
      setupVisible.value = true
      setupError.value = ''
      message.warning('初次使用请先设置联系电话并修改登录密码')
      return
    }
    message.success('登录成功')
    router.push(route.query.redirect || '/')
  } catch (error) {
    loginError.value = error.message
  } finally {
    loading.value = false
  }
}

function cancelSetup() {
  auth.logout()
  setupVisible.value = false
  setupError.value = ''
  router.replace('/login')
}

async function onFirstSetup() {  if (setupSaving.value) return
  setupError.value = ''
  const current = setupForm.current_password
  const phone = setupForm.phone.trim()
  const newPassword = setupForm.new_password
  const confirm = setupForm.confirm_password
  if (!current) { setupError.value = '请输入当前密码'; return }
  if (!phone) { setupError.value = '联系电话必填'; return }
  if (phone.length > 30) { setupError.value = '联系电话不超过30个字符'; return }
  if (newPassword.length < 6 || newPassword.length > 64) { setupError.value = '新密码须为6至64个字符'; return }
  if (newPassword !== confirm) { setupError.value = '两次输入的新密码不一致'; return }
  if (newPassword === current) { setupError.value = '新密码不能与当前密码相同'; return }
  setupSaving.value = true
  try {
    await updateProfile({ phone, email: setupForm.email.trim() })
    const resp = await changePassword({ current_password: current, new_password: newPassword })
    auth.setUser({ ...resp.data, must_change_password: false })
    setupVisible.value = false
    message.success('设置完成，正在进入系统')
    router.push(route.query.redirect || auth.roleHomePath)
  } catch (error) {
    setupError.value = error.message
  } finally {
    setupSaving.value = false
  }
}

const features = [
  { icon: 'seat', title: '可视化选座' },
  { icon: 'booking', title: '分钟预约' },
  { icon: 'check', title: '扫码签到' },
  { icon: 'reservations', title: '本人记录' }
]
</script>

<template>
  <div v-reveal class="auth-shell">
    <!-- ============ 左侧：品牌叙事 ============ -->
    <aside class="auth-hero">
      <div class="hero-brand">
        <div class="hero-mark"><AppIcon name="book" :size="20" /></div>
        <div>
          <div class="hero-name">{{ SYSTEM_NAME_ZH }}</div>
          <div class="hero-sub">{{ SYSTEM_NAME }}</div>
        </div>
      </div>

      <div class="hero-body">
        <div class="hero-copy">
          <h1 class="hero-title">让每一次自习都有理想的位置</h1>
          <p class="hero-desc">分钟预约 · 可视化选座 · 面向校园的自习室数字化系统</p>
        </div>

        <ul class="hero-features">
          <li v-for="f in features" :key="f.title">
            <span class="feature-icon"><AppIcon :name="f.icon" :size="18" /></span>
            <span class="feature-title">{{ f.title }}</span>
          </li>
        </ul>
      </div>

      <div class="hero-foot">
        <img src="/images/login-reader.png?v=2" alt="女生在书桌前安静阅读" />
      </div>
    </aside>

    <!-- ============ 右侧：校园账号登录 ============ -->
    <main class="auth-panel">
      <div v-if="!showSetup" class="auth-card"><header class="auth-heading"><h2>欢迎回来</h2><p>为今天的学习，留一个好位置。</p></header>
        <form class="auth-form" @submit.prevent="onLogin">
          <p v-if="loginError" role="alert" class="form-label">{{ loginError }}</p>
          <div class="form-field">
            <label class="form-label" for="login-username">学号 <span class="req">*</span></label>
            <SInput id="login-username" v-model="loginForm.username" size="lg" placeholder="请输入学号" autocomplete="username" />
          </div>
          <div class="form-field">
            <label class="form-label" for="login-password">密码 <span class="req">*</span></label>
            <SInput
              id="login-password"
              v-model="loginForm.password"
              type="password"
              size="lg"
              placeholder="密码"
              show-password
              autocomplete="current-password"
            />
          </div>
          <SButton type="submit" variant="primary" size="lg" block :loading="loading">
            登录
          </SButton>
        </form>

        <p class="auth-tip">校园账号由管理员维护，暂不开放自助注册。</p>
        <p class="auth-tip">
          演示学生：202600001 / 123456<br />管理员：202630001 / admin123
        </p>
      </div>

      <div v-else class="auth-card">
        <header class="auth-heading"><h2>首次使用设置</h2><p>请设置联系电话并修改初始登录密码，完成后才能进入系统。</p></header>
        <form class="auth-form" @submit.prevent="onFirstSetup">
          <p v-if="setupError" role="alert" class="form-label">{{ setupError }}</p>
          <div class="form-field">
            <label class="form-label" for="setup-current">当前密码 <span class="req">*</span></label>
            <SInput id="setup-current" v-model="setupForm.current_password" type="password" size="lg" placeholder="当前登录密码" show-password autocomplete="current-password" />
          </div>
          <div class="form-field">
            <label class="form-label" for="setup-phone">联系电话 <span class="req">*</span></label>
            <SInput id="setup-phone" v-model="setupForm.phone" type="tel" size="lg" maxlength="30" placeholder="请输入联系电话" autocomplete="tel" />
          </div>
          <div class="form-field">
            <label class="form-label" for="setup-new">新密码 <span class="req">*</span></label>
            <SInput id="setup-new" v-model="setupForm.new_password" type="password" size="lg" placeholder="6至64个字符" show-password autocomplete="new-password" />
          </div>
          <div class="form-field">
            <label class="form-label" for="setup-confirm">确认新密码 <span class="req">*</span></label>
            <SInput id="setup-confirm" v-model="setupForm.confirm_password" type="password" size="lg" placeholder="再次输入新密码" show-password autocomplete="new-password" />
          </div>
          <div class="form-field">
            <label class="form-label" for="setup-email">邮箱（选填）</label>
            <SInput id="setup-email" v-model="setupForm.email" type="email" size="lg" maxlength="254" placeholder="选填" autocomplete="email" />
          </div>
          <SButton type="submit" variant="primary" size="lg" block :loading="setupSaving">
            完成设置并进入系统
          </SButton>
        </form>
        <p class="auth-tip"><button type="button" class="setup-logout" @click="cancelSetup">退出登录</button></p>
      </div>
    </main>
  </div>
</template>

<style scoped>
.auth-shell {
  height: 100%;
  display: grid;
  grid-template-columns: minmax(0, 1.12fr) minmax(420px, .88fr);
  background: var(--canvas);
}

/* ============ 左侧品牌区 ============ */
.auth-hero {
  position: relative;
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  padding: 56px 60px 40px;
  background:
    radial-gradient(circle at 88% 6%, rgba(47, 93, 70, .08), transparent 42%),
    radial-gradient(circle at 4% 92%, rgba(47, 93, 70, .05), transparent 40%),
    var(--canvas);
  overflow: hidden;
}

.auth-hero::before {
  content: '';
  position: absolute;
  inset: 0;
  background-image: radial-gradient(rgba(28, 37, 52, .05) 1px, transparent 1px);
  background-size: 26px 26px;
  mask-image: linear-gradient(to bottom, transparent 12%, #000 42%, #000 78%, transparent);
  pointer-events: none;
}

.hero-brand {
  position: relative;
  display: flex;
  align-items: center;
  gap: 12px;
}

.hero-mark {
  width: 40px;
  height: 40px;
  border-radius: var(--r-lg);
  background: var(--primary);
  color: #fff;
  display: grid;
  place-items: center;
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, .18), 0 4px 12px rgba(59, 102, 218, .32);
}

.hero-name {
  font-size: 16px;
  font-weight: 650;
  color: var(--text-1);
  letter-spacing: -.01em;
}

.hero-sub {
  font-size: 10.5px;
  color: var(--text-4);
  letter-spacing: .08em;
  text-transform: uppercase;
  margin-top: 1px;
}

.hero-body {
  position: relative;
  padding: 24px 0;
}

.hero-copy {
  width: 100%;
  max-width: 100%;
  text-align: center;
}

.hero-title {
  font-family: var(--font-display);
  font-size: clamp(30px, 3vw, 52px);
  line-height: 1.22;
  font-weight: 600;
  letter-spacing: .02em;
  color: var(--text-1);
  white-space: nowrap;
}

.hero-desc {
  width: 100%;
  margin-top: 18px;
  font-size: clamp(13px, 1.15vw, 15px);
  line-height: 1.75;
  color: var(--text-3);
  white-space: nowrap;
  text-align: center;
}

.hero-features {
  list-style: none;
  margin: 42px 0 0;
  padding: 0;
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 14px;
}

.hero-features li {
  display: flex;
  gap: 10px;
  align-items: center;
  padding: 16px 18px;
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--r-lg);
  box-shadow: var(--shadow-1);
}

.feature-icon {
  width: 34px;
  height: 34px;
  flex: 0 0 34px;
  border-radius: var(--r-md);
  background: var(--surface-3);
  color: var(--primary);
  display: grid;
  place-items: center;
}

.feature-title {
  font-size: 15px;
  font-weight: 600;
  color: var(--text-1);
  white-space: nowrap;
}

@media (min-width: 1280px) {
  .hero-features { grid-template-columns: repeat(4, minmax(0, 1fr)); }
  .hero-features li { gap: 8px; padding: 14px 12px; }
  .feature-icon { width: 32px; height: 32px; flex-basis: 32px; }
  .feature-title { font-size: 14px; }
}

.hero-foot {
  position: relative;
  width: min(calc(100% + 52px), 620px);
  height: min(30vh, 240px);
  min-height: 150px;
  margin-left: -52px;
  margin-bottom: -32px;
  display: flex;
  align-items: flex-end;
}

.hero-foot img {
  display: block;
  width: 100%;
  height: 100%;
  object-fit: contain;
  object-position: left bottom;
}

/* ============ 右侧表单区 ============ */
.auth-panel {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 40px 48px;
  overflow-y: auto;
}

.auth-card {
  width: 460px;
  max-width: 100%;
  padding: 36px 38px 28px;
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--r-2xl);
  box-shadow: var(--shadow-3);
}

.auth-heading h2 {
  font-family: var(--font-display);
  font-size: 34px;
  font-weight: 600;
  color: var(--text-1);
}

.auth-form {
  display: flex;
  flex-direction: column;
  gap: 16px;
  margin-top: 22px;
}

.form-field {
  display: flex;
  flex-direction: column;
  gap: 7px;
}

.form-label {
  font-size: var(--fs-caption);
  font-weight: 600;
  color: var(--text-2);
  letter-spacing: .02em;
}
.req { color: var(--red); margin-left: 2px; }
.setup-logout {
  all: unset;
  color: var(--primary);
  font-size: var(--fs-caption);
  cursor: pointer;
}
.setup-logout:hover { text-decoration: underline; }

.auth-form .s-btn {
  margin-top: 10px;
  height: 50px;
}

.auth-tip {
  margin-top: 18px;
  font-size: var(--fs-caption);
  color: var(--text-4);
  text-align: center;
  line-height: 1.7;
  padding-top: 16px;
  border-top: 1px solid var(--hairline);
}

/* ============ 响应式 ============ */
@media (max-width: 920px) {
  .auth-shell {
    grid-template-columns: 1fr;
  }
  .auth-hero {
    display: none;
  }
  .auth-panel {
    background:
      radial-gradient(circle at 85% 4%, rgba(59, 102, 218, .06), transparent 44%),
      var(--canvas);
  }
  .auth-card {
    background: var(--surface);
    border: 1px solid var(--border);
    border-radius: var(--r-xl);
    box-shadow: var(--shadow-2);
    padding: 26px 24px 20px;
  }
}

@media (max-width: 480px) {
  .auth-panel {
    padding: 20px 14px;
    align-items: flex-start;
    padding-top: max(20px, 8vh);
  }
  .auth-card {
    padding: 20px 16px 16px;
  }
}
</style>
