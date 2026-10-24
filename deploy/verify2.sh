echo "=== 直连网关 ==="
curl -s -o /dev/null -w 'gateway health: %{http_code}\n' http://127.0.0.1:18080/actuator/health

echo "=== 经 nginx 登录 ==="
RESP=$(curl -s -X POST http://127.0.0.1:8081/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"account":"admin","password":"123456","deviceType":"WEB"}')
echo "$RESP" | head -c 300
echo

TOKEN=$(echo "$RESP" | sed -n 's/.*"accessToken":"\([^"]*\)".*/\1/p')
if [ -z "$TOKEN" ]; then
  echo "!! 登录失败，没有拿到 token"
  exit 1
fi
echo "token 长度: ${#TOKEN}"

echo "=== 带 token 拉资料 ==="
curl -s http://127.0.0.1:8081/api/user/profile -H "Authorization: Bearer $TOKEN" | head -c 300
echo

echo "=== 好友列表 ==="
curl -s http://127.0.0.1:8081/api/im/friends -H "Authorization: Bearer $TOKEN" | head -c 300
echo

echo "=== 续签 ==="
curl -s -X POST http://127.0.0.1:8081/api/auth/renew -H "Authorization: Bearer $TOKEN" | head -c 200
echo
