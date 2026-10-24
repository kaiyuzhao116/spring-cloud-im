echo "=== 让预置账号两两成为好友（方便演示）==="
docker exec campus-mysql mysql -uroot -p123456 -e "
use spring_cloud;
INSERT IGNORE INTO im_friend (uid, friend_uid, status)
SELECT a.id, b.id, 1
FROM sys_user a JOIN sys_user b ON a.id <> b.id
WHERE a.status = 1;
" 2>/dev/null

echo "=== 好友关系数量 ==="
docker exec campus-mysql mysql -uroot -p123456 -N -e \
  "use spring_cloud; select count(*) from im_friend;" 2>/dev/null

echo "=== 小明的好友 ==="
docker exec campus-mysql mysql -uroot -p123456 -N -e \
  "use spring_cloud; select f.friend_uid, u.nickname from im_friend f join sys_user u on u.id=f.friend_uid where f.uid=3;" 2>/dev/null
