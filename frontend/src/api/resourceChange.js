import request from './request'
export const previewResourceChange = proposal => request.post('/admin/resource-changes/preview', proposal, { silent: true })
export const confirmResourceChange = body => request.post('/admin/resource-changes/confirm', body, { silent: true, timeout: 120000 })
