import request from './request'
export const listNotifications=params=>request.get('/notifications',{params,silent:true})
export const unreadCount=()=>request.get('/notifications/unread_count',{silent:true})
export const markRead=id=>request.post(`/notifications/${id}/read`,null,{silent:true})
export const markAllRead=()=>request.post('/notifications/read_all',null,{silent:true})
// 规则21管理取消回执；后端接入前页面只在响应带 can_ack=true 时调用。
export const ackManagementCancellation=(id,body)=>request.post(`/notifications/${id}/ack`,body,{silent:true})
