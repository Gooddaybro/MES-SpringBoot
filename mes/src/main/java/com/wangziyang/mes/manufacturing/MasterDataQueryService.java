package com.wangziyang.mes.manufacturing;

import com.wangziyang.mes.assistant.service.MaterialRoutingService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class MasterDataQueryService {
    private final JdbcTemplate jdbc;
    private final MaterialRoutingService routing;

    public MasterDataQueryService(JdbcTemplate jdbc, MaterialRoutingService routing) {
        this.jdbc = jdbc;
        this.routing = routing;
    }

    @Transactional(readOnly = true, timeout = 8)
    public Map<String,Object> material(String materialCode) {
        String code = ManufacturingSupport.code(materialCode, "物料编码", true);
        List<Object> parameters = new ArrayList<>();
        String sql = "SELECT id, materiel AS code, materiel_desc AS name, unit, mat_type AS materialType, "
                + "model, size, flow_id AS flowId, flow_desc AS flowName, is_deleted AS status "
                + "FROM sp_materile WHERE is_deleted='0'";
        if (code != null) { sql += " AND materiel=?"; parameters.add(code); }
        sql += " ORDER BY id LIMIT " + (code == null ? "21" : "2");
        List<Map<String,Object>> rows = jdbc.queryForList(sql, parameters.toArray());
        if (code != null && rows.size() > 1)
            return ManufacturingSupport.response("AMBIGUOUS", "该编码存在多条正常物料，请先核对主数据。");
        Map<String,Object> result = ManufacturingSupport.response(rows.isEmpty() ? "NO_MATERIAL" : "SUCCESS",
                rows.isEmpty() ? "未找到正常可用的物料。" : "已查询到" + Math.min(20, rows.size()) + "条正常物料。");
        result.put("materials", new ArrayList<>(rows.subList(0, Math.min(20, rows.size()))));
        result.put("truncated", rows.size() > 20);
        if (code != null && rows.size() == 1) result.put("material", rows.get(0));
        result.put("source", "sp_materile");
        return result;
    }

    @Transactional(readOnly = true, timeout = 8)
    @SuppressWarnings("unchecked")
    public Map<String,Object> operations(String operationCode, String materialCode) {
        String code = ManufacturingSupport.code(operationCode, "工序编码", true);
        String material = ManufacturingSupport.code(materialCode, "物料编码", true);
        List<Map<String,Object>> rows;
        if (material != null) {
            Map<String,Object> route = routing.query(material);
            if (!"SUCCESS".equals(route.get("status"))) return route;
            rows = (List<Map<String,Object>>)route.getOrDefault("steps", Collections.emptyList());
            if (code != null) rows = rows.stream().filter(item -> code.equalsIgnoreCase((String)item.get("code"))).collect(Collectors.toList());
        } else {
            List<Object> parameters = new ArrayList<>();
            String sql = "SELECT id, oper AS code, oper_desc AS name FROM sp_oper";
            if (code != null) { sql += " WHERE oper=?"; parameters.add(code); }
            sql += " ORDER BY id LIMIT " + (code == null ? "51" : "2");
            rows = jdbc.queryForList(sql, parameters.toArray());
            if (code != null && rows.size() > 1)
                return ManufacturingSupport.response("AMBIGUOUS", "该编码存在多条工序定义，请核对主数据。");
        }
        Map<String,Object> result = ManufacturingSupport.response(rows.isEmpty() ? "NO_OPERATION" : "SUCCESS",
                rows.isEmpty() ? "未找到匹配的工序定义。" : "已查询到" + Math.min(50, rows.size()) + "条工序定义，非生产执行记录。");
        result.put("operations", new ArrayList<>(rows.subList(0, Math.min(50, rows.size()))));
        result.put("truncated", rows.size() > 50);
        result.put("source", material == null ? "sp_oper" : "sp_materile → sp_flow_oper_relation → sp_oper");
        return result;
    }
}
