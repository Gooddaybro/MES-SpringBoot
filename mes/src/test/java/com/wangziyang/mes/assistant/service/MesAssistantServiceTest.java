package com.wangziyang.mes.assistant.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.wangziyang.mes.basedata.entity.SpMaterile;
import com.wangziyang.mes.basedata.service.ISpMaterileService;
import com.wangziyang.mes.technology.entity.SpFlow;
import com.wangziyang.mes.technology.entity.SpFlowOperRelation;
import com.wangziyang.mes.technology.entity.SpOper;
import com.wangziyang.mes.technology.service.ISpFlowService;
import com.wangziyang.mes.technology.service.ISpFlowOperRelationService;
import com.wangziyang.mes.technology.service.ISpOperService;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

@RunWith(MockitoJUnitRunner.class)
public class MesAssistantServiceTest {
    @InjectMocks private MesAssistantService service;
    @Mock private ISpMaterileService materials;
    @Mock private ISpFlowService flows;
    @Mock private ISpFlowOperRelationService relations;
    @Mock private ISpOperService operations;

    @Test
    public void noMaterialCodeDoesNotQueryDatabase() {
        Map<String, Object> answer = service.answer("工艺路线是什么？");
        assertEquals("rule-based", answer.get("mode"));
        verifyZeroInteractions(materials, flows, relations, operations);
    }

    @Test
    @SuppressWarnings("unchecked")
    public void nonexistentMaterialDoesNotInventSteps() {
        when(materials.list(any(Wrapper.class))).thenReturn(Collections.emptyList());
        Map<String, Object> result = service.answer("物料编码为 MAT-001 的工序有哪些？");
        assertEquals("rule-based", result.get("mode"));
        assertNull(result.get("steps"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void realRouteIsReturnedInConfiguredOrder() {
        SpMaterile material = new SpMaterile();
        material.setFlowId("flow-1");
        when(materials.list(any(Wrapper.class))).thenReturn(Collections.singletonList(material));
        SpFlow flow = new SpFlow();
        flow.setId("flow-1");
        flow.setFlow("ROUTE-A");
        when(flows.getById("flow-1")).thenReturn(flow);
        SpFlowOperRelation relation = new SpFlowOperRelation();
        relation.setOperId("oper-1");
        relation.setOper("OP-A");
        relation.setSortNum(1);
        when(relations.list(any(Wrapper.class))).thenReturn(Collections.singletonList(relation));
        SpOper operation = new SpOper();
        operation.setOperDesc("准备工序");
        when(operations.getById("oper-1")).thenReturn(operation);
        Map<String, Object> result = service.answer("物料编码为 MAT-001 的工艺路线是什么？");
        assertEquals("database", result.get("mode"));
        List<Map<String, Object>> steps = (List<Map<String, Object>>) result.get("steps");
        assertEquals(1, steps.size());
        assertEquals("准备工序", steps.get(0).get("name"));
        assertEquals(1, steps.get(0).get("order"));
    }
}
