<script setup>
import { pageMotion } from '../composables/motion'
import { computed, ref, onMounted, onUnmounted, nextTick, watch } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { gsap } from 'gsap'
import { ScrollTrigger } from 'gsap/ScrollTrigger'
import { message } from '../components/ui/feedback'
import { useAuthStore } from '../stores/auth'
import { useNotificationStore } from '../stores/notification'
import { useThemeStore } from '../stores/theme'
import { useAvatarStore } from '../stores/avatar'
import { logout as logoutApi } from '../api/auth'
import AppIcon from '../components/AppIcon.vue'
import MobileTopbar from '../components/MobileTopbar.vue'
import SBadge from '../components/ui/SBadge.vue'
import SButton from '../components/ui/SButton.vue'

const router = useRouter()
const route = useRoute()
const auth = useAuthStore()
const notifStore = useNotificationStore()
const themeStore = useThemeStore()
const avatarStore = useAvatarStore()
const narrowMediaQuery = window.matchMedia('(max-width: 1024px)')
const isNarrowScreen = ref(narrowMediaQuery.matches)
const isSidebarCollapsed = ref(narrowMediaQuery.matches)
const isAtTop = ref(true)
const mobileTitleProgress = ref(0)
const mobilePageTitle = ref('')
const contentRef = ref(null)
const userMenuRef = ref(null)
const userMenuOpen = ref(false)
let titleMedia = null
let timer = null

const unread = computed(() => notifStore.unread)
const displayName = computed(() => auth.user?.real_name || auth.user?.username || '我的')
const avatarText = computed(() => (displayName.value || '?').slice(0, 1))
watch(() => auth.user?.id, () => avatarStore.load(), { immediate: true })

const menus = computed(() => auth.isAdmin
  ? [
      { index: '/', title: '首页', icon: 'home' },
      { index: '/analytics', title: '热力图统计', icon: 'analytics' },
      { index: '/admin', title: '管理端', icon: 'admin' }
    ]
  : [
      { index: '/', title: '首页', icon: 'home' },
      { index: '/booking', title: '座位预约', icon: 'booking' },
      { index: '/complaints', title: '求助与投诉', icon: 'ticket' },
      { index: '/notifications', title: '消息', icon: 'notification-empty' }
    ])

const userLinks = computed(() => auth.isAdmin
  ? [{ index: '/profile', title: '个人中心', icon: 'profile' }]
  : [
      { index: '/mine', title: '我的预约', icon: 'reservations' },
      { index: '/room-reviews', title: '空间评价', icon: 'ticket' },
      { index: '/profile', title: '个人中心', icon: 'profile' }
    ])

function refreshUnread() {
  notifStore.refresh()
}

function isActive(index) {
  if (index === '/') return route.path === '/'
  return route.path === index || route.path.startsWith(index + '/')
}

async function go(index) {
  userMenuOpen.value = false
  if (route.path !== index) await router.push(index)
  if (isNarrowScreen.value) closeSidebar()
}

function onLogout() {
  userMenuOpen.value = false
  const token = auth.token
  if (token) void logoutApi(token).catch(() => {})
  auth.logout()
  message.success('已退出登录')
  router.push('/login')
}

function setTheme(mode) {
  themeStore.setMode(mode)
}

function openSidebar() { isSidebarCollapsed.value = false }
function closeSidebar() { isSidebarCollapsed.value = true }

function onNarrowChange(event) {
  isNarrowScreen.value = event.matches
  isSidebarCollapsed.value = event.matches
  isAtTop.value = event.matches ? (contentRef.value?.scrollTop || 0) <= 1 : true
  nextTick(setupMobileTitleAnimation)
}

function onContentScroll(event) {
  if (!isNarrowScreen.value) return
  isAtTop.value = event.currentTarget.scrollTop <= 1
}

function onDocumentClick(event) {
  if (userMenuOpen.value && userMenuRef.value && !userMenuRef.value.contains(event.target)) userMenuOpen.value = false
}

function onKeydown(event) {
  if (event.key === 'Escape') {
    if (userMenuOpen.value) userMenuOpen.value = false
    if (isNarrowScreen.value && !isSidebarCollapsed.value) closeSidebar()
  }
}

function cleanupMobileTitleAnimation() {
  titleMedia?.revert()
  titleMedia = null
  mobileTitleProgress.value = 0
}

