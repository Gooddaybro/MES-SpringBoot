package com.wangziyang.mes.assistant.agent;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wangziyang.mes.assistant.AssistantResponses;
import com.wangziyang.mes.assistant.service.MesAssistantService;
import com.wangziyang.mes.system.entity.SysUser;
import org.apache.shiro.session.Session;
import org.apache.shiro.subject.Subject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.ResponseErrorHandler;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.net.CookieHandler;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.*;

@Service
public class PythonAgentService {
    public static final String KNOWLEDGE_PERMISSION = "mes:assistant:knowledge:read";
    public static final String ROUTING_PERMISSION = "mes:assistant:routing:read";
    private static final int MAX_BODY_BYTES = 65536;
    private static final String SESSION_KEY = PythonAgentService.class.getName() + ".conversations";
    private static final Set<String> STATUSES = set("SUCCESS", "PARTIAL_SUCCESS", "NO_KNOWLEDGE", "DOCUMENT_FORBIDDEN", "NO_INDEX", "RETRIEVAL_ERROR", "TOOL_ERROR", "UNSUPPORTED", "NEEDS_INPUT", "TIMEOUT");
    private static final Set<String> NODES = set("classify_intent", "retrieve_knowledge", "execute_mes_tool", "generate_answer", "validate_answer", "handle_error");
    private final PythonAgentProperties properties;
    private final ObjectMapper mapper;
    private final JsonFactory strictJson = new JsonFactory().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    private final RestTemplate http;
    private final Clock clock;
    // Shiro may expose a different Session wrapper for each request to the same session.
    private final Object conversationLock = new Object();

    @Autowired public PythonAgentService(PythonAgentProperties properties, ObjectMapper mapper) {
        this(properties, mapper, Clock.systemUTC());
    }

    PythonAgentService(PythonAgentProperties properties, ObjectMapper mapper, Clock clock) {
        this.properties = properties;
        this.mapper = mapper.copy();
        this.clock = clock;
        http = new RestTemplate(requestFactory(properties));
        // Bound error bodies ourselves; default error handling buffers them first.
        http.setErrorHandler(new ResponseErrorHandler() {
            @Override public boolean hasError(ClientHttpResponse response) { return false; }
            @Override public void handleError(ClientHttpResponse response) { }
        });
    }

