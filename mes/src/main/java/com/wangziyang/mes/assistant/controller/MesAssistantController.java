package com.wangziyang.mes.assistant.controller;

import com.wangziyang.mes.assistant.AssistantResponses;
import com.wangziyang.mes.assistant.service.MaterialRoutingService;
import com.wangziyang.mes.assistant.service.MesAssistantService;
import com.wangziyang.mes.common.Result;
import org.apache.shiro.SecurityUtils;
import org.apache.shiro.subject.Subject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

@RestController
@RequestMapping("/admin/assistant")
public class MesAssistantController {
    private static final Logger LOGGER = LoggerFactory.getLogger(MesAssistantController.class);
    private static final String PERMISSION = "mes:assistant:routing:read";
    private final MesAssistantService assistant;
    private final MaterialRoutingService routing;

    public MesAssistantController(MesAssistantService assistant, MaterialRoutingService routing) {
        this.assistant = assistant;
        this.routing = routing;
    }

    @PostMapping("/chat")
    public ResponseEntity<Result<Map<String, Object>>> chat(@RequestParam(required = false) String message) {
        return execute("chat", () -> assistant.chat(message));
    }

    @GetMapping("/material-routing")
    public ResponseEntity<Result<Map<String, Object>>> materialRouting(@RequestParam(required = false) String materialCode) {
        return execute("material-routing", () -> {
            if (!AssistantResponses.validMaterialCode(materialCode)) {
                return AssistantResponses.reply("INVALID_INPUT", "rule-based", "请提供1至255个字符的有效物料编码，仅允许英文字母、数字、点、下划线、斜线和短横线，并以字母或数字开头。");
            }
            return routing.query(materialCode);
        });
    }

    private ResponseEntity<Result<Map<String, Object>>> execute(String action, Supplier<Map<String, Object>> query) {
        String traceId = UUID.randomUUID().toString();
        long started = System.nanoTime();
        HttpStatus httpStatus = HttpStatus.OK;
        Map<String, Object> data;
        try {
            Subject subject = SecurityUtils.getSubject();
            if (!subject.isAuthenticated()) {
                httpStatus = HttpStatus.UNAUTHORIZED;
                data = AssistantResponses.reply("AUTH_REQUIRED", "rule-based", "请先登录后使用助手。");
            } else if (!subject.isPermitted(PERMISSION)) {
                httpStatus = HttpStatus.FORBIDDEN;
                data = AssistantResponses.reply("FORBIDDEN", "rule-based", "当前账号没有物料工艺路线查询权限。");
            } else {
                data = new LinkedHashMap<>(query.get());
            }
        } catch (Exception e) {
            data = AssistantResponses.queryFailure(e);
        }
        switch (String.valueOf(data.get("status"))) {
            case "INVALID_INPUT": httpStatus = HttpStatus.BAD_REQUEST; break;
            case "TIMEOUT": httpStatus = HttpStatus.GATEWAY_TIMEOUT; break;
            case "DATA_UNAVAILABLE": httpStatus = HttpStatus.SERVICE_UNAVAILABLE; break;
            case "ERROR": httpStatus = HttpStatus.INTERNAL_SERVER_ERROR; break;
            default: break;
        }
        data.putIfAbsent("recognitionSource", "RULE");
        data.putIfAbsent("modelAttempted", false);
        data.putIfAbsent("modelConnected", false);
        data.putIfAbsent("fallbackReason", null);
        if ("database".equals(data.get("mode"))) {
            AssistantResponses.addRoutingEvidence(data);
        }
        long elapsedMs = (System.nanoTime() - started) / 1000000;
        data.put("traceId", traceId);
        data.put("elapsedMs", elapsedMs);
        LOGGER.info("traceId={} endpoint=/admin/assistant/{} status={} recognitionSource={} modelAttempted={} fallbackReason={} elapsedMs={}",
                traceId, action, data.get("status"), data.get("recognitionSource"), data.get("modelAttempted"), data.get("fallbackReason"), elapsedMs);
        String answer = (String) data.get("answer");
        Result<Map<String, Object>> result = httpStatus.isError() ? Result.failure(data, answer) : Result.success(data, answer);
        return ResponseEntity.status(httpStatus).body(result);
    }

}
