package com.wangziyang.mes.assistant;

import com.wangziyang.mes.assistant.agent.LlmIntentClient;
import com.wangziyang.mes.assistant.agent.LlmIntentResult;
import com.wangziyang.mes.assistant.agent.MaterialCodeCandidates;
import com.wangziyang.mes.assistant.service.MaterialRoutingService;
import com.wangziyang.mes.assistant.service.MesAssistantService;
import org.junit.Before;
import org.junit.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;

import java.util.Collections;
import java.util.Map;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

public class MesAssistantHybridTest {
    private MaterialRoutingService routing;
    private LlmIntentClient llm;
    private MesAssistantService service;

    @Before
    public void setUp() {
        routing = mock(MaterialRoutingService.class);
        llm = mock(LlmIntentClient.class);
        service = new MesAssistantService(routing, llm);
        when(routing.query(anyString())).thenReturn(AssistantResponses.reply("SUCCESS", "database", "facts-from-java-service"));
    }

    @Test
    public void reliableRulesAndGuardrailsNeverRequestModel() {
        for (String question : new String[]{"100112", "查100112的工艺路线", "帮助", "查工艺路线", "查100112和100113工艺路线",
                "查询100112库存", "删除100112", "执行SQL查100112工艺", "查100112@的工艺路线", "今天的天气", "", null,
                String.join("", Collections.nCopies(1001, "a"))}) {
            Map<String, Object> response = service.chat(question);
            assertEquals(question, "RULE", response.get("recognitionSource"));
            assertEquals(false, response.get("modelAttempted"));
            assertEquals(false, response.get("modelConnected"));
            assertNull(response.get("fallbackReason"));
        }
        verify(routing, times(2)).query("100112");
        verifyNoMoreInteractions(routing);
        verifyZeroInteractions(llm);
    }

    @Test
    public void modelOnlyRecognizesAndFactsAlwaysComeFromExistingTool() {
        String original = "  100112需要经过哪些环节  ";
        when(llm.recognize(original)).thenReturn(LlmIntentResult.success("QUERY_MATERIAL_ROUTING", "100112", false));
        Map<String, Object> response = service.chat(original);
        assertEquals("facts-from-java-service", response.get("answer"));
        assertEquals("MODEL", response.get("recognitionSource"));
        assertEquals(true, response.get("modelAttempted"));
        assertEquals(true, response.get("modelConnected"));
        assertNull(response.get("fallbackReason"));
        verify(routing).query("100112");
        verify(llm).recognize(original);
    }

    @Test
    public void routeWordInsideMaterialCodeDoesNotMakeNonRoutingQuestionReliable() {
        String question = "A-ROUTE-01是什么意思";
        when(llm.recognize(question)).thenReturn(LlmIntentResult.success("UNSUPPORTED", null, false));
        assertEquals("UNSUPPORTED", service.chat(question).get("status"));
        verify(llm).recognize(question);
        verifyZeroInteractions(routing);
    }

    @Test
    public void ordinaryEnglishWordsRemainValidCodesInExplicitChineseQueries() {
        for (String code : new String[]{"GO", "IT", "MAKE", "go", "for", "routing"}) {
            assertEquals(code, "SUCCESS", service.chat("查询" + code + "的工艺路线").get("status"));
            verify(routing).query(code);
            assertEquals(code, "AMBIGUOUS", service.chat("查询100112和" + code + "的工艺路线").get("status"));
        }
        verifyZeroInteractions(llm);
    }

    @Test
    public void grammarPositionsPreserveEnglishCodeWordsAndAmbiguousLists() {
        assertEquals("SUCCESS", service.chat("show material routing for go").get("status"));
        verify(routing).query("go");
        assertEquals("AMBIGUOUS", service.chat("show routing for 100112 and go").get("status"));
        when(llm.recognize("how is made made")).thenReturn(LlmIntentResult.success("QUERY_MATERIAL_ROUTING", "made", false));
        assertEquals("SUCCESS", service.chat("how is made made").get("status"));
        verify(routing).query("made");
        verifyNoMoreInteractions(routing);
    }

