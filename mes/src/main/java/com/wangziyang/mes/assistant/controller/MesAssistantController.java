package com.wangziyang.mes.assistant.controller;

import com.wangziyang.mes.assistant.AssistantResponses;
import com.wangziyang.mes.assistant.service.MaterialRoutingService;
import com.wangziyang.mes.assistant.service.MesAssistantService;
import com.wangziyang.mes.common.Result;
import com.wangziyang.mes.system.entity.SysUser;
import org.apache.shiro.SecurityUtils;
import org.apache.shiro.subject.Subject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.sql.SQLTimeoutException;
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
        String userId = "-";
        HttpStatus httpStatus = HttpStatus.OK;
        Map<String, Object> data;
        try {
            Subject subject = SecurityUtils.getSubject();
            Object principal = subject.getPrincipal();
            if (principal instanceof SysUser && ((SysUser) principal).getId() != null) {
                userId = ((SysUser) principal).getId().replaceAll("[^A-Za-z0-9_-]", "_");
            }
            if (!subject.isAuthenticated()) {
                httpStatus = HttpStatus.UNAUTHORIZED;
                data = AssistantResponses.reply("AUTH_REQUIRED", "rule-based", "请先登录后使用助手。");
            } else if (!subject.isPermitted(PERMISSION)) {
                httpStatus = HttpStatus.FORBIDDEN;
                data = AssistantResponses.reply("FORBIDDEN", "rule-based", "当前账号没有物料工艺路线查询权限。");
            } else {
                data = new LinkedHashMap<>(query.get());
                if ("INVALID_INPUT".equals(data.get("status"))) {
                    httpStatus = HttpStatus.BAD_REQUEST;
                }
            }
        } catch (Exception e) {
            if (isTimeout(e)) {
                httpStatus = HttpStatus.GATEWAY_TIMEOUT;
                data = AssistantResponses.reply("TIMEOUT", "database", "查询超时，请稍后重试。");
            } else if (e instanceof DataAccessException || e instanceof TransactionException) {
                httpStatus = HttpStatus.SERVICE_UNAVAILABLE;
                data = AssistantResponses.reply("DATA_UNAVAILABLE", "database", "数据服务暂时不可用，请稍后重试。");
            } else {
                httpStatus = HttpStatus.INTERNAL_SERVER_ERROR;
                data = AssistantResponses.reply("ERROR", "rule-based", "助手暂时无法完成请求，请稍后重试。");
            }
        }
        if ("database".equals(data.get("mode"))) {
            AssistantResponses.addRoutingEvidence(data);
        }
        long elapsedMs = (System.nanoTime() - started) / 1000000;
        data.put("traceId", traceId);
        data.put("elapsedMs", elapsedMs);
        data.put("modelConnected", false);
        LOGGER.info("traceId={} endpoint=/admin/assistant/{} userId={} status={} elapsedMs={}",
                traceId, action, userId, data.get("status"), elapsedMs);
        String answer = (String) data.get("answer");
        Result<Map<String, Object>> result = httpStatus.isError() ? Result.failure(data, answer) : Result.success(data, answer);
        return ResponseEntity.status(httpStatus).body(result);
    }

    private boolean isTimeout(Throwable error) {
        Throwable cause = error;
        for (int depth = 0; cause != null && depth < 10; depth++, cause = cause.getCause()) {
            if (cause instanceof QueryTimeoutException || cause instanceof TransactionTimedOutException || cause instanceof SQLTimeoutException) {
                return true;
            }
        }
        return false;
    }
}
