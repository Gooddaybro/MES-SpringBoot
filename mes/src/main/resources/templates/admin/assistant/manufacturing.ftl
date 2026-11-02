<!doctype html>
<html lang="zh-CN">
<head>
    <meta charset="UTF-8"><meta name="viewport" content="width=device-width, initial-scale=1">
    <title>MES 四业务工作台</title>
    <#include "/common/common.ftl">
    <style>
        body { margin: 0; background: #f3f7f7; color: #243746; font: 14px/1.65 Arial,"Microsoft YaHei",sans-serif; }
        .manufacturing { max-width: 1150px; margin: auto; padding: 28px 20px; }
        .manufacturing h1 { font-size: 26px; margin: 0 0 8px; } .manufacturing h2 { font-size: 19px; margin: 0 0 14px; }
        .manufacturing .intro { color: #4c656b; margin-bottom: 24px; } .manufacturing a { color: #00786f; text-decoration: underline; }
        .manufacturing .grid { display: grid; grid-template-columns: repeat(auto-fit,minmax(310px,1fr)); gap: 18px; align-items: start; }
        .manufacturing section { padding: 20px; background: #fff; border: 1px solid #dce5e7; border-radius: 10px; }
        .manufacturing label { display: block; margin: 8px 0 3px; } .manufacturing input,.manufacturing select { box-sizing: border-box; width: 100%; padding: 8px; border: 1px solid #b9cdcc; border-radius: 4px; background: #fff; }
        .manufacturing button { margin-top: 12px; padding: 8px 15px; border: 0; border-radius: 4px; background: #007f75; color: #fff; cursor: pointer; }
        .manufacturing button:disabled { opacity: .6; cursor: wait; } .manufacturing :focus-visible { outline: 2px solid #00786f; outline-offset: 2px; }
        .manufacturing .result { margin-top: 16px; max-height: 480px; overflow: auto; overflow-wrap: anywhere; }
        .manufacturing dl { display: grid; grid-template-columns: minmax(70px,auto) 1fr; gap: 4px 10px; margin: 6px 0; } .manufacturing dt { color: #4c656b; } .manufacturing dd { margin: 0; white-space: pre-wrap; }
        .manufacturing ol { padding-left: 24px; } .manufacturing li { padding: 8px 0; border-bottom: 1px solid #e5eded; } .manufacturing details { margin-top: 18px; } .manufacturing summary { cursor: pointer; color: #00786f; }
    </style>
</head>
<body>
<main id="mes-manufacturing" class="manufacturing" data-api="${request.contextPath}/admin/manufacturing">
    <h1>四业务工作台</h1>
    <p class="intro">查询物料、工序、工艺路线和工单。工单创建时保存当前工艺快照，状态变更需单独提交。<a href="${request.contextPath}/admin">返回 MES</a></p>
    <div class="grid">
        <section><h2>物料</h2><p>不填编码时显示最多20条正常物料。<a href="${request.contextPath}/basedata/materile/list-ui">物料管理</a></p>
            <form data-endpoint="/material" data-method="GET" data-result="material-result">
                <label for="material-code">物料编码（可选）</label><input id="material-code" name="materialCode" maxlength="255" autocomplete="off">
                <button type="submit">查询物料</button>
            </form><div id="material-result" class="result" role="status" aria-live="polite"></div>
        </section>
        <section><h2>工序</h2><p>可按工序编码查询，或查看某物料使用的工序。</p>
            <form data-endpoint="/operations" data-method="GET" data-result="operations-result">
                <label for="operation-code">工序编码（可选）</label><input id="operation-code" name="operationCode" maxlength="255" autocomplete="off">
                <label for="operation-material">物料编码（可选）</label><input id="operation-material" name="materialCode" maxlength="255" autocomplete="off">
                <button type="submit">查询工序</button>
            </form><div id="operations-result" class="result" role="status" aria-live="polite"></div>
        </section>
        <section><h2>工艺路线</h2><p><a href="${request.contextPath}/basedata/flow/process/list-ui">工艺路线管理</a></p>
            <form data-endpoint="/routing" data-method="GET" data-result="routing-result">
                <label for="routing-material">物料编码</label><input id="routing-material" name="materialCode" maxlength="255" required autocomplete="off">
                <button type="submit">查询路线与顺序</button>
            </form><div id="routing-result" class="result" role="status" aria-live="polite"></div>
        </section>
        <section><h2>工单</h2>
            <form data-endpoint="/orders" data-method="GET" data-result="orders-result">
                <label for="query-order">工单编号（可选）</label><input id="query-order" name="workOrderCode" maxlength="64" autocomplete="off">
                <label for="query-order-material">物料编码（可选）</label><input id="query-order-material" name="materialCode" maxlength="255" autocomplete="off">
                <button type="submit">查询工单</button>
            </form><div id="orders-result" class="result" role="status" aria-live="polite"></div>
            <details><summary>创建工单</summary>
                <form data-endpoint="/orders" data-method="POST" data-result="create-result">
                    <label for="create-order">新工单编号</label><input id="create-order" name="orderCode" maxlength="64" required autocomplete="off">
                    <label for="create-material">物料编码</label><input id="create-material" name="materialCode" maxlength="255" required autocomplete="off">
                    <label for="create-quantity">计划数量</label><input id="create-quantity" name="quantity" type="number" min="0.001" step="0.001" required>
                    <button type="submit">创建工单</button>
                </form><div id="create-result" class="result" role="status" aria-live="polite"></div>
            </details>
            <details><summary>变更工单状态</summary><p>待开工可开工或取消；进行中可完工。</p>
                <form data-endpoint="/orders/transition" data-method="POST" data-result="transition-result">
                    <label for="transition-order">工单编号</label><input id="transition-order" name="orderCode" maxlength="64" required autocomplete="off">
                    <label for="transition-action">操作</label><select id="transition-action" name="action"><option value="START">开工</option><option value="COMPLETE">完工</option><option value="CANCEL">取消</option></select>
                    <button type="submit">提交状态变更</button>
                </form><div id="transition-result" class="result" role="status" aria-live="polite"></div>
            </details>
        </section>
    </div>
</main>
<script src="${request.contextPath}/js/mes-manufacturing.js"></script>
</body>
</html>
