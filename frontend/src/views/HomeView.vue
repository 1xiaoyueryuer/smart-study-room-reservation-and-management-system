<script setup>
import StudyTips from '../components/StudyTips.vue'
import SAnimatedNumber from '../components/ui/SAnimatedNumber.vue'
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import { useNotificationStore } from '../stores/notification'
import { getRooms, getOpeningWindows } from '../api/room'
import { listMyReservations } from '../api/reservation'
import { getOverview } from '../api/stats'
import { listNotifications } from '../api/notification'
import { validOverview, ratioText } from '../composables/statsForm.js'
import { createStatsRefresh } from '../composables/statsRefresh.js'
import { checkinDeadlineOf, deadlineLabel } from '../composables/checkinDeadline.js'
import AppIcon from '../components/AppIcon.vue'
import SBanner from '../components/ui/SBanner.vue'
import SButton from '../components/ui/SButton.vue'

const router = useRouter()
const auth = useAuthStore()
const notifStore = useNotificationStore()

const who = computed(() => auth.user?.real_name || auth.user?.username || '同学')
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
function roomOpenNow(room) {
  const value = todayOpenings.value[room.id]
  if (!value || value.error || !value.windows?.length) return false
  const now = new Date()
  const minutes = now.getHours() * 60 + now.getMinutes()
  return value.windows.some(window => {
    const [sh, sm] = window.start.split(':').map(Number)
    const [eh, em] = window.end.split(':').map(Number)
    return minutes >= sh * 60 + sm && minutes < eh * 60 + em
  })
}
const roomsError = ref('')
const roomsLoading = ref(false)

const statsOverview = ref(null)
const statsError = ref('')
const statsLoading = ref(false)
const statsVisible = ref(true)
const unread = computed(() => notifStore.unread)
let unreadTimer = null
let disposed = false

const openAvailableSeats = computed(() => statsOverview.value?.current.open_available_seats ?? null)
const inUseNow = computed(() => statsOverview.value?.current.in_use_now ?? null)
const currentUsageText = computed(() => statsOverview.value ? ratioText(statsOverview.value.current.usage_ratio) : '尚未读取')
const statsSnapshotText = computed(() => statsOverview.value ? new Date(statsOverview.value.snapshot_at).toLocaleString('zh-CN', {timeZone:'Asia/Shanghai',hour12:false}) : '')

// 本人最近一条有效预约：报告只要求本人记录，不做学习时长/排名等个人统计。
const reservations = ref([])
const reservationsLoading = ref(false)
const reservationsError = ref('')
const activeReservations = computed(() => reservations.value.filter(row => ['pending','checked_in'].includes(row.status)))
const nextReservation = computed(() => activeReservations.value[0] || null)
const nextDeadline = computed(() => nextReservation.value ? checkinDeadlineOf(nextReservation.value) : null)

// 管理取消登录提示卡：只展示服务端真实通知，不伪造确认状态。
const cancellations = ref([])
async function loadCancellations() {
  if (!auth.isOrdinary) return
  try {
    const response = await listNotifications({ page: 1, page_size: 3, type: 'management_cancelled' })
    cancellations.value = (response.data?.items || []).filter(item => item.type === 'management_cancelled')
  } catch { cancellations.value = [] }
}

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

async function loadReservations() {
  if (!auth.isOrdinary) return
  reservationsLoading.value = true
  reservationsError.value = ''
  try {
    const response = await listMyReservations({ page: 1, page_size: 20 })
    reservations.value = response.data?.items || []
  } catch (error) {
    reservationsError.value = error.message
  } finally {
    reservationsLoading.value = false
  }
}

const statsRefresh = createStatsRefresh({onVisibility:visible=>{statsVisible.value=visible},resources:{overview:{
  read:async()=>{
    if (!auth.isAdmin) return null
    const actor=auth.user?.id
    try { const response=await getOverview();return {actor,data:response.data} }
    catch(error) { if(!auth.isAdmin||auth.user?.id!==actor)return null;throw error }
  },
  success:result=>{
    if(!result||!auth.isAdmin||auth.user?.id!==result.actor)return null
    if(!validOverview(result.data))throw new Error('统计回执不完整，请重新读取')
    statsOverview.value=result.data;return result.data
  },
  failure:error=>{statsError.value=error.message||'统计读取失败'},
  loading:value=>{statsLoading.value=value;if(value)statsError.value=''}
}}})
function loadStats(){if(auth.isAdmin)return statsRefresh.refresh('overview')}

