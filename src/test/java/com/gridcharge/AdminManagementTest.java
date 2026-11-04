package com.gridcharge;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gridcharge.config.GlobalExceptionHandler;
import com.gridcharge.config.WebMvcConfiguration;
import com.gridcharge.controller.admin.AdminAuthController;
import com.gridcharge.controller.admin.StationController;
import com.gridcharge.controller.user.ReservationController;
import com.gridcharge.controller.user.UserController;
import com.gridcharge.dto.StationDTO;
import com.gridcharge.dto.SlotDTO;
import com.gridcharge.dto.ReservationDTO;
import com.gridcharge.exception.BusinessException;
import com.gridcharge.mapper.*;
import com.gridcharge.service.AdminAuthService;
import com.gridcharge.service.AdminTransactionLock;
import com.gridcharge.service.AdminCancellationMailSender;
import com.gridcharge.service.StationService;
import com.gridcharge.service.StationBloomFilter;
import com.gridcharge.service.ReservationService;
import com.gridcharge.service.ReservationSubmitFacade;
import com.gridcharge.service.impl.ReservationServiceImpl;
import com.gridcharge.service.impl.UserServiceImpl;
import com.gridcharge.service.impl.StationServiceImpl;
import com.gridcharge.utils.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;
import java.time.LocalDateTime;
import java.math.BigDecimal;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.redisson.api.RLock;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.*;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.TestPropertySource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

