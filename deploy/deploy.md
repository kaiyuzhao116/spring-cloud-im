# 部署到腾讯云轻量服务器

目标服务器：`82.157.185.69`（CentOS 7.6 + Docker 26）

## 一、先做体检（很重要）

```bash
# 1. 内存：本项目约需 2.5~3G（6 个 JVM + MySQL + Nacos + Redis + RabbitMQ）
free -h

# 2. 已占用端口（有冲突就要改）
ss -lntp | grep -E ':(80|8080|8081|8089|3306|5672|6379|8848)\b'

# 3. 已有容器
docker ps --format '{{.Names}}\t{{.Ports}}'
```

**如果内存 ≤ 2G**，请先看本文最后的「内存不够时」。

## 二、上传

本地打包（在项目根目录执行）：

```bash
# ① 构建后端（生成 5 个 jar）
mvn -q -DskipTests package

# ② 构建前端
cd web && npm run build && cd ..

# ③ 组装部署包
rm -rf deploy/jars deploy/web deploy/db
mkdir -p deploy/jars deploy/db
cp user/target/user-*.jar                 deploy/jars/user.jar
cp auth/target/auth-*.jar                 deploy/jars/auth.jar
cp im/target/im-*.jar                     deploy/jars/im.jar
cp api-gateway/target/api-gateway-*.jar   deploy/jars/api-gateway.jar
cp ws-gateway/target/ws-gateway-*.jar     deploy/jars/ws-gateway.jar
cp -r web/dist                            deploy/web
cp db/schema.sql db/im_schema.sql         deploy/db/

# ④ 打成一个包
tar czf im-deploy.tar.gz -C deploy .
```

上传到服务器：

```bash
scp im-deploy.tar.gz root@82.157.185.69:/opt/
```

> 也可以用轻量云的「文件管理」或 OrcaTerm 拖拽上传。

## 三、启动

```bash
ssh root@82.157.185.69
mkdir -p /opt/im && cd /opt/im
tar xzf /opt/im-deploy.tar.gz

# 首次启动（MySQL 初始化建表需要 30~60 秒）
docker compose up -d

# 看日志
docker compose logs -f --tail=50 im-nacos
docker compose logs -f --tail=50 im-im
```

## 四、放行端口

轻量云控制台 → 防火墙 → 添加规则：

| 端口 | 用途 |
|---|---|
| **8081** | 前端 + API + WebSocket（nginx 统一入口） |

**只开 8081 就够了**（8080/8089 由 nginx 反代，不需要对外）。
如果你想让 Apifox 直接调网关，再放行 8080。

## 五、验证

```
浏览器打开  http://82.157.185.69:8081
账号 admin / 123456
发消息 → 看是否实时（WS 走的是同源 /ws，不需要额外端口）
```

命令行验证：

```bash
curl http://127.0.0.1:8080/actuator/health
curl -X POST http://127.0.0.1:8081/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"account":"admin","password":"123456","deviceType":"WEB"}'
```

## 六、常用运维

```bash
docker compose ps                     # 状态
docker compose logs -f im-gateway     # 某个服务日志
docker compose restart im-im          # 重启单个
docker compose down                   # 全停（数据在 ./data 里，不会丢）
docker compose up -d                  # 全启

docker compose logs im-im | grep ERROR   # 找错误
```

## 七、复用已有中间件

服务器上已经有 `campus-rabbitmq` 等容器。两种选择：

**A. 继续用自带的（推荐，互相隔离）**

如果 5672 端口被占，改 `docker-compose.yml` 里的 rabbitmq 端口比较麻烦（host 网络不能改端口映射）——
这种情况建议直接复用已有的，见 B。

**B. 复用已有 RabbitMQ**

删掉 compose 里的 `rabbitmq:` 整段，然后确认已有实例的账号密码与
`ws-gateway/src/main/resources/application.yml`、`im/.../application.yml` 里的
`spring.rabbitmq.username/password` 一致（默认 guest/guest，但 guest 只允许本机访问，
容器化后需确认没被限制）。

## 八、内存不够时（≤ 2G）

按优先级砍：

1. **不开 Nacos 容器**，改用轻量云上已有的注册中心（若有）；
2. 把 MySQL 换成已有的实例；
3. 只跑「能演示」的最小集合：`mysql + redis + rabbitmq + nacos + im + ws-gateway + web`
   （不启 auth/user/api-gateway 时，登录走不通，只能看 WS 链路，**不建议**）；
4. **升级服务器到 4G**（最省事，Lighthouse 支持按小时升级）。

## 九、端口占用对照

| 端口 | 谁用 |
|---|---|
| 8081 | nginx（对外唯一入口） |
| 8080 | api-gateway |
| 8089 | ws-gateway |
| 8083 / 8084 / 8085 | auth / user / im |
| 20883 / 20885(+1) | im / ws-gateway 的 Dubbo |
| 3306 / 6379 / 5672 / 15672 / 8848 | MySQL / Redis / RabbitMQ / Nacos |
