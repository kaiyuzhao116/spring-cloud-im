<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import {
  acceptFriendRequest,
  clearSession,
  fetchAdminUsers,
  fetchOnline,
  kickUser,
  resetUserPassword,
  updateUserStatus,
  fetchConversations,
  fetchFriendRequests,
  fetchFriends,
  fetchMessages,
  fetchProfile,
  getToken,
  login,
  logout,
  markRead,
  register,
  rejectFriendRequest,
  searchUsers,
  sendFriendRequest,
  setToken
} from './api'
import { ChatSocket } from './ws'

/** login | register */
const mode = ref('login')
const account = ref('admin')
const password = ref('123456')
const nickname = ref('')
const loading = ref(false)
const error = ref('')

const profile = ref(null)
const peer = ref({ id: 2, name: '小红' })
const messages = ref([])
const draft = ref('')
const status = ref('idle')
const listEl = ref(null)

/** 会话列表（含未读数） */
const conversations = ref([])

/** 好友列表（来自 im_friend，点一下即可开始聊天） */
const friends = ref([])

/** 左上角"更多"菜单 */
const showMenu = ref(false)

/** 联系人搜索关键词（纯前端过滤） */
const contactKeyword = ref('')

/** 收到的好友申请 */
const friendRequests = ref([])

/** 加好友弹窗 */
const showAddFriend = ref(false)
const keyword = ref('')
const searchResults = ref([])
const searching = ref(false)

/** 不在底部时收到的新消息条数（点按钮才跳） */
const unreadNew = ref(0)

/** 后台管理面板 */
const showAdmin = ref(false)
const adminUsers = ref([])
const adminKeyword = ref('')
const adminLoading = ref(false)

/** 好友在线状态的定时刷新 */
let presenceTimer = null

/** 轻提示（好友申请等），几秒后自动消失 */
const notice = ref('')
let noticeTimer = null

function showNotice(text) {
  notice.value = text
  if (noticeTimer) clearTimeout(noticeTimer)
  noticeTimer = setTimeout(() => {
    notice.value = ''
  }, 5000)
}

/** 标签页标题显示未读总数，切到别的页面也能看到 */
function updateTitle() {
  const total = conversations.value.reduce((sum, c) => sum + (c.unread || 0), 0)
  document.title = total > 0 ? `(${total > 99 ? '99+' : total}) IM` : 'IM'
}

function nameOf(uid) {
  const conv = conversations.value.find((c) => c.peerUid === uid)
  if (conv) return conv.name
  const friend = friends.value.find((f) => f.uid === uid)
  if (friend) return friend.nickname || nicknameOf(uid)
  return nicknameOf(uid)
}

/** 页面在后台且有权限时，弹一条系统通知 */
function notifyDesktop(uid, content) {
  if (!('Notification' in window) || Notification.permission !== 'granted') return
  if (!document.hidden) return
  try {
    new Notification(nameOf(uid), { body: content, tag: 'im-message' })
  } catch (e) {
    // 某些浏览器（如未授权的 PWA 环境）会抛，忽略即可
  }
}

/** 登录后请求一次通知权限（用户拒绝就不再打扰） */
function askNotificationPermission() {
  if (!('Notification' in window)) return
  if (Notification.permission === 'default') {
    Notification.requestPermission().catch(() => {})
  }
}

/** 每个会话已上报过的最大 seq，避免重复请求 */
const readSent = {}

/** 更早的消息还有没有（后端返回的 hasMore） */
const hasMore = ref(false)
/** 防止滚动时重复触发加载 */
const loadingMore = ref(false)
/** 内存里最多保留多少条，超出丢弃最老的（防止 DOM 无限增长） */
const MAX_KEEP = 300

let socket = null
/** 是否成功连接过：用来区分"首次连接"和"重连"（只有重连才补缺口） */
let everConnected = false

const statusText = {
  idle: '未连接',
  connecting: '连接中…',
  connected: '已连接',
  closed: '已断开',
  error: '连接失败',
  kicked: '已在其他设备登录',
  'too-big': '消息过大，已被断开',
  'token-expired': '登录已过期'
}

// 服务端主动断开时给用户的明确提示（不再自动重连，需要用户操作）
const statusError = {
  kicked: '你的账号已在其他设备登录，当前连接已断开',
  'too-big': '发送的内容超过 4KB，连接已被服务端断开，请刷新页面重连',
  'token-expired': '登录已过期，请重新登录'
}

/**
 * 没有任何会话/好友时的对端。
 *
 * <p>以前这里写死"admin↔小红"，导致新库（好友表为空）进去会看到一个
 * 根本不存在的联系人，发出去的消息又被服务端以 403 拒绝。</p>
 *
 * <p>现在返回 id=0 的空对端，界面显示"从左侧选择联系人"，输入框禁用。</p>
 */
function resolvePeer() {
  return { id: 0, name: '' }
}

/** 昵称映射（后端暂未返回 peerName，先按 uid 映射；接入 user 服务后可删） */
function nicknameOf(uid) {
  if (uid === 1) return '管理员'
  if (uid === 2) return '小红'
  return `用户${uid}`
}

async function loadConversations() {
  try {
    const list = await fetchConversations(50)
    conversations.value = list.map((c) => ({
      ...c,
      name: c.peerName || nicknameOf(c.peerUid)
    }))
  } catch (e) {
    console.warn('会话列表加载失败:', e.message)
  }
}

