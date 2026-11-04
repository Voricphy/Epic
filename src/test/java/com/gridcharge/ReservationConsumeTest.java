package com.gridcharge;

import static org.junit.jupiter.api.Assertions.*;

import com.gridcharge.mapper.ReservationMapper;
import com.gridcharge.mapper.SlotMapper;
import com.gridcharge.service.ReservationService;
import com.gridcharge.service.impl.ReservationServiceImpl;
import com.gridcharge.utils.SnowflakeIdWorker;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/** 使用真实 Mapper XML 和 Spring 事务代理，独立于本地 MySQL、Redis 和 RocketMQ。 */
@SpringJUnitConfig(ReservationConsumeTest.Config.class)
class ReservationConsumeTest {
    @Autowired ReservationService service;
    @Autowired JdbcTemplate jdbc;
    // 消费已有订单无需生成 ID，此依赖不连接 Redis。
    @MockBean SnowflakeIdWorker snowflakeIdWorker;

    @BeforeEach
    void prepareDatabase() {
        resetDatabase(jdbc);
    }

    static void resetDatabase(JdbcTemplate jdbc) {
        jdbc.execute("DROP TABLE IF EXISTS tb_reservation");
        jdbc.execute("DROP TABLE IF EXISTS tb_station_slot");
        jdbc.execute("""
                CREATE TABLE tb_station_slot (
                    id BIGINT PRIMARY KEY, quota INT NOT NULL, reserved INT NOT NULL,
                    status INT NOT NULL, start_time TIMESTAMP NOT NULL
                )
                """);
        jdbc.execute("""
                CREATE TABLE tb_reservation (
                    id BIGINT PRIMARY KEY, user_id BIGINT NOT NULL, slot_id BIGINT NOT NULL,
                    request_id VARCHAR(64), status VARCHAR(16) NOT NULL,
                    failure_reason VARCHAR(255), UNIQUE (user_id, request_id),
                    FOREIGN KEY (slot_id) REFERENCES tb_station_slot(id)
                )
                """);
        jdbc.update("""
                INSERT INTO tb_station_slot VALUES
                    (10, 1, 0, 1, DATEADD('DAY', 1, CURRENT_TIMESTAMP))
                """);
        jdbc.update("INSERT INTO tb_reservation VALUES (1001, 1, 10, 'request-1001', 'PROCESSING', NULL)");
    }

