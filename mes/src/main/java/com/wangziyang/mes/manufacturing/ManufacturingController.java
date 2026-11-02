package com.wangziyang.mes.manufacturing;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.wangziyang.mes.assistant.AssistantResponses;
import com.wangziyang.mes.assistant.service.MaterialRoutingService;
import com.wangziyang.mes.common.Result;
import com.wangziyang.mes.system.entity.SysUser;
import org.apache.shiro.SecurityUtils;
import org.apache.shiro.subject.Subject;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.*;
import java.util.function.Supplier;

@Controller
@RequestMapping("/admin/manufacturing")
public class ManufacturingController {
    private final MasterDataQueryService master;
    private final MaterialRoutingService routing;
    private final WorkOrderService orders;

    public ManufacturingController(MasterDataQueryService master, MaterialRoutingService routing, WorkOrderService orders) {
        this.master = master;
        this.routing = routing;
        this.orders = orders;
    }

    @GetMapping({"", "/"})
    public String workbench(HttpServletResponse response) throws IOException {
        Subject subject = SecurityUtils.getSubject();
        if (!subject.isAuthenticated()) { response.sendError(401); return null; }
        for(String domain : Arrays.asList("material", "operation", "routing", "workorder"))
            if (subject.isPermitted("mes:assistant:" + domain + ":read")) return "admin/assistant/manufacturing";
        response.sendError(403);
        return null;
    }

    @GetMapping("/material") @ResponseBody
    public ResponseEntity<Result<Map<String,Object>>> material(@RequestParam(required = false) String materialCode) {
        return run("material:read", () -> master.material(materialCode));
    }

    @GetMapping("/operations") @ResponseBody
    public ResponseEntity<Result<Map<String,Object>>> operations(@RequestParam(required = false) String operationCode,
                                                                @RequestParam(required = false) String materialCode) {
        return run("operation:read", () -> master.operations(operationCode, materialCode));
    }

    @GetMapping("/routing") @ResponseBody
    public ResponseEntity<Result<Map<String,Object>>> routing(@RequestParam(required = false) String materialCode) {
        return run("routing:read", () -> routing.query(materialCode));
    }

    @GetMapping("/orders") @ResponseBody
    public ResponseEntity<Result<Map<String,Object>>> orders(@RequestParam(required = false) String workOrderCode,
                                                            @RequestParam(required = false) String materialCode) {
        return run("workorder:read", () -> orders.query(workOrderCode, materialCode));
    }

    @PostMapping(value = "/orders", consumes = MediaType.APPLICATION_JSON_VALUE) @ResponseBody
    public ResponseEntity<Result<Map<String,Object>>> create(@RequestBody CreateOrderRequest body) {
        return run("workorder:write", () -> {
            if (body == null) throw new BusinessException("INVALID_INPUT", "请填写工单参数。");
            return orders.create(text(body.orderCode, "orderCode"), text(body.materialCode, "materialCode"),
                    ManufacturingSupport.quantity(body.quantity), actor());
        });
    }

    public static class CreateOrderRequest {
        public String orderCode;
        public String materialCode;
        public BigDecimal quantity;

        @JsonAnySetter
        public void rejectUnknownField(String name, Object value) {
            throw new IllegalArgumentException("不支持的工单字段。");
        }
    }

    @ExceptionHandler(HttpMessageNotReadableException.class) @ResponseBody
    public ResponseEntity<Result<Map<String,Object>>> invalidJson() {
        Map<String,Object> data = ManufacturingSupport.response("INVALID_INPUT", "请求格式或字段无效，请检查工单参数。");
        return ResponseEntity.badRequest().body(Result.failure(data, String.valueOf(data.get("answer"))));
    }

    @PostMapping(value = "/orders/transition", consumes = MediaType.APPLICATION_JSON_VALUE) @ResponseBody
    public ResponseEntity<Result<Map<String,Object>>> transition(@RequestBody Map<String,Object> body) {
        return run("workorder:write", () -> {
            keys(body, "orderCode", "action");
            return orders.transition(text(body, "orderCode"), text(body, "action"), actor());
        });
    }

    private void keys(Map<String,Object> body, String... allowed) {
        if (body == null || !new HashSet<>(Arrays.asList(allowed)).equals(body.keySet()))
            throw new BusinessException("INVALID_INPUT", "请求字段缺失或包含不支持的字段。");
    }

    private String text(Map<String,Object> body, String name) {
        return text(body.get(name), name);
    }

    private String text(Object value, String name) {
        if (!(value instanceof String) || ((String)value).length() > 255)
            throw new BusinessException("INVALID_INPUT", "字段 " + name + " 格式无效。");
        return (String)value;
    }

    private String actor() {
        Object principal = SecurityUtils.getSubject().getPrincipal();
        if (principal instanceof SysUser) {
            SysUser user = (SysUser)principal;
            return ManufacturingSupport.actor(user.getId() == null ? user.getUsername() : user.getId());
        }
        return ManufacturingSupport.actor(principal == null ? null : principal.toString());
    }

    private ResponseEntity<Result<Map<String,Object>>> run(String permission, Supplier<Map<String,Object>> action) {
        HttpStatus status = HttpStatus.OK;
        Map<String,Object> data;
        try {
            Subject subject = SecurityUtils.getSubject();
            if (!subject.isAuthenticated()) {
                data = ManufacturingSupport.response("AUTH_REQUIRED", "请先登录。");
            } else if (!subject.isPermitted("mes:assistant:" + permission)) {
                data = ManufacturingSupport.response("FORBIDDEN", "当前账号没有该业务权限。");
            } else data = action.get();
        } catch (BusinessException expected) {
            data = ManufacturingSupport.response(expected.getStatus(), expected.getMessage());
        } catch (Exception error) { data = AssistantResponses.queryFailure(error); }
        switch(String.valueOf(data.get("status"))) {
            case "AUTH_REQUIRED": status = HttpStatus.UNAUTHORIZED; break;
            case "FORBIDDEN": status = HttpStatus.FORBIDDEN; break;
            case "INVALID_INPUT": status = HttpStatus.BAD_REQUEST; break;
            case "CONFLICT": status = HttpStatus.CONFLICT; break;
            case "DATA_UNAVAILABLE": status = HttpStatus.SERVICE_UNAVAILABLE; break;
            case "TIMEOUT": status = HttpStatus.GATEWAY_TIMEOUT; break;
            case "ERROR": status = HttpStatus.INTERNAL_SERVER_ERROR; break;
            default: break;
        }
        String answer = String.valueOf(data.get("answer"));
        return ResponseEntity.status(status).body(status.isError() ? Result.failure(data, answer) : Result.success(data, answer));
    }
}
