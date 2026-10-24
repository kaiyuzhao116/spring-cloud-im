"""用 WebSocket 发一条消息（替代已移除的 HTTP 调试接口 POST /api/im/message）。

用法：
    python3 ws-demo.py <account> <password> <toUid> <content>

例：
    python3 ws-demo.py admin 123456 2 "你好"

只依赖 Python 标准库，不需要额外安装。
"""
import base64
import json
import os
import socket
import sys
import time
import urllib.request

WS_HOST, WS_PORT = "127.0.0.1", 8089
API_BASE = "http://127.0.0.1:8081"


def login(account, password):
    body = json.dumps({"account": account, "password": password, "deviceType": "WEB"}).encode()
    req = urllib.request.Request(
        API_BASE + "/api/auth/login", data=body,
        headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=10) as resp:
        data = json.loads(resp.read())
    if data.get("code") != 0:
        raise SystemExit("登录失败: %s" % data.get("message"))
    return data["data"]["accessToken"], data["data"]["userId"]


def connect(token):
    s = socket.create_connection((WS_HOST, WS_PORT), timeout=10)
    key = base64.b64encode(os.urandom(16)).decode()
    req = (
        "GET /ws?token=%s HTTP/1.1\r\n"
        "Host: %s:%d\r\n"
        "Upgrade: websocket\r\n"
        "Connection: Upgrade\r\n"
        "Sec-WebSocket-Key: %s\r\n"
        "Sec-WebSocket-Version: 13\r\n\r\n"
    ) % (token, WS_HOST, WS_PORT, key)
    s.sendall(req.encode())

    buf = b""
    while b"\r\n\r\n" not in buf:
        buf += s.recv(1024)
    status = buf.split(b"\r\n")[0].decode()
    if "101" not in status:
        raise SystemExit("握手失败: " + status)
    print("握手: " + status)
    return s


def send_text(s, text):
    """客户端发帧必须加掩码（RFC 6455）"""
    payload = text.encode()
    mask = os.urandom(4)
    frame = bytearray([0x81])
    n = len(payload)
    if n < 126:
        frame.append(0x80 | n)
    elif n < 65536:
        frame.append(0x80 | 126)
        frame += n.to_bytes(2, "big")
    else:
        frame.append(0x80 | 127)
        frame += n.to_bytes(8, "big")
    frame += mask
    frame += bytes(b ^ mask[i % 4] for i, b in enumerate(payload))
    s.sendall(bytes(frame))


def read_frame(s):
    head = s.recv(2)
    if len(head) < 2:
        return None, None
    op = head[0] & 0x0F
    length = head[1] & 0x7F
    if length == 126:
        length = int.from_bytes(s.recv(2), "big")
    elif length == 127:
        length = int.from_bytes(s.recv(8), "big")
    data = b""
    while len(data) < length:
        chunk = s.recv(length - len(data))
        if not chunk:
            break
        data += chunk
    return op, data


def main():
    if len(sys.argv) < 5:
        raise SystemExit(__doc__)

    account, password, to_uid, content = sys.argv[1], sys.argv[2], int(sys.argv[3]), sys.argv[4]

    token, uid = login(account, password)
    print("登录成功 uid=%d" % uid)

    s = connect(token)
    s.settimeout(5)

    # 先吃掉欢迎消息
    try:
        op, data = read_frame(s)
        if op == 1:
            print("欢迎帧:", json.loads(data).get("content"))
    except socket.timeout:
        pass

    cid = "demo-%d" % int(time.time() * 1000)
    send_text(s, json.dumps({"type": "MSG", "cid": cid, "to": to_uid, "content": content}))
    print("已发送 cid=%s to=%d" % (cid, to_uid))

    while True:
        try:
            op, data = read_frame(s)
        except socket.timeout:
            print("等待 ACK 超时")
            break
        if op != 1 or not data:
            continue
        frame = json.loads(data)
        if frame.get("type") == "ACK" and frame.get("cid") == cid:
            if frame.get("seq"):
                print("发送成功 seq=%s" % frame.get("seq"))
            else:
                print("发送失败 code=%s reason=%s" % (frame.get("code"), frame.get("reason")))
            break

    s.close()


if __name__ == "__main__":
    main()