onMounted(async () => {
  if(auth.isAdmin){statsRefresh.select('overview',{});statsRefresh.start()}
  await Promise.all([loadRooms(), loadReservations(), loadCancellations()])
  if (disposed) return
  refreshUnread()
  unreadTimer = setInterval(refreshUnread, 30000)
})

onUnmounted(() => {
  disposed = true
  statsRefresh.stop()
  if (unreadTimer) {
    clearInterval(unreadTimer)
    unreadTimer = null
  }
})

const shortcuts = computed(() => auth.isAdmin
  ? [
      { title: '热力图统计', desc: '真实使用记录 · 近七日趋势', icon: 'analytics', to: '/analytics' },
      { title: '管理端', desc: '自习室 · 座位 · 用户', icon: 'admin', to: '/admin' },
      { title: '个人中心', desc: '账户资料与联系方式', icon: 'profile', to: '/profile' }
    ]
  : [
      { title: '座位预约', desc: '三种找座方案 · 图列表双视图', icon: 'booking', to: '/booking' },
      { title: '我的预约', desc: '扫码签到 · 提前签退 · 使用记录', icon: 'reservations', to: '/mine' },
      { title: '求助与投诉', desc: '预约问题 · 设施报修 · 服务反馈', icon: 'ticket', to: '/complaints' },
      { title: '消息中心', desc: unread.value > 0 ? `${unread.value} 条未读` : '预约与反馈通知', icon: 'notification', to: '/notifications' }
    ])

function statusText(row) {
  return ({ pending: '待签到', checked_in: '使用中' })[row.status] || row.status
}
</script>

