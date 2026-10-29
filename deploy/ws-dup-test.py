"""验证 client_msg_id（cid）的幂等效果。

用同一个 cid 连发两次，观察：
  1. 两次的 ACK 返回的 seq 是否相同
  2. 数据库里是否只多了一条
"""
import base64
import json
import os
import socket
import sys
import time
import urllib.request

HOST, PORT = "127.0.0.1", 8089
API = "http://127.0.0.1:8081"


def login(account, password):
    body = json.dumps({"account": account, "password": password, "deviceType": "WEB"}).encode()
    req = urllib.request.Request(API + "/api/auth/login", data=body,
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=10) as r:
        return json.loads(r.read())["data"]["accessToken"]


def connect(token):
    s = socket.create_connection((HOST, PORT), timeout=10)
    key = base64.b64encode(os.urandom(16)).decode()
    req = (
        "GET /ws?token=%s HTTP/1.1\r\nHost: %s:%d\r\nUpgrade: websocket\r\n"
        "Connection: Upgrade\r\nSec-WebSocket-Key: %s\r\nSec-WebSocket-Version: 13\r\n\r\n"
    ) % (token, HOST, PORT, key)
    s.sendall(req.encode())
    buf = b""
    while b"\r\n\r\n" not in buf:
        buf += s.recv(1024)
    return s


def send_text(s, text):
    payload = text.encode()
    mask = os.urandom(4)
    frame = bytearray([0x81])
    n = len(payload)
    if n < 126:
        frame.append(0x80 | n)
    else:
        frame.append(0x80 | 126)
        frame += n.to_bytes(2, "big")
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


def wait_ack(s, cid, seconds=5):
    end = time.time() + seconds
    while time.time() < end:
        try:
            op, data = read_frame(s)
        except socket.timeout:
            continue
        if op != 1 or not data:
            continue
        frame = json.loads(data)
        if frame.get("type") == "ACK" and frame.get("cid") == cid:
            return frame.get("seq")
    return None


def main():
    todo_uid = int(sys.argv[1]) if len(sys.argv) > 1 else 2
    token = login("admin", "123456")
    s = connect(token)
    s.settimeout(3)
    try:
        read_frame(s)          # 吃掉欢迎帧
    except Exception:
        pass

    # 固定 cid：两条消息完全一样
    cid = "dup-test-%d" % int(time.time() * 1000)

    print("=== 第 1 次发送 ===")
    print("cid =", cid)
    send_text(s, json.dumps({"type": "MSG", "cid": cid, "to": todo_uid, "content": "幂等测试"}))
    seq1 = wait_ack(s, cid)
    print("服务端回 ACK: seq =", seq1)

    print()
    print("=== 第 2 次发送（同一个 cid）===")
    print("cid =", cid)
    send_text(s, json.dumps({"type": "MSG", "cid": cid, "to": todo_uid, "content": "幂等测试"}))
    seq2 = wait_ack(s, cid)
    print("服务端回 ACK: seq =", seq2)

    s.close()

    print()
    print("=== 结论 ===")
    print("两次 seq:", seq1, "vs", seq2, "→", "相同（说明没有新增消息）" if seq1 == seq2 else "不同（重复落库了！）")


if __name__ == "__main__":
    main()
