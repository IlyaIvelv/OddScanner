package com.oddscanner.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;
import java.net.URI;
import java.net.URISyntaxException;

/**
 * Converts Replit's DATABASE_URL format (postgresql://user:pass@host/db?sslmode=...)
 * to a proper JDBC datasource configuration.
 */
@Configuration
public class DataSourceConfig {

    @Bean
    @Primary
    public DataSource dataSource() {
        String rawUrl = System.getenv("DATABASE_URL");
        if (rawUrl == null || rawUrl.isBlank()) {
            throw new IllegalStateException("DATABASE_URL environment variable is not set");
        }

        HikariConfig config = new HikariConfig();

        try {
            // Parse postgres:// or postgresql:// URI
            String normalizedUrl = rawUrl
                    .replace("postgresql://", "http://")
                    .replace("postgres://", "http://");

            URI uri = new URI(normalizedUrl);
            String host = uri.getHost();
            int port = uri.getPort() > 0 ? uri.getPort() : 5432;
            String path = uri.getPath();
            String database = path.startsWith("/") ? path.substring(1) : path;

            // Build clean JDBC URL without sslmode
            String jdbcUrl = String.format("jdbc:postgresql://%s:%d/%s", host, port, database);

            String userInfo = uri.getUserInfo();
            String username = null;
            String password = null;
            if (userInfo != null) {
                String[] parts = userInfo.split(":", 2);
                username = parts[0];
                password = parts.length > 1 ? parts[1] : "";
            }

            // Check if SSL is required
            String query = uri.getQuery();
            boolean sslEnabled = query != null && query.contains("sslmode=require");

            if (sslEnabled) {
                jdbcUrl += "?ssl=true&sslfactory=org.postgresql.ssl.NonValidatingFactory";
            }

            config.setJdbcUrl(jdbcUrl);
            if (username != null) config.setUsername(username);
            if (password != null) config.setPassword(password);
            config.setDriverClassName("org.postgresql.Driver");
            config.setMaximumPoolSize(10);
            config.setConnectionTimeout(30000);
            config.setIdleTimeout(600000);

        } catch (URISyntaxException e) {
            // Fallback: try using the URL directly with jdbc: prefix
            String fallback = rawUrl.startsWith("jdbc:") ? rawUrl : "jdbc:" + rawUrl;
            // Strip sslmode parameter if present
            fallback = fallback.replaceAll("[?&]sslmode=[^&]*", "");
            config.setJdbcUrl(fallback);
            config.setDriverClassName("org.postgresql.Driver");
        }

        return new HikariDataSource(config);
    }
}
