<script setup>
import StudyTips from '../components/StudyTips.vue'
import SAnimatedNumber from '../components/ui/SAnimatedNumber.vue'
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import { useNotificationStore } from '../stores/notification'
import { getRooms, getOpeningWindows } from '../api/room'
import { getOverview } from '../api/stats'
import AppIcon from '../components/AppIcon.vue'
import SBanner from '../components/ui/SBanner.vue'
import SButton from '../components/ui/SButton.vue'

const router = useRouter()
const auth = useAuthStore()
const notifStore = useNotificationStore()

const who = computed(() => auth.user?.real_name || auth.user?.username || '同学')
const roleText = computed(() => auth.roleLabel)
const helloText = computed(() => (auth.isAdmin ? '欢迎回来，请查看今日运营情况' : '今天也要高效学习'))
const todayText = computed(() =>
  new Date().toLocaleDateString('zh-CN', { month: 'long', day: 'numeric', weekday: 'long' })
)
const greeting = computed(() => {
  const h = new Date().getHours()
  if (h < 6) return '夜深了'
  if (h < 12) return '早上好'
  if (h < 14) return '中午好'
  if (h < 18) return '下午好'
  return '晚上好'
})

const rooms = ref([])
const todayOpenings = ref({})
const campusDate = new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Shanghai', year:'numeric', month:'2-digit', day:'2-digit' }).format(new Date())
function roomOpeningText(room) {
  const value = todayOpenings.value[room.id]
  if (!value) return '开放信息尚未加载'
  if (value.error) return `开放信息读取失败：${value.error}`
  return value.windows.length ? value.windows.map(window => `${window.start}–${window.end}`).join('、') : `当日闭馆：${value.closed_reasons?.join('；') || '无开放时段'}`
}
const statsOverview = ref(null)
const statsError = ref('')
const statsLoading = ref(false)
const roomsError = ref('')
const roomsLoading = ref(false)
const unread = computed(() => notifStore.unread)
let unreadTimer = null

const totalSeats = computed(() => statsOverview.value?.total_seats ?? null)
const inUseNow = computed(() => statsOverview.value?.in_use_now ?? null)
const availableSeats = computed(() => totalSeats.value === null || inUseNow.value === null ? null : Math.max(0, totalSeats.value - inUseNow.value))
const roomCount = computed(() => rooms.value.length)
const openHours = computed(() => {
  const available = rooms.value.find(room => room.enabled && todayOpenings.value[room.id]?.windows?.length)
  if (available) return { name: available.name, text: roomOpeningText(available) }
  if (roomsLoading.value) return { name: '正在加载', text: '—' }
  if (roomsError.value || Object.values(todayOpenings.value).some(value => value.error)) return { name: '读取失败，请重试', text: '—' }
  return { name: '当日无开放时段', text: '—' }
})

function refreshUnread() {
  notifStore.refresh()
}

async function loadRooms() {
  roomsLoading.value = true
  roomsError.value = ''
  try {
    const roomResp = await getRooms()
    rooms.value = roomResp.data || []
    const openings = await Promise.allSettled(rooms.value.map(room => getOpeningWindows(room.id, campusDate)))
    todayOpenings.value = Object.fromEntries(openings.map((result,index) => [rooms.value[index].id, result.status === 'fulfilled' ? result.value.data : { error: result.reason.message }]))
  } catch (error) {
    roomsError.value = error.message
  } finally {
    roomsLoading.value = false
  }
}

async function loadStats() {
  if (!auth.isAdmin || statsLoading.value) return
  statsLoading.value = true
  statsError.value = ''
  try {
    const statsResp = await getOverview()
    statsOverview.value = statsResp.data
  } catch (error) {
    statsError.value = error.message
  } finally {
    statsLoading.value = false
  }
}

onMounted(async () => {
  await Promise.all([loadRooms(), loadStats()])
  refreshUnread()
  unreadTimer = setInterval(refreshUnread, 30000)
})

