package com.wangziyang.mes.assistant.service;

import com.wangziyang.mes.assistant.AssistantResponses;
import com.wangziyang.mes.assistant.agent.LlmIntentClient;
import com.wangziyang.mes.assistant.agent.LlmIntentResult;
import com.wangziyang.mes.assistant.agent.MaterialCodeCandidates;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Service
public class MesAssistantService {
    private static final Pattern WRITE = Pattern.compile("删除|修改|创建|新增|添加|增加|调整|清空|清除|移除|替换|重置|覆盖|更新|编辑|变更|设置|保存|写入|录入|导入|提交|报工|生成|下发|下达|报修|启用|禁用|取消|审批|批准|执行|发起|派工|领料|退料|入库|出库|发货|(?i)(?<![A-Za-z0-9._/-])(delete|update|insert|create|drop|alter|remove|write)(?![A-Za-z0-9._/-])");
    private static final Pattern WORK_ORDER = Pattern.compile("工单|(?i)(?<![A-Za-z0-9._/-])work\\s*order(?![A-Za-z0-9._/-])");
    private static final Pattern UNSUPPORTED = Pattern.compile("(?i)(?<![A-Za-z0-9._/-])BOM(?![A-Za-z0-9._/-])|物料清单|库存|质量|质检|检验|不良|维修|保养|知识库|采购|产量|排产|生产计划");
    private static final Pattern ROUTING = Pattern.compile("工艺|工序|路线|流程|(?i)(?<![A-Za-z0-9._/-])(routing|route|operations?)(?![A-Za-z0-9._/-])");
    private static final Pattern HELP = Pattern.compile("(?i)(帮助|你好|您好|怎么用|我?能做什么|功能|help|hello|hi)[!！?？。]*");
    private static final Pattern OVERRIDE_OR_SQL = Pattern.compile("忽略|无视|绕过|越权|系统提示|提示词|冒充|假装|(?i)(?<![A-Za-z0-9._/-])(ignore|override|system|prompt|sudo|select|union|exec|sql)(?![A-Za-z0-9._/-])");
    private static final Pattern INVALID_CONTROL = Pattern.compile("[\\p{Cc}&&[^\\s]]");

    private final MaterialRoutingService routing;
    private final LlmIntentClient llm;

    public MesAssistantService(MaterialRoutingService routing) {
        this(routing, null);
    }

    @Autowired
    public MesAssistantService(MaterialRoutingService routing, LlmIntentClient llm) {
        this.routing = routing;
        this.llm = llm;
    }

    public Map<String, Object> chat(String message) {
        if (message == null || message.trim().isEmpty() || message.length() > 1000) {
            return reply("INVALID_INPUT", "请输入1至1000个字符的问题。");
        }
        if (INVALID_CONTROL.matcher(message).find()) {
            return reply("INVALID_INPUT", "问题含不支持的控制字符，请提供原始完整的有效物料编码。");
        }
        String question = message.trim();
        if (WRITE.matcher(question).find() || OVERRIDE_OR_SQL.matcher(question).find()) {
            return reply("UNSUPPORTED", "当前助手仅支持只读查询，无法执行删除、修改、创建、报工、下发、报修等写操作。");
        }
        if (HELP.matcher(question).matches()) {
            return reply("HELP", "当前助手规则优先，可输入物料编码或询问工艺路线。未解决的单编码问句在服务端配置模型后可辅助识别；只发送本次问题，不发送数据库结果。BOM、报工和知识库尚未接入；仅支持只读查询。");
        }
        if (AssistantResponses.validMaterialCode(question)) {
            return query(question, "RULE", false, false);
        }
        if (WORK_ORDER.matcher(question).find()) {
            return reply("UNSUPPORTED", "当前仅支持按物料编码查询工艺路线，尚未接入工单查询，无法根据工单确认路线。");
        }
        if (UNSUPPORTED.matcher(question).find()) {
            return reply("UNSUPPORTED", "当前仅支持物料工艺路线只读查询，BOM、库存、质量、维修知识库和采购等能力尚未接入。");
        }
        boolean routeQuestion = ROUTING.matcher(question).find();
        MaterialCodeCandidates candidates = new MaterialCodeCandidates(message);
        if (!candidates.isValid()) {
            return reply("INVALID_INPUT", "物料编码格式无效或超过255个字符，请提供完整有效的单个编码。");
        }
        Set<String> codes = candidates.getCodes();
        if (codes.size() > 1) {
            return reply("AMBIGUOUS", "识别到多个不同编码，请每次提供一个物料编码以确定工艺路线。");
        }
        if (routeQuestion) {
            if (codes.isEmpty()) {
                return reply("NEEDS_INPUT", "请提供一个物料编码，以查询对应的工艺路线和工序。");
            }
            return query(codes.iterator().next(), "RULE", false, false);
        }
        if (codes.isEmpty()) {
            return reply("UNSUPPORTED", "当前支持按物料编码查询工艺路线。请输入单个物料编码，或输入“帮助”查看用法。");
        }
        LlmIntentResult intent = llm == null ? LlmIntentResult.failure("MODEL_NOT_CONFIGURED", false) : llm.recognize(message);
        if (!intent.isValid()) {
            return fallback(intent.isAttempted(), intent.getFailureReason());
        }
        if ("UNSUPPORTED".equals(intent.getIntent()) && intent.getMaterialCode() == null) {
            return AssistantResponses.recognition(reply("UNSUPPORTED", "模型识别为当前能力范围外的问题。当前仅支持物料工艺路线只读查询。"), "MODEL", true, true, null);
        }
        // Whitelist and original-token checks stay in Java, independently of the model's claims.
        if (!"QUERY_MATERIAL_ROUTING".equals(intent.getIntent())
                || (intent.getMaterialCode() != null && (!AssistantResponses.validMaterialCode(intent.getMaterialCode())
                || !codes.contains(intent.getMaterialCode())))) {
            return fallback(intent.isAttempted(), "MODEL_INVALID_PARAMETERS");
        }
        if (intent.isClarificationNeeded()) {
            return AssistantResponses.recognition(reply("NEEDS_INPUT", "请明确要查询的单个物料编码及工艺路线问题。"), "MODEL", true, true, null);
        }
        if (intent.getMaterialCode() == null) {
            return fallback(intent.isAttempted(), "MODEL_INVALID_PARAMETERS");
        }
        return query(intent.getMaterialCode(), "MODEL", true, true);
    }

    private Map<String, Object> query(String code, String source, boolean attempted, boolean connected) {
        Map<String, Object> result;
        try {
            result = routing.query(code);
        } catch (RuntimeException error) {
            result = AssistantResponses.queryFailure(error);
        }
        return AssistantResponses.recognition(result, source, attempted, connected, null);
    }

    private Map<String, Object> fallback(boolean attempted, String reason) {
        return AssistantResponses.recognition(reply("NEEDS_INPUT", "暂时无法确认查询意图，请输入单个物料编码或明确提问该物料的工艺路线。"),
                "RULE_FALLBACK", attempted, false, reason);
    }

    private Map<String, Object> reply(String status, String answer) {
        return AssistantResponses.reply(status, "rule-based", answer);
    }
}
