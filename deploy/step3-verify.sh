#!/bin/bash
echo "=== 容器状态 ==="
docker ps --filter name=im- --format '{{.Names}}\t{{.Status}}'

echo
echo "=== 内存 ==="
free -h | grep -iE 'mem|swap'

echo
echo "=== 健康检查 ==="
curl -s -o /dev/null -w 'web(8081)      : %{http_code}\n' http://127.0.0.1:8081/
curl -s -o /dev/null -w 'gateway(18080) : %{http_code}\n' http://127.0.0.1:18080/actuator/health
curl -s -o /dev/null -w 'ws(8089)       : %{http_code}\n' http://127.0.0.1:8089/

echo
echo "=== 登录测试 ==="
curl -s -X POST http://127.0.0.1:8081/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"account":"admin","password":"123456","deviceType":"WEB"}' | head -c 400
echo
