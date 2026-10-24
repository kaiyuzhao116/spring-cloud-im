echo "=== im_outbox 表 ==="
docker exec campus-mysql mysql -uroot -p123456 -N -e "use spring_cloud; show tables like 'im_outbox';" 2>/dev/null

echo "=== im 容器与健康 ==="
docker ps --filter name=im-im --format '{{.Status}}'
curl -s -o /dev/null -w 'health: %{http_code}\n' http://127.0.0.1:8085/actuator/health

echo "=== im 关键日志 ==="
docker logs --tail=300 im-im 2>&1 | grep -iE "Started ImApplication|outbox|Scheduling|ERROR" | tail -6
