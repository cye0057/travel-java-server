# LEARNING.md — 项目学习指南（按代码教你怎么读）

> 本文档是**学习路线**，不是代码注释的搬运。建议顺序：先花 10 分钟看"一张图看懂请求流转"，
> 再按"第 2 节的学习路径"逐个文件读，最后用"第 4 节的动手任务"自测。
> 配合 `docs/benchmark.md`（压测报告）、`RESUME.md`（面试问答）一起看，知识就闭环了。

---

## 0. 这个项目是什么（30 秒版）

一个**不依赖 Spring AI / LangChain** 的 AI 应用后端：用 OkHttp 直连大模型 API，
自己实现了完整链路——SSE 流式对话、Redis 多轮记忆、Function Calling 工具调用、
Milvus 向量 RAG、用户登录与历史回看。33 个 Java 类、约 2200 行。

技术栈：Java 17 · Spring Boot 4.1 · MySQL 8 (JdbcTemplate) · Redis · Milvus 2.5 ·
SiliconFlow GLM-4.5V（对话）+ bge-m3（向量化）· OkHttp 4 · JMeter。

一句话定位：**"把 AI 后端的每一个环节都拆开、看懂、自己实现一遍"。**

---

## 1. 一张图看懂请求流转

```
浏览器 (Vue3 H5)
   │  fetch / axios
   ▼
nginx（部署时） / Vite 代理（开发时）        ── 只做转发，SSE 要关 proxy_buffering
   ▼
┌────────────────────────────────────────────────────────────┐
│ Controller 层（4个）：只做参数接收 + 路由，不写业务逻辑        │
│  TravelController      /api/travel/recommend + /chat (SSE)   │
│  UserController        /api/user/register|login|logout|me|history
│  KnowledgeController   /api/knowledge/reload|search          │
│  HotContentController  /api/hot/questions|cities (运营位)    │
└────────────────────────────────────────────────────────────┘
   │
   ▼
┌────────────────────────────────────────────────────────────┐
│ Service 层（业务逻辑中心）                                    │
│  TravelServiceImpl   ★核心：SSE编排 + 工具循环 + RAG拼接       │
│  UserService         注册/登录/Redis token                   │
│  HistoryService      MySQL+Redis 双层历史                    │
│  ChatMemoryService   Redis 会话记忆（短期滑动窗口）            │
│  KnowledgeService    ★RAG：Milvus 建库/检索                  │
│  EmbeddingService    文本 → 1024维向量（bge-m3）             │
│  AmapService         高德工具执行方（天气/POI）               │
│  CityAttractionService 本地工具执行方（城市预算）             │
└────────────────────────────────────────────────────────────┘
   │
   ▼
┌────────────────────────────────────────────────────────────┐
│ 工具层（你的手写"AI SDK"）                                    │
│  LLMutils   ★唯一的模型通信入口：chat/chatStream/请求体构造     │
└────────────────────────────────────────────────────────────┘
   │                          │
   ▼                          ▼
SiliconFlow API         高德 Web API / Redis / Milvus / MySQL
(对话+embedding)         （四个基础设施）
```

---

## 2. 学习路径（按这个顺序读，每步都有"要看懂什么"）

### 第 1 步：读 `TravelController` + `ChatRequestDTO` —— 认识"软鉴权"
先看入口长什么样。重点不是代码，是**设计决策**：

- `POST /api/travel/chat` 返回 `SseEmitter`——Spring 的异步响应对象，这就是"打字机"效果的来源。
- `produces = "text/event-stream"`——告诉浏览器这是 SSE，不能当普通 JSON 解析。
- 第 41-42 行：`resolveUserId` 返回 `null` 也能继续聊天。这是"软鉴权"——**登录用户 vs 游客的差别只在历史落不落库，功能都完整**。这种设计让产品转化路径极短：先体验、再注册。

**看到这里应该懂**：Controller 只做三件事——收参数、调 Service、返回结果。业务逻辑全部在 Service。

### 第 2 步：读 `LLMutils` —— 认识"手写 AI SDK"
这是整个项目的基石，**最值得精读的文件**。三个重点：

