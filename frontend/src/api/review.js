import request from './request'
export const getMyReview=id=>request.get(`/reservations/${id}/review`,{silent:true})
export const createMyReview=(id,body)=>request.post(`/reservations/${id}/review`,body,{silent:true,timeout:120000})
export const updateMyReview=(id,body)=>request.put(`/reservations/${id}/review`,body,{silent:true,timeout:120000})
