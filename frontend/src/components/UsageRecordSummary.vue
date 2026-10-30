<script setup>
import { computed } from 'vue'
const props = defineProps({ record: { type: Object, default: null } })
const originLabels = { seat_code: '座位码签到', legacy: '历史迁移', demo_seed: '演示模拟（非到场证据）' }
const reasonLabels = { admin_resource_change: '资源管理取消', early_checkout: '提前签退', auto_end: '到期自动结束', temp_leave_timeout: '旧暂离流程结束（历史）', legacy_unclassified: '历史结束，具体方式未记录', demo_completed: '演示模拟结束' }
const durationText = computed(() => {
  const value = props.record?.duration_seconds
  return value !== null && value !== undefined && value !== '' && Number.isFinite(Number(value)) && Number(value) >= 0 ? `${value}秒` : '时长未登记'
})
</script>
<template>
  <div class="usage-record">
    <template v-if="record">
      <div>签到：{{ record.checkin_at || '未登记' }}（校园时间）</div>
      <div>使用结束：{{ record.ended_at || '尚未结束' }}</div>
      <div v-if="record.ended_at">结束原因：{{ reasonLabels[record.end_reason] || record.end_reason || '未登记' }}</div>
      <div>使用时长：{{ durationText }}<span v-if="!record.ended_at">（截至本次服务端读取，非实时计时）</span></div>
      <div v-if="record.observed_at">读取时刻：{{ record.observed_at }}</div>
      <div v-if="record.settled_at">结算登记：{{ record.settled_at }}</div>
      <div>使用记录来源：{{ record.origin==='admin_correction'?'管理员核实补签':originLabels[record.origin] || record.origin || '未登记' }}</div>
      <template v-if="record.correction"><div>补签实际操作：{{record.correction.operated_at}} · {{record.correction.actor_display_name}}</div><div>公开补签依据：{{record.correction.reason}}</div><div>核实来源：管理员自报</div></template>
    </template>
    <span v-else>尚无使用记录</span>
  </div>
</template>
<style scoped>
.usage-record{display:grid;gap:5px;font-size:var(--fs-caption);color:var(--text-2);overflow-wrap:anywhere;min-width:0}
</style>
