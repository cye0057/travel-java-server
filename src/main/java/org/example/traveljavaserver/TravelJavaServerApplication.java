package org.example.traveljavaserver;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling   // 启用定时任务：历史对话的每日过期清理（HistoryCleanupJob）
public class TravelJavaServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(TravelJavaServerApplication.class, args);
    }

}