/**
 * 左侧统一联系人列表 = 好友为底 + 会话补预览/未读。
 *
 * <p>之前"消息"和"好友"两个列表会让同一个人出现两次，用户不知道该点哪个；
 * 合成一个之后：有聊天记录显示预览和时间，没有就显示"还没有聊过"。</p>
 */
const contacts = computed(() => {
  const convMap = new Map(conversations.value.map((c) => [c.peerUid, c]))
  const friendMap = new Map(friends.value.map((f) => [f.uid, f]))
  const uids = new Set([...friendMap.keys(), ...convMap.keys()])
  const keyword = contactKeyword.value.trim().toLowerCase()

  return [...uids]
    .map((uid) => {
      const conv = convMap.get(uid)
      const friend = friendMap.get(uid)
      return {
        uid,
        name: (friend && friend.nickname) || (conv && conv.name) || nicknameOf(uid),
        online: !!(friend && friend.online),
        preview: conv ? conv.preview || '' : '',
        unread: conv ? conv.unread || 0 : 0,
        lastMsgAt: conv ? conv.lastMsgAt : null,
        hasChat: !!conv && !!conv.preview
      }
    })
    .filter((c) => {
      if (!keyword) return true
      return c.name.toLowerCase().includes(keyword) || String(c.uid) === keyword
    })
    .sort((a, b) => {
      // 先按"急迫程度"分层：有未读 > 聊过 > 没聊过
      const tier = (x) => (x.unread > 0 ? 0 : x.hasChat ? 1 : 2)
      if (tier(a) !== tier(b)) return tier(a) - tier(b)

      // 同层内：聊过的按最近消息时间倒序，没聊过的按名字
      if (tier(a) === 2) return a.name.localeCompare(b.name, 'zh-Hans-CN')

      const ta = a.lastMsgAt || 0
      const tb = b.lastMsgAt || 0
      if (ta !== tb) return tb - ta
      return a.uid - b.uid
    })
})

/** 是否选中了一个真实联系人（id=0 表示还没选） */
const hasPeer = computed(() => peer.value && peer.value.id > 0)

// 联系人列表从空变有时（比如刚注册完、或别人加了你），自动打开第一个
watch(contacts, (list) => {
  if (!hasPeer.value && list.length > 0) {
    selectContact(list[0])
  }
})

/** 会话列表里的时间：今天显示 HH:mm，昨天显示"昨天"，更早显示 M/D */
function formatListTime(ts) {
  if (!ts) return ''
  const d = new Date(ts)
  const today = new Date()
  if (d.toDateString() === today.toDateString()) return formatTime(ts)

  const yesterday = new Date(today)
  yesterday.setDate(today.getDate() - 1)
  if (d.toDateString() === yesterday.toDateString()) return '昨天'

  return `${d.getMonth() + 1}/${d.getDate()}`
}

/** 头像底色按 uid 取，避免所有人都是同一种蓝 */
const AVATAR_COLORS = ['#5b8def', '#7c6cf0', '#e879a6', '#f59e0b', '#10b981', '#06b6d4']

function avatarColor(uid) {
  return AVATAR_COLORS[(uid || 0) % AVATAR_COLORS.length]
}

/** 点联系人：清未读 → 切换会话 → 拉历史 */
async function selectContact(item) {
  const conv = conversations.value.find((c) => c.peerUid === item.uid)
  if (conv) conv.unread = 0

  if (item.uid === peer.value.id) return

  peer.value = { id: item.uid, name: item.name }
  messages.value = []
  hasMore.value = false
  await loadHistory()
}

async function loadFriends() {
  try {
    friends.value = await fetchFriends()
  } catch (e) {
    console.warn('好友列表加载失败:', e.message)
  }
}

async function loadFriendRequests() {
  try {
    friendRequests.value = await fetchFriendRequests()
  } catch (e) {
    console.warn('好友申请加载失败:', e.message)
  }
}

/** 搜索用户（账号/昵称前缀） */
async function doSearch() {
  const kw = keyword.value.trim()
  if (!kw) {
    searchResults.value = []
    return
  }
  searching.value = true
  try {
    searchResults.value = (await searchUsers(kw)).map((u) => ({ ...u, added: false }))
  } catch (e) {
    error.value = e.message
  } finally {
    searching.value = false
  }
}

async function addFriend(u) {
  try {
    await sendFriendRequest(u.id, `我是${profile.value.nickname || profile.value.account}`)
    u.added = true
  } catch (e) {
    error.value = e.message
  }
}

async function acceptRequest(r) {
  try {
    await acceptFriendRequest(r.id)
    await Promise.all([loadFriendRequests(), loadFriends()])
  } catch (e) {
    error.value = e.message
  }
}

async function rejectRequest(r) {
  try {
    await rejectFriendRequest(r.id)
    await loadFriendRequests()
  } catch (e) {
    error.value = e.message
  }
}

function openAddFriend() {
  showAddFriend.value = true
  keyword.value = ''
  searchResults.value = []
}

// ==================== 后台管理 ====================

function isAdmin() {
  return profile.value && profile.value.role === 'admin'
}

async function openAdmin() {
  if (!isAdmin()) return
  showAdmin.value = true
  await loadAdminUsers()
}