function setupMobileTitleAnimation() {
  cleanupMobileTitleAnimation()
  const scroller = contentRef.value
  const heading = scroller?.querySelector('[data-page-title]')
  if (!scroller || !heading) return
  mobilePageTitle.value = heading.querySelector('h1')?.textContent?.trim() || heading.textContent?.trim() || route.meta?.title || ''

  titleMedia = gsap.matchMedia()
  titleMedia.add(
    {
      isNarrow: '(max-width: 1024px)',
      reduceMotion: '(prefers-reduced-motion: reduce)'
    },
    (context) => {
      const { isNarrow, reduceMotion } = context.conditions
      if (!isNarrow) return

      if (reduceMotion) {
        const trigger = ScrollTrigger.create({
          trigger: heading,
          scroller,
          start: 'top 66px',
          onEnter: () => {
            gsap.set(heading, { autoAlpha: 0, y: 0 })
            mobileTitleProgress.value = 1
          },
          onLeaveBack: () => {
            gsap.set(heading, { autoAlpha: 1, y: 0 })
            mobileTitleProgress.value = 0
          }
        })
        return () => trigger.kill()
      }

      const tween = gsap.fromTo(
        heading,
        { autoAlpha: 1, y: 0 },
        {
          autoAlpha: 0,
          y: -12,
          ease: 'none',
          scrollTrigger: {
            trigger: heading,
            scroller,
            start: 'top 86px',
            end: 'top 46px',
            scrub: 0.18,
            invalidateOnRefresh: true,
            onUpdate: (self) => { mobileTitleProgress.value = self.progress }
          }
        }
      )
      return () => tween.kill()
    },
    scroller
  )
  ScrollTrigger.refresh()
}

watch(() => route.fullPath, async () => {
  userMenuOpen.value = false
  cleanupMobileTitleAnimation()
  mobilePageTitle.value = ''
  if (contentRef.value) contentRef.value.scrollTop = 0
  await nextTick()
  setupMobileTitleAnimation()
})

onMounted(() => {
  narrowMediaQuery.addEventListener('change', onNarrowChange)
  window.addEventListener('keydown', onKeydown)
  document.addEventListener('click', onDocumentClick)
  if (auth.isOrdinary) {
    refreshUnread()
    timer = setInterval(refreshUnread, 60000)
  }
  nextTick(setupMobileTitleAnimation)
})
onUnmounted(() => {
  cleanupMobileTitleAnimation()
  narrowMediaQuery.removeEventListener('change', onNarrowChange)
  window.removeEventListener('keydown', onKeydown)
  document.removeEventListener('click', onDocumentClick)
  if (timer) clearInterval(timer)
})
</script>

