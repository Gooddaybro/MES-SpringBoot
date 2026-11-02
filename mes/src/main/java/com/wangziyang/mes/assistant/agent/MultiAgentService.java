package com.wangziyang.mes.assistant.agent;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wangziyang.mes.assistant.AssistantResponses;
import com.wangziyang.mes.assistant.service.MaterialRoutingService;
import com.wangziyang.mes.manufacturing.BusinessException;
import com.wangziyang.mes.manufacturing.MasterDataQueryService;
import com.wangziyang.mes.manufacturing.WorkOrderService;
import com.wangziyang.mes.system.entity.SysUser;
import org.apache.shiro.subject.Subject;
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
import java.net.CookieHandler;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Java supplies authorized facts; Python only coordinates and explains those facts. */
@Service
public class MultiAgentService {
    private static final String PATH = "/api/multi-agent/chat";
    private static final int MAX_BYTES = 65536;
    private static final List<String> ROLES = Arrays.asList("material", "operation", "routing", "workorder");
    private static final List<String> TOOLS = Arrays.asList("get_material", "get_operations", "get_material_routing", "get_work_order");
    private static final Set<String> STATUSES = set("SUCCESS", "PARTIAL_SUCCESS", "NEEDS_INPUT", "TOOL_ERROR", "UNSUPPORTED", "TIMEOUT");
    private final PythonAgentProperties properties;
    private final ObjectMapper mapper;
    private final MasterDataQueryService master;
    private final MaterialRoutingService routing;
    private final WorkOrderService orders;
    private final RestTemplate http;
    private final String subjectSalt = UUID.randomUUID().toString();
    private final JsonFactory strictJson = new JsonFactory().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

    public MultiAgentService(PythonAgentProperties properties, ObjectMapper mapper, MasterDataQueryService master,
                             MaterialRoutingService routing, WorkOrderService orders) {
        this.properties = properties; this.mapper = mapper.copy(); this.master = master; this.routing = routing; this.orders = orders;
        http = new RestTemplate(requestFactory(properties));
        http.setErrorHandler(new ResponseErrorHandler() {
            @Override public boolean hasError(ClientHttpResponse response) { return false; }
            @Override public void handleError(ClientHttpResponse response) { }
        });
    }

