# travel-java-server — AI 旅游助手后端

个人学习/求职项目：不依赖 Spring AI、LangChain 等封装框架，用 **OkHttp 直连大模型 API** 手写
完整的 AI 应用后端链路，覆盖对话协议、上下文工程、检索增强、工具调用、用户体系与性能验证。

## 核心能力

| 模块 | 说明 | 技术要点 |
|---|---|---|
| 行程规划 | `POST /api/travel/recommend` 结构化 JSON 行程 | Prompt 工程 + Jackson 容错解析（剥 ```json 围栏） |
| SSE 分块推送 | `POST /api/travel/chat` 分块推送最终答案 | 手写 SSE 协议解析（`data:`/`[DONE]`/控制标记剥离），有界线程池 + CallerRunsPolicy 背压；工具轮次先缓冲，避免中间态外泄 |
| 多轮会话记忆 | Redis List 滑动窗口（LTRIM+EXPIRE） | 30 分钟空闲过期、20 条上限、空回答不落库防毒丸、孤儿消息头过滤 |
| Function Calling | 3 个工具：城市预算/高德天气/高德POI | JSON-Schema 工具注册、流式 tool_calls 碎片按 index 累积、**并行调用串行化回传**（规避上游 tool→tool 校验缺陷）、工具名幻觉容错 |
| RAG 知识库 | Milvus 向量检索注入系统提示词 | bge-m3 1024 维、按小节切块、Cache-Aside、相似度阈值校准（0.6）、needle 法验证检索有效性 |
| 用户中心 | 注册/登录/登出、唯一 userCode、7 天历史回看 | BCrypt 哈希、Redis 不透明 token（滑动续期/即时吊销）、MySQL+Redis 双写历史、归属校验、每日定时清理 |
| 运营位 | 热门问题/热门城市数据库化 | hot_question/hot_city 表 + INSERT IGNORE 幂等种子 |

## 技术栈

Java 17 · Spring Boot 4.1 · MySQL 8（JdbcTemplate） · Redis 8（会话/令牌/缓存） · Milvus 2.5（向量库） ·
SiliconFlow GLM-4.5V（对话）+ BAAI/bge-m3（向量化） · OkHttp 4 · JMeter 5.6（压测） · Vue3 + Vite + Vant（配套 H5 前端，另库）

## 快速开始

前置：JDK17、MySQL8、Redis、Milvus（standalone）。

```bash
# 1. 配置本地密钥：复制模板并填入真实值（该文件已被 .gitignore）
cp src/main/resources/application-dev-example.yml src/main/resources/application-dev.yml

# 2. 建库（schema 与应用内自动初始化，只需建库）
mysql -uroot -p -e "CREATE DATABASE IF NOT EXISTS travel_db DEFAULT CHARSET utf8mb4"

# 3. 启动
./mvnw spring-boot:run
```

启动后访问 `http://localhost:8080/api/hot/questions` 验证；RAG 首次启动自动向量化语料入库
（`classpath:knowledge/*.md`），改语料后 `POST /api/knowledge/reload` 重建。

> 密钥缺失时应用会在启动期崩溃：`LLM_API_KEY`/`AMAP_KEY` 缺失直接报
> `Could not resolve placeholder`（@Value 硬解析）；`DB_PASSWORD` 缺失表现为启动时数据库连接
> Access denied。总之不会带病上线——这是刻意的 fail-fast。

## API 一览

| 方法 | 路径 | 鉴权 | 说明 |
|---|---|---|---|
| POST | /api/travel/recommend | - | 生成结构化行程（JSON） |
| POST | /api/travel/chat | 软鉴权 | SSE 分块推送；带 token 则对话进 7 天历史，游客不落库 |
| GET/POST | /api/hot/questions, /api/hot/cities | - | 运营位数据 |
| GET | /api/knowledge/search?q=&k= | - | RAG 召回调试（分数/出处） |
| POST | /api/knowledge/reload | - | 重建向量库 |
| POST | /api/user/register, /login, /logout | 登出需 | 注册即登录，返回 token + userCode |
| GET | /api/user/me, /history, /history/{sid} | Bearer | 用户信息 / 历史会话列表 / 回放 |

## 密钥与部署说明

- 仓库内所有 yml **零真实密钥**：业务密钥经 `${ENV_VAR}` 占位符注入（本地 application-dev.yml，服务器环境变量 / docker `env_file`）；
- 高德 key 请在控制台配置 **IP 白名单**，即使泄露也无法被盗用；
- 部署包（Dockerfile + docker-compose）见 `deploy/` 目录。

## 压测摘要（JMeter 5.6，本机环境）

见 `jmeter/` 下测试计划与报告：瓶颈定位在模型推理侧（非应用代码），经线程池化与工具串行化改造后的
对比数据见 `docs/benchmark.md`。
