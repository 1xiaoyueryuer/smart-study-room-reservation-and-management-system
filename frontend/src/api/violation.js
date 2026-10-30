import request from './request'

export function listMyViolations(params) {
  return request.get('/violations/mine', {params, silent: true })
}

export function registerEventViolation(eventId, body) {
  return request.post(`/admin/complaint-events/${eventId}/violations`, body, { silent: true, timeout: 120000 })
}

export const getMyViolation=id=>request.get(`/violations/${id}`,{silent:true})
export const listAdminViolations=params=>request.get('/admin/violations',{params,silent:true})
