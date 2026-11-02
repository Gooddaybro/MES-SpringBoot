package com.wangziyang.mes.manufacturing;

import org.apache.shiro.subject.Subject;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

/** Resolves actual IDs once; no inferred associations and no writes. */
@Service
public class OperationImpactService {
    private final MasterDataQueryService master;
    private final WorkOrderService orders;
    public OperationImpactService(MasterDataQueryService master, WorkOrderService orders) { this.master=master; this.orders=orders; }

    @Transactional(readOnly = true, timeout = 8)
    @SuppressWarnings("unchecked")
    public Map<String,Object> query(BusinessQuery query, Subject subject) {
        if (subject == null || !subject.isAuthenticated()) return ManufacturingSupport.response("AUTH_REQUIRED", "请先登录。");
        for (String role : Arrays.asList("operation", "routing", "material", "workorder"))
            if (!subject.isPermitted("mes:assistant:" + role + ":read"))
                return ManufacturingSupport.response("FORBIDDEN", "工序关联查询需要物料、工序、路线、工单四项读取权限。");
        if (query.operationCode == null && query.operationName == null)
            return ManufacturingSupport.response("NEEDS_INPUT", "请提供工序编码或工序名称。");
        if (query.materialName != null)
            return ManufacturingSupport.response("NEEDS_INPUT", "限定物料的关联查询需要唯一物料编码；请先搜索名称、选择编码，并清除名称条件后重试。");
        Map<String,String> resolution=new LinkedHashMap<>();
        if(query.operationCode!=null) resolution.put("operationCode",query.operationCode);
        if(query.operationName!=null) resolution.put("operationName",query.operationName);
        // Resolve globally: an historical order may contain an operation removed from today's material route.
        Map<String,Object> operation = new LinkedHashMap<>(master.searchOperations(BusinessQuery.from(resolution).withPage(1,2)));
        List<Map<String,Object>> candidates = (List<Map<String,Object>>)operation.getOrDefault("operations", Collections.emptyList());
        String status = String.valueOf(operation.get("status"));
        if ("SUCCESS".equals(status) && (candidates.size() > 1 || Boolean.TRUE.equals(operation.get("hasMore")))) {
            status="AMBIGUOUS"; operation.put("status",status); operation.put("answer","匹配到多个工序，请提供完整工序编码后重试；未查询关联数据。");
        }
        Map<String,Object> facts = new LinkedHashMap<>(); facts.put("operation",operation);
        Map<String,Object> result = ManufacturingSupport.response(status, String.valueOf(operation.get("answer")));
        result.put("businessResults",facts); result.put("queryMode","operation_chain");
        if (!"SUCCESS".equals(status)) {
            for (String role : Arrays.asList("material","routing","workorder"))
                facts.put(role,ManufacturingSupport.response("NEEDS_INPUT","尚未确定唯一工序，未执行此关联查询。"));
            return result;
        }
        if (candidates.size()!=1 || !(candidates.get(0).get("id") instanceof String)
                || ((String)candidates.get(0).get("id")).trim().isEmpty())
            throw new BusinessException("DATA_UNAVAILABLE","工序查询未返回可靠的唯一标识。");
        String id=(String)candidates.get(0).get("id");
        result.put("operationId",id);
        facts.put("routing",master.searchRoutings(query,id));
        facts.put("material",master.searchMaterials(query,id));
        facts.put("workorder",orders.search(query,id));
        result.put("answer","已按唯一工序标识查询当前关联路线、物料及包含该工序历史快照的工单；空关联保持为空。各列表独立分页。");
        return result;
    }
}