<template>
  <div class="shell">
    <MobileTopbar
      v-if="isNarrowScreen"
      :sidebar-collapsed="isSidebarCollapsed"
      :at-top="isAtTop"
      :menus="menus"
      :current-path="route.path"
      :page-title="mobilePageTitle"
      :title-progress="mobileTitleProgress"
      @open-sidebar="openSidebar"
      @navigate="go"
    />

    <div v-if="isNarrowScreen && !isSidebarCollapsed" class="drawer-backdrop" aria-hidden="true" @click="closeSidebar" @wheel.prevent @touchmove.prevent />

    <!-- 移动端抽屉 -->
    <aside v-if="isNarrowScreen" class="drawer" :class="{ collapsed: isSidebarCollapsed }" aria-label="主导航">
      <div class="drawer-brand">
        <span class="brand-mark" aria-hidden="true">
          <svg viewBox="0 0 24 24" width="24" height="24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round">
            <path d="M12 6.4c-1.8-1.4-4.2-2-7.2-2v13.2c3 0 5.4.6 7.2 2 1.8-1.4 4.2-2 7.2-2V4.4c-3 0-5.4.6-7.2 2Z" />
            <path d="M12 6.4v13.2" />
          </svg>
        </span>
        <div class="brand-copy">
          <div class="brand-name">静阅自习室</div>
          <div class="brand-sub">SMART STUDY ROOM</div>
        </div>
      </div>
      <nav class="drawer-menu">
        <button v-for="m in menus" :key="m.index" class="drawer-item" :class="{ active: isActive(m.index) }" @click="go(m.index)">
          <AppIcon :name="m.icon" :size="20" />
          <span>{{ m.title }}</span>
          <SBadge v-if="m.index === '/notifications' && unread > 0" :value="unread" :max="99" />
        </button>
        <button v-for="m in userLinks" :key="m.index" class="drawer-item" :class="{ active: isActive(m.index) }" @click="go(m.index)">
          <AppIcon :name="m.icon" :size="20" />
          <span>{{ m.title }}</span>
        </button>
      </nav>
      <div class="drawer-spacer" />
      <div class="drawer-user">
        <span class="avatar"><img v-if="avatarStore.data" :src="avatarStore.data" alt="本人头像" /><template v-else>{{ avatarText }}</template></span>
        <div class="drawer-user-meta">
          <b>{{ displayName }}</b>
          <small>{{ auth.roleLabel }}</small>
        </div>
        <button type="button" class="icon-btn" title="退出登录" aria-label="退出登录" @click="onLogout">
          <AppIcon name="logout" :size="18" />
        </button>
      </div>
    </aside>

    <!-- 桌面顶部导航 -->
    <header v-if="!isNarrowScreen" class="topnav">
      <div class="topnav-inner">
        <button type="button" class="brand" aria-label="返回首页" @click="go('/')">
          <span class="brand-mark" aria-hidden="true">
            <svg viewBox="0 0 24 24" width="26" height="26" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round">
              <path d="M12 6.4c-1.8-1.4-4.2-2-7.2-2v13.2c3 0 5.4.6 7.2 2 1.8-1.4 4.2-2 7.2-2V4.4c-3 0-5.4.6-7.2 2Z" />
              <path d="M12 6.4v13.2" />
            </svg>
          </span>
          <span class="brand-name">静阅自习室</span>
        </button>

        <nav class="topnav-links" aria-label="主导航">
          <button v-for="m in menus" :key="m.index" type="button" class="nav-link" :class="{ active: isActive(m.index) }" @click="go(m.index)">
            {{ m.title }}
            <span v-if="m.index === '/notifications' && unread > 0" class="nav-unread">{{ unread > 99 ? '99+' : unread }}</span>
          </button>
        </nav>

        <div class="topnav-actions">
          <button v-if="!auth.isAdmin" type="button" class="icon-btn bell-btn" title="消息中心" aria-label="消息中心" @click="go('/notifications')">
            <AppIcon name="notification-empty" :size="21" />
            <span v-if="unread > 0" class="bell-dot" aria-hidden="true" />
          </button>

          <div ref="userMenuRef" class="user-menu">
            <button type="button" class="user-chip" :aria-expanded="userMenuOpen" @click.stop="userMenuOpen = !userMenuOpen">
              <span class="avatar"><img v-if="avatarStore.data" :src="avatarStore.data" alt="本人头像" /><template v-else>{{ avatarText }}</template></span>
              <span class="user-chip-name">{{ displayName }}</span>
              <span class="caret" aria-hidden="true">▾</span>
            </button>
            <div v-if="userMenuOpen" class="user-pop">
              <div class="user-pop-head">
                <span class="avatar"><img v-if="avatarStore.data" :src="avatarStore.data" alt="本人头像" /><template v-else>{{ avatarText }}</template></span>
                <div><b>{{ displayName }}</b><small>{{ auth.roleLabel }}</small></div>
              </div>
              <button v-for="m in userLinks" :key="m.index" type="button" class="pop-item" @click="go(m.index)">
                <AppIcon :name="m.icon" :size="17" /> {{ m.title }}
              </button>
              <div class="pop-theme">
                <span>主题</span>
                <div class="theme-switch" role="radiogroup" aria-label="主题模式选择">
                  <button type="button" :class="{ active: themeStore.mode === 'light' }" @click="setTheme('light')">浅色</button>
                  <button type="button" :class="{ active: themeStore.mode === 'dark' }" @click="setTheme('dark')">深色</button>
                </div>
              </div>
              <button type="button" class="pop-item pop-danger" @click="onLogout">
                <AppIcon name="logout" :size="17" /> 退出登录
              </button>
            </div>
          </div>
        </div>
      </div>
    </header>

    <section ref="contentRef" class="content" @scroll="onContentScroll">
      <main class="page-body">
        <section v-if="auth.isOrdinary && notifStore.error" class="notification-retry" role="status">
          <p>{{ notifStore.hasValue ? '未读数保留上次成功值' : '未读数尚未成功读取' }}：{{ notifStore.error }}</p>
          <SButton size="sm" :disabled="notifStore.loading" @click="refreshUnread">重试未读数量</SButton>
        </section>
        <router-view v-slot="{ Component }">
          <Transition :css="false" mode="out-in" @enter="pageMotion.enter" @leave="pageMotion.leave" @enter-cancelled="pageMotion.cancel" @leave-cancelled="pageMotion.cancel" @after-enter="setupMobileTitleAnimation">
            <div :key="route.path" class="route-surface"><component :is="Component" /></div>
          </Transition>
        </router-view>
      </main>
    </section>
  </div>
