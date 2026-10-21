const TOKEN_KEY = 'im_access_token'
const SID_KEY = 'im_session_id'

export function getToken() {
  return localStorage.getItem(TOKEN_KEY) || ''
}

export function setSession(token, sessionId) {
  localStorage.setItem(TOKEN_KEY, token)
  if (sessionId) {
    localStorage.setItem(SID_KEY, sessionId)
  }
}

export function clearSession() {
  localStorage.removeItem(TOKEN_KEY)
  localStorage.removeItem(SID_KEY)
}

export function getSessionId() {
  return localStorage.getItem(SID_KEY) || ''
}

async function request(url, { method = 'GET', body, auth = true } = {}) {
  const headers = { 'Content-Type': 'application/json' }
  if (auth) {
    const token = getToken()
    if (token) {
      headers.Authorization = `Bearer ${token}`
    }
  }

  const response = await fetch(url, {
    method,
    headers,
    body: body ? JSON.stringify(body) : undefined
  })

  // 网关在未登录/凭证失效时直接返回 401 JSON，没有 code 字段包装差异
  const data = await response.json().catch(() => ({ code: response.status, message: '响应解析失败' }))

  if (response.status === 401 || data.code === 401) {
    clearSession()
    throw new Error(data.message || '登录已失效，请重新登录')
  }
  if (data.code !== 0) {
    throw new Error(data.message || '请求失败')
  }
  return data.data
}

export function login(payload) {
  return request('/api/auth/login', { method: 'POST', body: payload, auth: false })
}

export function logout() {
  return request('/api/auth/logout', { method: 'POST' })
}

export function fetchProfile() {
  return request('/api/user/profile')
}
