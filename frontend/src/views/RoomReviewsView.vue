<script setup>
import {ref,onMounted,onBeforeUnmount} from 'vue'
import {getRooms} from '../api/room'
import {listRoomReviews} from '../api/review'
import {validReviewPage,validPublicReview} from '../composables/reviewManagement.js'
import SSelect from '../components/ui/SSelect.vue'
import SButton from '../components/ui/SButton.vue'
import SBanner from '../components/ui/SBanner.vue'
import SEmpty from '../components/ui/SEmpty.vue'
const rooms=ref([]),room=ref(null),snapshot=ref(null),snapshotRoom=ref(null),loading=ref(false),roomLoading=ref(false),error=ref(''),roomError=ref('');let alive=true,ticket=0
onBeforeUnmount(()=>{alive=false;ticket++})
async function loadRooms(){roomLoading.value=true;roomError.value='';try{const {data}=await getRooms();if(!alive)return;if(!Array.isArray(data)||!data.every(r=>Number.isSafeInteger(r.id)&&r.id>0&&typeof r.name==='string'))throw new Error('空间列表不完整');rooms.value=data;if(room.value===null&&data.length){room.value=data[0].id;await load(1)}}catch(e){if(alive)roomError.value=e.message}finally{if(alive)roomLoading.value=false}}
async function load(page=1){const target=Number(room.value);if(!Number.isSafeInteger(target)||target<1){error.value='请先选择真实空间';return}const current=++ticket,params={page,page_size:10};loading.value=true;error.value='';try{const {data}=await listRoomReviews(target,params);if(!alive||current!==ticket)return;if(!validReviewPage(data,params,validPublicReview))throw new Error('空间评价响应不完整，请重试读取');snapshot.value=data;snapshotRoom.value={id:target,name:rooms.value.find(r=>r.id===target)?.name||'空间#'+target}}catch(e){if(alive&&current===ticket)error.value=e.message}finally{if(alive&&current===ticket)loading.value=false}}
async function refreshAll(){await loadRooms();if(room.value)await load(1)}
onMounted(loadRooms)
</script>
<template>
  <div class="page-view room-reviews">
    <header class="view-heading"><h1>空间评价</h1><p>校园登录用户可查看公开评价；仅显示匿名评价内容。请勿在自己的评价中填写个人信息。</p></header>
    <section class="card">
      <div class="card-title-row"><h3>选择空间</h3><SButton size="sm" :loading="roomLoading" :disabled="loading" @click="refreshAll">刷新</SButton></div>
      <form class="review-query-row" @submit.prevent="load(1)">
        <label class="query-field">空间<SSelect v-model="room" :options="rooms.map(r=>({value:r.id,label:r.name}))" :disabled="roomLoading"/></label>
        <SButton type="submit" :disabled="!room||loading">查询空间评价</SButton>
      </form>
      <SBanner v-if="roomError" type="error" :title="roomError"/>
      <SBanner v-if="error" type="error" :title="error"/>
      <p v-if="loading" role="status">正在读取所选空间评价…</p>
      <p v-if="snapshot&&snapshotRoom.id!==Number(room)" class="muted">当前所选空间尚未成功读取，下方为上次结果。</p>
      <section class="result-box">
        <div v-if="snapshot&&snapshot.items.length" class="review-cards">
          <article v-for="item in snapshot.items" :key="item.id">
            <h3>匿名评价 · {{item.rating}}星</h3>
            <p class="content">{{item.content}}</p>
            <p>评价更新：{{item.updated_at}}</p>
            <template v-if="item.admin_reply"><h4>管理回复</h4><p class="content">{{item.admin_reply}}</p><p>回复登记：{{item.replied_at}}</p></template>
          </article>
        </div>
        <SEmpty v-else-if="snapshot" icon="ticket" description="该次查询暂无公开评价" />
        <SEmpty v-else-if="!loading&&!error" icon="ticket" description="请选择空间并查询公开评价" />
      </section>
      <div v-if="snapshot" class="list-footer">
        <span>共 {{ snapshot.total }} 条</span>
        <span class="page-info">第 {{ snapshot.page }} / {{ Math.max(1, Math.ceil(snapshot.total / snapshot.page_size)) }} 页</span>
        <div class="actions">
          <SButton :disabled="loading||snapshot.page<=1||snapshotRoom.id!==Number(room)" @click="load(snapshot.page-1)">上一页</SButton>
          <SButton :disabled="loading||snapshot.page*snapshot.page_size>=snapshot.total||snapshotRoom.id!==Number(room)" @click="load(snapshot.page+1)">下一页</SButton>
        </div>
      </div>
    </section>
  </div>
</template>
<style scoped>
.room-reviews,.room-reviews .card,.room-reviews label,.review-cards{display:grid;gap:16px;min-width:0;overflow-wrap:anywhere}
.card-title-row{margin-bottom:0}
.review-query-row{display:flex;align-items:flex-end;gap:12px;min-width:0}
.review-query-row .query-field{flex:1;min-width:0;display:grid;gap:7px}
.review-query-row :deep(.s-btn){flex:0 0 auto;align-self:flex-end}
.result-box{min-height:240px;padding:20px 22px;border:1px solid var(--border);border-radius:var(--r-lg);background:var(--surface);display:flex;flex-direction:column;justify-content:center}
.result-box .empty-state{margin:auto}
.review-cards article{border-top:1px solid var(--border);padding-top:16px;display:grid;gap:10px}
.review-cards article:first-child{border-top:none;padding-top:0}
.content{white-space:pre-wrap;overflow-wrap:anywhere}
.actions{display:flex;flex-wrap:wrap;gap:12px}
.list-footer{display:grid;grid-template-columns:1fr auto 1fr;align-items:center;gap:12px;margin-top:4px;color:var(--text-3);font-size:var(--fs-caption)}
.page-info{text-align:center;white-space:nowrap}
.list-footer .actions{justify-content:flex-end}
@media(max-width:620px){.review-query-row{flex-direction:column;align-items:stretch}.review-query-row :deep(.s-btn){width:100%}}
</style>
