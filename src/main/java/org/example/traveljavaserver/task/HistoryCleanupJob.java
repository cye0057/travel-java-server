package org.example.traveljavaserver.task;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.traveljavaserver.service.HistoryService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 每日凌晨 3 点清理 7 天前的历史对话（MySQL 侧）。
 * Redis 侧不用管：缓存 key 建的时候都带 7 天 TTL，自己会蒸发。
 * 数据量再大要改成"分批 DELETE 防长事务"，本项目量级下一条 SQL 足够。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HistoryCleanupJob {

    private final HistoryService historyService;

    @Scheduled(cron = "0 0 3 * * ?")
    public void cleanExpiredHistory() {
        try {
            int deleted = historyService.purgeExpired();
            log.info("历史对话清理任务完成，共删除 {} 行", deleted);
        } catch (Exception e) {
            //清理失败只影响存储占用，不影响业务，明晚自动重试
            log.error("历史对话清理任务异常", e);
        }
    }
}