    public Map<String, Object> chat(String question, String intentHint, String conversationId, Subject subject,
                                    String materialCode, Map<String, Object> routingResult) {
        if (subject == null || !subject.isAuthenticated()) return failure("AUTH_REQUIRED", "请先登录后使用助手。");
        boolean composite = "COMPOSITE".equals(intentHint);
        if (!composite && !"KNOWLEDGE".equals(intentHint)) return failure("UNSUPPORTED", "当前助手仅支持授权的只读工具。");
        if (!subject.isPermitted(KNOWLEDGE_PERMISSION) || composite && !subject.isPermitted(ROUTING_PERMISSION))
            return failure("FORBIDDEN", "当前账号没有此查询所需的助手权限。");
        Map<String, Object> invalid = MesAssistantService.validateReadOnlyQuestion(question);
        if (invalid != null) return invalid;
        if (!validConversationId(conversationId))
            return failure("INVALID_INPUT", "会话标识格式无效，请清空后重试。");
        if (composite) {
            MaterialCodeCandidates candidates = new MaterialCodeCandidates(question);
            if (!candidates.isValid() || candidates.getCodes().size() != 1 || !candidates.getCodes().contains(materialCode) || routingResult == null)
                return failure("INVALID_INPUT", "复合查询需要原始问题中的唯一有效物料编码。");
        }
        if (!properties.isEnabled() || !properties.isValid() || CookieHandler.getDefault() != null) return unavailable();
        String requestId = UUID.randomUUID().toString();
        List<String> scopes = new ArrayList<>(Collections.singletonList("public"));
        if (subject.isPermitted("mes:assistant:knowledge:restricted")) scopes.add("restricted");
        List<String> tools = new ArrayList<>(Collections.singletonList("search_sop_knowledge"));
        if (composite) tools.add("get_material_routing");
        try {
            long issuedAt = clock.instant().getEpochSecond();
            ObjectNode body = mapper.createObjectNode();
            body.put("requestId", requestId); body.put("question", question); body.put("intentHint", intentHint);
            SessionContext context = sessionContext(subject, conversationId, question, issuedAt);
            body.set("messages", mapper.valueToTree(context.messages));
            ObjectNode acl = body.putObject("authorizedContext");
            acl.put("subject", context.subject); acl.set("scopes", mapper.valueToTree(scopes));
            acl.set("allowedTools", mapper.valueToTree(tools)); acl.put("issuedAt", issuedAt); acl.put("expiresAt", issuedAt + 60);
            body.putArray("providedToolResults");
            Map<String, Object> safeRouting = null;
            if (composite) {
                safeRouting = safeRouting(routingResult);
                ObjectNode supplied = body.withArray("providedToolResults").addObject();
                supplied.put("tool", "get_material_routing"); supplied.put("materialCode", materialCode); supplied.set("result", mapper.valueToTree(safeRouting));
            }
            byte[] bytes = mapper.writeValueAsBytes(body);
            if (bytes.length > MAX_BODY_BYTES) return failure("INVALID_INPUT", "问题或路线结果过大，请缩小查询范围。");
            String timestamp = Long.toString(issuedAt);
            String signature = signature(bytes, timestamp, requestId);
            Map<String, Object> result = http.execute(URI.create(properties.getEndpoint()), HttpMethod.POST, request -> {
                request.getHeaders().setContentType(MediaType.APPLICATION_JSON);
                request.getHeaders().set("X-MES-Timestamp", timestamp);
                request.getHeaders().set("X-MES-Nonce", requestId);
                request.getHeaders().set("X-MES-Signature", signature);
                request.getHeaders().setContentLength(bytes.length);
                request.getBody().write(bytes);
            }, response -> extract(response, requestId, scopes, tools));
            if (safeRouting != null && !isBridgeFailure(result)) {
                // The database result stays authoritative even if Python emits business fields.
                for (String field : Arrays.asList("material", "routing", "steps"))
                    if (safeRouting.containsKey(field)) result.put(field, safeRouting.get(field));
            }
            return result;
        } catch (ResourceAccessException transport) {
            return hasCause(transport, SocketTimeoutException.class) ? failure("TIMEOUT", "知识服务查询超时，请稍后重试。") : unavailable();
        } catch (IOException | GeneralSecurityException | RestClientException | IllegalArgumentException invalidResponse) {
            return unavailable();
        }
    }

    private Map<String, Object> extract(ClientHttpResponse response, String requestId, List<String> scopes, List<String> tools) throws IOException {
        int status = response.getRawStatusCode();
        byte[] bytes;
        try { bytes = readBounded(response.getBody()); }
        catch (IOException invalidBody) {
            if (status < 200 || status >= 300) return unavailable();
            if (invalidBody instanceof ResponseTooLargeException) return invalidResponse();
            throw invalidBody;
        }
        if (status < 200 || status >= 300) return unavailable();
        try { return validateResponse(readStrict(bytes), requestId, scopes, tools); }
        catch (IOException | IllegalArgumentException malformed) { return invalidResponse(); }
    }

