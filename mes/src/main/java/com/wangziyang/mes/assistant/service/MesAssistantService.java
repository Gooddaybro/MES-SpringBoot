package com.wangziyang.mes.assistant.service;

import com.wangziyang.mes.assistant.AssistantResponses;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class MesAssistantService {
    private static final Pattern WRITE = Pattern.compile("删除|修改|创建|新增|添加|增加|调整|清空|清除|移除|替换|重置|覆盖|更新|编辑|变更|设置|保存|写入|录入|导入|提交|报工|生成|下发|下达|报修|启用|禁用|取消|审批|批准|执行|发起|派工|领料|退料|入库|出库|发货|(?i)(?<![A-Za-z0-9._/-])(delete|update|insert|create|drop|alter|remove|write)(?![A-Za-z0-9._/-])");
    private static final Pattern WORK_ORDER = Pattern.compile("工单|(?i)(?<![A-Za-z0-9._/-])work\\s*order(?![A-Za-z0-9._/-])");
    private static final Pattern UNSUPPORTED = Pattern.compile("(?i)(?<![A-Za-z0-9._/-])BOM(?![A-Za-z0-9._/-])|物料清单|库存|质量|质检|检验|不良|维修|保养|知识库|采购|产量|排产|生产计划");
    private static final Pattern ROUTING = Pattern.compile("工艺|工序|路线|流程|(?i)\\b(routing|route|operations?)\\b");
    private static final Pattern HELP = Pattern.compile("(?i)(帮助|你好|您好|怎么用|我?能做什么|功能|help|hello|hi)[!！?？。]*");
    // Keep the whole token, including invalid characters, so an invalid code cannot become a valid prefix.
    private static final Pattern TOKEN = Pattern.compile("[^\\p{IsHan}\\s，。；：、！？（）【】“”‘’;:!?()\\[\\]\\\"',]+");
    private static final Set<String> QUESTION_WORDS = new HashSet<>(Arrays.asList(
            "show", "material", "routing", "route", "operation", "operations", "for", "the", "of", "and", "code", "query", "get", "check", "find", "please"));

    private final MaterialRoutingService routing;

    public MesAssistantService(MaterialRoutingService routing) {
        this.routing = routing;
    }

    public Map<String, Object> chat(String message) {
        if (message == null || message.trim().isEmpty() || message.length() > 1000) {
            return reply("INVALID_INPUT", "请输入1至1000个字符的问题。");
        }
        String question = message.trim();
        if (WRITE.matcher(question).find()) {
            return reply("UNSUPPORTED", "当前助手仅支持只读查询，无法执行删除、修改、创建、报工、下发、报修等写操作。");
        }
        if (HELP.matcher(question).matches()) {
            return reply("HELP", "当前助手采用规则识别，尚未接入大模型。可输入物料编码，或提问“查物料编码的工艺路线”；仅查询现有数据库，不执行写操作。");
        }
        if (AssistantResponses.validMaterialCode(question)) {
            return routing.query(question);
        }
        if (WORK_ORDER.matcher(question).find()) {
            return reply("UNSUPPORTED", "当前仅支持按物料编码查询工艺路线，尚未接入工单查询，无法根据工单确认路线。");
        }
        if (UNSUPPORTED.matcher(question).find()) {
            return reply("UNSUPPORTED", "当前仅支持物料工艺路线只读查询，BOM、库存、质量、维修知识库和采购等能力尚未接入。");
        }
        boolean routeQuestion = ROUTING.matcher(question).find();
        Set<String> codes = new LinkedHashSet<>();
        Matcher matcher = TOKEN.matcher(question);
        while (matcher.find()) {
            String token = matcher.group();
            if (routeQuestion && QUESTION_WORDS.contains(token.toLowerCase(Locale.ROOT))) {
                continue;
            }
            if (!AssistantResponses.validMaterialCode(token)) {
                return reply("INVALID_INPUT", "物料编码格式无效或超过255个字符，请提供完整有效的单个编码。");
            }
            codes.add(token);
        }
        if (codes.size() > 1) {
            return reply("AMBIGUOUS", "识别到多个不同编码，请每次提供一个物料编码以确定工艺路线。");
        }
        if (routeQuestion) {
            if (codes.isEmpty()) {
                return reply("NEEDS_INPUT", "请提供一个物料编码，以查询对应的工艺路线和工序。");
            }
            return routing.query(codes.iterator().next());
        }
        return reply("UNSUPPORTED", "当前支持按物料编码查询工艺路线。请输入单个物料编码，或输入“帮助”查看用法。");
    }

    private Map<String, Object> reply(String status, String answer) {
        return AssistantResponses.reply(status, "rule-based", answer);
    }
}
