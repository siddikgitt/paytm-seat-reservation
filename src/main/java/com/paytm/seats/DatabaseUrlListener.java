package com.paytm.seats;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Translates a platform-style {@code DATABASE_URL} (postgres://user:pass@host:port/db)
 * into Spring datasource properties. Explicit {@code SPRING_DATASOURCE_*} settings win.
 */
public class DatabaseUrlListener implements ApplicationListener<ApplicationEnvironmentPreparedEvent> {

    @Override
    public void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {
        ConfigurableEnvironment env = event.getEnvironment();
        String databaseUrl = env.getProperty("DATABASE_URL");
        if (databaseUrl == null || databaseUrl.isBlank() || env.containsProperty("SPRING_DATASOURCE_URL")) {
            return;
        }
        env.getPropertySources().addFirst(new MapPropertySource("databaseUrl",
                toDatasourceProperties(databaseUrl, env.getProperty("DB_SSLMODE", "prefer"))));
    }

    static Map<String, Object> toDatasourceProperties(String databaseUrl, String sslMode) {
        URI uri = URI.create(databaseUrl.replaceFirst("^postgres(ql)?://", "postgresql://"));
        Map<String, Object> props = new HashMap<>();
        int port = uri.getPort() == -1 ? 5432 : uri.getPort();
        String query = uri.getRawQuery() == null ? "sslmode=" + sslMode : uri.getRawQuery();
        props.put("spring.datasource.url", "jdbc:postgresql://" + uri.getHost() + ":" + port + uri.getPath() + "?" + query);
        String userInfo = uri.getRawUserInfo();
        if (userInfo != null) {
            String[] parts = userInfo.split(":", 2);
            props.put("spring.datasource.username", decode(parts[0]));
            if (parts.length > 1) {
                props.put("spring.datasource.password", decode(parts[1]));
            }
        }
        return props;
    }

    private static String decode(String s) {
        return URLDecoder.decode(s, StandardCharsets.UTF_8);
    }
}