/** 真实 MVC 配置、拦截器、Service、MyBatis 和 H2 事务；Redis/Redisson 使用模拟边界。 */
@SpringJUnitConfig(AdminManagementTest.Config.class)
@WebAppConfiguration
@TestPropertySource(properties = {"spring.mail.username=admin-notify@example.test", "spring.mail.password=test-only", "epic.admin.password=test-admin-password"})
class AdminManagementTest {
    private static final String STATION = """
            {"name":"测试站点","address":"福州大学城","longitude":119.198,
             "latitude":26.058,"maxPowerKw":480,"pileCount":8,"status":1}
            """;
    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired StationService stations;
    @Autowired StationBloomFilter stationBloomFilter;
    @SpyBean StationMapper stationMapper;
    @Autowired ReservationService reservations;
    @Autowired AdminCancellationMailSender cancellationMailSender;
    @Autowired PlatformTransactionManager transactionManager;
    @MockBean StringRedisTemplate redis;
    @MockBean RedissonClient redisson;
    @MockBean SnowflakeIdWorker snowflakeIdWorker;
    @MockBean JavaMailSender mailSender;
    @MockBean ReservationSubmitFacade reservationSubmitFacade;
    private final ObjectMapper json = new ObjectMapper();
    private final Map<String, Map<Object, Object>> sessions = new HashMap<>();
    private final Map<String, String> cache = new ConcurrentHashMap<>();
    private final Set<String> bloomIds = ConcurrentHashMap.newKeySet();
    private RBloomFilter<String> bloom;
    private MockMvc mvc;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        UserHolder.removeUser();
        sessions.clear();
        cache.clear();
        bloomIds.clear();
        jdbc.execute("DROP ALL OBJECTS");
        jdbc.execute("CREATE TABLE tb_user(id BIGINT PRIMARY KEY, email VARCHAR(254), nickname VARCHAR(64), role VARCHAR(16))");
        jdbc.update("INSERT INTO tb_user VALUES (1,'admin@gridcharge.local','管理员','ADMIN'),(2,'driver@gridcharge.local','车主','USER')");
        jdbc.execute("""
                CREATE TABLE tb_station(id BIGINT AUTO_INCREMENT PRIMARY KEY, name VARCHAR(100),
                address VARCHAR(255), longitude DECIMAL(9,6), latitude DECIMAL(8,6),
                max_power_kw DECIMAL(10,2), pile_count INT, status INT)
                """);
        jdbc.execute("""
                CREATE TABLE tb_station_slot(id BIGINT AUTO_INCREMENT PRIMARY KEY, station_id BIGINT,
                start_time TIMESTAMP, end_time TIMESTAMP, quota INT, power_per_car_kw DECIMAL(10,2),
                reserved INT DEFAULT 0 NOT NULL, status INT DEFAULT 1 NOT NULL, price DECIMAL(8,2),
                UNIQUE(station_id,start_time,end_time), FOREIGN KEY(station_id) REFERENCES tb_station(id))
                """);
        jdbc.execute("""
                CREATE TABLE tb_reservation(id BIGINT PRIMARY KEY, user_id BIGINT NOT NULL,
                slot_id BIGINT NOT NULL, request_id VARCHAR(64), status VARCHAR(16) NOT NULL,
                failure_reason VARCHAR(255), UNIQUE(user_id,request_id),
                FOREIGN KEY(slot_id) REFERENCES tb_station_slot(id))
                """);
        new ResourceDatabasePopulator(new ClassPathResource(
                "db/migrations/20261025_admin_cancellation_mail.sql")).execute(jdbc.getDataSource());
        HashOperations<String, Object, Object> hashes = mock(HashOperations.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForHash()).thenReturn(hashes);
        when(redis.opsForValue()).thenReturn(values);
        doAnswer(call -> {
            sessions.put(call.getArgument(0), new HashMap<>(call.getArgument(1, Map.class)));
            return null;
        }).when(hashes).putAll(anyString(), anyMap());
        when(hashes.entries(anyString())).thenAnswer(call ->
                new HashMap<>(sessions.getOrDefault(call.getArgument(0), Map.of())));
        when(values.get(anyString())).thenAnswer(call -> cache.get(call.getArgument(0)));
        doAnswer(call -> { cache.put(call.getArgument(0), call.getArgument(1)); return null; })
                .when(values).set(anyString(), anyString());
        doAnswer(call -> { cache.put(call.getArgument(0), call.getArgument(1)); return null; })
                .when(values).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));
        when(redis.delete(anyString())).thenAnswer(call -> {
            String key = call.getArgument(0);
            boolean sessionRemoved = sessions.remove(key) != null;
            boolean cacheRemoved = cache.remove(key) != null;
            return sessionRemoved || cacheRemoved;
        });
        when(redis.expire(anyString(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        // 按 key 模拟可重入互斥，避免所有 tryLock 恒为 true 掩盖并发问题。
        Map<String, RLock> locks = new ConcurrentHashMap<>();
        when(redisson.getLock(anyString())).thenAnswer(call ->
                locks.computeIfAbsent(call.getArgument(0), this::localLock));
        bloom = mock(RBloomFilter.class);
        when(redisson.<String>getBloomFilter(RedisKeyConstant.STATION_BLOOM, StringCodec.INSTANCE)).thenReturn(bloom);
        when(bloom.tryInit(anyLong(), anyDouble())).thenReturn(true);
        when(bloom.isExists()).thenAnswer(call -> !bloomIds.isEmpty());
        when(bloom.contains(anyString())).thenAnswer(call -> bloomIds.contains(call.getArgument(0)));
        when(bloom.add(anyString())).thenAnswer(call -> bloomIds.add(call.getArgument(0)));
        when(bloom.add(anyCollection())).thenAnswer(call -> {
            long added = 0;
            Collection<String> ids = call.getArgument(0);
            for (String id : ids) {
                if (bloomIds.add(id)) added++;
            }
            return added;
        });
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Test
    void passwordLoginIssuesAdminSessionAndRefreshesTtl() throws Exception {
        String token = login();
        assertEquals("ADMIN", sessions.get(RedisKeyConstant.LOGIN_USER_KEY + token).get("role"));
        mvc.perform(get("/admin/stations").header("authorization", token)).andExpect(status().isOk());
        verify(redis, times(2)).expire(RedisKeyConstant.LOGIN_USER_KEY + token,
                RedisKeyConstant.LOGIN_USER_TTL, TimeUnit.MINUTES);
        assertNull(UserHolder.getUser());
    }

    @ParameterizedTest
    @ValueSource(strings = {"admin", "user"})
    void logoutInvalidatesOnlyCurrentSessionAndRejectsTokenReuse(String side) throws Exception {
        String token = loginForSide(side);
        String otherToken = loginForSide(side);
        String path = "/" + side + "/auth/logout";
        mvc.perform(post(path).header("authorization", token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(1));
        assertFalse(sessions.containsKey(RedisKeyConstant.LOGIN_USER_KEY + token));
        assertTrue(sessions.containsKey(RedisKeyConstant.LOGIN_USER_KEY + otherToken));
        assertNull(UserHolder.getUser());
        mvc.perform(post(path).header("authorization", token)).andExpect(status().isUnauthorized());
        mvc.perform(get("/user/reservations/9999").header("authorization", token))
                .andExpect(status().isUnauthorized());
        // 另一个会话仍能通过鉴权，随后也能独立登出。
        mvc.perform(post(path).header("authorization", otherToken)).andExpect(status().isOk());
        verify(redis).delete(RedisKeyConstant.LOGIN_USER_KEY + token);
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM tb_user", Integer.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"admin", "user"})
    void logoutRequiresAValidSessionAndReportsRedisFailure(String side) throws Exception {
        String path = "/" + side + "/auth/logout";
        mvc.perform(post(path)).andExpect(status().isUnauthorized());
        mvc.perform(post(path).header("authorization", "expired")).andExpect(status().isUnauthorized());
        String token = loginForSide(side);
        doThrow(new org.springframework.data.redis.RedisConnectionFailureException("test unavailable"))
                .when(redis).delete(RedisKeyConstant.LOGIN_USER_KEY + token);
        mvc.perform(post(path).header("authorization", token))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value(0));
        assertTrue(sessions.containsKey(RedisKeyConstant.LOGIN_USER_KEY + token));
        assertNull(UserHolder.getUser());
    }

    @Test
    void ordinaryUserCannotCallAdminLogout() throws Exception {
        String token = loginForSide("user");
        mvc.perform(post("/admin/auth/logout").header("authorization", token))
                .andExpect(status().isForbidden());
        assertTrue(sessions.containsKey(RedisKeyConstant.LOGIN_USER_KEY + token));
        verify(redis, never()).delete(RedisKeyConstant.LOGIN_USER_KEY + token);
    }

    private String loginForSide(String side) throws Exception {
        if ("admin".equals(side)) return login();
        cache.put("gc:auth:code:driver@gridcharge.local", "123456");
        String response = mvc.perform(post("/user/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"driver@gridcharge.local\",\"code\":\"123456\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(1))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(response).get("data").asText();
    }

    @Test
    void wrongOrMissingPasswordDoesNotCreateSession() throws Exception {
        mvc.perform(post("/admin/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"password\":\"wrong\"}")).andExpect(status().isUnauthorized());
        mvc.perform(post("/admin/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{}")).andExpect(status().isBadRequest());
        assertTrue(sessions.isEmpty());
    }

    @Test
    void passwordCannotPromoteOrdinaryUser() throws Exception {
        jdbc.update("UPDATE tb_user SET role='USER' WHERE id=1");
        mvc.perform(post("/admin/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"password\":\"test-admin-password\"}")).andExpect(status().isForbidden());
        assertTrue(sessions.isEmpty());
    }

    @Test
    void allStationRoutesRejectAnonymousAndOrdinaryUsers() throws Exception {
        sessions.put(RedisKeyConstant.LOGIN_USER_KEY + "user", Map.of("id", "2", "role", "USER"));
        for (var request : List.of(get("/admin/stations"), get("/admin/stations/1"),
                post("/admin/stations"), put("/admin/stations/1"), delete("/admin/stations/1"),
                post("/admin/stations/1/slots"), post("/admin/stations/slots/1/close"),
                post("/admin/stations/slots/1/cancel"), post("/admin/stations/slots/1/open"))) {
            mvc.perform(request).andExpect(status().isUnauthorized());
            mvc.perform(request.header("authorization", "user")).andExpect(status().isForbidden());
            assertNull(UserHolder.getUser());
        }
        mvc.perform(get("/admin/stations").header("authorization", "expired"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void revokedRoleRejectsExistingAdminToken() throws Exception {
        String token = login();
        jdbc.update("UPDATE tb_user SET role='USER' WHERE id=1");
        mvc.perform(get("/admin/stations").header("authorization", token)).andExpect(status().isForbidden());
        assertNull(UserHolder.getUser());
    }

    @Test
    void adminCanCreateReadFilterUpdateAndDeleteStation() throws Exception {
        String token = login();
        long id = create(token);
        mvc.perform(get("/admin/stations/{id}", id).header("authorization", token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.name").value("测试站点"));
        mvc.perform(get("/admin/stations").param("name", "测试").param("size", "1")
                .header("authorization", token)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records.length()").value(1));
        mvc.perform(get("/admin/stations").param("name", "不存在").header("authorization", token))
                .andExpect(jsonPath("$.data.total").value(0));
        mvc.perform(get("/admin/stations").param("page", "2").param("size", "1")
                .header("authorization", token)).andExpect(jsonPath("$.data.records.length()").value(0));
        assertEquals("测试站点", stations.detail(id).getName()); // 冷缓存能回源
        assertTrue(cache.containsKey(RedisKeyConstant.station(id)));
        mvc.perform(put("/admin/stations/{id}", id).header("authorization", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(STATION.replace("测试站点", "修改后的站点").replace("\"status\":1", "\"status\":0")))
                .andExpect(status().isOk());
        assertFalse(cache.containsKey(RedisKeyConstant.station(id)));
        assertEquals("修改后的站点", stations.detail(id).getName());
        mvc.perform(get("/admin/stations/{id}", id).header("authorization", token))
                .andExpect(jsonPath("$.data.status").value(0));
        mvc.perform(delete("/admin/stations/{id}", id).header("authorization", token)).andExpect(status().isOk());
        assertFalse(cache.containsKey(RedisKeyConstant.station(id)));
        mvc.perform(get("/admin/stations/{id}", id).header("authorization", token)).andExpect(status().isNotFound());
        assertThrows(com.gridcharge.exception.BusinessException.class, () -> stations.detail(id));
    }

    @Test
    void invalidParametersAndMissingStationsAreRejected() throws Exception {
        String token = login();
        mvc.perform(post("/admin/stations").header("authorization", token)
                .contentType(MediaType.APPLICATION_JSON).content(STATION.replace("\"pileCount\":8", "\"pileCount\":0")))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/admin/stations").param("page", "0").header("authorization", token))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/admin/stations").param("size", "101").header("authorization", token))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/admin/stations/999").header("authorization", token)
                .contentType(MediaType.APPLICATION_JSON).content(STATION)).andExpect(status().isNotFound());
        mvc.perform(delete("/admin/stations/999").header("authorization", token)).andExpect(status().isNotFound());
    }

    @Test
    void existingSlotsPreventDeletionAndIncompatibleCapacityReduction() throws Exception {
        String token = login();
        long id = create(token);
        jdbc.update("INSERT INTO tb_station_slot(id,station_id,start_time,end_time,quota,power_per_car_kw) VALUES (10,?,DATEADD('DAY',1,CURRENT_TIMESTAMP),DATEADD('DAY',2,CURRENT_TIMESTAMP),8,60)", id);
        mvc.perform(delete("/admin/stations/{id}", id).header("authorization", token)).andExpect(status().isConflict());
        mvc.perform(put("/admin/stations/{id}", id).header("authorization", token)
                .contentType(MediaType.APPLICATION_JSON).content(STATION.replace("\"pileCount\":8", "\"pileCount\":4")))
                .andExpect(status().isConflict());
        assertEquals(8, stations.adminDetail(id).getPileCount());
    }

    @Test
    void cacheInvalidationWaitsForCommitAndDoesNotRunOnRollback() throws Exception {
        long id = create(login());
        stations.detail(id);
        String oldCache = cache.get(RedisKeyConstant.station(id));
        StationDTO dto = json.readValue(STATION.replace("测试站点", "回滚站点"), StationDTO.class);
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            stations.save(id, dto);
            assertEquals(oldCache, cache.get(RedisKeyConstant.station(id)));
            tx.setRollbackOnly();
        });
        assertEquals("测试站点", stations.adminDetail(id).getName());
        assertEquals(oldCache, cache.get(RedisKeyConstant.station(id)));
    }

    @Test
    void bloomRejectsUnknownIdsWithoutDatabaseLookupOrEmptyCachePollution() {
        stationBloomFilter.run(null);
        clearInvocations(stationMapper);
        BusinessException error = assertThrows(BusinessException.class, () -> stations.detail(999L));
        assertEquals(404, error.getStatus());
        verify(stationMapper, never()).findById(anyLong());
        assertFalse(cache.containsKey(RedisKeyConstant.station(999L)));
    }

    @Test
    void bloomFalsePositiveUsesShortEmptyCacheAndReloadsAfterExpiry() {
        stationBloomFilter.run(null);
        bloomIds.add("999"); // 模拟布隆误判。
        clearInvocations(stationMapper);
        assertThrows(BusinessException.class, () -> stations.detail(999L));
        assertThrows(BusinessException.class, () -> stations.detail(999L));
        assertEquals("", cache.get(RedisKeyConstant.station(999L)));
        verify(stationMapper).findById(999L);
        verify(redis.opsForValue()).set(eq(RedisKeyConstant.station(999L)), eq(""),
                longThat(ttl -> ttl >= 180 && ttl <= 240), eq(TimeUnit.SECONDS));
        cache.remove(RedisKeyConstant.station(999L)); // 模拟 TTL 到期。
        assertThrows(BusinessException.class, () -> stations.detail(999L));
        verify(stationMapper, times(2)).findById(999L);
    }

    @Test
    void bloomStartupLoadsHistoricalIdsInPagesAndRecoversMissingBitmap() {
        jdbc.update("INSERT INTO tb_station(id,name) SELECT X,'历史站点' FROM SYSTEM_RANGE(1,1001)");
        stationBloomFilter.run(null);
        verify(stationMapper).idsAfter(0L);
        verify(stationMapper).idsAfter(1000L);
        verify(stationMapper).idsAfter(1001L);
        assertEquals(1002, bloomIds.size()); // 包括用于空库初始化的哨兵。
        assertEquals("1", cache.get(RedisKeyConstant.STATION_BLOOM_READY));
        bloomIds.clear(); // 就绪标记仍在，但位图丢失。
        assertEquals("历史站点", stations.detail(1001L).getName());
        assertTrue(bloomIds.contains("1001"));
        verify(stationMapper, times(2)).idsAfter(0L);
    }

    @Test
    void incompleteBloomInitializationFallsBackWithoutFalseNotFound() {
        jdbc.update("INSERT INTO tb_station(id,name) VALUES(100,'已有站点')");
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("test scan failure"))
                .when(stationMapper).idsAfter(0L);
        assertEquals("已有站点", stations.detail(100L).getName());
        assertFalse(cache.containsKey(RedisKeyConstant.STATION_BLOOM_READY));
        verify(stationMapper).findById(100L);
    }

    @Test
    void bloomFailureFallsBackAndWarmCacheDoesNotConsultBloom() throws Exception {
        long id = create(login());
        when(bloom.contains(Long.toString(id))).thenThrow(new IllegalStateException("test bloom unavailable"));
        assertEquals("测试站点", stations.detail(id).getName());
        clearInvocations(bloom, stationMapper);
        assertEquals("测试站点", stations.detail(id).getName());
        verifyNoInteractions(bloom);
        verify(stationMapper, never()).findById(anyLong());
    }

    @Test
    void createClearsEarlierEmptyCacheAndDeleteKeepsBloomMembership() throws Exception {
        stationBloomFilter.run(null);
        bloomIds.add("1");
        assertThrows(BusinessException.class, () -> stations.detail(1L));
        assertEquals("", cache.get(RedisKeyConstant.station(1)));
        long id = create(login());
        assertEquals(1, id);
        assertFalse(cache.containsKey(RedisKeyConstant.station(id)));
        assertEquals("测试站点", stations.detail(id).getName());
        stations.delete(id);
        assertTrue(bloomIds.contains(Long.toString(id)));
        clearInvocations(stationMapper);
        assertThrows(BusinessException.class, () -> stations.detail(id));
        assertThrows(BusinessException.class, () -> stations.detail(id));
        verify(stationMapper).findById(id);
    }

    @Test
    void bloomWriteFailureRollsBackNewStation() throws Exception {
        stationBloomFilter.run(null);
        doThrow(new IllegalStateException("test bloom write failure")).when(bloom).add("1");
        StationDTO dto = json.readValue(STATION, StationDTO.class);
        assertThrows(IllegalStateException.class, () -> stations.save(null, dto));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM tb_station", Integer.class));
        assertFalse(redisson.getLock(RedisKeyConstant.STATION_BLOOM_LOCK).isHeldByCurrentThread());
    }

    @Test
    void rolledBackCreateOnlyLeavesHarmlessBloomFalsePositive() throws Exception {
        StationDTO dto = json.readValue(STATION, StationDTO.class);
        long id = new TransactionTemplate(transactionManager).execute(tx -> {
            long created = stations.save(null, dto);
            tx.setRollbackOnly();
            return created;
        });
        assertTrue(bloomIds.contains(Long.toString(id)));
        assertThrows(BusinessException.class, () -> stations.detail(id));
        assertEquals("", cache.get(RedisKeyConstant.station(id)));
    }

    @Test
    void bloomRebuildWaitsForConcurrentStationCreationToCommit() throws Exception {
        StationDTO dto = json.readValue(STATION, StationDTO.class);
        runBeforeCommit(() -> stations.save(null, dto), () -> stationBloomFilter.run(null));
        long id = jdbc.queryForObject("SELECT id FROM tb_station", Long.class);
        assertTrue(bloomIds.contains(Long.toString(id)));
        assertEquals("测试站点", stations.detail(id).getName());
    }

    @Test
    void publishCloseAndCancelThroughAdminEndpoints() throws Exception {
        String token = login();
        long stationId = create(token);
        String body = slotJson(slotDto());
        String response = mvc.perform(post("/admin/stations/{id}/slots", stationId)
                        .header("authorization", token).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(1))
                .andReturn().getResponse().getContentAsString();
        long slotId = json.readTree(response).get("data").asLong();
        assertEquals(0, reserved(slotId));
        assertEquals(1, slotStatus(slotId));
        addOrder(1001, slotId, "SUCCESS");
        addOrder(1002, slotId, "PROCESSING");
        addOrder(1003, slotId, "FAILED");
        addOrder(1004, slotId, "CANCELLED");
        jdbc.update("UPDATE tb_station_slot SET reserved=1 WHERE id=?", slotId);

        for (int i = 0; i < 2; i++) {
            mvc.perform(post("/admin/stations/slots/{id}/close", slotId).header("authorization", token))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(1));
        }
        assertEquals(0, slotStatus(slotId));
        assertEquals(1, reserved(slotId));
        assertEquals("SUCCESS", orderStatus(1001));
        assertEquals("PROCESSING", orderStatus(1002));
        assertEquals(0, mailTaskCount());
        assertThrows(BusinessException.class, () -> reservations.submit(20L, reservationDto(slotId), false));

        mvc.perform(post("/admin/stations/slots/{id}/cancel", slotId).header("authorization", token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").value(2));
        mvc.perform(post("/admin/stations/slots/{id}/cancel", slotId).header("authorization", token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").value(0));
        assertEquals(2, mailTaskCount());
        verifyNoInteractions(mailSender);
        assertEquals(0, reserved(slotId));
        assertEquals("CANCELLED", orderStatus(1001));
        assertEquals("CANCELLED", orderStatus(1002));
        assertEquals("FAILED", orderStatus(1003));
        assertNull(jdbc.queryForObject("SELECT failure_reason FROM tb_reservation WHERE id=1004", String.class));
        assertEquals("管理员取消该时段预约", jdbc.queryForObject(
                "SELECT failure_reason FROM tb_reservation WHERE id=1001", String.class));
        reservations.consume(1001L);
        reservations.consume(1002L);
        assertEquals(0, reserved(slotId));
        assertEquals("CANCELLED", orderStatus(1002));
    }

    @Test
    void reopenIsIdempotentAndPreservesExistingReservations() throws Exception {
        String token = login();
        long slotId = stations.publish(create(token), slotDto());
        addOrder(1001, slotId, "SUCCESS");
        jdbc.update("UPDATE tb_station_slot SET reserved=1 WHERE id=?", slotId);
        stations.closeSlot(slotId);
        for (int i = 0; i < 2; i++) {
            mvc.perform(post("/admin/stations/slots/{id}/open", slotId).header("authorization", token))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(1));
        }
        assertEquals(1, slotStatus(slotId));
        assertEquals(1, reserved(slotId));
        assertEquals("SUCCESS", orderStatus(1001));
        assertEquals(0, mailTaskCount());
    }

    @Test
    void reopenAfterCancellationAllowsNewBookingWithoutRestoringOldOrders() throws Exception {
        String token = login();
        long slotId = stations.publish(create(token), slotDto());
        addOrder(1001, slotId, "SUCCESS");
        jdbc.update("UPDATE tb_station_slot SET reserved=1 WHERE id=?", slotId);
        stations.cancelSlot(slotId);
        mvc.perform(post("/admin/stations/slots/{id}/open", slotId).header("authorization", token))
                .andExpect(status().isOk());
        assertEquals(1, slotStatus(slotId));
        assertEquals(0, reserved(slotId));
        ReservationDTO retry = reservationDto(slotId);
        retry.setRequestId("request-1001");
        assertEquals("CANCELLED", reservations.submit(1001L, retry, true).getStatus());
        when(snowflakeIdWorker.nextId()).thenReturn(2001L);
        assertEquals("SUCCESS", reservations.submit(1001L, reservationDto(slotId), true).getStatus());
        assertEquals("CANCELLED", orderStatus(1001));
        assertEquals(1, reserved(slotId));
        assertEquals(1, mailTaskCount());
        verifyNoInteractions(mailSender);
    }

    @Test
    void reopenRejectsInvalidIdMissingSlotAndDisabledStation() throws Exception {
        String token = login();
        mvc.perform(post("/admin/stations/slots/0/open").header("authorization", token))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/admin/stations/slots/999/open").header("authorization", token))
                .andExpect(status().isNotFound());
        long stationId = create(token);
        long slotId = stations.publish(stationId, slotDto());
        stations.closeSlot(slotId);
        jdbc.update("UPDATE tb_station SET status=0 WHERE id=?", stationId);
        mvc.perform(post("/admin/stations/slots/{id}/open", slotId).header("authorization", token))
                .andExpect(status().isConflict());
        assertEquals(0, slotStatus(slotId));
    }

    @ParameterizedTest
    @ValueSource(ints = {-2, 1})
    void reopenRejectsStartedAndEndedSlots(int endOffsetHours) throws Exception {
        String token = login();
        long slotId = stations.publish(create(token), slotDto());
        stations.closeSlot(slotId);
        jdbc.update("UPDATE tb_station_slot SET start_time=?,end_time=? WHERE id=?",
                LocalDateTime.now().minusHours(3), LocalDateTime.now().plusHours(endOffsetHours), slotId);
        mvc.perform(post("/admin/stations/slots/{id}/open", slotId).header("authorization", token))
                .andExpect(status().isConflict());
        assertEquals(0, slotStatus(slotId));
    }

    @Test
    void reopeningRollsBackWithItsTransaction() throws Exception {
        long slotId = stations.publish(create(login()), slotDto());
        stations.closeSlot(slotId);
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            stations.openSlot(slotId);
            assertEquals(1, slotStatus(slotId));
            tx.setRollbackOnly();
        });
        assertEquals(0, slotStatus(slotId));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void reopeningAndCancellationSerializeInEitherOrder(boolean openFirst) throws Exception {
        long slotId = stations.publish(create(login()), slotDto());
        addOrder(1001, slotId, "SUCCESS");
        jdbc.update("UPDATE tb_station_slot SET reserved=1 WHERE id=?", slotId);
        stations.closeSlot(slotId);
        Runnable open = () -> stations.openSlot(slotId);
        Runnable cancel = () -> stations.cancelSlot(slotId);
        runBeforeCommit(openFirst ? open : cancel, openFirst ? cancel : open);
        assertEquals(openFirst ? 0 : 1, slotStatus(slotId));
        assertEquals("CANCELLED", orderStatus(1001));
        assertEquals(0, reserved(slotId));
        assertEquals(1, mailTaskCount());
    }

    @Test
    void publishValidatesTimeCapacityOverlapAndStationState() throws Exception {
        String token = login();
        long stationId = create(token);
        SlotDTO dto = slotDto();
        String body = slotJson(dto);
        mvc.perform(post("/admin/stations/999/slots").header("authorization", token)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isNotFound());
        mvc.perform(post("/admin/stations/{id}/slots", stationId).header("authorization", token)
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isBadRequest());
        dto.setEndTime(dto.getStartTime().minusMinutes(1));
        assertThrows(BusinessException.class, () -> stations.publish(stationId, dto));
        dto.setEndTime(dto.getStartTime().plusHours(1));
        dto.setStartTime(LocalDateTime.now().minusHours(1));
        assertThrows(BusinessException.class, () -> stations.publish(stationId, dto));
        dto.setStartTime(slotDto().getStartTime());
        dto.setQuota(9); // 只有 8 个充电桩。
        assertThrows(BusinessException.class, () -> stations.publish(stationId, dto));
        dto.setQuota(8);
        dto.setPowerPerCarKw(new BigDecimal("100")); // 总功率不足。
        assertThrows(BusinessException.class, () -> stations.publish(stationId, dto));
        dto.setPowerPerCarKw(new BigDecimal("30"));
        jdbc.update("UPDATE tb_station SET status=0 WHERE id=?", stationId);
        assertThrows(BusinessException.class, () -> stations.publish(stationId, dto));
        jdbc.update("UPDATE tb_station SET status=1 WHERE id=?", stationId);
        stations.publish(stationId, dto);
        dto.setStartTime(dto.getStartTime().plusMinutes(30));
        dto.setEndTime(dto.getEndTime().plusMinutes(30));
        assertThrows(BusinessException.class, () -> stations.publish(stationId, dto));
        dto.setStartTime(dto.getEndTime().minusMinutes(30)); // 与前一时段首尾相接。
        stations.publish(stationId, dto);
        for (String action : List.of("close", "cancel")) {
            mvc.perform(post("/admin/stations/slots/999/" + action).header("authorization", token))
                    .andExpect(status().isNotFound());
            mvc.perform(post("/admin/stations/slots/0/" + action).header("authorization", token))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void cancellationRollsBackCloseOrdersAndCapacityOnDatabaseFailure() throws Exception {
        long slotId = stations.publish(create(login()), slotDto());
        addOrder(1001, slotId, "SUCCESS");
        addOrder(1002, slotId, "PROCESSING");
        jdbc.update("UPDATE tb_station_slot SET reserved=1 WHERE id=?", slotId);
        // 最后清零名额时失败，之前的关闭、取消订单必须一起回滚。
        jdbc.execute("ALTER TABLE tb_station_slot ADD CONSTRAINT reject_clear CHECK(reserved > 0)");
        assertThrows(org.springframework.dao.DataAccessException.class, () -> stations.cancelSlot(slotId));
        assertEquals(1, slotStatus(slotId));
        assertEquals(1, reserved(slotId));
        assertEquals("SUCCESS", orderStatus(1001));
        assertEquals("PROCESSING", orderStatus(1002));
        assertEquals(0, mailTaskCount());
        jdbc.execute("ALTER TABLE tb_station_slot DROP CONSTRAINT reject_clear");
        assertEquals(2, stations.cancelSlot(slotId));
        assertEquals(0, reserved(slotId));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void cancellationAndConsumerSerializeInEitherOrder(boolean cancelFirst) throws Exception {
        long slotId = stations.publish(create(login()), slotDto());
        addOrder(1001, slotId, "PROCESSING");
        Runnable cancel = () -> stations.cancelSlot(slotId);
        Runnable consume = () -> reservations.consume(1001L);
        runBeforeCommit(cancelFirst ? cancel : consume, cancelFirst ? consume : cancel);
        assertEquals("CANCELLED", orderStatus(1001));
        assertEquals(0, reserved(slotId));
        assertEquals(0, slotStatus(slotId));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void cancellationAndSubmissionCannotLeaveAnActiveOrder(boolean cancelFirst) throws Exception {
        long slotId = stations.publish(create(login()), slotDto());
        when(snowflakeIdWorker.nextId()).thenReturn(2001L);
        Runnable cancel = () -> stations.cancelSlot(slotId);
        Runnable submit = () -> reservations.submit(2L, reservationDto(slotId), false);
        if (cancelFirst) {
            runBeforeCommit(cancel, () -> assertThrows(BusinessException.class, submit::run));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM tb_reservation", Integer.class));
        } else {
            runBeforeCommit(submit, cancel);
            assertEquals("CANCELLED", orderStatus(2001));
        }
        assertEquals(0, reserved(slotId));
        assertEquals(0, slotStatus(slotId));
    }

    @Test
    void concurrentOverlappingPublicationsAreSerializedByStationLock() throws Exception {
        long stationId = create(login());
        SlotDTO first = slotDto();
        SlotDTO second = slotDto();
        second.setStartTime(first.getStartTime().plusMinutes(30));
        second.setEndTime(first.getEndTime().plusMinutes(30));
        runBeforeCommit(() -> stations.publish(stationId, first),
                () -> assertThrows(BusinessException.class, () -> stations.publish(stationId, second)));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM tb_station_slot", Integer.class));
    }

    private void runBeforeCommit(Runnable first, Runnable second) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch firstDone = new CountDownLatch(1);
        CountDownLatch commit = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        try {
            Future<?> a = executor.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
                first.run();
                firstDone.countDown();
                try {
                    assertTrue(commit.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }));
            assertTrue(firstDone.await(5, TimeUnit.SECONDS));
            Future<?> b = executor.submit(() -> { secondStarted.countDown(); second.run(); });
            assertTrue(secondStarted.await(5, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> b.get(150, TimeUnit.MILLISECONDS));
            commit.countDown();
            a.get(10, TimeUnit.SECONDS);
            b.get(10, TimeUnit.SECONDS);
        } finally {
            commit.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private SlotDTO slotDto() {
        SlotDTO dto = new SlotDTO();
        dto.setStartTime(LocalDateTime.now().plusDays(1).withNano(0));
        dto.setEndTime(dto.getStartTime().plusHours(1));
        dto.setQuota(8);
        dto.setPowerPerCarKw(new BigDecimal("30"));
        dto.setPrice(new BigDecimal("1.50"));
        return dto;
    }

    private String slotJson(SlotDTO dto) throws Exception {
        return json.writeValueAsString(Map.of("startTime", dto.getStartTime().toString(),
                "endTime", dto.getEndTime().toString(), "quota", dto.getQuota(),
                "powerPerCarKw", dto.getPowerPerCarKw(), "price", dto.getPrice()));
    }

    private ReservationDTO reservationDto(long slotId) {
        ReservationDTO dto = new ReservationDTO();
        dto.setSlotId(slotId);
        dto.setRequestId("new-request");
        return dto;
    }

    private void addOrder(long id, long slotId, String status) {
        jdbc.update("INSERT INTO tb_user VALUES (?,?,'预约用户','USER')", id, "driver-" + id + "@example.test");
        jdbc.update("INSERT INTO tb_reservation VALUES (?,?,?, ?,?,NULL)", id, id, slotId, "request-" + id, status);
    }

    private int mailTaskCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM tb_admin_cancellation_mail", Integer.class);
    }

    @Test
    void cancellationMailWaitsForCommitAndUsesAffectedOrderSnapshots() throws Exception {
        long stationId = create(login());
        SlotDTO dto = slotDto();
        long slotId = stations.publish(stationId, dto);
        addOrder(1001, slotId, "SUCCESS");
        addOrder(1002, slotId, "PROCESSING");
        addOrder(1003, slotId, "FAILED");
        jdbc.update("UPDATE tb_station_slot SET reserved=1 WHERE id=?", slotId);
        SlotDTO other = slotDto();
        other.setStartTime(dto.getEndTime());
        other.setEndTime(dto.getEndTime().plusHours(1));
        long otherId = stations.publish(stationId, other);
        addOrder(1004, otherId, "SUCCESS");
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
                assertEquals(2, stations.cancelSlot(slotId));
                assertThrows(IllegalStateException.class, cancellationMailSender::sendPending);
                try {
                    executor.submit(cancellationMailSender::sendPending).get(5, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
                verifyNoInteractions(mailSender);
            });
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
        assertEquals(2, mailTaskCount());
        // 发信使用取消时的快照，不受后续资料修改影响。
        jdbc.update("UPDATE tb_user SET email='changed@example.test' WHERE id=1001");
        cancellationMailSender.sendPending();
        cancellationMailSender.sendPending();
        var messages = org.mockito.ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender, times(2)).send(messages.capture());
        SimpleMailMessage success = messages.getAllValues().stream()
                .filter(m -> m.getText().contains("预约编号：1001")).findFirst().orElseThrow();
        assertArrayEquals(new String[]{"driver-1001@example.test"}, success.getTo());
        assertEquals("admin-notify@example.test", success.getFrom());
        assertEquals("充电预约取消通知", success.getSubject());
        assertTrue(success.getText().contains("已成功预约"));
        assertTrue(success.getText().contains("测试站点"));
        assertTrue(success.getText().contains(dto.getStartTime().toLocalDate().toString()));
        assertTrue(messages.getAllValues().stream().anyMatch(m ->
                m.getText().contains("预约编号：1002") && m.getText().contains("预约申请")));
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM tb_admin_cancellation_mail WHERE status='SENT'", Integer.class));
        assertEquals("SUCCESS", orderStatus(1004));
    }

    @Test
    void mailFailureRetriesWithoutUndoingCancellation() throws Exception {
        long slotId = stations.publish(create(login()), slotDto());
        addOrder(1001, slotId, "SUCCESS");
        jdbc.update("UPDATE tb_station_slot SET reserved=1 WHERE id=?", slotId);
        stations.cancelSlot(slotId);
        doThrow(new MailSendException("模拟发送失败")).doNothing()
                .when(mailSender).send(any(SimpleMailMessage.class));
        cancellationMailSender.sendPending();
        assertEquals("CANCELLED", orderStatus(1001));
        assertEquals(0, reserved(slotId));
        assertEquals("PENDING", jdbc.queryForObject(
                "SELECT status FROM tb_admin_cancellation_mail WHERE reservation_id=1001", String.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT attempts FROM tb_admin_cancellation_mail WHERE reservation_id=1001", Integer.class));
        cancellationMailSender.sendPending(); // 尚未到重试时间，不发信。
        verify(mailSender).send(any(SimpleMailMessage.class));
        jdbc.update("UPDATE tb_admin_cancellation_mail SET next_attempt_at=DATEADD('SECOND',-1,CURRENT_TIMESTAMP)");
        cancellationMailSender.sendPending();
        verify(mailSender, times(2)).send(any(SimpleMailMessage.class));
        assertEquals("SENT", jdbc.queryForObject(
                "SELECT status FROM tb_admin_cancellation_mail WHERE reservation_id=1001", String.class));
        assertNull(jdbc.queryForObject(
                "SELECT last_error FROM tb_admin_cancellation_mail WHERE reservation_id=1001", String.class));
    }

    @Test
    void failureToPersistNotificationRollsBackCancellation() throws Exception {
        long slotId = stations.publish(create(login()), slotDto());
        addOrder(1001, slotId, "SUCCESS");
        jdbc.update("UPDATE tb_station_slot SET reserved=1 WHERE id=?", slotId);
        jdbc.execute("ALTER TABLE tb_admin_cancellation_mail ADD CONSTRAINT reject_mail CHECK(previous_status <> 'SUCCESS')");
        assertThrows(org.springframework.dao.DataAccessException.class, () -> stations.cancelSlot(slotId));
        assertEquals(1, slotStatus(slotId));
        assertEquals(1, reserved(slotId));
        assertEquals("SUCCESS", orderStatus(1001));
        assertEquals(0, mailTaskCount());
        verifyNoInteractions(mailSender);
    }

    @Test
    void concurrentMailWorkersOnlySendOnce() throws Exception {
        long slotId = stations.publish(create(login()), slotDto());
        addOrder(1001, slotId, "PROCESSING");
        stations.cancelSlot(slotId);
        CountDownLatch sending = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        doAnswer(call -> {
            sending.countDown();
            assertTrue(finish.await(5, TimeUnit.SECONDS));
            return null;
        }).when(mailSender).send(any(SimpleMailMessage.class));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = executor.submit(cancellationMailSender::sendPending);
            assertTrue(sending.await(5, TimeUnit.SECONDS));
            executor.submit(cancellationMailSender::sendPending).get(5, TimeUnit.SECONDS);
            finish.countDown();
            first.get(5, TimeUnit.SECONDS);
        } finally {
            finish.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
        verify(mailSender).send(any(SimpleMailMessage.class));
        assertEquals("SENT", jdbc.queryForObject(
                "SELECT status FROM tb_admin_cancellation_mail WHERE reservation_id=1001", String.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"SUCCESS", "PROCESSING"})
    void userCancellationReleasesOnlyItsOwnCapacityAndIsIdempotent(String initialStatus) throws Exception {
        long slotId = stations.publish(create(login()), slotDto());
        addOrder(1001, slotId, initialStatus);
        addOrder(1002, slotId, "SUCCESS");
        jdbc.update("UPDATE tb_station_slot SET reserved=? WHERE id=?",
                "SUCCESS".equals(initialStatus) ? 2 : 1, slotId);
        stations.closeSlot(slotId); // 关闭入口仍允许用户取消。
        sessions.put(RedisKeyConstant.LOGIN_USER_KEY + "owner", Map.of("id", "1001", "role", "USER"));
        for (int i = 0; i < 2; i++) {
            mvc.perform(post("/user/reservations/1001/cancel").header("authorization", "owner"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(1))
                    .andExpect(jsonPath("$.data.id").value("1001"))
                    .andExpect(jsonPath("$.data.status").value("CANCELLED"))
                    .andExpect(jsonPath("$.data.failureReason").value("用户主动取消预约"));
        }
        reservations.consume(1001L); // 迟到消息不能恢复已取消的预约。
        assertEquals("CANCELLED", orderStatus(1001));
        assertEquals("SUCCESS", orderStatus(1002));
        assertEquals(1, reserved(slotId));
        assertEquals(0, slotStatus(slotId));
        assertEquals(0, mailTaskCount());
        verifyNoInteractions(mailSender);
    }

    @Test
    void userCancellationRequiresLoginOwnershipAndValidId() throws Exception {
        long slotId = stations.publish(create(login()), slotDto());
        addOrder(1001, slotId, "PROCESSING");
        mvc.perform(post("/user/reservations/1001/cancel")).andExpect(status().isUnauthorized());
        mvc.perform(post("/user/reservations/1001/cancel").header("authorization", "expired"))
                .andExpect(status().isUnauthorized());
        sessions.put(RedisKeyConstant.LOGIN_USER_KEY + "user", Map.of("id", "2", "role", "USER"));
        mvc.perform(post("/user/reservations/1001/cancel").header("authorization", "user"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/user/reservations/9999/cancel").header("authorization", "user"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/user/reservations/0/cancel").header("authorization", "user"))
                .andExpect(status().isBadRequest());
        assertEquals("PROCESSING", orderStatus(1001));
        assertEquals(0, reserved(slotId));
    }

    @ParameterizedTest
    @ValueSource(strings = {"SUCCESS", "PROCESSING", "FAILED", "CANCELLED"})
    void userCancellationHonorsStatusAndStartTime(String initialStatus) throws Exception {
        long slotId = stations.publish(create(login()), slotDto());
        addOrder(1001, slotId, initialStatus);
        int before = "SUCCESS".equals(initialStatus) ? 1 : 0;
        jdbc.update("UPDATE tb_station_slot SET reserved=? WHERE id=?", before, slotId);
        sessions.put(RedisKeyConstant.LOGIN_USER_KEY + "owner", Map.of("id", "1001", "role", "USER"));
        if ("FAILED".equals(initialStatus)) {
            mvc.perform(post("/user/reservations/1001/cancel").header("authorization", "owner"))
                    .andExpect(status().isConflict());
        }
        jdbc.update("UPDATE tb_station_slot SET start_time=? WHERE id=?", LocalDateTime.now().minusHours(1), slotId);
        mvc.perform(post("/user/reservations/1001/cancel").header("authorization", "owner"))
                .andExpect("CANCELLED".equals(initialStatus) ? status().isOk() : status().isConflict());
        assertEquals(initialStatus, orderStatus(1001));
        assertEquals(before, reserved(slotId));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void userCancellationAndConsumerSerializeInEitherOrder(boolean cancelFirst) throws Exception {
        long slotId = stations.publish(create(login()), slotDto());
        addOrder(1001, slotId, "PROCESSING");
        Runnable cancel = () -> reservations.cancel(1001L, 1001L);
        Runnable consume = () -> reservations.consume(1001L);
        runBeforeCommit(cancelFirst ? cancel : consume, cancelFirst ? consume : cancel);
        assertEquals("CANCELLED", orderStatus(1001));
        assertEquals(0, reserved(slotId));
    }

    @Test
    void concurrentUserCancellationsOnlyReleaseOnce() throws Exception {
        long slotId = stations.publish(create(login()), slotDto());
        addOrder(1001, slotId, "SUCCESS");
        addOrder(1002, slotId, "SUCCESS");
        jdbc.update("UPDATE tb_station_slot SET reserved=2 WHERE id=?", slotId);
        Runnable cancel = () -> assertEquals("CANCELLED", reservations.cancel(1001L, 1001L).getStatus());
        runBeforeCommit(cancel, cancel);
        assertEquals(1, reserved(slotId));
        assertEquals("SUCCESS", orderStatus(1002));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void userAndAdminCancellationSerializeInEitherOrder(boolean userFirst) throws Exception {
        long slotId = stations.publish(create(login()), slotDto());
        addOrder(1001, slotId, "SUCCESS");
        jdbc.update("UPDATE tb_station_slot SET reserved=1 WHERE id=?", slotId);
        Runnable userCancel = () -> reservations.cancel(1001L, 1001L);
        Runnable adminCancel = () -> stations.cancelSlot(slotId);
        runBeforeCommit(userFirst ? userCancel : adminCancel, userFirst ? adminCancel : userCancel);
        assertEquals("CANCELLED", orderStatus(1001));
        assertEquals(0, reserved(slotId));
        assertEquals(0, slotStatus(slotId));
        assertEquals(userFirst ? 0 : 1, mailTaskCount());
        assertEquals(userFirst ? "用户主动取消预约" : "管理员取消该时段预约",
                reservations.get(1001L, 1001L).getFailureReason());
    }

    @ParameterizedTest
    @ValueSource(strings = {"order-update", "release-error", "release-zero"})
    void userCancellationFailureRollsBackOrderAndCapacity(String failure) throws Exception {
        long slotId = stations.publish(create(login()), slotDto());
        addOrder(1001, slotId, "SUCCESS");
        jdbc.update("UPDATE tb_station_slot SET reserved=1 WHERE id=?", slotId);
        switch (failure) {
            case "order-update" -> jdbc.execute("ALTER TABLE tb_reservation ADD CONSTRAINT reject_cancel CHECK(status <> 'CANCELLED')");
            case "release-error" -> jdbc.execute("ALTER TABLE tb_station_slot ADD CONSTRAINT reject_release CHECK(reserved > 0)");
            case "release-zero" -> jdbc.update("UPDATE tb_station_slot SET reserved=0 WHERE id=?", slotId);
            default -> throw new IllegalArgumentException(failure);
        }
        Class<? extends RuntimeException> expected = "release-zero".equals(failure)
                ? IllegalStateException.class : org.springframework.dao.DataAccessException.class;
        assertThrows(expected, () -> reservations.cancel(1001L, 1001L));
        assertEquals("SUCCESS", orderStatus(1001));
        assertNull(reservations.get(1001L, 1001L).getFailureReason());
        assertEquals("release-zero".equals(failure) ? 0 : 1, reserved(slotId));
    }

    private int reserved(long slotId) {
        return jdbc.queryForObject("SELECT reserved FROM tb_station_slot WHERE id=?", Integer.class, slotId);
    }

    private int slotStatus(long slotId) {
        return jdbc.queryForObject("SELECT status FROM tb_station_slot WHERE id=?", Integer.class, slotId);
    }

    private String orderStatus(long id) {
        return jdbc.queryForObject("SELECT status FROM tb_reservation WHERE id=?", String.class, id);
    }

    private RLock localLock(String key) {
        ReentrantLock delegate = new ReentrantLock();
        RLock lock = mock(RLock.class, key);
        try {
            when(lock.tryLock(anyLong(), any(TimeUnit.class))).thenAnswer(call ->
                    delegate.tryLock(call.getArgument(0, Long.class), call.getArgument(1, TimeUnit.class)));
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }
        when(lock.tryLock()).thenAnswer(call -> delegate.tryLock());
        doAnswer(call -> { delegate.lock(); return null; }).when(lock).lock();
        when(lock.isHeldByCurrentThread()).thenAnswer(call -> delegate.isHeldByCurrentThread());
        doAnswer(call -> { delegate.unlock(); return null; }).when(lock).unlock();
        return lock;
    }

    @Test
    void adminLockIsReleasedOnlyAfterCommitOrRollback() throws Exception {
        long stationId = create(login());
        RLock lock = redisson.getLock("gc:lock:admin:station:" + stationId);
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            stations.publish(stationId, slotDto());
            assertTrue(lock.isHeldByCurrentThread());
            verify(lock, never()).unlock();
        });
        assertFalse(lock.isHeldByCurrentThread());
        verify(lock).unlock();

        long slotId = jdbc.queryForObject("SELECT id FROM tb_station_slot", Long.class);
        RLock slotLock = redisson.getLock("gc:lock:admin:slot:" + slotId);
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            stations.closeSlot(slotId);
            assertTrue(lock.isHeldByCurrentThread());
            assertTrue(slotLock.isHeldByCurrentThread());
            tx.setRollbackOnly();
        });
        assertFalse(lock.isHeldByCurrentThread());
        assertFalse(slotLock.isHeldByCurrentThread());
        assertEquals(1, slotStatus(slotId));
    }

    @Test
    void adminLockTimeoutDoesNotPublishOrUnlockAnotherOwner() throws Exception {
        String token = login();
        long stationId = create(token);
        RLock lock = redisson.getLock("gc:lock:admin:station:" + stationId);
        when(lock.tryLock(5, TimeUnit.SECONDS)).thenReturn(false);
        mvc.perform(post("/admin/stations/{id}/slots", stationId).header("authorization", token)
                .contentType(MediaType.APPLICATION_JSON).content(slotJson(slotDto())))
                .andExpect(status().isServiceUnavailable());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM tb_station_slot", Integer.class));
        verify(lock, never()).unlock();
    }

    @Test
    void losingAdminLockBeforeCommitRollsBackPublication() throws Exception {
        long stationId = create(login());
        RLock lock = redisson.getLock("gc:lock:admin:station:" + stationId);
        assertThrows(BusinessException.class, () ->
                new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
                    stations.publish(stationId, slotDto());
                    // 模拟事务提交前失去锁所有权。
                    lock.unlock();
                }));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM tb_station_slot", Integer.class));
        verify(lock).unlock();
    }

    private String login() throws Exception {
        String response = mvc.perform(post("/admin/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"password\":\"test-admin-password\"}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1)).andReturn().getResponse().getContentAsString();
        return json.readTree(response).get("data").asText();
    }

    private long create(String token) throws Exception {
        String response = mvc.perform(post("/admin/stations").header("authorization", token)
                .contentType(MediaType.APPLICATION_JSON).content(STATION)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(response).get("data").asLong();
    }

    @Configuration
    @EnableWebMvc
    @EnableTransactionManagement
    @Import({WebMvcConfiguration.class, GlobalExceptionHandler.class, RefreshTokenInterceptor.class,
            LoginInterceptor.class, AdminInterceptor.class, AdminAuthController.class,
            AdminAuthService.class, StationController.class, ReservationController.class,
            StationServiceImpl.class, ReservationServiceImpl.class, UserController.class, UserServiceImpl.class,
            AdminTransactionLock.class, AdminCancellationMailSender.class, StationBloomFilter.class})
    static class Config {
        @Bean static org.springframework.validation.beanvalidation.MethodValidationPostProcessor methodValidation() {
            return new org.springframework.validation.beanvalidation.MethodValidationPostProcessor();
        }
        @Bean DataSource dataSource() {
            return new DriverManagerDataSource("jdbc:h2:mem:admin_management;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        }
        @Bean JdbcTemplate jdbcTemplate(DataSource source) { return new JdbcTemplate(source); }
        @Bean PlatformTransactionManager transactionManager(DataSource source) {
            return new DataSourceTransactionManager(source);
        }
        @Bean SqlSessionFactory sqlSessionFactory(DataSource source) throws Exception {
            SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
            factory.setDataSource(source);
            factory.setTypeAliasesPackage("com.gridcharge.entity");
            factory.setMapperLocations(new ClassPathResource("mapper/StationMapper.xml"),
                    new ClassPathResource("mapper/SlotMapper.xml"), new ClassPathResource("mapper/UserMapper.xml"),
                    new ClassPathResource("mapper/ReservationMapper.xml"),
                    new ClassPathResource("mapper/AdminCancellationMailMapper.xml"));
            var configuration = new org.apache.ibatis.session.Configuration();
            configuration.setMapUnderscoreToCamelCase(true);
            factory.setConfiguration(configuration);
            return factory.getObject();
        }
        @Bean SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory factory) { return new SqlSessionTemplate(factory); }
        @Bean StationMapper stationMapper(SqlSessionTemplate sql) { return sql.getMapper(StationMapper.class); }
        @Bean SlotMapper slotMapper(SqlSessionTemplate sql) { return sql.getMapper(SlotMapper.class); }
        @Bean UserMapper userMapper(SqlSessionTemplate sql) { return sql.getMapper(UserMapper.class); }
        @Bean ReservationMapper reservationMapper(SqlSessionTemplate sql) { return sql.getMapper(ReservationMapper.class); }
        @Bean AdminCancellationMailMapper cancellationMailMapper(SqlSessionTemplate sql) {
            return sql.getMapper(AdminCancellationMailMapper.class);
        }
    }
}
