package com.wangziyang.mes.assistant.agent;

import com.wangziyang.mes.manufacturing.BusinessException;
import org.junit.Test;
import java.util.Collections;
import static com.wangziyang.mes.manufacturing.BusinessQueryTest.params;
import static org.junit.Assert.*;

public class BusinessQuestionTest {
    @Test public void explicitChineseOperationClauseBecomesAReadOnlyDependencyQuery() {
        BusinessQuestion parsed=BusinessQuestion.parse("帮我查询包含老化测试工序的物料，以及目前处于已创建状态的相关生产工单。",Collections.emptyMap());
        assertEquals("operation_chain",parsed.mode); assertEquals("老化测试",parsed.query.operationName);
        assertEquals("CREATED",parsed.query.state);
        assertFalse(parsed.codeQuestion.contains("老化测试"));
    }
    @Test public void quotedMaterialNamesRemainNamesAndNeverBecomeGuessedCodes() {
        BusinessQuestion parsed=BusinessQuestion.parse("查询物料“Servo控制器”",Collections.emptyMap());
        assertEquals("Servo控制器",parsed.query.materialName); assertNull(parsed.query.materialCode);
        assertFalse(parsed.codeQuestion.contains("Servo"));
    }
    @Test public void comparisonUsesTheExistingOrderCodeExtractionInsteadOfInventingOne() {
        BusinessQuestion parsed=BusinessQuestion.parse("对比工单 WO-1 使用的工艺和当前物料工艺的差异",Collections.emptyMap());
        assertEquals("route_compare",parsed.mode); assertTrue(parsed.codeQuestion.contains("WO-1"));
    }
    @Test public void conflictingNaturalAndExplicitConditionsAreRejected() {
        try { BusinessQuestion.parse("查询包含老化测试工序的物料和工单",params("operationName","装配")); fail("Conflict must not be guessed"); }
        catch (BusinessException expected) { assertEquals("INVALID_INPUT",expected.getStatus()); }
        try { BusinessQuestion.parse("查询已创建状态的工单",params("state","COMPLETED")); fail("State conflict must fail"); }
        catch (BusinessException expected) { assertEquals("INVALID_INPUT",expected.getStatus()); }
    }

    @Test public void unsupportedNegativeTimeAndStateConstraintsAskInsteadOfQueryingTheOppositeSet() {
        for(String question:new String[]{"查询不包含老化测试工序的物料和工单","查询去年已创建的工单","查询最近三天的工单","查询未完成工单","查询已下达工单",
                "查询不是已完成的工单","查询不处于已完成状态的工单","查询没有包含老化测试工序的工单","查询上个月已创建的工单","查询伺服控制器物料"}) {
            try { BusinessQuestion.parse(question,Collections.emptyMap()); fail("A query constraint was lost: "+question); }
            catch(BusinessException expected) { assertEquals("NEEDS_INPUT",expected.getStatus()); }
        }
        assertEquals("去年已创建控制器",BusinessQuestion.parse("查询物料“去年已创建控制器”",Collections.emptyMap()).query.materialName);
    }

    @Test public void anExplicitOperationFilterCanDriveAnAssociationWithoutRepeatingTheNameInTheQuestion() {
        assertEquals("operation_chain",BusinessQuestion.parse("查询关联物料和工单",params("operationName","老化测试")).mode);
        assertEquals("2",BusinessQuestion.parse("查询禁用物料",Collections.emptyMap()).query.materialStatus);
        assertEquals("CREATED",BusinessQuestion.parse("查询状态为CREATED的工单",Collections.emptyMap()).query.state);
    }
}
