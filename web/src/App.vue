<script setup>
import { nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { clearSession, fetchMessages, fetchProfile, getToken, login, logout, setToken } from './api'
import { ChatSocket } from './ws'

const account = ref('admin')
const password = ref('123456')
const loading = ref(false)
const error = ref('')

const profile = ref(null)
const peer = ref({ id: 2, name: '小红' })
const messages = ref([])
const draft = ref('')
const status = ref('idle')
const listEl = ref(null)

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
  // 本地演示：库里只有两个账号，互相为对端
  if (me.id === 1) return { id: 2, name: '小红' }
  return { id: 1, name: '管理员' }
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
    return
  }
  if (frame.type === 'ACK') {
    const target = messages.value.find((m) => m.key === frame.cid)
    if (!target) return
    if (!frame.seq) {
      // seq=0 表示服务端没存成：转成"发送失败"，不要让用户以为还在发
      target.status = 'failed'
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
    peer.value = resolvePeer(profile.value)
    await loadHistory()      // 先灌历史，再建连，避免实时消息被历史覆盖
    connect()
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
}

async function handleLogout() {
  try {
    await logout()
  } catch (e) {
    // 精简版 logout 是空实现，本地清理才是关键
  }
  if (socket) socket.close()
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

      <form class="form" @submit.prevent="handleLogin">
        <label class="field">
          <span>账号</span>
          <input v-model="account" type="text" autocomplete="username" placeholder="admin 或 xiaohong" />
        </label>
        <label class="field">
          <span>密码</span>
          <input v-model="password" type="password" autocomplete="current-password" placeholder="123456" />
        </label>

        <p v-if="error" class="error">{{ error }}</p>

        <button class="primary" type="submit" :disabled="loading">
          {{ loading ? '登录中…' : '登录' }}
        </button>
        <p class="hint">开两个标签页分别登录 admin 与 xiaohong，即可互相聊天</p>
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
        </div>

        <div class="contact active">
          <div class="avatar small">{{ peer.name.charAt(0) }}</div>
          <div class="contact-info">
            <strong>{{ peer.name }}</strong>
            <span>uid {{ peer.id }}</span>
          </div>
          <i class="dot" :class="status"></i>
        </div>

        <button class="ghost logout" type="button" @click="handleLogout">退出登录</button>
      </aside>

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
                <span v-else-if="msg.status === 'failed'" class="failed-text">发送失败，请重发</span>
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
