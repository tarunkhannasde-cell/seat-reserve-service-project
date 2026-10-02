package com.tarun.seat_reserve_service_project.health;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import javax.sql.DataSource;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("databaseHealthIndicator")
public class DatabaseHealthIndicator implements HealthIndicator {

    private final DataSource dataSource;

    public DatabaseHealthIndicator(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public Health health() {
        long start = System.nanoTime();

        try (
                Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet resultSet =
                        statement.executeQuery("SELECT 1 FROM shows LIMIT 1")
        ) {

            return Health.up()
                    .withDetail(
                            "database",
                            connection.getMetaData().getDatabaseProductName()
                    )
                    .withDetail(
                            "latency_ms",
                            (System.nanoTime() - start) / 1_000_000
                    )
                    .build();

        } catch (Exception e) {

            return Health.down()
                    .withDetail(
                            "error",
                            e.getClass().getSimpleName()
                                    + ": "
                                    + e.getMessage()
                    )
                    .build();
        }
    }
}