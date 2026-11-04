package com.nexus360.alert;

import com.nexus360.config.AppProperties;
import com.nexus360.integration.UrlSafety;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Outbound calls to AWS SNS: fetching signing certificates (cached for 24 hours) and confirming subscriptions. Every URL must be
 * {@code https://sns.<region>.amazonaws.com/...} for the region of the message's topic ARN (no other host, port or user info), and
 * the actual request additionally passes {@link UrlSafety} (no private or internal addresses). Responses are capped at 64 KB and
 * redirects are not followed.
 */
@Slf4j
@Component
public class SnsGateway {

    private static final Pattern TOPIC_ARN = Pattern.compile("^arn:aws(-[a-z]+)?:sns:([a-z0-9-]{4,30}):(\\d{12}):([A-Za-z0-9_.-]{1,256})$");
    private static final Pattern SUBSCRIPTION_ARN = Pattern.compile("<SubscriptionArn>([^<]{1,300})</SubscriptionArn>");
    private static final int MAX_BYTES = 64 * 1024;
    private static final Duration CACHE = Duration.ofHours(24);

    private record CachedKey(PublicKey key, Instant fetchedAt, Instant notAfter) {}

    private final AppProperties properties;
    private final Clock clock;
    private final Map<String, CachedKey> keys = new ConcurrentHashMap<>();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
    private volatile Function<URI, byte[]> fetcher;

    public SnsGateway(AppProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
        this.fetcher = this::httpGet;
    }

    /** Region of an SNS topic ARN ({@code arn:aws:sns:us-east-1:123456789012:topic} → us-east-1), or {@code null} when invalid. */
    public static String topicRegion(String topicArn) {
        if (topicArn == null) {
            return null;
        }
        Matcher m = TOPIC_ARN.matcher(topicArn.trim());
        return m.matches() ? m.group(2) : null;
    }

    public static boolean validTopicArn(String topicArn) {
        return topicRegion(topicArn) != null;
    }

    /** Whether {@code url} is an HTTPS URL on {@code sns.<region>.amazonaws.com} (default port, no user info). */
    public static boolean allowedUrl(String url, String region, boolean certificate) {
        if (url == null || region == null) {
            return false;
        }
        try {
            URI uri = URI.create(url.trim());
            String path = uri.getPath() == null ? "" : uri.getPath();
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getRawUserInfo() == null && (uri.getPort() == -1 || uri.getPort() == 443)
                    && ("sns." + region + ".amazonaws.com").equals(uri.getHost() == null ? null : uri.getHost().toLowerCase(Locale.ROOT))
                    && (!certificate || (path.endsWith(".pem") && uri.getRawQuery() == null));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** The public key of the signing certificate at {@code url} (validated by the caller with {@link #allowedUrl}). */
    public PublicKey certificateKey(String url) {
        Instant now = clock.instant();
        CachedKey cached = keys.get(url);
        if (cached != null && cached.fetchedAt().plus(CACHE).isAfter(now) && cached.notAfter().isAfter(now)) {
            return cached.key();
        }
        try (InputStream in = new ByteArrayInputStream(fetcher.apply(URI.create(url)))) {
            X509Certificate cert = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(in);
            cert.checkValidity(Date.from(now));
            keys.put(url, new CachedKey(cert.getPublicKey(), now, cert.getNotAfter().toInstant()));
            return cert.getPublicKey();
        } catch (Exception e) {
            log.warn("Could not load the SNS signing certificate {}: {}", url, e.getMessage());
            return null;
        }
    }

    /** Confirms a subscription by visiting its SubscribeURL; returns the subscription ARN, or {@code null} when it failed. */
    public String confirm(String subscribeUrl) {
        try {
            String body = new String(fetcher.apply(URI.create(subscribeUrl)), StandardCharsets.UTF_8);
            Matcher m = SUBSCRIPTION_ARN.matcher(body);
            return m.find() ? m.group(1).trim() : "confirmed";
        } catch (RuntimeException e) {
            log.warn("SNS subscription confirmation failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Replaces the HTTP fetcher (tests use canned certificates and confirmation responses); {@code null} restores HTTPS. Clears the
     * certificate cache.
     */
    public void useFetcher(Function<URI, byte[]> replacement) {
        this.fetcher = replacement == null ? this::httpGet : replacement;
        keys.clear();
    }

    private byte[] httpGet(URI uri) {
        UrlSafety.validate(uri.toString(), properties.integrations().allowPrivateTargets());
        try {
            HttpResponse<InputStream> response = http.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10)).GET().build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream in = response.body()) {
                if (response.statusCode() != 200) {
                    throw new IllegalStateException("HTTP " + response.statusCode());
                }
                byte[] data = in.readNBytes(MAX_BYTES + 1);
                if (data.length > MAX_BYTES) {
                    throw new IllegalStateException("Response too large");
                }
                return data;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted");
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e.getMessage());
        }
    }
}