async function loadAdminUsers() {
  adminLoading.value = true
  try {
    const users = await fetchAdminUsers(adminKeyword.value.trim())
    const online = users.length > 0 ? await fetchOnline(users.map((u) => u.id)) : []
    const onlineMap = new Map(online.map((o) => [o.uid, o.online]))
    adminUsers.value = users.map((u) => ({ ...u, online: onlineMap.get(u.id) === true }))
  } catch (e) {
    error.value = e.message
  } finally {
    adminLoading.value = false
  }
}

/** 启用 / 禁用（后台的"删除"用禁用实现，避免历史消息出现孤儿 uid） */
async function toggleUserStatus(u) {
  const next = u.status === 1 ? 0 : 1
  if (next === 0 && !window.confirm(`确定禁用「${u.nickname}」？禁用后无法登录`)) {
    return
  }
  try {
    await updateUserStatus(u.id, next)
    u.status = next
  } catch (e) {
    error.value = e.message
  }
}

async function kickOffline(u) {
  if (!window.confirm(`确定把「${u.nickname}」踢下线？`)) {
    return
  }
  try {
    await kickUser(u.id)
    u.online = false
    showNotice(`「${u.nickname}」已被强制下线`)
  } catch (e) {
    error.value = e.message
  }
}

async function resetPasswordOf(u) {
  const pwd = window.prompt(`给「${u.nickname}」设置新密码（8~20 位，含字母和数字）`)
  if (!pwd) return
  try {
    await resetUserPassword(u.id, pwd)
    showNotice(`已重置「${u.nickname}」的密码`)
  } catch (e) {
    error.value = e.message
  }
}

/** 连接建立/重连时刷新一次好友与申请（实时通知之外的兜底） */
function refreshSocial() {
  loadFriends()
  loadFriendRequests()
}



function scrollToBottom() {
  nextTick(() => {
    if (listEl.value) {
      listEl.value.scrollTop = listEl.value.scrollHeight
      unreadNew.value = 0
    }
  })
}

/** 是否已经贴着底部（决定要不要自动滚动） */
function atBottom() {
  const el = listEl.value
  if (!el) return true
  return el.scrollHeight - el.scrollTop - el.clientHeight < 60
}

function jumpToBottom() {
  scrollToBottom()
}

/**
 * 追加一条消息，并裁掉超出上限的最老消息。
 *
 * <p>{@code force=true}（自己发的、系统提示）一定滚到底；
 * 别人发来的消息只有当你本来就在底部时才滚，否则计数等你点。</p>
 */
function pushMessage(item, force = false) {
  messages.value.push(item)
  if (messages.value.length > MAX_KEEP) {
    messages.value.splice(0, messages.value.length - MAX_KEEP)
  }

  if (force || atBottom()) {
    scrollToBottom()
  } else {
    unreadNew.value += 1
  }
}

// ==================== 时间显示 ====================

const TIME_GAP_MS = 5 * 60 * 1000