1. **OkHttp 超时设置（31-35 行）**：connect 60s / read 120s。120s 是因为 GLM 生成 2000+ token 的行程规划真的可能要两分钟。这个数字不是拍脑袋——它决定了整个项目的"最长等待预算"。
2. **`chatStream` 的流式解析（100-197 行）**：逐行读 `data: ` 前缀的 SSE 事件。注意 `tool_calls` 的**碎片式到达**——模型不是一次给完整工具调用，而是 `{index:0, id:"call_x"}` 先来，`{index:0, arguments:"{\"city\":"}` 后续碎片续上。所以用 `TreeMap<Integer, ...>` 按 index 累积，最后拼成完整调用（116-118 行 + 184-197 行）。
3. **`stripSpecialTokens`（204-209 行）**：GLM 会把 `|<|begin_of_box|>` 这种控制标记混进正文，正则剔除。**这是"真实踩坑后补的代码"**——你上课学不到，因为只有真连了模型才会看到。

**看到这里应该懂**：LLM 接口的本质是一个"会返回 JSON 的长超时 HTTP 请求"。所有 AI 框架（Spring AI 等）干的事，本质就是这里几十行：拼请求体、读流、解析、容错。

### 第 3 步：读 `ChatMessage` + `TravelServiceImpl.runWithTools` —— 认识 Function Calling
先读 `ChatMessage`（一个 record，四种 role 的静态工厂），再跳到 `TravelServiceImpl` 的 `runWithTools`（335-355 行）。

- 工具调用的**完整循环**：模型说"我要调 get_weather(杭州)" → 你执行 → 把结果作为 `role=tool` 消息回传 → 模型基于结果再答。`MAX_TOOL_ROUNDS = 3` 防止无限套娃。
- 第 344-352 行的注释解释了一个**连框架都不处理的坑**：SiliconFlow 拒绝"连续 tool 消息"（实测 400 code 20015）。解法是把 N 个并行调用拆成 N 对 `assistant(单个call) → tool(结果)`。这是面试的绝佳亮点——"我不但用框架，还知道框架底下协议的真实约束"。

**看到这里应该懂**：Function Calling 不是魔法，是"模型输出结构化意图 → 你执行 → 结果回灌"的循环。`TRAVEL_TOOLS`（72-102 行）里的 JSON-Schema 是写给模型看的说明书，description 写得越准，模型调得越对。

### 第 4 步：读 `ChatMemoryService` + `TravelServiceImpl.chat` —— 认识会话记忆
`chat()`（257-313 行）是**整个项目的枢纽**，把所有环节串起来：

1. `knowledgeService.retrieve(message)` —— RAG 检索（只查本轮问题，不进记忆）
2. `chatMemoryService.load(sessionId)` —— 读 Redis 历史
3. 拼消息列表 → `runWithTools` → 得最终答案
4. 答案非空才 `append` 进记忆（**空回答不写 = 防毒丸**，第 278-280 行）
5. 登录用户才 `historyService.persist`（MySQL 持久层，第 285-291 行）

`ChatMemoryService` 本身很短（88 行），但设计很讲究：
- **为什么用 Redis List 而不是 String**（17-22 行注释）：RPUSH/LTRIM/EXPIRE 都是单命令原子操作，不会出现"读-改-写"被并发插队的丢消息。
- **为什么 trim 用负数下标**（80 行）：`[-20, -1]` = 最后 20 条，滑动窗口一句话实现。
- **为什么每次写都重置 TTL**（82 行）：语义是"30 分钟不聊才忘"，不是"会话总寿命 30 分钟"。

**看到这里应该懂**：会话记忆 = "把模型的消息列表存起来，下轮再喂回去"。所有记忆系统（包括 LangChain 的 memory）本质都是这个，只是存哪、存多久、怎么裁的取舍不同。

### 第 5 步：读 `KnowledgeService` + `EmbeddingService` + 语料 —— 认识 RAG
先看 `src/main/resources/knowledge/*.md`（5 个城市的语料，注意里面**埋了虚构事实**——那是"针头测试"用的）。

然后读 `KnowledgeService`，对照类注释（35-42 行）里和 LangChain 的对应关系：

| LangChain | 本项目 |
|---|---|
| `DocumentLoader` | `loadChunks()`（222-253 行：按 `##` 小节切块） |
| `VectorStore.from_documents` | `reload()`（97-129 行：批量 embed + insert） |
| `similarity_search` | `retrieve()`（155-187 行：查询向量 + COSINE topK） |

