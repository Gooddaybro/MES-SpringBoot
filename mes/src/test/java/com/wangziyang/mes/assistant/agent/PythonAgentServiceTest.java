package com.wangziyang.mes.assistant.agent;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.apache.shiro.session.Session;
import org.apache.shiro.session.mgt.DefaultSessionContext;
import org.apache.shiro.session.mgt.DefaultSessionKey;
import org.apache.shiro.session.mgt.DefaultSessionManager;
import org.apache.shiro.session.mgt.SessionKey;
import org.apache.shiro.session.mgt.SimpleSession;
import org.apache.shiro.subject.Subject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.client.RestTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class PythonAgentServiceTest {
    private static final String SECRET = "test-only-32-character-shared-secret";
    private final ObjectMapper mapper = new ObjectMapper();
    private HttpServer server;
    private ExecutorService executor;
    private String baseUrl;
    private Subject subject;
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicInteger redirects = new AtomicInteger();
    private byte[] rawBody;
    private String timestamp, nonce, signature, cookie, authorization;
    private volatile int responseStatus = 200;
    private volatile long delayMs;
    private volatile String rawResponse;
    private UnaryOperator<ObjectNode> responseChange = value -> value;

    @Before public void setUp() throws Exception {
        subject = principal("private-user-id", new SimpleSession());
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/api/agent/chat", this::respond);
        server.createContext("/redirect-target", exchange -> { redirects.incrementAndGet(); exchange.sendResponseHeaders(200, -1); exchange.close(); });
        server.start();
    }

    @After public void tearDown() { server.stop(0); executor.shutdownNow(); }

    @Test public void signsExactBodyAndDerivesMinimalServerAclWithoutCredentials() throws Exception {
        PythonAgentService service = service(configured());
        Map<String, Object> result = ask(service, "安全文档的防护要求是什么", UUID.randomUUID().toString());
        assertEquals("SUCCESS", result.get("status"));
        assertEquals(1, requests.get());
        assertNull(cookie); assertNull(authorization);
        JsonNode body = mapper.readTree(rawBody);
        assertEquals(set("requestId", "question", "intentHint", "messages", "authorizedContext", "providedToolResults"), fields(body));
        assertEquals(nonce, body.path("requestId").asText());
        assertEquals(nonce, UUID.fromString(nonce).toString());
        String canonical = "POST\n/api/agent/chat\n" + timestamp + "\n" + nonce + "\n" + hex(MessageDigest.getInstance("SHA-256").digest(rawBody));
        Mac hmac = Mac.getInstance("HmacSHA256");
        hmac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        assertEquals(hex(hmac.doFinal(canonical.getBytes(StandardCharsets.UTF_8))), signature);
        JsonNode context = body.path("authorizedContext");
        assertEquals(Long.parseLong(timestamp), context.path("issuedAt").asLong());
        assertEquals(60, context.path("expiresAt").asLong() - context.path("issuedAt").asLong());
        assertEquals(Collections.singletonList("public"), mapper.convertValue(context.path("scopes"), List.class));
        assertEquals(Collections.singletonList("search_sop_knowledge"), mapper.convertValue(context.path("allowedTools"), List.class));
        assertNotNull(UUID.fromString(context.path("subject").asText()));
        assertFalse(new String(rawBody, StandardCharsets.UTF_8).contains("private-user-id"));
        assertFalse(new String(rawBody, StandardCharsets.UTF_8).contains(SECRET));
        assertEquals("EXTRACTIVE_NO_MODEL", result.get("answerMode"));
        assertEquals(false, result.get("llmConfigured"));
        assertEquals("RULE", result.get("recognitionSource"));
        assertEquals(false, result.get("modelAttempted"));
        assertEquals(false, result.get("modelConnected"));
        assertNull(result.get("fallbackReason"));
    }

    @Test public void restrictedRequiresItsOwnExplicitGrant() throws Exception {
        when(subject.isPermitted("mes:assistant:knowledge:restricted")).thenReturn(true);
        assertEquals("SUCCESS", ask(service(configured()), "安全文档要求", null).get("status"));
        assertEquals(Arrays.asList("public", "restricted"), mapper.convertValue(mapper.readTree(rawBody).path("authorizedContext").path("scopes"), List.class));
    }

    @Test public void missingDisabledShortSecretAndUnsafeConfigNeverMakeARequest() {
        List<MockEnvironment> environments = Arrays.asList(new MockEnvironment(), configured().withProperty("MES_AGENT_ENABLED", "false"),
                configured().withProperty("MES_AGENT_SHARED_SECRET", ""), configured().withProperty("MES_AGENT_SHARED_SECRET", "too-short"),
                configured().withProperty("MES_AGENT_BASE_URL", "http://169.254.169.254"), configured().withProperty("MES_AGENT_BASE_URL", baseUrl + "/other"),
                configured().withProperty("MES_AGENT_READ_TIMEOUT_MS", "0"), configured().withProperty("MES_AGENT_ENABLED", "maybe"));
        for (MockEnvironment environment : environments) assertEquals("AGENT_UNAVAILABLE", ask(service(environment), "安全文档要求", null).get("status"));
        assertEquals(0, requests.get());
    }

    @Test public void propertiesKeepDefaultsAndOnlyLoopbackDestinations() {
        PythonAgentProperties defaults = new PythonAgentProperties(new MockEnvironment());
        assertFalse(defaults.isEnabled());
        assertEquals("http://127.0.0.1:8001/api/agent/chat", defaults.getEndpoint());
        assertEquals(3000, defaults.getConnectTimeoutMs()); assertEquals(35000, defaults.getReadTimeoutMs());
        for (String url : Arrays.asList("http://localhost:8001", "http://LOCALHOST:8001/", "http://[::1]:8001/", "https://127.0.0.1:8001"))
            assertTrue(url, new PythonAgentProperties(configured().withProperty("MES_AGENT_BASE_URL", url)).isValid());
        assertEquals("http://localhost:8001/api/agent/chat", new PythonAgentProperties(configured().withProperty("MES_AGENT_BASE_URL", " http://localhost:8001/ ")).getEndpoint());
        for (String url : Arrays.asList("http://10.1.2.3:8001", "http://172.16.1.2:8001", "https://192.168.1.2:8001", "https://example.com", "http://127.1", "http://localhost.evil", "http://user:password@127.0.0.1", baseUrl + "?secret=x", baseUrl + "#x", "ftp://127.0.0.1", "http://127.0.0.1:0", "http://172.32.0.1"))
            assertFalse(url, new PythonAgentProperties(configured().withProperty("MES_AGENT_BASE_URL", url)).isValid());
    }

    @Test public void usesBoundedSubjectBoundConversationHistoryAndNewIdsIsolateIt() throws Exception {
        PythonAgentService service = service(configured());
        String conversation = UUID.randomUUID().toString();
        for (int i = 0; i < 6; i++) assertEquals("SUCCESS", ask(service, "文档问题" + i, conversation).get("status"));
        JsonNode body = mapper.readTree(rawBody);
        assertEquals(4, body.path("messages").size());
        assertEquals("文档问题1", body.path("messages").get(0).path("content").asText());
        for (JsonNode message : body.path("messages")) { assertEquals("user", message.path("role").asText()); assertEquals(2, message.size()); }
        String firstSubject = body.path("authorizedContext").path("subject").asText();
        ask(service, "新会话文档问题", UUID.randomUUID().toString());
        assertEquals(0, mapper.readTree(rawBody).path("messages").size());
        ask(service, "单次文档问题", null); ask(service, "另一单次文档问题", null);
        assertEquals(0, mapper.readTree(rawBody).path("messages").size());
        when(subject.getPrincipal()).thenReturn("another-private-user");
        ask(service, "切换用户后的文档问题", conversation);
        assertEquals(0, mapper.readTree(rawBody).path("messages").size());
        assertNotEquals(firstSubject, mapper.readTree(rawBody).path("authorizedContext").path("subject").asText());
    }

    @Test public void expiresOldHistoryAndCapsConversations() throws Exception {
        MutableClock clock = new MutableClock();
        PythonAgentService service = new PythonAgentService(new PythonAgentProperties(configured()), mapper, clock);
        String first = UUID.randomUUID().toString();
        assertEquals("SUCCESS", ask(service, "首个文档问题", first).get("status"));
        clock.seconds += 1801;
        ask(service, "过期后的文档问题", first);
        assertEquals(0, mapper.readTree(rawBody).path("messages").size());
        for (int i = 0; i < 8; i++) ask(service, "其他文档问题", UUID.randomUUID().toString());
        ask(service, "已淘汰会话文档问题", first);
        assertEquals(0, mapper.readTree(rawBody).path("messages").size());
    }

    @Test public void followupExistsOnlyForSameAuthenticatedSubjectConversationWithinTtl() {
        MutableClock clock = new MutableClock();
        PythonAgentService service = new PythonAgentService(new PythonAgentProperties(configured()), mapper, clock);
        String conversation = UUID.randomUUID().toString();
        assertFalse(service.hasKnowledgeConversation(subject, conversation));
        assertEquals("SUCCESS", ask(service, "安全文档有哪些要求", conversation).get("status"));
        assertTrue(service.hasKnowledgeConversation(subject, conversation));
        assertFalse(service.hasKnowledgeConversation(subject, UUID.randomUUID().toString()));
        when(subject.getPrincipal()).thenReturn("other-user");
        assertFalse(service.hasKnowledgeConversation(subject, conversation));
        when(subject.getPrincipal()).thenReturn("private-user-id");
        clock.seconds += 1801;
        assertFalse(service.hasKnowledgeConversation(subject, conversation));
        when(subject.isAuthenticated()).thenReturn(false);
        assertFalse(service.hasKnowledgeConversation(subject, conversation));
    }

    @Test public void concurrentRequestsThroughDistinctShiroWrappersKeepBothQuestions() throws Exception {
        CountDownLatch initialReads = new CountDownLatch(2);
        DefaultSessionManager sessions = new DefaultSessionManager() {
            @Override public Object getAttribute(SessionKey key, Object name) {
                Object value = super.getAttribute(key, name);
                if (value == null && (PythonAgentService.class.getName() + ".conversations").equals(name)) {
                    initialReads.countDown();
                    try { initialReads.await(1, TimeUnit.SECONDS); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
                }
                return value;
            }
        };
        sessions.setSessionValidationSchedulerEnabled(false);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Session first = sessions.start(new DefaultSessionContext());
            Session second = sessions.getSession(new DefaultSessionKey(first.getId()));
            assertEquals(first.getId(), second.getId()); assertNotSame(first, second);
            Subject firstSubject = principal("private-user-id", first), secondSubject = principal("private-user-id", second);
            PythonAgentService service = service(configured());
            String conversation = UUID.randomUUID().toString();
            CountDownLatch start = new CountDownLatch(1);
            Future<Map<String, Object>> firstResult = workers.submit(() -> { start.await(); return service.chat("第一条文档问题", "KNOWLEDGE", conversation, firstSubject, null, null); });
            Future<Map<String, Object>> secondResult = workers.submit(() -> { start.await(); return service.chat("第二条文档问题", "KNOWLEDGE", conversation, secondSubject, null, null); });
            start.countDown();
            assertEquals("SUCCESS", firstResult.get(5, TimeUnit.SECONDS).get("status"));
            assertEquals("SUCCESS", secondResult.get(5, TimeUnit.SECONDS).get("status"));
            assertTrue(service.hasKnowledgeConversation(secondSubject, conversation));
            subject = firstSubject;
            assertEquals("SUCCESS", ask(service, "后续文档问题", conversation).get("status"));
            JsonNode messages = mapper.readTree(rawBody).path("messages");
            assertEquals("Concurrent first requests must both survive in session history", 2, messages.size());
            Set<String> previous = new HashSet<>();
            for (JsonNode message : messages) previous.add(message.path("content").asText());
            assertEquals(set("第一条文档问题", "第二条文档问题"), previous);
        } finally { workers.shutdownNow(); sessions.destroy(); }
    }

    @Test public void rejectsUntrustedConversationIdsAndMissingPermissionsWithoutHttp() {
        PythonAgentService service = service(configured());
        for (String id : Arrays.asList("not-a-uuid", "1-1-1-1-1", "../other", repeat("a", 1000)))
            assertEquals("INVALID_INPUT", ask(service, "文档要求", id).get("status"));
        when(subject.isPermitted("mes:assistant:knowledge:read")).thenReturn(false);
        assertEquals("FORBIDDEN", ask(service, "文档要求", null).get("status"));
        assertEquals(0, requests.get());
    }

    @Test public void compositeForwardsOnlyAllowlistedBusinessFieldsAndGrantsTwoTools() throws Exception {
        when(subject.isPermitted("mes:assistant:routing:read")).thenReturn(true);
        Map<String, Object> route = new LinkedHashMap<>();
        route.put("status", "SUCCESS"); route.put("answer", "现有路线"); route.put("password", "must-not-send");
        Map<String, Object> material = new LinkedHashMap<>(); material.put("id", "private-row-id"); material.put("code", "A_1-2/3.4"); material.put("name", "物料名称");
        route.put("material", material);
        route.put("steps", Arrays.asList(new LinkedHashMap<String, Object>() {{ put("order", 1); put("code", "OP1"); put("name", "装配"); put("operationId", "private-operation-id"); }}));
        assertEquals("SUCCESS", service(configured()).chat("查询A_1-2/3.4的工艺路线和安全文档", "COMPOSITE", null, subject, "A_1-2/3.4", route).get("status"));
        JsonNode body = mapper.readTree(rawBody);
        assertEquals(Arrays.asList("search_sop_knowledge", "get_material_routing"), mapper.convertValue(body.path("authorizedContext").path("allowedTools"), List.class));
        JsonNode supplied = body.path("providedToolResults").get(0);
        assertEquals("get_material_routing", supplied.path("tool").asText()); assertEquals("A_1-2/3.4", supplied.path("materialCode").asText());
        String encoded = supplied.toString(); assertFalse(encoded.contains("private-row-id")); assertFalse(encoded.contains("must-not-send")); assertFalse(encoded.contains("private-operation-id"));
        assertEquals("装配", supplied.path("result").path("steps").get(0).path("name").asText());
    }

    @Test public void refusesCompositeCodeNotFromOriginalAndNeverGrantsWriteTools() {
        when(subject.isPermitted("mes:assistant:routing:read")).thenReturn(true);
        PythonAgentService service = service(configured());
        assertEquals("INVALID_INPUT", service.chat("100112的工艺路线及安全文档", "COMPOSITE", null, subject, "invented", Collections.emptyMap()).get("status"));
        assertEquals("UNSUPPORTED", service.chat("安全文档", "EXECUTE_SQL", null, subject, null, null).get("status"));
        assertEquals(0, requests.get());
    }

    @Test public void stripsUnknownTopLevelFieldsAndRejectsUnauthorizedCitations() {
        responseChange = value -> { value.put("secret", "never-return"); value.putObject("html").put("script", "evil"); return value; };
        Map<String, Object> valid = ask(service(configured()), "安全文档", null);
        assertEquals("SUCCESS", valid.get("status")); assertFalse(valid.containsKey("secret")); assertFalse(valid.containsKey("html"));
        responseChange = value -> { ((ObjectNode)value.path("citations").get(0)).put("scope", "restricted"); return value; };
        assertEquals("INVALID_AGENT_RESPONSE", ask(service(configured()), "安全文档", null).get("status"));
    }

    @Test public void rejectsBadResponseSchemaCrossRequestIdsPathsAndOversizedFields() {
        List<UnaryOperator<ObjectNode>> changes = Arrays.asList(
                value -> { value.put("requestId", UUID.randomUUID().toString()); return value; },
                value -> { value.put("agentExecuted", "true"); return value; },
                value -> { value.put("agentFramework", "invented"); return value; },
                value -> { value.put("answer", repeat("x", 8001)); return value; },
                value -> { ((ObjectNode)value.path("citations").get(0)).put("filename", "C:\\private\\source.pdf"); return value; },
                value -> { ((ObjectNode)value.path("citations").get(0)).put("quote", repeat("x", 1501)); return value; },
                value -> { ((ObjectNode)value.path("citations").get(0)).put("pageNumber", 0); return value; },
                value -> { ((ObjectNode)value.path("citations").get(0)).put("score", 2.0); return value; },
                value -> { value.put("modelConnected", true); return value; },
                value -> { value.putArray("toolCalls").addObject().put("name", "execute_sql").put("status", "SUCCESS").put("elapsedMs", 0); return value; }
        );
        for (UnaryOperator<ObjectNode> change : changes) { responseChange = change; assertEquals("INVALID_AGENT_RESPONSE", ask(service(configured()), "安全文档", null).get("status")); }
    }

    @Test public void rejectsMalformedDuplicateTrailingDeepAndOversizedJson() {
        for (String response : Arrays.asList("{", "[]", "{} {}", "{\"status\":\"SUCCESS\",\"status\":\"ERROR\"}", "{\"nested\":" + repeat("[", 32) + "0" + repeat("]", 32) + "}", repeat("x", 65537))) {
            rawResponse = response;
            assertEquals("INVALID_AGENT_RESPONSE", ask(service(configured()), "安全文档", null).get("status"));
        }
    }

    @Test public void timeoutsAndHttpFailuresAreControlledAndRedirectsAreNeverFollowed() {
        delayMs = 200;
        assertEquals("TIMEOUT", ask(service(configured().withProperty("MES_AGENT_READ_TIMEOUT_MS", "25")), "private-question", null).get("status"));
        delayMs = 0;
        rawResponse = "private-service-error";
        for (int status : new int[]{401, 422, 500, 503, 302}) {
            responseStatus = status;
            Map<String, Object> result = ask(service(configured()), "private-question", null);
            assertEquals("AGENT_UNAVAILABLE", result.get("status")); assertFalse(result.toString().contains("private-service-error"));
        }
        assertEquals(0, redirects.get());
    }

    @Test public void noJvmCookieStoreOrSensitiveDebugLogging() {
        CookieHandler previous = CookieHandler.getDefault();
        try { CookieHandler.setDefault(new CookieManager()); assertEquals("AGENT_UNAVAILABLE", ask(service(configured()), "安全文档", null).get("status")); assertEquals(0, requests.get()); }
        finally { CookieHandler.setDefault(previous); }
        Logger logger = (Logger) LoggerFactory.getLogger(RestTemplate.class);
        Level level = logger.getLevel(); ListAppender<ILoggingEvent> capture = new ListAppender<>(); capture.start(); logger.addAppender(capture); logger.setLevel(Level.DEBUG);
        try { assertEquals("SUCCESS", ask(service(configured()), "private-question-sentinel", null).get("status")); }
        finally { logger.detachAppender(capture); logger.setLevel(level); capture.stop(); }
        for (ILoggingEvent event : capture.list) { assertFalse(event.getFormattedMessage().contains(SECRET)); assertFalse(event.getFormattedMessage().contains("private-question-sentinel")); assertNull(event.getThrowableProxy()); }
    }

    private Map<String, Object> ask(PythonAgentService service, String question, String conversation) { return service.chat(question, "KNOWLEDGE", conversation, subject, null, null); }
    private PythonAgentService service(MockEnvironment environment) { return new PythonAgentService(new PythonAgentProperties(environment), mapper); }
    private MockEnvironment configured() { return new MockEnvironment().withProperty("MES_AGENT_ENABLED", "true").withProperty("MES_AGENT_BASE_URL", baseUrl).withProperty("MES_AGENT_SHARED_SECRET", SECRET); }
    private Subject principal(String user, Session session) {
        Subject value = mock(Subject.class); when(value.isAuthenticated()).thenReturn(true); when(value.getPrincipal()).thenReturn(user); when(value.getSession()).thenReturn(session); when(value.getSession(true)).thenReturn(session); when(value.getSession(false)).thenReturn(session);
        when(value.isPermitted("mes:assistant:knowledge:read")).thenReturn(true); return value;
    }

    private ObjectNode success(String requestId) {
        ObjectNode root = mapper.createObjectNode(); root.put("requestId", requestId); root.put("status", "SUCCESS"); root.put("mode", "rag"); root.put("answer", "原文摘录：采取必要的防护措施。");
        root.put("agentExecuted", true); root.put("agentFramework", "LANGGRAPH"); root.putArray("executedNodes").add("classify_intent").add("retrieve_knowledge").add("generate_answer").add("validate_answer");
        root.putArray("toolCalls").addObject().put("name", "search_sop_knowledge").put("status", "SUCCESS").put("elapsedMs", 1);
        root.put("retrievalStatus", "SUCCESS"); root.put("answerMode", "EXTRACTIVE_NO_MODEL"); root.put("llmConfigured", false); root.put("recognitionSource", "RULE"); root.put("modelAttempted", false); root.put("modelConnected", false); root.putNull("fallbackReason"); root.put("elapsedMs", 2);
        root.putArray("citations").addObject().put("documentId", "doc-1").put("filename", "安全手册.pdf").put("documentVersion", "unknown").put("pageNumber", 2).put("chunkId", "doc-1-p2-c1").put("quote", "采取必要的防护措施。").put("score", 0.8).put("scope", "public");
        return root;
    }

    private void respond(HttpExchange exchange) throws IOException {
        requests.incrementAndGet(); byte[] requestBytes = readAll(exchange.getRequestBody()); rawBody = requestBytes;
        timestamp = exchange.getRequestHeaders().getFirst("X-MES-Timestamp"); nonce = exchange.getRequestHeaders().getFirst("X-MES-Nonce"); signature = exchange.getRequestHeaders().getFirst("X-MES-Signature"); cookie = exchange.getRequestHeaders().getFirst("Cookie"); authorization = exchange.getRequestHeaders().getFirst("Authorization");
        try {
            if (delayMs > 0) Thread.sleep(delayMs);
            if (responseStatus == 302) exchange.getResponseHeaders().set("Location", baseUrl + "/redirect-target");
            String body = rawResponse == null ? mapper.writeValueAsString(responseChange.apply(success(mapper.readTree(requestBytes).path("requestId").asText()))) : rawResponse;
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8); exchange.getResponseHeaders().set("Content-Type", "application/json"); exchange.sendResponseHeaders(responseStatus, bytes.length);
            try (OutputStream output = exchange.getResponseBody()) { output.write(bytes); }
        } catch (InterruptedException stop) { Thread.currentThread().interrupt(); } finally { exchange.close(); }
    }

    private static byte[] readAll(InputStream input) throws IOException { try (ByteArrayOutputStream output = new ByteArrayOutputStream()) { byte[] buffer = new byte[4096]; int n; while ((n = input.read(buffer)) != -1) output.write(buffer, 0, n); return output.toByteArray(); } }
    private static String hex(byte[] bytes) { StringBuilder text = new StringBuilder(); for (byte value : bytes) text.append(String.format(Locale.ROOT, "%02x", value & 255)); return text.toString(); }
    private static String repeat(String text, int count) { return String.join("", Collections.nCopies(count, text)); }
    private static Set<String> set(String... values) { return new HashSet<>(Arrays.asList(values)); }
    private static Set<String> fields(JsonNode value) { Set<String> result = new HashSet<>(); value.fieldNames().forEachRemaining(result::add); return result; }
    private static final class MutableClock extends Clock {
        private long seconds = 1790000000L;
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochSecond(seconds); }
    }
}
