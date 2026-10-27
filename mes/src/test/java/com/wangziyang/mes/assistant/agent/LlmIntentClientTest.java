package com.wangziyang.mes.assistant.agent;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.mock.env.MockEnvironment;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

public class LlmIntentClientTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private HttpServer server;
    private ExecutorService executor;
    private String baseUrl;
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicInteger redirectedRequests = new AtomicInteger();
    private final AtomicReference<String> requestBody = new AtomicReference<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private final AtomicReference<String> method = new AtomicReference<>();
    private final AtomicReference<String> path = new AtomicReference<>();
    private final AtomicReference<String> cookie = new AtomicReference<>();
    private volatile int status = 200;
    private volatile String response;
    private volatile long responseDelayMs;
    private volatile boolean pauseAfterOversizedChunk;
    private volatile boolean dropResponse;
    private volatile boolean bodylessResponse;

    @Before
    public void setUp() throws Exception {
        response = envelope(query("100112"));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
        server.createContext("/v1/chat/completions", this::respond);
        server.createContext("/redirect-target", exchange -> {
            redirectedRequests.incrementAndGet();
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        });
        server.start();
    }

    @After
    public void tearDown() {
        server.stop(0);
        executor.shutdownNow();
    }

    @Test
    public void missingConfigurationDoesNotAttemptAnHttpRequest() throws Exception {
        for (MockEnvironment environment : new MockEnvironment[]{new MockEnvironment(),
                configured().withProperty("MES_LLM_API_KEY", ""),
                configured().withProperty("MES_LLM_MODEL", " ")}) {
            assertFailure("MODEL_NOT_CONFIGURED", false, client(environment).recognize("material 100112"));
        }
        assertEquals(0, requests.get());
    }

    @Test
    public void sendsOnePrivateNonStreamingRequestAndAcceptsStrictRoutingResult() throws Exception {
        String question = "show the production path for 100112";
        LlmIntentResult result = client(configured()).recognize(question);
        assertTrue(result.isAttempted());
        assertTrue(result.isValid());
        assertNull(result.getFailureReason());
        assertEquals("QUERY_MATERIAL_ROUTING", result.getIntent());
        assertEquals("100112", result.getMaterialCode());
        assertFalse(result.isClarificationNeeded());
        assertEquals(1, requests.get());
        assertEquals("POST", method.get());
        assertEquals("/v1/chat/completions", path.get());
        assertEquals("Bearer test-fake-key", authorization.get());
        assertNull(cookie.get());
        JsonNode body = mapper.readTree(requestBody.get());
        assertEquals(set("model", "messages", "stream"), fields(body));
        assertEquals("test-model", body.get("model").textValue());
        assertFalse(body.get("stream").booleanValue());
        assertEquals(2, body.get("messages").size());
        JsonNode system = body.get("messages").get(0);
        JsonNode user = body.get("messages").get(1);
        assertEquals(set("role", "content"), fields(system));
        assertEquals(set("role", "content"), fields(user));
        assertEquals("system", system.get("role").textValue());
        assertTrue(system.get("content").textValue().contains("QUERY_MATERIAL_ROUTING"));
        assertTrue(system.get("content").textValue().contains("UNSUPPORTED"));
        assertEquals("user", user.get("role").textValue());
        assertEquals(question, user.get("content").textValue());
        assertFalse(requestBody.get().contains("test-fake-key"));
    }

    @Test
    public void debugLoggingContainsNoKeyQuestionOrRawResponse() throws Exception {
        String question = "private-question-sentinel 100112";
        String responseReason = "private-response-sentinel";
        response = envelope(query("100112").replace("routing", responseReason));
        Logger logger = (Logger) LoggerFactory.getLogger(RestTemplate.class);
        Level previousLevel = logger.getLevel();
        ListAppender<ILoggingEvent> capture = new ListAppender<>();
        capture.start();
        logger.addAppender(capture);
        logger.setLevel(Level.DEBUG);
        try {
            assertTrue(client(configured()).recognize(question).isValid());
        } finally {
            logger.detachAppender(capture);
            logger.setLevel(previousLevel);
            capture.stop();
        }
        assertFalse(capture.list.isEmpty());
        for (ILoggingEvent event : capture.list) {
            String logged = event.getFormattedMessage();
            assertFalse(logged.contains("test-fake-key"));
            assertFalse(logged.contains(question));
            assertFalse(logged.contains(responseReason));
            assertNull(event.getThrowableProxy());
        }
    }

    @Test
    public void normalizesEndpointAndKeepsDocumentedTimeoutDefaults() {
        for (String root : new String[]{baseUrl, baseUrl + "/", baseUrl + "///", baseUrl + "/chat/completions/"}) {
            LlmProperties properties = new LlmProperties(configured().withProperty("MES_LLM_BASE_URL", root));
            assertTrue(properties.isConfigured());
            assertTrue(properties.isValid());
            assertEquals(baseUrl + "/chat/completions", properties.getEndpoint());
            assertEquals(3000, properties.getConnectTimeoutMs());
            assertEquals(15000, properties.getReadTimeoutMs());
            assertFalse(properties.toString().contains("test-fake-key"));
        }
    }

    @Test
    public void acceptsExplicitValidatedTimeouts() {
        LlmProperties properties = new LlmProperties(configured()
                .withProperty("MES_LLM_CONNECT_TIMEOUT_MS", "1")
                .withProperty("MES_LLM_READ_TIMEOUT_MS", "60000"));
        assertTrue(properties.isValid());
        assertEquals(1, properties.getConnectTimeoutMs());
        assertEquals(60000, properties.getReadTimeoutMs());
    }

    @Test
    public void acceptsHttpsAndOnlyLiteralLoopbackHttpConfiguration() {
        for (String url : new String[]{"https://example.test/v1", "http://localhost:1/v1", "http://127.0.0.1:1/v1", "http://[::1]:1/v1"}) {
            assertTrue(url, new LlmProperties(configured().withProperty("MES_LLM_BASE_URL", url)).isValid());
        }
    }

    @Test
    public void invalidEndpointsFailClosedWithoutNetworkAccess() {
        for (String url : new String[]{"http://example.test/v1", "http://127.0.0.2/v1", "http://127.1/v1",
                "http://localhost.evil.test/v1", "ftp://localhost/v1", "https://user:secret@example.test/v1",
                baseUrl + "?api_key=fake", baseUrl + "#fragment", "not a URL", "https:///v1",
                "http://localhost:0/v1", "https://example.test:70000/v1"}) {
            assertFailure(url, "MODEL_INVALID_CONFIGURATION", false,
                    client(configured().withProperty("MES_LLM_BASE_URL", url)).recognize("100112"));
        }
        assertEquals(0, requests.get());
    }

    @Test
    public void unsafeKeysAndInvalidTimeoutsFailClosedWithoutNetworkAccess() {
        for (String key : new String[]{"test\r\nX-Injected: fake", "test\u0000fake", "test fake", "Bearer fake", "test\tfake", "test\u007ffake"}) {
            assertFailure("MODEL_INVALID_CONFIGURATION", false,
                    client(configured().withProperty("MES_LLM_API_KEY", key)).recognize("100112"));
        }
        for (String name : new String[]{"MES_LLM_CONNECT_TIMEOUT_MS", "MES_LLM_READ_TIMEOUT_MS"}) {
            for (String value : new String[]{"0", "-1", "60001", "not-a-number", "2147483648"}) {
                assertFailure("MODEL_INVALID_CONFIGURATION", false,
                        client(configured().withProperty(name, value)).recognize("100112"));
            }
        }
        assertEquals(0, requests.get());
    }

    @Test
    public void neverUsesJvmWideCookieStore() {
        CookieHandler previous = CookieHandler.getDefault();
        try {
            CookieHandler.setDefault(new CookieManager());
            assertFailure("MODEL_INVALID_CONFIGURATION", false, client(configured()).recognize("100112"));
            assertEquals(0, requests.get());
        } finally {
            CookieHandler.setDefault(previous);
        }
    }

    @Test
    public void mapsHttpFailuresWithoutReturningResponseOrExceptionText() {
        response = "secret-looking-response-and-original-input";
        int[] statuses = {401, 429, 500, 503, 400, 403, 302};
        String[] failures = {"MODEL_HTTP_401", "MODEL_HTTP_429", "MODEL_HTTP_5XX", "MODEL_HTTP_5XX",
                "MODEL_HTTP_4XX", "MODEL_HTTP_4XX", "MODEL_HTTP_3XX"};
        for (int i = 0; i < statuses.length; i++) {
            status = statuses[i];
            assertFailure("status=" + status, failures[i], true, client(configured()).recognize("100112"));
        }
    }

    @Test
    public void preservesHttpFailureStatusForBodylessErrorResponses() {
        bodylessResponse = true;
        int[] statuses = {401, 429, 500};
        String[] failures = {"MODEL_HTTP_401", "MODEL_HTTP_429", "MODEL_HTTP_5XX"};
        for (int i = 0; i < statuses.length; i++) {
            status = statuses[i];
            assertFailure("status=" + status, failures[i], true, client(configured()).recognize("100112"));
        }
        assertEquals(3, requests.get());
    }

    @Test
    public void refusesRedirectsWithoutForwardingAuthorization() {
        status = 302;
        assertFailure("MODEL_HTTP_3XX", true, client(configured()).recognize("100112"));
        assertEquals(1, requests.get());
        assertEquals(0, redirectedRequests.get());
    }

    @Test
    public void mapsReadTimeoutWithoutRetry() {
        responseDelayMs = 1000;
        assertFailure("MODEL_TIMEOUT", true, client(configured().withProperty("MES_LLM_READ_TIMEOUT_MS", "50")).recognize("100112"));
        assertEquals(1, requests.get());
    }

    @Test
    public void mapsDeterministicConnectTimeoutWithoutExposingDetails() {
        SimpleClientHttpRequestFactory timeoutFactory = new SimpleClientHttpRequestFactory() {
            @Override
            protected HttpURLConnection openConnection(URL url, Proxy proxy) throws IOException {
                throw new SocketTimeoutException("secret-and-input-must-not-escape");
            }
        };
        LlmIntentClient client = new LlmIntentClient(new LlmProperties(configured()), mapper, timeoutFactory);
        assertFailure("MODEL_TIMEOUT", true, client.recognize("100112"));
        assertEquals(0, requests.get());
    }

    @Test
    public void mapsConnectionRefusedWithoutRetry() throws Exception {
        HttpServer temporary = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int closedPort = temporary.getAddress().getPort();
        temporary.start();
        temporary.stop(0);
        assertFailure("MODEL_CONNECTION_ERROR", true,
                client(configured().withProperty("MES_LLM_BASE_URL", "http://127.0.0.1:" + closedPort + "/v1")).recognize("100112"));
    }

    @Test
    public void doesNotReplayPostWhenServerDropsResponse() {
        dropResponse = true;
        assertFailure("MODEL_CONNECTION_ERROR", true, client(configured()).recognize("100112"));
        assertEquals(1, requests.get());
    }

    @Test
    public void rejectsMalformedEmptyAndTrailingOuterJson() throws Exception {
        for (String raw : new String[]{"", " ", "{", "null", "[]", envelope(query("100112")) + " {}",
                "{\"choices\":[],\"choices\":[]}", "{\"choices\":/*comment*/[]}"}) {
            response = raw;
            assertFailure("MODEL_INVALID_JSON", true, client(configured()).recognize("100112"));
        }
    }

    @Test
    public void rejectsMalformedInnerJsonAndDuplicateOrTrailingFields() throws Exception {
        for (String content : new String[]{"{", "null", "[]", "```json\n" + query("100112") + "\n```",
                query("100112") + " {}", query("100112").replace("\"reason\":", "\"intent\":\"UNSUPPORTED\",\"reason\":"),
                "{intent:'QUERY_MATERIAL_ROUTING'}"}) {
            response = envelope(content);
            assertFailure("MODEL_INVALID_JSON", true, client(configured()).recognize("100112"));
        }
    }

    @Test
    public void remainsStrictWhenApplicationMapperAllowsNonJsonSyntax() throws Exception {
        ObjectMapper permissive = new ObjectMapper().enable(JsonParser.Feature.ALLOW_COMMENTS)
                .enable(JsonParser.Feature.ALLOW_SINGLE_QUOTES).enable(JsonParser.Feature.ALLOW_UNQUOTED_FIELD_NAMES);
        response = envelope(query("100112").replace("\"reason\":", "\"reason\":/*not JSON*/"));
        assertFailure("MODEL_INVALID_JSON", true,
                new LlmIntentClient(new LlmProperties(configured()), permissive).recognize("100112"));
    }

    @Test
    public void rejectsExcessiveNestingBeforeTreeDeserialization() throws Exception {
        String valid = envelope(query("100112"));
        response = valid.substring(0, valid.length() - 1) + ",\"metadata\":" + repeat("[", 32) + "0" + repeat("]", 32) + "}";
        assertFailure("MODEL_INVALID_JSON", true, client(configured()).recognize("100112"));
    }

    @Test
    public void rejectsUnknownIntent() throws Exception {
        response = envelope("{\"intent\":\"EXECUTE_SQL\",\"materialCode\":null,\"clarificationNeeded\":false,\"reason\":\"no\"}");
        assertFailure("MODEL_UNKNOWN_INTENT", true, client(configured()).recognize("100112"));
    }

    @Test
    public void rejectsMissingExtraAndIncorrectlyTypedOutputFields() throws Exception {
        String valid = query("100112");
        for (String content : new String[]{valid.replace(",\"reason\":\"routing\"", ""),
                valid.replace("\"reason\":", "\"sql\":\"SELECT secret\",\"reason\":"),
                valid.replace("\"intent\":\"QUERY_MATERIAL_ROUTING\"", "\"intent\":7"),
                valid.replace("\"materialCode\":\"100112\"", "\"materialCode\":100112"),
                valid.replace("\"materialCode\":\"100112\"", "\"materialCode\":[]"),
                valid.replace("\"clarificationNeeded\":false", "\"clarificationNeeded\":\"false\""),
                valid.replace("\"clarificationNeeded\":false", "\"clarificationNeeded\":null"),
                valid.replace("\"reason\":\"routing\"", "\"reason\":true"),
                valid.replace("\"reason\":\"routing\"", "\"reason\":\" \""),
                valid.replace("\"reason\":\"routing\"", "\"reason\":\"" + repeat("x", 257) + "\"")}) {
            response = envelope(content);
            assertFailure("MODEL_INVALID_PARAMETERS", true, client(configured()).recognize("100112"));
        }
    }

    @Test
    public void requiresACompleteAsciiMaterialCodeWhenNoClarificationIsNeeded() throws Exception {
        for (String code : new String[]{"", "_100112", "100112@", "A B", "Ａ-1", repeat("A", 256)}) {
            response = envelope(query(code));
            assertFailure("MODEL_INVALID_PARAMETERS", true, client(configured()).recognize("100112"));
        }
        response = envelope("{\"intent\":\"QUERY_MATERIAL_ROUTING\",\"materialCode\":null,\"clarificationNeeded\":false,\"reason\":\"missing\"}");
        assertFailure("MODEL_INVALID_PARAMETERS", true, client(configured()).recognize("100112"));
    }

    @Test
    public void preservesMaximalAndPunctuatedCodesWithoutDatabaseOrOriginalInputValidation() throws Exception {
        for (String code : new String[]{"A_100-1/2.3", repeat("A", 255), "CaseSensitive-1"}) {
            response = envelope(query(code));
            LlmIntentResult result = client(configured()).recognize("a question with no matching code");
            assertTrue(result.isValid());
            assertEquals(code, result.getMaterialCode());
        }
    }

    @Test
    public void acceptsClarificationAndUnsupportedWithoutCodeButRejectsUnsupportedCode() throws Exception {
        response = envelope("{\"intent\":\"QUERY_MATERIAL_ROUTING\",\"materialCode\":null,\"clarificationNeeded\":true,\"reason\":\"missing code\"}");
        LlmIntentResult clarification = client(configured()).recognize("100112");
        assertTrue(clarification.isValid());
        assertTrue(clarification.isClarificationNeeded());
        assertNull(clarification.getMaterialCode());
        response = envelope("{\"intent\":\"UNSUPPORTED\",\"materialCode\":null,\"clarificationNeeded\":false,\"reason\":\"unsupported\"}");
        LlmIntentResult unsupported = client(configured()).recognize("100112");
        assertTrue(unsupported.isValid());
        assertEquals("UNSUPPORTED", unsupported.getIntent());
        assertNull(unsupported.getMaterialCode());
        response = envelope(query("100112").replace("QUERY_MATERIAL_ROUTING", "UNSUPPORTED"));
        assertFailure("MODEL_INVALID_PARAMETERS", true, client(configured()).recognize("100112"));
    }

    @Test
    public void rejectsAmbiguousOrNonAssistantEnvelopeAndNonStopFinishReasons() throws Exception {
        String valid = envelope(query("100112"));
        JsonNode validTree = mapper.readTree(valid);
        String choice = mapper.writeValueAsString(validTree.get("choices").get(0));
        for (String raw : new String[]{"{}", "{\"choices\":[]}", "{\"choices\":{}}",
                "{\"choices\":[" + choice + "," + choice + "]}",
                valid.replace("\"role\":\"assistant\"", "\"role\":\"user\""),
                valid.replace("\"role\":\"assistant\"", "\"role\":null"),
                valid.replace("\"content\":", "\"tool_calls\":[],\"content\":"),
                valid.replace("\"content\":", "\"function_call\":null,\"content\":"),
                valid.replace("\"finish_reason\":\"stop\"", "\"finish_reason\":\"length\""),
                valid.replace("\"finish_reason\":\"stop\"", "\"finish_reason\":null"),
                valid.replace("\"finish_reason\":\"stop\",", "")}) {
            response = raw;
            assertFailure("MODEL_INVALID_JSON", true, client(configured()).recognize("100112"));
        }
        ObjectNode wrongContent = (ObjectNode) mapper.readTree(valid);
        ((ObjectNode) wrongContent.get("choices").get(0).get("message")).put("content", 123);
        response = mapper.writeValueAsString(wrongContent);
        assertFailure("MODEL_INVALID_JSON", true, client(configured()).recognize("100112"));
    }

    @Test
    public void boundsContentLengthBeforeParsingIt() throws Exception {
        response = envelope(repeat("x", 2049));
        assertFailure("MODEL_RESPONSE_TOO_LARGE", true, client(configured()).recognize("100112"));
    }

    @Test
    public void acceptsExactBodyContentAndReasonLimits() throws Exception {
        String content = query("100112").replace("routing", repeat("r", 256));
        response = envelope(content + repeat(" ", 2048 - content.length()));
        assertTrue(client(configured()).recognize("100112").isValid());
        response = envelope(query("100112"));
        response += repeat(" ", 65536 - response.getBytes(StandardCharsets.UTF_8).length);
        assertTrue(client(configured()).recognize("100112").isValid());
    }

    @Test
    public void boundsSuccessAndErrorBodiesWhileReadingAndDoesNotDrainTheRest() {
        response = repeat("x", 65537);
        pauseAfterOversizedChunk = true;
        for (int code : new int[]{200, 401, 500}) {
            status = code;
            long started = System.nanoTime();
            String expected = code == 200 ? "MODEL_RESPONSE_TOO_LARGE" : code == 401 ? "MODEL_HTTP_401" : "MODEL_HTTP_5XX";
            assertFailure("status=" + code, expected, true,
                    client(configured().withProperty("MES_LLM_READ_TIMEOUT_MS", "500")).recognize("100112"));
            long elapsedMs = (System.nanoTime() - started) / 1000000;
            assertTrue("Must stop before the server finishes its unbounded tail; elapsed=" + elapsedMs, elapsedMs < 900);
        }
    }

    @Test
    public void resultFactoriesAreImmutableValueResultsWithoutResponseReason() {
        LlmIntentResult success = LlmIntentResult.success("QUERY_MATERIAL_ROUTING", "100112", false);
        assertTrue(success.isAttempted());
        assertTrue(success.isValid());
        assertNull(success.getFailureReason());
        assertEquals("100112", success.getMaterialCode());
        LlmIntentResult failure = LlmIntentResult.failure("MODEL_TIMEOUT", true);
        assertTrue(failure.isAttempted());
        assertFalse(failure.isValid());
        assertNull(failure.getIntent());
        assertNull(failure.getMaterialCode());
        assertFalse(failure.isClarificationNeeded());
        assertFalse(LlmIntentResult.failure(null, true).isValid());
    }

    private MockEnvironment configured() {
        return new MockEnvironment().withProperty("MES_LLM_BASE_URL", baseUrl)
                .withProperty("MES_LLM_MODEL", "test-model").withProperty("MES_LLM_API_KEY", "test-fake-key");
    }

    private LlmIntentClient client(MockEnvironment environment) {
        return new LlmIntentClient(new LlmProperties(environment), mapper);
    }

    private String query(String code) throws Exception {
        return "{\"intent\":\"QUERY_MATERIAL_ROUTING\",\"materialCode\":" + mapper.writeValueAsString(code)
                + ",\"clarificationNeeded\":false,\"reason\":\"routing\"}";
    }

    private String envelope(String content) throws Exception {
        ObjectNode root = mapper.createObjectNode();
        root.put("id", "test-completion");
        root.put("object", "chat.completion");
        ObjectNode choice = root.putArray("choices").addObject();
        choice.put("index", 0);
        choice.put("finish_reason", "stop");
        choice.putObject("message").put("role", "assistant").put("content", content);
        return mapper.writeValueAsString(root);
    }

    private void respond(HttpExchange exchange) throws IOException {
        requests.incrementAndGet();
        method.set(exchange.getRequestMethod());
        path.set(exchange.getRequestURI().getPath());
        authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
        cookie.set(exchange.getRequestHeaders().getFirst("Cookie"));
        requestBody.set(new String(readAll(exchange.getRequestBody()), StandardCharsets.UTF_8));
        try {
            if (dropResponse) {
                return;
            }
            if (responseDelayMs > 0) {
                Thread.sleep(responseDelayMs);
            }
            if (status == 302) {
                exchange.getResponseHeaders().add("Location", "http://127.0.0.1:" + server.getAddress().getPort() + "/redirect-target");
            }
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            if (bodylessResponse) {
                exchange.sendResponseHeaders(status, -1);
                return;
            }
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, pauseAfterOversizedChunk ? 0 : bytes.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(bytes);
                output.flush();
                if (pauseAfterOversizedChunk) {
                    Thread.sleep(2000);
                    output.write('x');
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } finally {
            exchange.close();
        }
    }

    private static byte[] readAll(InputStream input) throws IOException {
        try (InputStream body = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] bytes = new byte[4096];
            int count;
            while ((count = body.read(bytes)) != -1) {
                output.write(bytes, 0, count);
            }
            return output.toByteArray();
        }
    }

    private static String repeat(String text, int count) {
        return String.join("", Collections.nCopies(count, text));
    }

    private static Set<String> fields(JsonNode node) {
        Set<String> fields = new HashSet<>();
        node.fieldNames().forEachRemaining(fields::add);
        return fields;
    }

    private static Set<String> set(String... fields) {
        return new HashSet<>(Arrays.asList(fields));
    }

    private static void assertFailure(String reason, boolean attempted, LlmIntentResult result) {
        assertFailure(reason, reason, attempted, result);
    }

    private static void assertFailure(String message, String reason, boolean attempted, LlmIntentResult result) {
        assertFalse(message, result.isValid());
        assertEquals(message, attempted, result.isAttempted());
        assertEquals(message, reason, result.getFailureReason());
    }
}
