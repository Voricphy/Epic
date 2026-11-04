package com.gridcharge;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.gridcharge.config.GlobalExceptionHandler;
import com.gridcharge.controller.user.ReservationController;
import com.gridcharge.dto.ReservationDTO;
import com.gridcharge.dto.UserDTO;
import com.gridcharge.mq.ReservationProducer;
import com.gridcharge.service.ReservationService;
import com.gridcharge.service.ReservationSubmitFacade;
import com.gridcharge.utils.SnowflakeIdWorker;
import com.gridcharge.utils.UserHolder;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 使用真实 Controller、Facade、Service、Mapper 和数据库事务；模拟 MQ 和 Redis 边界。 */
@SpringJUnitConfig({ReservationConsumeTest.Config.class,
        ReservationSubmitFacade.class, ReservationController.class})
class ReservationSubmitFlowTest {
    private static final String REQUEST = "{\"slotId\":10,\"requestId\":\"request-2001\"}";

    @Autowired ReservationController controller;
    @Autowired ReservationService service;
    @Autowired ReservationSubmitFacade facade;
    @Autowired JdbcTemplate jdbc;
    @MockBean SnowflakeIdWorker snowflakeIdWorker;
    @MockBean RedissonClient redissonClient;
    @MockBean ReservationProducer producer;

    private RLock lock;
    private MockMvc mvc;

    @BeforeEach
    void setUp() throws Exception {
        ReservationConsumeTest.resetDatabase(jdbc);
        when(snowflakeIdWorker.nextId()).thenReturn(2001L);
        lock = mock(RLock.class);
        when(redissonClient.getLock("lock:reservation:user:2")).thenReturn(lock);
        when(lock.tryLock(5, TimeUnit.SECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        UserDTO user = new UserDTO();
        user.setId(2L);
        UserHolder.saveUser(user);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @AfterEach
    void clearUser() {
        UserHolder.removeUser();
    }

    @Test
    void asyncEndpointAcceptsWithoutReservingAndQueryShowsConsumedResult() throws Exception {
        mvc.perform(post("/user/reservations").contentType(MediaType.APPLICATION_JSON).content(REQUEST))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value("2001"))
                .andExpect(jsonPath("$.data.status").value("PROCESSING"));
        assertEquals(0, reserved());
        verify(producer).send(2001L);
        verify(lock).unlock();

        service.consume(2001L);
        mvc.perform(get("/user/reservations/2001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUCCESS"));
        assertEquals(1, reserved());
    }

    @Test
    void snowflakeIdSurvivesCreateQueryAndCancelWithoutPrecisionLoss() throws Exception {
        long id = new SnowflakeIdWorker(5, 6).nextId();
        assertTrue(id > 9007199254740991L);
        when(snowflakeIdWorker.nextId()).thenReturn(id);
        mvc.perform(post("/user/reservations").contentType(MediaType.APPLICATION_JSON).content(REQUEST))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(Long.toString(id)));
        verify(producer).send(id);
        assertEquals(id, service.get(2L, id).getId());
        service.consume(id);
        mvc.perform(get("/user/reservations/{id}", id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(Long.toString(id)))
                .andExpect(jsonPath("$.data.status").value("SUCCESS"));
        mvc.perform(post("/user/reservations/{id}/cancel", id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(Long.toString(id)))
                .andExpect(jsonPath("$.data.status").value("CANCELLED"));
        assertEquals(0, reserved());
    }

    @Test
    void publisherRunsAfterCommitAndConsumerCanImmediatelyReadOrder() {
        doAnswer(invocation -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            assertEquals("PROCESSING", jdbc.queryForObject(
                    "SELECT status FROM tb_reservation WHERE id=2001", String.class));
            // 模拟消息发出后消费者立即执行，它必须能读取已提交的订单。
            service.consume(invocation.getArgument(0, Long.class));
            return null;
        }).when(producer).send(2001L);

        assertEquals("PROCESSING", facade.submit(2L, dto(), false).getStatus());
        assertEquals("SUCCESS", service.get(2L, 2001L).getStatus());
        assertEquals(1, reserved());
    }

    @Test
    void syncEndpointCompletesImmediatelyAndDoesNotPublish() throws Exception {
        mvc.perform(post("/user/reservations/sync").contentType(MediaType.APPLICATION_JSON).content(REQUEST))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUCCESS"));
        assertEquals(1, reserved());
        verifyNoInteractions(producer);
    }

    @Test
    void publishingFailureKeepsCommittedOrderAndSameRequestRetriesPublication() throws Exception {
        doThrow(new IllegalStateException("模拟 MQ 超时"))
                .doNothing().when(producer).send(2001L);

        mvc.perform(post("/user/reservations").contentType(MediaType.APPLICATION_JSON).content(REQUEST))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value(0));
        assertEquals("PROCESSING", service.get(2L, 2001L).getStatus());
        assertEquals(0, reserved());

        mvc.perform(post("/user/reservations").contentType(MediaType.APPLICATION_JSON).content(REQUEST))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value("2001"));
        verify(producer, times(2)).send(2001L);
        verify(snowflakeIdWorker).nextId();
        verify(lock, times(2)).unlock();
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM tb_reservation WHERE user_id=2", Integer.class));
    }

    @Test
    void completedRequestReturnsOriginalOrderWithoutPublishingAgain() {
        facade.submit(2L, dto(), false);
        service.consume(2001L);

        assertEquals("SUCCESS", facade.submit(2L, dto(), false).getStatus());
        verify(producer).send(2001L);
        verify(snowflakeIdWorker).nextId();
        assertEquals(1, reserved());
    }

    @Test
    void sameRequestCannotBeReusedForAnotherSlot() throws Exception {
        facade.submit(2L, dto(), false);

        mvc.perform(post("/user/reservations").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"slotId\":11,\"requestId\":\"request-2001\"}"))
                .andExpect(status().isConflict());
        verify(producer).send(2001L);
        verify(snowflakeIdWorker).nextId();
    }

    @Test
    void failedSyncTransactionRollsBackInsertedOrderAndCapacity() {
        jdbc.execute("ALTER TABLE tb_reservation ADD CONSTRAINT reject_success CHECK (status <> 'SUCCESS')");

        assertThrows(DataAccessException.class, () -> facade.submit(2L, dto(), true));

        assertEquals(0, reserved());
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM tb_reservation WHERE id=2001", Integer.class));
        verifyNoInteractions(producer);
        verify(lock).unlock();
    }

    @Test
    void queryDoesNotExposeAnotherUsersOrder() throws Exception {
        // 测试初始订单 1001 属于用户 1，当前登录用户是 2。
        mvc.perform(get("/user/reservations/1001"))
                .andExpect(status().isNotFound());
    }

    private ReservationDTO dto() {
        ReservationDTO dto = new ReservationDTO();
        dto.setSlotId(10L);
        dto.setRequestId("request-2001");
        return dto;
    }

    private int reserved() {
        return jdbc.queryForObject("SELECT reserved FROM tb_station_slot WHERE id=10", Integer.class);
    }
}
