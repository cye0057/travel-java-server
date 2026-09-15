package org.example.traveljavaserver.service;

import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * SiliconFlow 向量化客户端（/v1/embeddings）。
 * RAG 的"入库"和"检索"两端共用这一个入口：文本进、向量出——
 * 只有同一模型产出的向量放进同一空间才有可比性，换模型必须重建整个 collection。
 */
@Slf4j
@Service
public class EmbeddingService {

    /** bge-m3 输出维度，Milvus collection schema 要按它建（已用真实请求核实：dim=1024） */
    public static final int DIMENSION = 1024;

    @Value("${llm.base-url}")
    private String baseUrl;
    @Value("${llm.api-key}")
    private String apiKey;
    @Value("${rag.embedding-model}")
    private String model;

    private final ObjectMapper objectMapper = new ObjectMapper();

    //独立 client 而非复用 LLMutils：embedding 是毫秒级接口，超时策略和对话推理完全不同
    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .build();

    /**
     * 批量向量化：input 传数组一次可拿回多条向量，入库场景务必用批量（逐条 = 几十次往返）。
     * 返回顺序与入参一致（上游按 index 字段标注，这里显式排序，不赌"返回即有序"）。
     */
    public List<List<Float>> embed(List<String> texts) throws IOException {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        String body = objectMapper.writeValueAsString(Map.of("model", model, "input", texts));
        Request request = new Request.Builder()
                .url(baseUrl + "/embeddings")
                .addHeader("Content-Type", "application/json")
                .addHeader("Authorization", "Bearer " + apiKey)
                .post(RequestBody.create(body, MediaType.parse("application/json; charset=utf-8")))
                .build();
        try (Response response = client.newCall(request).execute()) {
            String respBody = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) {
                throw new IOException("Embedding调用异常 " + response.code() + "，上游响应: " + respBody);
            }
            List<JsonNode> items = new ArrayList<>();
            objectMapper.readTree(respBody).path("data").forEach(items::add);
            items.sort(Comparator.comparingInt(n -> n.path("index").asInt()));
            List<List<Float>> result = new ArrayList<>();
            for (JsonNode item : items) {
                List<Float> vector = new ArrayList<>();
                item.path("embedding").forEach(x -> vector.add((float) x.asDouble()));
                result.add(vector);
            }
            return result;
        }
    }

    /** 单条便捷入口（检索时用） */
    public List<Float> embedOne(String text) throws IOException {
        List<List<Float>> batch = embed(List.of(text));
        if (batch.isEmpty()) {
            throw new IOException("Embedding返回空结果");
        }
        return batch.get(0);
    }
}