    public Map<String, Object> chat(String question, String conversationId, Subject subject,
                                   String materialCode, String operationCode, String workOrderCode) {
        if (subject == null || !subject.isAuthenticated()) return response("AUTH_REQUIRED", "请先登录后使用助手。");
        if (question == null || question.trim().isEmpty() || question.length() > 1000 || hasControl(question)
                || !PythonAgentService.validConversationId(conversationId))
            return response("INVALID_INPUT", "请提供1至1000字的问题和有效会话标识。");
        List<String> requested = requestedRoles(question);
        if (requested.isEmpty()) return response("UNSUPPORTED", "多 Agent 支持查询物料、工序、工艺路线、工单；输入“综合查询”可查询四项。可在补充条件中填写各业务编码，工单写入请使用业务工作台。");
        Inputs input;
        try { input = inputs(question, requested, materialCode, operationCode, workOrderCode); }
        catch (IllegalArgumentException invalid) { return response("INVALID_INPUT", "请使用完整有效的编码，每类业务只提供一个编码，问题中的编码须与补充条件一致。"); }

        List<String> allowed = new ArrayList<>();
        Map<String, Object> facts = new LinkedHashMap<>();
        // Resolve every permission before starting any database query.
        for (String role : requested) {
            if (subject.isPermitted("mes:assistant:" + role + ":read")) allowed.add(role);
            else facts.put(role, business("FORBIDDEN", "当前账号没有此业务的查询权限。"));
        }
        for (String role : allowed) {
            Map<String, Object> result;
            try {
                switch (role) {
                    case "material": result = master.material(input.material); break;
                    case "operation": result = master.operations(input.operation, input.material); break;
                    case "routing": result = input.material == null ? business("NEEDS_INPUT", "查询工艺路线需要物料编码，请在问题或补充条件中填写。") : routing.query(input.material); break;
                    case "workorder": result = orders.query(input.order, input.material); break;
                    default: throw new IllegalStateException();
                }
                if (result == null) result = business("TOOL_ERROR", "业务查询未返回可靠结果。");
            } catch (BusinessException invalid) { result = business(invalid.getStatus(), invalid.getMessage()); }
            catch (Exception failed) { result = AssistantResponses.queryFailure(failed); }
            facts.put(role, freeze(result));
        }
        Map<String, Object> immutableFacts = immutableMap(facts);
        String requestId = UUID.randomUUID().toString();
        if (allowed.isEmpty()) return fallback(requestId, requested, immutableFacts, "FORBIDDEN", null);
        if (!properties.isEnabled() || !properties.isValid() || CookieHandler.getDefault() != null)
            return fallback(requestId, requested, immutableFacts, null, "AGENT_UNAVAILABLE");
        try {
            List<String> tools = new ArrayList<>(); for (String role : allowed) tools.add(tool(role));
            long now = Instant.now().getEpochSecond();
            ObjectNode body = mapper.createObjectNode(); body.put("requestId", requestId); body.put("question", question); body.putArray("messages");
            body.set("requestedAgents", mapper.valueToTree(allowed));
            ObjectNode acl = body.putObject("authorizedContext"); acl.put("subject", opaqueSubject(subject)); acl.putArray("scopes");
            acl.set("allowedTools", mapper.valueToTree(tools)); acl.put("issuedAt", now); acl.put("expiresAt", now + 60);
            body.putArray("providedToolResults");
            for (String role : allowed) {
                ObjectNode supplied = body.withArray("providedToolResults").addObject(); supplied.put("tool", tool(role));
                supplied.set("parameters", mapper.valueToTree(parameters(role, input))); supplied.set("result", mapper.valueToTree(modelFacts(immutableFacts.get(role))));
            }
            byte[] bytes = mapper.writeValueAsBytes(body);
            if (bytes.length > MAX_BYTES) return fallback(requestId, requested, immutableFacts, null, "CONTEXT_TOO_LARGE");
            String timestamp = Long.toString(now), signature = signature(bytes, timestamp, requestId);
            URI endpoint = URI.create(properties.getEndpoint()).resolve(PATH);
            Map<String, Object> result = http.execute(endpoint, HttpMethod.POST, request -> {
                request.getHeaders().setContentType(MediaType.APPLICATION_JSON); request.getHeaders().setContentLength(bytes.length);
                request.getHeaders().set("X-MES-Timestamp", timestamp); request.getHeaders().set("X-MES-Nonce", requestId); request.getHeaders().set("X-MES-Signature", signature);
                request.getBody().write(bytes);
            }, response -> extract(response, requestId, allowed));
            result.put("businessResults", immutableFacts);
            if (allowed.size() != requested.size()) {
                result.put("status", "PARTIAL_SUCCESS");
                List<Map<String, Object>> traces = (List<Map<String, Object>>)result.get("agentResults");
                for (String role : requested) if (!allowed.contains(role)) traces.add(trace(role, (Map<?, ?>)immutableFacts.get(role)));
                result.put("answer", result.get("answer") + "\n部分业务无查询权限，请查看各业务状态。");
            }
            return result;
        } catch (ResourceAccessException transport) {
            return fallback(requestId, requested, immutableFacts, null, hasCause(transport, InvalidResponse.class) ? "INVALID_AGENT_RESPONSE" : hasCause(transport, SocketTimeoutException.class) ? "TIMEOUT" : "AGENT_UNAVAILABLE");
        }
        catch (IOException | GeneralSecurityException | RestClientException | IllegalArgumentException unavailable) {
            return fallback(requestId, requested, immutableFacts, null, "AGENT_UNAVAILABLE");
        }
    }

