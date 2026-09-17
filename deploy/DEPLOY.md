# 部署指南（VM 局域网版）

目标环境：本机虚拟机（Linux + Docker，IP 192.168.11.194），**复用**其中已有的
`redis` 与 `milvus-standalone` 容器，只新增 MySQL、后端、前端三件套。

## 前提自检（在 VM 上执行）

```bash
docker --version                       # 需 20.10+（compose v2 子命令 + host-gateway 支持）
docker compose version
docker ps                              # 应看到 redis(6379) 与 milvus(19530) 在跑
ss -tlnp | grep -E ':80 |:8080 '       # 80/8080 应空闲，被占则改 compose 端口映射
```

### 硬前置：`redis` 与 `milvus-standalone` 必须已经在跑，不通过就别往下走

后端启动期就要连 Redis（注册/登录/会话记忆/历史）和 Milvus（RAG 检索）：

- **Redis 不在线** → 注册/登录返回 **500**，后端日志 `java.net.ConnectException: ... Connection refused`，
  应用日志写明 `登录服务暂不可用（Redis未启动）`。注意 `hot/questions` 这类只读 MySQL 的接口**仍然正常**，
  所以会出现"页面能开、热门问题能显示、就是注册不了"这种迷惑组合。
- **Milvus 不在线** → RAG 检索静默降级（不报错，但回答不再引用语料）。

**这两个容器没有 restart policy，VM 重启后不会自己回来，必须手动拉起**（首次部署实测就卡在这里：
三个新容器全 Up、MySQL healthy，接口却 500，排查一圈才发现是 Redis 没起）。

```bash
docker ps -a --format 'table {{.Names}}\t{{.Status}}'   # 停止的容器只在 -a 里可见
docker start redis milvus-standalone                    # 容器名以实际输出为准

# 验证端口真的有人监听
timeout 3 bash -c 'echo > /dev/tcp/127.0.0.1/6379'  && echo "6379 OK"  || echo "6379 FAIL"
timeout 3 bash -c 'echo > /dev/tcp/127.0.0.1/19530' && echo "19530 OK" || echo "19530 FAIL"
```

> 排障要点：**"连接被拒绝"（ECONNREFUSED）说明那个端口上根本没有进程在听，是服务没起，
> 不要往防火墙方向查。** ufw 的 `deny` 是静默丢包，症状是**超时挂起**而非立刻拒绝——
> 这两者的区别能省掉一整轮误判。

国内网络拉 `mysql:8.0`/`node:22`/`nginx` 镜像慢或失败时，二选一：
给 VM 配代理（临时 `export https_proxy=http://宿主机IP:7890`），
或在 `/etc/docker/daemon.json` 配 registry-mirrors 后 `systemctl restart docker`。

## 部署步骤

```bash
# 1) 取代码（私有仓库需 GitHub 账号；嫌麻烦可在网页 Code→Download ZIP 后传入VM）
git clone https://github.com/cye0057/travel-java-server.git

# 2) 前端仓库需与本仓库【同级目录】（compose 里 build context 是 ../../trval-h5）
git clone https://github.com/cye0057/trval-h5.git

# 3) 密钥注入：compose 自动读同目录 .env
cd travel-java-server/deploy
cp .env.example .env
vi .env        # 填 DB_PASSWORD(自定) / LLM_API_KEY / AMAP_KEY 三个值

# 4) 先校验 compose 语法：变量没填、占位符写坏都会在这里报出来，比 up 到一半失败快得多
docker compose -f docker-compose.lan.yml config > /dev/null && echo "语法 OK"

# 5) 构建并启动（首次构建含 npm ci + mvn package；国内直连 20~40 分钟，配好镜像源可大幅缩短）
docker compose -f docker-compose.lan.yml up -d --build

# 6) 验活
docker compose -f docker-compose.lan.yml ps            # 全部 Up/healthy
curl -s http://localhost/api/hot/questions | head -c 80   # 经 nginx 反代的后端
curl -s http://localhost:8080/api/user/register -H "Content-Type: application/json" \
  -d '{"username":"smoke1","password":"test1234"}'        # 端到端含 Redis 写入，最有效的验活
```

> 变量写法只用最朴素的 `${VAR}`，**不要加冒号修饰**：曾出现过被写坏成字面星号，
> compose 报 `invalid interpolation format for services.xxx.environment.XXX`，排查成本很高。
> 上面第 4 步的 `config` 就是为此准备的，别省。

浏览器访问：VM 内 `http://localhost`，局域网任意设备 `http://VM的IP`（本文档场景是 `192.168.11.194`）。

## VM 换了网络环境（IP 变化）怎么办

Redis/Milvus 地址已参数化为 `${REDIS_HOST}`，默认 `192.168.11.194`。VM 拿到新 IP 后：

```bash
cd travel-java-server/deploy
echo "REDIS_HOST=新IP" >> .env          # 例：echo "REDIS_HOST=192.168.1.4" >> .env
# 确认 .env 里已有这一行，再重建 backend（环境变量变了，必须 --force-recreate）
docker compose -f docker-compose.lan.yml up -d --force-recreate backend
docker compose -f docker-compose.lan.yml logs --tail=15 backend
```

浏览器访问地址也相应变成 `http://新IP`。注意之前注册过的用户名在新 IP 下仍算"已存在"，
验活时用新用户名。

## 首次启动会发生什么（都是预期行为）

| 组件 | 行为 |
|---|---|
| MySQL 容器 | 空库 travel_db，应用启动时 schema.sql 自动建 5 张表 + 运营位种子 |
| Milvus（复用） | 检测到 travel_knowledge 集合已存在 → 日志"跳过建库"，**不重复花 embedding 钱** |
| Redis（复用） | token/会话记忆/历史缓存直接落现有实例 |
| 用户数据 | 全新空库=干净上线；本机 MySQL 里的测试账号不迁移（本就是冒烟数据） |

## 日常运维命令

```bash
docker compose -f docker-compose.lan.yml logs -f backend      # 看后端日志
docker compose -f docker-compose.lan.yml restart backend      # 后端改配置后重启
docker compose -f docker-compose.lan.yml up -d --build backend # 代码更新后重建（先 git pull）
```

## 已知边界（面试被问就答这个）

- 单实例部署，无 HTTPS/域名/备案——局域网 demo 级；上公网需反代+证书+域名；
- 无限流与验证码，公网裸奔会被刷 LLM 配额（高德 key 记得在控制台配 IP 白名单兜底）；
- nginx 已为 SSE 关闭 `proxy_buffering`，若未来加 CDN 需确认其流式转发能力。
- **前端在 `http://局域网IP` 下属于"非安全上下文"**：`crypto.randomUUID`、`crypto.subtle`、
  `navigator.clipboard`、Service Worker 这些 Web API 只在 HTTPS 或 localhost 存在。
  对话页曾因此在局域网 IP 下**整页白屏**，而本机 `localhost:5173` 开发完全测不出
  （localhost 被浏览器特殊豁免为可信），现已用 `crypto.getRandomValues` 兜底生成会话 id。
  后续若引入上述其它 API，同样需要兜底，或给站点上 HTTPS。
