echo "=== 服务器时间 ==="
date '+%Y-%m-%d %H:%M:%S'

echo "=== 发一条测试消息 ==="
AT=$(curl -s -X POST http://127.0.0.1:8081/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"account":"admin","password":"123456","deviceType":"WEB"}' \
  | sed -n 's/.*"accessToken":"\([^"]*\)".*/\1/p')

CID="tz-$(date +%s)"
curl -s -o /dev/null -w 'HTTP %{http_code}\n' -X POST http://127.0.0.1:8081/api/im/message \
  -H "Authorization: Bearer $AT" \
  -H 'Content-Type: application/json' \
  -d "{\"to\":2,\"cid\":\"$CID\",\"content\":\"时区测试\"}"

sleep 2

echo "=== 消息时间（应与上面的服务器时间一致）==="
docker exec campus-mysql mysql -uroot -p123456 -N -e \
  "use spring_cloud; select id, created_at from im_message order by id desc limit 2;" 2>/dev/null

echo "=== outbox ==="
docker exec campus-mysql mysql -uroot -p123456 -N -e \
  "use spring_cloud; select id, status, retry, created_at, sent_at from im_outbox order by id desc limit 3;" 2>/dev/null
