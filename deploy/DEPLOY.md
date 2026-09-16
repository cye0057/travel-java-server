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

# 4) 构建并启动（首次构建含 npm install + mvn package，约 5~10 分钟）
docker compose -f docker-compose.lan.yml up -d --build

# 5) 验活
docker compose -f docker-compose.lan.yml ps            # 全部 Up/healthy
curl -s http://localhost/api/hot/questions | head -c 80   # 经 nginx 反代的后端
```

浏览器访问：VM 内 `http://localhost`，局域网任意设备 `http://192.168.11.194`。

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
