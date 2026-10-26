cd /opt/im
chmod -R 755 /opt/im/web
chmod 644 /opt/im/web/index.html /opt/im/web/assets/*

echo "=== 重启 im / ws-gateway（契约删了 recall）==="
docker compose restart im ws-gateway 2>&1 | tail -4

echo "=== 等 30 秒 ==="
sleep 30

echo "=== 容器状态 ==="
docker ps --filter name=im- --format '{{.Names}} {{.Status}}'

echo "=== 启动日志 ==="
docker logs --tail=200 im-im 2>&1 | grep -a "Started ImApplication" | tail -1
docker logs --tail=200 im-ws 2>&1 | grep -a "Started WsGatewayApplication" | tail -1