    private Map<String, Object> validateResponse(JsonNode root, String requestId, List<String> scopes, List<String> tools) throws IOException {
        require(requestId.equals(text(root, "requestId", 36)) && STATUSES.contains(text(root, "status", 32)));
        require(set("rag", "hybrid", "agent").contains(text(root, "mode", 16)));
        text(root, "answer", 8000);
        require(bool(root, "agentExecuted") && "LANGGRAPH".equals(text(root, "agentFramework", 16)));
        require(set("SUCCESS", "NO_MATCH", "FORBIDDEN", "NOT_INDEXED", "ERROR", "NOT_REQUESTED").contains(text(root, "retrievalStatus", 24)));
        String answerMode = text(root, "answerMode", 32);
        require(set("EXTRACTIVE_NO_MODEL", "GROUNDED_MODEL", "EXTRACTIVE_FALLBACK", "NONE").contains(answerMode));
        boolean configured = bool(root, "llmConfigured"), attempted = bool(root, "modelAttempted"), connected = bool(root, "modelConnected");
        String source = text(root, "recognitionSource", 24);
        require(set("RULE", "MODEL", "RULE_FALLBACK").contains(source));
        require(root.has("fallbackReason"));
        if (!root.path("fallbackReason").isNull()) require(set("LLM_TIMEOUT", "LLM_UNAVAILABLE", "LLM_INVALID_RESPONSE", "LLM_UNGROUNDED").contains(text(root, "fallbackReason", 64)));
        require(!connected || attempted && configured);
        require(!"MODEL".equals(source) || connected);
        require(!"GROUNDED_MODEL".equals(answerMode) || connected);
        require(configured || !attempted && !connected && "RULE".equals(source) && root.path("fallbackReason").isNull()
                && set("EXTRACTIVE_NO_MODEL", "NONE").contains(answerMode));
        require(!"RULE_FALLBACK".equals(source) || attempted && !connected && !root.path("fallbackReason").isNull());
        nonnegative(root, "elapsedMs");
        JsonNode nodes = array(root, "executedNodes", 12);
        for (JsonNode node : nodes) require(node.isTextual() && NODES.contains(node.textValue()));
        JsonNode calls = array(root, "toolCalls", 2);
        List<Map<String, Object>> safeCalls = new ArrayList<>();
        Set<String> called = new HashSet<>();
        for (JsonNode call : calls) {
            String name = text(call, "name", 32);
            require(tools.contains(name) && called.add(name));
            require(set("SUCCESS", "NO_RESULTS", "DENIED", "ERROR").contains(text(call, "status", 16)));
            nonnegative(call, "elapsedMs");
            safeCalls.add(select(call, "name", "status", "elapsedMs"));
        }
        List<Map<String, Object>> citations = new ArrayList<>();
        for (JsonNode citation : array(root, "citations", 8)) {
            identifier(citation, "documentId"); identifier(citation, "chunkId");
            String filename = text(citation, "filename", 255);
            require(!filename.matches(".*[\\\\/:<>\"|?*].*") && !".".equals(filename) && !"..".equals(filename));
            text(citation, "documentVersion", 128); text(citation, "quote", 1500);
            require(citation.path("pageNumber").isIntegralNumber() && citation.path("pageNumber").canConvertToInt()
                    && citation.path("pageNumber").intValue() > 0);
            JsonNode score = citation.path("score");
            require(score.isNumber() && Double.isFinite(score.doubleValue()) && score.doubleValue() >= -1 && score.doubleValue() <= 1);
            require(scopes.contains(text(citation, "scope", 16)));
            citations.add(select(citation, "documentId", "filename", "documentVersion", "pageNumber", "chunkId", "quote", "score", "scope"));
        }
        Map<String, Object> result = select(root, "requestId", "status", "mode", "answer", "agentExecuted", "agentFramework", "executedNodes", "retrievalStatus", "answerMode", "llmConfigured", "recognitionSource", "modelAttempted", "modelConnected", "fallbackReason", "elapsedMs");
        result.put("citations", citations); result.put("toolCalls", safeCalls);
        return result;
    }

