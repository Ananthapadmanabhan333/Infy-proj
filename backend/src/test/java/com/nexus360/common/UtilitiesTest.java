package com.nexus360.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexus360.analytics.ForecastModel;
import com.nexus360.config.DatabaseUrlEnvironmentPostProcessor;
import com.nexus360.security.PasswordPolicy;
import com.nexus360.security.ratelimit.InMemoryRateLimiter;
import com.nexus360.security.ratelimit.RateLimiter;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class UtilitiesTest {

    @Test
    void neonConnectionStringIsConvertedToJdbc() throws Exception {
        var method = DatabaseUrlEnvironmentPostProcessor.class.getDeclaredMethod("convert", String.class);
        method.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) method.invoke(null,
                "postgresql://nexus_owner:p%40ss@ep-cool-sun-123456.ap-southeast-1.aws.neon.tech/nexus360?sslmode=require&channel_binding=require");
        assertThat(props.get("spring.datasource.url"))
                .isEqualTo("jdbc:postgresql://ep-cool-sun-123456.ap-southeast-1.aws.neon.tech/nexus360?sslmode=require");
        assertThat(props.get("spring.datasource.username")).isEqualTo("nexus_owner");
        assertThat(props.get("spring.datasource.password")).isEqualTo("p@ss");
    }

    @Test
    void passwordPolicy() {
        PasswordPolicy.validate("Correct-Horse-42", "someone@example.com");
        assertThatThrownBy(() -> PasswordPolicy.validate("short1", null)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> PasswordPolicy.validate("onlyletterslong", null)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> PasswordPolicy.validate("someone12345", "someone@example.com")).isInstanceOf(ApiException.class);
    }

    @Test
    void csvNeutralisesFormulasAndQuotes() {
        assertThat(Csv.escape("=HYPERLINK(\"x\")")).isEqualTo("\"'=HYPERLINK(\"\"x\"\")\"");
        assertThat(Csv.escape("a,b")).isEqualTo("\"a,b\"");
        assertThat(Csv.escape(-5)).isEqualTo("-5");
        assertThat(Csv.escape(null)).isEmpty();
    }

    @Test
    void rateLimiterBlocksAfterLimitAndResetsPerWindow() {
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-03T10:00:00Z"));
        Clock clock = new Clock() {
            @Override public Instant instant() { return now.get(); }
            @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId z) { return this; }
        };
        InMemoryRateLimiter limiter = new InMemoryRateLimiter(clock);
        for (int i = 0; i < 3; i++) {
            assertThat(limiter.tryAcquire("login:1.2.3.4", 3, Duration.ofMinutes(1)).allowed()).isTrue();
        }
        RateLimiter.Decision blocked = limiter.tryAcquire("login:1.2.3.4", 3, Duration.ofMinutes(1));
        assertThat(blocked.allowed()).isFalse();
        assertThat(blocked.retryAfterSeconds()).isBetween(1L, 60L);
        assertThat(limiter.tryAcquire("login:5.6.7.8", 3, Duration.ofMinutes(1)).allowed()).isTrue();
        now.set(now.get().plusSeconds(61));
        assertThat(limiter.tryAcquire("login:1.2.3.4", 3, Duration.ofMinutes(1)).allowed()).isTrue();
    }

    @Test
    void forecastFitsLinearTrend() {
        ForecastModel.Fit fit = ForecastModel.fit(List.of(2L, 4L, 6L, 8L, 10L));
        assertThat(fit.slope()).isEqualTo(2.0);
        assertThat(fit.rSquared()).isEqualTo(1.0);
        assertThat(ForecastModel.project(fit, 5, 2)).extracting(ForecastModel.Point::predicted).containsExactly(12.0, 14.0);
        assertThat(ForecastModel.project(ForecastModel.fit(List.of(5L, 3L, 1L, 0L)), 4, 3))
                .allSatisfy(p -> assertThat(p.predicted()).isGreaterThanOrEqualTo(0));
    }

    @Test
    void jsonMapsNormaliseEnumsAndNumbers() {
        Map<String, Object> normalised = JsonMaps.normalize(Map.of("priority", com.nexus360.incident.Priority.HIGH, "id", 5L));
        assertThat(normalised.get("priority")).isEqualTo("HIGH");
        assertThat(normalised.get("id")).isEqualTo(5);
    }
}
