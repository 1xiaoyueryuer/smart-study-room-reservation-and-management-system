import request from './request'
export const listAuditEvents=params=>request.get('/admin/audit-events',{params,silent:true})
export const getAuditEvent=id=>request.get(`/admin/audit-events/${id}`,{silent:true})
