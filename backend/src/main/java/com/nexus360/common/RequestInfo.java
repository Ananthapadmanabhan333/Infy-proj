package com.nexus360.common;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** Client metadata for audit records. Uses the forwarded address resolved by {@code server.forward-headers-strategy}. */
public final class RequestInfo {

    private RequestInfo() {}

    public static String clientIp() {
        HttpServletRequest request = current();
        return request == null ? null : request.getRemoteAddr();
    }

    public static String userAgent() {
        HttpServletRequest request = current();
        if (request == null) {
            return null;
        }
        String ua = request.getHeader("User-Agent");
        return ua == null ? null : ua.substring(0, Math.min(ua.length(), 255));
    }

    private static HttpServletRequest current() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            return attrs.getRequest();
        }
        return null;
    }
}
