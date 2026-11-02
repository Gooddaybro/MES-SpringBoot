package com.wangziyang.mes.manufacturing;

import com.wangziyang.mes.assistant.service.MaterialRoutingService;
import org.apache.shiro.subject.Subject;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.util.*;

@Service
public class RoutingComparisonService {
    private final WorkOrderService orders;
    private final MaterialRoutingService routing;
    public RoutingComparisonService(WorkOrderService orders, MaterialRoutingService routing) { this.orders=orders; this.routing=routing; }

    @Transactional(readOnly = true, timeout = 8)
    @SuppressWarnings("unchecked")
    public Map<String,Object> compare(String workOrderCode, Subject subject) {
        if (subject == null || !subject.isAuthenticated()) return ManufacturingSupport.response("AUTH_REQUIRED","请先登录。");
        if (!subject.isPermitted("mes:assistant:workorder:read") || !subject.isPermitted("mes:assistant:routing:read"))
            return ManufacturingSupport.response("FORBIDDEN","工艺对比需要工单及工艺路线读取权限。");
        String code=ManufacturingSupport.code(workOrderCode,"工单号",false);
        if (code.length()>64) throw new BusinessException("INVALID_INPUT","工单号最多64字符。");
        Map<String,Object> queried=orders.query(code,null);
        if (!"SUCCESS".equals(queried.get("status"))) return queried;
        Object value=queried.get("workOrder");
        if (!(value instanceof Map)) return unreliable("工单未提供可靠的历史快照。");
        Map<String,Object> order=(Map<String,Object>)value;
        String material=order.get("materialCode") instanceof String ? (String)order.get("materialCode") : null;
        Object raw=order.get("routeSnapshot");
        if (material==null || !(raw instanceof Map)) return unreliable("工单没有可靠的历史工艺快照，不能用当前工艺补造。");
        Map<?,?> snapshot=(Map<?,?>)raw;
        Map<?,?> definition=snapshot.get("material") instanceof Map ? (Map<?,?>)snapshot.get("material") : Collections.emptyMap();
        if (!nonblank(definition.get("id")) || !material.equalsIgnoreCase(String.valueOf(definition.get("code"))) || !identity(snapshot.get("routing")))
            return unreliable("历史快照物料或路线标识不完整或与工单不一致。");
        List<Map<String,Object>> before=steps(snapshot.get("steps"));
        if (before==null) return unreliable("历史工序缺少可靠标识、编码或顺序；未生成差异结论。");
        Map<String,Object> current=routing.query(material);
        Map<String,Object> result=ManufacturingSupport.response("SUCCESS","已比较创建时快照与物料当前关联路线。该路线没有发布版本或生效日期记录。");
        result.put("workOrderCode",order.get("orderCode")); result.put("materialCode",material);
        result.put("workOrderState",order.get("state")); result.put("snapshotSteps",before);
        result.put("snapshotRouting",snapshot.get("routing")); result.put("comparisonBasis","CURRENT_ASSIGNED_ROUTE");
        result.put("source","mes_work_order.route_snapshot_json / sp_materile → sp_flow → sp_flow_oper_relation → sp_oper");
        if (!"SUCCESS".equals(current.get("status"))) {
            result.put("status",current.get("status")); result.put("answer",current.get("answer")); result.put("comparisonAvailable",false);
            return result;
        }
        Object currentMaterial=current.get("material");
        if(!(currentMaterial instanceof Map) || !Objects.equals(definition.get("id"),((Map<?,?>)currentMaterial).get("id"))) {
            result.put("status","MATERIAL_IDENTITY_CHANGED"); result.put("comparisonAvailable",false);
            result.put("answer","当前同编码物料与工单历史物料不是同一记录，不能将替代物料的路线当作原物料标准。请人工核对主数据。");
            return result;
        }
        List<Map<String,Object>> after=steps(current.get("steps"));
        if (after==null || !identity(current.get("routing"))) {
            result.put("status","ROUTE_INCOMPLETE"); result.put("answer","当前路线结构不完整，未生成差异结论。"); result.put("comparisonAvailable",false);
            return result;
        }
        Map<String,Map<String,Object>> oldSteps=index(before), newSteps=index(after);
        List<Map<String,Object>> changes=new ArrayList<>();
        Map<?,?> oldRoute=(Map<?,?>)snapshot.get("routing"), newRoute=(Map<?,?>)current.get("routing");
        if (!Objects.equals(oldRoute.get("id"),newRoute.get("id")) || !Objects.equals(oldRoute.get("code"),newRoute.get("code")))
            changes.add(change("ROUTING_CHANGED",null,oldRoute,newRoute));
        for (Map.Entry<String,Map<String,Object>> entry:oldSteps.entrySet()) {
            Map<String,Object> previous=entry.getValue(), next=newSteps.get(entry.getKey());
            if (next==null) { changes.add(change("REMOVED",entry.getKey(),previous,null)); continue; }
            for (String field:Arrays.asList("order","code","name")) {
                Object oldValue=previous.get(field), newValue=next.get(field);
                boolean equal="order".equals(field) ? sequence(oldValue)==sequence(newValue) : Objects.equals(oldValue,newValue);
                if (!equal) changes.add(change("order".equals(field) ? "ORDER_CHANGED" : "code".equals(field) ? "CODE_CHANGED" : "NAME_CHANGED",entry.getKey(),previous,next));
            }
        }
        for (Map.Entry<String,Map<String,Object>> entry:newSteps.entrySet())
            if (!oldSteps.containsKey(entry.getKey())) changes.add(change("ADDED",entry.getKey(),null,entry.getValue()));
        boolean structural=changes.stream().anyMatch(item -> !"NAME_CHANGED".equals(item.get("type")));
        List<String> limitations=new ArrayList<>(Arrays.asList(
                "当前路线没有发布版本及生效日期，不能确认它是最新已发布标准。",
                "历史快照未保存温度、扭矩、工时、工序类型等参数，不能确认这些参数是否变化。",
                "差异是数据库事实；实际质量、产能及交期影响需要工艺人员确认。"));
        if (before.stream().map(item -> item.get("operationId")).distinct().count()!=before.size()
                || after.stream().map(item -> item.get("operationId")).distinct().count()!=after.size())
            limitations.add("同一工序重复出现时按出现次数配对；复杂重复工序调整需要人工复核。");
        result.put("comparisonAvailable",true); result.put("currentRouting",newRoute); result.put("currentSteps",after);
        result.put("changes",changes); result.put("hasChanges",!changes.isEmpty()); result.put("potentialProductionImpact",structural);
        result.put("requiresProcessReview",!changes.isEmpty()); result.put("limitations",limitations);
        result.put("impactAssessment",structural ? "存在可能影响生产的结构或标识变化，请由工艺人员评估；历史工单快照保持不变。"
                : "未证实存在影响生产的结构变化；未保存的参数仍无法确认。");
        return result;
    }

