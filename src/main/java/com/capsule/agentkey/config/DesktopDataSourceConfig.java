package com.capsule.agentkey.config;

import com.capsule.agentkey.service.desktop.DbLifecycleManager;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import javax.sql.DataSource;

/**
 * 桌面端数据源：连接由 DbLifecycleManager 启动的内嵌 PostgreSQL。
 *
 * 不用 spring.datasource.url，是因为端口在启动时才确定（默认自动挑空闲端口）。
 * 声明 DataSource bean 后，Boot 的 DataSourceAutoConfiguration 会自动让位，
 * JPA / Flyway 直接复用这里的连接池。
 */
@Configuration
@Profile("desktop")
public class DesktopDataSourceConfig {

    @Bean
    public DataSource dataSource(DbLifecycleManager dbLifecycleManager) {
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setPoolName("desktop-db");
        dataSource.setDriverClassName("org.postgresql.Driver");
        dataSource.setJdbcUrl(dbLifecycleManager.jdbcUrl());
        dataSource.setUsername(DbLifecycleManager.USER);
        dataSource.setPassword(DbLifecycleManager.USER);
        // 单机单用户，连接数远小于云端；PG 侧 max_connections 也只开了 20
        dataSource.setMaximumPoolSize(10);
        dataSource.setMinimumIdle(2);
        return dataSource;
    }
}
