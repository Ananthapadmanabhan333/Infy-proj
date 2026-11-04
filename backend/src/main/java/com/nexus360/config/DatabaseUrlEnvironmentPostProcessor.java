package com.nexus360.config;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Accepts the connection string format that Neon and Render hand out
 * ({@code postgresql://user:password@host:port/db?sslmode=require}) and converts it to the JDBC URL,
 * username and password Spring expects. JDBC URLs are left untouched.
 */
public class DatabaseUrlEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String raw = environment.getProperty("DATABASE_URL");
        if (raw == null || raw.isBlank() || raw.startsWith("jdbc:")) {
            return;
        }
        if (!raw.startsWith("postgres://") && !raw.startsWith("postgresql://")) {
            return;
        }
        Map<String, Object> converted = convert(raw);
        String explicitUser = environment.getProperty("DATABASE_USER");
        if (explicitUser != null && !explicitUser.isBlank()) {
            converted.remove("spring.datasource.username");
            converted.remove("spring.datasource.password");
        }
        environment.getPropertySources().addFirst(new MapPropertySource("nexusDatabaseUrl", converted));
    }

    static Map<String, Object> convert(String raw) {
        URI uri = URI.create(raw.replaceFirst("^postgres(ql)?://", "http://"));
        Map<String, Object> props = new HashMap<>();
        StringBuilder jdbc = new StringBuilder("jdbc:postgresql://").append(uri.getHost());
        if (uri.getPort() > 0) {
            jdbc.append(':').append(uri.getPort());
        }
        jdbc.append(uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/postgres" : uri.getRawPath());
        String query = uri.getRawQuery();
        if (query != null && !query.isBlank()) {
            // libpq-only parameters that pgjdbc does not understand.
            String filtered = java.util.Arrays.stream(query.split("&"))
                    .filter(p -> !p.startsWith("channel_binding="))
                    .collect(Collectors.joining("&"));
            if (!filtered.isBlank()) {
                jdbc.append('?').append(filtered);
            }
        }
        props.put("spring.datasource.url", jdbc.toString());
        String userInfo = uri.getRawUserInfo();
        if (userInfo != null && !userInfo.isBlank()) {
            String[] parts = userInfo.split(":", 2);
            props.put("spring.datasource.username", URLDecoder.decode(parts[0], StandardCharsets.UTF_8));
            if (parts.length > 1) {
                props.put("spring.datasource.password", URLDecoder.decode(parts[1], StandardCharsets.UTF_8));
            }
        }
        return props;
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
