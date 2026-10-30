import request from './request'
export const listNotifications=params=>request.get('/notifications',{params,silent:true})
export const unreadCount=()=>request.get('/notifications/unread_count',{silent:true})
export const markRead=id=>request.post(`/notifications/${id}/read`,null,{silent:true})
export const markAllRead=()=>request.post('/notifications/read_all',null,{silent:true})
