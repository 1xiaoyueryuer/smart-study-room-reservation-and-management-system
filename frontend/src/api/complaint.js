import request from './request'
export const listMyComplaints = params => request.get('/complaints/mine', {params, silent: true })
export const getComplaint = id => request.get(`/complaints/${id}`, { silent: true })
export const createComplaint = body => request.post('/complaints', body, { silent: true, timeout: 120000 })
export function uploadComplaintAttachment(file) {
  const body = new FormData(); body.append('file', file)
  return request.post('/complaint-attachments', body, { silent: true, timeout: 120000 })
}
export const getComplaintAttachment = id => request.get(`/complaint-attachments/${id}/content`, { responseType: 'blob', silent: true, timeout: 60000 })

export const listAdminComplaints = filters => request.get('/admin/complaints', { params: filters, silent: true })
export const getAdminComplaint = id => request.get(`/admin/complaints/${id}`, { silent: true })
export const acceptComplaint = (id, body) => request.post(`/admin/complaints/${id}/accept`, body, { silent: true, timeout: 120000 })
export const recordComplaintServiceProgress = (id, body) => request.post(`/admin/complaints/${id}/service-progress`, body, { silent: true, timeout: 120000 })
export const recordComplaintVerification = (id, body) => request.post(`/admin/complaints/${id}/verifications`, body, { silent: true, timeout: 120000 })
export const listMyReminders = () => request.get('/reminders/mine', { silent: true })

export const feedbackComplaint = (id, body) => request.post(`/admin/complaints/${id}/feedback`, body, { silent: true, timeout: 120000 })
export const getComplaintPriorityOptions = () => request.get('/complaint-priority-mappings', { silent: true })
export const getAdminComplaintPriorityMappings = () => request.get('/admin/complaint-priority-mappings', { silent: true })
export const replaceAdminComplaintPriorityMappings = body => request.put('/admin/complaint-priority-mappings', body, { silent: true, timeout: 120000 })
export const withdrawComplaint = (id, body) => request.post(`/complaints/${id}/withdraw`, body, { silent: true, timeout: 120000 })
export const confirmComplaintFeedback = (id, body) => request.post(`/complaints/${id}/confirm`, body, { silent: true, timeout: 120000 })
export const objectComplaintFeedback = (id, body) => request.post(`/complaints/${id}/object`, body, { silent: true, timeout: 120000 })
