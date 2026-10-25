package com.wangziyang.mes.assistant.controller;

import com.wangziyang.mes.assistant.service.MesAssistantService;
import com.wangziyang.mes.common.Result;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/admin/assistant")
public class MesAssistantController {
    @Autowired private MesAssistantService assistantService;

    @PostMapping("/chat")
    public Result<Map<String, Object>> chat(@RequestParam("message") String message) {
        if (message == null || message.trim().isEmpty() || message.length() > 200) {
            return Result.failure("请输入 1～200 字的问题。");
        }
        return Result.success(assistantService.answer(message));
    }
}