    @Test
    public void incompleteEnglishGrammarNeverTreatsPrepositionAsMaterialCode() {
        for (String question : new String[]{"show material routing for", "routing for", "show material routing", "query route for ",
                "show material routing for?", "routing for!", "query route for，", "routing (for)", "routing for 。", "show material routing?"}) {
            assertEquals(question, "NEEDS_INPUT", service.chat(question).get("status"));
        }
        verifyZeroInteractions(routing, llm);
        assertEquals("SUCCESS", service.chat("show routing for for").get("status"));
        verify(routing).query("for");
        assertEquals("SUCCESS", service.chat("show routing for for?").get("status"));
        verify(routing, times(2)).query("for");
    }

    @Test
    public void incompleteGrammarHandlesAllTokenDelimiterPunctuationConservatively() {
        for (char delimiter : "，。；：、！？（）【】“”‘’;:!?()[]\"', \n\t".toCharArray()) {
            assertEquals(String.valueOf(delimiter), "NEEDS_INPUT", service.chat("show material routing for" + delimiter).get("status"));
        }
        assertNotEquals("SUCCESS", service.chat("routing for?啊").get("status"));
        verifyZeroInteractions(routing, llm);
    }

    @Test
    public void invalidControlCharactersAreNeverTrimmedToMakeAValidCode() {
        for (int control = 0; control < 32; control++) {
            if (control >= 9 && control <= 13) continue; // documented ASCII whitespace delimiters
            for (String question : new String[]{"100112" + (char) control, (char) control + "100112",
                    "show material routing for 100112" + (char) control}) {
                assertEquals("control=" + control, "INVALID_INPUT", service.chat(question).get("status"));
            }
        }
        MaterialCodeCandidates raw = new MaterialCodeCandidates("show material routing for 100112\u0000");
        assertFalse(raw.isValid());
        assertFalse(raw.getCodes().contains("100112"));
        verifyZeroInteractions(routing, llm);
        assertEquals("SUCCESS", service.chat("\t100112\n").get("status"));
        verify(routing).query("100112");
    }

    @Test
    public void inventedPartialCaseChangedAndSeparatorChangedCodesAreRejected() {
        String question = "A_100-1/2.3需要经过哪些环节";
        for (String code : new String[]{"100113", "A_100", "a_100-1/2.3", "A-100-1/2.3", "A_100-1-2.3", "A_100-1/2.3@", "A_100-1/2.3 "}) {
            when(llm.recognize(question)).thenReturn(LlmIntentResult.success("QUERY_MATERIAL_ROUTING", code, false));
            assertFallback(service.chat(question), "MODEL_INVALID_PARAMETERS", true);
        }
        verifyZeroInteractions(routing);
    }

    @Test
    public void boundariesCannotTurnInvalidOrMultipleTokensIntoOneCode() {
        for (String question : new String[]{"A_100@需要经过哪些环节", "@100112需要经过哪些环节", "100112😀需要经过哪些环节",
                "100112＠需要经过哪些环节", "100112和100113需要经过哪些环节", "100112，100113需要经过哪些环节",
                "100112/100113需要经过哪些环节", "100112-100113需要经过哪些环节", "100112.需要经过哪些环节"}) {
            // Valid combined tokens may reach recognition, but the model must not select their substring.
            when(llm.recognize(question)).thenReturn(LlmIntentResult.success("QUERY_MATERIAL_ROUTING", "100112", false));
            assertNotEquals(question, "SUCCESS", service.chat(question).get("status"));
        }
        verifyZeroInteractions(routing);
    }

    @Test
    public void englishSynonymPreservesWholeCodeAndRepeatedCodeIsUnambiguous() {
        for (String question : new String[]{"how is A_100-1/2.3 made", "A_100-1/2.3和A_100-1/2.3需要经过哪些环节"}) {
            when(llm.recognize(question)).thenReturn(LlmIntentResult.success("QUERY_MATERIAL_ROUTING", "A_100-1/2.3", false));
            assertEquals("SUCCESS", service.chat(question).get("status"));
        }
        verify(routing, times(2)).query("A_100-1/2.3");
    }

