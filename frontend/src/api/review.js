import request from './request'
export const getMyReview=id=>request.get(`/reservations/${id}/review`,{silent:true})
export const createMyReview=(id,body)=>request.post(`/reservations/${id}/review`,body,{silent:true,timeout:120000})
export const updateMyReview=(id,body)=>request.put(`/reservations/${id}/review`,body,{silent:true,timeout:120000})
export const listAdminReviews=params=>request.get('/admin/reviews',{params,silent:true})
export const getAdminReview=id=>request.get(`/admin/reviews/${id}`,{silent:true})
export const replyReview=(id,body)=>request.post(`/admin/reviews/${id}/reply`,body,{silent:true,timeout:120000})
export const hideReview=(id,body)=>request.post(`/admin/reviews/${id}/hide`,body,{silent:true,timeout:120000})
export const listRoomReviews=(id,params)=>request.get(`/rooms/${id}/reviews`,{params,silent:true})
