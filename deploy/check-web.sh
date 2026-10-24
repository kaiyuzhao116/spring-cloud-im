echo "=== index.html 内容 ==="
curl -s http://127.0.0.1:8081/ 

echo
echo "=== 目录 ==="
ls -l /opt/im/web /opt/im/web/assets

echo
echo "=== 静态资源状态 ==="
for f in $(curl -s http://127.0.0.1:8081/ | grep -o '/assets/[^"]*'); do
  code=$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:8081$f")
  echo "$f -> $code"
done

echo
echo "=== nginx 容器日志（最后 5 行）==="
docker logs --tail=5 im-web 2>&1
