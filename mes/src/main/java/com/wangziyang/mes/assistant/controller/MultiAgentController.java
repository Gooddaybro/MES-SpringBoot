package com.wangziyang.mes.assistant.controller;

import com.wangziyang.mes.assistant.AssistantResponses;
import com.wangziyang.mes.assistant.agent.MultiAgentService;
import com.wangziyang.mes.common.Result;
import org.apache.shiro.SecurityUtils;
import org.apache.shiro.subject.Subject;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/admin/assistant/multi")
public class MultiAgentController {
    private final MultiAgentService service;
    public MultiAgentController(MultiAgentService service) { this.service = service; }

    @PostMapping("/chat")
    public ResponseEntity<Result<Map<String, Object>>> chat(@RequestParam(required = false) String message,
            @RequestParam(required = false) String conversationId, @RequestParam(required = false) String materialCode,
            @RequestParam(required = false) String operationCode, @RequestParam(required = false) String workOrderCode) {
        long started = System.nanoTime(); Map<String, Object> data;
        try {
            Subject subject = SecurityUtils.getSubject();
            data = !subject.isAuthenticated() ? MultiAgentService.response("AUTH_REQUIRED", "请先登录后使用助手。")
                : service.chat(message, conversationId, subject, materialCode, operationCode, workOrderCode);
            data = new LinkedHashMap<>(data);
        } catch (Exception failure) { data = new LinkedHashMap<>(AssistantResponses.queryFailure(failure)); data.put("mode", "multi-agent"); data.put("agentExecuted", false); }
        HttpStatus status;
        switch (String.valueOf(data.get("status"))) {
            case "AUTH_REQUIRED": status = HttpStatus.UNAUTHORIZED; break;
            case "FORBIDDEN": status = HttpStatus.FORBIDDEN; break;
            case "INVALID_INPUT": status = HttpStatus.BAD_REQUEST; break;
            case "TIMEOUT": status = HttpStatus.GATEWAY_TIMEOUT; break;
            case "DATA_UNAVAILABLE": case "AGENT_UNAVAILABLE": case "TOOL_ERROR": status = HttpStatus.SERVICE_UNAVAILABLE; break;
            case "INVALID_AGENT_RESPONSE": status = HttpStatus.BAD_GATEWAY; break;
            case "ERROR": status = HttpStatus.INTERNAL_SERVER_ERROR; break;
            default: status = HttpStatus.OK;
        }
        data.put("traceId", UUID.randomUUID().toString()); data.put("elapsedMs", (System.nanoTime() - started) / 1000000);
        String answer = String.valueOf(data.get("answer"));
        return ResponseEntity.status(status).body(status.isError() ? Result.failure(data, answer) : Result.success(data, answer));
    }
}
