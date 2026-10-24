PAYLOAD='{"msgId":1,"conversationId":"s_1_2","seq":99999,"fromUid":2,"toUid":1,"content":"dup-test","ts":1}'
BODY=$(python3 -c "import json,sys; print(json.dumps({'properties':{},'routing_key':'message.created','payload':sys.argv[1],'payload_encoding':'string'}))" "$PAYLOAD" 2>/dev/null || echo '')

if [ -z "$BODY" ]; then
  # 没有 python3 就手写转义
  BODY="{\"properties\":{},\"routing_key\":\"message.created\",\"payload\":\"{\\\"msgId\\\":1,\\\"conversationId\\\":\\\"s_1_2\\\",\\\"seq\\\":99999,\\\"fromUid\\\":2,\\\"toUid\\\":1,\\\"content\\\":\\\"dup-test\\\",\\\"ts\\\":1}\",\"payload_encoding\":\"string\"}"
fi

echo "=== 第 1 次投递（应正常推送）==="
curl -s -u admin:123456 -X POST http://127.0.0.1:15672/api/exchanges/%2F/im.events/publish \
  -H 'Content-Type: application/json' -d "$BODY"
echo

sleep 1

echo "=== 第 2 次投递（同样的 seq，应被跳过）==="
curl -s -u admin:123456 -X POST http://127.0.0.1:15672/api/exchanges/%2F/im.events/publish \
  -H 'Content-Type: application/json' -d "$BODY"
echo

sleep 1

echo "=== ws-gateway 日志（看 seq=99999 的两次处理）==="
docker logs --tail=60 im-ws 2>&1 | grep -a "99999" | tail -5
