echo "=== 重启 user / im ==="
cd /opt/im
docker compose restart user im 2>&1 | tail -4

echo "=== 等 30 秒 ==="
sleep 30

echo "=== 健康检查 ==="
curl -s -o /dev/null -w 'user(8084): %{http_code}\n' http://127.0.0.1:8084/actuator/health
curl -s -o /dev/null -w 'im(8085)  : %{http_code}\n' http://127.0.0.1:8085/actuator/health

echo "=== 启动日志 ==="
docker logs --tail=200 im-im 2>&1 | grep -a "Started ImApplication" | tail -1
docker logs --tail=200 im-user 2>&1 | grep -a "Started" | tail -1
