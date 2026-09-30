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
        if (rawUrl == null || rawUrl.isBlank()) {
            throw new BusinessException(40001, "媒体地址不能为空");
        }
        String value = rawUrl.trim();
        if (value.startsWith("/api/files/") || value.startsWith("/api/library/media/")) {
            return;
        }
        URI uri = parse(value);
        String scheme = requireHttpScheme(uri);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new BusinessException(40001, "仅支持 HTTP/HTTPS 媒体地址");
        }
        String origin = origin(uri);
        Set<String> normalized = normalizeOrigins(allowedOrigins);
        if (!normalized.contains(origin)) {
            throw new BusinessException(40001, "媒体地址不在允许的服务范围内");
        }
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
