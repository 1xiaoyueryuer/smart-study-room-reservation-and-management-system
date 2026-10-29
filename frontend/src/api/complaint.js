import request from './request'
export const listMyComplaints = () => request.get('/complaints/mine', { silent: true })
export const getComplaint = id => request.get(`/complaints/${id}`, { silent: true })
export const createComplaint = body => request.post('/complaints', body, { silent: true, timeout: 120000 })
export function uploadComplaintAttachment(file) {
  const body = new FormData(); body.append('file', file)
  return request.post('/complaint-attachments', body, { silent: true, timeout: 120000 })
}
export const getComplaintAttachment = id => request.get(`/complaint-attachments/${id}/content`, { responseType: 'blob', silent: true, timeout: 60000 })
