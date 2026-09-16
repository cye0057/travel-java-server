package org.example.traveljavaserver.service;

import jakarta.validation.constraints.NotBlank;
import org.example.traveljavaserver.vo.TravelRecommendVO;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

public interface TravelService {
    TravelRecommendVO recommend(String city , Integer days , String budget);

    /** userId 为 null 表示游客：对话不进历史持久层（设计意图见 impl 注释） */
    SseEmitter chat(String sessionId, String message, Long userId);
}
