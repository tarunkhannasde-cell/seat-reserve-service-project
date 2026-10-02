package com.tarun.seat_reserve_service_project.config;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Hosting platforms hand out {@code DATABASE_URL} / {@code MYSQL_URL=mysql://user:pass@host:port/db}.
 * Translate it into JDBC properties so the same image runs unchanged locally and in the cloud.
 * An explicit DB_URL always wins.
 */
public class DatabaseUrlEnvironmentPostProcessor implements EnvironmentPostProcessor {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication application) {
        if (env.containsProperty("DB_URL")) {
            return;
        }
        String databaseUrl = firstNonBlank(env.getProperty("DATABASE_URL"), env.getProperty("MYSQL_URL"));
        if (databaseUrl == null) {
            return;
        }
        if (databaseUrl.startsWith("jdbc:")) {
            env.getPropertySources().addFirst(new MapPropertySource("databaseUrl",
                    Map.of("spring.datasource.url", databaseUrl)));
            return;
        }
        URI uri = URI.create(databaseUrl);
        if (!"mysql".equals(uri.getScheme())) {
            throw new IllegalStateException("DATABASE_URL must be a mysql:// URL, got scheme " + uri.getScheme());
        }
        int port = uri.getPort() == -1 ? 3306 : uri.getPort();
        String query = uri.getQuery() == null ? "" : "?" + uri.getQuery();
        Map<String, Object> props = new HashMap<>();
        props.put("spring.datasource.url", "jdbc:mysql://" + uri.getHost() + ":" + port + uri.getPath() + query);
        if (uri.getUserInfo() != null) {
            String[] parts = uri.getUserInfo().split(":", 2);
            props.put("spring.datasource.username", parts[0]);
            if (parts.length > 1) {
                props.put("spring.datasource.password", parts[1]);
            }
        }
        env.getPropertySources().addFirst(new MapPropertySource("databaseUrl", props));
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }
}
