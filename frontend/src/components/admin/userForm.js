export function userPayload(form, creating) {
  const data = Object.fromEntries(['username', 'real_name', 'campus_id', 'phone', 'email', 'reason'].map(key => [key, (form[key] || '').trim()]))
  if (!data.real_name || data.real_name.length > 50) throw new Error('请输入不超过50个字符的姓名')
  if (creating) data.username = data.campus_id
  if (data.username.length < 3 || data.username.length > 50) throw new Error('登录名须为3—50个字符')
  if (creating && !data.campus_id) throw new Error('新账号须填写学号')
  if (data.campus_id) {
    if (!/^20\d{2}[0-3]\d{4}$/.test(data.campus_id)) throw new Error('学号须为9位：4位入学年份+1位身份+4位序号')
    const digit = data.campus_id[4]
    const year = Number(data.campus_id.slice(0, 4))
    if (year < 2000 || year > new Date().getFullYear() + 1) throw new Error('学号入学年份不合理')
    if (form.role === 'student') {
      if (!['0', '1', '2'].includes(digit)) throw new Error('学生学号第5位须为0（本科）、1（研究生）或2（博士）')
    } else if (digit !== '3') {
      throw new Error('教职工和管理员学号第5位须为3')
    }
  }
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
  if (password.length < 6 || password.length > 64 || new TextEncoder().encode(password).length > 72) throw new Error('密码须为6—64个字符，UTF-8编码不超过72字节')
  return password
}

export function operationReason(value) {
  const reason = (value || '').trim()
  if (!reason || reason.length > 1000) throw new Error('请填写不超过1000个字符的操作理由')
  return reason
}
