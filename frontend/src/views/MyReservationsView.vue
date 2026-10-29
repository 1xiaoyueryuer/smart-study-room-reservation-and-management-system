<script setup>
import PageHelp from '../components/PageHelp.vue'
import SAnimatedNumber from '../components/ui/SAnimatedNumber.vue'
import { usePagination } from '../composables/usePagination'
import SPagination from '../components/ui/SPagination.vue'
import { ref, computed, onMounted } from 'vue'
import { message, confirmDialog } from '../components/ui/feedback'
import {
  listMyReservations, cancelReservation, abandonReservation, checkinReservation,
  leaveReservation, returnReservation, checkoutReservation
} from '../api/reservation'
import AppIcon from '../components/AppIcon.vue'
import SButton from '../components/ui/SButton.vue'
import STag from '../components/ui/STag.vue'
import SEmpty from '../components/ui/SEmpty.vue'
import SBanner from '../components/ui/SBanner.vue'
import ViolationsPanel from '../components/ViolationsPanel.vue'

const list = ref([])
const loading = ref(false)
const error = ref(''), actionError = ref(''), busyId = ref(null), violations = ref(null)
const filterStatus = ref('all')
let loadSequence = 0

const statusMeta = {
  pending:     { text: '待签到',   type: 'warning' },
  checked_in:  { text: '使用中',   type: 'success' },
  temp_leave:  { text: '临时离开', type: 'info' },
  completed:   { text: '已完成',   type: '' },
  cancelled:   { text: '已取消',   type: 'info' },
  unused_ended: { text:'未使用结束', type:'info' },
  violation: { text:'旧违约状态（历史）', type:'info' }
}
const sourceMeta = { manual: '手动选座', auto: '历史智能分配', waitlist: '历史候补递补' }
const terminationLabels = {user_cancelled:'开始前本人取消',abandon:'开始后主动放弃',no_show:'到签到期限仍未签到',legacy_violation:'历史违约状态，仅作溯源，未转为新独立违规'}

async function load() {
  const sequence = ++loadSequence
  loading.value = true
  error.value = ''
  try {
    const resp = await listMyReservations()
    if (sequence !== loadSequence) return
    list.value = resp.data || []
  } catch (failure) { if (sequence === loadSequence) error.value = failure.message }
  finally {
    if (sequence === loadSequence) loading.value = false
  }
}

const actionVariant = {
  primary: 'primary',
  danger: 'soft-danger',
  warning: 'soft-warn',
  success: 'soft-success',
  info: 'soft'
}

const actionDefinitions = {
  checkin:{key:'checkin',label:'签到',type:'primary'},
  cancel:{key:'cancel',label:'开始前取消',type:'danger'},
  abandon:{key:'abandon',label:'开始后放弃',type:'danger'},
  leave:{key:'leave',label:'临时离开',type:'warning'},
  return:{key:'return',label:'返回座位',type:'success'},
  checkout:{key:'checkout',label:'签退',type:'primary'}
}
function actionsOf(row) {
  return Array.isArray(row.allowed_actions) ? row.allowed_actions.map(key=>actionDefinitions[key]).filter(Boolean) : []
}
const apiMap = { checkin:checkinReservation,cancel:cancelReservation,abandon:abandonReservation,leave:leaveReservation,return:returnReservation,checkout:checkoutReservation }
async function refreshAll() {
  await load()
  await violations.value?.refresh()
}
async function doAction(row, key) {
  if (loading.value || error.value || busyId.value !== null || !actionsOf(row).some(action=>action.key===key)) return
  const reservationId = row.id
  busyId.value = reservationId
  actionError.value = ''
  try {
    if (key==='cancel' || key==='abandon') {
      try {
        await confirmDialog(key==='cancel' ? '仅开始前可取消，不登记新的违规；若时刻已变化，系统会重新核对。确认取消？' : '开始后未签到主动放弃将结束预约、释放余时，并登记一次未使用违约。确认放弃？', key==='cancel'?'开始前取消':'开始后放弃', {confirmButtonText:key==='cancel'?'确认取消':'确认放弃',type:'warning'})
      } catch { return }
    }
    try {
      await apiMap[key](reservationId)
      message.success(`${actionDefinitions[key].label}成功`)
    } catch (failure) {
      actionError.value = failure.status===409 && failure.details?.termination_reason==='no_show' ? '预约已按签到期限超时结束并登记一次未签到违约；本次操作未执行，请查看刷新后的预约及违规明细。' : failure.message
    }
    await refreshAll()
  } finally { busyId.value = null }
}

