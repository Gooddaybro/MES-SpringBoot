/* Scoped drawer; business answers and database fields are always text nodes. */
(function () {
    "use strict";
    var root = document.getElementById("mes-ai-widget");
    if (!root || !window.jQuery || root.getAttribute("data-initialized") === "true") return;
    root.setAttribute("data-initialized", "true");
    function byId(name) { return document.getElementById("mes-ai-" + name); }
    var launcher = byId("launcher"), panel = byId("panel"), input = byId("input");
    var form = byId("form"), send = byId("send"), messages = byId("messages"), feedback = byId("feedback");
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
            data && data.mode === "database" ? "数据库查询结果" : data && data.mode === "hybrid" ? "数据库与资料查询" : data && data.mode === "rag" ? "知识库检索结果" : "MES 助手"));
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
        if (data && data.agentExecuted === true) {
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
            if (data.agentExecuted === true) evidence.push("知识流程：" + (data.agentFramework || "知识服务") + "；回答方式：" + (data.answerMode || "NONE"));
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
        function removePending() { if (pending.parentNode) pending.parentNode.removeChild(pending); }
        activeRequest = window.jQuery.ajax({
            url: root.getAttribute("data-api"), type: "POST", dataType: "json",
            headers: { "X-Requested-With": "XMLHttpRequest" }, data: { message: text, conversationId: conversationId }, timeout: 150000
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
    byId("clear").addEventListener("click", function () {
        generation += 1;
        conversationId = newConversationId();
        if (activeRequest) { activeRequest.abort(); activeRequest = null; }
        while (messages.firstChild) messages.removeChild(messages.firstChild);
        input.value = ""; feedback.textContent = "对话已清空。"; setBusy(false); input.focus();
    });
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
    add("bot", "你好！我可以按物料编码查询工艺路线，也可检索已授权PDF并显示文件名、物理页码和原文。知识服务启用后支持连续追问；未配置聊天模型时显示原文摘录。复合查询会向内部知识服务提供必要的工艺结果；配置外部模型后会发送相关问题与资料片段。请勿填写敏感信息。BOM齐套和报工尚未接入。");
})();
