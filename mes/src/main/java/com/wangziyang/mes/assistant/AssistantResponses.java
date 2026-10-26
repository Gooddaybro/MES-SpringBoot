package com.wangziyang.mes.assistant;

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
        return result;
    }

    public static void addRoutingEvidence(Map<String, Object> result) {
        result.putIfAbsent("tool", "get_material_routing");
        result.putIfAbsent("source", "sp_materile → sp_flow → sp_flow_oper_relation → sp_oper");
        result.putIfAbsent("queriedAt", Instant.now().toString());
    }
}