    @Test
    void successfulReservationAndRedeliveryOnlyReserveOnce() {
        service.consume(1001L);
        service.consume(1001L);

        assertEquals("SUCCESS", status(1001L));
        assertEquals(1, reserved());
        assertNull(jdbc.queryForObject(
                "SELECT failure_reason FROM tb_reservation WHERE id=1001", String.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"SUCCESS", "FAILED", "CANCELLED"})
    void terminalOrdersAreNotProcessedAgain(String status) {
        jdbc.update("UPDATE tb_reservation SET status=? WHERE id=1001", status);

        service.consume(1001L);

        assertEquals(status, status(1001L));
        assertEquals(0, reserved());
    }

    @ParameterizedTest
    @ValueSource(strings = {"full", "closed", "started"})
    void unavailableSlotCommitsFailureWithoutConsumingCapacity(String situation) {
        switch (situation) {
            case "full" -> jdbc.update("UPDATE tb_station_slot SET reserved=quota WHERE id=10");
            case "closed" -> jdbc.update("UPDATE tb_station_slot SET status=0 WHERE id=10");
            case "started" -> jdbc.update("""
                    UPDATE tb_station_slot SET start_time=DATEADD('DAY', -1, CURRENT_TIMESTAMP)
                    WHERE id=10
                    """);
            default -> throw new IllegalArgumentException(situation);
        }
        int before = reserved();

        service.consume(1001L);
        service.consume(1001L);

        assertEquals("FAILED", status(1001L));
        assertEquals(before, reserved());
        assertEquals("名额不足或时段已不可预约", jdbc.queryForObject(
                "SELECT failure_reason FROM tb_reservation WHERE id=1001", String.class));
    }

    @Test
    void concurrentDeliveriesOfSameOrderOnlyReserveOnce() throws Exception {
        runTogether(1001L, 1001L);

        assertEquals("SUCCESS", status(1001L));
        assertEquals(1, reserved());
    }

    @Test
    void twoOrdersCompetingForLastPlaceProduceOneSuccessAndOneFailure() throws Exception {
        jdbc.update("INSERT INTO tb_reservation VALUES (1002, 2, 10, 'request-1002', 'PROCESSING', NULL)");

        runTogether(1001L, 1002L);

        assertEquals(1, reserved());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM tb_reservation WHERE status='SUCCESS'", Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM tb_reservation WHERE status='FAILED'", Integer.class));
    }

    @Test
    void databaseFailureRollsBackCapacityAndAllowsRetry() {
        // 在扣减名额后的订单更新处制造真实数据库错误，验证整个事务回滚。
        jdbc.execute("ALTER TABLE tb_reservation ADD CONSTRAINT reject_success CHECK (status <> 'SUCCESS')");

        assertThrows(DataAccessException.class, () -> service.consume(1001L));
        assertEquals(0, reserved());
        assertEquals("PROCESSING", status(1001L));

        jdbc.execute("ALTER TABLE tb_reservation DROP CONSTRAINT reject_success");
        service.consume(1001L);
        assertEquals(1, reserved());
        assertEquals("SUCCESS", status(1001L));
    }

    @Test
    void missingOrderIsNotAcknowledgedAsSuccessfulConsumption() {
        assertThrows(IllegalStateException.class, () -> service.consume(9999L));
        assertEquals(0, reserved());
    }

    private String status(Long id) {
        return jdbc.queryForObject("SELECT status FROM tb_reservation WHERE id=?", String.class, id);
    }

    private int reserved() {
        return jdbc.queryForObject("SELECT reserved FROM tb_station_slot WHERE id=10", Integer.class);
    }

    private void runTogether(Long first, Long second) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Callable<Void> firstTask = () -> {
                ready.countDown();
                assertTrue(start.await(5, TimeUnit.SECONDS));
                service.consume(first);
                return null;
            };
            Callable<Void> secondTask = () -> {
                ready.countDown();
                assertTrue(start.await(5, TimeUnit.SECONDS));
                service.consume(second);
                return null;
            };
            Future<Void> a = executor.submit(firstTask);
            Future<Void> b = executor.submit(secondTask);
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            a.get(15, TimeUnit.SECONDS);
            b.get(15, TimeUnit.SECONDS);
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @TestConfiguration
    @EnableTransactionManagement
    static class Config {
        @Bean
        DataSource dataSource() {
            return new DriverManagerDataSource(
                    "jdbc:h2:mem:reservation_consume;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000",
                    "sa", "");
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource dataSource) {
            return new JdbcTemplate(dataSource);
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        SqlSessionFactory sqlSessionFactory(DataSource dataSource) throws Exception {
            SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
            factory.setDataSource(dataSource);
            factory.setTypeAliasesPackage("com.gridcharge.entity");
            factory.setMapperLocations(
                    new ClassPathResource("mapper/ReservationMapper.xml"),
                    new ClassPathResource("mapper/SlotMapper.xml"));
            org.apache.ibatis.session.Configuration configuration =
                    new org.apache.ibatis.session.Configuration();
            configuration.setMapUnderscoreToCamelCase(true);
            factory.setConfiguration(configuration);
            return factory.getObject();
        }

        @Bean
        SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory factory) {
            return new SqlSessionTemplate(factory);
        }

        @Bean
        ReservationMapper reservationMapper(SqlSessionTemplate template) {
            return template.getMapper(ReservationMapper.class);
        }

        @Bean
        SlotMapper slotMapper(SqlSessionTemplate template) {
            return template.getMapper(SlotMapper.class);
        }

        @Bean
        ReservationService reservationService() {
            return new ReservationServiceImpl();
        }
    }
}