function formatTime(ts) {
  const d = new Date(ts)
  return `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
}

function formatDay(ts) {
  const d = new Date(ts)
  const today = new Date()
  if (d.toDateString() === today.toDateString()) return '今天'

  const yesterday = new Date(today)
  yesterday.setDate(today.getDate() - 1)
  if (d.toDateString() === yesterday.toDateString()) return '昨天'

  return `${d.getMonth() + 1}月${d.getDate()}日`
}

/** 与上一条间隔超过 5 分钟、或跨天、或是第一条时，才显示时间 */
function needTimeDivider(index) {
  const current = messages.value[index]
  if (!current || !current.ts) return false
  if (index === 0) return true

  const prev = messages.value[index - 1]
  if (!prev || !prev.ts) return true
  return current.ts - prev.ts > TIME_GAP_MS
}

/** 2 分钟内、自己发的、已成功的消息才能撤回 */
const RECALL_WINDOW_MS = 2 * 60 * 1000

function canRecall(msg) {
  return !!(msg.mine && msg.status === 'sent' && msg.seq && !msg.recalled
    && Date.now() - (msg.ts || 0) < RECALL_WINDOW_MS)
}

function recall(msg) {
  if (!canRecall(msg) || !socket) return
  socket.sendFrame({
    type: 'RECALL',
    cid: `r-${Date.now()}`,
    to: msg.to || peer.value.id,
    seq: msg.seq
  })
}

/** 点击失败的红色气泡：用同一个 cid 重发（服务端幂等，不会重复落库） */
function resend(msg) {
  if (msg.status !== 'failed' || !socket || !msg.to) return

  const ok = socket.sendFrame({ type: 'MSG', cid: msg.key, to: msg.to, content: msg.content })
  if (ok) {
    msg.status = 'sending'
    msg.failReason = ''
  }
}

function handleFrame(frame) {
  // 服务端主动通知：好友申请、对方同意
  if (frame.type === 'NOTIFY') {
    if (frame.event === 'friend_request') {
      loadFriendRequests()
      showNotice(`${frame.content || '有人'} 请求添加你为好友`)
    } else if (frame.event === 'friend_accepted') {
      loadFriendRequests()
      loadFriends()
      showNotice(`${frame.content || '对方'} 同意了你的好友申请`)
    } else if (frame.event === 'message_recalled') {
      // 对方撤回：content 里放的是 seq
      const seq = Number(frame.content)
      const target = messages.value.find((m) => m.seq === seq)
      if (target) {
        target.recalled = true
        target.content = ''
      }
      const conv = conversations.value.find((c) => c.peerUid === frame.from)
      if (conv) conv.preview = '[消息已撤回]'
    }
    return
  }

  if (frame.type === 'MSG') {
    // from=0 是系统消息（欢迎、通知…），居中展示，不属于任何会话
    if (frame.from === 0) {
      pushMessage(
        {
          key: `sys-${Date.now()}-${messages.value.length}`,
          system: true,
          content: frame.content,
          ts: Date.now()
        },
        true
      )
      return
    }

    // 同一条消息可能既在历史里、又被实时推过来，按 seq 去重
    if (messages.value.some((m) => m.seq === frame.seq && !m.mine)) {
      return
    }

    pushMessage({
      key: `peer-${frame.seq}`,
      mine: false,
      content: frame.content,
      seq: frame.seq,
      ts: Date.now(),
      from: frame.from,
      status: 'received'
    })

    // 同步更新左侧会话卡片，并把它置顶（最近有消息的排最前）
    const conv = conversations.value.find((c) => c.peerUid === frame.from)
    if (conv) {
      conv.preview = frame.content
      conv.lastSeq = frame.seq
      conv.lastMsgAt = Date.now()   // 用于左侧按最近消息排序
      if (conv.peerUid === peer.value.id) {
        // 正在看这个会话：直接算已读
        conv.unread = 0
        reportRead(conversationId(), frame.seq)
      } else {
        conv.unread = (conv.unread || 0) + 1
      }

      const idx = conversations.value.indexOf(conv)
      if (idx > 0) {
        conversations.value.splice(idx, 1)
        conversations.value.unshift(conv)
      }
    } else {
      loadConversations()   // 新会话（对方第一次发消息给我）
    }

    // 不在这个会话里才值得提醒；正在看的会话已经算已读
    if (frame.from !== peer.value.id) {
      notifyDesktop(frame.from, frame.content)
    }
    updateTitle()
    return
  }
  if (frame.type === 'ACK') {
    // 撤回回执：cid 以 r- 开头，成功后把本地那条也标记为已撤回
    if (frame.cid && frame.cid.startsWith('r-')) {
      if (!frame.seq) {
        error.value = frame.reason || '撤回失败'
        return
      }
      const recalled = messages.value.find((m) => m.seq === frame.seq)
      if (recalled) {
        recalled.recalled = true
        recalled.content = ''
      }
      const conv = conversations.value.find((c) => c.peerUid === peer.value.id)
      if (conv) conv.preview = '[消息已撤回]'
      return
    }

    const target = messages.value.find((m) => m.key === frame.cid)
    if (!target) return
    if (!frame.seq) {
      // seq=0 表示服务端没存成：转成"发送失败"，并把服务端给的原因带出来
      target.status = 'failed'
      target.failReason = frame.reason || ''
      return
    }
    target.seq = frame.seq
    target.status = 'sent'
  }
}

function connect() {
  if (socket) socket.close()
  socket = new ChatSocket({
    token: getToken(),
    onFrame: handleFrame,
    onStatus: (s) => {
      status.value = s
      if (statusError[s]) {
        // 被踢 / 消息过大 / 过期：显示明确原因，且不会自动重连
        error.value = statusError[s]
      } else if (s === 'connected') {
        error.value = ''
        // 首次连接靠 loadHistory 灌数据；之后的重连才需要补缺口
        if (everConnected) {
          catchUp()
        }
        everConnected = true
        refreshSocial()   // 断线期间可能错过好友通知，连上后补一次
      }
    }
  })
  socket.connect()
}

/** 会话 ID：s_{小uid}_{大uid}，和后端 ConversationId.single 规则一致 */
function conversationId() {
  if (!hasPeer.value) return ''
  const a = profile.value.id
  const b = peer.value.id
  return `s_${Math.min(a, b)}_${Math.max(a, b)}`
}

/** 拉历史消息；失败不打断聊天（只是看不到旧记录） */
async function loadHistory() {
  if (!hasPeer.value) {
    messages.value = []
    hasMore.value = false
    return
  }
  try {
    const data = await fetchMessages(conversationId(), { limit: 30 })
    messages.value = data.messages.map((m) => ({
      key: `h-${m.seq}`,
      mine: m.from === profile.value.id,
      content: m.content,
      seq: m.seq,
      ts: m.ts,
      from: m.from,
      recalled: m.recalled,
      status: 'sent'
    }))
    hasMore.value = data.hasMore
    scrollToBottom()
    reportRead(conversationId(), lastSeqValue())   // 打开会话即视为已读
  } catch (e) {
    console.warn('历史消息加载失败:', e.message)
  }
}

/** 上滑到顶部时加载更早的消息；插入后补偿滚动高度，保证视口不跳 */
async function loadMore() {
  if (!hasMore.value || loadingMore.value) return
  const oldest = messages.value.find((m) => m.seq)
  if (!oldest) return

  loadingMore.value = true
  const el = listEl.value
  const prevHeight = el ? el.scrollHeight : 0

  try {
    const data = await fetchMessages(conversationId(), { beforeSeq: oldest.seq, limit: 20 })
    const existing = new Set(messages.value.filter((m) => m.seq).map((m) => m.seq))
    const older = data.messages
      .filter((m) => !existing.has(m.seq))
      .map((m) => ({
        key: `h-${m.seq}`,
        mine: m.from === profile.value.id,
        content: m.content,
        seq: m.seq,
        ts: m.ts,
        from: m.from,
        recalled: m.recalled,
        status: 'sent'
      }))

    messages.value.unshift(...older)
    hasMore.value = data.hasMore

    await nextTick()
    if (el) {
      // 视口补偿：保持用户当前看到的内容不动
      el.scrollTop += el.scrollHeight - prevHeight
    }
  } catch (e) {
    console.warn('加载更多失败:', e.message)
  } finally {
    loadingMore.value = false
  }
}

function handleScroll() {
  if (listEl.value && listEl.value.scrollTop < 40) {
    loadMore()
  }
}

/** 本地已知的最大 seq，重连补缺口时作为下界 */
function lastSeqValue() {
  return messages.value.reduce((max, m) => Math.max(max, m.seq || 0), 0)
}

/**
 * 上报已读：把"我看到第几条"告诉服务端。
 *
 * <p>未读数是 last_seq - last_read_seq 算出来的，推进它就能把红点消掉；
 * 服务端只增不减，所以重复上报、乱序上报都不会出错。</p>
 */
async function reportRead(convId, seq) {
  if (!convId || !seq) return
  if ((readSent[convId] || 0) >= seq) return   // 同一个位置只报一次
  readSent[convId] = seq

  const item = conversations.value.find((c) => c.conversationId === convId)
  if (item) item.unread = 0

  try {
    await markRead(convId, seq)
  } catch (e) {
    console.warn('已读上报失败:', e.message)
  }
}

/**
 * 重连后补缺口：只拉"我最后看到的 seq"之后的消息。
 *
 * <p>这是"离线消息"的正解——不靠推送补发，靠客户端上报进度 + 服务端按 seq 补。</p>
 */
async function catchUp() {
  const from = lastSeqValue()
  if (!from) return
  try {
    const data = await fetchMessages(conversationId(), { afterSeq: from, limit: 100 })
    const existing = new Set(messages.value.filter((m) => m.seq).map((m) => m.seq))
    const missed = data.messages.filter((m) => !existing.has(m.seq))

    if (missed.length === 0) return

    missed.forEach((m) => {
      pushMessage({
        key: `h-${m.seq}`,
        mine: m.from === profile.value.id,
        content: m.content,
        seq: m.seq,
        ts: m.ts,
        from: m.from,
        status: 'sent'
      })
    })
    scrollToBottom()
    console.info(`重连补到 ${missed.length} 条漏掉的消息`)
  } catch (e) {
    console.warn('重连补消息失败:', e.message)
  }
}

async function loadProfile() {
  try {
    profile.value = await fetchProfile()
    await Promise.all([loadConversations(), loadFriends(), loadFriendRequests()])
    const first = conversations.value[0]
    const firstFriend = friends.value[0]
    if (first) {
      peer.value = { id: first.peerUid, name: first.name }
    } else if (firstFriend) {
      peer.value = { id: firstFriend.uid, name: firstFriend.nickname || nicknameOf(firstFriend.uid) }
    } else {
      peer.value = resolvePeer(profile.value)
    }
    await loadHistory()      // 先灌历史，再建连，避免实时消息被历史覆盖
    updateTitle()
    askNotificationPermission()
    connect()
    // 在线状态会过期，60 秒刷新一次好友列表（数据量小，成本可忽略）
    presenceTimer = setInterval(loadFriends, 60000)
  } catch (e) {
    error.value = e.message
    clearSession()
  }
}

async function handleLogin() {
  error.value = ''
  if (!account.value.trim() || !password.value) {
    error.value = '请输入账号和密码'
    return
  }
  loading.value = true
  try {
    const data = await login({
      account: account.value.trim(),
      password: password.value,
      deviceType: 'WEB'
    })
    setToken(data.accessToken)
    messages.value = []
    await loadProfile()
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}

/** 注册：成功后后端直接给 token，等于顺便登录 */
async function handleRegister() {
  error.value = ''
  if (!account.value.trim() || !password.value) {
    error.value = '请输入账号和密码'
    return
  }
  loading.value = true
  try {
    const data = await register({
      account: account.value.trim(),
      password: password.value,
      nickname: nickname.value.trim() || undefined,
      deviceType: 'WEB'
    })
    setToken(data.accessToken)
    messages.value = []
    mode.value = 'login'
    await loadProfile()
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}

function handleSend() {
  const text = draft.value.trim()
  if (!text) return
  if (!hasPeer.value) {
    error.value = '请先在左侧选择一位联系人'
    return
  }
  if (status.value !== 'connected') {
    // 保留"被踢/过期/过大"这类具体原因，不要被通用提示覆盖
    error.value = statusError[status.value] || '连接未建立，稍后再试'
    return
  }
  const cid = socket.sendMessage(peer.value.id, text)
  if (!cid) {
    error.value = '发送失败，连接已断开'
    return
  }
  pushMessage(
    {
      key: cid,
      mine: true,
      content: text,
      seq: null,
      ts: Date.now(),
      to: peer.value.id,
      status: 'sending'
    },
    true
  )
  draft.value = ''
  error.value = ''

  // 左侧会话卡片的预览同步更新
  const conv = conversations.value.find((c) => c.peerUid === peer.value.id)
  if (conv) {
    conv.preview = text
    conv.lastMsgAt = Date.now()
  }
}

async function handleLogout() {
  try {
    await logout()
  } catch (e) {
    // 精简版 logout 是空实现，本地清理才是关键
  }
  if (socket) socket.close()
  if (presenceTimer) clearInterval(presenceTimer)
  document.title = 'IM'
  clearSession()
  profile.value = null
  messages.value = []
  status.value = 'idle'
}

onMounted(() => {
  if (getToken()) {
    loadProfile()
  }
})

onBeforeUnmount(() => {
  if (socket) socket.close()
  if (noticeTimer) clearTimeout(noticeTimer)
  if (presenceTimer) clearInterval(presenceTimer)
})
</script>

<template>
  <div class="page" :class="{ 'auth-layout': !profile }">
    <div class="glow glow-a"></div>
    <div class="glow glow-b"></div>

    <!-- 未登录：左品牌区 -->
    <aside v-if="!profile" class="auth-hero">
      <div class="auth-hero-logo">IM</div>
      <h2>微服务通讯平台</h2>
      <p>Spring Cloud Alibaba · Dubbo · Netty · RocketMQ</p>
      <ul class="auth-features">
        <li>单聊 · 好友 · 在线状态</li>
        <li>消息撤回与离线补发</li>
        <li>后台用户管理</li>
      </ul>
    </aside>

    <!-- 未登录 -->
    <main v-if="!profile" class="card">
      <header class="brand">
        <div class="logo">IM</div>
        <div>
          <h1>微服务通讯平台</h1>
          <p>Spring Cloud Alibaba + Dubbo + Netty + MQ</p>
        </div>
      </header>

      <form class="form" @submit.prevent="mode === 'login' ? handleLogin() : handleRegister()">
        <label class="field field-account">
          <span>账号</span>
          <input
            v-model="account"
            type="text"
            autocomplete="username"
            :placeholder="mode === 'login' ? 'admin / xiaohong / xiaoming …' : '4~20 位字母、数字、下划线'"
          />
        </label>
        <label class="field field-password">
          <span>密码</span>
          <input
            v-model="password"
            type="password"
            :autocomplete="mode === 'login' ? 'current-password' : 'new-password'"
            :placeholder="mode === 'login' ? '123456' : '8~20 位，需含字母和数字'"
          />
        </label>
        <label v-if="mode === 'register'" class="field field-nickname">
          <span>昵称</span>
          <input v-model="nickname" type="text" placeholder="不填就用账号" />
        </label>

        <p v-if="error" class="error">{{ error }}</p>

        <button class="primary" type="submit" :disabled="loading">
          {{ loading ? (mode === 'login' ? '登录中…' : '注册中…') : mode === 'login' ? '登录' : '注册并登录' }}
        </button>

        <p class="hint">
          <a class="switch-mode" href="#" @click.prevent="mode = mode === 'login' ? 'register' : 'login'">
            {{ mode === 'login' ? '还没有账号？去注册' : '已有账号？去登录' }}
          </a>
        </p>
        <p v-if="mode === 'login'" class="hint">
          多开几个标签页登录不同账号即可互相聊天（演示账号密码都是 123456）
        </p>
        <p v-else class="hint">注册成功后自动与管理员成为好友，可直接找管理员聊天</p>
      </form>
    </main>

    <!-- 已登录：聊天 -->
    <main v-else class="chat">
      <aside class="contacts">
        <!-- 自己 + 操作（收成一行，把按钮收进菜单） -->
        <div class="me">
          <div class="avatar small" :style="{ background: avatarColor(profile.id) }">
            {{ (profile.nickname || profile.account).charAt(0) }}
          </div>
          <div class="me-info">
            <strong>{{ profile.nickname || profile.account }}</strong>
            <span>
              <i class="dot" :class="status"></i>
              {{ statusText[status] }}
            </span>
          </div>
          <button class="icon-btn" type="button" title="添加好友" @click="openAddFriend">＋</button>
          <button class="icon-btn" type="button" title="更多" @click="showMenu = !showMenu">⋯</button>

          <div v-if="showMenu" class="menu">
            <button v-if="isAdmin()" type="button" class="menu-item" @click="showMenu = false; openAdmin()">
              管理后台
            </button>
            <button type="button" class="menu-item danger" @click="showMenu = false; handleLogout()">
              退出登录
            </button>
          </div>
        </div>

        <!-- 联系人过滤（纯前端，按昵称或 uid） -->
        <div class="search-box">
          <input v-model="contactKeyword" type="text" placeholder="搜索联系人" />
          <button v-if="contactKeyword" type="button" class="search-clear" @click="contactKeyword = ''">
            ×
          </button>
        </div>

        <!-- 好友申请（有待处理时才显示） -->
        <template v-if="friendRequests.length > 0">
          <p class="section-title">好友申请（{{ friendRequests.length }}）</p>
          <div class="request-list">
            <div v-for="r in friendRequests" :key="r.id" class="request-item">
              <div class="request-info">
                <strong>{{ r.fromName || '用户' + r.fromUid }}</strong>
                <span>{{ r.message || '请求添加你为好友' }}</span>
              </div>
              <div class="request-actions">
                <button type="button" class="mini primary-mini" @click="acceptRequest(r)">同意</button>
                <button type="button" class="mini" @click="rejectRequest(r)">拒绝</button>
              </div>
            </div>
          </div>
        </template>

        <!-- 联系人：好友与会话合并，一个人只出现一次 -->
        <div class="contact-list">
          <div
            v-for="c in contacts"
            :key="c.uid"
            class="contact"
            :class="{ active: c.uid === peer.id }"
            @click="selectContact(c)"
          >
            <div class="avatar small" :style="{ background: avatarColor(c.uid) }">
              {{ c.name.charAt(0) }}
            </div>
            <div class="contact-info">
              <strong>
                {{ c.name }}
                <span v-if="c.online" class="presence on" title="在线"></span>
              </strong>
              <span class="preview">{{ c.hasChat ? c.preview : '还没有聊过' }}</span>
            </div>
            <div class="contact-side">
              <span class="contact-time">{{ formatListTime(c.lastMsgAt) }}</span>
              <span v-if="c.unread > 0" class="badge">{{ c.unread > 99 ? '99+' : c.unread }}</span>
            </div>
          </div>

          <p v-if="contacts.length === 0" class="contact-empty">
            {{ contactKeyword ? '没有匹配的联系人' : '还没有好友（注册后会自动加管理员）' }}
          </p>
        </div>
      </aside>

      <!-- 加好友弹窗 -->
      <div v-if="showAddFriend" class="modal-mask" @click.self="showAddFriend = false">
        <div class="modal">
          <header class="modal-header">
            <strong>添加好友</strong>
            <button type="button" class="mini" @click="showAddFriend = false">关闭</button>
          </header>

          <div class="modal-search">
            <input
              v-model="keyword"
              type="text"
              placeholder="输入对方完整账号（如 xiaoming）"
              @keyup.enter="doSearch"
            />
            <button type="button" class="primary" :disabled="searching" @click="doSearch">
              {{ searching ? '搜索中…' : '搜索' }}
            </button>
          </div>

          <div class="modal-results">
            <div v-for="u in searchResults" :key="u.id" class="result-item">
              <div class="avatar small">{{ (u.nickname || u.account).charAt(0) }}</div>
              <div class="result-info">
                <strong>{{ u.nickname }}</strong>
                <span>@{{ u.account }} · uid {{ u.id }}</span>
              </div>
              <button
                type="button"
                class="mini primary-mini"
                :disabled="u.added"
                @click="addFriend(u)"
              >
                {{ u.added ? '已发送' : '加好友' }}
              </button>
            </div>

            <p v-if="searchResults.length === 0" class="contact-empty">
              只能按完整账号精确查找，搜不到就是没有这个人
            </p>
          </div>
        </div>
      </div>

      <!-- 后台管理面板（仅管理员可见） -->
      <div v-if="showAdmin" class="modal-mask" @click.self="showAdmin = false">
        <div class="modal admin-modal">
          <header class="modal-header">
            <strong>用户管理</strong>
            <button type="button" class="mini" @click="showAdmin = false">关闭</button>
          </header>

          <div class="modal-search">
            <input
              v-model="adminKeyword"
              type="text"
              placeholder="账号或昵称关键词"
              @keyup.enter="loadAdminUsers"
            />
            <button type="button" class="primary" :disabled="adminLoading" @click="loadAdminUsers">
              {{ adminLoading ? '加载中…' : '查询' }}
            </button>
          </div>

          <div class="admin-table">
            <div class="admin-row admin-head">
              <span>用户</span>
              <span>角色</span>
              <span>状态</span>
              <span>操作</span>
            </div>

            <div v-for="u in adminUsers" :key="u.id" class="admin-row">
              <div class="admin-user">
                <strong>{{ u.nickname }}</strong>
                <span>@{{ u.account }} · uid {{ u.id }}</span>
                <span class="presence" :class="{ on: u.online }" :title="u.online ? '在线' : '离线'"></span>
              </div>
              <span class="admin-role">{{ u.role === 'admin' ? '管理员' : '普通用户' }}</span>
              <span :class="u.status === 1 ? 'state-ok' : 'state-off'">
                {{ u.status === 1 ? '正常' : '已禁用' }}
              </span>
              <div class="admin-actions">
                <button type="button" class="mini" :disabled="!u.online" @click="kickOffline(u)">踢下线</button>
                <button type="button" class="mini" @click="toggleUserStatus(u)">
                  {{ u.status === 1 ? '禁用' : '启用' }}
                </button>
                <button type="button" class="mini" @click="resetPasswordOf(u)">改密码</button>
              </div>
            </div>

            <p v-if="adminUsers.length === 0" class="contact-empty">没有匹配的用户</p>
          </div>
        </div>
      </div>

      <section class="conversation">
        <header class="conv-header">
          <div v-if="hasPeer" class="conv-peer">
            <div class="avatar small" :style="{ background: avatarColor(peer.id) }">
              {{ (peer.name || '?').charAt(0) }}
            </div>
            <div>
              <strong>{{ peer.name }}</strong>
              <span class="status-text">{{ statusText[status] }}</span>
            </div>
          </div>
          <div v-else class="conv-peer">
            <div class="avatar small ghost-avatar">?</div>
            <div>
              <strong>未选择联系人</strong>
              <span class="status-text">{{ statusText[status] }}</span>
            </div>
          </div>
        </header>

        <!-- 断线/重连提示：比顶部的小圆点更显眼 -->
        <p v-if="status === 'closed' || status === 'connecting'" class="conn-banner">
          连接已断开，正在尝试重连…
        </p>

        <div ref="listEl" class="messages" @scroll="handleScroll">
          <!-- 顶部：可点击加载更早，避免"上滑"这个交互太隐蔽；内容不满一屏时也能用 -->
          <button
            v-if="hasMore"
            type="button"
            class="loading-more clickable"
            :disabled="loadingMore"
            @click="loadMore"
          >
            {{ loadingMore ? '加载中…' : '加载更早的消息' }}
          </button>
          <p v-else-if="messages.length > 0" class="loading-more">没有更早的消息了</p>

          <div v-if="messages.length === 0" class="empty-state">
            <svg class="empty-icon" viewBox="0 0 48 48" width="44" height="44" aria-hidden="true">
              <path
                d="M8 12a6 6 0 0 1 6-6h20a6 6 0 0 1 6 6v14a6 6 0 0 1-6 6H20l-8 7v-7h-2a2 2 0 0 1-2-2V12z"
                fill="none"
                stroke="#c9d4e6"
                stroke-width="2.5"
                stroke-linejoin="round"
              />
              <circle cx="18" cy="19" r="1.8" fill="#d5dfef" />
              <circle cx="24" cy="19" r="1.8" fill="#d5dfef" />
              <circle cx="30" cy="19" r="1.8" fill="#d5dfef" />
            </svg>
            <p class="empty-title">{{ hasPeer ? '还没有消息' : '还没有选择联系人' }}</p>
            <p class="empty-sub">
              {{
                hasPeer
                  ? `跟「${peer.name}」打个招呼吧`
                  : '从左侧选一位好友，或点右上角 ＋ 添加好友'
              }}
            </p>
          </div>

          <template v-for="(msg, index) in messages" :key="msg.key">
            <!-- 时间分隔：第一条、跨天、或间隔超过 5 分钟才显示 -->
            <p v-if="needTimeDivider(index)" class="msg-time">
              {{ formatDay(msg.ts) }} {{ formatTime(msg.ts) }}
            </p>

            <div class="row" :class="msg.system ? 'system' : msg.mine ? 'mine' : 'peer'">
              <!-- 系统消息（from=0）：居中的灰字提示，而不是对话气泡 -->
              <span v-if="msg.system" class="system-tip">{{ msg.content }}</span>

              <template v-else>
                <div
                  class="bubble"
                  :class="{ failed: msg.status === 'failed', recalled: msg.recalled }"
                  :title="msg.status === 'failed' ? '点击重发' : ''"
                  @click="resend(msg)"
                >
                  <span v-if="msg.recalled">
                    {{ msg.mine ? '你撤回了一条消息' : '对方撤回了一条消息' }}
                  </span>
                  <span v-else>{{ msg.content }}</span>
                </div>
                <span class="meta" v-if="msg.mine">
                  <template v-if="msg.recalled">
                    <span class="recall-link">已撤回</span>
                  </template>
                  <template v-else-if="msg.status === 'sending'">
                    <span class="status sending" title="发送中">
                      <svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.6">
                        <circle cx="8" cy="8" r="6.2" />
                        <path d="M8 4.5V8l2.3 1.4" stroke-linecap="round" stroke-linejoin="round" />
                      </svg>
                      发送中
                    </span>
                  </template>
                  <template v-else-if="msg.status === 'failed'">
                    <span class="failed-text">
                      <svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.7">
                        <circle cx="8" cy="8" r="6.2" />
                        <path d="M8 4.8v3.6" stroke-linecap="round" />
                        <circle cx="8" cy="11.3" r="0.7" fill="currentColor" stroke="none" />
                      </svg>
                      {{ msg.failReason || '发送失败，点击重发' }}
                    </span>
                  </template>
                  <template v-else>
                    <span class="status sent" title="已送达">
                      <svg viewBox="0 0 18 13" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
                        <path d="M2 7.2L6.5 11.5L16 2.5" />
                      </svg>
                    </span>
                    <span v-if="canRecall(msg)" class="recall-link" @click.stop="recall(msg)">撤回</span>
                  </template>
                </span>
              </template>
            </div>
          </template>
        </div>

        <!-- 不在底部时，新消息不抢滚动，只提示条数 -->
        <button v-if="unreadNew > 0" type="button" class="jump-bottom" @click="jumpToBottom">
          ↓ {{ unreadNew }} 条新消息
        </button>

        <footer class="composer">
          <p v-if="notice" class="notice">{{ notice }}</p>
          <div v-if="error" class="error-bar">
            <span class="error">{{ error }}</span>
            <!-- 被踢 / 过期后给出明确出口，否则只能手动刷新 -->
            <button
              v-if="status === 'kicked' || status === 'token-expired'"
              type="button"
              class="mini primary-mini"
              @click="handleLogout"
            >
              重新登录
            </button>
          </div>
          <div class="composer-row">
            <input
              v-model="draft"
              class="composer-input"
              type="text"
              :disabled="status !== 'connected' || !hasPeer"
              :placeholder="
                !hasPeer
                  ? '请先选择一位联系人'
                  : status === 'connected'
                    ? '输入消息…'
                    : '连接已断开，请重新登录'
              "
              @keyup.enter="handleSend"
            />
            <button
              class="send-btn"
              type="button"
              :disabled="status !== 'connected' || !hasPeer || !draft.trim()"
              title="发送（回车）"
              @click="handleSend"
            >
              ➤
            </button>
          </div>
        </footer>
      </section>
    </main>
  </div>
</template>
