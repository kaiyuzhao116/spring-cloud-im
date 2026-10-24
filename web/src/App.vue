<script setup>
import { nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import {
  acceptFriendRequest,
  clearSession,
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

/** 收到的好友申请 */
const friendRequests = ref([])

/** 加好友弹窗 */
const showAddFriend = ref(false)
const keyword = ref('')
const searchResults = ref([])
const searching = ref(false)

/** 申请列表轮询（P1 先用轮询，实时推送留到后面） */
let requestTimer = null

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

function resolvePeer(me) {
  // 兜底：会话列表为空时，按"另一个账号"来猜对端
  if (me.id === 1) return { id: 2, name: '小红' }
  return { id: 1, name: '管理员' }
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

/** 切换会话：清空当前消息，拉对方的历史 */
async function switchPeer(c) {
  c.unread = 0
  if (c.peerUid === peer.value.id) return

  peer.value = { id: c.peerUid, name: c.name }
  messages.value = []
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

function startRequestPolling() {
  stopRequestPolling()
  // 20 秒拉一次申请；P2 会换成 MQ 实时推送
  requestTimer = setInterval(loadFriendRequests, 20000)
}

function stopRequestPolling() {
  if (requestTimer) {
    clearInterval(requestTimer)
    requestTimer = null
  }
}

/** 从好友列表点开聊天（可能还没有任何会话，历史为空） */
async function openFriend(f) {
  const name = f.nickname || nicknameOf(f.uid)
  if (f.uid === peer.value.id) return

  peer.value = { id: f.uid, name }
  messages.value = []
  hasMore.value = false
  await loadHistory()
}

function scrollToBottom() {
  nextTick(() => {
    if (listEl.value) {
      listEl.value.scrollTop = listEl.value.scrollHeight
    }
  })
}

/** 追加一条消息，并裁掉超出上限的最老消息 */
function pushMessage(item, toBottom = true) {
  messages.value.push(item)
  if (messages.value.length > MAX_KEEP) {
    messages.value.splice(0, messages.value.length - MAX_KEEP)
  }
  if (toBottom) {
    scrollToBottom()
  }
}

function handleFrame(frame) {
  if (frame.type === 'MSG') {
    // from=0 是系统消息（欢迎、通知…），居中展示，不属于任何会话
    if (frame.from === 0) {
      pushMessage({
        key: `sys-${Date.now()}-${messages.value.length}`,
        system: true,
        content: frame.content
      })
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
      status: 'received'
    })

    // 同步更新左侧会话卡片
    const conv = conversations.value.find((c) => c.peerUid === frame.from)
    if (conv) {
      conv.preview = frame.content
      conv.lastSeq = frame.seq
      if (conv.peerUid === peer.value.id) {
        // 正在看这个会话：直接算已读
        conv.unread = 0
        reportRead(conversationId(), frame.seq)
      } else {
        conv.unread = (conv.unread || 0) + 1
      }
    } else {
      loadConversations()   // 新会话（对方第一次发消息给我）
    }
    return
  }
  if (frame.type === 'ACK') {
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
      }
    }
  })
  socket.connect()
}

/** 会话 ID：s_{小uid}_{大uid}，和后端 ConversationId.single 规则一致 */
function conversationId() {
  const a = profile.value.id
  const b = peer.value.id
  return `s_${Math.min(a, b)}_${Math.max(a, b)}`
}

