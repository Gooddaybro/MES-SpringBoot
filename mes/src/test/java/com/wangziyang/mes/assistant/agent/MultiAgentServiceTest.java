package com.wangziyang.mes.assistant.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.wangziyang.mes.assistant.service.MaterialRoutingService;
import com.wangziyang.mes.manufacturing.MasterDataQueryService;
import com.wangziyang.mes.manufacturing.WorkOrderService;
import com.wangziyang.mes.manufacturing.OperationImpactService;
import com.wangziyang.mes.manufacturing.RoutingComparisonService;
import org.apache.shiro.subject.Subject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.mock.env.MockEnvironment;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class MultiAgentServiceTest {
    private static final String SECRET = "multi-agent-test-only-shared-secret-32";
    private final ObjectMapper mapper = new ObjectMapper();
    private MasterDataQueryService master;
    private MaterialRoutingService routing;
    private WorkOrderService orders;
    private OperationImpactService impact;
    private RoutingComparisonService comparison;
    private Subject subject;
    private HttpServer server;
    private String baseUrl;
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicInteger redirects = new AtomicInteger();
    private byte[] rawBody;
    private String timestamp, nonce, signature, cookie, authorization;
    private int responseStatus = 200;
    private String rawResponse;
    private UnaryOperator<ObjectNode> change = node -> node;

    @Before public void setUp() throws Exception {
        master = mock(MasterDataQueryService.class); routing = mock(MaterialRoutingService.class); orders = mock(WorkOrderService.class);
        impact = mock(OperationImpactService.class); comparison = mock(RoutingComparisonService.class);
        subject = mock(Subject.class); when(subject.isAuthenticated()).thenReturn(true);
        when(subject.getPrincipal()).thenReturn("private-user"); when(subject.isPermitted(anyString())).thenReturn(true);
        when(master.material(any())).thenReturn(fact("真实物料")); when(master.operations(any(), any())).thenReturn(fact("真实工序"));
        when(routing.query(anyString())).thenReturn(fact("真实路线")); when(orders.query(any(), any())).thenReturn(fact("真实工单"));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/api/multi-agent/chat", this::respond);
        server.createContext("/redirect-target", exchange -> { redirects.incrementAndGet(); exchange.sendResponseHeaders(200, -1); exchange.close(); }); server.start();
    }
    @After public void tearDown() { server.stop(0); }

    @Test public void signsNewPathAndForwardsOnlyAuthorizedFactsWithOpaqueSubject() throws Exception {
        Map<String, Object> result = ask(service(true), "综合查询物料MAT-1工单WO-1", "MAT-1", "OP-1", "WO-1");
        assertEquals("SUCCESS", result.get("status")); assertEquals(true, result.get("agentExecuted"));
        JsonNode body = mapper.readTree(rawBody);
        assertEquals(new HashSet<>(Arrays.asList("requestId", "question", "messages", "requestedAgents", "authorizedContext", "providedToolResults")), fields(body));
        assertEquals(4, body.path("requestedAgents").size()); assertEquals(4, body.path("providedToolResults").size());
        assertEquals(0, body.path("messages").size()); assertEquals(0, body.path("authorizedContext").path("scopes").size());
        assertEquals(60, body.path("authorizedContext").path("expiresAt").asLong() - Long.parseLong(timestamp));
        assertEquals(nonce, body.path("requestId").asText()); assertEquals(nonce, UUID.fromString(nonce).toString());
        assertFalse(new String(rawBody, StandardCharsets.UTF_8).contains("private-user")); assertNull(cookie); assertNull(authorization);
        String canonical = "POST\n/api/multi-agent/chat\n" + timestamp + "\n" + nonce + "\n" + hex(MessageDigest.getInstance("SHA-256").digest(rawBody));
        Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        assertEquals(hex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8))), signature);
        assertEquals("真实路线", ((Map<?, ?>)((Map<?, ?>) result.get("businessResults")).get("routing")).get("answer"));
        verify(master).material("MAT-1"); verify(master).operations("OP-1", "MAT-1"); verify(orders).query("WO-1", "MAT-1");
    }

    @Test public void authenticationAndEachDomainPermissionPrecedeEveryQuery() {
        when(subject.isAuthenticated()).thenReturn(false);
        assertEquals("AUTH_REQUIRED", ask(service(true), "综合", "MAT-1", null, null).get("status"));
        verify(subject, never()).isPermitted(anyString()); verifyZeroInteractions(master, routing, orders); assertEquals(0, requests.get());
        when(subject.isAuthenticated()).thenReturn(true); when(subject.isPermitted(anyString())).thenReturn(false);
        assertEquals("FORBIDDEN", ask(service(true), "综合", "MAT-1", null, null).get("status"));
        verifyZeroInteractions(master, routing, orders); assertEquals(0, requests.get());
    }

    @Test public void deniedRoleIsNeverSentToPythonWhileAllowedRoleStillWorks() throws Exception {
        when(subject.isPermitted("mes:assistant:workorder:read")).thenReturn(false);
        Map<String, Object> result = ask(service(true), "工艺路线和工单", "MAT-1", null, "WO-1");
        assertEquals("PARTIAL_SUCCESS", result.get("status")); verifyZeroInteractions(orders);
        JsonNode body = mapper.readTree(rawBody); assertEquals(1, body.path("requestedAgents").size());
        assertEquals("routing", body.path("requestedAgents").get(0).asText());
        assertFalse(new String(rawBody, StandardCharsets.UTF_8).contains("get_work_order"));
        assertEquals("FORBIDDEN", ((Map<?, ?>)((Map<?, ?>)result.get("businessResults")).get("workorder")).get("status"));
    }

    @Test public void missingRoutingIdentifierNeverInventsCodeAndOtherRolesCanList() {
        Map<String, Object> result = ask(service(false), "综合查询", null, null, null);
        assertEquals("NEEDS_INPUT", ((Map<?, ?>)((Map<?, ?>)result.get("businessResults")).get("routing")).get("status"));
        verify(routing, never()).query(anyString()); verify(master).material(null); verify(master).operations(null, null); verify(orders).query(null, null);
    }

    @Test public void chineseIdentifiersAreExtractedWithoutConfusingOrderAndMaterial() {
        ask(service(false), "查询物料MAT-1工艺路线和工单WO-1", null, null, null);
        verify(routing).query("MAT-1"); verify(orders).query("WO-1", "MAT-1");
        reset(master, routing, orders);
        ask(service(false), "查询工单WO-2", null, null, null);
        verify(orders).query("WO-2", null); verifyZeroInteractions(master, routing);
    }

    @Test public void malformedMessageCodeConversationOrConflictingMaterialNeverQueries() {
        MultiAgentService service = service(true);
        for (String question : new String[]{null, " ", String.join("", Collections.nCopies(1001, "x")), "物料\u0000MAT-1"})
            assertEquals("INVALID_INPUT", ask(service, question, "MAT-1", null, null).get("status"));
        assertEquals("INVALID_INPUT", ask(service, "查物料", "MAT@1", null, null).get("status"));
        assertEquals("INVALID_INPUT", service.chat("查物料", "not-uuid", subject, "MAT-1", null, null).get("status"));
        assertEquals("INVALID_INPUT", ask(service, "查询物料MAT-2", "MAT-1", null, null).get("status"));
        assertEquals("INVALID_INPUT", ask(service, "查询工单", null, null, String.join("", Collections.nCopies(65, "W"))).get("status"));
        verifyZeroInteractions(master, routing, orders); assertEquals(0, requests.get());
    }

    @Test public void unknownQuestionReturnsCapabilityInstructionsWithoutQueries() {
        assertEquals("UNSUPPORTED", ask(service(true), "你会什么", null, null, null).get("status"));
        verifyZeroInteractions(master, routing, orders); assertEquals(0, requests.get());
    }

    @Test public void pythonFieldsCannotOverwriteImmutableAuthoritativeFacts() {
        change = node -> { node.putObject("businessResults").putObject("routing").put("answer", "假路线"); node.put("sql", "secret"); return node; };
        Map<String, Object> result = ask(service(true), "查询工艺路线", "MAT-1", null, null);
        Map<?, ?> facts = (Map<?, ?>)result.get("businessResults"); assertEquals("真实路线", ((Map<?, ?>)facts.get("routing")).get("answer"));
        assertFalse(result.containsKey("sql"));
        try { ((Map)facts.get("routing")).put("answer", "mutated"); fail("Facts must be immutable"); }
        catch (UnsupportedOperationException expected) { }
    }

    @Test public void invalidPythonRequestIdRolesToolsFlagsAndBoundsPreserveJavaFactsWithHonestFallback() {
        List<UnaryOperator<ObjectNode>> changes = Arrays.asList(
            n -> { n.put("requestId", UUID.randomUUID().toString()); return n; },
            n -> { n.withArray("executedAgents").add("workorder"); return n; },
            n -> { ((ObjectNode)n.withArray("agentResults").get(0)).withArray("toolCalls").addObject().put("name", "execute_sql").put("status", "SUCCESS").put("elapsedMs", 0); return n; },
            n -> { n.put("modelConnected", true); return n; },
            n -> { n.withArray("handoffs").addObject().put("from", "coordinator").put("to", "routing").put("status", "MADE_UP"); return n; },
            n -> { n.withArray("handoffs").removeAll(); return n; },
            n -> { n.put("answer", String.join("", Collections.nCopies(12001, "a"))); return n; },
            n -> { n.put("elapsedMs", -1); return n; }
        );
        for (UnaryOperator<ObjectNode> edit : changes) {
            change = edit; Map<String, Object> result = ask(service(true), "工艺路线", "MAT-1", null, null);
            assertEquals(false, result.get("agentExecuted")); assertEquals(false, result.get("modelConnected"));
            assertEquals("INVALID_AGENT_RESPONSE", result.get("fallbackReason")); assertTrue(result.get("answer").toString().contains("真实路线"));
        }
    }

    @Test public void nonSuccessDuplicateJsonAndOversizedResponsesNeverEraseFacts() {
        responseStatus = 503;
        assertEquals("AGENT_UNAVAILABLE", ask(service(true), "工艺路线", "MAT-1", null, null).get("fallbackReason"));
        responseStatus = 200; rawResponse = "{\"requestId\":\"x\",\"requestId\":\"y\"}";
        assertEquals("INVALID_AGENT_RESPONSE", ask(service(true), "工艺路线", "MAT-1", null, null).get("fallbackReason"));
        rawResponse = String.join("", Collections.nCopies(65537, "x"));
        assertEquals("INVALID_AGENT_RESPONSE", ask(service(true), "工艺路线", "MAT-1", null, null).get("fallbackReason"));
    }

    @Test public void globalCookieHandlerPreventsHttpRequestAndDisabledBridgeKeepsFacts() {
        CookieHandler original = CookieHandler.getDefault();
        try { CookieHandler.setDefault(new CookieManager()); assertEquals(false, ask(service(true), "工艺路线", "MAT-1", null, null).get("agentExecuted")); }
        finally { CookieHandler.setDefault(original); }
        assertEquals(0, requests.get());
        Map<String, Object> result = ask(service(false), "工艺路线", "MAT-1", null, null);
        assertEquals("PARTIAL_SUCCESS", result.get("status")); assertTrue(result.get("answer").toString().contains("真实路线"));
    }

    @Test public void redirectIsNotFollowedAndOversizedFactContextIsNotTransmitted() {
        responseStatus = 302;
        assertEquals("AGENT_UNAVAILABLE", ask(service(true), "工艺路线", "MAT-1", null, null).get("fallbackReason"));
        assertEquals(0, redirects.get());
        int before = requests.get();
        when(routing.query("MAT-1")).thenReturn(fact(String.join("", Collections.nCopies(65537, "x"))));
        assertEquals("CONTEXT_TOO_LARGE", ask(service(true), "工艺路线", "MAT-1", null, null).get("fallbackReason"));
        assertEquals(before, requests.get());
    }

    private MultiAgentService service(boolean enabled) {
        MockEnvironment env = new MockEnvironment().withProperty("MES_AGENT_ENABLED", String.valueOf(enabled))
            .withProperty("MES_AGENT_BASE_URL", baseUrl).withProperty("MES_AGENT_SHARED_SECRET", SECRET);
        return new MultiAgentService(new PythonAgentProperties(env), mapper, master, routing, orders, impact, comparison);
    }

    @Test public void operationChainSignsTheModeAndActualDependencyIdentity() throws Exception {
        Map<String,Object> facts=new LinkedHashMap<>();
        for(String role:Arrays.asList("material","operation","routing","workorder")) facts.put(role,fact("关联"+role));
        Map<String,Object> resolved=fact("已解析"); resolved.put("businessResults",facts); resolved.put("operationId","actual-op");
        when(impact.query(any(),eq(subject))).thenReturn(resolved);
        Map<String,Object> result=ask(service(true),"查询包含老化测试工序的物料及相关工单",null,null,null);
        assertEquals(true,result.get("agentExecuted"));
        JsonNode body=mapper.readTree(rawBody); assertEquals("operation_chain",body.path("queryMode").asText());
        for(JsonNode supplied:body.path("providedToolResults")) assertEquals("actual-op",supplied.path("parameters").path("operationId").asText());
        verify(impact).query(argThat(q -> "老化测试".equals(q.operationName)),eq(subject));
        verifyZeroInteractions(master,routing,orders);
    }

    @Test public void comparisonAndCrossModulePermissionsAreCheckedBeforeAnyFactIsRead() {
        when(subject.isPermitted("mes:assistant:routing:read")).thenReturn(false);
        assertEquals("FORBIDDEN",ask(service(true),"对比工单 WO-1 的工艺和当前工艺",null,null,null).get("status"));
        assertEquals("FORBIDDEN",ask(service(true),"查询包含老化测试工序的物料及相关工单",null,null,null).get("status"));
        verifyZeroInteractions(master,routing,orders,impact,comparison); assertEquals(0,requests.get());
    }

    @Test public void nameSearchUsesBoundedSearchAndDoesNotPickTheFirstMaterialForRouting() {
        when(master.searchMaterials(any(),isNull())).thenReturn(fact("物料候选"));
        Map<String,Object> result=service(true).chat("查询物料“Servo控制器”",UUID.randomUUID().toString(),subject,null,null,null,Collections.emptyMap());
        assertEquals(true,result.get("agentExecuted"));
        verify(master).searchMaterials(argThat(q -> "Servo控制器".equals(q.materialName) && q.materialCode==null),isNull());
        verifyZeroInteractions(routing,orders,impact,comparison);
    }

    @Test public void aMaterialNameDoesNotTurnAnAssociatedOrderQuestionIntoAnUnfilteredOrderList() {
        when(master.searchMaterials(any(),isNull())).thenReturn(fact("多个物料候选"));
        Map<String,Object> result=ask(service(false),"查询物料“伺服”及其工单和工序",null,null,null);
        Map<?,?> facts=(Map<?,?>)result.get("businessResults");
        assertEquals("NEEDS_INPUT",((Map<?,?>)facts.get("workorder")).get("status"));
        assertEquals("NEEDS_INPUT",((Map<?,?>)facts.get("operation")).get("status"));
        verifyZeroInteractions(orders,routing,impact,comparison);
        verify(master,never()).searchOperations(any());
    }

    @Test public void theSeparateOperationCodeFieldParticipatesInDependencyRouting() {
        Map<String,Object> resolved=fact("没有工序"); resolved.put("status","NO_OPERATION");
        when(impact.query(any(),eq(subject))).thenReturn(resolved);
        ask(service(false),"查询工单",null,"AGE-1",null);
        verify(impact).query(argThat(q -> "AGE-1".equals(q.operationCode)),eq(subject));
        verifyZeroInteractions(orders,master,routing);
    }

    @Test public void anInlineOperationCodeAlsoParticipatesInDependencyRouting() {
        Map<String,Object> resolved=fact("没有工序"); resolved.put("status","NO_OPERATION");
        when(impact.query(any(),eq(subject))).thenReturn(resolved);
        ask(service(false),"查询工序 AGE-1 的工单",null,null,null);
        verify(impact).query(argThat(q -> "AGE-1".equals(q.operationCode)),eq(subject));
        verifyZeroInteractions(orders,master,routing);
    }

    @Test public void comprehensiveSynonymsPreserveIndependentQueriesAndAllOrdersOnlyMeansOrders() {
        for(String word:Arrays.asList("综合","全部","四个业务"))
            ask(service(false),word+"查询物料MAT-1工单WO-1","MAT-1","OP-1","WO-1");
        verifyZeroInteractions(impact);
        verify(master,times(3)).material("MAT-1"); verify(orders,times(3)).query("WO-1","MAT-1");
        reset(master,routing,orders);
        when(orders.search(any(),isNull())).thenReturn(fact("筛选工单"));
        Map<String,Object> result=ask(service(false),"查询全部已创建工单",null,null,null);
        assertEquals(Collections.singleton("workorder"),((Map<?,?>)result.get("businessResults")).keySet());
        verifyZeroInteractions(master,routing);
    }
    private Map<String, Object> ask(MultiAgentService service, String question, String material, String operation, String order) {
        return service.chat(question, UUID.randomUUID().toString(), subject, material, operation, order);
    }
    private static Map<String, Object> fact(String answer) { Map<String, Object> result = new LinkedHashMap<>(); result.put("status", "SUCCESS"); result.put("answer", answer); return result; }
    private void respond(HttpExchange exchange) throws java.io.IOException {
        requests.incrementAndGet();
        try (InputStream input = exchange.getRequestBody(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096]; int n; while ((n = input.read(buffer)) >= 0) output.write(buffer, 0, n); rawBody = output.toByteArray();
        }
        timestamp = exchange.getRequestHeaders().getFirst("X-MES-Timestamp"); nonce = exchange.getRequestHeaders().getFirst("X-MES-Nonce");
        signature = exchange.getRequestHeaders().getFirst("X-MES-Signature"); cookie = exchange.getRequestHeaders().getFirst("Cookie"); authorization = exchange.getRequestHeaders().getFirst("Authorization");
        JsonNode body = mapper.readTree(rawBody); ObjectNode response = mapper.createObjectNode();
        response.put("requestId", body.path("requestId").asText()); response.put("status", "SUCCESS"); response.put("mode", "multi-agent");
        response.put("answer", "Python汇总"); response.put("agentFramework", "LANGGRAPH_MULTI_AGENT"); response.put("agentExecuted", true);
        response.set("executedAgents", body.path("requestedAgents")); response.putArray("agentResults");
        for (JsonNode role : body.path("requestedAgents")) {
            ObjectNode agent = response.withArray("agentResults").addObject(); agent.put("agentId", role.asText()); agent.put("status", "SUCCESS"); agent.put("answer", "查询完成");
            agent.putArray("toolCalls"); agent.putArray("executedNodes").add("query_" + role.asText()); agent.put("modelAttempted", false); agent.put("modelConnected", false); agent.putNull("fallbackReason");
        }
        response.putArray("handoffs");
        for (JsonNode role : body.path("requestedAgents")) {
            response.withArray("handoffs").addObject().put("from", "coordinator").put("to", role.asText()).put("status", "SUCCESS");
            response.withArray("handoffs").addObject().put("from", role.asText()).put("to", "coordinator").put("status", "SUCCESS");
        }
        response.put("modelAttempted", false); response.put("modelConnected", false); response.putNull("fallbackReason"); response.put("elapsedMs", 2);
        byte[] bytes = (rawResponse == null ? mapper.writeValueAsString(change.apply(response)) : rawResponse).getBytes(StandardCharsets.UTF_8);
        if (responseStatus == 302) exchange.getResponseHeaders().set("Location", baseUrl + "/redirect-target");
        exchange.sendResponseHeaders(responseStatus, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
    }
    private static Set<String> fields(JsonNode node) { Set<String> names = new HashSet<>(); node.fieldNames().forEachRemaining(names::add); return names; }
    private static String hex(byte[] bytes) { StringBuilder out = new StringBuilder(); for (byte b : bytes) out.append(String.format("%02x", b & 255)); return out.toString(); }
}
