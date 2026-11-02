package com.wangziyang.mes.assistant.agent;

import com.wangziyang.mes.manufacturing.BusinessException;
import com.wangziyang.mes.manufacturing.BusinessQuery;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A small explicit grammar. Unrecognized names or conflicting filters are never guessed. */
public final class BusinessQuestion {
    private static final Pattern OPERATION=Pattern.compile("(?:包含|含有)[“\"‘']?([^“”\"‘’'，。；！？,;!?\\n]{1,100}?)[”\"’']?工序");
    private static final Pattern MATERIAL=Pattern.compile("(?:物料(?:名称)?(?:包含|为|叫)?|名称(?:包含|为))[“\"‘']([^“”\"‘’'\\n]{1,100})[”\"’']");
    private static final Pattern STATE=Pattern.compile("状态(?:为|是|[:：])?\\s*(CREATED|IN_PROGRESS|COMPLETED|CANCELLED)(?![A-Za-z0-9_])");
    private static final Pattern UNSUPPORTED_FILTER=Pattern.compile("不包含|未包含|不含|不包括|排除|除了|未完成|未开工|已下达|已发布|已暂停|暂停中|已挂起|已关闭|"
            + "今天|今日|昨天|昨日|前天|明天|本周|上周|这周|本月|上月|本年|今年|去年|最近|过去|近[一二三四五六七八九十百0-9]+[天周月年]|"
            + "[0-9]{4}年|(?<![A-Za-z0-9._/-])[0-9]{4}-[0-9]{2}-[0-9]{2}(?![A-Za-z0-9._/-])|截止|截至|之前|之后|早于|晚于|大于|小于|至少|至多|不超过");
    private static final Pattern READ_WORDS=Pattern.compile("帮我|麻烦你|请你|我想要|我想|我要|帮忙|查询|查找|查看|展示|显示|列出|检查|对比|比较|对照|差异|区别|"
            + "生产工单|工艺路线|四个业务|四项业务|四业务|物料|工序|工单|工艺|路线|编码|编号|当前|现在|目前|最新|标准|历史|创建时|保存|快照|"
            + "使用|采用|对应|完整|所有|全部|综合|详情|明细|情况|信息|定义|结果|记录|关联|相关|包含|含有|以及|与|和|及|并且|并|"
            + "已创建|待开工|进行中|已开工|已完成|已完工|已取消|已删除|禁用|正常|状态|处于|是什么|哪些|什么|怎么样|如何|请|查|找|看|的|有|为|是|其");
    public final String mode, codeQuestion;
    public final BusinessQuery query;
    public final boolean search;

    private BusinessQuestion(String mode,String codeQuestion,BusinessQuery query) {
        this.mode=mode; this.codeQuestion=codeQuestion; this.query=query;
        search=query.parameters().keySet().stream().anyMatch(key -> !Arrays.asList("materialCode","operationCode","workOrderCode").contains(key));
    }
    public static BusinessQuestion parse(String question,Map<String,String> supplied) {
        Map<String,String> filters=new LinkedHashMap<>(supplied); String codeQuestion=question;
        Matcher operation=OPERATION.matcher(question); boolean operationClause=false;
        while(operation.find()) { merge(filters,"operationName",operation.group(1).trim()); operationClause=true; }
        if (operationClause) codeQuestion=OPERATION.matcher(codeQuestion).replaceAll("包含工序");
        Matcher material=MATERIAL.matcher(question); boolean materialClause=false;
        while(material.find()) { merge(filters,"materialName",material.group(1).trim()); materialClause=true; }
        if(materialClause) codeQuestion=MATERIAL.matcher(codeQuestion).replaceAll("物料");
        Matcher stateCode=STATE.matcher(codeQuestion);
        while(stateCode.find()) merge(filters,"state",stateCode.group(1));
        codeQuestion=STATE.matcher(codeQuestion).replaceAll("状态");
        if(UNSUPPORTED_FILTER.matcher(codeQuestion).find())
            throw new BusinessException("NEEDS_INPUT","暂不能可靠解释该否定、时间、数量或状态限制，请使用支持的明确编码、状态及创建时间字段，不能忽略限制后执行查询。");
        for(String[] state:new String[][]{{"CREATED","已创建","待开工"},{"IN_PROGRESS","进行中","已开工"},
                {"COMPLETED","已完成","已完工"},{"CANCELLED","已取消"}})
            for(int i=1;i<state.length;i++) if(codeQuestion.contains(state[i])) merge(filters,"state",state[0]);
        if(codeQuestion.contains("禁用物料") || codeQuestion.contains("禁用状态")) merge(filters,"materialStatus","2");
        if(codeQuestion.contains("已删除物料") || codeQuestion.contains("删除状态")) merge(filters,"materialStatus","1");
        String remainder=codeQuestion.replaceAll("[A-Za-z0-9][A-Za-z0-9._/-]*", " ");
        if(Pattern.compile("\\p{IsHan}").matcher(READ_WORDS.matcher(remainder).replaceAll("")).find())
            throw new BusinessException("NEEDS_INPUT","尚有无法可靠解释的名称或限定条件；名称请加引号或填入补充条件，时间请使用创建时间字段。未执行忽略条件的查询。");
        String mode="independent";
        if (question.matches("(?s).*(?:对比|比较|差异).*") && question.contains("工单") && (question.contains("工艺") || question.contains("路线")))
            mode="route_compare";
        else if ((operationClause || question.contains("关联") && question.contains("工序")
                || !comprehensive(question) && (filters.get("operationName")!=null || filters.get("operationCode")!=null))
                && (question.contains("物料") || question.contains("工单") || question.contains("路线"))) mode="operation_chain";
        return new BusinessQuestion(mode,codeQuestion,BusinessQuery.from(filters));
    }
    public static boolean comprehensive(String question) {
        if(question.contains("综合") || question.contains("四个业务") || question.contains("四项业务") || question.contains("四业务") || question.contains("全部业务")) return true;
        if(!question.contains("全部")) return false;
        String value=question.toLowerCase(Locale.ROOT); int domains=0;
        if(value.contains("物料") || value.contains("material")) domains++;
        if(value.contains("工序") || value.contains("operation")) domains++;
        if(value.contains("路线") || value.contains("routing")) domains++;
        if(value.contains("工单") || value.contains("workorder") || value.contains("work order")) domains++;
        return domains!=1;
    }
    private static void merge(Map<String,String> filters,String key,String value) {
        String previous=filters.get(key);
        if(previous!=null && !previous.trim().isEmpty() && !previous.trim().equals(value))
            throw new BusinessException("INVALID_INPUT","问题中的条件与补充条件冲突，或包含多个候选条件，请明确后重试。");
        filters.put(key,value);
    }
}