<template>
  <div v-reveal class="page-view home-page">
    <header class="home-hello" data-page-title>
      <p class="hello-date">{{ todayText }}</p>
      <h1>{{ greeting }}，{{ who }}</h1>
    </header>

    <section v-if="cancellations.length" class="cancel-notice">
      <div class="cancel-copy">
        <span class="pill pill-warning">资源管理取消</span>
        <h3>{{ cancellations[0].title }}</h3>
        <p>{{ cancellations[0].content }}</p>
        <p class="cancel-meta">本次管理取消不记本人违规；预计恢复时间以系统可预约状态为准。无需专程到场确认，已到场或需要帮助可点“需要协助”。</p>
      </div>
      <div class="cancel-actions">
        <SButton variant="primary" @click="router.push('/notifications')">查看通知</SButton>
        <SButton variant="soft" @click="router.push('/booking')">重新选座</SButton>
        <SButton variant="soft" @click="router.push({name:'complaints',query:{type:'service'}})">需要协助</SButton>
      </div>
    </section>

    <div class="home-layout">
      <div class="home-main">
        <section class="hero">
          <div class="hero-copy">
            <p class="hero-eyebrow">静阅 · 专注空间</p>
            <h2 :class="{ 'hero-title-admin': auth.isAdmin }">{{ auth.isAdmin ? '今日运营，一目了然' : '今日宜专注' }}</h2>
            <p class="hero-sub">{{ auth.isAdmin ? '查看真实使用统计与待办，安排今天的开放资源' : '预约你的专属座位，让每一次坐下都算数' }}</p>
            <SButton variant="primary" size="lg" @click="router.push(auth.isAdmin ? '/analytics' : '/booking')">
              {{ auth.isAdmin ? '查看统计' : '立即预约' }}
            </SButton>
          </div>
          <div class="hero-art" aria-hidden="true">
            <svg viewBox="0 0 520 300" fill="none">
              <rect x="24" y="52" width="472" height="210" rx="18" fill="var(--primary-weak)" opacity=".45" />
              <path d="M60 210h400v18H60z" fill="#b98d5f" opacity=".55" />
              <path d="M60 228h400v10H60z" fill="#a97c4e" opacity=".35" />
              <path d="M120 88h120v104H120z" fill="#fffdf8" stroke="#cfc2a9" stroke-width="3" />
              <path d="M120 120h120M120 152h120" stroke="#e0d6c2" stroke-width="2" />
              <path d="M132 100h44M132 112h66M132 132h58M132 144h40M132 164h64M132 176h48" stroke="#c9bda6" stroke-width="5" stroke-linecap="round" />
              <path d="M300 92c0-16 12-28 28-28h4v122h-32z" fill="#e3d6bd" />
              <path d="M345 136c0-10 8-18 18-18s18 8 18 18" stroke="#8a6a44" stroke-width="5" stroke-linecap="round" />
              <path d="M363 136v46" stroke="#8a6a44" stroke-width="5" stroke-linecap="round" />
              <path d="M336 182h56" stroke="#8a6a44" stroke-width="5" stroke-linecap="round" />
              <path d="M392 172c10-22 28-34 52-34" stroke="#4c7a5c" stroke-width="4" stroke-linecap="round" />
              <path d="M404 158c-2-14 4-26 16-32M414 148c10 0 18 6 22 16" stroke="#4c7a5c" stroke-width="4" stroke-linecap="round" />
              <circle cx="452" cy="92" r="22" fill="#f2d8a8" opacity=".7" />
            </svg>
          </div>
        </section>

        <section class="section-head">
          <h2>自习室列表</h2>
          <span>按开放时间选择你的学习空间</span>
        </section>

        <div v-if="roomsLoading" class="room-cards"><div v-for="n in 4" :key="n" class="room-card skeleton" /></div>
        <SBanner v-else-if="roomsError" type="error" :title="roomsError"><SButton size="sm" @click="loadRooms">重试</SButton></SBanner>
        <p v-else-if="!rooms.length" class="muted">暂无自习室信息。</p>
        <div v-else class="room-cards">
          <article v-for="room in rooms" :key="room.id" class="room-card">
            <div class="room-card-top">
              <h3>{{ room.name }}</h3>
              <span class="room-status" :class="roomOpenNow(room) ? 'open' : 'closed'">{{ roomOpenNow(room) ? '开放中' : '未开放' }}</span>
            </div>
            <p class="room-location">{{ room.building || '楼栋未登记' }}<template v-if="room.floor"> · {{ room.floor }}</template><template v-if="room.location"> · {{ room.location }}</template></p>
            <p class="room-open">{{ roomOpeningText(room) }}</p>
            <SButton :disabled="!room.enabled" variant="soft" block @click="router.push(auth.isAdmin ? '/admin' : '/booking')">{{ auth.isAdmin ? '管理空间' : '去预约' }}</SButton>
          </article>
        </div>
      </div>

      <aside class="home-side">
        <section v-if="auth.isOrdinary" class="card next-card">
          <div class="card-title-row"><h3>我的预约</h3><RouterLink to="/mine" class="text-link">全部记录</RouterLink></div>
          <p v-if="reservationsLoading" class="muted">正在读取本人预约…</p>
          <SBanner v-else-if="reservationsError" type="error" :title="reservationsError"><SButton size="sm" @click="loadReservations">重试</SButton></SBanner>
          <template v-else-if="nextReservation">
            <span class="pill" :class="nextReservation.status === 'pending' ? 'pill-warning' : 'pill-success'">{{ statusText(nextReservation) }}</span>
            <h4>{{ nextReservation.room_name }} · {{ nextReservation.seat_no }}</h4>
            <p class="next-time">{{ nextReservation.res_date }} {{ nextReservation.start_time }}—{{ nextReservation.end_time }}</p>
            <p v-if="nextDeadline" class="next-deadline">签到截止 <b>{{ deadlineLabel(nextDeadline) }}</b></p>
            <SButton variant="primary" block @click="router.push(`/mine/${nextReservation.id}/checkin`)">去签到</SButton>
            <SButton variant="soft" block @click="router.push('/mine')">查看我的预约</SButton>
          </template>
          <div v-else class="empty-next">
            <p>今天还没有安排学习。</p>
            <SButton variant="primary" block @click="router.push('/booking')">去选座</SButton>
          </div>
        </section>

        <section v-else class="card next-card">
          <div class="card-title-row"><h3>今日概览</h3><RouterLink to="/analytics" class="text-link">详细统计</RouterLink></div>
          <p v-if="statsLoading" class="muted">正在加载统计…</p>
          <p class="muted" role="status">{{ statsVisible ? '页面可见时每 10 秒刷新。' : '页面已隐藏，刷新暂停。' }}</p>
          <SBanner v-if="statsError" type="error" :title="statsError"><SButton size="sm" @click="loadStats">重试统计</SButton></SBanner>
          <div class="mini-kpis">
            <div><SAnimatedNumber v-if="inUseNow !== null" :value="inUseNow" /><span v-else>—</span><small>使用中</small></div>
            <div><SAnimatedNumber v-if="openAvailableSeats !== null" :value="openAvailableSeats" /><span v-else>—</span><small>开放可用</small></div>
            <div><span>{{ currentUsageText }}</span><small>使用比例</small></div>
          </div>
          <p v-if="statsOverview" class="snapshot">快照 {{ statsSnapshotText }}</p>
        </section>

        <section class="card quick-card">
          <div class="card-title-row"><h3>快速入口</h3></div>
          <div class="quick-list">
            <button v-for="s in shortcuts" :key="s.to" type="button" class="quick-item" @click="router.push(s.to)">
              <span class="quick-icon"><AppIcon :name="s.icon" :size="20" /></span>
              <span class="quick-copy"><b>{{ s.title }}</b><small>{{ s.desc }}</small></span>
              <AppIcon name="chevron-right" :size="16" />
            </button>
          </div>
        </section>

        <StudyTips :title="auth.isOrdinary ? '使用须知' : '管理提示'">
          <ul v-if="auth.isOrdinary" class="tips tips-list">
            <li>可预约当天至后天，单次至少 30 分钟，起止在开放空档内自选。</li>
            <li>开始前取消不记违规；开始后未签到放弃记一次违约。</li>
            <li>签到窗口为开始时刻起至开始后 15 分钟；符合规则的占座求助可延长一次 15 分钟。</li>
            <li>短暂离开不延长原结束时间；提前签退释放剩余时段。</li>
          </ul>
          <ul v-else class="tips tips-list">
            <li>资源变更先预览影响清单，确认后管理取消受影响预约，不记用户违约。</li>
            <li>投诉先受理再核实，未知身份不归责；设施报修按故障类型、影响范围和安全风险分级。</li>
            <li>统计快照每 10 秒刷新，失败保留上次成功值与时间。</li>
            <li>评价隐藏后本人修改不自动恢复公开，需管理员复核。</li>
          </ul>
        </StudyTips>
      </aside>
    </div>
  </div>
