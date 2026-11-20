package com.socraticmind.infrastructure.store;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;

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

    /**
     * 建表要先于种子语料灌入，否则 pg 档首次启动会因为表还不存在而失败。
     * 显式排序，不依赖 bean 的注册顺序。
     */
    @Bean
    @Order(0)
    ApplicationRunner schemaInitializer(DataSource dataSource,
                                       @Value("${psych.store:memory}") String knowledgeStore,
                                       @Value("${psych.ai.embedding-dimension:256}") int embeddingDimension) {
        return args -> {
            // 会话域与知识域分开初始化：H2 上没有 pgvector，只跑 cbt 三张表
            if ("postgres".equals(knowledgeStore)) {
                // 向量列维度必须等于 embedding 模型的实际维度（桩 256、BGE-M3 1024），
                // 所以建表时把 DDL 里的 256 换成配置值。已建好的表不会自动迁移：
                // 换模型要清空 kb_chunk 后重建该列与索引（Docker 档删卷重来即可）。
                String ddl = new String(new ClassPathResource("db/schema-pg.sql").getInputStream().readAllBytes(),
                        StandardCharsets.UTF_8)
                        .replace("vector(256)", "vector(" + embeddingDimension + ")");
                ResourceDatabasePopulator kb = new ResourceDatabasePopulator(
                        new ByteArrayResource(ddl.getBytes(StandardCharsets.UTF_8)));
                kb.setContinueOnError(true);
                kb.execute(dataSource);
            }
            ResourceDatabasePopulator cbt = new ResourceDatabasePopulator(new ClassPathResource("db/schema-cbt.sql"));
            cbt.setContinueOnError(true);
            cbt.execute(dataSource);
        };
    }
}
