package com.wangziyang.mes.assistant;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.wangziyang.mes.assistant.service.MaterialRoutingService;
import com.wangziyang.mes.basedata.entity.SpMaterile;
import com.wangziyang.mes.basedata.service.ISpMaterileService;
import com.wangziyang.mes.technology.entity.SpFlow;
import com.wangziyang.mes.technology.entity.SpFlowOperRelation;
import com.wangziyang.mes.technology.entity.SpOper;
import com.wangziyang.mes.technology.service.ISpFlowService;
import com.wangziyang.mes.technology.service.ISpFlowOperRelationService;
import com.wangziyang.mes.technology.service.ISpOperService;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

public class MaterialRoutingServiceTest {
    private ISpMaterileService materials;
    private ISpFlowService flows;
    private ISpFlowOperRelationService relations;
    private ISpOperService operations;
    private MaterialRoutingService service;

    @Before
    public void setUp() {
        materials = mock(ISpMaterileService.class);
        flows = mock(ISpFlowService.class);
        relations = mock(ISpFlowOperRelationService.class);
        operations = mock(ISpOperService.class);
        service = new MaterialRoutingService(materials, flows, relations, operations);
        when(materials.list(any())).thenReturn(Collections.singletonList(material("m1", "f1")));
        SpFlow flow = new SpFlow();
        flow.setId("f1");
        flow.setFlow("ROUTE-1");
        flow.setFlowDesc("实有工艺路线");
        when(flows.getById("f1")).thenReturn(flow);
        when(relations.list(any())).thenReturn(Arrays.asList(relation("r2", "o2", 20), relation("r1", "o1", 10)));
        when(operations.listByIds(any())).thenReturn(Arrays.asList(operation("o1", "OP-1", "贴片"), operation("o2", "OP-2", "功能测试")));
    }

    @Test
    public void returnsDatabaseEvidenceAndOrderedActualOperationDetails() {
        Map<String, Object> result = service.query("100112");
        assertEquals("SUCCESS", result.get("status"));
        assertEquals("database", result.get("mode"));
        assertEquals("get_material_routing", result.get("tool"));
        assertEquals("100112", map(result.get("material")).get("code"));
        assertEquals("0", map(result.get("material")).get("status"));
        assertEquals("m1", map(result.get("material")).get("id"));
        assertEquals("ROUTE-1", map(result.get("routing")).get("code"));
        List<?> steps = (List<?>) result.get("steps");
        assertEquals(2, steps.size());
        assertEquals("r1", map(steps.get(0)).get("relationId"));
        assertEquals("o1", map(steps.get(0)).get("operationId"));
        assertEquals(10, map(steps.get(0)).get("order"));
        assertEquals("OP-1", map(steps.get(0)).get("code"));
        assertEquals("功能测试", map(steps.get(1)).get("name"));
        assertEquals("sp_materile → sp_flow → sp_flow_oper_relation → sp_oper", result.get("source"));
        assertNotNull(Instant.parse((String) result.get("queriedAt")));
        verify(operations).listByIds(any());
        verify(operations, never()).getById(any());
    }

    @Test
    @SuppressWarnings("unchecked")
    public void exactQueryFiltersNormalZeroStatusAndDetectsDuplicatesWithoutLoadingAllMaterials() {
        service.query("100112");
        ArgumentCaptor<QueryWrapper> capture = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(materials).list(capture.capture());
        QueryWrapper query = capture.getValue();
        String sql = query.getSqlSegment();
        assertTrue(sql.contains("materiel ="));
        assertTrue(sql.contains("is_deleted ="));
        assertTrue(sql.toUpperCase().contains("LIMIT 2"));
        assertTrue(query.getParamNameValuePairs().containsValue("100112"));
        assertTrue(query.getParamNameValuePairs().containsValue("0"));
        verify(relations).list(capture.capture());
        assertTrue(capture.getValue().getSqlSegment().contains("flow_id ="));
        assertTrue(capture.getValue().getSqlSegment().contains("ORDER BY sort_num ASC"));
    }

    @Test
    public void missingMaterialReturnsNoMaterialInsteadOfInventingRouting() {
        when(materials.list(any())).thenReturn(Collections.emptyList());
        Map<String, Object> result = service.query("100112");
        assertEquals("NO_MATERIAL", result.get("status"));
        assertFalse(result.containsKey("material"));
        assertFalse(result.containsKey("routing"));
        assertTrue(result.containsKey("answer"));
        assertTrue(result.get("answer").toString().contains("正常"));
        verifyZeroInteractions(flows, relations, operations);
    }

