package org.example.traveljavaserver.utils;

import com.fasterxml.jackson.annotation.JsonInclude;
import okhttp3.*;
import org.example.traveljavaserver.dto.ChatMessage;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public class LLMutils {
    private String apiKey;
    private String baseUrl;
    private String model;
    private OkHttpClient client;
    private ObjectMapper objectMapper = new ObjectMapper();

    public LLMutils(String apiKey,String baseUrl ,String model){
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.model = model;
        this.client = new OkHttpClient.Builder()
                .connectTimeout(60, TimeUnit.SECONDS)
                .readTimeout(120,TimeUnit.SECONDS)
                .writeTimeout(60,TimeUnit.SECONDS)
                .build();
    }

    //旅游推荐接口
    public String chat(String systemPrompt ,String userPrompt){
        //单问单答的便捷入口：内部拼成消息列表，与多轮对话走同一条构造路径
        List<ChatMessage> messages = new ArrayList<>();
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            messages.add(ChatMessage.system(systemPrompt));
        }
        messages.add(ChatMessage.user(userPrompt));
        String requestBody = buildRequestBody(messages, false);
        //System.out.println(requestBody);
        //创建一个post请求
        Request request = new Request.Builder()
                .url(baseUrl + "/chat/completions")
                .addHeader("Content-Type","application/json")
                .addHeader("Authorization","Bearer " + apiKey)
                .post(RequestBody.create(requestBody,MediaType.parse("application/json; charset=utf-8")))
                .build();
        try(Response response = client.newCall(request).execute()){
            if(!response.isSuccessful()){
                String errBody = response.body() != null ? response.body().string() : "";
                throw new IOException("LLM调用异常" + response.code() + "，上游响应: " + errBody);
            }
           // System.out.println(response.body().string());
            String responseBody = response.body().string();
            return extractContent(responseBody);
        } catch (IOException e) {
            throw new RuntimeException(e);
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


    // 请求体由 Jackson 序列化生成；LlmRequest 只是本次调用的传输结构，保留为私有嵌套类型
    // NON_NULL：不传 tools 时请求体里干脆不出现该字段，兼容严格的 OpenAI 实现
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record LlmRequest(String model, boolean stream, List<ChatMessage> messages, double temperature, List<Map<String, Object>> tools) {}

    public String buildRequestBody(List<ChatMessage> messages, boolean stream) {
        return buildRequestBody(messages, stream, null);
    }

    public String buildRequestBody(List<ChatMessage> messages, boolean stream, List<Map<String, Object>> tools) {
        return objectMapper.writeValueAsString(new LlmRequest(model, stream, messages, 0.7, tools));
    }

    /** 一轮流式调用的结果：累计出的文本 + 模型要求的工具调用（两者通常只出现其一） */
    public record ChatStreamResult(String content, List<ChatMessage.ToolCall> toolCalls) {
        public boolean hasToolCalls() {
            return toolCalls != null && !toolCalls.isEmpty();
        }
    }

    //多轮对话入口：消息列表由调用方组装（system + 历史 + 当前问题）；tools 传 null 表示不带工具
    public ChatStreamResult chatStream(List<ChatMessage> messages, List<Map<String, Object>> tools,
                                       Consumer<String> tokenCallback) throws IOException {
        String requestBody = buildRequestBody(messages, true, tools);
        //创建请求
        Request request = new Request.Builder()
                .url(baseUrl + "/chat/completions")
                .addHeader("Content-Type", "application/json")
                .addHeader("Authorization", "Bearer " + apiKey)
                .addHeader("Accept", "text/event-stream")
                .post(RequestBody.create(requestBody, MediaType.parse("application/json; charset=utf-8")))
                .build();

        // 记录完整的内容
        StringBuilder fullContent = new StringBuilder();
        // 流式协议里 tool_calls 是碎片式传来的：首个碎片带 id/name，后续碎片逐段拼 arguments，
        // 多个并行调用靠 index 区分，所以用 TreeMap 按下标累积（TreeMap 保证最终按 index 有序）
        Map<Integer, String> callIds = new TreeMap<>();
        Map<Integer, StringBuilder> callNames = new TreeMap<>();
        Map<Integer, StringBuilder> callArgs = new TreeMap<>();

        try (Response response = client.newCall(request).execute()){
            if(!response.isSuccessful()){
                String errBody = response.body() != null ? response.body().string() : "";
                throw new IOException("LLM调用异常" + response.code() + "，上游响应: " + errBody);
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body().byteStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if(line.startsWith("data: ")){
                        //截取字符串
                        String data = line.substring(6);
                        if("[DONE]".equals(data)){
                            break;
                        }
                        handleStreamEvent(data, fullContent, callIds, callNames, callArgs, tokenCallback);
                    }
                }
            }
        }
        return new ChatStreamResult(fullContent.toString(), buildToolCalls(callIds, callNames, callArgs));
    }

    /** 解析单个流式事件：正文走 tokenCallback 实时推，tool_calls 碎片进累加器 */
    private void handleStreamEvent(String data, StringBuilder fullContent,
                                   Map<Integer, String> callIds,
                                   Map<Integer, StringBuilder> callNames,
                                   Map<Integer, StringBuilder> callArgs,
                                   Consumer<String> tokenCallback) {
        try {
            JsonNode root = objectMapper.readTree(data);
            JsonNode delta = root.path("choices").path(0).path("delta");

            String content = stripSpecialTokens(delta.path("content").asText(""));
            if (!content.isEmpty()) {
                fullContent.append(content);
                if (tokenCallback != null) {
                    tokenCallback.accept(content);
                }
            }

            JsonNode toolCalls = delta.path("tool_calls");
            if (toolCalls.isArray()) {
                for (JsonNode tc : toolCalls) {
                    int idx = tc.path("index").asInt(0);
                    if (tc.hasNonNull("id")) {
                        callIds.put(idx, tc.get("id").asText());
                    }
                    JsonNode fn = tc.path("function");
                    String name = fn.path("name").asText("");
                    if (!name.isEmpty()) {
                        callNames.computeIfAbsent(idx, k -> new StringBuilder()).append(name);
                    }
                    String argDelta = fn.path("arguments").asText("");
                    if (!argDelta.isEmpty()) {
                        callArgs.computeIfAbsent(idx, k -> new StringBuilder()).append(argDelta);
                    }
                }
            }
        } catch (Exception e) {
            System.out.println("解析流式数据失败：" + e.getMessage());
        }
    }

    /** 把累加器组装成完整的工具调用列表；没有收到任何碎片则返回空 */
    private List<ChatMessage.ToolCall> buildToolCalls(Map<Integer, String> callIds,
                                                      Map<Integer, StringBuilder> callNames,
                                                      Map<Integer, StringBuilder> callArgs) {
        List<ChatMessage.ToolCall> result = new ArrayList<>();
        for (Integer idx : callIds.keySet()) {
            result.add(new ChatMessage.ToolCall(
                    callIds.get(idx),
                    "function",
                    new ChatMessage.FunctionCall(
                            callNames.getOrDefault(idx, new StringBuilder()).toString(),
                            callArgs.getOrDefault(idx, new StringBuilder()).toString())));
        }
        return result;
    }

    /**
     * 剔除模型内部控制标记，如 <|begin_of_box|> <|end_of_box|> 等。
     * 带思考能力的模型会把最终答案包在这些标记里，直接透传会把协议符号显示给用户。
     * 正则要求 <|xxx|> 成对模式才删除，正文里单独出现的 < 或 | 不会被误伤。
     */
    static String stripSpecialTokens(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        return text.replaceAll("<\\|[a-zA-Z_]+\\|>", "");
    }


}