onUnmounted(() => {
  if (unreadTimer) {
    clearInterval(unreadTimer)
    unreadTimer = null
  }
})

const shortcuts = computed(() => auth.isAdmin
  ? [
      { title: '热力图统计', desc: '座位利用率 · 高峰时段', icon: 'analytics', to: '/analytics', tone: 'warm' },
      { title: '管理端', desc: '自习室 · 座位 · 用户', icon: 'admin', to: '/admin', tone: 'primary' },
      { title: '个人中心', desc: '查看管理员账户信息', icon: 'profile', to: '/profile', tone: 'violet' }
    ]
  : [
      { title: '座位预约', desc: '分钟时段 · 平面图与3D选座', icon: 'booking', to: '/booking', tone: 'primary' },
      { title: '我的预约', desc: '扫码签到 · 使用记录 · 提前签退', icon: 'reservations', to: '/mine', tone: 'success' },
      { title: '历史候补', desc: '查原记录 · 可退出历史等待', icon: 'waitlist', to: '/waitlist', tone: 'warm' },
      {
        title: '消息中心',
        desc: unread.value > 0 ? `${unread.value} 条未读消息` : '预约结果 · 历史消息',
        icon: unread.value > 0 ? 'notification' : 'notification-empty',
        to: '/notifications',
        tone: 'violet'
      }
    ])
const cardToneClass = {
  primary: 'tone-primary',
  success: 'tone-success',
  warm: 'tone-warm',
  violet: 'tone-violet'
}
</script>

