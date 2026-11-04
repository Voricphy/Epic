package com.gridcharge;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.web.servlet.MockMvc;

/** 验证应用装配与登录拦截；运行前需启动 Redis，后台业务任务尚未注册。 */
@SpringBootTest
@AutoConfigureMockMvc
class ScaffoldContextTest {
    @Autowired MockMvc mvc;
    @Autowired ApplicationContext context;

    @Test
    void openApiAvailable() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/user/reservations'].post").exists());
    }

    @Test
    void stationRequestRequiresLogin() throws Exception {
        mvc.perform(get("/user/stations"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(""));
    }

    @Test
    void redissonAvailableAndBackgroundTasksInactive() {
        assertNotNull(context.getBean(org.redisson.api.RedissonClient.class));
        assertTrue(
                context.getBeansOfType(com.gridcharge.task.ReservationStreamConsumer.class)
                        .isEmpty());
        assertTrue(context.getBeansOfType(com.gridcharge.task.RecoveryTask.class).isEmpty());
    }
}