**三个设计决策必须看懂**：
1. **切块加城市前缀**（第 248 行 `【杭州·美食】`）：前缀参与向量化，显著提高"杭州的xxx"的召回精度。这是调出来的，不是抄的。
2. **min-score 阈值 0.6**（58 行）：向量检索永远返回"最近的 K 条"，没内容也会硬凑。实测 bge-m3 相关命中 0.78+、无关 0.49~0.56，取 0.6 一刀切开。**阈值必须用自家模型实测，不能抄博客**。
3. **失败静默降级**（87-90、183-185 行）：Milvus 挂了 RAG 降级为纯模型回答，对话不断。所有外部依赖都该这么处理——**核心体验优先，增强功能可牺牲**。

### 第 6 步：读 `AmapService` + `CityAttractionService` —— 认识"工具执行方"
重点读 `AmapService` 类注释（17-26 行）的思考题答案：**为什么高德用 5s/10s 超时而 LLM 用 120s**。

两类下游的"慢"性质不同：LLM 慢是真在算，值得等；高德毫秒级接口 10s 不返基本是网络故障。共用 120s 会让一次高德抖动拖死整条 SSE。**超时参数本质是"我愿意为这个下游花多少等待预算"，必须按下游特性逐个定**——这是简历里"工程素养"的体现。

`getWeather`（54-91 行）内部是两次 HTTP 编排：城市名 → adcode → 天气。**复杂度藏在工具内部**，模型只看到一个"查天气"入口——这正是 Function Calling 的意义。

### 第 7 步：读 `UserService` + `AuthInterceptor` + `WebConfig` —— 认识用户体系
重点决策：**为什么用 Redis 不透明 token 而不是 JWT**（UserService 类注释 1-10 行）：

1. 项目已有 Redis（会话记忆），零新增基础设施；
2. 登出删 key 即**即时失效**，JWT 做不到（除非加黑名单）；
3. 每次请求 `expire` 滑动续期 = "7 天不活跃才掉线"。

再看 `Result.fail(code, msg)` 里那行注释（vo/Result.java）：**"之前漏了回显 code，导致 400/401 都被写成 500"**——这是 E2E 测试才抓得到的真实 bug（token 过期时前端收到 500 当普通错误，用户卡在"以为还登录着"）。

### 第 8 步：读 `HistoryService` + `HistoryCleanupJob` —— 认识"双存储一致性"
这里有个教科书级设计：**MySQL 是唯一事实源，Redis 只是读缓存**（Cache-Aside 模式）：

- 写对话 → MySQL 落库 → **主动 DEL 缓存**（invalidateCache）
- 读历史 → 先查 Redis，miss 则回源 MySQL + 回填缓存
- 过期 → MySQL 靠定时任务删（`@Scheduled` 每天 3 点），Redis 靠 TTL 自己蒸发

**为什么不用 Redis List 而用整串 JSON**（类注释）：列表/消息都要求"一次全取"，整串读写一次一命令，省掉 LRANGE 和半截列表的边界处理。数据量上限 50 会话/200 条，单值体积可控——**选型是对着数据特征选的**。

---

## 3. 六个"为什么"速查（面试/答辩高频）

| 问题 | 答案（一句话） | 代码位置 |
|---|---|---|
| 为什么不直接用 Spring AI？ | 框架掩盖了协议细节，手写才能定位/修复"上游拒连续tool消息"这类真问题 | LLMutils + TravelServiceImpl 注释 |
| 为什么 Redis 记忆用 List？ | RPUSH/LTRIM/EXPIRE 单命令原子，避免读改写被并发插队 | ChatMemoryService 17-22 行 |
| 为什么 RAG 阈值 0.6？ | 实测 bge-m3 分数分两群（相关 0.78+/无关 0.49~0.56），取中间值切开 | KnowledgeService 58 行 |
| 为什么高德超时 5s/10s，LLM 120s？ | 超时=等待预算，LLM 慢是真算值得等，高德毫秒级不返=故障 | AmapService 17-26 行 |
| 为什么 token 用 Redis 不用 JWT？ | 可即时吊销 + 滑动续期，零新增基础设施 | UserService 1-10 行 |
| 为什么历史用 MySQL+Redis 双层？ | MySQL 是事实源，Redis 是读缓存，写后失效保证一致性 | HistoryService 类注释 |

