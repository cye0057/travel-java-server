package org.example.traveljavaserver.config;

import org.example.traveljavaserver.service.UserService;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** 注册鉴权拦截器：/api/user/** 需登录，注册/登录两个入口本身放开 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final UserService userService;

    public WebConfig(UserService userService) {
        this.userService = userService;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new AuthInterceptor(userService))
                .addPathPatterns("/api/user/**")
                .excludePathPatterns("/api/user/register", "/api/user/login");
    }
}
