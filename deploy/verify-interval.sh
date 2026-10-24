cd /opt/im
docker compose restart im 2>&1 | tail -2
sleep 25
docker logs --tail=100 im-im 2>&1 | grep -a "Started ImApplication" | tail -1

echo "=== 连发 3 条，看延迟（扫表已放宽到 5 秒，延迟应仍很短）==="
AT=$(curl -s -X POST http://127.0.0.1:8081/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"account":"admin","password":"123456","deviceType":"WEB"}' \
  | sed -n 's/.*"accessToken":"\([^"]*\)".*/\1/p')

for i in 1 2 3; do
  CID="iv-$(date +%s)-$i"
  curl -s -o /dev/null -X POST http://127.0.0.1:8081/api/im/message \
    -H "Authorization: Bearer $AT" -H 'Content-Type: application/json' \
    -d "{\"to\":2,\"cid\":\"$CID\",\"content\":\"间隔测试$i\"}"
done

sleep 1
docker exec campus-mysql mysql -uroot -p123456 -e \
  "use spring_cloud; select id, status, timestampdiff(microsecond, created_at, sent_at) as cost_us from im_outbox order by id desc limit 3;" 2>/dev/null
