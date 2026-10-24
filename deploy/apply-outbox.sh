echo "=== 1. 建 im_outbox 表 ==="
docker exec -i campus-mysql mysql -uroot -p123456 --default-character-set=utf8mb4 < /opt/im/db/im_outbox.sql 2>&1 | grep -v Warning
docker exec campus-mysql mysql -uroot -p123456 -N -e "use spring_cloud; show tables like 'im_outbox';" 2>/dev/null

echo "=== 2. 重启 im ==="
cd /opt/im && docker compose restart im-im 2>&1 | tail -3

echo "=== 3. 等待启动 ==="
for i in $(seq 1 20); do
  code=$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8085/actuator/health)
  if [ "$code" = "200" ]; then echo "im 已就绪（$((i*3))s）"; break; fi
  sleep 3
done

echo "=== 4. 启动日志里的关键行 ==="
docker logs --tail=200 im-im 2>&1 | grep -iE "Started ImApplication|Outbox|Scheduled" | tail -5
