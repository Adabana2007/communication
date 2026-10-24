package hnist.communication.config;

import org.flywaydb.core.api.configuration.Configuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * 在 Flyway 执行迁移前校验数据库端口、库名和实例 UUID，
 * 连接错误实例时不产生任何写入。
 */
@org.springframework.context.annotation.Configuration
public class FlywaySafetyConfig {

    @Bean
    public FlywayMigrationStrategy verifiedFlywayMigrationStrategy(
            @Value("${database.expected-port:3306}") int expectedPort,
            @Value("${database.expected-name:communication}") String expectedDatabase,
            @Value("${database.expected-server-uuid:}") String expectedServerUuid) {
        return flyway -> {
            Configuration config = flyway.getConfiguration();
            try (Connection connection = config.getDataSource().getConnection();
                 Statement statement = connection.createStatement();
                 ResultSet rs = statement.executeQuery(
                         "SELECT @@port, DATABASE(), @@server_uuid, @@hostname, @@datadir")) {
                if (!rs.next()) {
                    throw new IllegalStateException("无法读取数据库实例身份");
                }
                int actualPort = rs.getInt(1);
                String actualDatabase = rs.getString(2);
                String actualUuid = rs.getString(3);
                if (actualPort != expectedPort) {
                    throw new IllegalStateException("数据库端口不符合预期，迁移已阻止：expected="
                            + expectedPort + ", actual=" + actualPort);
                }
                if (expectedDatabase != null && !expectedDatabase.isBlank()
                        && !expectedDatabase.equalsIgnoreCase(actualDatabase)) {
                    throw new IllegalStateException("数据库名称不符合预期，迁移已阻止：expected="
                            + expectedDatabase + ", actual=" + actualDatabase);
                }
                if (expectedServerUuid != null && !expectedServerUuid.isBlank()
                        && !expectedServerUuid.equalsIgnoreCase(actualUuid)) {
                    throw new IllegalStateException("数据库实例 UUID 不符合预期，迁移已阻止：expected="
                            + expectedServerUuid + ", actual=" + actualUuid);
                }
            } catch (java.sql.SQLException e) {
                throw new IllegalStateException("数据库实例身份校验失败，迁移已阻止", e);
            }
            // 实例身份全部校验通过后才允许 Flyway 写入 flyway_schema_history 和迁移脚本。
            flyway.migrate();
        };
    }
}
