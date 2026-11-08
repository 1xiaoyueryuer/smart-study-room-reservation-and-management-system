<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { listMyComplaints, getComplaint } from '../api/complaint'
import { complaintStatuses, complaintCategory, parseRepair } from '../composables/complaintForm.js'
import { useServiceTargetClock } from '../composables/useServiceTargetClock.js'
import ComplaintForm from '../components/ComplaintForm.vue'
import ComplaintRecord from '../components/ComplaintRecord.vue'
import SButton from '../components/ui/SButton.vue'
import SBanner from '../components/ui/SBanner.vue'
import SEmpty from '../components/ui/SEmpty.vue'
import {createRecordPager,recordFields} from '../composables/recordPage.js'
import RecordPageControls from '../components/RecordPageControls.vue'

const route = useRoute()
const pager = createRecordPager(listMyComplaints, { initial: { type: '' } })
const fields = recordFields('complaint')
const {items, loading, loaded, error, filters, snapshot, snapshotQuery} = pager
const formComponent = ref(null)
const formOpen = ref(false)
const category = ref('all')
const selectedId = ref(null)
const detail = ref(null)
const detailLoading = ref(false)
const detailError = ref('')
let detailSequence = 0, alive = true

const targetClock = useServiceTargetClock({records: items, blocked: computed(() => loading.value || !!formComponent.value?.targetReadBlocked), onDue: () => load()})
const load = () => pager.refresh()

const categories = [
  {key: 'all', label: '全部'},
  {key: 'occupation', label: '预约与座位'},
  {key: 'facility', label: '设施报修'},
  {key: 'other', label: '其他服务'}
]

const visibleItems = computed(() => {
  if (category.value === 'all') return items.value
  if (category.value === 'occupation') return items.value.filter(item => item.type === 'occupation')
  if (category.value === 'facility') return items.value.filter(item => item.type === 'service' && !!parseRepair(item.description))
  return items.value.filter(item => item.type === 'service' && !parseRepair(item.description))
})

function categoryOf(item) { return complaintCategory(item.type, item.description) }
function roomOf(item) { return item.context_snapshot?.room_name || (item.room_id ? `空间#${item.room_id}` : '未关联空间') }
function titleOf(item) {
  const repair = parseRepair(item.description)
  if (repair) return `${repair.faultType || '设施'}故障`
  const first = String(item.description || '').split(/\r?\n/).find(line => line.trim() && !line.startsWith('【')) || '求助与投诉'
  return first.length > 24 ? first.slice(0, 24) + '…' : first
}
function timeOf(item) {
  const value = item.submitted_at || ''
  return value ? String(value).replace('T', ' ').slice(0, 16) : '—'
}

function selectCategory(key) {
  if (loading.value) return
  category.value = key
  filters.value = {...filters.value, type: key === 'occupation' ? 'occupation' : (key === 'facility' || key === 'other') ? 'service' : ''}
  pager.apply()
}

async function selectItem(item) {
  selectedId.value = item.id
  const ticket = ++detailSequence
  detailLoading.value = true
  detailError.value = ''
  detail.value = null
  try {
    const response = await getComplaint(item.id)
    if (!alive || ticket !== detailSequence) return
    if (response.data?.id !== item.id) throw new Error('详情返回不完整')
    detail.value = response.data
  } catch (failure) {
    if (alive && ticket === detailSequence) detailError.value = failure.message
  } finally {
    if (alive && ticket === detailSequence) detailLoading.value = false
  }
}

function created(id) {
  formOpen.value = false
  return load().then(() => {
    const target = items.value.find(item => item.id === id)
    if (target) selectItem(target)
  })
}

function toggleForm() {
  formOpen.value = !formOpen.value
}

watch(items, (value) => {
  if (!value.length) { detail.value = null; selectedId.value = null; return }
  if (!selectedId.value || !value.some(item => item.id === selectedId.value)) selectItem(value[0])
}, {immediate: true})

watch(() => route.query, (query) => {
  if (query.reservation_id || query.type) formOpen.value = true
}, {immediate: true})

onMounted(load)
onBeforeUnmount(() => { alive = false; detailSequence++; pager.dispose() })
</script>