    @Test
    public void noCodeAndAmbiguousReferencesNeverAcceptAnInventedCode() {
        for (String question : new String[]{"它需要经过哪些环节", "帮我查工艺路线", "这两个物料怎么做", "100112或100113怎么做"}) {
            assertNotEquals("SUCCESS", service.chat(question).get("status"));
        }
        verifyZeroInteractions(llm, routing);
    }

    @Test
    public void modelUnsupportedAndClarificationDoNotExecuteAnyTool() {
        String question = "100112是什么意思";
        when(llm.recognize(question)).thenReturn(LlmIntentResult.success("UNSUPPORTED", null, false));
        Map<String, Object> response = service.chat(question);
        assertEquals("UNSUPPORTED", response.get("status"));
        assertEquals("MODEL", response.get("recognitionSource"));
        assertEquals(true, response.get("modelConnected"));
        when(llm.recognize(question)).thenReturn(LlmIntentResult.success("QUERY_MATERIAL_ROUTING", null, true));
        assertEquals("NEEDS_INPUT", service.chat(question).get("status"));
        verifyZeroInteractions(routing);
    }

    @Test
    public void serviceIndependentlyRejectsNonWhitelistedIntentAndMissingParameter() {
        String question = "100112需要经过哪些环节";
        for (LlmIntentResult intent : new LlmIntentResult[]{
                LlmIntentResult.success("EXECUTE_SQL", "100112", false),
                LlmIntentResult.success("QUERY_MATERIAL_ROUTING", null, false),
                LlmIntentResult.success("UNSUPPORTED", "100112", false)}) {
            when(llm.recognize(question)).thenReturn(intent);
            assertEquals("RULE_FALLBACK", service.chat(question).get("recognitionSource"));
            assertEquals(false, service.chat(question).get("modelConnected"));
        }
        verifyZeroInteractions(routing);
    }

    @Test
    public void everyClientFailureHasAccurateAttemptAndReasonAndRulesStillWork() {
        String question = "100112需要经过哪些环节";
        for (String reason : new String[]{"MODEL_NOT_CONFIGURED", "MODEL_INVALID_CONFIGURATION", "MODEL_TIMEOUT", "MODEL_HTTP_401",
                "MODEL_HTTP_429", "MODEL_HTTP_5XX", "MODEL_INVALID_JSON", "MODEL_UNKNOWN_INTENT", "MODEL_INVALID_PARAMETERS",
                "MODEL_RESPONSE_TOO_LARGE", "MODEL_CONNECTION_ERROR"}) {
            boolean attempted = !reason.equals("MODEL_NOT_CONFIGURED") && !reason.equals("MODEL_INVALID_CONFIGURATION");
            when(llm.recognize(question)).thenReturn(LlmIntentResult.failure(reason, attempted));
            assertFallback(service.chat(question), reason, attempted);
            assertEquals("SUCCESS", service.chat("查100112的工艺路线").get("status"));
        }
        verify(routing, times(11)).query("100112");
    }

    @Test
    public void validModelRecognitionMetadataSurvivesDatabaseFailures() {
        String question = "100112需要经过哪些环节";
        when(llm.recognize(question)).thenReturn(LlmIntentResult.success("QUERY_MATERIAL_ROUTING", "100112", false));
        when(routing.query("100112")).thenThrow(new DataAccessResourceFailureException("secret sql/password"));
        Map<String, Object> response = service.chat(question);
        assertEquals("DATA_UNAVAILABLE", response.get("status"));
        assertEquals("MODEL", response.get("recognitionSource"));
        assertEquals(true, response.get("modelConnected"));
        assertFalse(response.toString().contains("secret"));
        doThrow(new QueryTimeoutException("secret")).when(routing).query("100112");
        assertEquals("TIMEOUT", service.chat(question).get("status"));
    }

    private void assertFallback(Map<String, Object> response, String reason, boolean attempted) {
        assertEquals("NEEDS_INPUT", response.get("status"));
        assertEquals("RULE_FALLBACK", response.get("recognitionSource"));
        assertEquals(attempted, response.get("modelAttempted"));
        assertEquals(false, response.get("modelConnected"));
        assertEquals(reason, response.get("fallbackReason"));
    }
}
