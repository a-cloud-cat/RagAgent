package com.example.rag.config;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import com.zaxxer.hikari.HikariDataSource;

/**
 * 主数据源（PostgreSQL）显式装配。
 *
 * cloudMall 的 MySQL 连接以 DataSource / JdbcTemplate Bean 的形式存在于容器中，
 * 会让 Spring Boot 的 DataSourceAutoConfiguration 与 JdbcTemplateAutoConfiguration
 * 整体让位（二者都带 @ConditionalOnMissingBean / @ConditionalOnSingleCandidate），
 * 结果所有未加 @Qualifier 的仓储实现都会注入到 MySQL 连接上。
 * 因此这里显式声明主数据源与主 JdbcTemplate，并用 @Primary 指定默认注入目标；
 * Tool 层仍通过 @Qualifier("cloudMallJdbcTemplate") 指向 MySQL。
 */
@Configuration
public class PrimaryDataSourceConfig {

    @Bean
    @Primary
    public DataSource dataSource(@Value("${spring.datasource.url}") String url,
                                 @Value("${spring.datasource.username}") String username,
                                 @Value("${spring.datasource.password}") String password,
                                 @Value("${spring.datasource.driver-class-name:org.postgresql.Driver}") String driverClassName) {
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(url);
        ds.setUsername(username);
        ds.setPassword(password);
        ds.setDriverClassName(driverClassName);
        ds.setPoolName("primary-hikari");
        ds.setMinimumIdle(2);
        ds.setMaximumPoolSize(10);
        return ds;
    }

    @Bean
    @Primary
    public JdbcTemplate jdbcTemplate(DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }
}
