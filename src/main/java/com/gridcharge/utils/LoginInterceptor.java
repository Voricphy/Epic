package com.gridcharge.utils;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class LoginInterceptor implements HandlerInterceptor {
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler){
//      获取当前线程中保存的用户，判断是否登录
        if(UserHolder.getUser() == null){
//          未登录，停止处理
            response.setStatus(401);
            return false;
        }
//      已登录，放行
        return true;
    }
}
