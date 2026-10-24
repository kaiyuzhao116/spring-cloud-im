// 用 sessionStorage：同一个浏览器开两个标签页可以分别登录两个账号（方便本地双人联调）
const TOKEN_KEY = 'im_access_token'

export function getToken() {
  return sessionStorage.getItem(TOKEN_KEY) || ''
}

export function setToken(token) {
  sessionStorage.setItem(TOKEN_KEY, token)
}

export function clearSession() {
  sessionStorage.removeItem(TOKEN_KEY)
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

  const data = await response.json().catch(() => ({ code: response.status, message: '响应解析失败' }))

  if (response.status === 401 || data.code === 401) {
    clearSession()
    throw new Error(data.message || '登录已失效，请重新登录')
  }
  if (data.code !== 0) {
    // 带上 HTTP 状态码：路由 404 / 下游 503 这类问题一眼能看出来
    throw new Error(data.message || `请求失败（HTTP ${response.status}）`)
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

/**
 * 拉历史消息。
 * @param {string} conv      会话 ID，如 s_1_2
 * @param {number} beforeSeq 取该 seq 之前的；0 表示取最新
 * @param {number} limit     条数（服务端上限 100）
 */
export function fetchMessages(conv, beforeSeq = 0, limit = 30) {
  const qs = new URLSearchParams({
    conv,
    beforeSeq: String(beforeSeq),
    limit: String(limit)
  })
  return request(`/api/im/messages?${qs.toString()}`)
}