    private Map<String, Object> extract(ClientHttpResponse response, String requestId, List<String> requested) throws IOException {
        if (response.getRawStatusCode() < 200 || response.getRawStatusCode() >= 300) { response.getBody().close(); throw new IOException("Unavailable multi agent"); }
        try {
            JsonNode root = readStrict(readBounded(response.getBody()));
            require(requestId.equals(text(root, "requestId", 36)) && STATUSES.contains(text(root, "status", 32)));
            require("multi-agent".equals(text(root, "mode", 16)) && "LANGGRAPH_MULTI_AGENT".equals(text(root, "agentFramework", 32)));
            text(root, "answer", 12000); nonnegative(root, "elapsedMs");
            boolean executed = bool(root, "agentExecuted"), attempted = bool(root, "modelAttempted"), connected = bool(root, "modelConnected");
            reason(root); require(!connected || attempted);
            if (root.has("llmConfigured")) require(bool(root, "llmConfigured") || !attempted && !connected);
            Set<String> executedRoles = new LinkedHashSet<>();
            for (JsonNode role : array(root, "executedAgents", 4)) require(role.isTextual() && requested.contains(role.textValue()) && executedRoles.add(role.textValue()));
            require(executed == !executedRoles.isEmpty());
            List<Map<String, Object>> traces = new ArrayList<>(); Set<String> seen = new HashSet<>(); boolean anyAttempt = false, anyConnected = false;
            for (JsonNode agent : array(root, "agentResults", 4)) {
                String role = text(agent, "agentId", 16); require(requested.contains(role) && seen.add(role) && executedRoles.contains(role));
                require(STATUSES.contains(text(agent, "status", 32))); text(agent, "answer", 4000);
                boolean workerAttempt = bool(agent, "modelAttempted"), workerConnected = bool(agent, "modelConnected"); reason(agent);
                require(!workerConnected || workerAttempt); anyAttempt |= workerAttempt; anyConnected |= workerConnected;
                List<Map<String, Object>> calls = new ArrayList<>(); Set<String> called = new HashSet<>();
                for (JsonNode call : array(agent, "toolCalls", 1)) {
                    String name = text(call, "name", 32); require(tool(role).equals(name) && called.add(name));
                    require(set("SUCCESS", "NO_RESULTS", "DENIED", "ERROR").contains(text(call, "status", 16))); nonnegative(call, "elapsedMs");
                    calls.add(select(call, "name", "status", "elapsedMs"));
                }
                for (JsonNode node : array(agent, "executedNodes", 3)) require(node.isTextual() && node.textValue().matches("[A-Za-z_][A-Za-z0-9_]{0,63}"));
                Map<String, Object> safe = select(agent, "agentId", "status", "answer", "executedNodes", "modelAttempted", "modelConnected", "fallbackReason"); safe.put("toolCalls", calls); traces.add(safe);
            }
            require(seen.equals(executedRoles) && attempted == anyAttempt && connected == anyConnected);
            List<Map<String, Object>> handoffs = new ArrayList<>();
            Set<String> dispatched = new HashSet<>(), returned = new HashSet<>();
            for (JsonNode handoff : array(root, "handoffs", 8)) {
                String from = text(handoff, "from", 16), to = text(handoff, "to", 16);
                require(STATUSES.contains(text(handoff, "status", 32)));
                if ("coordinator".equals(from)) require(executedRoles.contains(to) && dispatched.add(to));
                else require("coordinator".equals(to) && executedRoles.contains(from) && returned.add(from));
                handoffs.add(select(handoff, "from", "to", "status"));
            }
            require(dispatched.equals(executedRoles) && returned.equals(executedRoles));
            Map<String, Object> result = select(root, "requestId", "status", "mode", "answer", "agentFramework", "agentExecuted", "executedAgents", "modelAttempted", "modelConnected", "fallbackReason", "elapsedMs");
            if (root.has("llmConfigured")) result.put("llmConfigured", root.get("llmConfigured").booleanValue());
            result.put("agentResults", traces); result.put("handoffs", handoffs); result.put("recognitionSource", connected ? "MODEL" : attempted ? "RULE_FALLBACK" : "RULE"); return result;
        } catch (IOException | IllegalArgumentException malformed) { throw new InvalidResponse(); }
    }