<template>
  <div v-reveal class="page-view">
    <header class="view-heading" data-page-title>
      <h1>{{ greeting }}，{{ who }}</h1>
      <p class="heading-sub">{{ todayText }} · {{ helloText }}</p>
    </header>
    <!-- 首页：页面级双栏 -->
    <div class="split home-split">
    <!-- 左栏：账户 KPI + 快速贴士 -->
    <div class="split-left">
      <section v-if="auth.isOrdinary" class="card overview-card">
        <div class="card-title-row">
          <h3>账户概览</h3>
          <span class="role-pill" :class="auth.isAdmin ? 'role-pill--admin' : 'role-pill--student'">{{ roleText }}</span>
        </div>
        <div class="kpi-grid">
          <div class="kpi">
            <div class="kpi-num"><SButton variant="ghost" @click="router.push('/profile')">查看本人违规</SButton></div>
            <div class="kpi-label">独立事件明细与历史资料</div>
          </div>
          <div class="kpi">
            <div class="kpi-num"><SAnimatedNumber v-if="!roomsLoading && !roomsError" :value="roomCount" /><span v-else>—</span></div>
            <div class="kpi-label">自习室可用</div>
          </div>
          <div class="kpi">
            <div class="kpi-num"><SButton variant="ghost" @click="router.push('/booking')">查询座位</SButton></div>
            <div class="kpi-label">按日期与时段查询可约座位</div>
          </div>
          <div class="kpi">
            <div class="kpi-num kpi-time">
              <span class="kpi-time-start">{{ openHours.text }}</span>
              <span class="kpi-time-end">{{ openHours.name }}</span>
            </div>
            <div class="kpi-label">当日开放示例（校园时区）</div>
          </div>
        </div>
      </section>

      <section v-else class="card overview-card">
        <div class="card-title-row">
          <h3>管理概览</h3>
          <span class="role-pill role-pill--admin">管理员</span>
        </div>
        <p v-if="statsLoading" class="muted">正在加载统计…</p>
        <SBanner v-if="statsError" type="error" :title="statsError"><SButton size="sm" @click="loadStats">重试统计</SButton></SBanner>
        <div class="kpi-grid">
          <div class="kpi">
            <div class="kpi-num"><SAnimatedNumber v-if="!roomsLoading && !roomsError" :value="roomCount" /><span v-else>—</span></div>
            <div class="kpi-label">自习室</div>
          </div>
          <div class="kpi kpi-ok">
            <div class="kpi-num">
              <SAnimatedNumber v-if="availableSeats !== null" :value="availableSeats" /><span v-else>—</span><span class="kpi-slash">/</span><span class="kpi-total">{{ totalSeats ?? '—' }}</span>
            </div>
            <div class="kpi-label">可用 / 总座位</div>
          </div>
          <div class="kpi">
            <div class="kpi-num">2</div>
            <div class="kpi-label">管理模块</div>
          </div>
          <div class="kpi">
            <div class="kpi-num kpi-time">
              <span class="kpi-time-start">{{ openHours.text }}</span>
              <span class="kpi-time-end">{{ openHours.name }}</span>
            </div>
            <div class="kpi-label">当日开放示例（校园时区）</div>
          </div>
        </div>
      </section>


    </div>

    <!-- 右栏：功能入口卡 -->
    <div class="split-right">
      <section class="card shortcuts-card">
        <div class="card-title-row">
          <h3>快速入口</h3>
          <span class="muted">点击卡片直接进入对应功能</span>
        </div>
        <div class="shortcut-grid">
          <button
            v-for="s in shortcuts"
            :key="s.to"
            class="shortcut"
            :class="cardToneClass[s.tone]"
            @click="router.push(s.to)"
          >
            <div class="shortcut-top">
              <div class="shortcut-icon"><AppIcon :name="s.icon" :size="24" /></div>
              <div class="chev"><AppIcon name="chevron-right" :size="18" /></div>
            </div>
            <div class="shortcut-body">
              <div class="shortcut-title">{{ s.title }}</div>
              <div class="shortcut-desc">{{ s.desc }}</div>
            </div>
          </button>
        </div>
      </section>
      <StudyTips :title="auth.isOrdinary ? '使用小贴士' : '管理提示'">
        <ul v-if="auth.isOrdinary" class="tips tips-list">
          <li>开始前取消不登记新违规；开始后未签到主动放弃会登记一次未使用违约</li>
          <li>到开始后15分钟仍未签到，系统结束预约、释放余时并登记一次未签到违约</li>
          <li>按服务端校园日期选择今天至后天，预约至少<b>30分钟</b>，使用分钟起止</li>
          <li>历史信用只读，不再新增奖惩或低分触发；已有旧限制由服务端按原截止核对</li>
        </ul>
        <ul v-else class="tips tips-list">
          <li>定期检查自习室开放时间与座位规模是否准确</li>
          <li>维护中的座位不会开放给普通用户预约</li>
          <li>可在用户管理中启用或禁用普通用户账号</li>
          <li>通过热力图了解座位利用率和高峰时段</li>
        </ul>
            </StudyTips>
      <section class="card rooms-card">
        <div class="card-title-row"><h3>找到你的专注空间</h3><span class="muted">自习室</span></div>
        <div class="room-list">
          <button v-for="room in rooms" :key="room.id" class="room-link" @click="router.push(auth.isAdmin ? '/admin' : '/booking')">
            <span><b>{{ room.name }}</b><small>{{ room.location }} · {{ roomOpeningText(room) }}</small></span><span aria-hidden="true">↗</span>
          </button>
          <p v-if="roomsLoading" class="muted">正在加载自习室…</p>
          <SBanner v-else-if="roomsError" type="error" :title="roomsError"><SButton size="sm" @click="loadRooms">重试</SButton></SBanner>
          <p v-else-if="!rooms.length" class="muted">暂无自习室信息</p>
        </div>
      </section>

      <section class="card intro-card">
        <div class="card-title-row">
          <h3>系统介绍</h3>
        </div>
        <div v-if="auth.isOrdinary" class="intro-grid">
          <div class="intro-cell">
            <div class="intro-label">在线预约</div>
            <div class="intro-text">平面图可视化选座，时段冲突自动检测，支持扫码签到、提前签退与到期自动结算；短暂离开不延长原结束。</div>
          </div>
          <div class="intro-cell">
            <div class="intro-label">分钟预约</div>
            <div class="intro-text">按开放窗口选择分钟起止，结束可填24:00；提交时重新核对资格和时段冲突。</div>
          </div>
          <div class="intro-cell">
            <div class="intro-label">历史候补</div>
            <div class="intro-text">保留原候补状态及预约来源，可查询历史记录并退出仍在等待的条目；不再新增或递补。</div>
          </div>
          <div class="intro-cell">
            <div class="intro-label">独立违规记录</div>
            <div class="intro-text">本人查询自动未使用违约明细及有效性；历史信用流水另行只读保留，不按分数换算违规。</div>
          </div>
        </div>
        <div v-else class="intro-grid">
          <div class="intro-cell">
            <div class="intro-label">资源管理</div>
            <div class="intro-text">维护自习室名称、位置、开放时间和座位规模，统一管理可预约资源。</div>
          </div>
          <div class="intro-cell">
            <div class="intro-label">座位维护</div>
            <div class="intro-text">批量生成座位并维护座位属性与状态，确保不可用座位及时下线。</div>
          </div>
          <div class="intro-cell">
            <div class="intro-label">用户管理</div>
            <div class="intro-text">查看校园身份与账号状态，按需维护和启停账号；历史信用仅作溯源。</div>
          </div>
          <div class="intro-cell">
            <div class="intro-label">运营分析</div>
            <div class="intro-text">查看座位利用率和时段热力分布，为资源配置与开放安排提供参考。</div>
          </div>
        </div>
      </section>
    </div>
    </div>
  </div>