</template>

<style scoped>
.home-page { padding-top: 22px; }
.home-hello { margin: 0 2px 22px; }
.hello-date { color: var(--text-3); font-size: var(--fs-body-sm); letter-spacing: .04em; }
.home-hello h1 { margin-top: 6px; font-size: 34px; color: var(--text-1); }
.home-layout { display: grid; grid-template-columns: minmax(0, 1fr) 340px; gap: 24px; align-items: start; }
.cancel-notice {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
  margin-bottom: 22px;
  padding: 18px 22px;
  border: 1px solid var(--amber-weak);
  border-radius: var(--r-xl);
  background: var(--amber-faint);
}
.cancel-copy { min-width: 0; }
.cancel-copy h3 { margin: 8px 0 4px; font-size: 20px; color: var(--text-1); }
.cancel-copy p { color: var(--text-2); font-size: var(--fs-body-sm); line-height: 1.6; }
.cancel-meta { color: var(--text-3) !important; font-size: 11px !important; margin-top: 6px; }
.cancel-actions { display: flex; flex-wrap: wrap; gap: 8px; flex: 0 0 auto; }
@media (max-width: 760px) { .cancel-notice { flex-direction: column; align-items: flex-start; } }
.home-main { min-width: 0; }
.home-side { display: flex; flex-direction: column; gap: 18px; min-width: 0; }