</template>

<style scoped>
.shell {
  height: 100%;
  display: flex;
  flex-direction: column;
  background: var(--canvas);
}
.notification-retry { max-width: 1560px; width: 100%; margin: 12px auto 0; padding: 8px 40px; font-size: var(--fs-caption); overflow-wrap: anywhere; }
.notification-retry p { margin-bottom: 6px; }

/* ============ 桌面顶部导航 ============ */
.topnav {
  position: sticky;
  top: 0;
  z-index: var(--z-nav);
  flex: 0 0 auto;
  background: color-mix(in srgb, var(--canvas) 92%, transparent);
  backdrop-filter: blur(10px);
  border-bottom: 1px solid var(--border);
}
.topnav-inner {
  width: 100%;
  max-width: 1560px;
  height: 82px;
  margin: 0 auto;
  padding: 0 40px;
  display: flex;
  align-items: center;
  gap: 28px;
}
.brand {
  all: unset;
  display: inline-flex;
  align-items: center;
  gap: 10px;
  cursor: pointer;
  color: var(--text-1);
  flex: 0 0 auto;
}
.brand-mark { color: var(--primary); display: grid; place-items: center; }
.brand-name {
  font-family: var(--font-display);
  font-size: 22px;
  font-weight: 600;
  letter-spacing: .06em;
  white-space: nowrap;
}
.topnav-links {
  flex: 1;
  min-width: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 10px;
}
.nav-link {
  all: unset;
  position: relative;
  padding: 10px 18px;
  border-radius: var(--r-md);
  color: var(--text-2);
  font-size: 15px;
  cursor: pointer;
  white-space: nowrap;
  transition: color var(--dur-1) var(--ease), background var(--dur-1) var(--ease);
}
.nav-link:hover { color: var(--text-1); background: var(--surface-2); }
.nav-link.active { color: var(--primary); font-weight: 600; }
.nav-link.active::after {
  content: '';
  position: absolute;
  left: 22%;
  right: 22%;
  bottom: 2px;
  height: 2px;
  border-radius: var(--r-full);
  background: var(--primary);
}
.nav-unread {
  display: inline-grid;
  place-items: center;
  min-width: 18px;
  height: 18px;
  margin-left: 6px;
  padding: 0 5px;
  border-radius: var(--r-full);
  background: var(--primary);
  color: var(--text-inverse);
  font-size: 11px;
  line-height: 1;
  vertical-align: 2px;
}
.topnav-actions { display: flex; align-items: center; gap: 8px; flex: 0 0 auto; }
.icon-btn {
  all: unset;
  width: 40px;
  height: 40px;
  border-radius: var(--r-full);
  display: grid;
  place-items: center;
  color: var(--text-2);
  cursor: pointer;
  position: relative;
  transition: background var(--dur-1) var(--ease), color var(--dur-1) var(--ease);
}
.icon-btn:hover { background: var(--surface-2); color: var(--text-1); }
.bell-dot {
  position: absolute;
  top: 8px;
  right: 8px;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--red);
  border: 2px solid var(--canvas);
}
.user-menu { position: relative; }
.user-chip {
  all: unset;
  display: inline-flex;
  align-items: center;
  gap: 9px;
  padding: 4px 10px 4px 4px;
  border-radius: var(--r-full);
  cursor: pointer;
  color: var(--text-1);
  transition: background var(--dur-1) var(--ease);
}
.user-chip:hover { background: var(--surface-2); }
.avatar {
  width: 34px;
  height: 34px;
  flex: 0 0 34px;
  border-radius: 50%;
  display: grid;
  place-items: center;
  background: var(--primary-weak);
  color: var(--primary-active);
  font-family: var(--font-display);
  font-weight: 600;
  font-size: 15px;
  overflow: hidden;
}
.avatar img { width: 100%; height: 100%; object-fit: cover; display: block; }
.user-chip-name { font-size: 14px; max-width: 120px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.caret { color: var(--text-3); font-size: 11px; }
.user-pop {
  position: absolute;
  right: 0;
  top: calc(100% + 10px);
  width: 232px;
  padding: 8px;
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--r-lg);
  box-shadow: var(--shadow-3);
  z-index: var(--z-popover);
}
.user-pop-head {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 10px 12px;
  border-bottom: 1px solid var(--hairline);
  margin-bottom: 6px;
}
.user-pop-head b { display: block; font-size: 14px; color: var(--text-1); }
.user-pop-head small { color: var(--text-3); font-size: var(--fs-caption); }
.pop-item {
  all: unset;
  width: 100%;
  box-sizing: border-box;
  display: flex;
  align-items: center;
  gap: 9px;
  padding: 10px;
  border-radius: var(--r-sm);
  color: var(--text-2);
  font-size: 14px;
  cursor: pointer;
}
.pop-item:hover { background: var(--surface-2); color: var(--text-1); }
.pop-danger { color: var(--red-strong); }
.pop-danger:hover { background: var(--red-weak); color: var(--red-strong); }
.pop-theme {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 10px;
  margin-top: 4px;
  border-top: 1px solid var(--hairline);
  color: var(--text-3);
  font-size: var(--fs-caption);
}
.theme-switch { display: inline-flex; gap: 2px; padding: 2px; background: var(--surface-3); border-radius: var(--r-sm); }
.theme-switch button {
  all: unset;
  padding: 4px 7px;
  border-radius: 5px;
  font-size: 11px;
  color: var(--text-3);
  cursor: pointer;
}
.theme-switch button.active { background: var(--surface); color: var(--text-1); box-shadow: var(--shadow-1); }

