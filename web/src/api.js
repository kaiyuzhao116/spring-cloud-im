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

/** 注册（成功后直接返回 token，注册即登录） */
export function register(payload) {
  return request('/api/auth/register', { method: 'POST', body: payload, auth: false })
}

/** 好友列表 */
export function fetchFriends() {
  return request('/api/im/friends')
}

// ==================== 后台管理（仅 admin） ====================

/** 用户列表（可按账号/昵称模糊搜索） */
export function fetchAdminUsers(keyword = '') {
  const qs = new URLSearchParams({ keyword, limit: '100' })
  return request(`/api/admin/users?${qs.toString()}`)
}

/** 启用 / 禁用（后台的"删除"用禁用实现） */
export function updateUserStatus(id, status) {
  return request(`/api/admin/users/${id}/status`, { method: 'POST', body: { status } })
}

/** 重置密码 */
export function resetUserPassword(id, password) {
  return request(`/api/auth/admin/users/${id}/password`, { method: 'POST', body: { password } })
}

/** 批量查在线状态 */
export function fetchOnline(uids) {
  return request(`/api/im/online?uids=${uids.join(',')}`)
}

/** 强制下线 */
export function kickUser(uid) {
  return request(`/api/im/admin/kick/${uid}`, { method: 'POST' })
}

/** 搜索用户（按账号/昵称前缀） */
export function searchUsers(keyword) {
  const qs = new URLSearchParams({ keyword })
  return request(`/api/user/search?${qs.toString()}`)
}

/** 发起好友申请 */
export function sendFriendRequest(toUid, message = '') {
  return request('/api/im/friends/requests', { method: 'POST', body: { toUid, message } })
}

/** 我收到的待处理申请 */
export function fetchFriendRequests() {
  return request('/api/im/friends/requests')
}

export function acceptFriendRequest(id) {
  return request(`/api/im/friends/requests/${id}/accept`, { method: 'POST' })
}

export function rejectFriendRequest(id) {
  return request(`/api/im/friends/requests/${id}/reject`, { method: 'POST' })
}

export function logout() {
  return request('/api/auth/logout', { method: 'POST' })
}

export function fetchProfile() {
  return request('/api/user/profile')
}

/** 上报已读位置（只增不减） */
export function markRead(conv, seq) {
  return request('/api/im/read', {
    method: 'POST',
    body: { conv, seq }
  })
}

/** 会话列表（含未读数），按最近消息时间倒序 */
export function fetchConversations(limit = 50) {
  const qs = new URLSearchParams({ limit: String(limit) })
  return request(`/api/im/conversations?${qs.toString()}`)
}

/**
 * 拉消息。
 * @param {string} conv 会话 ID，如 s_1_2
 * @param {object} opts
 * @param {number} opts.beforeSeq 取该 seq 之前的（翻历史）；0 表示取最新
 * @param {number} opts.afterSeq  取该 seq 之后的（重连补缺口）；0 表示不补
 * @param {number} opts.limit     条数（服务端上限 100）
 */
export function fetchMessages(conv, { beforeSeq = 0, afterSeq = 0, limit = 30 } = {}) {
  const qs = new URLSearchParams({
    conv,
    beforeSeq: String(beforeSeq),
    afterSeq: String(afterSeq),
    limit: String(limit)
  })
  return request(`/api/im/messages?${qs.toString()}`)
}