    private static Map<String,Object> unreliable(String message) {
        Map<String,Object> result=ManufacturingSupport.response("SNAPSHOT_UNRELIABLE",message); result.put("comparisonAvailable",false); return result;
    }
    private static boolean identity(Object value) {
        if (!(value instanceof Map)) return false; Map<?,?> identity=(Map<?,?>)value;
        return nonblank(identity.get("id")) && nonblank(identity.get("code"));
    }
    private static boolean nonblank(Object value) { return value instanceof String && !((String)value).trim().isEmpty(); }
    @SuppressWarnings("unchecked")
    private static List<Map<String,Object>> steps(Object value) {
        if (!(value instanceof List) || ((List<?>)value).isEmpty()) return null;
        List<Map<String,Object>> result=new ArrayList<>(); int previous=0;
        for (Object raw:(List<?>)value) {
            if (!(raw instanceof Map)) return null; Map<String,Object> step=(Map<String,Object>)raw;
            int order=sequence(step.get("order"));
            if (!nonblank(step.get("operationId")) || !nonblank(step.get("code")) || order<=previous) return null;
            previous=order; result.add(step);
        }
        return result;
    }
    private static int sequence(Object value) {
        if (!(value instanceof Number)) return -1;
        try { return new BigDecimal(value.toString()).intValueExact(); }
        catch (ArithmeticException | NumberFormatException invalid) { return -1; }
    }
    private static Map<String,Map<String,Object>> index(List<Map<String,Object>> steps) {
        Map<String,Integer> occurrences=new HashMap<>(); Map<String,Map<String,Object>> indexed=new LinkedHashMap<>();
        for (Map<String,Object> step:steps) {
            String id=String.valueOf(step.get("operationId")); int count=occurrences.getOrDefault(id,0)+1;
            occurrences.put(id,count); indexed.put(id+"#"+count,step);
        }
        return indexed;
    }
    private static Map<String,Object> change(String type,String occurrence,Object before,Object after) {
        Map<String,Object> result=new LinkedHashMap<>(); result.put("type",type); result.put("occurrence",occurrence);
        result.put("before",before); result.put("after",after); return result;
    }
}
