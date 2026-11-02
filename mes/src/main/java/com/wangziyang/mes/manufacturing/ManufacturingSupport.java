package com.wangziyang.mes.manufacturing;

import com.wangziyang.mes.assistant.AssistantResponses;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

final class ManufacturingSupport {
    private ManufacturingSupport() { }

    static String code(String value, String label, boolean optional) {
        if (value == null || value.trim().isEmpty()) {
            if (optional) return null;
            throw new BusinessException("INVALID_INPUT", "请填写" + label + "。");
        }
        String code = value.trim();
        if (!AssistantResponses.validMaterialCode(code))
            throw new BusinessException("INVALID_INPUT", label + "格式无效，最多255字符，仅支持英文字母、数字、点、下划线、斜线和短横线。");
        return code;
    }

    static BigDecimal quantity(BigDecimal value) {
        if (value == null || value.signum() <= 0 || value.stripTrailingZeros().scale() > 3
                || value.compareTo(new BigDecimal("1000000000")) > 0)
            throw new BusinessException("INVALID_INPUT", "计划数量必须大于0、不超过10亿，最多3位小数。");
        return value;
    }

    static String actor(String value) {
        if (value == null || value.trim().isEmpty() || value.length() > 128)
            throw new BusinessException("INVALID_INPUT", "登录主体无效。");
        return value;
    }

    static Map<String,Object> response(String status, String answer) {
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("status", status);
        result.put("mode", "database");
        result.put("answer", answer);
        return result;
    }
}
