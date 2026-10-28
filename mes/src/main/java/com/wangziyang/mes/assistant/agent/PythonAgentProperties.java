package com.wangziyang.mes.assistant.agent;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

@Component
public final class PythonAgentProperties {
    private final boolean enabled;
    private final boolean valid;
    private final String endpoint;
    private final String sharedSecret;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;

    public PythonAgentProperties(Environment environment) {
        String flag = property(environment, "MES_AGENT_ENABLED", "false");
        enabled = "true".equalsIgnoreCase(flag);
        endpoint = endpoint(property(environment, "MES_AGENT_BASE_URL", "http://127.0.0.1:8001"));
        sharedSecret = property(environment, "MES_AGENT_SHARED_SECRET", "");
        connectTimeoutMs = timeout(property(environment, "MES_AGENT_CONNECT_TIMEOUT_MS", "3000"));
        readTimeoutMs = timeout(property(environment, "MES_AGENT_READ_TIMEOUT_MS", "35000"));
        valid = ("true".equalsIgnoreCase(flag) || "false".equalsIgnoreCase(flag))
                && endpoint != null && sharedSecret.length() >= 32 && sharedSecret.length() <= 1024
                && !sharedSecret.trim().isEmpty() && !hasControl(sharedSecret)
                && connectTimeoutMs > 0 && readTimeoutMs > 0;
    }

    public boolean isEnabled() { return enabled; }
    public boolean isValid() { return valid; }
    public String getEndpoint() { return endpoint; }
    public String getSharedSecret() { return sharedSecret; }
    public int getConnectTimeoutMs() { return connectTimeoutMs; }
    public int getReadTimeoutMs() { return readTimeoutMs; }

    private static String property(Environment environment, String key, String fallback) {
        try { return environment.getProperty(key, fallback); }
        catch (RuntimeException invalid) { return fallback; }
    }

    private static int timeout(String value) {
        try { int number = Integer.parseInt(value); return number > 0 && number <= 60000 ? number : -1; }
        catch (NumberFormatException invalid) { return -1; }
    }

    private static String endpoint(String value) {
        try {
            URI uri = new URI(value.trim());
            if (uri.isOpaque() || uri.getHost() == null || uri.getRawUserInfo() != null
                    || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || uri.getPort() == 0 || uri.getPort() > 65535
                    || !(uri.getRawPath().isEmpty() || "/".equals(uri.getRawPath()))
                    || !("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || !trustedHost(uri.getHost().toLowerCase(Locale.ROOT))) return null;
            return new URI(uri.getScheme(), null, uri.getHost(), uri.getPort(), "/api/agent/chat", null, null).toASCIIString();
        } catch (URISyntaxException | IllegalArgumentException invalid) { return null; }
    }

    private static boolean trustedHost(String host) {
        return "localhost".equals(host) || "127.0.0.1".equals(host) || "::1".equals(host) || "[::1]".equals(host);
    }

    private static boolean hasControl(String text) {
        for (int i = 0; i < text.length(); i++) if (Character.isISOControl(text.charAt(i))) return true;
        return false;
    }
}