// 左栏状态快筛
const statusBuckets = computed(() => {
  const b = { all: list.value.length, pending: 0, checked_in: 0, temp_leave: 0, completed: 0, cancelled:0, unused_ended:0, violation: 0 }
  list.value.forEach((r) => { if (b[r.status] !== undefined) b[r.status]++ })
  return b
})
const filtered = computed(() => {
  if (filterStatus.value === 'all') return list.value
  return list.value.filter((r) => r.status === filterStatus.value)
})

const statusNav = [
  { key: 'all',        label: '全部',   icon: 'reservations' },
  { key: 'pending',    label: '待签到', icon: 'waitlist' },
  { key: 'checked_in', label: '使用中', icon: 'circle-success' },
  { key: 'temp_leave', label: '临离开', icon: 'walk' },
  { key: 'completed',  label: '已完成', icon: 'check' },
  { key:'cancelled',label:'已取消',icon:'circle-muted' },
  { key:'unused_ended',label:'未使用结束',icon:'circle-muted' },
  { key:'violation',label:'旧违约状态',icon:'warning' }
]

onMounted(refreshAll)
const { page, pages, pageItems } = usePagination(filtered, filterStatus)
</script>

<template>
  <div v-reveal class="page-view">
    <header class="view-heading has-page-help"><div class="heading-copy" data-page-title>
      <h1>我的预约</h1>
      <p class="heading-sub">管理签到、临时离开与签退等预约全生命周期操作</p>
    </div><PageHelp title="操作说明">
        <ul class="tips">
          <li>开始前取消不登记新违规；开始后未签到主动放弃会登记一次未使用违约。</li><li>签到窗口为开始起至开始后15分钟之前；达到期限仍未签到则自动结束并登记一次，动作以服务端当前允许为准。</li><li>信用分和流水只读保留，不再新增加减分或低分触发限制。</li>
        </ul>
      </PageHelp></header>
    <!-- 我的预约：页面级双栏 = 左状态筛选 | 右列表 -->
    <div class="split mine-split">
    <div class="split-left">
      <section class="card responsive-compact">
        <div class="card-title-row">
          <h3>预约概览</h3>
          <SButton size="sm" variant="secondary" :loading="loading" :disabled="busyId !== null" @click="refreshAll"><AppIcon name="refresh" :size="14" />刷新</SButton>
        </div>
        <div class="kpi-grid kpi-lg">
          <div class="kpi"><div class="kpi-num"><SAnimatedNumber v-if="!loading && !error" :value="statusBuckets.all" /><span v-else>—</span></div><div class="kpi-label">累计预约</div></div>
          <div class="kpi kpi-warn"><div class="kpi-num"><SAnimatedNumber v-if="!loading && !error" :value="statusBuckets.pending" /><span v-else>—</span></div><div class="kpi-label">待签到预约</div></div>
          <div class="kpi kpi-ok"><div class="kpi-num">{{ loading || error ? '—' : statusBuckets.checked_in + statusBuckets.temp_leave }}</div><div class="kpi-label">当前进行中</div></div>
          <div class="kpi kpi-bad"><div class="kpi-num"><SAnimatedNumber v-if="!loading && !error" :value="statusBuckets.unused_ended" /><span v-else>—</span></div><div class="kpi-label">未使用结束预约</div></div>
        </div>
      </section>

      <section class="card responsive-compact">
        <div class="card-title-row">
          <h3>状态筛选</h3>
        </div>
        <nav v-active-track class="side-nav">
          <button
            v-for="s in statusNav"
            :key="s.key"
            class="side-nav-item"
            :class="{ active: filterStatus === s.key }"
            @click="filterStatus = s.key"
          >
            <span class="side-nav-icon"><AppIcon :name="s.icon" :size="16" /></span>
            <span class="side-nav-label">{{ s.label }}</span>
            <span class="side-nav-count">{{ loading || error ? '—' : statusBuckets[s.key] ?? 0 }}</span>
          </button>
        </nav>
      </section>

      
    </div>

    <div class="split-right">
      <section class="card">
        <div class="card-title-row">
          <h3>
            预约记录
            <span class="muted">共 {{ filtered.length }} 条</span>
          </h3>
        </div>

        <p v-if="loading" role="status">正在读取预约并核对已到期未签到状态…</p>
        <SBanner v-if="error" type="error" :title="error"><SButton size="sm" :disabled="busyId !== null" @click="refreshAll">重试预约查询</SButton></SBanner>
        <SBanner v-if="actionError" type="error" :title="actionError" />
        <div v-list-motion="pageItems.map(row => row.id + row.status).join()" class="table-wrap">
          <div class="table-scroll" v-loading="loading" tabindex="0" aria-label="预约记录表格，可左右滑动">
            <table class="table mine-table">
              <thead>
                <tr>
                  <th style="width:106px">日期</th>
                  <th style="width:128px">时段</th>
                  <th style="min-width:118px">自习室</th>
                  <th style="width:64px">座位</th>
                  <th style="width:90px">来源</th>
                  <th style="width:96px">状态</th>
                  <th style="width:178px">操作</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="row in pageItems" :key="row.id">
                  <td class="num">{{ row.res_date }}</td>
                  <td class="num">{{ row.start_time.slice(0, 5) }} - {{ row.end_time.slice(0, 5) }}</td>
                  <td>{{ row.room_name }}</td>
                  <td class="cell-strong">{{ row.seat_no }}</td>
                  <td><STag>{{ sourceMeta[row.source] || row.source }}</STag></td>
                  <td>
                    <STag :type="statusMeta[row.status]?.type" dot :line="row.status === 'cancelled'">
                      {{ statusMeta[row.status]?.text || row.status }}
                    </STag>
                    <div class="termination-detail">{{ terminationLabels[row.termination_reason] || row.termination_reason || '—' }}</div>
                    <div v-if="row.ended_at" class="termination-detail">结束：{{ row.ended_at }}（校园时间）</div>
                    <div v-if="row.legacy_status" class="termination-detail">历史原状态：{{ row.legacy_status }}</div>
                  </td>
                  <td>
                    <span class="row-actions">
                      <SButton
                        v-for="a in actionsOf(row)"
                        :key="a.key"
                        size="sm"
                        :variant="actionVariant[a.type]"
                        :disabled="loading || !!error || busyId !== null"
                        :loading="busyId === row.id"
                        @click="doAction(row, a.key)"
                      >
                        {{ a.label }}
                      </SButton>
                      <span v-if="!actionsOf(row).length" class="cell-dim">—</span>
                    </span>
                  </td>
                </tr>
              </tbody>
            </table>
            <SEmpty v-if="!filtered.length && !loading && !error" description="当前筛选条件下暂无预约记录" />
          </div>
        </div>
        <SPagination v-model="page" :pages="pages" :total="filtered.length" />
      </section>
      <ViolationsPanel ref="violations" :auto-load="false" />
    </div>
    </div>
  </div>
</template>

<style scoped>


.kpi-lg .kpi-num { font-size: 24px; }
.mine-table { min-width: 960px; }
.termination-detail { font-size:var(--fs-caption); max-width:240px; overflow-wrap:anywhere; color:var(--text-3); margin-top:5px; }
.mine-table .row-actions {
  flex-wrap: nowrap;
  white-space: nowrap;
}
.mine-table td:nth-child(3) {
  max-width: 180px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
