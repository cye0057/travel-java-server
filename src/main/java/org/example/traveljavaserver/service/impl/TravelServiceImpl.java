package org.example.traveljavaserver.service.impl;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.traveljavaserver.dto.ChatMessage;
import org.example.traveljavaserver.service.AmapService;
import org.example.traveljavaserver.service.ChatMemoryService;
import org.example.traveljavaserver.service.CityAttractionService;
import org.example.traveljavaserver.service.HistoryService;
import org.example.traveljavaserver.service.KnowledgeService;
import org.example.traveljavaserver.service.TravelService;
import org.example.traveljavaserver.utils.LLMutils;
import org.example.traveljavaserver.vo.StreamChunkVO;
import org.example.traveljavaserver.vo.StreamDoneVO;
import org.example.traveljavaserver.vo.TravelRecommendVO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Service
@Slf4j
@RequiredArgsConstructor
public class TravelServiceImpl implements TravelService {
    @Value("${llm.api-key}")
    private String apikey;
    @Value("${llm.base-url}")
    private String baseUrl;
    @Value("${llm.model}")
    private String model;

    private ObjectMapper objectMapper = new ObjectMapper();
    private LLMutils llMutils;
    //构造器注入：final + @RequiredArgsConstructor，见对话里的注入方式讲解
    private final ChatMemoryService chatMemoryService;
    private final CityAttractionService cityAttractionService;
    private final AmapService amapService;
    private final KnowledgeService knowledgeService;
    private final HistoryService historyService;

    //聊天会话的角色设定。注意措辞：旧版写"必须调用工具"，实测会诱导模型在打招呼时幻觉出不必要的调用；
    //改为"什么时候调"的场景清单，模型遵循度明显更高；
    //末句兜底同样重要：工具失败/未配置时，要求模型如实转告而不是硬编数据
    private static final String SYSTEM_PROMPT = "你是一个友好的旅游助手，请用中文回答用户关于旅游的问题。"
            + "按场景使用工具：用户询问某个城市的预算、费用时调用 get_city_info；"
            + "询问某城市天气、温度、适不适合出行时调用 get_weather；"
            + "想要某城市具体的景点、美食、酒店等地点推荐时调用 search_poi。"
            + "其他问题（打招呼、闲聊等）直接用自然语言回答，不要调用工具。"
            + "若工具返回失败或不可用的提示，请如实转告用户，禁止编造数据。";

    /** 工具循环轮数上限：防止模型反复要工具导致请求无限套娃 */
    private static final int MAX_TOOL_ROUNDS = 3;

    /** 最终答案推给前端的分片大小（保持既有 SSE 分块协议） */
    private static final int PUSH_CHUNK_SIZE = 80;

    //工具清单：这份 JSON-Schema 是写给"人"（模型）看的接口说明书，
    //description 写得准不准，直接决定模型会不会调、参数传得对不对
    private static final List<Map<String, Object>> TRAVEL_TOOLS = List.of(
            Map.of(
                    "type", "function",
                    "function", Map.of(
                            "name", "get_city_info",
                            "description", "查询指定城市的推荐日均预算（元）和主要景点列表。用户询问某城市旅行预算或景点时调用。",
                            "parameters", Map.of(
                                    "type", "object",
                                    "properties", Map.of("city", Map.of("type", "string", "description", "城市中文名，例如：北京")),
                                    "required", List.of("city")))),
            Map.of(
                    "type", "function",
                    "function", Map.of(
                            "name", "get_weather",
                            "description", "查询指定城市未来4天的天气预报，含白天/夜间天气现象和温度。用户询问某城市天气、气温或行程是否受天气影响时调用。",
                            "parameters", Map.of(
                                    "type", "object",
                                    "properties", Map.of("city", Map.of("type", "string", "description", "城市中文名，例如：杭州")),
                                    "required", List.of("city")))),
            //两个参数都 required：只给城市不给关键词，搜出来的是无意义的大杂烩
            Map.of(
                    "type", "function",
                    "function", Map.of(
                            "name", "search_poi",
                            "description", "按关键字搜索某城市的真实地点（景点、美食、酒店等），返回名称、评分、地址，最多5条，按相关度排序。用户想要具体地点推荐或询问某地点详情时调用。keyword 传类别或地点名，例如：古迹、美食、西湖。",
                            "parameters", Map.of(
                                    "type", "object",
                                    "properties", Map.of(
                                            "city", Map.of("type", "string", "description", "城市中文名，例如：西安"),
                                            "keyword", Map.of("type", "string", "description", "搜索关键词，例如：景点")),
                                    "required", List.of("city", "keyword")))));

