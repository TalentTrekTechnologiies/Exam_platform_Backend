package com.Exam.Exam_System.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * Two pools to the same database, for one reason: the health check must never
 * be able to starve behind application traffic.
 *
 * Found live: 50 concurrent candidates validating against the application's
 * 5-connection pool queued up to the configured 30-second connection-timeout
 * — a deliberately generous wait so a real candidate's request degrades
 * gracefully rather than failing outright. But /health drew from that SAME
 * pool. With every connection checked out, the health probe queued behind
 * everyone else, Render's own liveness check timed out waiting on it, and the
 * whole container was restarted — not because anything crashed, but because
 * the one endpoint whose entire job is reporting "am I OK" was itself stuck
 * behind the load it was supposed to be reporting on.
 *
 * The fix is a second, tiny, independent pool that only /health ever touches:
 * it can never be exhausted by candidate traffic because candidate traffic
 * never draws from it, and it fails in ~2 seconds instead of queueing for 30 —
 * an honest, fast answer instead of a slow, misleading one.
 */
@Configuration
public class DataSourceConfig {

    @Value("${spring.datasource.url}") private String url;
    @Value("${spring.datasource.username}") private String username;
    @Value("${spring.datasource.password}") private String password;
    @Value("${spring.datasource.driver-class-name}") private String driverClassName;

    /**
     * The application's real pool — identical to what Spring Boot's own
     * auto-configuration would have built from spring.datasource.hikari.*.
     * Declaring a DataSource bean by hand switches that auto-configuration off
     * entirely, so this reproduces it explicitly rather than leaving a gap.
     *
     * Built via DataSourceBuilder rather than `new HikariDataSource(hikariConfig)`
     * — that constructor starts the pool immediately, and @ConfigurationProperties
     * binds AFTER the bean method returns, which then fails with "the
     * configuration of the pool is sealed once started". The builder returns an
     * unstarted instance, so the property binding lands before first use, the
     * same order Spring Boot's own auto-configuration uses internally.
     */
    @Primary
    @Bean
    @ConfigurationProperties(prefix = "spring.datasource.hikari")
    public DataSource dataSource() {
        return DataSourceBuilder.create()
                .url(url)
                .username(username)
                .password(password)
                .driverClassName(driverClassName)
                .type(HikariDataSource.class)
                .build();
    }

    /** Deliberately tiny and fast-failing — see class comment. */
    @Bean
    public DataSource healthDataSource() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername(username);
        config.setPassword(password);
        config.setDriverClassName(driverClassName);
        config.setPoolName("HealthHikari");
        config.setMaximumPoolSize(2);
        config.setMinimumIdle(1);
        config.setConnectionTimeout(2000);
        return new HikariDataSource(config);
    }

    /**
     * Declared explicitly and marked @Primary alongside healthJdbcTemplate
     * below, deliberately — with two DataSource beans in play, leaving this to
     * Spring Boot's default JdbcTemplate auto-configuration risks it wiring
     * ambiguously, or every existing plain `JdbcTemplate` injection across the
     * app (RankingService, ReportController, MonitorController, and others)
     * silently resolving to the tiny 2-connection health pool instead of the
     * real one. Explicit beats implicit here — getting this wrong would starve
     * the whole application, not just fix the health check.
     */
    @Primary
    @Bean
    public JdbcTemplate jdbcTemplate() {
        return new JdbcTemplate(dataSource());
    }

    @Bean
    public JdbcTemplate healthJdbcTemplate() {
        return new JdbcTemplate(healthDataSource());
    }
}
