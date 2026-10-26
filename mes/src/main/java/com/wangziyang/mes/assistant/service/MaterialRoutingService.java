package com.wangziyang.mes.assistant.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.wangziyang.mes.assistant.AssistantResponses;
import com.wangziyang.mes.basedata.entity.SpMaterile;
import com.wangziyang.mes.basedata.service.ISpMaterileService;
import com.wangziyang.mes.technology.entity.SpFlow;
import com.wangziyang.mes.technology.entity.SpFlowOperRelation;
import com.wangziyang.mes.technology.entity.SpOper;
import com.wangziyang.mes.technology.service.ISpFlowService;
import com.wangziyang.mes.technology.service.ISpFlowOperRelationService;
import com.wangziyang.mes.technology.service.ISpOperService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class MaterialRoutingService {
    private final ISpMaterileService materials;
    private final ISpFlowService flows;
    private final ISpFlowOperRelationService relations;
    private final ISpOperService operations;

    public MaterialRoutingService(ISpMaterileService materials, ISpFlowService flows,
                                  ISpFlowOperRelationService relations, ISpOperService operations) {
        this.materials = materials;
        this.flows = flows;
        this.relations = relations;
        this.operations = operations;
    }

    @Transactional(readOnly = true, timeout = 8)
    public Map<String, Object> query(String materialCode) {
        if (!AssistantResponses.validMaterialCode(materialCode)) {
            return AssistantResponses.reply("INVALID_INPUT", "rule-based", "物料编码须以英文字母或数字开头，仅含字母、数字、点、下划线、斜线和短横线，最多255个字符。");
        }
        Map<String, Object> result = AssistantResponses.reply("NO_MATERIAL", "database", "未找到编码对应的正常可用物料，请核对物料编码与状态。");
        AssistantResponses.addRoutingEvidence(result);
        List<SpMaterile> found = materials.list(new QueryWrapper<SpMaterile>()
                .eq("materiel", materialCode).eq("is_deleted", "0").last("LIMIT 2"));
        if (found.isEmpty()) {
            return result;
        }
        if (found.size() > 1) {
            return finish(result, "AMBIGUOUS", "该编码存在多条正常物料记录，无法确定工艺路线，请先核对物料主数据。");
        }
        SpMaterile material = found.get(0);
        Map<String, Object> materialData = identity(material.getId(), material.getMateriel(), material.getMaterielDesc());
        materialData.put("status", material.getDeleted());
        result.put("material", materialData);
        if (blank(material.getFlowId())) {
            return finish(result, "NO_ROUTE", "该物料尚未关联工艺路线。");
        }
        SpFlow flow = flows.getById(material.getFlowId());
        if (flow == null) {
            return finish(result, "NO_ROUTE", "物料关联的工艺路线不存在，请核对路线关联数据。");
        }
        result.put("routing", identity(flow.getId(), flow.getFlow(), flow.getFlowDesc()));
        List<SpFlowOperRelation> ordered = new ArrayList<>(relations.list(new QueryWrapper<SpFlowOperRelation>()
                .eq("flow_id", flow.getId()).orderByAsc("sort_num")));
        List<Map<String, Object>> steps = new ArrayList<>();
        result.put("steps", steps);
        if (ordered.isEmpty()) {
            return finish(result, "NO_STEPS", "已找到工艺路线，但尚未配置工序。");
        }
        ordered.sort(Comparator.comparing(SpFlowOperRelation::getSortNum, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(SpFlowOperRelation::getId, Comparator.nullsLast(Comparator.naturalOrder())));
        Set<String> operationIds = new LinkedHashSet<>();
        for (SpFlowOperRelation relation : ordered) {
            if (!blank(relation.getOperId())) {
                operationIds.add(relation.getOperId());
            }
        }
        Map<String, SpOper> operationById = new HashMap<>();
        if (!operationIds.isEmpty()) {
            for (SpOper operation : operations.listByIds(operationIds)) {
                operationById.put(operation.getId(), operation);
            }
        }
        boolean complete = true;
        Set<Integer> orders = new HashSet<>();
        for (SpFlowOperRelation relation : ordered) {
            Integer order = relation.getSortNum();
            SpOper operation = operationById.get(relation.getOperId());
            if (order == null || order <= 0 || !orders.add(order) || operation == null) {
                complete = false;
            }
            Map<String, Object> step = new LinkedHashMap<>();
            step.put("relationId", relation.getId());
            step.put("operationId", relation.getOperId());
            step.put("order", order);
            if (operation != null) {
                step.put("code", operation.getOper());
                step.put("name", operation.getOperDesc());
            }
            steps.add(step);
        }
        if (!complete) {
            return finish(result, "ROUTE_INCOMPLETE", "已查到路线配置，但存在缺失工序或无效、重复的工序顺序，当前无法确认完整有效的工艺路线。");
        }
        return finish(result, "SUCCESS", "已查询到物料" + material.getMateriel() + "的工艺路线，共" + steps.size() + "道工序。");
    }

    private Map<String, Object> identity(String id, String code, String name) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", id);
        data.put("code", code);
        data.put("name", name);
        return data;
    }

    private Map<String, Object> finish(Map<String, Object> result, String status, String answer) {
        result.put("status", status);
        result.put("answer", answer);
        return result;
    }

    private boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
