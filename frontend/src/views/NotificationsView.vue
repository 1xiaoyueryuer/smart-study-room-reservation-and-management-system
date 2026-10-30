<script setup>
import PageHelp from '../components/PageHelp.vue'
import {ref,computed,onMounted,onBeforeUnmount,watch} from 'vue'
import {message} from '../components/ui/feedback'
import {listNotifications,markRead,markAllRead} from '../api/notification'
import {useNotificationStore} from '../stores/notification'
import {useAuthStore} from '../stores/auth'
import {createRecordPager} from '../composables/recordPage.js'
import {reviewNotificationTarget} from '../composables/reviewManagement.js'
import {validNotificationPage,validReadReceipt,reservationTarget,appealTarget,restrictionTarget,violationTarget,complaintTarget,hasNotificationTarget} from '../composables/notificationPage.js'
import RecordFilters from '../components/RecordFilters.vue'
import RecordPageControls from '../components/RecordPageControls.vue'
import AppIcon from '../components/AppIcon.vue'
import SButton from '../components/ui/SButton.vue'
import SEmpty from '../components/ui/SEmpty.vue'
import SBanner from '../components/ui/SBanner.vue'
const auth=useAuthStore(),notifStore=useNotificationStore(),markingId=ref(null),markingAll=ref(false),actionError=ref(''),writeNotice=ref('')
const scope=()=>`${auth.user?.id}:${auth.token}:${auth.user?.role}`
let alive=true
const pager=createRecordPager(async query=>{
 const identity=scope(),ticket=notifStore.beginUpdate(),response=await listNotifications(query)
 if(!alive||identity!==scope())throw new Error('账号已变化，请重新读取本人消息')
 if(!validNotificationPage(response.data,query))throw new Error('消息分页或全本人汇总响应不完整')
 notifStore.acceptUnread(response.data.counts.unread,ticket)
 return response
},{blocked:()=>markingId.value!==null||markingAll.value})
const {items:list,loading,error,filters,snapshot,snapshotQuery}=pager
const typeMeta = {
  reservation_checkin:{text:'预约签到成功',color:'var(--green-strong)',bg:'var(--green-weak)'},
  reservation_completed:{text:'预约使用结束',color:'var(--text-2)',bg:'var(--surface-3)'},
  review_replied:{text:'评价管理回复',color:'var(--primary-active)',bg:'var(--primary-weak)'},
  review_hidden:{text:'评价隐藏通知',color:'var(--text-2)',bg:'var(--surface-3)'},
  reservation_corrected_checkin:{text:'管理员核实补签',color:'var(--primary-active)',bg:'var(--primary-weak)'},
  reservation_success: { text: '预约成功', color: 'var(--green-strong)', bg: 'var(--green-weak)' },
  reservation_cancelled:{ text:'预约取消', color:'var(--text-3)', bg:'var(--surface-3)' },
  management_cancelled:{ text:'资源管理取消', color:'var(--text-3)', bg:'var(--surface-3)' },
  checkin_reminder:   { text: '历史签到提醒', color: 'var(--primary-active)', bg: 'var(--primary-weak)' },
  appeal_reviewed:{text:'申诉审核结果',color:'var(--primary-active)',bg:'var(--primary-weak)'},
  restriction_started:{text:'预约限制生效',color:'var(--red-strong)',bg:'var(--red-weak)'},
  restriction_released:{text:'单条预约限制解除',color:'var(--green-strong)',bg:'var(--green-weak)'},
  restriction_cancelled:{text:'限制管理取消预约',color:'var(--text-2)',bg:'var(--surface-3)'},
  violation:          { text: '违规通知', color: 'var(--red-strong)', bg: 'var(--red-weak)' },
  credit_change:      { text: '历史信用变动', color: 'var(--amber-strong)', bg: 'var(--amber-weak)' },
  waitlist_promoted:  { text: '历史候补递补', color: 'var(--violet-strong)', bg: 'var(--violet-weak)' },
  complaint_feedback:{ text:'投诉反馈',color:'var(--primary-active)',bg:'var(--primary-weak)' },
  complaint_auto_closed:{ text:'投诉自动结案',color:'var(--text-2)',bg:'var(--surface-3)' },
  system:             { text: '系统',     color: 'var(--text-2)', bg: 'var(--surface-3)' }
}

