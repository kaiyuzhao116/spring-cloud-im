echo "=== 1. 注册新账号 xiaozhao ==="
curl -s -X POST http://127.0.0.1:8081/api/auth/register \
  -H 'Content-Type: application/json' \
  -d '{"account":"xiaozhao","password":"abc12345","nickname":"小赵","deviceType":"WEB"}' | head -c 300
echo

echo
echo "=== 2. admin 登录 ==="
AT=$(curl -s -X POST http://127.0.0.1:8081/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"account":"admin","password":"123456","deviceType":"WEB"}' \
  | sed -n 's/.*"accessToken":"\([^"]*\)".*/\1/p')

echo "=== 3. admin 的好友列表（应有小赵）==="
curl -s http://127.0.0.1:8081/api/im/friends -H "Authorization: Bearer $AT" | head -c 400
echo

echo
echo "=== 4. 数据库确认 im_friend ==="
docker exec campus-mysql mysql -uroot -p123456 -N -e \
  "use spring_cloud; select uid, friend_uid from im_friend order by uid;" 2>/dev/null
