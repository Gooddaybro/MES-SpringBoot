/* Explicit business forms; all returned strings remain text. */
(function () {
    "use strict";
    var root = document.getElementById("mes-manufacturing");
    if (!root || !window.jQuery || root.getAttribute("data-initialized") === "true") return;
    root.setAttribute("data-initialized", "true");
    var labels = {
        status: "状态", state: "工单状态", answer: "说明", material: "物料", materials: "物料列表", operations: "工序列表", operation: "工序", routing: "工艺路线", steps: "工序顺序", orders: "工单列表", workOrder: "工单",
        orderCode: "工单编号", code: "编码", name: "名称", unit: "单位", quantity: "计划数量", plannedQuantity: "计划数量", materialCode: "物料编码", materialName: "物料名称", materialType: "物料类型", operationCode: "工序编码", operationName: "工序名称",
        sequence: "顺序", orderNo: "顺序", order: "顺序", flowCode: "路线编码", flowName: "路线名称", routeCode: "路线编码", flowId: "路线记录", id: "记录编号", model: "型号", size: "规格",
        createdAt: "创建时间", updatedAt: "更新时间", startedAt: "开工时间", completedAt: "完工时间", version: "版本", routeSnapshot: "工艺快照", total: "数量", limit: "最多显示", orderStatus: "工单状态", snapshot: "工艺快照", source: "来源", mode: "查询方式", truncated: "还有后续结果", duplicate: "已存在，未重复提交",
        page: "当前页", pageSize: "每页条数", count: "本页数量", hasMore: "还有下一页", requiresSelection: "需明确选择候选", routings: "当前关联路线", businessResults: "各模块查询结果", operationId: "工序记录", relationId: "路线工序关系", workOrderCode: "工单编号", workOrderState: "工单状态", comparisonAvailable: "可完成对比", hasChanges: "存在已证实差异", potentialProductionImpact: "存在潜在生产影响", requiresProcessReview: "需工艺人员复核", snapshotSteps: "工单快照", currentSteps: "当前关联路线工序", changes: "差异明细", limitations: "无法确认的内容", impactAssessment: "生产影响评估", snapshotRouting: "快照路线", currentRouting: "当前路线", comparisonBasis: "对比依据", queryMode: "查询流程", materialStatus: "物料状态"
    };
    var states = { CREATED: "待开工", IN_PROGRESS: "进行中", COMPLETED: "已完成", CANCELLED: "已取消" };
    var changeLabels = { ADDED: "工序新增", REMOVED: "工序删除", ORDER_CHANGED: "顺序调整", CODE_CHANGED: "编码变更", NAME_CHANGED: "名称变更", ROUTING_CHANGED: "关联路线变更" };
    function node(tag, text) { var result = document.createElement(tag); if (text !== undefined) result.textContent = String(text); return result; }
    function describeStep(value) { return value ? [value.order, value.code, value.name].filter(function (part) { return part !== undefined && part !== null; }).join(" · ") : "—"; }
    function comparison(value, depth) {
        var card = node("div"); card.setAttribute("class", "comparison");
        card.appendChild(render({ workOrderCode: value.workOrderCode, materialCode: value.materialCode, workOrderState: value.workOrderState, hasChanges: value.hasChanges }, depth + 1));
        card.appendChild(node("h3", "工单快照")); card.appendChild(render(value.snapshotSteps, depth + 1));
        card.appendChild(node("h3", "当前关联路线")); card.appendChild(render(value.currentSteps, depth + 1));
        card.appendChild(node("h3", "差异明细"));
        if (!value.changes || !value.changes.length) card.appendChild(node("p", "本次可比较字段未发现差异。"));
        else {
            var table = node("table"), head = node("tr");
            ["变更", "工单快照", "当前配置"].forEach(function (label) { head.appendChild(node("th", label)); }); table.appendChild(head);
            value.changes.forEach(function (change) {
                var row = node("tr"); [changeLabels[change.type] || change.type, describeStep(change.before), describeStep(change.after)].forEach(function (text) { row.appendChild(node("td", text)); }); table.appendChild(row);
            }); card.appendChild(table);
        }
        card.appendChild(node("p", value.impactAssessment || "生产影响需要工艺人员确认。"));
        card.appendChild(node("h3", "无法确认的内容")); card.appendChild(render(value.limitations || [], depth + 1));
        if (value.source) card.appendChild(node("p", "来源：" + value.source));
        return card;
    }
    function render(value, depth) {
        if (value === null || value === undefined) return node("span", "—");
        if (depth > 8) return node("span", "结果层级过深，请缩小查询范围。");
        if (Array.isArray(value)) {
            if (!value.length) return node("p", "暂无记录。");
            var list = node("ol"); value.forEach(function (item) { var row = node("li"); row.appendChild(render(item, depth + 1)); list.appendChild(row); }); return list;
        }
        if (typeof value === "object") {
            if (value.comparisonAvailable === true && Array.isArray(value.snapshotSteps) && Array.isArray(value.currentSteps)) return comparison(value, depth);
            var list = node("dl");
            Object.keys(value).forEach(function (key) { list.appendChild(node("dt", labels[key] || key)); var item = node("dd"); item.appendChild(render(value[key], depth + 1)); list.appendChild(item); });
            return list;
        }
        return node("span", typeof value === "boolean" ? value ? "是" : "否" : states[value] || changeLabels[value] || value);
    }
    Array.prototype.forEach.call(root.querySelectorAll("form[data-endpoint]"), function (form) {
        var busy = false, output = document.getElementById(form.getAttribute("data-result"));
        form.addEventListener("submit", function (event) {
            event.preventDefault(); if (busy) return;
            var data = {};
            Array.prototype.forEach.call(form.querySelectorAll("[name]"), function (input) { var value = input.value.trim(); if (value) data[input.getAttribute("name")] = value; });
            var method = form.getAttribute("data-method"), buttons = form.querySelectorAll("button");
            busy = true; Array.prototype.forEach.call(buttons, function (button) { button.disabled = true; });
            output.textContent = method === "GET" ? "正在查询…" : "正在提交…"; output.setAttribute("aria-busy", "true");
            var options = { url: root.getAttribute("data-api") + form.getAttribute("data-endpoint"), type: method, dataType: "json", headers: { "X-Requested-With": "XMLHttpRequest" }, data: method === "GET" ? data : JSON.stringify(data), timeout: 35000 };
            if (method !== "GET") options.contentType = "application/json; charset=UTF-8";
            window.jQuery.ajax(options).done(function (response) {
                output.textContent = "";
                if (response && response.data) output.appendChild(render(response.data, 0));
                else output.textContent = response && response.msg || "没有取得结果，请重试。";
            }).fail(function (xhr, reason) {
                var data = xhr.responseJSON && xhr.responseJSON.data;
                output.textContent = "服务暂时不可用，请稍后重试。";
                if (xhr.status === 401) output.textContent = "登录已过期，请重新登录。";
                else if (xhr.status === 403) output.textContent = "没有此操作所需权限，请联系管理员。";
                else if (reason === "timeout" || xhr.status === 504) output.textContent = "请求超时，请先查询工单当前状态后再决定是否重试。";
                else if (data) { output.textContent = ""; output.appendChild(render(data, 0)); }
                else if (xhr.responseJSON && xhr.responseJSON.msg) output.textContent = xhr.responseJSON.msg;
            }).always(function () {
                busy = false; output.setAttribute("aria-busy", "false"); Array.prototype.forEach.call(buttons, function (button) { button.disabled = false; });
            });
        });
    });
})();