    private Map<String, Object> select(JsonNode node, String... fields) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String field : fields) result.put(field, mapper.convertValue(node.path(field), Object.class));
        return result;
    }

    private static String text(JsonNode node, String field, int limit) throws IOException {
        JsonNode value = node.path(field);
        require(value.isTextual() && !value.textValue().trim().isEmpty() && value.textValue().length() <= limit);
        String text = value.textValue();
        for (int i = 0; i < text.length(); i++) require(!Character.isISOControl(text.charAt(i)) || Character.isWhitespace(text.charAt(i)));
        return text;
    }
    private static boolean bool(JsonNode node, String field) throws IOException { require(node.path(field).isBoolean()); return node.path(field).booleanValue(); }
    private static void identifier(JsonNode node, String field) throws IOException { require(text(node, field, 256).matches("[A-Za-z0-9._:-]+")); }
    private static JsonNode array(JsonNode node, String field, int max) throws IOException { JsonNode value = node.path(field); require(value.isArray() && value.size() <= max); return value; }
    private static void nonnegative(JsonNode node, String field) throws IOException { require(node.path(field).isIntegralNumber() && node.path(field).canConvertToLong() && node.path(field).longValue() >= 0); }
    private static void require(boolean condition) throws IOException { if (!condition) throw new IOException("Invalid agent response"); }

    private JsonNode readStrict(byte[] bytes) throws IOException {
        try (JsonParser parser = strictJson.createParser(bytes)) {
            int depth = 0; JsonToken token;
            while ((token = parser.nextToken()) != null) {
                if ((token == JsonToken.START_ARRAY || token == JsonToken.START_OBJECT) && ++depth > 16) throw new IOException();
                if (token == JsonToken.END_ARRAY || token == JsonToken.END_OBJECT) depth--;
            }
        }
        try (JsonParser parser = strictJson.createParser(bytes)) {
            JsonNode root = mapper.readTree(parser);
            require(root != null && root.isObject() && parser.nextToken() == null);
            return root;
        }
    }

    private String signature(byte[] bytes, String timestamp, String nonce) throws GeneralSecurityException {
        String canonical = "POST\n/api/agent/chat\n" + timestamp + "\n" + nonce + "\n" + hex(MessageDigest.getInstance("SHA-256").digest(bytes));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(properties.getSharedSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return hex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
    }

    private static String hex(byte[] bytes) {
        char[] digits = "0123456789abcdef".toCharArray(); StringBuilder output = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) output.append(digits[(b & 255) >>> 4]).append(digits[b & 15]);
        return output.toString();
    }

    public boolean hasKnowledgeConversation(Subject subject, String conversationId) {
        if (subject == null || !subject.isAuthenticated() || conversationId == null || conversationId.isEmpty() || !validConversationId(conversationId)) return false;
        Session session = subject.getSession(false);
        if (session == null) return false;
        synchronized (conversationLock) {
            Object saved = session.getAttribute(SESSION_KEY);
            if (!(saved instanceof ConversationState)) return false;
            ConversationState state = (ConversationState) saved;
            if (!Objects.equals(state.owner, owner(subject))) return false;
            Conversation conversation = state.conversations.get(conversationId);
            long now = clock.instant().getEpochSecond();
            return conversation != null && !conversation.questions.isEmpty() && now >= conversation.updatedAt && now - conversation.updatedAt < 1800;
        }
    }

    private static String owner(Subject subject) {
        Object principal = subject.getPrincipal();
        if (!(principal instanceof SysUser)) return String.valueOf(principal);
        SysUser user = (SysUser) principal;
        return user.getId() != null ? user.getId() : user.getUsername();
    }

    private SessionContext sessionContext(Subject subject, String conversationId, String question, long now) {
        String owner = owner(subject);
        Session session = subject.getSession();
        if (session == null) return new SessionContext(UUID.randomUUID().toString(), Collections.emptyList());
        synchronized (conversationLock) {
            Object saved = session.getAttribute(SESSION_KEY);
            ConversationState state = saved instanceof ConversationState ? (ConversationState) saved : null;
            if (state == null || !Objects.equals(state.owner, owner)) state = new ConversationState(owner);
            List<Map<String, String>> messages = new ArrayList<>();
            state.conversations.entrySet().removeIf(entry -> now - entry.getValue().updatedAt >= 1800 || now < entry.getValue().updatedAt);
            if (conversationId != null && !conversationId.isEmpty()) {
                Conversation conversation = state.conversations.remove(conversationId);
                if (conversation == null) conversation = new Conversation();
                for (String previous : conversation.questions) {
                    Map<String, String> message = new LinkedHashMap<>(); message.put("role", "user"); message.put("content", previous); messages.add(message);
                }
                conversation.questions.add(question);
                while (conversation.questions.size() > 4) conversation.questions.remove(0);
                conversation.updatedAt = now; state.conversations.put(conversationId, conversation);
                while (state.conversations.size() > 8) state.conversations.remove(state.conversations.keySet().iterator().next());
            }
            session.setAttribute(SESSION_KEY, state);
            return new SessionContext(state.subject, messages);
        }
    }

    private static Map<String, Object> safeRouting(Map<String, Object> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        copyText(source, result, "status", 32); copyText(source, result, "answer", 2000);
        for (String key : Arrays.asList("material", "routing")) {
            Object identity = source.get(key);
            if (identity instanceof Map) { Map<String, Object> safe = new LinkedHashMap<>(); copyText((Map<?, ?>) identity, safe, "code", 255); copyText((Map<?, ?>) identity, safe, "name", 1000); result.put(key, safe); }
        }
        if (source.get("steps") instanceof List) {
            List<Map<String, Object>> steps = new ArrayList<>();
            for (Object value : (List<?>) source.get("steps")) {
                if (steps.size() >= 100 || !(value instanceof Map)) throw new IllegalArgumentException();
                Map<?, ?> step = (Map<?, ?>) value; Map<String, Object> safe = new LinkedHashMap<>();
                Object order = step.get("order");
                if (order == null || order instanceof Integer) safe.put("order", order); else throw new IllegalArgumentException();
                copyText(step, safe, "code", 255); copyText(step, safe, "name", 1000); steps.add(safe);
            }
            result.put("steps", steps);
        }
        return result;
    }
    private static void copyText(Map<?, ?> source, Map<String, Object> target, String key, int max) {
        Object value = source.get(key);
        if (value == null) return;
        if (!(value instanceof String) || ((String) value).length() > max) throw new IllegalArgumentException();
        target.put(key, value);
    }

    private static byte[] readBounded(InputStream input) throws IOException {
        // Close the stream here so Spring cannot drain an oversized body afterwards.
        try (InputStream body = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (body == null) return new byte[0];
            byte[] buffer = new byte[4096]; int count;
            while ((count = body.read(buffer, 0, Math.min(buffer.length, MAX_BODY_BYTES + 1 - output.size()))) != -1) {
                output.write(buffer, 0, count);
                if (output.size() > MAX_BODY_BYTES) throw new ResponseTooLargeException();
            }
            return output.toByteArray();
        }
    }

    private static SimpleClientHttpRequestFactory requestFactory(PythonAgentProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory() {
            @Override protected HttpURLConnection openConnection(URL url, Proxy proxy) throws IOException {
                if (CookieHandler.getDefault() != null) throw new IOException("Unsafe cookie configuration");
                HttpURLConnection connection = super.openConnection(url, proxy);
                if (CookieHandler.getDefault() != null) { connection.disconnect(); throw new IOException("Unsafe cookie configuration"); }
                return connection;
            }
            @Override protected void prepareConnection(HttpURLConnection connection, String method) throws IOException {
                super.prepareConnection(connection, method); connection.setInstanceFollowRedirects(false);
            }
        };
        factory.setProxy(Proxy.NO_PROXY);
        factory.setConnectTimeout(properties.getConnectTimeoutMs() > 0 ? properties.getConnectTimeoutMs() : 3000);
        factory.setReadTimeout(properties.getReadTimeoutMs() > 0 ? properties.getReadTimeoutMs() : 35000);
        return factory;
    }

    private static Map<String, Object> failure(String status, String answer) {
        Map<String, Object> result = AssistantResponses.reply(status, "agent", answer);
        result.put("agentExecuted", false); result.put("citations", Collections.emptyList()); result.put("answerMode", "NONE");
        result.put("llmConfigured", false); result.put("retrievalStatus", "ERROR"); result.put("toolCalls", Collections.emptyList()); result.put("executedNodes", Collections.emptyList());
        return result;
    }
    public static Map<String, Object> unavailable() { return failure("AGENT_UNAVAILABLE", "知识服务暂时不可用，请检查服务配置后重试。"); }
    private static Map<String, Object> invalidResponse() { return failure("INVALID_AGENT_RESPONSE", "知识服务返回了无效结果，请稍后重试。"); }
    private static boolean isBridgeFailure(Map<String, Object> result) { return result == null || !Boolean.TRUE.equals(result.get("agentExecuted")); }
    private static boolean hasCause(Throwable failure, Class<? extends Throwable> type) { for (Throwable cause = failure; cause != null; cause = cause.getCause()) if (type.isInstance(cause)) return true; return false; }
    public static boolean validConversationId(String value) {
        if (value == null || value.isEmpty()) return true;
        try { return UUID.fromString(value).toString().equalsIgnoreCase(value); }
        catch (IllegalArgumentException invalid) { return false; }
    }
    private static Set<String> set(String... values) { return new HashSet<>(Arrays.asList(values)); }
    private static final class ResponseTooLargeException extends IOException { private static final long serialVersionUID = 1L; }
    private static final class SessionContext {
        private final String subject; private final List<Map<String, String>> messages;
        private SessionContext(String subject, List<Map<String, String>> messages) { this.subject = subject; this.messages = messages; }
    }
    private static final class ConversationState implements Serializable {
        private static final long serialVersionUID = 1L;
        private final String owner; private final String subject = UUID.randomUUID().toString();
        private final LinkedHashMap<String, Conversation> conversations = new LinkedHashMap<>();
        private ConversationState(String owner) { this.owner = owner; }
    }
    private static final class Conversation implements Serializable {
        private static final long serialVersionUID = 1L;
        private long updatedAt; private final List<String> questions = new ArrayList<>();
    }
}