const fields=[{key:'type',label:'消息类型',options:[{value:'',label:'全部类型'},...Object.entries(typeMeta).map(([value,meta])=>({value,label:meta.text}))]},{key:'is_read',label:'已读状态',options:[{value:'',label:'全部已读状态'},{value:'false',label:'未读'},{value:'true',label:'已读'}]}]
const busy=computed(()=>markingId.value!==null||markingAll.value)
const buckets=computed(()=>snapshot.value?{...snapshot.value.counts.by_type,all:snapshot.value.counts.total,unread:snapshot.value.counts.unread}:null)
const typeNav=computed(()=>[{key:'all',label:'全部消息',icon:'inbox'},{key:'unread',label:'全部未读',icon:'notification'},...Object.entries(typeMeta).map(([key,value])=>({key,label:value.text,icon:'circle-muted'}))])
const activeCategory=computed(()=>filters.value.type|| (filters.value.is_read==='false'?'unread':'all'))
function selectCategory(key){if(busy.value)return;filters.value={type:['all','unread'].includes(key)?'':key,is_read:key==='unread'?'false':''};return pager.apply()}
const load=()=>pager.refresh()
async function afterRead(){await Promise.all([pager.refresh(true),notifStore.refresh()])}
async function onMarkRead(item){if(busy.value||item.is_read||!Number.isSafeInteger(item.id)||item.id<1)return;const identity=scope(),ticket=notifStore.beginUpdate();markingId.value=item.id;actionError.value='';writeNotice.value='';try{const {data}=await markRead(item.id);if(!alive||identity!==scope())return;if(!validReadReceipt(data,item.id))throw new Error('已读回执不完整，请主动重读或再次核对');notifStore.acceptUnread(data.unread_count,ticket);writeNotice.value=`本次实际标记${data.marked}条已读；服务端当时未读${data.unread_count}条。`;await afterRead()}catch(e){if(alive&&identity===scope())actionError.value=e.message+'；未伪造已读状态，可主动重试核对。'}finally{if(alive&&identity===scope())markingId.value=null}}
async function onMarkAll(){if(busy.value)return;const identity=scope(),ticket=notifStore.beginUpdate();markingAll.value=true;actionError.value='';writeNotice.value='';try{const {data}=await markAllRead();if(!alive||identity!==scope())return;if(!validReadReceipt(data,null))throw new Error('全部已读回执不完整，请主动重读或再次核对');notifStore.acceptUnread(data.unread_count,ticket);writeNotice.value=`本次实际标记${data.marked}条已读；服务端当时仍未读${data.unread_count}条，新消息不会被强行清零。`;message.success(`本次已标记${data.marked}条`);await afterRead()}catch(e){if(alive&&identity===scope())actionError.value=e.message+'；保留上次成功快照，可主动重试核对。'}finally{if(alive&&identity===scope())markingAll.value=false}}
function fmtTime(value){return new Date(value).toLocaleString('zh-CN',{hour12:false})}
function linked(item){return hasNotificationTarget(item,reviewNotificationTarget)}
watch(scope,()=>{pager.items.value=[];pager.snapshot.value=null;pager.snapshotQuery.value=null;pager.loaded.value=false;actionError.value='';writeNotice.value='';markingId.value=null;markingAll.value=false})
onMounted(load);onBeforeUnmount(()=>{alive=false;pager.dispose()})
</script>
<template><div class="page-view"><header class="view-heading has-page-help"><div class="heading-copy" data-page-title><h1>消息中心</h1><p>业务结果与历史通知；关联目标独立核对本人权限。</p></div><PageHelp title="消息说明"><ul class="tips"><li>消息已读不代表业务已处理或申诉期限重置。</li><li>数量汇总覆盖本人的全部消息，不受当前筛选限制；列表总数按筛选条件计算。</li><li>无可靠关联的历史消息不根据正文猜测目标。</li></ul></PageHelp></header><div class="split notif-split"><div class="split-left"><section class="card"><h3>全本人消息汇总</h3><p>总消息：{{buckets?.all??'尚未读取'}}；未读快照：{{buckets?.unread??'尚未读取'}}</p><p v-if="notifStore.hasValue">最近权威未读数：{{notifStore.unread}}</p><SBanner v-if="notifStore.error" type="error" :title="notifStore.error"/><SButton :disabled="busy" @click="notifStore.refresh()">复核未读角标</SButton><SButton :disabled="busy||!buckets" :loading="markingAll" @click="onMarkAll">全部标记已读</SButton></section><section class="card"><h3>消息类型（全本人数量）</h3><nav class="side-nav"><button v-for="category in typeNav" :key="category.key" class="side-nav-item" :class="{active:activeCategory===category.key}" :disabled="busy||loading" @click="selectCategory(category.key)"><AppIcon :name="category.icon" :size="16"/><span>{{category.label}}</span><span>{{buckets?(buckets[category.key]??0):'—'}}</span></button></nav></section></div><div class="split-right"><section class="card"><h3>消息列表</h3><RecordFilters v-model="filters" :fields="fields" :disabled="busy||loading" @apply="pager.apply"/><SButton :disabled="busy||loading" @click="load">刷新已应用条件</SButton><RecordPageControls :snapshot="snapshot" :query="snapshotQuery" :fields="fields" :disabled="busy||loading" @page="pager.turn"/><SBanner v-if="error" type="error" :title="error"/><SBanner v-if="actionError" type="error" :title="actionError"/><p v-if="writeNotice" role="status">{{writeNotice}}</p><p v-if="loading" role="status">正在读取真实消息页；下方保留上次成功快照。</p><div class="list"><SEmpty v-if="snapshot&&!list.length&&!loading&&!error" icon="inbox" description="该次筛选暂无消息"/><div v-for="item in list" :key="item.id" class="msg-entry"><button class="msg-item" :class="{unread:!item.is_read}" :disabled="busy||item.is_read" @click="onMarkRead(item)"><span class="msg-type" :style="{color:typeMeta[item.type]?.color,background:typeMeta[item.type]?.bg}">{{typeMeta[item.type]?.text||'历史消息类型'}}</span><div class="msg-body"><div class="msg-title"><span class="msg-title-text">{{item.title}}</span><span v-if="!item.is_read" class="msg-dot" aria-label="未读"/></div><div class="msg-content">{{item.content}}</div></div><span class="msg-time">{{fmtTime(item.created_at)}} · {{item.is_read?'已读':'未读'}}</span></button><RouterLink v-if="reservationTarget(item)" class="complaint-link" :to="reservationTarget(item)">查看本人关联预约/使用记录</RouterLink><RouterLink v-if="reviewNotificationTarget(item)" class="complaint-link" :to="reviewNotificationTarget(item)">查看本人评价与管理结果</RouterLink><RouterLink v-if="appealTarget(item)" class="complaint-link" :to="appealTarget(item)">查看本人申诉审核结果</RouterLink><RouterLink v-if="restrictionTarget(item)" class="complaint-link" :to="restrictionTarget(item)">查看本人关联限制与资格</RouterLink><RouterLink v-if="violationTarget(item)" class="complaint-link" :to="violationTarget(item)">查看本人关联违规</RouterLink><RouterLink v-if="complaintTarget(item)" class="complaint-link" :to="complaintTarget(item)">查看本人投诉进度</RouterLink><p v-if="!linked(item)" class="complaint-link">历史消息未保存可定位关联；不根据正文猜测业务目标。</p></div></div></section></div></div></div></template>
<style scoped>
.msg-entry{min-width:0}.complaint-link{display:inline-block;margin:0 16px 10px 106px;font-size:var(--fs-caption)}@media(max-width:600px){.complaint-link{margin-left:16px}}


