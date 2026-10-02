package com.tarun.seat_reserve_service_project.health;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Properties;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.stereotype.Component;

/**
 * Readiness dependency check, exposed as the "database" component of /readyz. Deliberately NOT
 * borrowed from the Hikari pool:
 * <ul>
 *   <li>during an on-sale burst the pool is saturated by design, and readiness must not flap DOWN
 *       (and get us pulled from the load balancer) just because every connection is busy;</li>
 *   <li>when the database is gone, a pool borrow blocks for the full connection-timeout (30s), whereas
 *       this opens a fresh connection with a 2s connect/socket timeout and fails closed quickly.</li>
 * </ul>
 * It also checks the schema is there (shows table), so "reachable but not migrated" is not ready.
 */
@Component("databaseHealthIndicator")
public class DatabaseHealthIndicator implements HealthIndicator {

    private final DataSourceProperties ds;

    public DatabaseHealthIndicator(DataSourceProperties ds) {
        this.ds = ds;
    }

    @Override
    public Health health() {
        Properties props = new Properties();
        if (ds.determineUsername() != null) props.setProperty("user", ds.determineUsername());
        if (ds.determinePassword() != null) props.setProperty("password", ds.determinePassword());
        props.setProperty("connectTimeout", "2000"); // MySQL Connector/J: milliseconds
        props.setProperty("socketTimeout", "2000");
        long start = System.nanoTime();
        try (Connection c = DriverManager.getConnection(ds.determineUrl(), props);
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT 1 FROM shows LIMIT 0")) {
            rs.next();
            return Health.up()
                    .withDetail("database", c.getMetaData().getDatabaseProductName())
                    .withDetail("latency_ms", (System.nanoTime() - start) / 1_000_000)
                    .build();
        } catch (Exception e) {
            return Health.down().withDetail("error", e.getClass().getSimpleName() + ": " + e.getMessage()).build();
        }
    }
}
