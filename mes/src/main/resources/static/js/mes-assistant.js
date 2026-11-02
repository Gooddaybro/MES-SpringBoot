/* Scoped drawer; business answers and database fields are always text nodes. */
(function () {
    "use strict";
    var root = document.getElementById("mes-ai-widget");
    if (!root || !window.jQuery || root.getAttribute("data-initialized") === "true") return;
    root.setAttribute("data-initialized", "true");
    function byId(name) { return document.getElementById("mes-ai-" + name); }
    var launcher = byId("launcher"), panel = byId("panel"), input = byId("input");
    var form = byId("form"), send = byId("send"), messages = byId("messages"), feedback = byId("feedback");
    var mode = byId("mode"), context = byId("context");
    var shortcuts = root.querySelectorAll("[data-question]");
    var busy = false, composing = false, generation = 0, activeRequest = null;
    function newConversationId() {
        if (window.crypto && window.crypto.randomUUID) return window.crypto.randomUUID();
        // This UUID only groups messages. Shiro supplies authentication and ownership.
        return "xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx".replace(/[xy]/g, function (c) {
            var n = Math.floor(Math.random() * 16);
            return (c === "x" ? n : (n & 3) | 8).toString(16);
        });
    }
    var conversationId = newConversationId();
    function isMulti() { return mode && mode.value === "multi"; }

    function node(tag, className, text) {
        var element = document.createElement(tag);
        if (className) element.className = className;
        if (text !== undefined && text !== null) element.textContent = String(text);
        return element;
    }
    function openPanel(open) {
        panel.hidden = !open;
        launcher.setAttribute("aria-expanded", String(open));
        if (open) input.focus(); else launcher.focus();
    }
    function setBusy(value) {
        busy = value;
        send.disabled = value;
        messages.setAttribute("aria-busy", String(value));
        Array.prototype.forEach.call(shortcuts, function (button) { button.disabled = value; });
    }
    function copy(text) {
        function failed() { feedback.textContent = "复制失败，请选中文字后手动复制。"; }
        function succeeded() { feedback.textContent = "已复制，可直接粘贴。"; }
        if (window.isSecureContext && navigator.clipboard && navigator.clipboard.writeText) {
            navigator.clipboard.writeText(text).then(succeeded, failed);
        } else {
            var temporary = node("textarea", "mes-ai-clipboard");
            temporary.value = text;
            temporary.setAttribute("aria-label", "待复制内容");
            root.appendChild(temporary);
            temporary.select();
            try { if (document.execCommand("copy")) succeeded(); else failed(); }
            catch (_) { failed(); }
            root.removeChild(temporary);
            if (!panel.hidden) input.focus();
        }
    }
    function copyButton(label, text) {
        var button = node("button", "mes-ai-copy", label);
        button.type = "button";
        button.addEventListener("click", function () { copy(text); });
        return button;
    }
    function add(role, text, data, pending) {
        var message = node("div", "mes-ai-message mes-ai-" + role);
        var parts = [text || ""];
        if (role === "bot") message.appendChild(node("div", "mes-ai-label",
            data && data.mode === "multi-agent" ? "四业务多 Agent" : data && data.mode === "database" ? "数据库查询结果" : data && data.mode === "hybrid" ? "数据库与资料查询" : data && data.mode === "rag" ? "知识库检索结果" : "MES 助手"));
        message.appendChild(node("div", "mes-ai-message-body", text || ""));
        if (data && data.material) {
            var material = data.material;
            var description = "物料：" + (material.code || "") + " · " + (material.name || "");
            if (data.routing) description += "\n路线：" + (data.routing.code || "") + " · " + (data.routing.name || "");
            message.appendChild(node("div", "mes-ai-material", description));
            parts.push(description);
            if (material.code) message.appendChild(copyButton("复制物料编码", material.code));
        }
        if (data && (data.status === "SUCCESS" || data.status === "PARTIAL_SUCCESS" || data.agentExecuted === true) && Array.isArray(data.steps) && data.steps.length) {
            var wrap = node("div", "mes-ai-table-wrap"), table = node("table", "mes-ai-table");
            var head = node("thead"), titles = node("tr");
            ["序号", "工序编码", "工序名称"].forEach(function (title) {
                var cell = node("th", "", title); cell.setAttribute("scope", "col"); titles.appendChild(cell);
            });
            head.appendChild(titles); table.appendChild(head);
            var body = node("tbody");
            data.steps.forEach(function (step) {
                var row = node("tr");
                [step.order, step.code, step.name].forEach(function (value) { row.appendChild(node("td", "", value)); });
                body.appendChild(row);
                parts.push(String(step.order) + ". " + (step.code || "") + " " + (step.name || ""));
            });
            table.appendChild(body); wrap.appendChild(table); message.appendChild(wrap);
        }
        if (data && data.mode === "multi-agent") {
            var labels = { material: "物料", operation: "工序", routing: "工艺路线", workorder: "工单" };
            var execution = data.agentExecuted === true ? "多 Agent 流程已执行" : "多 Agent 服务未执行，显示 Java 查询结果";
            message.appendChild(node("p", "mes-ai-rag-state", execution)); parts.push(execution);
            (data.agentResults || []).forEach(function (agent) {
                var lines = [(labels[agent.agentId] || agent.agentId) + " · " + agent.status, agent.answer || ""];
                (agent.toolCalls || []).forEach(function (tool) { lines.push("工具：" + tool.name + " · " + tool.status + " · " + tool.elapsedMs + " ms"); });
                if (agent.executedNodes && agent.executedNodes.length) lines.push("执行节点：" + agent.executedNodes.join(" → "));
                lines.push("模型请求：" + (agent.modelAttempted === true ? "已尝试" : "未尝试"));
                lines.push("模型状态：" + (agent.modelConnected === true ? "本次响应校验通过" : "本次未调用或未通过校验"));
                if (agent.fallbackReason) lines.push("降级原因：" + agent.fallbackReason);
                message.appendChild(node("div", "mes-ai-agent-card", lines.join("\n"))); parts.push(lines.join("\n"));
            });
            var handoffs = (data.handoffs || []).map(function (handoff) { return handoff.from + " → " + handoff.to + " · " + handoff.status; });
            if (handoffs.length) { message.appendChild(node("p", "mes-ai-handoffs", "业务交接：\n" + handoffs.join("\n"))); parts.push(handoffs.join("\n")); }
            if (data.businessResults) {
                var facts = node("details", "mes-ai-evidence");
                facts.appendChild(node("summary", "", "查看 Java 实际查询结果"));
                Object.keys(data.businessResults).forEach(function (id) {
                    var result = data.businessResults[id], description = (labels[id] || id) + "：" + JSON.stringify(result, null, 2);
                    facts.appendChild(node("pre", "", description)); parts.push(description);
                });
                message.appendChild(facts);
            }
        }
        if (data && data.mode !== "multi-agent" && data.agentExecuted === true) {
            var state = "资料检索：" + (data.retrievalStatus || "NOT_REQUESTED");
            if (data.answerMode === "EXTRACTIVE_NO_MODEL") state += " · 未配置聊天模型，显示原文摘录";
            else if (data.answerMode === "GROUNDED_MODEL") state += " · 模型已选择原文依据";
            else if (data.answerMode === "EXTRACTIVE_FALLBACK") state += " · 模型失败，保留原文摘录";
            message.appendChild(node("p", "mes-ai-rag-state", state)); parts.push(state);
        }
        if (data && Array.isArray(data.citations) && data.citations.length) {
            var citations = node("div", "mes-ai-citations");
            citations.appendChild(node("strong", "", "资料来源（PDF物理页码）"));
            data.citations.slice(0, 5).forEach(function (citation, index) {
                var title = "[" + (index + 1) + "] " + citation.filename + " · PDF第" + citation.pageNumber + "页";
                var version = "版本：" + (citation.documentVersion && citation.documentVersion !== "unknown" ? citation.documentVersion : "未标注");
                var card = node("details", "mes-ai-citation");
                card.appendChild(node("summary", "", title));
                card.appendChild(node("div", "mes-ai-citation-version", version));
                card.appendChild(node("blockquote", "mes-ai-citation-quote", citation.quote));
                citations.appendChild(card); parts.push(title + "\n" + version + "\n" + citation.quote);
            });
            message.appendChild(citations);
        }
        if (data && (data.source || data.traceId || data.recognitionSource)) {
            var evidence = [];
            if (data.recognitionSource) {
                evidence.push("识别来源：" + data.recognitionSource);
                evidence.push("模型请求：" + (data.modelAttempted === true ? "已尝试" : "未尝试"));
                evidence.push("模型状态：" + (data.modelConnected === true ? "本次响应校验通过" : "本次未调用或未通过校验"));
                if (data.fallbackReason) evidence.push("降级原因：" + data.fallbackReason);
            }
            if (data.source) evidence.push("来源：" + data.source);
            if (data.queriedAt) evidence.push("查询时间：" + data.queriedAt);
            if (data.agentExecuted === true) evidence.push("执行流程：" + (data.agentFramework || "知识服务"));
            if (data.material && data.material.id) evidence.push("物料记录：" + data.material.id);
            if (data.routing && data.routing.id) evidence.push("路线记录：" + data.routing.id);
            if (Array.isArray(data.steps)) data.steps.forEach(function (step) {
                if (step.relationId || step.operationId) evidence.push("关系记录：" + (step.relationId || "") + "；工序记录：" + (step.operationId || ""));
            });
            if (data.traceId) evidence.push("请求编号：" + data.traceId);
            if (data.elapsedMs !== undefined) evidence.push("耗时：" + data.elapsedMs + " ms");
            var details = node("details", "mes-ai-evidence");
            details.appendChild(node("summary", "", "查看查询依据"));
            details.appendChild(node("pre", "", evidence.join("\n")));
            message.appendChild(details); parts.push(evidence.join("\n"));
        }
        if (!pending) message.appendChild(copyButton(role === "bot" ? "复制回答" : "复制内容", parts.join("\n")));
        messages.appendChild(message);
        messages.scrollTop = messages.scrollHeight;
        return message;
    }
    function ask(question) {
        var text = (question || "").trim();
        if (busy || !text) return;
        if (text.length > 1000) { feedback.textContent = "问题最多1000字，请缩短后发送。"; return; }
        feedback.textContent = "";
        add("user", text); input.value = ""; setBusy(true);
        if (!panel.hidden) input.focus();
        var pending = add("bot", "正在查询，请稍候…", null, true), version = generation;
        var parameters = { message: text, conversationId: conversationId };
        if (isMulti()) ["material", "operation", "work-order"].forEach(function (name) {
            var field = byId(name + "-code"), value = field && field.value.trim();
            if (value) parameters[name === "work-order" ? "workOrderCode" : name + "Code"] = value;
        });
        function removePending() { if (pending.parentNode) pending.parentNode.removeChild(pending); }
        activeRequest = window.jQuery.ajax({
            url: isMulti() ? (root.getAttribute("data-multi-api") || "/admin/assistant/multi/chat") : root.getAttribute("data-api"), type: "POST", dataType: "json",
            headers: { "X-Requested-With": "XMLHttpRequest" }, data: parameters, timeout: 150000
        });
        activeRequest.done(function (response) {
            if (version !== generation) return;
            removePending();
            if (response && response.code === 0 && response.data) add("bot", response.data.answer, response.data);
            else add("bot", "查询未成功，请检查输入后重试。", response && response.data);
        }).fail(function (xhr, reason) {
            if (version !== generation || reason === "abort") return;
            removePending();
            var hint = "服务暂时不可用，请检查后端与网络后重试。";
            if (xhr.status === 401) hint = "登录已过期，请重新登录MES后重试。";
            else if (xhr.status === 403) hint = "没有本次查询所需权限，请联系管理员授权后重新登录。";
            else if (reason === "timeout" || xhr.status === 504) hint = "查询超时，请稍后重试；本次未取得可靠结果。";
            else if (xhr.status === 400) hint = "输入参数不正确，请使用单个完整物料编码重试。";
            else if (xhr.responseJSON && xhr.responseJSON.data && xhr.responseJSON.data.answer) hint = xhr.responseJSON.data.answer;
            add("bot", hint, xhr.responseJSON && xhr.responseJSON.data);
        }).always(function () {
            if (version !== generation) return;
            activeRequest = null; setBusy(false);
        });
    }
    launcher.addEventListener("click", function () { openPanel(panel.hidden); });
    byId("close").addEventListener("click", function () { openPanel(false); });
    function resetConversation() {
        generation += 1;
        conversationId = newConversationId();
        if (activeRequest) { activeRequest.abort(); activeRequest = null; }
        while (messages.firstChild) messages.removeChild(messages.firstChild);
        input.value = ""; setBusy(false);
    }
    byId("clear").addEventListener("click", function () {
        resetConversation();
        ["material-code", "operation-code", "work-order-code"].forEach(function (name) { var field = byId(name); if (field) field.value = ""; });
        feedback.textContent = "对话已清空。"; input.focus();
    });
    if (mode) mode.addEventListener("change", function () {
        resetConversation();
        if (context) context.hidden = !isMulti();
        feedback.textContent = isMulti() ? "已切换多 Agent：查询物料、工序、工艺路线和工单。" : "已切换单 Agent：工艺路线与知识查询。";
    });
    if (context) context.hidden = !isMulti();
    form.addEventListener("submit", function (event) { event.preventDefault(); if (!composing) ask(input.value); });
    input.addEventListener("compositionstart", function () { composing = true; });
    input.addEventListener("compositionend", function () { composing = false; });
    input.addEventListener("keydown", function (event) {
        if (event.key === "Enter" && !event.shiftKey && !composing && !event.isComposing && event.keyCode !== 229) {
            event.preventDefault(); ask(input.value);
        }
    });
    document.addEventListener("keydown", function (event) {
        if (event.key === "Escape" && !event.isComposing && !composing && !panel.hidden) openPanel(false);
    });
    Array.prototype.forEach.call(shortcuts, function (button) {
        button.addEventListener("click", function () { ask(button.getAttribute("data-question")); });
    });
    add("bot", "你好！单 Agent 支持工艺路线与授权 PDF 查询。切换多 Agent 可查询物料、工序、工艺路线和工单，并查看各业务的真实查询结果与执行状态。业务工作台可创建工单及变更状态。配置外部模型后会发送相关问题与授权查询结果，请勿填写敏感信息。");
})();
