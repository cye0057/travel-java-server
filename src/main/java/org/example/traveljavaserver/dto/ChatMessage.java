package org.example.traveljavaserver.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 一条聊天消息，role 取值：system / user / assistant / tool。
 * 跨层共享的领域模型：ChatMemoryService 存它、LLMutils 发给模型、TravelServiceImpl 拼工具调用循环。
 * @JsonInclude(NON_NULL)：普通消息序列化时不带 tool_calls 等空字段，兼容严格的 OpenAI 实现。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChatMessage(String role,
                          String content,
                          @JsonProperty("tool_calls") List<ToolCall> toolCalls,
                          @JsonProperty("tool_call_id") String toolCallId) {

    /** OpenAI 协议的工具调用请求：模型输出它，意思不是"我说完了"，而是"请你执行这个函数后把结果给我" */
    public record ToolCall(String id, String type, FunctionCall function) {}

    /** name=工具名；arguments=模型现写的参数JSON——注意它是【字符串】，需要二次解析才能用 */
    public record FunctionCall(String name, String arguments) {}

    public static ChatMessage system(String content) {
        return new ChatMessage("system", content, null, null);
    }

    public static ChatMessage user(String content) {
        return new ChatMessage("user", content, null, null);
    }

    public static ChatMessage assistant(String content) {
        return new ChatMessage("assistant", content, null, null);
    }

    /** 模型请求工具调用的那条 assistant 消息：content 为空，携带 tool_calls */
    public static ChatMessage assistantWithToolCalls(List<ToolCall> toolCalls) {
        return new ChatMessage("assistant", "", toolCalls, null);
    }

    /** 工具执行结果消息：role 固定为 tool，必须带 tool_call_id，模型靠它把结果和调用对上号 */
    public static ChatMessage tool(String toolCallId, String content) {
        return new ChatMessage("tool", content, null, toolCallId);
    }
}
