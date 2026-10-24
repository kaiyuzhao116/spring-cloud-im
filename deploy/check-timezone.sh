echo "=== 服务器时间 ==="
date

echo "=== MySQL 时间与时区 ==="
docker exec campus-mysql mysql -uroot -p123456 -N -e "select now(), @@global.time_zone, @@session.time_zone;"

echo "=== 最新消息的 created_at ==="
docker exec campus-mysql mysql -uroot -p123456 -N -e "use spring_cloud; select id, from_uid, left(content,10), created_at from im_message order by id desc limit 3;"

echo "=== outbox 时间 ==="
docker exec campus-mysql mysql -uroot -p123456 -N -e "use spring_cloud; select id, created_at, sent_at from im_outbox order by id desc limit 3;"
