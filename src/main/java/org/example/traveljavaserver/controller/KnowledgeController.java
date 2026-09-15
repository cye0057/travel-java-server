package org.example.traveljavaserver.controller;

import lombok.RequiredArgsConstructor;
import org.example.traveljavaserver.service.KnowledgeService;
import org.example.traveljavaserver.vo.Result;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * 知识库管理接口：把"建库"和"召回"从对话链路里拆出来单独可测。
 * 排障顺序应该是：/search 召回不对 → 先修切块/阈值；召回对了 chat 回答还不对 → 才查提示词。
 */
@RestController
@RequestMapping("/api/knowledge")
@RequiredArgsConstructor
public class KnowledgeController {

    private final KnowledgeService knowledgeService;

    /** 全量重建知识库（改了 knowledge/*.md 后调它），返回写入的分块数 */
    @PostMapping("/reload")
    public Result<Map<String, Object>> reload() throws IOException {
        int chunks = knowledgeService.reload();
        return Result.ok(Map.of("chunks", chunks));
    }

    /** 纯检索调试：看 topK 命中了什么、分数多少，不经 LLM */
    @GetMapping("/search")
    public Result<List<KnowledgeService.KnowledgeHit>> search(@RequestParam String q,
                                                              @RequestParam(defaultValue = "4") int k) {
        return Result.ok(knowledgeService.search(q, k));
    }
}