    @Test
    public void duplicateNormalMaterialRequiresClarification() {
        when(materials.list(any())).thenReturn(Arrays.asList(material("m1", "f1"), material("m2", "f2")));
        assertEquals("AMBIGUOUS", service.query("100112").get("status"));
        verifyZeroInteractions(flows, relations, operations);
    }

    @Test
    public void missingRouteLinkIsNoRoute() {
        when(materials.list(any())).thenReturn(Collections.singletonList(material("m1", " ")));
        Map<String, Object> result = service.query("100112");
        assertEquals("NO_ROUTE", result.get("status"));
        assertTrue(result.containsKey("material"));
        assertFalse(result.containsKey("routing"));
        verifyZeroInteractions(flows, relations, operations);
    }

    @Test
    public void nonexistentRouteIsNoRoute() {
        when(flows.getById("f1")).thenReturn(null);
        assertEquals("NO_ROUTE", service.query("100112").get("status"));
        verifyZeroInteractions(relations, operations);
    }

    @Test
    public void emptyRelationsIsNoSteps() {
        when(relations.list(any())).thenReturn(Collections.emptyList());
        Map<String, Object> result = service.query("100112");
        assertEquals("NO_STEPS", result.get("status"));
        assertEquals(Collections.emptyList(), result.get("steps"));
        verifyZeroInteractions(operations);
    }

    @Test
    public void missingOperationMakesRouteIncompleteWithoutUsingDenormalizedName() {
        when(operations.listByIds(any())).thenReturn(Collections.singletonList(operation("o1", "OP-1", "贴片")));
        assertEquals("ROUTE_INCOMPLETE", service.query("100112").get("status"));
    }

    @Test
    public void nullOrderMakesRouteIncomplete() {
        when(relations.list(any())).thenReturn(Collections.singletonList(relation("r1", "o1", null)));
        assertEquals("ROUTE_INCOMPLETE", service.query("100112").get("status"));
    }

    @Test
    public void zeroAndNegativeOrderMakeRouteIncomplete() {
        for (int order : new int[]{0, -1}) {
            when(relations.list(any())).thenReturn(Collections.singletonList(relation("r1", "o1", order)));
            assertEquals("ROUTE_INCOMPLETE", service.query("100112").get("status"));
        }
    }

    @Test
    public void duplicateOrdersMakeRouteIncomplete() {
        when(relations.list(any())).thenReturn(Arrays.asList(relation("r1", "o1", 10), relation("r2", "o2", 10)));
        assertEquals("ROUTE_INCOMPLETE", service.query("100112").get("status"));
    }

    @Test
    public void missingOperationIdMakesRouteIncomplete() {
        when(relations.list(any())).thenReturn(Collections.singletonList(relation("r1", null, 10)));
        assertEquals("ROUTE_INCOMPLETE", service.query("100112").get("status"));
        verifyZeroInteractions(operations);
    }

    @Test
    public void invalidCodeIsRejectedBeforeAnyDatabaseRead() {
        reset(materials, flows, relations, operations);
        for (String code : new String[]{null, "", " 100112", "100112 ", "中文", "A@B", String.join("", Collections.nCopies(256, "A"))}) {
            assertEquals("INVALID_INPUT", service.query(code).get("status"));
        }
        verifyZeroInteractions(materials, flows, relations, operations);
    }

    @Test
    public void transactionIsReadOnlyWithFiniteTimeout() throws Exception {
        Transactional transaction = MaterialRoutingService.class.getMethod("query", String.class).getAnnotation(Transactional.class);
        assertNotNull(transaction);
        assertTrue(transaction.readOnly());
        assertTrue(transaction.timeout() > 0 && transaction.timeout() <= 10);
    }

    private static Map<?, ?> map(Object value) {
        return (Map<?, ?>) value;
    }

    private static SpMaterile material(String id, String flowId) {
        SpMaterile result = new SpMaterile();
        result.setId(id);
        result.setMateriel("100112");
        result.setMaterielDesc("数据库中现有物料");
        result.setDeleted("0");
        result.setFlowId(flowId);
        return result;
    }

    private static SpFlowOperRelation relation(String id, String operationId, Integer order) {
        SpFlowOperRelation result = new SpFlowOperRelation();
        result.setId(id);
        result.setFlowId("f1");
        result.setOperId(operationId);
        result.setOper("不要使用过期工序代码");
        result.setSortNum(order);
        return result;
    }

    private static SpOper operation(String id, String code, String name) {
        SpOper result = new SpOper();
        result.setId(id);
        result.setOper(code);
        result.setOperDesc(name);
        return result;
    }
}
