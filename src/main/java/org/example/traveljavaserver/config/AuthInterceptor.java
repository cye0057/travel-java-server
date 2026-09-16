package org.example.traveljavaserver.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.example.traveljavaserver.common.UnauthorizedException;
import org.example.traveljavaserver.service.UserService;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 强制登录拦截器：挂在 /api/user/** 的受保护接口上（register/login 排除在外）。
 * 校验通过把 userId 塞进 request attribute，下游用 @RequestAttribute 取——
 * 拦截器统一挡"没带票的"，业务代码不必每处手写 token 判空。
 */
@RequiredArgsConstructor
public class AuthInterceptor implements HandlerInterceptor {

    private final UserService userService;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String token = UserService.extractBearer(request.getHeader("Authorization"));
        Long userId = userService.resolveUserId(token);
        if (userId == null) {
            throw new UnauthorizedException("未登录或登录已过期");
        }
        request.setAttribute("userId", userId);
        return true;
    }
}
