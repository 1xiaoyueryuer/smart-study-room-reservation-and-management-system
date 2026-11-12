<script setup>
import PageHelp from '../components/PageHelp.vue'
import {createRecordPager,recordFields} from '../composables/recordPage.js'
import RecordFilters from '../components/RecordFilters.vue'
import RecordPageControls from '../components/RecordPageControls.vue'

import { ref, computed, onMounted, onBeforeUnmount, watch } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import {reviewReservationParam} from '../composables/reviewManagement.js'
import {checkinDeadlineOf, deadlineLabel} from '../composables/checkinDeadline.js'
import { message, confirmDialog } from '../components/ui/feedback'
import {
  listMyReservations, cancelReservation, abandonReservation,
  checkoutReservation, getReservation
} from '../api/reservation'
import SButton from '../components/ui/SButton.vue'
import STag from '../components/ui/STag.vue'
import SEmpty from '../components/ui/SEmpty.vue'
import SBanner from '../components/ui/SBanner.vue'
import UsageRecordSummary from '../components/UsageRecordSummary.vue'
import ReviewEditor from '../components/ReviewEditor.vue'

const router = useRouter()
const route=useRoute()
const reviewEditor=ref(null)
const linkedReservation=ref(null),linkedLoading=ref(false),linkedError=ref('');let linkedAlive=true,linkedSequence=0
const pager=createRecordPager(listMyReservations,{initial:{record_type:'reservation'},blocked:()=>busyId.value!==null}),fields=recordFields('reservation')
const {items:list,loading,error,filters,snapshot,snapshotQuery}=pager
const actionError = ref(''), busyId = ref(null)

const statusMeta = {
  pending:     { text: '待签到',   type: 'warning' },
  checked_in:  { text: '使用中',   type: 'success' },
  completed:   { text: '已结束',   type: '' },
  cancelled:   { text: '已取消',   type: 'info' },
  unused_ended: { text:'未使用结束', type:'info' },
  violation: { text:'旧违约状态（历史）', type:'info' }
}
const sourceMeta = { manual: '手动选座', auto: '历史智能分配', waitlist: '历史候补递补' }
const terminationLabels = {user_cancelled:'开始前本人取消',abandon:'开始后主动放弃',no_show:'到签到期限仍未签到',legacy_violation:'历史违约状态，仅作溯源，未转为新独立违规',early_checkout:'提前签退',auto_end:'到期自动结束',legacy_unclassified:'历史结束，具体方式未记录',demo_completed:'演示模拟结束',temp_leave_timeout:'旧暂离流程结束（历史）',admin_resource_change:'资源管理取消（不记用户违约）',admin_restriction:'预约限制管理取消（不记用户违约）'}

function deadlineOf(row) { return checkinDeadlineOf(row) }
function fmtStamp(value) { return value ? String(value).replace('T', ' ').slice(0, 19) : '' }
const pendingRows = computed(() => list.value.filter(row => row.status === 'pending' && deadlineOf(row)).slice(0, 3))

const load=()=>pager.refresh(true)
onBeforeUnmount(()=>{pager.dispose();linkedAlive=false;linkedSequence++})
async function readLinkedReservation(){const id=reviewReservationParam(route.query.reservation_id),ticket=++linkedSequence;linkedReservation.value=null;linkedError.value='';if(id===null){linkedLoading.value=false;return}linkedLoading.value=true;try{const {data}=await getReservation(id);if(!linkedAlive||ticket!==linkedSequence)return;if(data?.id!==id||typeof data.status!=='string'||!Array.isArray(data.allowed_actions))throw new Error('关联预约单条返回不完整');linkedReservation.value=data}catch(e){if(linkedAlive&&ticket===linkedSequence)linkedError.value=e.message}finally{if(linkedAlive&&ticket===linkedSequence)linkedLoading.value=false}}
watch(()=>route.query.reservation_id,readLinkedReservation)

const actionVariant = {
  primary: 'primary',
  danger: 'soft-danger',
  warning: 'soft-warn',
  success: 'soft-success',
  info: 'soft'
}

