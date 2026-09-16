package org.example.traveljavaserver.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.example.traveljavaserver.dto.LoginDTO;
import org.example.traveljavaserver.dto.RegisterDTO;
import org.example.traveljavaserver.service.HistoryService;
import org.example.traveljavaserver.service.UserService;
import org.example.traveljavaserver.vo.HistoryItemVO;
import org.example.traveljavaserver.vo.HistoryMessageVO;
import org.example.traveljavaserver.vo.LoginUserVO;
import org.example.traveljavaserver.vo.Result;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 个人中心后端：注册/登录/登出/我的信息/历史对话 */
@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;
    private final HistoryService historyService;

    /** 注册成功即视为登录，直接下发 token */
    @PostMapping("/register")
    public Result<LoginUserVO> register(@Valid @RequestBody RegisterDTO dto) {
        return Result.ok(userService.register(dto));
    }

    @PostMapping("/login")
    public Result<LoginUserVO> login(@Valid @RequestBody LoginDTO dto) {
        return Result.ok(userService.login(dto));
    }

    /** 登出：删除 Redis 里的 token，实现即时失效（幂等，永远成功） */
    @PostMapping("/logout")
    public Result<Void> logout(HttpServletRequest request) {
        userService.logout(UserService.extractBearer(request.getHeader("Authorization")));
        return Result.ok();
    }

    @GetMapping("/me")
    public Result<LoginUserVO> me(@RequestAttribute("userId") Long userId) {
        return Result.ok(userService.currentUser(userId));
    }

    /** 历史会话列表（"我的"页进入时调用；已登录用户在 Redis 缓存 + MySQL 回源） */
    @GetMapping("/history")
    public Result<List<HistoryItemVO>> history(@RequestAttribute("userId") Long userId) {
        return Result.ok(historyService.listHistory(userId));
    }

    /** 打开某段历史：完整消息用于前端回放 */
    @GetMapping("/history/{sessionId}")
    public Result<List<HistoryMessageVO>> historyDetail(@RequestAttribute("userId") Long userId,
                                                        @PathVariable String sessionId) {
        return Result.ok(historyService.sessionMessages(userId, sessionId));
    }
}
