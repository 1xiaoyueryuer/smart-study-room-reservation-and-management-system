import request from './request'
export const getAppealEligibility=id=>request.get(`/violations/${id}/appeal-eligibility`,{silent:true})
export const createAppeal=(id,body)=>request.post(`/violations/${id}/appeals`,body,{silent:true,timeout:120000})
export const listMyAppeals=()=>request.get('/appeals/mine',{silent:true})
export const getAppeal=id=>request.get(`/appeals/${id}`,{silent:true})
export const listAdminAppeals=params=>request.get('/admin/appeals',{params,silent:true})
export const getAdminAppeal=id=>request.get(`/admin/appeals/${id}`,{silent:true})
export function uploadAppealAttachment(file){const body=new FormData();body.append('file',file);return request.post('/appeal-attachments',body,{silent:true,timeout:120000})}
export const getAppealAttachment=id=>request.get(`/appeal-attachments/${id}`,{responseType:'blob',silent:true,timeout:60000})

export const previewAppealReview=(id,body)=>request.post(`/admin/appeals/${id}/review/preview`,body,{silent:true})
export const confirmAppealReview=(id,body)=>request.post(`/admin/appeals/${id}/review/confirm`,body,{silent:true,timeout:120000})
