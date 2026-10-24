cd /opt/im
chmod -R 755 /opt/im/web
chmod 644 /opt/im/web/index.html /opt/im/web/assets/*

echo "=== 重启 ws-gateway ==="
docker compose restart ws-gateway 2>&1 | tail -2
sleep 20
docker logs --tail=50 im-ws 2>&1 | grep -a "Started WsGatewayApplication" | tail -1

echo
echo "=== 乱序投递测试：先发 seq=101，再发 seq=100 ==="
PUB() {
  curl -s -u admin:123456 -X POST http://127.0.0.1:15672/api/exchanges/%2F/im.events/publish \
    -H 'Content-Type: application/json' \
    -d "{\"properties\":{},\"routing_key\":\"message.created\",\"payload\":\"{\\\"msgId\\\":$1,\\\"conversationId\\\":\\\"s_1_2\\\",\\\"seq\\\":$1,\\\"fromUid\\\":2,\\\"toUid\\\":1,\\\"content\\\":\\\"order-test-$1\\\",\\\"ts\\\":1}\",\"payload_encoding\":\"string\"}"
  echo
}

PUB 101
sleep 1
PUB 100
sleep 1

echo "=== 日志：两条都应命中（不能因为 100 < 101 就丢）==="
docker logs --tail=40 im-ws 2>&1 | grep -a "seq=10" | tail -4

echo
echo "=== 重复投递测试：再发一次 101（应被跳过）==="
PUB 101
sleep 1
docker logs --tail=10 im-ws 2>&1 | grep -a "seq=101" | tail -2