.hero {
  position: relative;
  display: grid;
  grid-template-columns: minmax(0, 1fr) 38%;
  gap: 12px;
  min-height: 280px;
  padding: 34px 36px;
  border: 1px solid var(--border);
  border-radius: var(--r-2xl);
  background:
    radial-gradient(120% 130% at 92% 12%, rgba(47, 93, 70, .10), transparent 55%),
    linear-gradient(135deg, #fbf7ec 0%, #f3ecdc 100%);
  overflow: hidden;
}
.hero-copy { display: flex; flex-direction: column; justify-content: center; align-items: flex-start; min-width: 0; }
.hero-eyebrow { color: var(--primary); font-size: 12px; letter-spacing: .22em; }
.hero h2 {
  margin: 10px 0 8px;
  font-size: clamp(38px, 4.2vw, 60px);
  line-height: 1.1;
  color: #2a3f33;
  letter-spacing: .04em;
  white-space: nowrap;
}
.hero h2.hero-title-admin { font-size: clamp(30px, 3.2vw, 46px); }
.hero-sub { color: var(--text-2); font-size: 16px; margin-bottom: 24px; }
.hero-art { display: flex; align-items: center; justify-content: center; min-width: 0; }
.hero-art svg { width: 100%; max-width: 520px; height: auto; }

.section-head { display: flex; align-items: baseline; gap: 12px; margin: 30px 2px 16px; }
.section-head h2 { font-size: 28px; color: var(--text-1); }
.section-head span { color: var(--text-3); font-size: var(--fs-body-sm); }

.room-cards { display: grid; grid-template-columns: repeat(auto-fill, minmax(220px, 1fr)); gap: 16px; }
.room-card {
  display: flex;
  flex-direction: column;
  gap: 10px;
  min-height: 186px;
  padding: 18px;
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--r-xl);
  box-shadow: var(--shadow-1);
  transition: transform var(--dur-2) var(--ease), box-shadow var(--dur-2) var(--ease);
}
.room-card:hover { transform: translateY(-2px); box-shadow: var(--shadow-2); }
.room-card h3 { font-size: 20px; color: var(--text-1); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.room-card-top { display: flex; align-items: flex-start; justify-content: space-between; gap: 10px; }
.room-status { flex: 0 0 auto; padding: 3px 10px; border-radius: var(--r-full); font-size: 12px; }
.room-status.open { background: var(--green-weak); color: var(--green-strong); }
.room-status.closed { background: var(--surface-3); color: var(--text-3); }
.room-location { color: var(--text-3); font-size: var(--fs-caption); }
.room-open { color: var(--text-2); font-size: var(--fs-body-sm); line-height: 1.6; flex: 1; }
.room-card .s-btn { margin-top: auto; }
.skeleton { background: linear-gradient(90deg, var(--surface-2), var(--surface-3), var(--surface-2)); background-size: 200% 100%; animation: shimmer 1.4s infinite; }
@keyframes shimmer { to { background-position: -200% 0; } }

.next-card h4 { margin: 12px 0 4px; font-family: var(--font-display); font-size: 21px; color: var(--text-1); }
.next-time { color: var(--text-2); font-size: var(--fs-body-sm); }
.next-deadline { margin-top: 8px; color: var(--text-3); font-size: var(--fs-body-sm); }
.next-deadline b { color: var(--amber-strong); font-variant-numeric: tabular-nums; }
.next-card .s-btn { margin-top: 12px; }
.empty-next { display: grid; gap: 14px; padding: 8px 0 2px; color: var(--text-3); }
.text-link { color: var(--primary); font-size: var(--fs-caption); text-decoration: none; }
.text-link:hover { text-decoration: underline; }

.mini-kpis { display: grid; grid-template-columns: repeat(3, 1fr); gap: 8px; margin: 14px 0 6px; }
.mini-kpis > div { background: var(--surface-2); border-radius: var(--r-md); padding: 12px 10px; text-align: center; }
.mini-kpis span { display: block; font-family: var(--font-display); font-size: 24px; color: var(--text-1); }
.mini-kpis small { display: block; margin-top: 4px; color: var(--text-3); font-size: 11px; }
.snapshot { color: var(--text-4); font-size: 11px; margin-top: 6px; }

.quick-list { display: flex; flex-direction: column; gap: 6px; }
.quick-item {
  all: unset;
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 11px 12px;
  border-radius: var(--r-md);
  cursor: pointer;
  color: var(--text-2);
  transition: background var(--dur-1) var(--ease);
}
.quick-item:hover { background: var(--surface-2); }
.quick-icon { width: 38px; height: 38px; flex: 0 0 38px; border-radius: 50%; display: grid; place-items: center; background: var(--primary-weak); color: var(--primary); }
.quick-copy { flex: 1; min-width: 0; }
.quick-copy b { display: block; font-size: 14px; color: var(--text-1); font-weight: 600; }
.quick-copy small { display: block; margin-top: 2px; color: var(--text-3); font-size: 11px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

@media (max-width: 1200px) {
  .home-layout { grid-template-columns: minmax(0, 1fr); }
  .home-side { display: grid; grid-template-columns: repeat(auto-fit, minmax(260px, 1fr)); }
}
@media (max-width: 760px) {
  .hero { grid-template-columns: 1fr; padding: 26px 22px; }
  .hero-art { display: none; }
  .home-hello h1 { font-size: 28px; }
  .room-cards { grid-template-columns: 1fr; }
}
</style>