<template>
  <div class="page-view complaints-page">
    <header class="view-heading with-action" data-page-title>
      <div class="heading-copy">
        <h1>求助与投诉</h1>
        <p class="heading-sub">预约与座位问题、设施报修、其他服务反馈；提交后等待受理与核实。</p>
      </div>
      <SButton variant="primary" @click="toggleForm">{{ formOpen ? '收起表单' : '＋ 新建求助' }}</SButton>
    </header>

    <section v-if="formOpen" class="card form-panel">
      <ComplaintForm ref="formComponent" :initial-type="String(route.query.type || 'service')" :initial-reservation-id="route.query.reservation_id || null" @created="created" />
    </section>

    <div class="complaint-workbench">
      <section class="card list-panel">
        <nav class="complaint-tabs" aria-label="求助类型">
          <button v-for="tab in categories" :key="tab.key" type="button" :class="{active: category === tab.key}" :disabled="loading" @click="selectCategory(tab.key)">{{ tab.label }}</button>
        </nav>
        <p class="list-note">当前页共 {{ visibleItems.length }} 条；数量以服务端筛选结果为准。</p>
        <SBanner v-if="error" type="error" :title="error"><SButton size="sm" @click="load">重试列表</SButton></SBanner>
        <p v-if="loading" role="status">正在读取本人求助与投诉…</p>
        <ul v-if="!loading" class="ticket-list">
          <li v-for="item in visibleItems" :key="item.id">
            <button type="button" class="ticket-item" :class="{active: selectedId === item.id}" @click="selectItem(item)">
              <span class="pill" :class="item.status === 'closed' ? 'pill-success' : item.status === 'submitted' ? 'pill-neutral' : 'pill-warning'">{{ complaintStatuses[item.status] || item.status }}</span>
              <b>{{ titleOf(item) }}</b>
              <small>{{ categoryOf(item) }} · {{ roomOf(item) }} · {{ timeOf(item) }}</small>
            </button>
          </li>
        </ul>
        <SEmpty v-if="loaded && !loading && !error && !visibleItems.length" description="该分类暂无记录，可新建求助" />
        <RecordPageControls :snapshot="snapshot" :query="snapshotQuery" :fields="fields" :disabled="loading" @page="pager.turn" />
      </section>

      <section class="card detail-panel">
        <div class="detail-head">
          <h3>详情</h3>
          <RouterLink v-if="selectedId" :to="`/complaints/${selectedId}`" class="text-link">打开完整页面</RouterLink>
        </div>
        <p v-if="detailLoading" role="status">正在读取本人详情…</p>
        <SBanner v-if="detailError" type="error" :title="detailError"><SButton size="sm" @click="selectItem({id:selectedId})">重试详情</SButton></SBanner>
        <ComplaintRecord v-if="detail && !detailLoading" :record="detail" :target-progress="targetClock.progress(detail)" />
        <SEmpty v-else-if="!detailLoading && !detailError" description="从左侧选择一条记录查看详情" />
      </section>
    </div>
  </div>
</template>

<style scoped>
.complaints-page { max-width: 1480px; }
.view-heading.with-action { display: flex; align-items: flex-end; justify-content: space-between; gap: 18px; }
.form-panel { margin-bottom: 20px; }
.complaint-workbench { display: grid; grid-template-columns: minmax(300px, 380px) minmax(0, 1fr); gap: 20px; align-items: start; }
.list-panel { display: flex; flex-direction: column; min-width: 0; max-height: calc(100vh - 240px); }
.complaint-tabs { display: flex; flex-wrap: wrap; gap: 4px; padding-bottom: 12px; border-bottom: 1px solid var(--hairline); margin-bottom: 10px; }
.complaint-tabs button {
  all: unset;
  padding: 7px 12px;
  border-radius: var(--r-md);
  color: var(--text-2);
  font-size: var(--fs-body-sm);
  cursor: pointer;
  white-space: nowrap;
}
.complaint-tabs button:hover { background: var(--surface-2); color: var(--text-1); }
.complaint-tabs button.active { background: var(--primary-weak); color: var(--primary-active); font-weight: 600; }
.list-note { color: var(--text-4); font-size: 11px; margin-bottom: 10px; }
.ticket-list { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 8px; overflow-y: auto; min-height: 0; }
.ticket-item {
  all: unset;
  display: grid;
  gap: 6px;
  width: 100%;
  box-sizing: border-box;
  padding: 14px 15px;
  border: 1px solid var(--border);
  border-radius: var(--r-lg);
  background: var(--surface);
  cursor: pointer;
  transition: border-color var(--dur-1) var(--ease), background var(--dur-1) var(--ease), transform var(--dur-1) var(--ease);
}
.ticket-item:hover { border-color: var(--border-strong); transform: translateY(-1px); }
.ticket-item.active { border-color: var(--primary); background: var(--primary-faint); }
.ticket-item b { color: var(--text-1); font-size: 15px; font-weight: 600; line-height: 1.45; }
.ticket-item small { color: var(--text-3); font-size: 11px; line-height: 1.5; }
.ticket-item .pill { justify-self: start; }
.detail-panel { min-width: 0; min-height: 420px; }
.detail-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding-bottom: 12px; margin-bottom: 14px; border-bottom: 1px solid var(--hairline); }
.text-link { color: var(--primary); font-size: var(--fs-caption); text-decoration: none; }
.text-link:hover { text-decoration: underline; }
@media (max-width: 960px) {
  .complaint-workbench { grid-template-columns: 1fr; }
  .list-panel { max-height: none; }
  .view-heading.with-action { align-items: flex-start; flex-direction: column; }
}
</style>
