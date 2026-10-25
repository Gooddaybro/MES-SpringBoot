package com.wangziyang.mes.assistant.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.wangziyang.mes.basedata.entity.SpMaterile;
import com.wangziyang.mes.basedata.service.ISpMaterileService;
import com.wangziyang.mes.technology.entity.SpFlow;
import com.wangziyang.mes.technology.entity.SpFlowOperRelation;
import com.wangziyang.mes.technology.entity.SpOper;
import com.wangziyang.mes.technology.service.ISpFlowService;
import com.wangziyang.mes.technology.service.ISpFlowOperRelationService;
import com.wangziyang.mes.technology.service.ISpOperService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import java.util.regex.*;

/**
 * V1 为规则式的真实数据查询，不是 LLM。只读，且只允许按物料编码查询工艺。
 */
@Service
public class MesAssistantService {
    private static final Pattern CODE = Pattern.compile(
        "(?:物料编码|物料号|物料代码|编码)\\s*(?:为|是|[:：=])?\\s*([A-Za-z0-9][A-Za-z0-9._-]{0,63})(?![A-Za-z0-9._-])",
        Pattern.CASE_INSENSITIVE);

    @Autowired private ISpMaterileService materials;
    @Autowired private ISpFlowService flows;
    @Autowired private ISpFlowOperRelationService relations;
    @Autowired private ISpOperService operations;

    @Transactional(readOnly = true)
    public Map<String, Object> answer(String question) {
        String q = question == null ? "" : question.trim();
        if (q.isEmpty() || q.length() > 200) return notice("请填写 1～200 字的问题。");
        if (q.contains("工单") && (q.contains("工艺") || q.contains("工序"))) {
            return notice("工艺路线描述制造产品的标准工序顺序；工单代表一次具体生产任务。目前项目只有工艺查询助手，工单执行仍待开发。");
        }
        if (q.contains("能做什么") || q.contains("支持什么") || q.contains("如何查询") || q.contains("怎么查询") || q.contains("怎么用")) {
            return notice("当前支持按照物料编码查询工艺路线。示例：物料编码为 MAT-001 的工序有哪些？MAT-001 只是格式示例，请换成库中真实编码。尚未接入大模型。");
        }
        if (!q.contains("工序") && !q.contains("工艺") && !q.contains("路线")) {
            return notice("当前只支持工艺路线查询；BOM、质量统计和通用 AI 问答尚未接入。");
        }
        Matcher match = CODE.matcher(q);
        if (!match.find()) return notice("请提供物料编码，例如：物料编码为 MAT-001 的工艺路线是什么？");
        String code = match.group(1);
        QueryWrapper<SpMaterile> filter = new QueryWrapper<>();
        filter.eq("materiel", code).last("LIMIT 2");
        List<SpMaterile> found = materials.list(filter);
        if (found == null || found.isEmpty()) return notice("数据库中未找到物料编码「" + code + "」。");
        if (found.size() > 1) return notice("编码「" + code + "」匹配到多个物料，不能擅自选择，请检查重复数据。");
        SpMaterile material = found.get(0);
        if (material.getFlowId() == null || material.getFlowId().trim().isEmpty())
            return notice("已找到物料「" + code + "」，但未绑定工艺路线。");
        SpFlow flow = flows.getById(material.getFlowId());
        if (flow == null) return notice("物料「" + code + "」关联的工艺路线不存在。");
        QueryWrapper<SpFlowOperRelation> routeFilter = new QueryWrapper<>();
        routeFilter.eq("flow_id", flow.getId()).orderByAsc("sort_num");
        List<SpFlowOperRelation> route = relations.list(routeFilter);
        if (route == null || route.isEmpty())
            return notice("路线「" + flow.getFlow() + "」尚未配置工序。");
        List<Map<String, Object>> steps = new ArrayList<>();
        for (SpFlowOperRelation item : route) {
            SpOper operation = item.getOperId() == null ? null : operations.getById(item.getOperId());
            Map<String, Object> step = new LinkedHashMap<>();
            step.put("order", item.getSortNum());
            step.put("code", item.getOper());
            step.put("name", operation != null && operation.getOperDesc() != null
                && !operation.getOperDesc().trim().isEmpty()
                ? operation.getOperDesc() : item.getOper());
            steps.add(step);
        }
        Map<String, Object> result = notice("查到物料「" + code + "」的工艺路线「"
            + flow.getFlow() + "」，共 " + steps.size() + " 道工序：");
        result.put("mode", "database");
        result.put("steps", steps);
        result.put("source", "sp_materile → sp_flow → sp_flow_oper_relation / sp_oper");
        return result;
    }

    private Map<String, Object> notice(String answer) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("mode", "rule-based");
        data.put("answer", answer);
        return data;
    }
}
