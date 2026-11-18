package com.socraticmind.infrastructure.store;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import javax.sql.DataSource;

/**
 * 只在 psych.store=postgres 时装配数据源。
 *
 * 刻意不用 Spring Boot 的 DataSourceAutoConfiguration：内存档没有数据库，
 * 自动配置会直接让应用启动失败。连接池先用 DriverManagerDataSource，
 * 上线前换 HikariCP（第一版并发量不需要）。
 */
@Configuration
@ConditionalOnProperty(name = "psych.db.enabled", havingValue = "true")
public class PostgresConfig {

    @Bean
    DataSource dataSource(@Value("${psych.db.url:jdbc:postgresql://localhost:5432/psych}") String url,
                          @Value("${psych.db.username:psych}") String username,
                          @Value("${psych.db.password:psych_local_dev}") String password,
                          @Value("${psych.db.driver:org.postgresql.Driver}") String driver) {
        DriverManagerDataSource ds = new DriverManagerDataSource(url, username, password);
        // 驱动可换：生产连 PostgreSQL，本机无 Docker 时用 H2 文件库验证同一套 DDL
        ds.setDriverClassName(driver);
        return ds;
    }

    @Bean
    JdbcTemplate jdbcTemplate(DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    @Bean
    ApplicationRunner schemaInitializer(DataSource dataSource,
                                       @Value("${psych.store:memory}") String knowledgeStore) {
        return args -> {
            // 会话域与知识域分开初始化：H2 上没有 pgvector，只跑 cbt 三张表
            if ("postgres".equals(knowledgeStore)) {
                ResourceDatabasePopulator kb = new ResourceDatabasePopulator(new ClassPathResource("db/schema-pg.sql"));
                kb.setContinueOnError(true);
                kb.execute(dataSource);
            }
            ResourceDatabasePopulator cbt = new ResourceDatabasePopulator(new ClassPathResource("db/schema-cbt.sql"));
            cbt.setContinueOnError(true);
            cbt.execute(dataSource);
        };
    }
}