const actionDefinitions = {
  checkin:{key:'checkin',label:'扫码签到',type:'primary'},
  cancel:{key:'cancel',label:'开始前取消',type:'danger'},
  abandon:{key:'abandon',label:'开始后放弃',type:'danger'},
  checkout:{key:'checkout',label:'提前签退',type:'primary'}
}
function actionsOf(row) {
  return Array.isArray(row.allowed_actions) ? row.allowed_actions.map(key=>actionDefinitions[key]).filter(Boolean) : []
}
const apiMap = { cancel:cancelReservation,abandon:abandonReservation,checkout:checkoutReservation }
async function refreshAll() {
  await Promise.all([load(),readLinkedReservation()])
}
function requestOccupationHelp(row) { if (!loading.value && !error.value && busyId.value === null) return router.push({name:'complaints',query:{type:'occupation',reservation_id:String(row.id)}}) }
async function doAction(row, key) {
  if (loading.value || error.value || busyId.value !== null || !actionsOf(row).some(action=>action.key===key)) return
  if (key === 'checkin') { await router.push({ name: 'checkin', params: { id: row.id } }); return }
  const reservationId = row.id
  busyId.value = reservationId
  actionError.value = ''
  try {
    if (key==='cancel' || key==='abandon') {
      try {
        await confirmDialog(key==='cancel' ? '仅开始前可取消，不登记新的违规；若时刻已变化，系统会重新核对。确认取消？' : '开始后未签到主动放弃将结束预约、释放余时，并登记一次未使用违约。确认放弃？', key==='cancel'?'开始前取消':'开始后放弃', {confirmButtonText:key==='cancel'?'确认取消':'确认放弃',type:'warning'})
      } catch { return }
    }
    if (key === 'checkout') {
      try { await confirmDialog('确认提前签退？系统将结束本次使用并释放剩余时段，不新增信用奖惩；到期不点签退也会自动结算，不额外处罚。', '提前签退', { confirmButtonText: '确认签退并释放余时', type: 'warning' }) }
      catch { return }
    }
    try {
      await apiMap[key](reservationId)
      message.success(`${actionDefinitions[key].label}成功`)
    } catch (failure) {
      actionError.value = failure.status===409 && failure.details?.termination_reason==='no_show' ? '预约已按签到期限超时结束并登记一次未签到违约；本次操作未执行，请查看刷新后的预约及违规明细。' : failure.status === 409 && failure.details?.status === 'completed' ? '预约已结束，本次操作未重复执行；请查看刷新后的真实使用记录。' : failure.message
    }
    await refreshAll()
  } finally { busyId.value = null }
}

const filtered=list,pageItems=list

onMounted(async()=>{await refreshAll();const target=reviewReservationParam(route.query.review_reservation_id);if(target!==null)await reviewEditor.value?.open(target)})

</script>