</template>

<style scoped>


.role-pill {
  font-size: 11px;
  font-weight: 500;
  line-height: 1;
  padding: 4px 9px;
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

/* KPI 中的可用/总数格式美化 */
.kpi-slash {
  font-size: 14px;
  margin: 0 1px;
  color: var(--text-4);
  font-weight: 500;
}
.kpi-total {
  font-size: 14px;
  color: var(--text-3);
  font-weight: 500;
}
.kpi-time {
  font-size: 19px;
  display: flex;
  align-items: baseline;
}
.kpi-time-start,
.kpi-time-end {
  display: inline-flex;
  align-items: baseline;
}
.kpi-tilde {
  font-size: 13px;
  color: var(--text-4);
  margin: 0 2px;
  font-weight: 500;
}

/* 贴士 */
.tips-list {
  margin-top: 2px;
}

/* 快速入口卡片 */
.shortcuts-card {
  display: flex;
  flex-direction: column;
}

.shortcut-grid {
  flex: 1;
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 12px;
  min-height: 0;
}

.shortcut {
  all: unset;
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  height: 100%;
  min-height: 148px;
  padding: 18px 16px 16px;
  border-radius: var(--r-xl);
  cursor: pointer;
  border: 1px solid var(--border);
  background: var(--surface);
  transition: transform var(--dur-2) var(--ease), box-shadow var(--dur-2) var(--ease),
    border-color var(--dur-2) var(--ease);
  box-sizing: border-box;
}

.shortcut:hover {
  transform: translateY(-3px);
  box-shadow: var(--shadow-2);
  border-color: var(--border-strong);
}

.shortcut:focus-visible {
  outline: 2px solid var(--primary);
  outline-offset: 2px;
}

.shortcut-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 18px;
}

.shortcut-icon {
  width: 44px;
  height: 44px;
  border-radius: var(--r-lg);
  display: grid;
  place-items: center;
  flex-shrink: 0;
  transition: transform var(--dur-2) var(--ease-out);
}

.shortcut:hover .shortcut-icon {
  transform: scale(1.05);
}

.shortcut-body {
  min-width: 0;
}

.shortcut-title {
  font-size: 16px;
  font-weight: 650;
  color: var(--text-1);
  line-height: 1.3;
  margin-bottom: 5px;
  letter-spacing: -.01em;
  transition: color var(--dur-1) var(--ease);
}

