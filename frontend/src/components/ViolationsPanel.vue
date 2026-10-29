<script setup>
import { computed, onMounted, ref } from 'vue'
import { listMyViolations } from '../api/violation'
import SButton from './ui/SButton.vue'
import SBanner from './ui/SBanner.vue'
import STag from './ui/STag.vue'
import SEmpty from './ui/SEmpty.vue'
const props = defineProps({ autoLoad: { type:Boolean, default:true } })
const items = ref([]), loading = ref(false), error = ref(''), loaded = ref(false)
const activeCount = computed(() => items.value.filter(item => item.status === 'active').length)
const typeLabels = { abandon:'开始后主动放弃', no_show:'超时未签到' }
let sequence = 0
async function refresh() {
  const request = ++sequence
  loading.value = true
  error.value = ''
  try {
    const response = await listMyViolations()
    if (request !== sequence) return
    items.value = response.data
    loaded.value = true
  } catch (failure) { if (request === sequence) error.value = failure.message }
  finally { if (request === sequence) loading.value = false }
}
onMounted(() => { if (props.autoLoad) void refresh() })
defineExpose({ refresh })
</script>
<template>
  <section id="violations" class="card">
    <div class="card-title-row"><h3>本人独立违规记录</h3><SButton size="sm" :loading="loading" @click="refresh">刷新违规记录</SButton></div>
    <p class="muted">按独立事件记录，不由预约终态或历史信用分换算。时间按校园时区Asia/Shanghai展示。</p>
    <p v-if="loading" role="status">正在读取本人违规记录…</p>
    <SBanner v-else-if="error" type="error" :title="error"><SButton size="sm" @click="refresh">重试违规查询</SButton></SBanner>
    <p v-else-if="!loaded" role="status" class="muted">尚未读取本人独立违规记录，请等待预约状态核对或点击刷新。</p>
    <template v-else>
      <p class="muted">已登记{{ items.length }}条，其中有效{{ activeCount }}条；历史信用流水另行只读保留。</p>
      <div class="table-wrap"><div class="table-scroll" tabindex="0" aria-label="本人独立违规明细，可左右滑动"><table class="table violation-table"><thead><tr><th>预约</th><th>事件类型</th><th>发生时间</th><th>登记时间</th><th>有效性 / 来源</th><th>依据</th></tr></thead><tbody><tr v-for="item in items" :key="item.id"><td>#{{ item.reservation_id }}</td><td>{{ typeLabels[item.type] || item.type }}</td><td>{{ item.occurred_at }}</td><td>{{ item.registered_at }}</td><td><STag :type="item.status === 'active' ? 'danger' : 'neutral'">{{ item.status === 'active' ? '有效' : item.status === 'revoked' ? '已撤销' : item.status }}</STag><div>{{ item.source === 'automatic' ? '系统自动事件' : item.source }}</div></td><td>{{ item.reason }}</td></tr></tbody></table><SEmpty v-if="!items.length" description="尚无已登记的独立违规记录" /></div></div>
    </template>
  </section>
</template>
<style scoped>
.violation-table { min-width:900px; }
</style>