<template>
  <div v-reveal class="page-view">
    <header class="view-heading has-page-help"><div class="heading-copy" data-page-title>
      <h1>我的预约</h1>
      <p class="heading-sub">查看预约和真实使用记录，管理扫码签到、提前签退与结束状态</p>
    </div><PageHelp title="操作说明">
        <ul class="tips">
          <li>开始前取消不登记新违规；开始后未签到主动放弃会登记一次未使用违约。</li><li>请扫描预约座位的二维码签到。签到窗口为开始起至开始后15分钟之前；达到期限仍未签到则自动结束并登记一次，动作以服务端当前允许为准。</li><li>提前签退释放剩余时段；到期自动结束，不额外处罚漏点签退。短暂离开不延长原结束时间。</li><li>使用时长截至本次服务端读取；信用分和流水只读保留，不再新增加减分或低分触发限制。</li>
        </ul>
      </PageHelp></header>
    <!-- 我的预约：页面级双栏 = 左状态筛选 | 右列表 -->
    <div class="mine-stack">
      <section v-if="pendingRows.length" class="pending-strip" aria-label="待签到预约">
        <article v-for="row in pendingRows" :key="row.id" class="pending-card">
          <div class="pending-head">
            <STag type="warning" dot>待签到</STag>
            <span class="pending-room">{{ row.room_name }} · {{ row.seat_no }}</span>
          </div>
          <div class="pending-time">{{ row.res_date }} {{ row.start_time.slice(0, 5) }}—{{ row.end_time.slice(0, 5) }}</div>
          <div class="pending-deadline">
            签到截止 <b>{{ deadlineOf(row).text }}</b>
            <span class="ext-hint">被占求助不会改变原签到截止</span>
          </div>
          <p class="pending-note">开始时刻起至截止前可签到；超过截止仍未签到按规则处理。</p>
          <div class="row-actions">
            <SButton v-for="a in actionsOf(row)" :key="a.key" size="sm" :variant="actionVariant[a.type]" :disabled="busyId !== null" @click="doAction(row, a.key)">{{ a.label }}</SButton>
            <SButton size="sm" variant="soft" :disabled="busyId !== null" @click="requestOccupationHelp(row)">座位被占求助</SButton>
          </div>
        </article>
      </section>

      <section class="card mine-card">
        <div class="card-title-row">
          <h3>预约与使用记录</h3>
          <SButton size="sm" :disabled="loading||busyId!==null" @click="refreshAll">刷新</SButton>
        </div>
        <RecordFilters v-model="filters" :fields="fields" :disabled="loading||busyId!==null" @apply="pager.apply" />

        <p v-if="loading" role="status">正在读取预约、结算已到期状态并查询使用记录…</p>
        <SBanner v-if="error" type="error" :title="error"><SButton size="sm" :disabled="busyId !== null" @click="refreshAll">重试预约查询</SButton></SBanner>
        <SBanner v-if="actionError" type="error" :title="actionError" />
        <div v-list-motion="pageItems.map(row => row.id + row.status).join()" class="table-wrap mine-table-wrap">
          <div class="table-scroll no-x" v-loading="loading" aria-label="预约记录">
            <table class="table mine-table">
              <colgroup>
                <col style="width:11%" />
                <col style="width:14%" />
                <col style="width:8%" />
                <col style="width:18%" />
                <col style="width:30%" />
                <col style="width:19%" />
              </colgroup>
              <thead>
                <tr>
                  <th>日期 / 时段</th>
                  <th>自习室 / 座位</th>
                  <th>来源</th>
                  <th>状态</th>
                  <th>使用记录</th>
                  <th>操作</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="row in pageItems" :key="row.id">
                  <td class="num"><b>{{ row.res_date }}</b><div class="time-line">{{ row.start_time.slice(0, 5) }}–{{ row.end_time.slice(0, 5) }}</div></td>
                  <td class="room-cell"><div class="room-name" :title="row.room_name">{{ row.room_name }}</div><div class="seat-line">座位 {{ row.seat_no }}</div></td>
                  <td><STag>{{ sourceMeta[row.source] || row.source }}</STag></td>
                  <td class="status-cell">
                    <STag :type="statusMeta[row.status]?.type" dot :line="row.status === 'cancelled'">{{ statusMeta[row.status]?.text || row.status }}</STag>
                    <div v-if="deadlineOf(row) && ['pending','checked_in'].includes(row.status)" class="deadline-line" :class="{ extended: deadlineOf(row).extended }">
                      签到截止 <b>{{ deadlineOf(row).text }}</b>
                      <span v-if="deadlineOf(row).extended">（原 {{ deadlineOf(row).baseText }}，已延期）</span>
                    </div>
                    <div class="termination-detail">{{ terminationLabels[row.termination_reason] || row.termination_reason || '—' }}</div>
                    <div v-if="['admin_resource_change','admin_restriction'].includes(row.termination_reason) && row.termination_note" class="termination-detail">当次公开管理原因：{{ row.termination_note }}</div>
                    <div v-if="row.ended_at" class="termination-detail">结束：{{ fmtStamp(row.ended_at) }}</div>
                    <div v-if="row.legacy_status" class="termination-detail">历史原状态：{{ row.legacy_status === 'temp_leave' ? '暂离（已退出该流程）' : row.legacy_status }}</div>
                  </td>
                  <td class="usage-cell"><UsageRecordSummary :record="row.usage_record" /><div v-if="row.legacy_status === 'temp_leave' && row.leave_at" class="termination-detail">历史暂离登记：{{ fmtStamp(row.leave_at) }}；不延长原结束。</div></td>
                  <td class="actions-cell">
                    <span class="row-actions">
                      <SButton v-for="a in actionsOf(row)" :key="a.key" size="sm" :variant="actionVariant[a.type]" :disabled="loading || !!error || busyId !== null" :loading="busyId === row.id" @click="doAction(row, a.key)">{{ a.label }}</SButton>
                      <SButton size="sm" variant="soft" :disabled="loading || !!error || busyId !== null" @click="requestOccupationHelp(row)">座位被占求助</SButton>
                      <SButton size="sm" variant="soft" :disabled="loading || !!error || busyId !== null" @click="reviewEditor?.open(row.id)">评价/查看本人评价</SButton>
                    </span>
                  </td>
                </tr>
              </tbody>
            </table>
            <SEmpty v-if="!filtered.length && !loading && !error" description="当前筛选条件下暂无预约记录" />
          </div>
        </div>
        <RecordPageControls :snapshot="snapshot" :query="snapshotQuery" :fields="fields" :disabled="loading||busyId!==null" @page="pager.turn"/>
      </section>

      <section v-if="route.query.reservation_id" class="card"><h3>消息关联预约（独立读取）</h3><SButton :disabled="linkedLoading||busyId!==null" @click="readLinkedReservation">刷新关联预约</SButton><p v-if="linkedLoading" role="status">正在核对本人关联预约及真实使用记录…</p><SBanner v-if="linkedError" type="error" :title="linkedError"/><template v-if="linkedReservation"><h4>预约#{{linkedReservation.id}} · {{statusMeta[linkedReservation.status]?.text||linkedReservation.status}}</h4><p>{{linkedReservation.room_name}} / {{linkedReservation.seat_no}} · {{linkedReservation.res_date}} {{linkedReservation.start_time}}—{{linkedReservation.end_time}}</p><p v-if="deadlineOf(linkedReservation)" class="linked-deadline">签到截止 <b>{{ deadlineOf(linkedReservation).text }}</b><span v-if="deadlineOf(linkedReservation).extended">（已延期，原 {{ deadlineOf(linkedReservation).baseText }}）</span></p><p>{{terminationLabels[linkedReservation.termination_reason]||linkedReservation.termination_reason||'尚未终止'}}</p><UsageRecordSummary :record="linkedReservation.usage_record"/><p>这是一条独立的本人记录，不计入当前筛选页总数。</p><div class="row-actions"><SButton v-for="action in actionsOf(linkedReservation)" :key="action.key" :disabled="busyId!==null" @click="doAction(linkedReservation,action.key)">{{action.label}}</SButton><SButton :disabled="busyId!==null" @click="reviewEditor?.open(linkedReservation.id)">查看本人评价</SButton></div></template></section>

      <ReviewEditor ref="reviewEditor"/>
    </div>
  </div>
