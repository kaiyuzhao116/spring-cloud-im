cd /opt/im
docker compose restart ws-gateway 2>&1 | tail -2
sleep 20
docker ps --filter name=im-ws --format '{{.Names}} {{.Status}}'
ss -lntp | grep 8089 | head -1
docker logs --tail=50 im-ws 2>&1 | grep -aiE "started|listen|8089" | tail -3
