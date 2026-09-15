package org.example.traveljavaserver.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.DataType;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.AddFieldReq;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.collection.request.DropCollectionReq;
import io.milvus.v2.service.collection.request.HasCollectionReq;
import io.milvus.v2.service.collection.request.LoadCollectionReq;
import io.milvus.v2.service.vector.request.InsertReq;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.request.data.FloatVec;
import io.milvus.v2.service.vector.response.SearchResp;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Milvus 知识库（RAG 的存储与检索层）。
 *
 * 与 Python 课程里 langchain 的链路一一对应：
 *   DocumentLoader → loadChunks()（按 ## 小节切块）
 *   VectorStore.from_documents → reload()（批量 embed + insert）
 *   similarity_search → retrieve()（查询向量 → COSINE topK）
 * langchain 把这层全包起来了，这里手写一遍，面试被问"RAG 原理"才答得出细节。
 *
 * 降级策略与会话记忆一致：Milvus/embedding 任一环节失败都不阻断对话，
 * 只是这一轮退化为"纯模型回答"。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeService {

    private final EmbeddingService embeddingService;

    @Value("${rag.milvus.uri}")
    private String milvusUri;
    @Value("${rag.milvus.collection}")
    private String collection;
    @Value("${rag.top-k:4}")
    private int topK;
    /** 相似度阈值：低于该值的召回视为"与问题无关"，宁缺毋滥防止无关资料污染提示词 */
    @Value("${rag.min-score:0.4}")
    private float minScore;
    @Value("${rag.embed-batch-size:16}")
    private int embedBatchSize;
    /** 启动时若 collection 不存在则自动建库；文档改动后用 /api/knowledge/reload 手动重建 */
    @Value("${rag.ingest-on-startup:true}")
    private boolean ingestOnStartup;

    /** null = Milvus 不可用，所有方法静默降级 */
    private MilvusClientV2 client;
    private boolean collectionReady;

    /** 一个知识分块：text 是给模型看的正文，source 用于回答里标注出处 */
    public record KnowledgeHit(String text, String source, float score) {}

    @PostConstruct
    public void init() {
        try {
            client = new MilvusClientV2(ConnectConfig.builder().uri(milvusUri).build());
            boolean exists = client.hasCollection(HasCollectionReq.builder().collectionName(collection).build());
            if (exists) {
                collectionReady = true;
                log.info("Milvus collection[{}] 已存在，跳过建库", collection);
                return;
            }
            if (ingestOnStartup) {
                log.info("Milvus collection[{}] 不存在，启动时自动建库", collection);
                reload();
            }
        } catch (Exception e) {
            log.warn("Milvus 初始化失败，RAG 整体降级为无知识库模式: {}", e.getMessage());
            collectionReady = false;
        }
    }

    /**
     * 重建知识库：读语料 → 切块 → 批量向量化 → drop 旧 collection → 建 schema → 写入 → load。
     * 全量重建而非增量 upsert：语料只有几十块，重建比维护"哪些块变了"的差集逻辑便宜得多。
     */
    public synchronized int reload() throws IOException {
        ensureConnected();
        List<ChunkDraft> drafts = loadChunks();
        if (drafts.isEmpty()) {
            throw new IOException("classpath:knowledge/*.md 下没有找到语料");
        }
        //向量批量生成：一次 API 拿一批，20 个块若逐条embed就是20次网络往返
        List<List<Float>> vectors = new ArrayList<>();
        for (int i = 0; i < drafts.size(); i += embedBatchSize) {
            List<ChunkDraft> batch = drafts.subList(i, Math.min(i + embedBatchSize, drafts.size()));
            vectors.addAll(embeddingService.embed(batch.stream().map(ChunkDraft::text).toList()));
        }
        if (client.hasCollection(HasCollectionReq.builder().collectionName(collection).build())) {
            client.dropCollection(DropCollectionReq.builder().collectionName(collection).build());
        }
        createCollection();

        List<JsonObject> rows = new ArrayList<>();
        for (int i = 0; i < drafts.size(); i++) {
            JsonObject row = new JsonObject();
            row.addProperty("text", drafts.get(i).text());
            row.addProperty("source", drafts.get(i).source());
            JsonArray vec = new JsonArray();
            vectors.get(i).forEach(vec::add);
            row.add("vector", vec);
            rows.add(row);
        }
        client.insert(InsertReq.builder().collectionName(collection).data(rows).build());
        client.loadCollection(LoadCollectionReq.builder().collectionName(collection).build());
        collectionReady = true;
        log.info("知识库重建完成: {} 个分块写入 Milvus collection[{}]", rows.size(), collection);
        return rows.size();
    }

    /** collection schema：id自增主键 + text正文字段 + source出处 + 1024维向量，COSINE 距离 */
    private void createCollection() {
        CreateCollectionReq.CollectionSchema schema = client.createSchema();
        schema.addField(AddFieldReq.builder().fieldName("id")
                .dataType(DataType.Int64).isPrimaryKey(true).autoID(true).build());
        schema.addField(AddFieldReq.builder().fieldName("text")
                .dataType(DataType.VarChar).maxLength(4096).build());
        schema.addField(AddFieldReq.builder().fieldName("source")
                .dataType(DataType.VarChar).maxLength(512).build());
        schema.addField(AddFieldReq.builder().fieldName("vector")
                .dataType(DataType.FloatVector).dimension(EmbeddingService.DIMENSION).build());
        client.createCollection(CreateCollectionReq.builder()
                .collectionName(collection)
                .description("travel assistant knowledge base")
                .collectionSchema(schema)
                .indexParams(List.of(IndexParam.builder()
                        .fieldName("vector")
                        .indexType(IndexParam.IndexType.AUTOINDEX)
                        .metricType(IndexParam.MetricType.COSINE)
                        .build()))
                .build());
    }

    /** 检索：问题向量 → COSINE topK → 阈值过滤。失败静默返回空列表（降级），调用方无须 try */
    public List<KnowledgeHit> retrieve(String query) {
        if (client == null || !collectionReady || query == null || query.isBlank()) {
            return List.of();
        }
        try {
            List<Float> qv = embeddingService.embedOne(query.trim());
            SearchResp resp = client.search(SearchReq.builder()
                    .collectionName(collection)
                    .annsField("vector")
                    .data(List.of(new FloatVec(qv)))
                    .limit(topK)
                    .outputFields(List.of("text", "source"))
                    .metricType(IndexParam.MetricType.COSINE)
                    .build());
            List<KnowledgeHit> hits = new ArrayList<>();
            if (!resp.getSearchResults().isEmpty()) {
                for (SearchResp.SearchResult r : resp.getSearchResults().get(0)) {
                    float score = r.getScore() == null ? 0f : r.getScore();
                    if (score < minScore) {
                        continue;
                    }
                    Object text = r.getEntity().get("text");
                    Object source = r.getEntity().get("source");
                    hits.add(new KnowledgeHit(String.valueOf(text), String.valueOf(source), score));
                }
            }
            log.info("RAG 检索 q={} 召回{}条", query, hits.size());
            return hits;
        } catch (Exception e) {
            log.warn("RAG 检索失败，本轮不带知识库上下文: {}", e.getMessage());
            return List.of();
        }
    }

    /** 带参数的检索（调试接口用）：k 由调用方指定，忽略阈值 */
    public List<KnowledgeHit> search(String query, int k) {
        if (client == null || !collectionReady) {
            throw new IllegalStateException("Milvus 不可用或知识库未建立");
        }
        try {
            List<Float> qv = embeddingService.embedOne(query.trim());
            SearchResp resp = client.search(SearchReq.builder()
                    .collectionName(collection)
                    .annsField("vector")
                    .data(List.of(new FloatVec(qv)))
                    .limit(k)
                    .outputFields(List.of("text", "source"))
                    .metricType(IndexParam.MetricType.COSINE)
                    .build());
            List<KnowledgeHit> hits = new ArrayList<>();
            if (!resp.getSearchResults().isEmpty()) {
                for (SearchResp.SearchResult r : resp.getSearchResults().get(0)) {
                    hits.add(new KnowledgeHit(String.valueOf(r.getEntity().get("text")),
                            String.valueOf(r.getEntity().get("source")),
                            r.getScore() == null ? 0f : r.getScore()));
                }
            }
            return hits;
        } catch (Exception e) {
            throw new RuntimeException("检索失败: " + e.getMessage(), e);
        }
    }

    private record ChunkDraft(String text, String source) {}

    /** 切块策略：以 Markdown 的 "## 小节" 为天然边界，块头加【城市·主题】前缀——
     *  前缀本身也参与向量化，能显著提高"杭州的xxx"这类问题的召回精度 */
    private List<ChunkDraft> loadChunks() throws IOException {
        Resource[] resources = new PathMatchingResourcePatternResolver()
                .getResources("classpath:knowledge/*.md");
        List<ChunkDraft> chunks = new ArrayList<>();
        for (Resource res : resources) {
            String content = new String(res.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String city = res.getFilename();
            for (String line : content.split("\\R")) {
                if (line.startsWith("# ")) {
                    city = line.substring(2).trim();
                    break;
                }
            }
            // (?m)^## 多行模式下按小节标题切；[0] 是 H1 与第一个小节之间的空隙，通常是空的
            String[] sections = content.split("(?m)^## ");
            for (String section : sections) {
                String trimmed = section.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                int nl = trimmed.indexOf('\n');
                String title = nl > 0 ? trimmed.substring(0, nl).trim() : city;
                String body = nl > 0 ? trimmed.substring(nl + 1).trim() : trimmed;
                //丢弃首行 H1 残留之类的无信息小块：超短文本的余弦向量噪声大，反而容易在无关问题下误召回
                if (body.length() < 30) {
                    continue;
                }
                chunks.add(new ChunkDraft("【" + city + "·" + title + "】" + body, res.getFilename() + "#" + title));
            }
        }
        return chunks;
    }

    private void ensureConnected() throws IOException {
        if (client == null) {
            try {
                client = new MilvusClientV2(ConnectConfig.builder().uri(milvusUri).build());
            } catch (Exception e) {
                throw new IOException("Milvus 连接失败: " + e.getMessage(), e);
            }
        }
    }

    @PreDestroy
    public void close() {
        if (client != null) {
            try {
                client.close();
            } catch (Exception ignored) {
            }
        }
    }
}
