export function userPayload(form, creating) {
  const data = Object.fromEntries(['username', 'real_name', 'campus_id', 'phone', 'email', 'reason'].map(key => [key, (form[key] || '').trim()]))
  if (data.username.length < 3 || data.username.length > 50) throw new Error('用户名须为3—50个字符')
  if (!data.real_name || data.real_name.length > 50) throw new Error('请输入不超过50个字符的姓名')
  if ((creating && !data.campus_id) || data.campus_id.length > 20) throw new Error('新账号须填写校园编号，且不超过20个字符')
  data.reason = operationReason(data.reason)
  Object.assign(data, contactPayload(form))
  const password = form.password || ''
  if (creating || password) {
    data.password = validatePassword(password)
  }
  return { ...data, campus_id: data.campus_id || null, role: form.role, status: form.status }
}

export function contactPayload(form) {
  const phone = (form.phone || '').trim()
  const email = (form.email || '').trim()
  if (phone.length > 30) throw new Error('联系电话不超过30个字符')
  if (email.length > 254 || (email && !/^[^\s@]+@[^\s@]+$/.test(email))) throw new Error('请输入有效邮箱地址')
  return { phone, email }
}

export function validatePassword(password) {
  if (password.length < 6 || password.length > 64 || new TextEncoder().encode(password).length > 72) throw new Error('口令须为6—64个字符，UTF-8编码不超过72字节')
  return password
}

export function operationReason(value) {
  const reason = (value || '').trim()
  if (!reason || reason.length > 1000) throw new Error('请填写不超过1000个字符的操作理由')
  return reason
}