</template>

<style scoped>


.kpi-lg .kpi-num { font-size: 24px; }
.mine-stack { display: flex; flex-direction: column; gap: 16px; }
.mine-card { min-width: 0; }
.mine-table-wrap { margin-top: 20px; overflow: hidden; }
.mine-table-wrap .table-scroll { overflow-x: hidden; }
.mine-table { width: 100%; table-layout: fixed; }
.mine-table th, .mine-table td { padding: 10px; vertical-align: top; font-size: 13px; }
.mine-table thead th { font-size: 11px; }
.mine-table .num b { display: block; color: var(--text-1); font-weight: 600; font-variant-numeric: tabular-nums; }
.time-line { color: var(--text-3); font-size: 12px; margin-top: 3px; }
.room-cell { min-width: 0; }
.room-name { color: var(--text-1); font-weight: 600; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.seat-line { color: var(--text-3); font-size: 12px; margin-top: 3px; }
.status-cell, .usage-cell { min-width: 0; }
.status-cell .termination-detail { max-width: none; }
.actions-cell .row-actions { display: flex; flex-wrap: wrap; gap: 6px; white-space: normal; }
.actions-cell :deep(.s-btn) { font-size: 11px; }
.deadline-line { margin-top: 6px; color: var(--text-3); font-size: var(--fs-caption); white-space: nowrap; }
.deadline-line b { color: var(--text-1); font-variant-numeric: tabular-nums; }
.deadline-line.extended b { color: var(--amber-strong); }
.pending-strip { display: grid; grid-template-columns: repeat(auto-fit, minmax(260px, 1fr)); gap: 14px; }
.pending-card {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 18px 20px;
  background: linear-gradient(160deg, #fffdf8, #faf3e4);
  border: 1px solid var(--border);
  border-radius: var(--r-xl);
  box-shadow: var(--shadow-1);
}
.pending-head { display: flex; align-items: center; justify-content: space-between; gap: 10px; }
.pending-room { color: var(--text-2); font-size: var(--fs-body-sm); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.pending-time { font-family: var(--font-display); font-size: 20px; color: var(--text-1); }
.pending-deadline { color: var(--text-2); font-size: var(--fs-body-sm); display: flex; flex-wrap: wrap; align-items: baseline; gap: 6px; }
.pending-deadline b { font-family: var(--font-display); font-size: 24px; color: var(--primary); font-variant-numeric: tabular-nums; }
.ext-tag { background: var(--amber-weak); color: var(--amber-strong); border-radius: var(--r-full); padding: 2px 8px; font-size: 11px; }
.ext-hint { color: var(--text-4); font-size: 11px; }
.pending-note { color: var(--text-3); font-size: 11px; line-height: 1.6; }
.pending-card .row-actions { margin-top: auto; }
.linked-deadline { color: var(--text-2); font-size: var(--fs-body-sm); }
.linked-deadline b { color: var(--amber-strong); font-variant-numeric: tabular-nums; }
.termination-detail { font-size:var(--fs-caption); max-width:240px; overflow-wrap:anywhere; color:var(--text-3); margin-top:5px; }
</style>
