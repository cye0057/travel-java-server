package org.example.traveljavaserver.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.example.traveljavaserver.dto.ChatRequestDTO;
import org.example.traveljavaserver.dto.TravelRequestDTO;
import org.example.traveljavaserver.service.TravelService;
import org.example.traveljavaserver.service.UserService;
import org.example.traveljavaserver.vo.Result;
import org.example.traveljavaserver.vo.TravelRecommendVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/travel")
@RequiredArgsConstructor
public class TravelController {
    @Autowired
    private TravelService travelService;
    private final UserService userService;


    @PostMapping("/recommend")
    public Result<TravelRecommendVO> recommend (@Valid @RequestBody TravelRequestDTO travelRequestDTO){
        TravelRecommendVO travelRecommendVO = travelService.recommend(travelRequestDTO.getCity(), travelRequestDTO.getDays(), travelRequestDTO.getBudget());
        return Result.ok(travelRecommendVO);
    }

    @PostMapping(path = "/chat",produces = "text/event-stream")
    public SseEmitter chat(@Valid @RequestBody ChatRequestDTO chatRequestDTO,
                           HttpServletRequest request) {
        //软鉴权入口：带有效token=登录用户（对话进历史持久层），不带/无效=游客（功能照常用，仅无历史记录）
        Long userId = userService.resolveUserId(UserService.extractBearer(request.getHeader("Authorization")));
        return travelService.chat(chatRequestDTO.getSessionId(), chatRequestDTO.getMessage(), userId);
    }
}
