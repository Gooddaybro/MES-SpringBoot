package com.wangziyang.mes.assistant;

import org.springframework.dao.DataAccessException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionTimedOutException;

import java.sql.SQLTimeoutException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

public final class AssistantResponses {
    private static final Pattern MATERIAL_CODE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._/-]{0,254}");

    private AssistantResponses() {
    }

    public static boolean validMaterialCode(String code) {
        return code != null && MATERIAL_CODE.matcher(code).matches();
    }

    public static Map<String, Object> reply(String status, String mode, String answer) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", status);
        result.put("mode", mode);
        result.put("answer", answer);
        return recognition(result, "RULE", false, false, null);
    }

    public static Map<String, Object> recognition(Map<String, Object> response, String source,
                                                   boolean attempted, boolean connected, String failure) {
        Map<String, Object> result = new LinkedHashMap<>(response);
        result.put("recognitionSource", source);
        result.put("modelAttempted", attempted);
        result.put("modelConnected", connected);
        result.put("fallbackReason", failure);
        return result;
    }

    public static Map<String, Object> queryFailure(Exception error) {
        Throwable cause = error;
        for (int depth = 0; cause != null && depth < 10; depth++, cause = cause.getCause()) {
            if (cause instanceof QueryTimeoutException || cause instanceof TransactionTimedOutException || cause instanceof SQLTimeoutException) {
                return reply("TIMEOUT", "database", "查询超时，请稍后重试。");
            }
        }
        if (error instanceof DataAccessException || error instanceof TransactionException) {
            return reply("DATA_UNAVAILABLE", "database", "数据服务暂时不可用，请稍后重试。");
        }
        return reply("ERROR", "rule-based", "助手暂时无法完成请求，请稍后重试。");
    }

    public static void addRoutingEvidence(Map<String, Object> result) {
        result.putIfAbsent("tool", "get_material_routing");
        result.putIfAbsent("source", "sp_materile → sp_flow → sp_flow_oper_relation → sp_oper");
        result.putIfAbsent("queriedAt", Instant.now().toString());
    }
}
