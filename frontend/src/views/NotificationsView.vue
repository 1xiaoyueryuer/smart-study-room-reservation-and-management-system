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
const {items:list,loading,error,snapshot,snapshotQuery}=pager
const category=ref('all'),selected=ref(null)

const typeMeta = {
  reservation_checkin:{text:'签到成功',color:'var(--green-strong)',bg:'var(--green-weak)'},
  reservation_completed:{text:'使用结束',color:'var(--text-2)',bg:'var(--surface-3)'},
  review_replied:{text:'评价回复',color:'var(--primary-active)',bg:'var(--primary-weak)'},
  review_hidden:{text:'评价隐藏',color:'var(--text-2)',bg:'var(--surface-3)'},
  reservation_corrected_checkin:{text:'核实补签',color:'var(--primary-active)',bg:'var(--primary-weak)'},
  reservation_success:{text:'预约成功',color:'var(--green-strong)',bg:'var(--green-weak)'},
  reservation_cancelled:{text:'预约取消',color:'var(--text-3)',bg:'var(--surface-3)'},
  management_cancelled:{text:'资源管理取消',color:'var(--amber-strong)',bg:'var(--amber-weak)'},
  checkin_reminder:{text:'签到提醒',color:'var(--primary-active)',bg:'var(--primary-weak)'},
  appeal_reviewed:{text:'申诉审核',color:'var(--primary-active)',bg:'var(--primary-weak)'},
  restriction_started:{text:'限制生效',color:'var(--red-strong)',bg:'var(--red-weak)'},
  restriction_released:{text:'限制解除',color:'var(--green-strong)',bg:'var(--green-weak)'},
  restriction_cancelled:{text:'限制取消预约',color:'var(--text-2)',bg:'var(--surface-3)'},
  violation:{text:'违规通知',color:'var(--red-strong)',bg:'var(--red-weak)'},
  credit_change:{text:'历史信用变动',color:'var(--amber-strong)',bg:'var(--amber-weak)'},
  waitlist_promoted:{text:'历史候补',color:'var(--violet-strong)',bg:'var(--violet-weak)'},
  complaint_feedback:{text:'投诉反馈',color:'var(--primary-active)',bg:'var(--primary-weak)'},
  complaint_auto_closed:{text:'投诉结案',color:'var(--text-2)',bg:'var(--surface-3)'},
  system:{text:'系统通知',color:'var(--text-2)',bg:'var(--surface-3)'}
}

const groups = {
  all: null,
  unread: null,
  reservation: ['reservation_success','reservation_cancelled','reservation_checkin','reservation_completed','reservation_corrected_checkin','management_cancelled','restriction_cancelled','checkin_reminder'],
  system: ['system','violation','restriction_started','restriction_released','credit_change','waitlist_promoted','appeal_reviewed'],
  feedback: ['complaint_feedback','complaint_auto_closed','review_replied','review_hidden']
}
const categories = [
  {key:'all',label:'全部'},
  {key:'unread',label:'未读'},
  {key:'reservation',label:'预约',title:'预约提醒'},
  {key:'system',label:'系统',title:'系统通知'},
  {key:'feedback',label:'反馈',title:'投诉与评价'}
]
const visibleItems = computed(() => {
  if (category.value === 'unread') return list.value.filter(item => !item.is_read)
  const allowed = groups[category.value]
  return allowed ? list.value.filter(item => allowed.includes(item.type)) : list.value
})
const unreadCount = computed(() => list.value.filter(item => !item.is_read).length)
const busy=computed(()=>markingId.value!==null||markingAll.value)

