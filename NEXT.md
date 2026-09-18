# NEXT.md — 交接便签（新会话第一句：请先读 NEXT.md）

> 一页纸恢复全部上下文。不进 git。最后更新：2026-09-16

## 0. 环境

- **后端** `D:\study_xue\JAVA_code\travel-java-server`：Spring Boot 4.1 + Java 17，OkHttp 直连 SiliconFlow
  - 接口：`POST /api/travel/recommend`、`POST /api/travel/chat`(SSE)、`POST /api/knowledge/reload`、`GET /api/knowledge/search`
  - 启动：`cd travel-java-server && ./mvnw spring-boot:run`；冒烟测试加 `'-Dspring-boot.run.arguments=--server.port=8081'` 避开 8080
- **前端** `D:\study_xue\Trae CN\code\project\trval-h5`：Vue3+Vite+Vant，代理 /api → localhost:8080；`npm run dev`（5173，多次测试会残留 5174/5175 旧进程）
- **基础设施在 Docker**：Redis 192.168.11.194:6379、Milvus http://192.168.11.194:19530（v2.5.0，容器名 `milvus-standalone`/`redis`）。电脑重启后需 `docker start redis milvus-standalone`
- **JMeter**：`D:\study_xue\apache-jmeter-5.6.3\apache-jmeter-5.6.3`，计划在项目 `jmeter\`

## 1. A-E 阶段状态

- **A 通信基建** ✅ LLMutils 改 record+Jackson（上游报错带响应正文）；SSE 有界线程池（8/32/队列100/CallerRunsPolicy）；Redis 会话记忆 List(RPUSH/LTRIM/EXPIRE)+TTL1800s+max-messages=20；空回答不写记忆；孤儿 assistant 过滤已修（ChatMemoryService.java:66-68）；前端 fetchStream 路径笔误/response.ok/sessionId 已修
- **B Function Calling** ✅ 三工具：`get_city_info`(本地静态)、`get_weather`/`search_poi`(高德 AmapService，天气内部两次 HTTP 编排 district→weatherInfo)；见 §3 串行化决策
- **C Milvus RAG** ✅ 语料 `resources/knowledge/*.md`（5 城×4 小节，每城"隐藏福利"小节各埋 1 处虚构针头事实，共 5 处，用于验证检索链路）；EmbeddingService(bge-m3 1024维批量)；KnowledgeService(按 `##` 切块+城市前缀+<30字过滤；全量重建；collection `travel_knowledge` COSINE+AUTOINDEX)；chat 每轮现查现用注入 system 提示词；关键参数 `rag.top-k=4`、`rag.min-score=0.6`
- **D 部署上线 + GitHub + JMeter** ✅ **已完成**（2026-09-17 核对）。实际执行与 §2 原计划有偏差，均为更优做法：未建 `application-example.yml`，改为把 `application.yml` 全量环境变量化（`${ENV_VAR}` 占位符，入库零真实密钥）+ 新增 `application-dev-example.yml` 模板 + `.gitignore` 屏蔽 `application-dev.yml`；git 历史经 `-S` 全量扫描确认从未出现真实密钥；仓库 `cye0057/travel-java-server`（main 分支，非 master）已推送且本地=远端 `2a4980b`；JMeter 报告已提交 `docs/benchmark.md`；8080/8081 无残留进程
- **E 简历项目描述** ✅ **已完成**（2026-09-17）。产出 `RESUME.md`：完整档 350 字简历正文 / 精简档 1~2 行 / STAR 面试版 7 个高频问题 / 已知边界 / 技术栈关键词
- **VM 局域网部署实战** ✅ **已跑通**（2026-09-17，192.168.11.194）。过程中排掉 3 个坑，均已固化进 `deploy/DEPLOY.md` 与 compose：
  1. `host-gateway` 解析到的是网桥 `172.17.0.1`，而宿主机 Redis/Milvus 发布在 `0.0.0.0` → 容器连 6379/19530 超时 → 注册 500（`登录服务暂不可用（Redis未启动）`，但 MySQL 写入成功、前两行日志显示 Hikari 正常）→ **改直连真实 IP**（REDIS_HOST/MILVUS_URI 不再用 host.docker.internal）
  2. `docker compose up -d` 修改环境变量后**不会重建容器**（镜像没变时 0.0.0s 跳过），必须 `--force-recreate`
  3. VM 上 `git pull` 撞本地改动 → `git stash` + `git stash pop`（用户手动去掉了 compose 里的变量默认值，保留）
- 验活命令：`curl localhost:8080/api/user/register -d '{"username":"smoke1","password":"test1234"}'` 返回 token 即全链路通（含 Redis）
  - D 阶段的 E2E 真实浏览器测试已完成，抓到 5 个接口测试测不出的 bug 并全部修复：①fetchStream 原生 fetch 不走 axios 拦截器→手动补 Authorization ②前端读 `res.message` 后端是 `messages`→拦截器统一取值 ③`Result.fail(code,msg)` 漏回填 code（见 §3）④越权查询 `queryForObject` 无结果抛异常→改 `queryForList` 判空抛 UnauthorizedException（HistoryService.java:86-92）⑤模型幻觉工具名 get_poi→别名归一+清单回传自纠（TravelServiceImpl.java:367-383）
  - 已核对：uitester1 会话 4 条消息落库；登出后 token 即时失效
- **E 简历项目描述** ⏳ 未开始

## 2. D 阶段剩余待办（按顺序）

1. GitHub 建仓库：**.gitignore 选 Maven 模板；README 不勾选**（自己写）
2. 同步忽略密钥：`.gitignore` 追加 `src/main/resources/application.yml`（内含 SiliconFlow+高德两个真实密钥）、`.idea/`、`*.iml`、`jmeter.log`、`.zcode/`；另建 `application-example.yml`（密钥换占位符）提交上去
3. **首推验证步骤（必须按序，密钥一旦 commit 进历史就删不掉）**：
   ```bash
   cd D:/study_xue/JAVA_code/travel-java-server   # 目前还不是 git 仓库
   git init && git add -A
   git status --short | grep application.yml      # 必须无输出！有输出=漏网，禁止继续
   git commit -m "init: travel assistant (Spring Boot + RAG + Function Calling)"
   git remote add origin <你的仓库地址> && git push -u origin master
   ```
4. JMeter 最终压测数据（基线：5 并发 0 错误；推荐接口平均 98s，P95 顶着 OkHttp 120s 读超时，瓶颈在模型推理不在服务端）
5. 顺带确认冒烟进程/端口已清理（`netstat -ano | grep LISTENING` 后 taskkill，Docker 容器不要动）

## 3. 关键决策的"为什么"（面试话术）

**① 401 链路：为什么 `Result.fail(code,msg)` 必须回填 code**
跨栈链路四环：后端 `setCode(code)` → 响应体 `code=401` → 前端 axios 响应拦截器识别 401 → 清 localStorage 的 token/user（request.js:31-35）。原实现里两参 `fail()` 复用了硬编码 500 的无参版本、忘了覆盖 code，导致 token 过期时前端收到 500 当普通错误处理，用户卡在"以为还登录着"的假状态。教训：状态码是**契约**，链路缺一环功能就不成立；此类问题只有"故意把 token 弄过期再点页面"的端到端测试才测得出。

**② 工具调用串行化：为什么把 N 个并行调用拆成 N 对 assistant→tool**
SiliconFlow/GLM 的校验器**拒绝连续的 tool 消息**（裸 API 实验复现：400 code 20015 "after tool message, next must be user or assistant"）。模型一轮并行发起 2 个调用本身没问题，但按 OpenAI 惯例连着回传 2 条 tool 就被拒。修法：拆成 `assistant(只带这一个call) → tool(对应结果)` 重复 N 次，实测上游接受且模型能综合多结果作答（`runWithTools`）。为什么不加 `parallel_tool_calls:false`？——实测上游只"接受"该字段，未必真约束模型行为；串行化是无论模型怎么发都成立的兜底。

**③ RAG 阈值 min-score 为什么定 0.6**
向量检索永远会返回"最近的 K 条"，哪怕库里毫无相关内容。实测 bge-m3 分数分两群：相关命中 0.78+，无关问题 0.49~0.56（库外问题曾以 0.55 误召回 4 条无关资料）。取两群中间的 0.6 一刀切开后：库外题召回 0 条（模型如实说"暂不支持"），5 处针头事实对应的提问全部答对。教训：相似度阈值不能抄博客，必须用自家 embedding 模型实测分数分布来定。

**④ SSE 是真流式吗？——不是逐 token 透传，是有意的分块缓冲**
`chatStream` 的 `tokenCallback` 传 `null`（`TravelServiceImpl.java:337`），上游虽然逐行流式读取，但正文先进 `fullContent` 缓冲，等 `runWithTools` 确认这是"最终答案"（而非工具中间轮的只言片语）后，才按 `PUSH_CHUNK_SIZE = 80` 分块推给前端。
- **为什么**：工具调用循环里模型会先输出"我来查一下天气"这类过程文本，直接透传用户会看到中间态。
- **代价**：首字延迟 = 完整生成时间（对话均值 ~7s），流式"低首字延迟"的价值没拿到。
- **要做真流式**：按"本轮是否含工具调用"分流——无工具的轮次直接把 `tokenCallback` 接到 emitter 透传，有工具的轮次才缓冲。已列为优化方向。
- **面试要点**：主动说清这个权衡，比含糊带过更能体现对 SSE 协议本质（首字延迟，而非分块传输）的理解。

**⑤ 其他**
- RAG 资料**不进 Redis 记忆**：记忆跨轮复用，进了等于每轮回放旧资料，token 膨胀且把过期资料喂给新问题。
- Amap 用独立短超时 client（5s/10s）：LLM 推理慢值得等，高德是毫秒级接口，共用 120s 会让一次网络抖动拖死整条 SSE；失败转文字回传给模型自纠（工具失败不中断对话）。
- max-messages 必须偶数：奇数时 LTRIM 裁出孤儿 assistant 开头，上游报 20015。

## 4. 测试备忘

- Git Bash 命令行传中文会乱码 → JSON body 写文件 + `curl --data-binary @file.json`
- SSE 测试：`curl -sS -N --max-time 280 -X POST http://localhost:8081/api/travel/chat -H "Content-Type: application/json" --data-binary @file.json`
- RAG 排障顺序：先 `/api/knowledge/search`（纯检索不过 LLM）看召回与分数，再看生成
- 验证 RAG 有效性的手法是"针头测试"：语料里埋模型不可能凭常识知道的事实，答对=检索链路通；再加库外问题对照（应召回 0 条且不编造）
