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

    @Transactional(readOnly = true, timeout = 8)
    public Map<String,Object> searchMaterials(BusinessQuery query, String operationId) {
        List<Object> args = new ArrayList<>();
        String sql = "SELECT m.id,m.materiel AS code,m.materiel_desc AS name,m.unit,m.mat_type AS materialType,"
                + "m.model,m.size,m.flow_id AS flowId,m.flow_desc AS flowName,m.is_deleted AS status "
                + "FROM sp_materile m WHERE m.is_deleted=?";
        args.add(query.materialStatus);
        if (query.materialCode != null) { sql += " AND m.materiel=?"; args.add(query.materialCode); }
        if (query.materialName != null) { sql += " AND m.materiel_desc LIKE ? ESCAPE '!'"; args.add(BusinessQuery.like(query.materialName)); }
        if (operationId != null) {
            sql += " AND EXISTS (SELECT 1 FROM sp_flow_oper_relation r JOIN sp_flow f ON f.id=r.flow_id"
                    + " WHERE r.flow_id=m.flow_id AND r.oper_id=?)";
            args.add(operationId);
        }
        args.add(query.pageSize + 1); args.add(query.offset());
        Map<String,Object> result = query.result("materials", jdbc.queryForList(sql + " ORDER BY m.materiel,m.id LIMIT ? OFFSET ?", args.toArray()),
                "NO_MATERIAL", operationId == null ? "sp_materile" : "sp_materile.flow_id → sp_flow → sp_flow_oper_relation.oper_id");
        result.put("requiresSelection", ((List<?>)result.get("materials")).size() > 1 || Boolean.TRUE.equals(result.get("hasMore")));
        return result;
    }

    @Transactional(readOnly = true, timeout = 8)
    public Map<String,Object> searchOperations(BusinessQuery query) {
        List<Object> args = new ArrayList<>();
        String sql = "SELECT o.id,o.oper AS code,o.oper_desc AS name FROM sp_oper o WHERE 1=1";
        if (query.operationCode != null) { sql += " AND o.oper=?"; args.add(query.operationCode); }
        if (query.operationName != null) { sql += " AND o.oper_desc LIKE ? ESCAPE '!'"; args.add(BusinessQuery.like(query.operationName)); }
        if (query.materialCode != null) {
            sql += " AND EXISTS (SELECT 1 FROM sp_materile m JOIN sp_flow f ON f.id=m.flow_id"
                    + " JOIN sp_flow_oper_relation r ON r.flow_id=f.id WHERE m.materiel=? AND m.is_deleted='0' AND r.oper_id=o.id)";
            args.add(query.materialCode);
        }
        args.add(query.pageSize + 1); args.add(query.offset());
        return query.result("operations", jdbc.queryForList(sql + " ORDER BY o.oper,o.id LIMIT ? OFFSET ?", args.toArray()),
                "NO_OPERATION", "sp_oper（工序定义，非执行记录）");
    }

    @Transactional(readOnly = true, timeout = 8)
    public Map<String,Object> searchRoutings(BusinessQuery query, String operationId) {
        List<Object> args = new ArrayList<>();
        String sql = "SELECT f.id,f.flow AS code,f.flow_desc AS name FROM sp_flow f WHERE 1=1";
        if (query.materialCode != null) {
            sql += " AND EXISTS (SELECT 1 FROM sp_materile m WHERE m.flow_id=f.id AND m.materiel=? AND m.is_deleted='0')";
            args.add(query.materialCode);
        }
        if (operationId != null) {
            sql += " AND EXISTS (SELECT 1 FROM sp_flow_oper_relation r WHERE r.flow_id=f.id AND r.oper_id=?)";
            args.add(operationId);
        }
        args.add(query.pageSize + 1); args.add(query.offset());
        return query.result("routings", jdbc.queryForList(sql + " ORDER BY f.flow,f.id LIMIT ? OFFSET ?", args.toArray()),
                "NO_ROUTE", "sp_flow / sp_flow_oper_relation（当前配置，无发布版本字段）");
    }
}
