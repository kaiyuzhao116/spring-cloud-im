const WS_URL = 'ws://127.0.0.1:8089/ws'
const PING_INTERVAL = 25000
const RECONNECT_DELAYS = [1000, 2000, 4000, 8000, 10000]

export class ChatSocket {
  constructor({ token, onFrame, onStatus }) {
    this.token = token
    this.onFrame = onFrame || (() => {})
    this.onStatus = onStatus || (() => {})
    this.ws = null
    this.pingTimer = null
    this.closedByUser = false
    this.retry = 0
  }

  connect() {
    this.closedByUser = false
    this.onStatus('connecting')
    this.ws = new WebSocket(`${WS_URL}?token=${encodeURIComponent(this.token)}`)

    this.ws.onopen = () => {
      this.retry = 0
      this.onStatus('connected')
      this.startPing()
    }

    this.ws.onmessage = (event) => {
      let frame
      try {
        frame = JSON.parse(event.data)
      } catch (e) {
        return
      }
      if (frame.type === 'PONG') return
      this.onFrame(frame)
    }

    this.ws.onerror = () => {
      this.onStatus('error')
    }

    this.ws.onclose = (event) => {
      this.stopPing()
      this.onStatus('closed')
      // 4001 = 被踢下线，不重连
      if (this.closedByUser || event.code === 4001) return
      const delay = RECONNECT_DELAYS[Math.min(this.retry, RECONNECT_DELAYS.length - 1)]
      this.retry += 1
      setTimeout(() => this.connect(), delay)
    }
  }

  startPing() {
    this.stopPing()
    this.pingTimer = setInterval(() => {
      this.sendFrame({ type: 'PING' })
    }, PING_INTERVAL)
  }

  stopPing() {
    if (this.pingTimer) {
      clearInterval(this.pingTimer)
      this.pingTimer = null
    }
  }

  sendFrame(frame) {
    if (this.ws && this.ws.readyState === WebSocket.OPEN) {
      this.ws.send(JSON.stringify(frame))
      return true
    }
    return false
  }

  /**
   * 发消息，返回本地 cid（用于把 ACK 对应到那条消息）
   */
  sendMessage(to, content) {
    const cid = `c-${Date.now()}-${Math.floor(Math.random() * 1000)}`
    const ok = this.sendFrame({ type: 'MSG', cid, to, content })
    return ok ? cid : null
  }

  close() {
    this.closedByUser = true
    this.stopPing()
    if (this.ws) {
      this.ws.close(1000)
    }
  }
}
