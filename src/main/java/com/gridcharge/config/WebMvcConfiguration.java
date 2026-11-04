package com.gridcharge.config;

import com.gridcharge.utils.LoginInterceptor;
import com.gridcharge.utils.AdminInterceptor;
import com.gridcharge.utils.RefreshTokenInterceptor;
import io.swagger.v3.oas.models.*;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.web.servlet.config.annotation.*;

@Configuration
public class WebMvcConfiguration implements WebMvcConfigurer {
    @Autowired
    private RefreshTokenInterceptor refreshTokenInterceptor;
    @Autowired
    private LoginInterceptor loginInterceptor;
    @Autowired
    private AdminInterceptor adminInterceptor;
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
//      刷新拦截器
        registry.addInterceptor(refreshTokenInterceptor)
                .addPathPatterns("/**")
                .order(0);
//      登录拦截器
        registry.addInterceptor(loginInterceptor)
                .excludePathPatterns(
                        "/user/auth/code",
                        "/user/auth/login",
                        "/admin/auth/login",
                        "/swagger-ui.html",
                        "/swagger-ui/**",
                        "/v3/api-docs/**",
                        "/error"
                )
                .order(1);
        registry.addInterceptor(adminInterceptor)
                .addPathPatterns("/admin/**")
                .excludePathPatterns("/admin/auth/login")
                .order(2);
    }

    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()
                .info(new Info().title("Epic 学习骨架").version("1.0.0"))
                .components(
                        new Components()
                                .addSecuritySchemes(
                                        "Token",
                                        new SecurityScheme()
                                                .type(SecurityScheme.Type.APIKEY)
                                                .in(SecurityScheme.In.HEADER)
                                                .name("authorization")
                                                .description("直接填写登录返回的 token")))
                .addSecurityItem(new SecurityRequirement().addList("Token"));
    }
}
