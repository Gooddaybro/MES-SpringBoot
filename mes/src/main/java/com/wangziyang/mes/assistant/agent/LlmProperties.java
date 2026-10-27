package com.wangziyang.mes.assistant.agent;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.regex.Pattern;

@Component
public final class LlmProperties {
    private static final Pattern BEARER_TOKEN = Pattern.compile("[A-Za-z0-9._~+/-]+=*");
    private final String baseUrl;
    private final String endpoint;
    private final String model;
    private final String apiKey;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;
    private final boolean configured;
    private final boolean valid;

    public LlmProperties(Environment environment) {
        String root = property(environment, "MES_LLM_BASE_URL");
        String configuredModel = property(environment, "MES_LLM_MODEL");
        apiKey = property(environment, "MES_LLM_API_KEY");
        baseUrl = stripTrailingSlashes(root == null ? null : root.trim());
        model = configuredModel == null ? null : configuredModel.trim();
        configured = hasText(baseUrl) && hasText(model) && hasText(apiKey);
        connectTimeoutMs = timeout(property(environment, "MES_LLM_CONNECT_TIMEOUT_MS"), 3000);
        readTimeoutMs = timeout(property(environment, "MES_LLM_READ_TIMEOUT_MS"), 15000);
        endpoint = endpoint(baseUrl);
        valid = configured && endpoint != null && model.length() <= 256 && !hasControl(model)
                && BEARER_TOKEN.matcher(apiKey).matches() && connectTimeoutMs > 0 && readTimeoutMs > 0;
    }

    public boolean isConfigured() {
        return configured;
    }

    public boolean isValid() {
        return valid;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public String getModel() {
        return model;
    }

    public String getApiKey() {
        return apiKey;
    }

    public int getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    public int getReadTimeoutMs() {
        return readTimeoutMs;
    }

    private static String property(Environment environment, String name) {
        try {
            return environment.getProperty(name);
        } catch (RuntimeException invalidProperty) {
            return null;
        }
    }

    private static String endpoint(String baseUrl) {
        if (!hasText(baseUrl)) {
            return null;
        }
        try {
            URI uri = new URI(baseUrl);
            if (uri.isOpaque() || uri.getHost() == null || uri.getRawUserInfo() != null
                    || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || uri.getPort() == 0 || uri.getPort() > 65535) {
                return null;
            }
            String scheme = uri.getScheme();
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            boolean loopback = "localhost".equals(host) || "127.0.0.1".equals(host)
                    || "[::1]".equals(host) || "::1".equals(host);
            if (!"https".equalsIgnoreCase(scheme) && !("http".equalsIgnoreCase(scheme) && loopback)) {
                return null;
            }
            return baseUrl.endsWith("/chat/completions") ? baseUrl : baseUrl + "/chat/completions";
        } catch (URISyntaxException invalidUri) {
            return null;
        }
    }

    private static int timeout(String value, int fallback) {
        if (!hasText(value)) {
            return fallback;
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            return parsed >= 1 && parsed <= 60000 ? parsed : -1;
        } catch (NumberFormatException invalidTimeout) {
            return -1;
        }
    }

    private static String stripTrailingSlashes(String value) {
        if (value == null) {
            return null;
        }
        int end = value.length();
        while (end > 0 && value.charAt(end - 1) == '/') {
            end--;
        }
        return value.substring(0, end);
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private static boolean hasControl(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (Character.isISOControl(value.charAt(i))) {
                return true;
            }
        }
        return false;
    }
}