.shortcut:hover .shortcut-title {
  color: var(--primary-active);
}

.shortcut-desc {
  font-size: var(--fs-caption);
  color: var(--text-3);
  line-height: 1.55;
  min-height: calc(1.55em * 2);
}

.shortcut .chev {
  color: var(--text-4);
  display: grid;
  place-items: center;
  width: 26px;
  height: 26px;
  border-radius: var(--r-sm);
  transition: transform var(--dur-2) var(--ease), color var(--dur-1) var(--ease), background var(--dur-1) var(--ease);
}

.shortcut:hover .chev {
  transform: translateX(3px);
  color: var(--primary);
  background: var(--primary-faint);
}






/* 系统介绍 */
.intro-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 12px;
}
.intro-cell {
  border-radius: var(--r-lg);
  padding: 14px 16px;
  background: var(--surface-2);
  border: 1px solid var(--hairline);
}
.intro-label {
  font-size: var(--fs-body-sm);
  font-weight: 600;
  color: var(--text-1);
  margin-bottom: 5px;
  letter-spacing: -.005em;
}
.intro-text {
  font-size: var(--fs-caption);
  color: var(--text-3);
  line-height: 1.7;
}

/* ---------- 响应式断点 ---------- */
@media (max-width: 1024px) {
  .home-split {
    display: flex !important;
    flex-direction: column !important;
    gap: 14px !important;
    width: 100% !important;
  }
  .home-split .split-left,
  .home-split .split-right {
    display: contents !important;
  }
  .overview-card { order: 1; width: 100%; }
  .shortcuts-card { order: 2; width: 100%; }
  .rooms-card { order: 3; width: 100%; }
  .tips-card { order: 4; width: 100%; }
  .intro-card { order: 5; width: 100%; }

  .shortcut-grid {
    grid-template-columns: 1fr;
    gap: 10px;
    width: 100%;
  }
  .shortcut {
    width: 100%;
    min-height: 84px;
    padding: 16px 18px;
    display: flex;
    flex-direction: row;
    align-items: center;
    gap: 14px;
  }
  .shortcut-top {
    display: contents;
  }
  .shortcut-icon {
    order: 1;
    width: 48px;
    height: 48px;
    flex-shrink: 0;
  }
  .shortcut-desc {
    min-height: 0;
  }
  .shortcut-body {
    order: 2;
    flex: 1;
    min-width: 0;
  }
  .shortcut .chev {
    order: 3;
    flex-shrink: 0;
    width: 30px;
    height: 30px;
  }

  .intro-grid {
    grid-template-columns: 1fr;
    gap: 10px;
    width: 100%;
  }

  .kpi-time {
    flex-direction: column !important;
    align-items: center !important;
    justify-content: center !important;
    line-height: 1.15 !important;
    font-size: clamp(12px, 2.7vw, 15px) !important;
    white-space: normal !important;
    text-align: center !important;
  }
  .kpi-time .kpi-time-start,
  .kpi-time .kpi-time-end {
    display: block !important;
    line-height: 1.15 !important;
    text-align: center !important;
  }
  .kpi-time .kpi-tilde {
    font-size: 0.85em !important;
    margin: 0 1px 0 0 !important;
  }
}

@media (max-width: 560px) {
  .shortcut {
    min-height: 74px;
    padding: 14px 14px;
    gap: 12px;
  }
  .shortcut-icon {
    width: 42px;
    height: 42px;
  }
  .shortcut-title {
    font-size: 15px;
  }
  .shortcut-desc {
    font-size: 11.5px;
  }

}
.tone-primary .shortcut-icon { background: var(--primary-weak); color: var(--primary-active); }
.tone-success .shortcut-icon { background: var(--green-weak); color: var(--green-strong); }
.tone-warm    .shortcut-icon { background: var(--amber-weak); color: var(--amber-strong); }
.tone-violet  .shortcut-icon { background: var(--violet-weak); color: var(--violet-strong); }
</style>
