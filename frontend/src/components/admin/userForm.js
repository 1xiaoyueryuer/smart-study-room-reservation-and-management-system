export function userPayload(form, creating, originalIdentity = form.original_identity || null) {
  const data = Object.fromEntries(['username', 'real_name', 'campus_id', 'phone', 'email', 'reason'].map(key => [key, (form[key] || '').trim()]))
  if (!data.real_name || data.real_name.length > 50) throw new Error('请输入不超过50个字符的姓名')
  const campusIdentityRequired = form.role === 'student' || form.role === 'staff'
  if (creating && campusIdentityRequired && !data.campus_id) throw new Error('新学生/教职工账号须填写校园编号')
  if (creating && form.role === 'admin' && !data.username) throw new Error('管理员账号须填写登录名')
  const sameLegacyIdentity = !creating && originalIdentity
    && data.campus_id === originalIdentity.campus_id
    && form.role === originalIdentity.role
    && data.username === originalIdentity.username
  if (campusIdentityRequired && data.campus_id && !sameLegacyIdentity) {
    const error = campusIdentityError(data.campus_id, form.role)
    if (error) throw new Error(error)
    data.username = data.campus_id
  }
  if (campusIdentityRequired && !sameLegacyIdentity && !data.campus_id) throw new Error('新学生/教职工账号须填写校园编号')
  if (form.role === 'admin' && data.campus_id.length > 20) throw new Error('管理员校园编号不超过20个字符')
  if (data.username.length < 3 || data.username.length > 50) throw new Error('登录名须为3—50个字符')
  if (data.campus_id && sameLegacyIdentity) data.username = originalIdentity.username
  data.reason = operationReason(data.reason)
  Object.assign(data, contactPayload(form))
  const password = form.password || ''
  if (creating || password) {
    data.password = validatePassword(password)
  }
  return { ...data, campus_id: data.campus_id || null, role: form.role, status: form.status }
}

export function campusIdentityError(campusId, role, currentYear = Number(new Intl.DateTimeFormat('en', { timeZone: 'Asia/Shanghai', year: 'numeric' }).format(new Date()))) {
  if (role === 'admin' && !campusId) return null
  if (!/^[0-9]{4}[0-3][0-9]{4}$/.test(campusId)) return '校园编号须为9位：4位入学年份+1位身份类别+4位序号'
  const year = Number(campusId.slice(0, 4))
  if (year < 2000 || year > currentYear + 1) return '校园编号入学年份不合理'
  const category = campusId[4]
  if (role === 'student' && ['0', '1', '2'].includes(category)) return ''
  if (role === 'staff' && category === '3') return ''
  if (role === 'admin' && category === '3') return ''
  return '学生第5位须为0/1/2，教职工第5位须为3'
}

export function contactPayload(form) {
  const phone = (form.phone || '').trim()
  const email = (form.email || '').trim()
  if (phone.length > 30) throw new Error('联系电话不超过30个字符')
  if (email.length > 254 || (email && !/^[^\s@]+@[^\s@]+$/.test(email))) throw new Error('请输入有效邮箱地址')
  return { phone, email }
}

export function validatePassword(password) {
  if (password.length < 6 || password.length > 64 || new TextEncoder().encode(password).length > 72) throw new Error('密码须为6—64个字符，UTF-8编码不超过72字节')
  return password
}

export function operationReason(value) {
  const reason = (value || '').trim()
  if (!reason || reason.length > 1000) throw new Error('请填写不超过1000个字符的操作理由')
  return reason
}
