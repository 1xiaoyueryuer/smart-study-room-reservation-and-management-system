<script setup>
import PageHelp from '../components/PageHelp.vue'
import SAnimatedNumber from '../components/ui/SAnimatedNumber.vue'
import { usePagination } from '../composables/usePagination'
import SPagination from '../components/ui/SPagination.vue'
import { ref, computed, onMounted } from 'vue'
import { message, confirmDialog } from '../components/ui/feedback'
import { listMyWaitlist, cancelWaitlist } from '../api/waitlist'
import AppIcon from '../components/AppIcon.vue'
import SButton from '../components/ui/SButton.vue'
import STag from '../components/ui/STag.vue'
import SEmpty from '../components/ui/SEmpty.vue'
import SBanner from '../components/ui/SBanner.vue'

const list = ref([])
const loading = ref(false)
const error = ref('')
const cancelling = ref(null)
const filterStatus = ref('all')

const statusMeta = {
  waiting:   { text: '历史等待', type: 'warning' },
  promoted:  { text: '历史已递补', type: 'success' },
  cancelled: { text: '已取消', type: 'info' },
  expired:   { text: '已过期', type: 'info' }
}
const zoneName = { quiet: '静音区', regular: '普通区', discussion: '研讨区', computer: '机房区' }

async function load() {
  loading.value = true
  error.value = ''
  try {
    const resp = await listMyWaitlist()
    list.value = resp.data || []
  } catch (failure) {
    error.value = failure.message
  } finally {
    loading.value = false
  }
}

async function doCancel(row) {
  if (cancelling.value !== null) return
  try { await confirmDialog('确认退出这条历史等待记录？停止递补后不会产生新预约。', '退出历史候补', { type:'warning' }) }
  catch { return }
  cancelling.value = row.id
  error.value = ''
  try {
    await cancelWaitlist(row.id)
    message.success('已退出历史候补')
    await load()
  } catch (failure) { error.value = failure.message }
  finally { cancelling.value = null }
}

function prefText(row) {
  const ps = []
  if (row.zone) ps.push(zoneName[row.zone] || row.zone)
  if (row.has_power) ps.push('电源')
  if (row.near_window) ps.push('靠窗')
  return ps.length ? ps.join(' / ') : '不限'
}

const buckets = computed(() => {
  const b = { all: list.value.length, waiting: 0, promoted: 0, cancelled: 0, expired: 0 }
  list.value.forEach((r) => { if (b[r.status] !== undefined) b[r.status]++ })
  return b
})
const filtered = computed(() => {
  if (filterStatus.value === 'all') return list.value
  return list.value.filter((r) => r.status === filterStatus.value)
})
const nav = [
  { key: 'all',       label: '全部候补', icon: 'waitlist' },
  { key: 'waiting',   label: '历史等待',   icon: 'circle-wait' },
  { key: 'promoted',  label: '历史已递补',   icon: 'circle-success' },
  { key: 'expired',   label: '已过期',   icon: 'circle-muted' },
  { key: 'cancelled', label: '已取消',   icon: 'circle-muted' }
]

onMounted(load)
const { page, pages, pageItems } = usePagination(filtered, filterStatus)
</script>

