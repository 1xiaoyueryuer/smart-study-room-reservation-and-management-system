import request from './request'
export const getMyReview=id=>request.get(`/reservations/${id}/review`,{silent:true})
export const createMyReview=(id,body)=>request.post(`/reservations/${id}/review`,body,{silent:true,timeout:120000})
export const updateMyReview=(id,body)=>request.put(`/reservations/${id}/review`,body,{silent:true,timeout:120000})
export const listAdminReviews=params=>request.get('/admin/reviews',{params,silent:true})
export const getAdminReview=id=>request.get(`/admin/reviews/${id}`,{silent:true})
export const replyReview=(id,body)=>request.post(`/admin/reviews/${id}/reply`,body,{silent:true,timeout:120000})
export const hideReview=(id,body)=>request.post(`/admin/reviews/${id}/hide`,body,{silent:true,timeout:120000})
// 规则22补充：隐藏评价被本人修改后的复核；后端接入前页面按 recheck_pending 字段显示。
export const restoreReview=(id,body)=>request.post(`/admin/reviews/${id}/restore`,body,{silent:true,timeout:120000})
export const listRoomReviews=(id,params)=>request.get(`/rooms/${id}/reviews`,{params,silent:true})
