package com.nexus360.integration;

import com.nexus360.common.ApiException;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;

/**
 * Server-side request forgery guard for outbound webhooks: only http(s), no credentials in the URL, and the host
 * must not resolve to loopback, private, link-local (cloud metadata), CGNAT, multicast or unique-local addresses.
 * Checked when an integration is saved and again before every delivery (DNS can change).
 */
public final class UrlSafety {

    private UrlSafety() {}

    public static URI validate(String url, boolean allowPrivateTargets) {
        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("Endpoint URL is not valid");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("https") && !(allowPrivateTargets && scheme.equals("http"))) {
            throw ApiException.badRequest("Endpoint URL must use https");
        }
        if (uri.getHost() == null || uri.getRawUserInfo() != null) {
            throw ApiException.badRequest("Endpoint URL must have a host and no embedded credentials");
        }
        if (!allowPrivateTargets) {
            try {
                for (InetAddress address : InetAddress.getAllByName(uri.getHost())) {
                    if (isInternal(address)) {
                        throw ApiException.badRequest("Endpoint host resolves to a private or internal address");
                    }
                }
            } catch (UnknownHostException e) {
                throw ApiException.badRequest("Endpoint host cannot be resolved");
            }
        }
        return uri;
    }

    static boolean isInternal(InetAddress a) {
        if (a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress() || a.isSiteLocalAddress() || a.isMulticastAddress()) {
            return true;
        }
        byte[] b = a.getAddress();
        if (b.length == 4) {
            int first = b[0] & 0xFF;
            int second = b[1] & 0xFF;
            return first == 0 || (first == 100 && second >= 64 && second <= 127) || (first == 198 && (second == 18 || second == 19));
        }
        if (a instanceof Inet6Address) {
            return (b[0] & 0xFE) == 0xFC; // fc00::/7 unique local
        }
        return false;
    }
}
