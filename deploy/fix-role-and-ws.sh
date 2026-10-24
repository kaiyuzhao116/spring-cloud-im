echo "=== 1. 修正 admin 角色 ==="
docker exec campus-mysql mysql -uroot -p123456 -e \
  "use spring_cloud; update sys_user set role='admin' where account='admin'; select id,account,role from sys_user where account='admin';" 2>/dev/null

echo
echo "=== 2. 重新登录拿 token ==="
TOKEN=$(curl -s -X POST http://127.0.0.1:8081/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"account":"admin","password":"123456","deviceType":"WEB"}' \
  | sed -n 's/.*"accessToken":"\([^"]*\)".*/\1/p')
echo "token: ${TOKEN:0:24}..."

echo
echo "=== 3. 确认 role 已变 admin ==="
curl -s http://127.0.0.1:8081/api/user/profile -H "Authorization: Bearer $TOKEN" | head -c 200
echo

echo
echo "=== 4. WebSocket 握手（经 nginx /ws）==="
curl -s -i -N --max-time 3 \
  -H "Connection: Upgrade" \
  -H "Upgrade: websocket" \
  -H "Sec-WebSocket-Version: 13" \
  -H "Sec-WebSocket-Key: x3JJHMbDL1EzLkh9GBhXDw==" \
  "http://127.0.0.1:8081/ws?token=$TOKEN" 2>&1 | head -8

echo
echo "=== 5. 管理后台接口（应 200）==="
curl -s -o /dev/null -w 'admin/users: %{http_code}\n' http://127.0.0.1:8081/api/admin/users -H "Authorization: Bearer $TOKEN"
