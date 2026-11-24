package com.example.rag.config;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import com.zaxxer.hikari.HikariDataSource;

/**
 * cloudMall MySQL 业务库连接配置。独立于主数据源（PostgreSQL），
 * 仅 Tool 层只读查询使用，不会影响 Spring Boot 的主数据源自动配置。
 */
@Configuration
@EnableConfigurationProperties(CloudMallProperties.class)
public class CloudMallDbConfig {

    private static final Logger log = LoggerFactory.getLogger(CloudMallDbConfig.class);

    @Bean(name = "cloudMallDataSource")
    @ConditionalOnProperty(name = "cloudmall.datasource.url")
    public DataSource cloudMallDataSource(CloudMallProperties props) {
        log.info("初始化 cloudMall MySQL 数据源: {}", props.getUrl());
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(props.getUrl());
        ds.setUsername(props.getUsername());
        ds.setPassword(props.getPassword());
        ds.setDriverClassName(props.getDriverClassName());
        ds.setPoolName("cloudmall-hikari");
        ds.setMinimumIdle(2);
        ds.setMaximumPoolSize(5);
        return ds;
    }

    @Bean(name = "cloudMallJdbcTemplate")
    @ConditionalOnProperty(name = "cloudmall.datasource.url")
    public JdbcTemplate cloudMallJdbcTemplate(DataSource cloudMallDataSource) {
        return new JdbcTemplate(cloudMallDataSource);
    }
}
