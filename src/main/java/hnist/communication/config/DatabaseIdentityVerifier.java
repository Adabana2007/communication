package hnist.communication.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 启动时记录并校验 MySQL 实例身份，避免 Windows 命名管道/双实例导致连接到空库。
 */
@Slf4j
@Component
@Order(-200)
@RequiredArgsConstructor
public class DatabaseIdentityVerifier implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;

    @Value("${database.expected-port:3306}")
    private int expectedPort;

    @Value("${database.expected-server-uuid:}")
    private String expectedServerUuid;

    @Override
    public void run(ApplicationArguments args) {
        Integer port = jdbcTemplate.queryForObject("SELECT @@port", Integer.class);
        String uuid = jdbcTemplate.queryForObject("SELECT @@server_uuid", String.class);
        String host = jdbcTemplate.queryForObject("SELECT @@hostname", String.class);
        String dataDir = jdbcTemplate.queryForObject("SELECT @@datadir", String.class);
        String database = jdbcTemplate.queryForObject("SELECT DATABASE()", String.class);

        log.info("[DB-IDENTITY] host={}, port={}, database={}, serverUuid={}, dataDir={}",
                host, port, database, uuid, dataDir);

        // 端口或实例 UUID 与部署配置不符说明连到了另一个 MySQL：直接抛异常中止启动，避免后续写入错误的库。
        if (port == null || port != expectedPort) {
            throw new IllegalStateException("数据库端口不符合预期：expected=" + expectedPort + ", actual=" + port);
        }
        if (expectedServerUuid != null && !expectedServerUuid.isBlank()
                && !expectedServerUuid.equalsIgnoreCase(uuid)) {
            throw new IllegalStateException("数据库实例 UUID 不符合预期，已阻止启动：expected="
                    + expectedServerUuid + ", actual=" + uuid);
        }
    }
}
