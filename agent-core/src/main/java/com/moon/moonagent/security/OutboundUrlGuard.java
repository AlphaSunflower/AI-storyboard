package com.moon.moonagent.security;

import com.storyboard.common.BusinessException;

import java.net.InetAddress;
import java.net.URI;
import java.util.Collection;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Rejects server-side HTTP requests to loopback, link-local, private, and
 * otherwise non-public addresses. User-controlled media URLs must pass this
 * guard before the server fetches them.
 */
public final class OutboundUrlGuard {

    private OutboundUrlGuard() {
    }

    public static void requireSafeHttpUrl(String rawUrl) {
        URI uri = parse(rawUrl);
        String scheme = requireHttpScheme(uri);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new BusinessException(40001, "仅支持 HTTP/HTTPS 媒体地址");
        }

        String host = normalizeHost(uri.getHost());
        if (host.isBlank()) {
            throw new BusinessException(40001, "媒体地址缺少主机名");
        }
        if ("localhost".equalsIgnoreCase(host) || host.toLowerCase(Locale.ROOT).endsWith(".localhost")) {
            throw new BusinessException(40001, "禁止访问本机或内网媒体地址");
        }

        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (Exception error) {
            throw new BusinessException(40001, "媒体地址无法解析");
        }
        for (InetAddress address : addresses) {
            if (!isPublicAddress(address)) {
                throw new BusinessException(40001, "禁止访问本机或内网媒体地址");
            }
        }
    }

    /**
     * Validates media URLs accepted from an untrusted request. Relative
     * application paths are allowed; absolute URLs must target one of the
     * explicitly configured service origins.
     */
    public static void requireAllowedMediaUrl(String rawUrl, Collection<String> allowedOrigins) {
        canonicalizeMediaUrl(rawUrl, allowedOrigins);
    }

    /**
     * Validates a media URL accepted from an untrusted request and returns its
     * canonical server-facing form.
     *
     * <p>Accepted shapes:
     * <ul>
     *   <li>relative application media paths ({@code /api/files/**},
     *       {@code /api/library/media/**}) — returned unchanged;
     *   <li>absolute URLs on an explicitly configured origin — returned unchanged;
     *   <li>absolute URLs whose <em>path</em> is one of the trusted media paths but
     *       whose origin is a browser-facing host (frontend dev server, gateway,
     *       public site) — returned as a relative path (query preserved, host and
     *       fragment dropped).
     * </ul>
     *
     * <p>Everything else is rejected. Because a foreign host is never part of the
     * returned value, downstream fetches resolve against the internal service
     * bases instead of the caller-supplied origin: this normalizes browser
     * absolute URLs without widening the set of hosts the server may contact.
     * The method performs no DNS or network lookup.
     */
    public static String canonicalizeMediaUrl(String rawUrl, Collection<String> allowedOrigins) {
        if (rawUrl == null || rawUrl.isBlank()) {
            throw new BusinessException(40001, "媒体地址不能为空");
        }
        String value = rawUrl.trim();
        if (isTrustedMediaPath(value)) {
            return value;
        }
        URI uri = parse(value);
        // requireHttpScheme 已经保证 scheme 是 http/https
        requireHttpScheme(uri);
        if (normalizeOrigins(allowedOrigins).contains(origin(uri))) {
            return value;
        }
        String path = uri.getRawPath();
        if (path != null && isTrustedMediaPath(path)) {
            String query = uri.getRawQuery();
            return query == null || query.isEmpty() ? path : path + "?" + query;
        }
        throw new BusinessException(40001, "媒体地址不在允许的服务范围内");
    }

    /** Trusted relative media paths served by the internal services. */
    private static boolean isTrustedMediaPath(String value) {
        return value != null
                && (value.startsWith("/api/files/") || value.startsWith("/api/library/media/"));
    }

    public static String origin(URI uri) {
        int port = uri.getPort();
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String defaultPort = "https".equals(scheme) ? "443" : "80";
        String effectivePort = port > 0 ? String.valueOf(port) : defaultPort;
        return scheme + "://" + normalizeHost(uri.getHost()).toLowerCase(Locale.ROOT) + ":" + effectivePort;
    }

    private static Set<String> normalizeOrigins(Collection<String> allowedOrigins) {
        Set<String> origins = new HashSet<>();
        if (allowedOrigins == null) return origins;
        for (String value : allowedOrigins) {
            if (value == null || value.isBlank()) continue;
            try {
                URI uri = URI.create(value.trim());
                if (uri.getScheme() != null && uri.getHost() != null) {
                    origins.add(origin(uri));
                }
            } catch (Exception ignored) {
                // Ignore malformed optional configuration entries.
            }
        }
        return origins;
    }

    private static String requireHttpScheme(URI uri) {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new BusinessException(40001, "仅支持 HTTP/HTTPS 媒体地址");
        }
        return scheme;
    }

    static boolean isPublicAddress(InetAddress address) {
        if (address == null
                || address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return false;
        }

        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            int first = bytes[0] & 0xff;
            int second = bytes[1] & 0xff;
            if (first == 100 && second >= 64 && second <= 127) return false;
            if (first == 192 && second == 0) return false;
            if (first == 198 && (second == 18 || second == 19)) return false;
            if (first == 198 && second == 51) return false;
            if (first == 203 && second == 0) return false;
            return true;
        }

        if (bytes.length == 16) {
            int first = bytes[0] & 0xff;
            int second = bytes[1] & 0xff;
            if ((first & 0xfe) == 0xfc) return false;
            if (first == 0xfe && (second & 0xc0) == 0x80) return false;
        }
        return true;
    }

    private static URI parse(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            throw new BusinessException(40001, "媒体地址不能为空");
        }
        try {
            return URI.create(rawUrl.trim());
        } catch (Exception error) {
            throw new BusinessException(40001, "媒体地址格式无效");
        }
    }

    private static String normalizeHost(String host) {
        if (host == null) return "";
        String value = host.trim();
        if (value.startsWith("[") && value.endsWith("]")) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }
}