/* ============ 内容区 ============ */
.content {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  overflow-x: hidden;
}
.page-body { min-height: 100%; }

/* ============ 移动端抽屉 ============ */
.drawer-backdrop {
  position: fixed;
  z-index: 40;
  inset: 0;
  background: var(--backdrop);
  backdrop-filter: blur(1px);
}
.drawer {
  position: fixed;
  z-index: 50;
  inset: 0 auto 0 0;
  width: 280px;
  min-height: 100%;
  display: flex;
  flex-direction: column;
  padding: 20px 14px 16px;
  background: var(--surface);
  border-right: 1px solid var(--border);
  box-shadow: 12px 0 32px rgba(35, 55, 45, .18);
  transition: transform var(--dur-3) var(--ease), visibility 0s linear 0s;
}
.drawer.collapsed {
  transform: translateX(-100%);
  visibility: hidden;
  transition: transform var(--dur-3) var(--ease), visibility 0s linear .24s;
}
.drawer-brand { display: flex; align-items: center; gap: 10px; padding: 6px 8px 20px; }
.drawer-brand .brand-name { font-size: 20px; }
.brand-sub { font-size: 9px; letter-spacing: .14em; color: var(--text-4); margin-top: 2px; }
.drawer-menu { display: flex; flex-direction: column; gap: 4px; }
.drawer-item {
  all: unset;
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 14px;
  border-radius: var(--r-md);
  color: var(--text-2);
  font-size: 15px;
  cursor: pointer;
}
.drawer-item:hover { background: var(--surface-2); color: var(--text-1); }
.drawer-item.active { background: var(--primary-weak); color: var(--primary-active); font-weight: 600; }
.drawer-spacer { flex: 1; }
.drawer-user {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px 10px;
  border-top: 1px solid var(--hairline);
}
.drawer-user-meta { flex: 1; min-width: 0; }
.drawer-user-meta b { display: block; font-size: 14px; color: var(--text-1); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.drawer-user-meta small { color: var(--text-3); font-size: var(--fs-caption); }

@media (max-width: 1100px) {
  .topnav-inner { padding: 0 22px; gap: 14px; }
  .nav-link { padding: 10px 12px; }
  .user-chip-name { display: none; }
}
</style>
