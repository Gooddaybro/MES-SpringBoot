package com.wangziyang.mes.manufacturing;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.*;

/** Validated filters only; SQL structure never comes from the caller or a model. */
public final class BusinessQuery {
    private static final Set<String> KEYS = new HashSet<>(Arrays.asList("materialCode", "materialName", "materialStatus",
            "operationCode", "operationName", "workOrderCode", "state", "createdFrom", "createdTo", "page", "pageSize"));
    private final Map<String,String> values;
    public final String materialCode, materialName, materialStatus, operationCode, operationName, workOrderCode, state;
    public final LocalDateTime createdFrom, createdTo;
    public final int page, pageSize;

    private BusinessQuery(Map<String,String> input) {
        if (input == null || !KEYS.containsAll(input.keySet())) invalid("查询含有不支持的字段。");
        for(String value:input.values()) if(value!=null)
            for(int i=0;i<value.length();i++) if(Character.isISOControl(value.charAt(i))) invalid("查询条件不能包含控制字符。");
        values = Collections.unmodifiableMap(new LinkedHashMap<>(input));
        materialCode = ManufacturingSupport.code(input.get("materialCode"), "物料编码", true);
        operationCode = ManufacturingSupport.code(input.get("operationCode"), "工序编码", true);
        workOrderCode = ManufacturingSupport.code(input.get("workOrderCode"), "工单号", true);
        if (workOrderCode != null && workOrderCode.length() > 64) invalid("工单号最多64字符。");
        materialName = name(input.get("materialName")); operationName = name(input.get("operationName"));
        String status = text(input.get("materialStatus")); materialStatus = status == null ? "0" : status;
        if (!Arrays.asList("0", "1", "2").contains(materialStatus)) invalid("物料状态只能为0正常、1删除、2禁用。");
        state = text(input.get("state"));
        if (state != null && !Arrays.asList("CREATED", "IN_PROGRESS", "COMPLETED", "CANCELLED").contains(state))
            invalid("工单状态只能为 CREATED、IN_PROGRESS、COMPLETED、CANCELLED。");
        page = number(input.get("page"), 1, 1000); pageSize = number(input.get("pageSize"), 20, 50);
        createdFrom = date(input.get("createdFrom")); createdTo = date(input.get("createdTo"));
        if (createdFrom != null && createdTo != null && !createdFrom.isBefore(createdTo))
            invalid("创建时间起点必须早于终点，终点不包含在结果中。");
    }

    public static BusinessQuery from(Map<String,String> input) { return new BusinessQuery(input); }
    public BusinessQuery withPage(int page, int size) {
        Map<String,String> copy = new LinkedHashMap<>(values);
        copy.put("page", String.valueOf(page)); copy.put("pageSize", String.valueOf(size));
        return from(copy);
    }
    public Map<String,String> parameters() { return values; }
    public int offset() { return (page - 1) * pageSize; }
    public static String like(String value) {
        return "%" + value.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
    }

    Map<String,Object> result(String key, List<Map<String,Object>> rows, String emptyStatus, String source) {
        List<Map<String,Object>> pageRows = new ArrayList<>(rows.subList(0, Math.min(pageSize, rows.size())));
        Map<String,Object> result = ManufacturingSupport.response(rows.isEmpty() ? emptyStatus : "SUCCESS",
                rows.isEmpty() ? "没有符合条件的记录。" : "本页查询到" + pageRows.size() + "条记录。");
        result.put(key, pageRows); result.put("page", page); result.put("pageSize", pageSize);
        result.put("count", pageRows.size()); result.put("hasMore", rows.size() > pageSize);
        result.put("truncated", rows.size() > pageSize); result.put("source", source);
        return result;
    }

    private static String text(String value) { return value == null || value.trim().isEmpty() ? null : value.trim(); }
    private static String name(String value) {
        if (value != null) for (int i=0;i<value.length();i++) if (Character.isISOControl(value.charAt(i))) invalid("名称不能包含控制字符。");
        String clean = text(value); if (clean != null && clean.length() > 100) invalid("名称最多100字符。");
        return clean;
    }
    private static int number(String value, int fallback, int maximum) {
        String clean = text(value); if (clean == null) return fallback;
        if (!clean.matches("[0-9]{1,4}")) { invalid("分页参数必须为有效整数。"); }
        int number = Integer.parseInt(clean);
        if (number < 1 || number > maximum) invalid("分页参数超出范围：page为1至1000，pageSize为1至50。");
        return number;
    }
    private static LocalDateTime date(String value) {
        String clean = text(value); if (clean == null) return null;
        try { return clean.length() == 10 ? LocalDate.parse(clean).atStartOfDay() : LocalDateTime.parse(clean); }
        catch (DateTimeParseException invalid) { throw new BusinessException("INVALID_INPUT", "时间须为有效的本地日期或日期时间，不带时区；终点不包含在结果中。"); }
    }
    private static void invalid(String message) { throw new BusinessException("INVALID_INPUT", message); }
}
