package com.nexus360.security.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus360.config.AppProperties;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** The public account-access POSTs are limited by the per-IP sign-in bucket; their GET link checks are not. */
class RateLimitFilterTest {

    private static final String IP = "203.0.113.7";

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    private RateLimitFilter filter(int loginPerMinute) {
        return new RateLimitFilter(new InMemoryRateLimiter(Clock.systemUTC()),
                new AppProperties.RateLimit(true, loginPerMinute, 1000, 100, 0), mapper);
    }

    private static MockHttpServletResponse call(RateLimitFilter filter, String method, String path, String ip) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setRemoteAddr(ip);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }

    @Test
    void everyPublicAccountPostIsLimitedOnItsOwn() throws Exception {
        for (String path : RateLimitFilter.PUBLIC_ACCOUNT_POSTS) {
            RateLimitFilter filter = filter(2);
            assertThat(call(filter, "POST", path, IP).getStatus()).as(path).isEqualTo(200);
            assertThat(call(filter, "POST", path, IP).getStatus()).as(path).isEqualTo(200);
            MockHttpServletResponse blocked = call(filter, "POST", path, IP);
            assertThat(blocked.getStatus()).as(path).isEqualTo(429);
            assertThat(blocked.getHeader("Retry-After")).as(path).isNotBlank();
            assertThat(mapper.readTree(blocked.getContentAsString()).get("status").asInt()).isEqualTo(429);
        }
    }

    @Test
    void publicAccountPostsShareTheSignInBucket() throws Exception {
        List<String> paths = new ArrayList<>(RateLimitFilter.PUBLIC_ACCOUNT_POSTS);
        RateLimitFilter filter = filter(paths.size());
        for (String path : paths) {
            assertThat(call(filter, "POST", path, IP).getStatus()).as(path).isEqualTo(200);
        }
        // The address has spent its sign-in budget across the four endpoints: all of them, and sign-in itself, are refused.
        for (String path : paths) {
            assertThat(call(filter, "POST", path, IP).getStatus()).as(path).isEqualTo(429);
        }
        assertThat(call(filter, "POST", "/api/auth/login", IP).getStatus()).isEqualTo(429);

        // Link checks use the general API bucket, and other addresses are unaffected.
        assertThat(call(filter, "GET", "/api/auth/invitations/abc", IP).getStatus()).isEqualTo(200);
        assertThat(call(filter, "GET", "/api/auth/reset-password/abc", IP).getStatus()).isEqualTo(200);
        assertThat(call(filter, "GET", "/api/public/info", IP).getStatus()).isEqualTo(200);
        assertThat(call(filter, "POST", "/api/auth/register", "198.51.100.1").getStatus()).isEqualTo(200);
    }

    @Test
    void publicStatusPagePostsUseTheSignInBucket() throws Exception {
        assertThat(RateLimitFilter.PUBLIC_STATUS_POSTS).containsExactlyInAnyOrder("/api/public/status/subscribe",
                "/api/public/status/confirm", "/api/public/status/unsubscribe");
        RateLimitFilter filter = filter(3);
        for (String path : RateLimitFilter.PUBLIC_STATUS_POSTS) {
            assertThat(call(filter, "POST", path, IP).getStatus()).as(path).isEqualTo(200);
        }
        for (String path : RateLimitFilter.PUBLIC_STATUS_POSTS) {
            assertThat(call(filter, "POST", path, IP).getStatus()).as(path).isEqualTo(429);
        }
        assertThat(call(filter, "POST", "/api/auth/login", IP).getStatus()).isEqualTo(429);
        // Reading the public page uses the general API bucket.
        assertThat(call(filter, "GET", "/api/public/status", IP).getStatus()).isEqualTo(200);
    }

    @Test
    void signInSpendsTheSameBudget() throws Exception {
        RateLimitFilter filter = filter(1);
        assertThat(call(filter, "POST", "/api/auth/login", IP).getStatus()).isEqualTo(200);
        assertThat(call(filter, "POST", "/api/auth/forgot-password", IP).getStatus()).isEqualTo(429);
    }
}