/** 拉历史消息；失败不打断聊天（只是看不到旧记录） */
async function loadHistory() {
  try {
    const data = await fetchMessages(conversationId(), { limit: 30 })
    messages.value = data.messages.map((m) => ({
      key: `h-${m.seq}`,
      mine: m.from === profile.value.id,
      content: m.content,
      seq: m.seq,
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
      pushMessage(
        {
          key: `h-${m.seq}`,
          mine: m.from === profile.value.id,
          content: m.content,
          seq: m.seq,
          status: 'sent'
        },
        false
      )
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
    connect()
    startRequestPolling()
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
  pushMessage({ key: cid, mine: true, content: text, seq: null, status: 'sending' })
  draft.value = ''
  error.value = ''

  // 左侧会话卡片的预览同步更新
  const conv = conversations.value.find((c) => c.peerUid === peer.value.id)
  if (conv) {
    conv.preview = text
  }
}

async function handleLogout() {
  try {
    await logout()
  } catch (e) {
    // 精简版 logout 是空实现，本地清理才是关键
  }
  if (socket) socket.close()
  stopRequestPolling()
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
  stopRequestPolling()
})
</script>

<template>
  <div class="page">
    <div class="glow glow-a"></div>
    <div class="glow glow-b"></div>

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
        <label class="field">
          <span>账号</span>
          <input
            v-model="account"
            type="text"
            autocomplete="username"
            :placeholder="mode === 'login' ? 'admin / xiaohong / xiaoming …' : '4~20 位字母、数字、下划线'"
          />
        </label>
        <label class="field">
          <span>密码</span>
          <input
            v-model="password"
            type="password"
            :autocomplete="mode === 'login' ? 'current-password' : 'new-password'"
            :placeholder="mode === 'login' ? '123456' : '8~20 位，需含字母和数字'"
          />
        </label>
        <label v-if="mode === 'register'" class="field">
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
        <div class="me">
          <div class="avatar">{{ (profile.nickname || profile.account).charAt(0) }}</div>
          <div class="me-info">
            <strong>{{ profile.nickname || profile.account }}</strong>
            <span>uid {{ profile.id }}</span>
          </div>
          <i class="dot" :class="status"></i>
        </div>

        <button class="ghost add-friend" type="button" @click="openAddFriend">＋ 添加好友</button>

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

        <p class="section-title">消息</p>
        <div class="contact-list">
          <div
            v-for="c in conversations"
            :key="c.conversationId"
            class="contact"
            :class="{ active: c.peerUid === peer.id }"
            @click="switchPeer(c)"
          >
            <div class="avatar small">{{ c.name.charAt(0) }}</div>
            <div class="contact-info">
              <strong>{{ c.name }}</strong>
              <span class="preview">{{ c.preview || '暂无消息' }}</span>
            </div>
            <span v-if="c.unread > 0" class="badge">{{ c.unread > 99 ? '99+' : c.unread }}</span>
          </div>

          <p v-if="conversations.length === 0" class="contact-empty">还没有会话</p>
        </div>

        <p class="section-title">好友</p>
        <div class="contact-list">
          <div
            v-for="f in friends"
            :key="f.uid"
            class="contact"
            :class="{ active: f.uid === peer.id }"
            @click="openFriend(f)"
          >
            <div class="avatar small">{{ (f.nickname || nicknameOf(f.uid)).charAt(0) }}</div>
            <div class="contact-info">
              <strong>{{ f.nickname || nicknameOf(f.uid) }}</strong>
              <span class="preview">点我开始聊天</span>
            </div>
          </div>

          <p v-if="friends.length === 0" class="contact-empty">还没有好友（注册后会自动加管理员）</p>
        </div>

        <button class="ghost logout" type="button" @click="handleLogout">退出登录</button>
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

      <section class="conversation">
        <header class="conv-header">
          <div>
            <strong>{{ peer.name }}</strong>
            <span class="status-text">{{ statusText[status] }}</span>
          </div>
        </header>

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

          <p v-if="messages.length === 0" class="empty">还没有消息，说点什么吧</p>

          <div
            v-for="msg in messages"
            :key="msg.key"
            class="row"
            :class="msg.system ? 'system' : msg.mine ? 'mine' : 'peer'"
          >
            <!-- 系统消息（from=0）：居中的灰字提示，而不是对话气泡 -->
            <span v-if="msg.system" class="system-tip">{{ msg.content }}</span>

            <template v-else>
            <div class="bubble" :class="{ failed: msg.status === 'failed' }">{{ msg.content }}</div>
            <span class="meta">
              <template v-if="msg.mine">
                <span v-if="msg.status === 'sending'">发送中…</span>
                <span v-else-if="msg.status === 'failed'" class="failed-text">
                  {{ msg.failReason || '发送失败，请重发' }}
                </span>
                <span v-else>seq {{ msg.seq }}</span>
              </template>
              <template v-else>seq {{ msg.seq }}</template>
            </span>
            </template>
          </div>
        </div>

        <footer class="composer">
          <p v-if="error" class="error">{{ error }}</p>
          <div class="composer-row">
            <input
              v-model="draft"
              type="text"
              :disabled="status !== 'connected'"
              :placeholder="status === 'connected' ? '输入消息，回车发送' : '连接已断开，请重新登录'"
              @keyup.enter="handleSend"
            />
            <button class="primary" type="button" :disabled="status !== 'connected'" @click="handleSend">
              发送
            </button>
          </div>
        </footer>
      </section>
    </main>
  </div>
</template>
