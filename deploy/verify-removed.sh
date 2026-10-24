cd /opt/im
docker compose restart im 2>&1 | tail -2
sleep 25
docker logs --tail=100 im-im 2>&1 | grep -a "Started ImApplication" | tail -1

AT=$(curl -s -X POST http://127.0.0.1:8081/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"account":"admin","password":"123456","deviceType":"WEB"}' \
  | sed -n 's/.*"accessToken":"\([^"]*\)".*/\1/p')

echo
echo "=== 1. 已删除的接口应 404 ==="
curl -s -o /dev/null -w 'POST /api/im/message  -> %{http_code}\n' -X POST \
  http://127.0.0.1:8081/api/im/message -H "Authorization: Bearer $AT" \
  -H 'Content-Type: application/json' -d '{"to":2,"cid":"x","content":"x"}'

echo
echo "=== 2. 其他接口应正常 ==="
curl -s -o /dev/null -w 'GET  /api/im/friends   -> %{http_code}\n' http://127.0.0.1:8081/api/im/friends -H "Authorization: Bearer $AT"
curl -s -o /dev/null -w 'GET  /api/im/messages  -> %{http_code}\n' "http://127.0.0.1:8081/api/im/messages?conv=s_1_2&limit=5" -H "Authorization: Bearer $AT"
curl -s -o /dev/null -w 'GET  /api/im/conversations -> %{http_code}\n' http://127.0.0.1:8081/api/im/conversations -H "Authorization: Bearer $AT"

echo
echo "=== 3. WS 发消息（替代工具）==="
python3 ws-demo.py admin 123456 2 "ws-demo 测试"
