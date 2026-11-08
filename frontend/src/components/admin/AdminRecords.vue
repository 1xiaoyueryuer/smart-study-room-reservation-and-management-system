<script setup>
import {ref,onMounted,onBeforeUnmount} from 'vue'
import {listAdminReservations,getAdminReservation} from '../../api/reservation'
import {listAdminViolations} from '../../api/violation'
import {createRecordPager,recordFields} from '../../composables/recordPage.js'
import {reservationStatuses} from '../../composables/complaintForm.js'
import {violationLabels} from '../../composables/appealForm.js'
import RecordFilters from '../RecordFilters.vue'
import RecordPageControls from '../RecordPageControls.vue'
import UsageRecordSummary from '../UsageRecordSummary.vue'
import SButton from '../ui/SButton.vue'
import SBanner from '../ui/SBanner.vue'
import SEmpty from '../ui/SEmpty.vue'
const props=defineProps({kind:{type:String,required:true}}),emit=defineEmits(['correct'])
const reservation=props.kind==='reservation',fields=recordFields(props.kind,true),pager=createRecordPager(reservation?listAdminReservations:listAdminViolations,{initial:reservation?{record_type:'reservation'}:{}})
const violationStatuses={active:'有效',revoked:'已撤销'}
const {items,filters,snapshot,snapshotQuery,loading,error}=pager,detail=ref(null),detailError=ref(''),selected=ref(null),detailLoading=ref(false);let alive=true,ticket=0
async function open(id){const current=++ticket;selected.value=id;detailLoading.value=true;detailError.value='';detail.value=null;try{const {data}=await getAdminReservation(id);if(!alive||current!==ticket)return;if(data?.reservation?.id!==id)throw new Error('管理预约详情不完整');detail.value=data}catch(e){if(alive&&current===ticket)detailError.value=e.message}finally{if(alive&&current===ticket)detailLoading.value=false}}
onMounted(()=>pager.refresh());onBeforeUnmount(()=>{alive=false;ticket++;pager.dispose()});defineExpose({refresh:pager.refresh})
</script>
<template>
  <section class="admin-records">
    <section class="card">
      <div class="card-title-row"><h3>{{reservation?'预约与使用记录':'违规记录'}}</h3><SButton size="sm" :loading="loading" @click="pager.refresh()">刷新</SButton></div>
      <RecordFilters v-model="filters" :fields="fields" :disabled="loading" @apply="pager.apply"/>
      <SBanner v-if="error" type="error" :title="error"/>
      <p v-if="loading">正在读取真实记录…</p>
      <div class="record-box">
        <article v-for="item in items" :key="item.id">
          <template v-if="reservation">
            <h4>预约#{{item.id}} · {{item.real_name||item.username}} / {{item.username}}</h4>
            <p>{{item.res_date}} {{item.start_time}}—{{item.end_time}} · {{item.room_name}} / {{item.seat_no}} · {{reservationStatuses[item.status]||item.status}}</p>
            <UsageRecordSummary :record="item.usage_record"/>
            <SButton @click="open(item.id)">查看管理单条与补签条件</SButton>
          </template>
          <template v-else>
            <h4>违规#{{item.id}} · {{violationLabels[item.type]||item.type}} · {{violationStatuses[item.status]||item.status}}</h4>
            <p>主体#{{item.subject?.id}} {{item.subject?.real_name||item.subject?.username}} / {{item.subject?.username}} / {{item.subject?.campus_id||'校园编号未登记'}}</p>
            <p>发生：{{item.occurred_at}}；登记：{{item.registered_at}}</p>
            <p class="content">{{item.reason}}</p>
            <p v-if="item.revocation">撤销：{{item.revocation.reason}} · {{item.revocation.revoked_at}}</p>
          </template>
        </article>
        <SEmpty v-if="snapshot&&!items.length&&!loading&&!error" description="该次查询无记录"/>
        <p v-else-if="!snapshot&&!loading&&!error">尚未读取记录。</p>
      </div>
      <RecordPageControls :snapshot="snapshot" :query="snapshotQuery" :fields="fields" :disabled="loading" @page="pager.turn"/>
    </section>
    <section v-if="selected" class="card">
      <h3>管理预约#{{selected}}</h3>
      <SBanner v-if="detailError" type="error" :title="detailError"/>
      <p v-if="detailLoading">正在读取单条…</p>
      <template v-if="detail">
        <p>{{detail.reservation.room_name}} / {{detail.reservation.seat_no}} · {{reservationStatuses[detail.reservation.status]||detail.reservation.status}}</p>
        <p>{{detail.reservation.res_date}} {{detail.reservation.start_time}}—{{detail.reservation.end_time}}</p>
        <UsageRecordSummary :record="detail.reservation.usage_record"/>
        <p>补签条件：{{detail.can_correct?'当前允许申请核实补签':detail.blocked_reason}} · 核对{{detail.server_now}}</p>
        <SButton @click="emit('correct',selected)">进入原核实补签入口</SButton>
      </template>
    </section>
  </section>
</template>
<style scoped>
.admin-records,.admin-records .card{display:grid;gap:16px;min-width:0;overflow-wrap:anywhere}
.record-box{border:1px solid var(--border);border-radius:var(--r-lg);padding:16px;display:grid;gap:12px;min-height:120px}
.admin-records article{display:grid;gap:10px;padding-bottom:12px;border-bottom:1px solid var(--hairline)}
.admin-records article:last-of-type{border-bottom:none;padding-bottom:0}
.content{white-space:pre-wrap}
</style>