.list {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
/* PC 宽屏：消息列表在右栏卡内纵向滚动，右栏高度可控 */

.msg-item {
  all: unset;
  display: grid;
  grid-template-columns: 76px 1fr auto;
  gap: 14px;
  align-items: flex-start;
  padding: 14px 16px;
  border-radius: var(--r-lg);
  cursor: pointer;
  transition: background var(--dur-1) var(--ease);
  border: 1px solid transparent;
  box-sizing: border-box;
  width: 100%;
}
.msg-item:hover {
  background: var(--surface-hover);
}
.msg-item:focus-visible {
  outline: 2px solid var(--primary);
  outline-offset: -2px;
}
.msg-item.unread {
  background: var(--primary-faint);
  border-color: var(--border-strong);
}
.msg-body { min-width: 0; }
.msg-type {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  height: 22px;
  padding: 0 9px;
  border-radius: var(--r-sm);
  font-size: 11.5px;
  font-weight: 500;
  white-space: nowrap;
}
.msg-title {
  font-weight: 600;
  color: var(--text-1);
  display: flex;
  align-items: center;
  gap: 6px;
}
.msg-title-text {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.msg-dot {
  width: 7px;
  height: 7px;
  flex: 0 0 7px;
  border-radius: 50%;
  background: var(--primary);
}
.msg-content {
  color: var(--text-2);
  font-size: var(--fs-body-sm);
  margin-top: 4px;
  line-height: 1.6;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.msg-time {
  color: var(--text-4);
  font-size: var(--fs-caption);
  white-space: nowrap;
  font-variant-numeric: tabular-nums;
  padding-top: 2px;
}

@media (max-width: 720px) {
  .msg-item {
    grid-template-columns: minmax(0, 1fr);
    gap: 8px 10px;
    padding: 12px;
  }
  .msg-time {
    grid-column: 1;
    white-space: normal;
  }
}
</style>
