import request from './request'

// 认证相关 API
export function login(data) {
  return request.post('/auth/login', data, { silent: true, skipAuthHandling: true })
}

export function logout(token) {
  return request.post('/auth/logout', null, {
    // 登出后会立即清理 localStorage，因此显式携带本次要注销的令牌。
    headers: { Authorization: `Bearer ${token}` },
    silent: true,
    skipAuthHandling: true
  })
}

export function fetchProfile() {
  return request.get('/auth/profile')
}
