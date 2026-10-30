import {defineStore} from 'pinia'
import {ref,watch} from 'vue'
import {unreadCount} from '../api/notification'
import {useAuthStore} from './auth'

export const useNotificationStore=defineStore('notification',()=>{
 const unread=ref(0),hasValue=ref(false),error=ref(''),loading=ref(false),auth=useAuthStore();let sequence=0
 const identity=()=>auth.isLoggedIn&&auth.isOrdinary?`${auth.user?.id}:${auth.token}`:null
 function reset(){sequence++;unread.value=0;hasValue.value=false;error.value='';loading.value=false}
 watch(()=>[auth.user?.id,auth.token,auth.user?.role],reset)
 function beginUpdate(){loading.value=false;return{identity:identity(),sequence:++sequence}}
 function current(ticket){return ticket?.identity!==null&&ticket?.identity===identity()&&ticket.sequence===sequence}
 function acceptUnread(count,ticket){if(!current(ticket))return false;loading.value=false;if(!Number.isSafeInteger(count)||count<0){error.value='未读数量响应不完整，保留上次成功值';return false}unread.value=count;hasValue.value=true;error.value='';return true}
 async function refresh(){if(identity()===null){reset();return false}const ticket=beginUpdate();loading.value=true;error.value='';try{const {data}=await unreadCount();return acceptUnread(data?.count,ticket)}catch(e){if(current(ticket))error.value=e.message||'未读数量读取失败，保留上次成功值';return false}finally{if(current(ticket))loading.value=false}}
 return{unread,hasValue,error,loading,refresh,beginUpdate,acceptUnread}
})