<template>
  <div v-reveal class="page-view">
    <header class="view-heading has-page-help"><div class="heading-copy" data-page-title>
      <h1>历史候补</h1>
      <p class="heading-sub">保留原记录与来源；已停止新增候补和自动递补</p>
    </div><PageHelp title="历史记录说明">
        <ul class="tips"><li>候补新增和自动递补已停止。</li><li>原状态及历史预约来源保留，不把旧记录改成新规则下的成功预约。</li><li>本人可退出仍为waiting的历史记录，其他记录只读查询。</li></ul>
      </PageHelp></header>
    <div class="split wl-split">
    <div class="split-left">
      <section class="card responsive-compact">
        <div class="card-title-row">
          <h3>候补概览</h3>
          <SButton size="sm" variant="secondary" @click="load"><AppIcon name="refresh" :size="14" />刷新</SButton>
        </div>
        <div class="kpi-grid">
          <div class="kpi"><div class="kpi-num"><SAnimatedNumber :value="buckets.all" /></div><div class="kpi-label">历史候补</div></div>
          <div class="kpi kpi-warn"><div class="kpi-num"><SAnimatedNumber :value="buckets.waiting" /></div><div class="kpi-label">历史等待</div></div>
          <div class="kpi kpi-ok"><div class="kpi-num"><SAnimatedNumber :value="buckets.promoted" /></div><div class="kpi-label">历史已递补</div></div>
          <div class="kpi"><div class="kpi-num"><SAnimatedNumber :value="buckets.expired" /></div><div class="kpi-label">已过期</div></div>
        </div>
      </section>

      <section class="card responsive-compact">
        <div class="card-title-row"><h3>状态分类</h3></div>
        <nav v-active-track class="side-nav">
          <button
            v-for="n in nav"
            :key="n.key"
            class="side-nav-item"
            :class="{ active: filterStatus === n.key }"
            @click="filterStatus = n.key"
          >
            <span class="side-nav-icon"><AppIcon :name="n.icon" :size="16" /></span>
            <span class="side-nav-label">{{ n.label }}</span>
            <span class="side-nav-count">{{ buckets[n.key] ?? 0 }}</span>
          </button>
        </nav>
      </section>

      
    </div>

    <div class="split-right">
      <section class="card">
        <div class="card-title-row">
          <h3>候补记录 <span class="muted">共 {{ filtered.length }} 条</span></h3>
        </div>
        <SBanner type="info" style="margin-bottom: 14px">
          此页仅供查询历史候补及退出仍在等待的记录。系统不再接受新增候补，也不会自动递补。
        </SBanner>

        <SBanner v-if="error" type="error" :title="error"><SButton size="sm" :loading="loading" @click="load">重试读取历史记录</SButton></SBanner>
        <p v-if="loading" role="status">正在读取历史候补…</p>
        <div v-list-motion="pageItems.map(row => row.id + row.status).join()" class="table-wrap">
          <div class="table-scroll" v-loading="loading" tabindex="0" aria-label="候补记录表格，可左右滑动">
            <table class="table waitlist-table">
              <thead>
                <tr>
                  <th style="width:110px">日期</th>
                  <th style="width:130px">时段</th>
                  <th style="min-width:130px">自习室</th>
                  <th style="min-width:120px">偏好</th>
                  <th style="width:98px">状态</th>
                  <th style="width:90px">操作</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="row in pageItems" :key="row.id">
                  <td class="num">{{ row.res_date }}</td>
                  <td class="num">{{ row.start_time.slice(0, 5) }} - {{ row.end_time.slice(0, 5) }}</td>
                  <td class="ellip">{{ row.room_name }}</td>
                  <td class="ellip">{{ prefText(row) }}</td>
                  <td>
                    <STag :type="statusMeta[row.status]?.type" dot :line="row.status === 'cancelled' || row.status === 'expired'">
                      {{ statusMeta[row.status]?.text || row.status }}
                    </STag>
                  </td>
                  <td>
                    <SButton v-if="row.status === 'waiting'" size="sm" variant="soft-danger" :loading="cancelling === row.id" :disabled="cancelling !== null" @click="doCancel(row)">
                      退出
                    </SButton>
                    <span v-else class="cell-dim">—</span>
                  </td>
                </tr>
              </tbody>
            </table>
            <SEmpty v-if="!filtered.length && !loading && !error" description="当前分类下暂无候补记录" />
          </div>
        </div>
        <SPagination v-model="page" :pages="pages" :total="filtered.length" />
      </section>
    </div>
    </div>
  </div>
</template>

<style scoped>

.waitlist-table { min-width: 790px; }

</style>