---

## 4. 动手任务（自测是否真的看懂了）

按难度递增，做完最后一个你就真懂这个项目了：

1. **改 RAG 阈值**：把 `application.yml` 的 `rag.min-score` 改成 0.9，重启，问一个库内问题——回答会变差（召回被全滤）。改回 0.6。**这验证了阈值是"一刀"不是摆设。**
2. **加一个工具**：仿照 `get_weather` 在 `AmapService` 加 `get_traffic`（查城市拥堵指数），在 `TRAVEL_TOOLS` 注册，`canonicalToolName` 加别名映射。**验证工具循环全链路你已掌握。**
3. **复现串行化坑**：临时把 `runWithTools` 的循环改成"一次回传全部 tool 消息"，重启问"杭州天气和西湖美食"——大概率复现上游 400 20015。**改回来，这是最有价值的实验。**
4. **针头测试**：在 `knowledge/杭州.md` 里加一句虚构事实（比如"杭州地铁 3 号线终点站是桂花站"），`POST /api/knowledge/reload`，问"杭州 3 号线终点站"，答对=检索链路通。
5. **读缓存验证**：登录聊几句 → `redis-cli` 看 `hist:index:{userId}` 有值 → 再聊一句（写操作）→ 该 key 消失（写后失效）。**把 Cache-Aside 看进眼里。**

---

## 5. 目录速查表（33 个类一页纸）

| 包 | 类 | 职责 | 精读度 |
|---|---|---|---|
| utils | **LLMutils** | 唯一模型通信入口：请求体构造/流式解析/工具碎片累积 | ★★★★★ |
| service.impl | **TravelServiceImpl** | 枢纽：SSE 编排/RAG 拼接/工具循环/记忆与持久化 | ★★★★★ |
| service | **KnowledgeService** | Milvus 建库/切块/检索/阈值/降级 | ★★★★★ |
| service | ChatMemoryService | Redis 会话记忆滑动窗口 | ★★★★ |
| service | EmbeddingService | 文本→1024 维向量，批量+显式排序 | ★★★★ |
| service | AmapService | 高德工具执行方：天气两次编排/POI/短超时 | ★★★★ |
| service | CityAttractionService | 本地城市数据工具 | ★★ |
| service | UserService | 注册登录/BCrypt/Redis token | ★★★★ |
| service | HistoryService | MySQL+Redis 双层历史，Cache-Aside | ★★★★ |
| task | HistoryCleanupJob | 每日凌晨 3 点清理 7 天前历史 | ★★ |
| dto | ChatMessage | 四种 role 的领域模型 + ToolCall/FunctionCall | ★★★★ |
| dto | ChatRequestDTO 等 | 请求参数校验 | ★ |
| vo | Result | 统一返回体（code/messages/data），注意 fail() 回填 bug | ★★★ |
| vo | StreamChunkVO 等 | SSE 分块协议三件套 | ★★ |
| config | WebConfig + AuthInterceptor | 鉴权拦截器挂载/软鉴权入口 | ★★★ |
| common | GlobalExceptionHandler | 全局异常 → 业务码（400/401），HTTP 永远 200 | ★★★ |
| controller | Travel/User/Knowledge/Hot | 四入口，只收参不写逻辑 | ★★ |

---

## 6. 学习配套资料（都在仓库里）

- `README.md` —— 项目总览、API 一览、快速开始
- `docs/benchmark.md` —— JMeter 三轮压测报告（结论：瓶颈在模型推理不在应用代码）
- `RESUME.md` —— 简历三档写法 + STAR 面试问答（7 个高频问题带答案）
- `deploy/DEPLOY.md` —— VM 部署指南 + 踩坑记录（含"连接被拒绝≠防火墙"这类排障心法）
- `NEXT.md` —— 交接便签：每个阶段干了什么、为什么这么干（面试话术）
- `jmeter/travel-loadtest.jmx` —— 压测计划文件，可用 JMeter 打开看负载模型

> 建议学习顺序：LEARNING.md（本文）→ 按第 2 节路径读代码 → docs/benchmark.md →
> RESUME.md 对着代码验证每个"亮点"都是真的 → 动手任务自测。