function selectCategory(key){if(busy.value)return;category.value=key}
function fmtTime(value){return new Date(value).toLocaleString('zh-CN',{hour12:false})}
function shortTime(value){const date=new Date(value);return Number.isNaN(date.getTime())?String(value):date.toLocaleString('zh-CN',{month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit',hour12:false})}
function linked(item){return hasNotificationTarget(item,reviewNotificationTarget)}

async function afterRead(){await Promise.all([pager.refresh(true),notifStore.refresh()])}
async function onMarkRead(item){
  if(busy.value||item.is_read||!Number.isSafeInteger(item.id)||item.id<1)return
  const identity=scope(),ticket=notifStore.beginUpdate()
  markingId.value=item.id;actionError.value='';writeNotice.value=''
  try{
    const {data}=await markRead(item.id)
    if(!alive||identity!==scope())return
    if(!validReadReceipt(data,item.id))throw new Error('已读回执不完整，请主动重读或再次核对')
    notifStore.acceptUnread(data.unread_count,ticket)
    writeNotice.value=`本次实际标记${data.marked}条已读；服务端当时未读${data.unread_count}条。`
    await afterRead()
    const updated=list.value.find(row=>row.id===item.id)
    if(updated)selected.value=updated
  }catch(e){if(alive&&identity===scope())actionError.value=e.message+'；未伪造已读状态，可主动重试核对。'}
  finally{if(alive&&identity===scope())markingId.value=null}
}
async function onMarkAll(){
  if(busy.value)return
  const identity=scope(),ticket=notifStore.beginUpdate()
  markingAll.value=true;actionError.value='';writeNotice.value=''
  try{
    const {data}=await markAllRead()
    if(!alive||identity!==scope())return
    if(!validReadReceipt(data,null))throw new Error('全部已读回执不完整，请主动重读或再次核对')
    notifStore.acceptUnread(data.unread_count,ticket)
    writeNotice.value=`本次实际标记${data.marked}条已读；服务端当时仍未读${data.unread_count}条。`
    message.success(`本次已标记${data.marked}条`)
    await afterRead()
  }catch(e){if(alive&&identity===scope())actionError.value=e.message+'；保留上次成功快照，可主动重试核对。'}
  finally{if(alive&&identity===scope())markingAll.value=false}
}
function selectItem(item){
  selected.value=item
  if(!item.is_read)void onMarkRead(item)
}



function cancelLink(item){const target=reservationTarget(item);return target?{...target,query:{...target.query,action:'cancel'}}:null}

watch(list,(value)=>{
  if(!value.length){selected.value=null;return}
  if(!selected.value||!value.some(item=>item.id===selected.value.id))selected.value=value[0]
},{immediate:true})
watch(scope,()=>{category.value='all';selected.value=null;pager.items.value=[];pager.snapshot.value=null;pager.snapshotQuery.value=null;pager.loaded.value=false;actionError.value='';writeNotice.value='';markingId.value=null;markingAll.value=false})
onMounted(pager.refresh);onBeforeUnmount(()=>{alive=false;pager.dispose()})
</script>

<template>
  <div class="page-view notifications-page">
    <header class="view-heading has-page-help with-action" data-page-title>
      <div class="heading-copy"><h1>消息中心</h1><p class="heading-sub">预约、管理取消、违规限制、申诉与反馈的业务结果通知；已读不代表业务已处理。</p></div>
      <div class="head-actions">
        <SButton :disabled="busy||!list.length" :loading="markingAll" @click="onMarkAll">全部标记已读</SButton>
      </div>
      <PageHelp title="消息说明"><ul class="tips"><li>消息已读不代表业务已处理或申诉期限重置。</li><li>数量汇总覆盖本人的全部消息，不受当前分类限制。</li><li>无可靠关联的历史消息不根据正文猜测目标。</li></ul></PageHelp>
    </header>

    <SBanner v-if="notifStore.error" type="error" :title="notifStore.error"/>
    <SBanner v-if="actionError" type="error" :title="actionError"/>
    <p v-if="writeNotice" class="write-notice" role="status">{{ writeNotice }}</p>

    <div class="notif-workbench">
      <section class="card notif-list-panel">
        <nav class="notif-tabs" aria-label="消息分类">
          <button v-for="tab in categories" :key="tab.key" type="button" :class="{active: category===tab.key}" :disabled="busy" :title="tab.title || tab.label" @click="selectCategory(tab.key)">
            {{ tab.label }}<span v-if="tab.key==='unread'&&unreadCount" class="tab-count">{{ unreadCount }}</span>
          </button>
        </nav>
        <p class="list-note">当前页 {{ visibleItems.length }} 条 · 未读 {{ unreadCount }} 条</p>
        <p v-if="loading" role="status">正在读取消息…</p>
        <ul v-else class="msg-list">
          <li v-for="item in visibleItems" :key="item.id">
            <button type="button" class="msg-entry" :class="{active: selected?.id===item.id, unread: !item.is_read}" @click="selectItem(item)">
              <span class="msg-icon" :style="{background:typeMeta[item.type]?.bg||'var(--surface-3)',color:typeMeta[item.type]?.color||'var(--text-2)'}"><AppIcon name="notification-empty" :size="18"/></span>
              <span class="msg-copy">
                <b>{{ item.title }}</b>
                <small>{{ item.content }}</small>
              </span>
              <span class="msg-side"><time>{{ shortTime(item.created_at) }}</time><i v-if="!item.is_read" class="dot" aria-label="未读"/></span>
            </button>
          </li>
        </ul>
        <SEmpty v-if="!loading && !visibleItems.length && !error" icon="inbox" description="该分类暂无消息"/>
        <SBanner v-if="error" type="error" :title="error"><SButton size="sm" @click="pager.refresh">重试</SButton></SBanner>
        <RecordPageControls :snapshot="snapshot" :query="snapshotQuery" :fields="[]" :disabled="busy||loading" @page="pager.turn"/>
      </section>

      <section class="card notif-detail-panel">
        <template v-if="selected">
          <header class="detail-head">
            <span class="pill" :style="{color:typeMeta[selected.type]?.color,background:typeMeta[selected.type]?.bg}">{{ typeMeta[selected.type]?.text || '历史消息' }}</span>
            <time>{{ fmtTime(selected.created_at) }}</time>
          </header>
          <h3>{{ selected.title }}</h3>
          <p class="detail-content">{{ selected.content }}</p>

          <section v-if="selected.type==='management_cancelled'" class="cancel-card">
            <h4>资源管理取消</h4>
            <p>原因：{{ selected.cancel_reason || selected.content || '未提供' }}</p>
            <p v-if="selected.expected_reopen_at">预计恢复：{{ selected.expected_reopen_at }}</p>
            <p v-else>预计恢复时间：待定，请以系统可预约状态为准。</p>
            <p>本次管理取消不记本人违规；无需专程到场确认，已到场或需要帮助可点“需要协助”。</p>
            <div class="row-actions">
                              <RouterLink :to="{name:'complaints',query:{type:'service'}}" class="action-link">需要协助（发起求助）</RouterLink>
                <span class="muted">确认收到回执需服务端接口；当前消息可作为取消依据。</span>
            </div>
          </section>

          <section class="detail-actions">
            <RouterLink v-if="reservationTarget(selected)" :to="reservationTarget(selected)" class="action-link">查看我的预约</RouterLink>
            <RouterLink v-if="cancelLink(selected)" :to="cancelLink(selected)" class="action-link">查看并取消预约</RouterLink>
            <RouterLink v-if="reviewNotificationTarget(selected)" :to="reviewNotificationTarget(selected)" class="action-link">查看本人评价与管理结果</RouterLink>
            <RouterLink v-if="appealTarget(selected)" :to="appealTarget(selected)" class="action-link">查看本人申诉审核结果</RouterLink>
            <RouterLink v-if="restrictionTarget(selected)" :to="restrictionTarget(selected)" class="action-link">查看本人限制与资格</RouterLink>
            <RouterLink v-if="violationTarget(selected)" :to="violationTarget(selected)" class="action-link">查看本人违规</RouterLink>
            <RouterLink v-if="complaintTarget(selected)" :to="complaintTarget(selected)" class="action-link">查看投诉进度</RouterLink>
            <p v-if="!linked(selected)" class="muted">该历史消息未保存可定位关联，不根据正文猜测业务目标。</p>
          </section>
        </template>
        <SEmpty v-else icon="inbox" description="从左侧选择一条消息查看详情"/>
      </section>
    </div>
  </div>
</template>

<style scoped>
.notifications-page { max-width: 1480px; }
.view-heading.with-action { display: flex; align-items: flex-end; justify-content: space-between; gap: 18px; flex-wrap: wrap; }
.head-actions { display: flex; gap: 10px; }
.write-notice { color: var(--text-3); font-size: var(--fs-caption); margin: -8px 0 14px; }
.notif-workbench { display: grid; grid-template-columns: minmax(320px, 400px) minmax(0, 1fr); gap: 20px; align-items: start; }
.notif-list-panel { display: flex; flex-direction: column; min-width: 0; max-height: calc(100vh - 220px); }
.notif-tabs { display: flex; flex-wrap: nowrap; gap: 4px; padding-bottom: 12px; border-bottom: 1px solid var(--hairline); margin-bottom: 10px; }
.notif-tabs button {
  all: unset;
  flex: 1 1 0;
  box-sizing: border-box;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 4px;
  padding: 7px 6px;
  border-radius: var(--r-md);
  color: var(--text-2);
  font-size: 13px;
  cursor: pointer;
  white-space: nowrap;
}
.notif-tabs button:hover { background: var(--surface-2); color: var(--text-1); }
.notif-tabs button.active { background: var(--primary-weak); color: var(--primary-active); font-weight: 600; }
.tab-count { min-width: 17px; height: 17px; padding: 0 5px; border-radius: var(--r-full); background: var(--red); color: #fff; font-size: 10px; display: inline-grid; place-items: center; }
.list-note { color: var(--text-4); font-size: 11px; margin-bottom: 10px; }
.msg-list { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 6px; overflow-y: auto; min-height: 0; }
.msg-entry {
  all: unset;
  display: grid;
  grid-template-columns: 40px minmax(0, 1fr) auto;
  gap: 11px;
  align-items: start;
  width: 100%;
  box-sizing: border-box;
  padding: 12px 13px;
  border: 1px solid transparent;
  border-radius: var(--r-lg);
  cursor: pointer;
  transition: background var(--dur-1) var(--ease), border-color var(--dur-1) var(--ease);
}
.msg-entry:hover { background: var(--surface-2); }
.msg-entry.active { background: var(--primary-faint); border-color: var(--primary-weak); }
.msg-entry.unread { border-color: var(--border); }
.msg-icon { width: 38px; height: 38px; border-radius: 50%; display: grid; place-items: center; }
.msg-copy { min-width: 0; }
.msg-copy b { display: block; color: var(--text-1); font-size: 14px; font-weight: 600; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.msg-copy small { display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; color: var(--text-3); font-size: 11px; line-height: 1.55; margin-top: 3px; }
.msg-side { display: flex; flex-direction: column; align-items: flex-end; gap: 5px; }
.msg-side time { color: var(--text-4); font-size: 10px; white-space: nowrap; }
.dot { width: 7px; height: 7px; border-radius: 50%; background: var(--red); }
.notif-detail-panel { min-width: 0; min-height: 420px; }
.detail-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding-bottom: 12px; border-bottom: 1px solid var(--hairline); margin-bottom: 14px; }
.detail-head time { color: var(--text-4); font-size: var(--fs-caption); }
.notif-detail-panel h3 { font-size: 26px; color: var(--text-1); margin-bottom: 12px; }
.detail-content { white-space: pre-wrap; color: var(--text-2); line-height: 1.75; }
.cancel-card { margin-top: 18px; padding: 16px 18px; border: 1px solid var(--amber-weak); background: var(--amber-faint); border-radius: var(--r-lg); display: grid; gap: 8px; }
.cancel-card h4 { font-family: var(--font-display); color: var(--text-1); font-size: 16px; }
.cancel-card p { color: var(--text-2); font-size: var(--fs-body-sm); line-height: 1.65; }
.detail-actions { display: flex; flex-wrap: wrap; gap: 12px; margin-top: 22px; padding-top: 16px; border-top: 1px solid var(--hairline); align-items: center; }
.action-link { color: var(--primary); font-size: var(--fs-body-sm); text-decoration: none; }
.action-link:hover { text-decoration: underline; }
.row-actions { display: flex; flex-wrap: wrap; gap: 10px; align-items: center; margin-top: 6px; }
@media (max-width: 960px) {
  .notif-workbench { grid-template-columns: 1fr; }
  .notif-list-panel { max-height: none; }
}
</style>
