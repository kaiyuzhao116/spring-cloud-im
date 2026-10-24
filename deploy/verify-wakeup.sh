cd /opt/im
docker compose restart im 2>&1 | tail -2
sleep 25
docker logs --tail=100 im-im 2>&1 | grep -a "Started ImApplication" | tail -1

echo "=== 发消息后立即查 outbox（不等 1 秒）==="
AT=$(curl -s -X POST http://127.0.0.1:8081/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"account":"admin","password":"123456","deviceType":"WEB"}' \
  | sed -n 's/.*"accessToken":"\([^"]*\)".*/\1/p')

CID="wake-$(date +%s)"
START=$(date +%s%3N)
curl -s -o /dev/null -X POST http://127.0.0.1:8081/api/im/message \
  -H "Authorization: Bearer $AT" -H 'Content-Type: application/json' \
  -d "{\"to\":2,\"cid\":\"$CID\",\"content\":\"唤醒测试\"}"

# 立刻查，不 sleep
docker exec campus-mysql mysql -uroot -p123456 -N -e \
  "use spring_cloud; select id, status, round(timestampdiff(microsecond, created_at, sent_at)/1000) as cost_ms from im_outbox order by id desc limit 1;" 2>/dev/null
END=$(date +%s%3N)

echo "客户端往返耗时: $((END-START)) ms"
