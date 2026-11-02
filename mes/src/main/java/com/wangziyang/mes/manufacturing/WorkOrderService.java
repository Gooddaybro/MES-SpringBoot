package com.wangziyang.mes.manufacturing;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangziyang.mes.assistant.service.MaterialRoutingService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;

@Service
public class WorkOrderService {
    private static final String SELECT = "SELECT id, order_code AS orderCode, material_code AS materialCode, "
            + "material_name AS materialName, unit, planned_quantity AS plannedQuantity, state, "
            + "route_code AS routeCode, route_snapshot_json AS routeSnapshotJson, version, "
            + "created_at AS createdAt, updated_at AS updatedAt, started_at AS startedAt, completed_at AS completedAt "
            + "FROM mes_work_order";
    private final JdbcTemplate jdbc;
    private final MaterialRoutingService routing;
    private final ObjectMapper json;

    public WorkOrderService(JdbcTemplate jdbc, MaterialRoutingService routing, ObjectMapper json) {
        this.jdbc = jdbc;
        this.routing = routing;
        this.json = json;
    }

    @Transactional(readOnly = true, timeout = 8)
    public Map<String,Object> query(String workOrderCode, String materialCode) {
        String code = orderCode(workOrderCode, true);
        String material = ManufacturingSupport.code(materialCode, "物料编码", true);
        List<Object> parameters = new ArrayList<>();
        String sql = SELECT + " WHERE 1=1";
        if (code != null) { sql += " AND order_code=?"; parameters.add(code); }
        if (material != null) { sql += " AND material_code COLLATE utf8mb4_unicode_ci=?"; parameters.add(material); }
        List<Map<String,Object>> rows = jdbc.queryForList(sql + " ORDER BY created_at DESC,id DESC LIMIT 21", parameters.toArray());
        Map<String,Object> result = ManufacturingSupport.response(rows.isEmpty() ? "NO_WORK_ORDER" : "SUCCESS",
                rows.isEmpty() ? "没有符合条件的工单，可在业务工作台创建。" : "已查询到" + Math.min(20, rows.size()) + "条工单；工艺采用创建时快照。");
        List<Map<String,Object>> orders = new ArrayList<>();
        for(Map<String,Object> row : rows.subList(0, Math.min(20, rows.size()))) orders.add(expose(row));
        result.put("orders", orders);
        result.put("truncated", rows.size() > 20);
        result.put("source", "mes_work_order（工艺快照）");
        if (code != null && orders.size() == 1) result.put("workOrder", orders.get(0));
        return result;
    }

    @Transactional(timeout = 8)
    @SuppressWarnings("unchecked")
    public Map<String,Object> create(String orderCode, String materialCode, BigDecimal quantity, String actor) {
        String code = orderCode(orderCode, false);
        String material = ManufacturingSupport.code(materialCode, "物料编码", false);
        BigDecimal planned = ManufacturingSupport.quantity(quantity);
        String user = ManufacturingSupport.actor(actor);
        List<Map<String,Object>> existing = jdbc.queryForList(SELECT + " WHERE order_code=?", code);
        if (!existing.isEmpty()) return existingCreate(existing.get(0), material, planned, true);

        Map<String,Object> route = routing.query(material);
        if (!"SUCCESS".equals(route.get("status")))
            throw new BusinessException(String.valueOf(route.get("status")), String.valueOf(route.get("answer")));
        Map<String,Object> definition = (Map<String,Object>)route.get("material");
        Map<String,Object> flow = (Map<String,Object>)route.get("routing");
        List<?> steps = (List<?>)route.get("steps");
        if (definition == null || flow == null || steps == null || steps.isEmpty())
            throw new BusinessException("ROUTE_INCOMPLETE", "创建工单需要完整物料及工艺路线。");
        List<Map<String,Object>> materials = jdbc.queryForList("SELECT unit FROM sp_materile WHERE id=? AND is_deleted='0'", definition.get("id"));
        if (materials.size() != 1 || materials.get(0).get("unit") == null)
            throw new BusinessException("NO_MATERIAL", "物料不存在、已禁用或缺少基本单位。");
        String unit = String.valueOf(materials.get(0).get("unit"));
        Map<String,Object> snapshot = new LinkedHashMap<>();
        snapshot.put("material", definition);
        snapshot.put("routing", flow);
        snapshot.put("steps", steps);
        String snapshotJson;
        try { snapshotJson = json.writeValueAsString(snapshot); }
        catch (Exception invalid) { throw new BusinessException("DATA_UNAVAILABLE", "无法保存工艺快照。"); }
        if (snapshotJson.length() > 256000) throw new BusinessException("INVALID_INPUT", "工艺快照过大。");
        String id = UUID.randomUUID().toString();
        // Unique order_code serializes racing creates without replacing an existing snapshot.
        jdbc.update("INSERT INTO mes_work_order (id,order_code,material_id,material_code,material_name,unit,planned_quantity,"
                        + "state,route_id,route_code,route_snapshot_json,version,created_at,updated_at,created_by,updated_by) "
                        + "VALUES (?,?,?,?,?,?,?,'CREATED',?,?,?,0,NOW(3),NOW(3),?,?) "
                        + "ON DUPLICATE KEY UPDATE order_code=order_code",
                id, code, definition.get("id"), definition.get("code"), definition.get("name"), unit, planned,
                flow.get("id"), flow.get("code"), snapshotJson, user, user);
        Map<String,Object> stored = locked(code);
        return existingCreate(stored, material, planned, !id.equals(stored.get("id")));
    }

