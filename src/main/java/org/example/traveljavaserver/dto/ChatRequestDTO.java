package org.example.traveljavaserver.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ChatRequestDTO {
    //会话标识：由前端生成（如 crypto.randomUUID()）并在同一会话中固定携带
    @NotBlank(message = "sessionId不能为空")
    private String sessionId;
    @NotBlank(message = "消息不能为空")
    private String message;
}
