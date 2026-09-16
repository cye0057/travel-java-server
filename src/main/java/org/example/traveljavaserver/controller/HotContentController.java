package org.example.traveljavaserver.controller;

import lombok.RequiredArgsConstructor;
import org.example.traveljavaserver.vo.Result;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 运营位只读接口：对话页热门问题、首页热门城市。
 * 数据在 MySQL（hot_question/hot_city），前端硬编码数组退役；
 * 挂在 /api/hot 下——不在鉴权拦截器的 /api/user/** 范围内，游客可见。
 * 纯查询无业务规则，controller 直连 JdbcTemplate，不再摆 service/dao 空架子。
 */
@RestController
@RequestMapping("/api/hot")
@RequiredArgsConstructor
public class HotContentController {

    private final JdbcTemplate jdbcTemplate;

    /** 热门问题文本列表，按 sort 升序，只回启用的 */
    @GetMapping("/questions")
    public Result<List<String>> questions() {
        return Result.ok(jdbcTemplate.queryForList(
                "SELECT question FROM hot_question WHERE enabled = 1 ORDER BY sort, id", String.class));
    }

    /** 热门城市列表，同上 */
    @GetMapping("/cities")
    public Result<List<String>> cities() {
        return Result.ok(jdbcTemplate.queryForList(
                "SELECT city FROM hot_city WHERE enabled = 1 ORDER BY sort, id", String.class));
    }
}
