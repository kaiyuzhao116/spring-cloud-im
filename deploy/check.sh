echo "=== 监听端口 ==="
ss -lntp | grep -E ':(18080|8089|8083|8084|8085|8848)' | sed 's/.*LISTEN[^:]*://' | cut -d' ' -f1 | sort -u

echo
echo "=== 容器 ==="
docker ps --filter name=im- --format '{{.Names}} {{.Status}}'

echo
echo "=== 服务日志（各取最后 8 行）==="
for c in im-user im-auth im-im im-gateway im-ws; do
  echo "----- $c -----"
  docker logs --tail=8 "$c" 2>&1 | tail -8
done
