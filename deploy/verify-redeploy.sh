echo "=== 1. 前端资源 ==="
curl -s -o /dev/null -w 'index: %{http_code}\n' http://127.0.0.1:8081/
JS=$(curl -s http://127.0.0.1:8081/ | grep -o '/assets/[^"]*\.js')
[ -n "$JS" ] && curl -s -o /dev/null -w "js($JS): %{http_code}\n" "http://127.0.0.1:8081$JS"

echo
echo "=== 2. 登录 ==="
AT=$(curl -s -X POST http://127.0.0.1:8081/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"account":"admin","password":"123456","deviceType":"WEB"}' \
  | sed -n 's/.*"accessToken":"\([^"]*\)".*/\1/p')
[ -n "$AT" ] && echo "登录成功 token=${#AT} 字符" || echo "登录失败"

echo
echo "=== 3. 已删除的接口应 404 ==="
curl -s -o /dev/null -w 'POST /api/im/read -> %{http_code}\n' -X POST \
  http://127.0.0.1:8081/api/im/read -H "Authorization: Bearer $AT" \
  -H 'Content-Type: application/json' -d '{"conv":"s_1_2","seq":1}'

echo
echo "=== 4. 会话列表（unread 应为 0）==="
curl -s "http://127.0.0.1:8081/api/im/conversations" -H "Authorization: Bearer $AT" | head -c 250
echo

echo
echo "=== 5. WS 发消息 + 推送日志 ==="
cd /opt/im
python3 ws-demo.py admin 123456 2 "多端部署验证 $(date +%H:%M:%S)" 2>&1 | tail -3
sleep 2
docker logs --tail=30 im-ws 2>&1 | grep -a "推送消息" | tail -2