    private Map<String, Object> fallback(String requestId, List<String> requested, Map<String, Object> facts, String forcedStatus, String reason) {
        List<String> answers = new ArrayList<>(); List<Map<String, Object>> traces = new ArrayList<>(); boolean useful = false; boolean needs = false;
        for (String role : requested) {
            Map<?, ?> fact = (Map<?, ?>)facts.get(role); answers.add(role + "：" + fact.get("answer")); traces.add(trace(role, fact));
            String status = String.valueOf(fact.get("status")); useful |= "SUCCESS".equals(status) || status.startsWith("NO_"); needs |= "NEEDS_INPUT".equals(status);
        }
        String status = forcedStatus != null ? forcedStatus : useful ? "PARTIAL_SUCCESS" : needs ? "NEEDS_INPUT" : "TOOL_ERROR";
        Map<String, Object> result = response(status, String.join("\n", answers)); result.put("requestId", requestId); result.put("fallbackReason", reason);
        result.put("agentResults", traces); result.put("businessResults", facts); return result;
    }
    private static Map<String, Object> trace(String role, Map<?, ?> fact) {
        Map<String, Object> result = new LinkedHashMap<>(); result.put("agentId", role); result.put("status", fact.get("status")); result.put("answer", fact.get("answer"));
        result.put("toolCalls", Collections.emptyList()); result.put("executedNodes", Collections.emptyList()); result.put("modelAttempted", false); result.put("modelConnected", false); result.put("fallbackReason", null); return result;
    }
    public static Map<String, Object> response(String status, String answer) {
        Map<String, Object> result = AssistantResponses.reply(status, "multi-agent", answer); result.put("agentFramework", "LANGGRAPH_MULTI_AGENT"); result.put("agentExecuted", false);
        result.put("executedAgents", Collections.emptyList()); result.put("agentResults", Collections.emptyList()); result.put("handoffs", Collections.emptyList()); result.put("businessResults", Collections.emptyMap()); return result;
    }
    private static Map<String, Object> business(String status, String answer) { Map<String, Object> value = new LinkedHashMap<>(); value.put("status", status); value.put("answer", answer); return value; }
    private static List<String> requestedRoles(String question) {
        String value = question.toLowerCase(Locale.ROOT); if (value.contains("综合") || value.contains("全部") || value.contains("四个业务")) return new ArrayList<>(ROLES);
        List<String> result = new ArrayList<>();
        if (value.contains("物料") || value.contains("material")) result.add("material");
        if (value.contains("工序") || value.contains("operation")) result.add("operation");
        if (value.contains("工艺路线") || value.contains("路线") || value.contains("routing")) result.add("routing");
        if (value.contains("工单") || value.contains("workorder") || value.contains("work order")) result.add("workorder"); return result;
    }
    private static Inputs inputs(String question, List<String> roles, String material, String operation, String order) {
        Set<String> materialCodes = labeled(question, "物料(?:编码)?|material(?:\\s+code)?");
        Set<String> operationCodes = labeled(question, "工序(?:编码)?|operation(?:\\s+code)?");
        Set<String> orderCodes = labeled(question, "工单(?:编号|编码)?|work\\s*order(?:\\s+code)?");
        material = resolve(material, materialCodes); operation = resolve(operation, operationCodes); order = resolve(order, orderCodes);
        String codeRegion = question.replaceAll("(?i)\\b(?:query|show|list|check|get|find|all|the|and|for|material|code|operations?|routing|route|workorder|work|order)\\b", " ");
        MaterialCodeCandidates candidates = new MaterialCodeCandidates(codeRegion); if (!candidates.isValid()) throw new IllegalArgumentException();
        Set<String> remaining = new LinkedHashSet<>(candidates.getCodes()); remaining.removeAll(materialCodes); remaining.removeAll(operationCodes); remaining.removeAll(orderCodes);
        if (!remaining.isEmpty()) {
            if (roles.contains("material") || roles.contains("routing")) material = resolve(material, remaining);
            else if (roles.size() == 1 && roles.contains("operation")) operation = resolve(operation, remaining);
            else if (roles.size() == 1 && roles.contains("workorder")) order = resolve(order, remaining);
            else throw new IllegalArgumentException();
        }
        if (order != null && order.length() > 64) throw new IllegalArgumentException();
        return new Inputs(material, operation, order);
    }
    private static Set<String> labeled(String question, String label) {
        Matcher matcher = Pattern.compile("(?i)(?:" + label + ")\\s*[:：]?\\s*([A-Za-z0-9][A-Za-z0-9._/-]*)(?=$|[\\s\\p{IsHan}，。；：、！？,;:!?])").matcher(question);
        Set<String> result = new LinkedHashSet<>(); while (matcher.find()) result.add(matcher.group(1)); return result;
    }
    private static String resolve(String supplied, Set<String> found) {
        if (supplied != null && !AssistantResponses.validMaterialCode(supplied) || found.size() > 1) throw new IllegalArgumentException();
        if (!found.isEmpty()) { String parsed = found.iterator().next(); if (!AssistantResponses.validMaterialCode(parsed) || supplied != null && !supplied.equals(parsed)) throw new IllegalArgumentException(); return parsed; }
        return supplied;
    }
    private static Map<String, String> parameters(String role, Inputs input) {
        Map<String, String> result = new LinkedHashMap<>(); if (input.material != null) result.put("materialCode", input.material);
        if ("operation".equals(role) && input.operation != null) result.put("operationCode", input.operation);
        if ("workorder".equals(role) && input.order != null) result.put("workOrderCode", input.order); return result;
    }
    private String opaqueSubject(Subject subject) throws GeneralSecurityException {
        Object principal = subject.getPrincipal(); if (principal instanceof SysUser) { SysUser user = (SysUser)principal; principal = user.getId() == null ? user.getUsername() : user.getId(); }
        return hex(MessageDigest.getInstance("SHA-256").digest((subjectSalt + ":" + String.valueOf(principal)).getBytes(StandardCharsets.UTF_8)));
    }
    private static Object freeze(Object value) {
        if (value instanceof Map) { Map<String, Object> result = new LinkedHashMap<>(); ((Map<?, ?>)value).forEach((key, item) -> result.put(String.valueOf(key), freeze(item))); return Collections.unmodifiableMap(result); }
        if (value instanceof List) { List<Object> result = new ArrayList<>(); for (Object item : (List<?>)value) result.add(freeze(item)); return Collections.unmodifiableList(result); }
        return value;
    }
    private static Map<String, Object> immutableMap(Map<String, Object> value) { return (Map<String, Object>)freeze(value); }
    private static Object modelFacts(Object value) {
        if (value instanceof Map) {
            Map<String, Object> result = new LinkedHashMap<>();
            ((Map<?, ?>)value).forEach((key, item) -> { String name = String.valueOf(key); if (!name.toLowerCase(Locale.ROOT).matches(".*(?:password|credential|secret|token|cookie|authorization).*")) result.put(name, modelFacts(item)); }); return result;
        }
        if (value instanceof List) { List<Object> result = new ArrayList<>(); for (Object item : (List<?>)value) result.add(modelFacts(item)); return result; } return value;
    }
    private Map<String, Object> select(JsonNode node, String... fields) { Map<String, Object> result = new LinkedHashMap<>(); for (String field : fields) result.put(field, mapper.convertValue(node.path(field), Object.class)); return result; }
    private static String tool(String role) { return TOOLS.get(ROLES.indexOf(role)); }
    private static boolean hasControl(String value) { for (int i = 0; i < value.length(); i++) if (Character.isISOControl(value.charAt(i)) && !Character.isWhitespace(value.charAt(i))) return true; return false; }
    private static String text(JsonNode node, String field, int max) throws IOException { JsonNode value = node.path(field); require(value.isTextual() && !value.textValue().trim().isEmpty() && value.textValue().length() <= max && !hasControl(value.textValue())); return value.textValue(); }
    private static boolean bool(JsonNode node, String field) throws IOException { require(node.path(field).isBoolean()); return node.path(field).booleanValue(); }
    private static JsonNode array(JsonNode node, String field, int max) throws IOException { JsonNode value = node.path(field); require(value.isArray() && value.size() <= max); return value; }
    private static void nonnegative(JsonNode node, String field) throws IOException { JsonNode value = node.path(field); require(value.isIntegralNumber() && value.canConvertToLong() && value.longValue() >= 0 && value.longValue() <= 600000); }
    private static void reason(JsonNode node) throws IOException { require(node.has("fallbackReason")); if (!node.path("fallbackReason").isNull()) require(text(node, "fallbackReason", 64).matches("[A-Z0-9_]+")); }
    private static void require(boolean condition) throws IOException { if (!condition) throw new IOException("Invalid multi agent response"); }
    private JsonNode readStrict(byte[] bytes) throws IOException {
        try (JsonParser parser = strictJson.createParser(bytes)) { int depth = 0; JsonToken token; while ((token = parser.nextToken()) != null) { if ((token == JsonToken.START_ARRAY || token == JsonToken.START_OBJECT) && ++depth > 16) throw new IOException(); if (token == JsonToken.END_ARRAY || token == JsonToken.END_OBJECT) depth--; } }
        try (JsonParser parser = strictJson.createParser(bytes)) { JsonNode root = mapper.readTree(parser); require(root != null && root.isObject() && parser.nextToken() == null); return root; }
    }
    private static byte[] readBounded(InputStream input) throws IOException {
        try (InputStream body = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (body == null) return new byte[0]; byte[] buffer = new byte[4096]; int count;
            while ((count = body.read(buffer, 0, Math.min(buffer.length, MAX_BYTES + 1 - output.size()))) != -1) { output.write(buffer, 0, count); if (output.size() > MAX_BYTES) throw new IOException(); } return output.toByteArray();
        }
    }
    private String signature(byte[] bytes, String timestamp, String nonce) throws GeneralSecurityException {
        String canonical = "POST\n" + PATH + "\n" + timestamp + "\n" + nonce + "\n" + hex(MessageDigest.getInstance("SHA-256").digest(bytes));
        Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(properties.getSharedSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256")); return hex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
    }
    private static String hex(byte[] bytes) { char[] digits = "0123456789abcdef".toCharArray(); StringBuilder result = new StringBuilder(bytes.length * 2); for (byte value : bytes) result.append(digits[(value & 255) >>> 4]).append(digits[value & 15]); return result.toString(); }
    private static SimpleClientHttpRequestFactory requestFactory(PythonAgentProperties properties) {
        SimpleClientHttpRequestFactory result = new SimpleClientHttpRequestFactory() {
            @Override protected HttpURLConnection openConnection(URL url, Proxy proxy) throws IOException {
                if (CookieHandler.getDefault() != null) throw new IOException("Unsafe cookie handler"); HttpURLConnection connection = super.openConnection(url, proxy);
                if (CookieHandler.getDefault() != null) { connection.disconnect(); throw new IOException("Unsafe cookie handler"); } return connection;
            }
            @Override protected void prepareConnection(HttpURLConnection connection, String method) throws IOException { super.prepareConnection(connection, method); connection.setInstanceFollowRedirects(false); }
        };
        result.setProxy(Proxy.NO_PROXY); result.setConnectTimeout(properties.getConnectTimeoutMs() > 0 ? properties.getConnectTimeoutMs() : 3000); result.setReadTimeout(properties.getReadTimeoutMs() > 0 ? properties.getReadTimeoutMs() : 35000); return result;
    }
    private static boolean hasCause(Throwable failure, Class<? extends Throwable> type) { for (Throwable cause = failure; cause != null; cause = cause.getCause()) if (type.isInstance(cause)) return true; return false; }
    private static Set<String> set(String... values) { return new HashSet<>(Arrays.asList(values)); }
    private static final class Inputs { private final String material, operation, order; private Inputs(String material, String operation, String order) { this.material = material; this.operation = operation; this.order = order; } }
    private static final class InvalidResponse extends IOException { private static final long serialVersionUID = 1L; }
}