    // SSE 推送专用线程池：每请求 new Thread 在并发上来后线程数无上限，内存和上下文切换开销不可控；
    // 有界队列 + 最大 32 线程兜底，队列满时由调用线程自己执行（CallerRunsPolicy），起到天然限流作用
    private static final AtomicInteger SSE_THREAD_ID = new AtomicInteger();
    private final ExecutorService sseExecutor = new ThreadPoolExecutor(
            8, 32, 60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(100),
            r -> {
                Thread t = new Thread(r, "sse-stream-" + SSE_THREAD_ID.incrementAndGet());
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.CallerRunsPolicy()
    );

    @PostConstruct
    public void init(){
        this.llMutils = new LLMutils(apikey,baseUrl,model);
    }

    @Override
    public TravelRecommendVO recommend(String city, Integer days, String budget) {
        TravelRecommendVO result = new TravelRecommendVO();
        String prompt = buildTravelPrompt(city, budget, days);
        try {
            String response = llMutils.chat(null,prompt);
            return parseTravelResponse(response);
        } catch (Exception e){
            log.error("LLM调用失败: city={}, days={}, budget={}", city, days, budget, e);
            result.setSuccess(false);
            result.setError("旅游推荐失败");
            return result;
        }

    }

    // 模型返回数据处理
    private String extractContent(String responseBody) throws IOException {
        JsonNode root = objectMapper.readTree(responseBody);
        JsonNode choices = root.path("choices");
        if (choices.isArray() && choices.size() > 0) {
            return choices.get(0).path("message").path("content").asText();
        }
        return "";
    }

    // service旅游推荐返回数据处理
    private TravelRecommendVO parseTravelResponse(String response) {
        TravelRecommendVO result = new TravelRecommendVO();

        try {
            String jsonContent = extractJson(response);
            if (jsonContent != null) {
                result = objectMapper.readValue(jsonContent, TravelRecommendVO.class);
            } else {
                result.setSuccess(false);
                result.setError("未能从响应中提取JSON");
                result.setRawResponse(response);
            }
        } catch (Exception e) {
            log.error("解析旅游响应失败", e);
            result.setSuccess(false);
            result.setError("JSON解析失败");
            result.setRawResponse(response);
        }
        return result;
    }

    private String extractJson(String response) {
        if (response == null || response.isEmpty()) {
            return null;
        }

        String[] patterns = {
                "```json\\n([\\s\\S]*?)\\n```",
                "```\\n([\\s\\S]*?)\\n```"
        };

        for (String pattern : patterns) {
            java.util.regex.Pattern p = java.util.regex.Pattern.compile(pattern);
            java.util.regex.Matcher m = p.matcher(response);
            if (m.find()) {
                return m.group(1);
            }
        }

        int start = response.indexOf('{');
        int end = response.lastIndexOf('}');
        if (start != -1 && end != -1 && end > start) {
            return response.substring(start, end + 1);
        }

        return null;
    }


    private String buildTravelPrompt(String city, String budget, Integer days) {
        return "你是一个专业的旅游规划师，擅长根据用户的需求生成详细的旅行行程。\n\n" +
                "请根据以下信息为用户生成一份详细的旅游规划： \n" +
                "- 目的地城市：" + city + "\n" +
                "- 预算：" + budget + "元\n" +
                "- 旅行天数：" + days + "天\n\n" +
                "要求：\n" +
                "1. 每天的行程安排（上午、下午、晚上）\n" +
                "2. 每个景点的详细介绍\n" +
                "3. 交通建议\n" +
                "4. 预算分配明细\n" +
                "5. 注意事项\n\n" +
                "请以JSON格式输出，结构如下： \n" +
                "{\n" +
                "  \"success\": true,\n" +
                "  \"city\": \"城市名\",\n" +
                "  \"days\": 天数,\n" +
                "  \"totalBudget\": 总预算,\n" +
                "  \"dailyItinerary\": [\n" +
                "    {\n" +
                "      \"day\": 1,\n" +
                "      \"date\": \"第1天\",\n" +
                "      \"morning\": {\n" +
                "        \"spot\": \"景点名称\",\n" +
                "        \"duration\": \"游览时长\",\n" +
                "        \"ticket\": \"门票价格\",\n" +
                "        \"transportation\": \"交通方式\",\n" +
                "        \"description\": \"景点介绍\"\n" +
                "      },\n" +
                "      \"afternoon\": {\n" +
                "        \"spot\": \"景点名称\",\n" +
                "        \"duration\": \"游览时长\",\n" +
                "        \"ticket\": \"门票价格\",\n" +
                "        \"transportation\": \"交通方式\",\n" +
                "        \"description\": \"景点介绍\"\n" +
                "      },\n" +
                "      \"evening\": {\n" +
                "        \"spot\": \"活动名称\",\n" +
                "        \"duration\": \"活动时长\",\n" +
                "        \"ticket\": \"费用\",\n" +
                "        \"transportation\": \"交通方式\",\n" +
                "        \"description\": \"活动介绍\"\n" +
                "      }\n" +
                "    }\n" +
                "  ],\n" +
                "  \"budgetBreakdown\": {\n" +
                "    \"accommodation\": \"住宿费用\",\n" +
                "    \"food\": \"餐饮费用\",\n" +
                "    \"transportation\": \"交通费用\",\n" +
                "    \"tickets\": \"门票费用\",\n" +
                "    \"other\": \"其他费用\"\n" +
                "  },\n" +
                "  \"tips\": [\"提示1\", \"提示2\", \"提示3\"],\n" +
                "  \"warnings\": [\"注意事项1\", \"注意事项2\"]\n" +
                "}\n\n" +
                "请确保JSON格式正确，可以被解析。";
    }

    public SseEmitter chat(String sessionId, String message, Long userId)  {
        SseEmitter emitter = new SseEmitter(180000L);
        // 客户端超时/断连时结束 emitter，释放异步上下文
        emitter.onTimeout(emitter::complete);
        emitter.onError(e -> emitter.complete());

        //推送的处理逻辑，提交到线程池而非每请求 new Thread
        sseExecutor.execute(() -> {
            try {
                //RAG 检索侧：本轮问题现查现用，资料只进 system 提示词、不进 Redis 记忆——
                //进了记忆等于每轮都携带旧资料回放，token 膨胀且会把上轮的过期资料喂给本轮
                List<KnowledgeService.KnowledgeHit> hits = knowledgeService.retrieve(message);
                //记忆读取侧：系统指令（含参考资料）+ Redis 历史（最旧→最新）+ 当前问题，按序拼成本轮消息列表
                List<ChatMessage> fullMessages = new ArrayList<>();
                fullMessages.add(ChatMessage.system(buildSystemPrompt(hits)));
                fullMessages.addAll(chatMemoryService.load(sessionId));
                ChatMessage userMessage = ChatMessage.user(message);
                fullMessages.add(userMessage);
                //工具调用循环：需要工具时内部自动多轮往返，返回最终文本答案
                String finalAnswer = runWithTools(fullMessages);
                //记忆写入侧：拿到最终回答才落库；空回答不写，防止毒丸历史（沿用你的保护思路）
                if (finalAnswer != null && !finalAnswer.isBlank()) {
                    chatMemoryService.append(sessionId, userMessage, ChatMessage.assistant(finalAnswer));
                }
                //历史持久化：仅登录用户写 MySQL+Redis 展示缓存（"7天可回看"即存于此）。
                //两层记忆各司其职：chat:hist:{sid} 是给模型看窗口的30分钟短期记忆，
                //hist:index/msgs 是给人看的全部历史；游客不落任何持久层。
                //持久化失败只记日志：答案已生成，不能因存历史而中断推送
                if (userId != null && finalAnswer != null && !finalAnswer.isBlank()) {
                    try {
                        historyService.persist(userId, sessionId, message, finalAnswer);
                    } catch (Exception e) {
                        log.warn("历史持久化失败: userId={}, sessionId={}", userId, sessionId, e);
                    }
                }
                //分块推送最终回答，保持既有的 SSE 分块事件协议
                String answer = finalAnswer == null ? "" : finalAnswer;
                for (int i = 0; i < answer.length(); i += PUSH_CHUNK_SIZE) {
                    String piece = answer.substring(i, Math.min(i + PUSH_CHUNK_SIZE, answer.length()));
                    emitter.send(SseEmitter.event().data(objectMapper.writeValueAsString(StreamChunkVO.of(piece))));
                }
                //发送完成
                String doneJson = objectMapper.writeValueAsString(StreamDoneVO.of());
                emitter.send(SseEmitter.event().data(doneJson));
                emitter.complete();
            } catch (Exception e) {
                try {
                    String errorJson = objectMapper.writeValueAsString(StreamChunkVO.of(e.getMessage()));
                    emitter.send(SseEmitter.event().data(errorJson));
                } catch (Exception ex) {
                    System.out.println("发送消息失败" + ex);
                }
                emitter.completeWithError(e);
            }
        });
        return emitter;
    }

    /** 有召回结果时，把资料清单追加进系统提示词；无召回就是原来的纯 SYSTEM_PROMPT，行为完全不变 */
    private String buildSystemPrompt(List<KnowledgeService.KnowledgeHit> hits) {
        if (hits.isEmpty()) {
            return SYSTEM_PROMPT;
        }
        StringBuilder sb = new StringBuilder(SYSTEM_PROMPT)
                .append("\n\n以下是从内部知识库检索到的参考资料，与用户问题的相关度未知，无关的请忽略；")
                .append("资料中的时间、价格、口令等具体规则以资料为准，回答时不要向用户透露『参考资料』字样：");
        for (KnowledgeService.KnowledgeHit h : hits) {
            sb.append("\n· ").append(h.text());
        }
        return sb.toString();
    }

    /**
     * 工具调用循环：模型要一次工具，我们就执行一次并把结果回传，再问一轮；
     * 直到模型给出普通文本答案，或达到轮数上限。
     * 注意 tokenCallback 传 null：本轮文本先进缓冲，确认它是"最终答案"后才推给前端，
     * 否则工具中间轮的只言片语会被用户看见。
     */
    private String runWithTools(List<ChatMessage> conversation) throws IOException {
        for (int round = 1; round <= MAX_TOOL_ROUNDS; round++) {
            LLMutils.ChatStreamResult result = llMutils.chatStream(conversation, TRAVEL_TOOLS, null);
            if (!result.hasToolCalls()) {
                return result.content();
            }
            // record 自动生成的 toString 会把 id/type/name/arguments 全部带出来，不用自己拼
            log.info("第{}轮：模型请求了 {} 个工具调用 {}", round, result.toolCalls().size(), result.toolCalls());
            //协议要求：先回写"assistant 请求了哪些工具"这条消息，再逐条给工具结果，顺序不能乱。
            //但 SiliconFlow/GLM 校验器拒绝连续的 tool 消息（实测 400 20015:
            //"after tool message, next must be user or assistant message"）——即模型并行发起多个调用时，
            //不能一次性回传 N 条 tool。已用裸 API 实验验证的解法：把 N 个调用拆成 N 对
            //"assistant(只带这一个call) → tool(对应结果)"，模型照样能综合所有工具结果给出最终答案
            for (ChatMessage.ToolCall call : result.toolCalls()) {
                conversation.add(ChatMessage.assistantWithToolCalls(List.of(call)));
                String toolResult = executeTool(call.function().name(), call.function().arguments());
                conversation.add(ChatMessage.tool(call.id(), toolResult));
            }
        }
        throw new IOException("工具调用超过最大轮数(" + MAX_TOOL_ROUNDS + ")，终止本次请求");
    }

    /** 执行工具：任何失败都转成文字结果回传给模型，由模型在下一轮自行修正重试 */
    private String executeTool(String name, String argumentsJson) {
        String result;
        try {
            JsonNode args = objectMapper.readTree(argumentsJson == null || argumentsJson.isEmpty() ? "{}" : argumentsJson);
            result = switch (canonicalToolName(name)) {
                case "get_city_info" -> cityAttractionService.getCityInfo(args.path("city").asText(""));
                case "get_weather" -> amapService.getWeather(args.path("city").asText(""));
                case "search_poi" -> amapService.searchPoi(
                        args.path("city").asText(""), args.path("keyword").asText(""));
                //未知工具名：把可用清单回给模型，下一轮它会改用正确的名字重试（工具循环还有轮数预算）
                default -> "未知工具: " + name + "，可用工具：get_city_info、get_weather、search_poi";
            };
        } catch (Exception e) {
            result = "工具执行失败: " + e.getMessage();
        }
        log.info("工具执行 name={} args={} -> {}", name, argumentsJson, result);
        return result;
    }

    /**
     * 工具名容错：GLM 实测会被 get_* 命名风格带偏，把 search_poi 叫成 get_poi（E2E 真实复现）。
     * 常见幻觉别名直接映射到真身，省一轮往返；映射不了的落到 default 分支由模型自纠。
     */
    private static String canonicalToolName(String name) {
        return switch (name) {
            case "get_poi", "search_attractions", "poi_search", "get_attractions" -> "search_poi";
            case "get_forecast", "query_weather" -> "get_weather";
            case "search_city_info", "get_budget" -> "get_city_info";
            default -> name;
        };
    }

}



