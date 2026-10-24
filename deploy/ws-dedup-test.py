"""端到端验证：乱序不丢、重复不推。

流程：
1. 登录拿 token，连 ws://127.0.0.1:8089/ws
2. 依次向 MQ 灌 seq=101、seq=100（乱序）、seq=101（重复）
3. 收集客户端收到的 seq，输出结论
"""
import base64
import json
import os
import socket
import time
import urllib.request

HOST, PORT = "127.0.0.1", 8089
TO_UID, FROM_UID = 1, 2


def login():
    body = json.dumps({"account": "admin", "password": "123456", "deviceType": "WEB"}).encode()
    req = urllib.request.Request(
        "http://127.0.0.1:8081/api/auth/login", data=body,
        headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=10) as resp:
        return json.loads(resp.read())["data"]["accessToken"]


def publish(seq):
    payload = json.dumps({
        "msgId": seq, "conversationId": "s_1_2", "seq": seq,
        "fromUid": FROM_UID, "toUid": TO_UID,
        "content": "order-test-%d" % seq, "ts": 1,
    })
    body = json.dumps({
        "properties": {}, "routing_key": "message.created",
        "payload": payload, "payload_encoding": "string",
    }).encode()
    cred = base64.b64encode(b"admin:123456").decode()
    req = urllib.request.Request(
        "http://127.0.0.1:15672/api/exchanges/%2F/im.events/publish",
        data=body, headers={"Content-Type": "application/json",
                            "Authorization": "Basic " + cred})
    with urllib.request.urlopen(req, timeout=10) as resp:
        return resp.read().decode()


def connect(token):
    s = socket.create_connection((HOST, PORT), timeout=15)
    key = base64.b64encode(os.urandom(16)).decode()
    req = (
        "GET /ws?token=%s HTTP/1.1\r\n"
        "Host: %s:%d\r\n"
        "Upgrade: websocket\r\n"
        "Connection: Upgrade\r\n"
        "Sec-WebSocket-Key: %s\r\n"
        "Sec-WebSocket-Version: 13\r\n\r\n"
    ) % (token, HOST, PORT, key)
    s.sendall(req.encode())
    buf = b""
    while b"\r\n\r\n" not in buf:
        buf += s.recv(1024)
    status = buf.split(b"\r\n")[0].decode()
    print("握手:", status)
    return s


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
    token = login()
    s = connect(token)
    s.settimeout(0.5)

    received = []

    def drain(seconds):
        end = time.time() + seconds
        while time.time() < end:
            try:
                op, data = read_frame(s)
            except socket.timeout:
                continue
            except Exception:
                return
            if op == 1 and data:
                try:
                    frame = json.loads(data)
                except Exception:
                    continue
                if frame.get("type") == "MSG" and frame.get("from") == FROM_UID:
                    received.append(frame.get("seq"))
                    print("收到 seq =", frame.get("seq"))

    drain(1)  # 清掉欢迎消息

    print("\n--- 1) 发 seq=101 ---")
    publish(101)
    drain(1.5)

    print("\n--- 2) 发 seq=100（比 101 小，属于乱序）---")
    publish(100)
    drain(1.5)

    print("\n--- 3) 再发一次 seq=101（完全重复）---")
    publish(101)
    drain(1.5)

    s.close()

    print("\n=== 结论 ===")
    print("客户端收到:", received)
    ok1 = 101 in received
    ok2 = 100 in received
    ok3 = received.count(101) == 1
    print("  乱序小 seq(100) 未丢失 :", "✅" if ok2 else "❌ 被误杀")
    print("  首个 seq(101) 正常到达 :", "✅" if ok1 else "❌")
    print("  重复 seq(101) 只推一次 :", "✅" if ok3 else "❌ 重复推送")


if __name__ == "__main__":
    main()