    @Transactional(timeout = 8)
    public Map<String,Object> transition(String orderCode, String action, String actor) {
        String code = orderCode(orderCode, false);
        String user = ManufacturingSupport.actor(actor);
        String command = action == null ? "" : action.trim().toUpperCase(Locale.ROOT);
        if (!Arrays.asList("START", "COMPLETE", "CANCEL").contains(command))
            throw new BusinessException("INVALID_INPUT", "工单操作只能是 START、COMPLETE 或 CANCEL。");
        String target = "START".equals(command) ? "IN_PROGRESS" : "COMPLETE".equals(command) ? "COMPLETED" : "CANCELLED";
        String origin = "COMPLETE".equals(command) ? "IN_PROGRESS" : "CREATED";
        Map<String,Object> order = locked(code);
        if (target.equals(order.get("state"))) return orderResult(order, "工单已处于目标状态，本次未重复修改。", true);
        if (!origin.equals(order.get("state")))
            throw new BusinessException("CONFLICT", "当前状态 " + order.get("state") + " 不允许该操作。");
        String time = "START".equals(command) ? ",started_at=NOW(3)" : "COMPLETE".equals(command) ? ",completed_at=NOW(3)" : "";
        int changed = jdbc.update("UPDATE mes_work_order SET state=?,version=version+1,updated_at=NOW(3),updated_by=?"
                + time + " WHERE id=? AND version=? AND state=?", target, user, order.get("id"), order.get("version"), origin);
        if (changed != 1) throw new BusinessException("CONFLICT", "工单已被其他操作更新，请重新查询。");
        return orderResult(locked(code), "工单已更新为 " + target + "。", false);
    }

    private String orderCode(String value, boolean optional) {
        String code = ManufacturingSupport.code(value, "工单号", optional);
        if (code != null && code.length() > 64) throw new BusinessException("INVALID_INPUT", "工单号最多64字符。");
        return code;
    }

    private Map<String,Object> locked(String code) {
        List<Map<String,Object>> rows = jdbc.queryForList(SELECT + " WHERE order_code=? FOR UPDATE", code);
        if (rows.isEmpty()) throw new BusinessException("NO_WORK_ORDER", "工单不存在。");
        return rows.get(0);
    }

    private Map<String,Object> existingCreate(Map<String,Object> order, String material, BigDecimal planned, boolean duplicate) {
        if (!material.equalsIgnoreCase(String.valueOf(order.get("materialCode"))) || planned.compareTo(new BigDecimal(order.get("plannedQuantity").toString())) != 0)
            throw new BusinessException("CONFLICT", "该工单号已存在，物料或计划数量不同，请使用新的工单号。");
        return orderResult(order, duplicate ? "工单已存在，返回已有记录，未重复创建。" : "工单已创建，工艺快照已保存。", duplicate);
    }

    private Map<String,Object> orderResult(Map<String,Object> order, String answer, boolean duplicate) {
        Map<String,Object> exposed = expose(order);
        Map<String,Object> result = ManufacturingSupport.response("SUCCESS", answer);
        result.put("workOrder", exposed);
        result.put("orders", Collections.singletonList(exposed));
        result.put("duplicate", duplicate);
        return result;
    }

    private Map<String,Object> expose(Map<String,Object> row) {
        Map<String,Object> order = new LinkedHashMap<>(row);
        Object snapshot = order.remove("routeSnapshotJson");
        try { order.put("routeSnapshot", json.readValue(String.valueOf(snapshot), new TypeReference<Map<String,Object>>() { })); }
        catch (Exception invalid) { throw new BusinessException("DATA_UNAVAILABLE", "工单工艺快照损坏，请联系维护人员。"); }
        return order;
    }
}
