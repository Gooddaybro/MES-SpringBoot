package com.wangziyang.mes.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangziyang.mes.assistant.agent.LlmIntentClient;
import com.wangziyang.mes.assistant.agent.LlmIntentResult;
import com.wangziyang.mes.assistant.legacy.LegacyR002Rules;
import com.wangziyang.mes.assistant.service.MaterialRoutingService;
import com.wangziyang.mes.assistant.service.MesAssistantService;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** Offline rules evaluation. Tool is mocked; NO model accuracy or DB latency is claimed. */
public class AssistantIntentEvaluationTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    public void compareFrozenR002RulesAndR003RulesWithoutCredentials() throws Exception {
        List<JsonNode> cases = cases();
        assertEquals(30, cases.size());
        AtomicReference<String> called = new AtomicReference<>();
        MaterialRoutingService tool = mock(MaterialRoutingService.class);
        when(tool.query(anyString())).thenAnswer(invocation -> {
            called.set(invocation.getArgument(0));
            return AssistantResponses.reply("SUCCESS", "database", "mocked-tool-no-facts");
        });
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("datasetSize", cases.size());
        report.put("baselineSource", "781ed2d:MesAssistantService (test-only LegacyR002Rules snapshot)");
        report.put("measurementBoundary", "30 local function calls; mocked tool; no HTTP/DB latency; no credentials consumed");
        report.put("r002Rules", evaluate(cases, new LegacyR002Rules(tool)::chat, called));
        Map<String, Object> current = evaluate(cases, new MesAssistantService(tool)::chat, called);
        report.put("r003RulesModelDisabled", current);
        report.put("hybridRealModel", "NOT_EXECUTED: real credentials and result review required; no model accuracy published");
        assertEquals(0, current.get("wrongToolCalls"));
        assertEquals(0, current.get("modelCalls"));
        Path output = Paths.get("target", "assistant-eval", "rules-evaluation.json");
        Files.createDirectories(output.getParent());
        Files.write(output, mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(report));
        System.out.println("Offline evaluation: " + output.toAbsolutePath());
    }

    @Test
    public void datasetModelMismatchFixturesAreRejectedWithoutCallingTool() throws Exception {
        MaterialRoutingService tool = mock(MaterialRoutingService.class);
        LlmIntentClient client = mock(LlmIntentClient.class);
        MesAssistantService service = new MesAssistantService(tool, client);
        int fixtures = 0;
        for (JsonNode row : cases()) {
            if (!row.has("faultModelCode")) continue;
            fixtures++;
            String question = row.path("question").asText();
            when(client.recognize(question)).thenReturn(LlmIntentResult.success("QUERY_MATERIAL_ROUTING", row.path("faultModelCode").asText(), false));
            Map<String, Object> response = service.chat(question);
            assertFalse(row.path("onFaultToolAllowed").asBoolean());
            assertEquals(row.path("onFaultReason").asText(), response.get("fallbackReason"));
            assertEquals("RULE_FALLBACK", response.get("recognitionSource"));
            assertEquals(false, response.get("modelConnected"));
        }
        assertEquals(2, fixtures);
        verifyZeroInteractions(tool);
    }

    private Map<String, Object> evaluate(List<JsonNode> rows, Function<String, Map<String, Object>> recognizer,
                                         AtomicReference<String> called) {
        int intentCorrect = 0, codeCorrect = 0, codeCases = 0, wrongCalls = 0, clarifications = 0, modelCalls = 0, eligible = 0;
        long nanos = 0;
        List<Map<String, Object>> decisions = new ArrayList<>();
        for (JsonNode row : rows) {
            called.set(null);
            long started = System.nanoTime();
            Map<String, Object> response = recognizer.apply(row.path("question").asText());
            nanos += System.nanoTime() - started;
            String status = String.valueOf(response.get("status"));
            String intent = "SUCCESS".equals(status) ? "QUERY_MATERIAL_ROUTING" :
                    ("NEEDS_INPUT".equals(status) || "AMBIGUOUS".equals(status) ? "NEEDS_CLARIFICATION" : status);
            if (intent.equals(row.path("expectedIntent").asText())) intentCorrect++;
            if (row.path("expectedIntent").asText().equals("QUERY_MATERIAL_ROUTING")) {
                codeCases++;
                if (row.path("expectedMaterialCode").asText().equals(called.get())) codeCorrect++;
            }
            if (called.get() != null && (!row.path("toolAllowed").asBoolean()
                    || !called.get().equals(row.path("expectedMaterialCode").asText()))) wrongCalls++;
            if (intent.equals("NEEDS_CLARIFICATION")) clarifications++;
            if (Boolean.TRUE.equals(response.get("modelAttempted"))) modelCalls++;
            if ("MODEL_NOT_CONFIGURED".equals(response.get("fallbackReason"))) eligible++;
            Map<String, Object> decision = new LinkedHashMap<>();
            decision.put("id", row.path("id").asText());
            decision.put("intent", intent);
            decision.put("toolCode", called.get());
            decision.put("recognitionSource", response.get("recognitionSource"));
            decision.put("fallbackReason", response.get("fallbackReason"));
            decisions.add(decision);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("intentCorrect", intentCorrect);
        result.put("intentAccuracy", intentCorrect / (double) rows.size());
        result.put("codeCorrect", codeCorrect);
        result.put("codeCases", codeCases);
        result.put("codeAccuracy", codeCorrect / (double) codeCases);
        result.put("wrongToolCalls", wrongCalls);
        result.put("clarifications", clarifications);
        result.put("clarificationRate", clarifications / (double) rows.size());
        result.put("modelCalls", modelCalls);
        result.put("modelCallRatio", modelCalls / (double) rows.size());
        result.put("modelEligibleCases", eligible);
        result.put("averageLocalMs", nanos / 1000000.0 / rows.size());
        result.put("decisions", decisions);
        return result;
    }

    private List<JsonNode> cases() throws Exception {
        List<JsonNode> rows = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                getClass().getResourceAsStream("/assistant/intent-eval.jsonl"), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) rows.add(mapper.readTree(line));
        }
        return rows;
    }
}
