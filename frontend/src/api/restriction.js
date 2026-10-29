import request from './request'
export const listAdminRestrictions = params => request.get('/admin/restrictions', {params,silent:true})
export const listMyRestrictions = () => request.get('/restrictions/mine', {silent:true})
export const getBookingEligibility = () => request.get('/booking-eligibility', {silent:true})
export const createRestriction = body => request.post('/admin/restrictions',body,{silent:true,timeout:120000})
export const releaseRestriction = (id,body) => request.post(`/admin/restrictions/${id}/release`,body,{silent:true,timeout:120000})
