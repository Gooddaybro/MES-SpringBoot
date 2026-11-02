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
        .manufacturing table { width: 100%; border-collapse: collapse; } .manufacturing th,.manufacturing td { border: 1px solid #dce5e7; padding: 7px; text-align: left; } .manufacturing h3 { font-size: 15px; }
    </style>
</head>
<body>
<main id="mes-manufacturing" class="manufacturing" data-api="${request.contextPath}/admin/manufacturing">
    <h1>四业务工作台</h1>
    <p class="intro">查询物料、工序、工艺路线和工单。工单创建时保存当前工艺快照，状态变更需单独提交。<a href="${request.contextPath}/admin">返回 MES</a></p>
    <div class="grid">
        <section><h2>物料</h2><p>可按名称和状态搜索；多个候选需明确选择编码。<a href="${request.contextPath}/basedata/materile/list-ui">物料管理</a></p>
            <form data-endpoint="/materials/search" data-method="GET" data-result="material-result">
                <label for="material-code">物料编码（可选）</label><input id="material-code" name="materialCode" maxlength="255" autocomplete="off">
                <label for="material-name">物料名称（模糊搜索）</label><input id="material-name" name="materialName" maxlength="100" autocomplete="off">
                <label for="material-status">物料状态</label><select id="material-status" name="materialStatus"><option value="0">正常</option><option value="1">删除</option><option value="2">禁用</option></select>
                <label for="material-page">页码</label><input id="material-page" name="page" type="number" min="1" max="1000" value="1">
                <label for="material-size">每页条数</label><input id="material-size" name="pageSize" type="number" min="1" max="50" value="20">
                <button type="submit">查询物料</button>
            </form><div id="material-result" class="result" role="status" aria-live="polite"></div>
        </section>
        <section><h2>工序</h2><p>可按工序编码查询，或查看某物料使用的工序。</p>
            <form data-endpoint="/operations/search" data-method="GET" data-result="operations-result">
                <label for="operation-code">工序编码（可选）</label><input id="operation-code" name="operationCode" maxlength="255" autocomplete="off">
                <label for="operation-name">工序名称（模糊搜索）</label><input id="operation-name" name="operationName" maxlength="100" autocomplete="off">
                <label for="operation-material">物料编码（可选）</label><input id="operation-material" name="materialCode" maxlength="255" autocomplete="off">
                <label for="operation-page">页码</label><input id="operation-page" name="page" type="number" min="1" max="1000" value="1">
                <label for="operation-size">每页条数</label><input id="operation-size" name="pageSize" type="number" min="1" max="50" value="20">
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
            <form data-endpoint="/orders/search" data-method="GET" data-result="orders-result">
                <label for="query-order">工单编号（可选）</label><input id="query-order" name="workOrderCode" maxlength="64" autocomplete="off">
                <label for="query-order-material">物料编码（可选）</label><input id="query-order-material" name="materialCode" maxlength="255" autocomplete="off">
                <label for="query-order-state">工单状态</label><select id="query-order-state" name="state"><option value="">全部</option><option value="CREATED">已创建 / 待开工</option><option value="IN_PROGRESS">进行中</option><option value="COMPLETED">已完成</option><option value="CANCELLED">已取消</option></select>
                <label for="query-order-from">创建时间起点（包含）</label><input id="query-order-from" name="createdFrom" type="datetime-local">
                <label for="query-order-to">创建时间终点（不包含）</label><input id="query-order-to" name="createdTo" type="datetime-local">
                <label for="query-order-page">页码</label><input id="query-order-page" name="page" type="number" min="1" max="1000" value="1">
                <label for="query-order-size">每页条数</label><input id="query-order-size" name="pageSize" type="number" min="1" max="50" value="20">
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
        <section><h2>按工序查询关联业务</h2><p>工序重名时请补充编码。物料和路线使用当前配置，工单按创建时快照筛选；本操作只查询。</p>
            <form data-endpoint="/operation-impact" data-method="GET" data-result="impact-result">
                <label for="impact-code">工序编码（与名称至少填一项）</label><input id="impact-code" name="operationCode" maxlength="255" autocomplete="off">
                <label for="impact-name">工序名称（模糊搜索）</label><input id="impact-name" name="operationName" maxlength="100" autocomplete="off">
                <label for="impact-state">工单状态</label><select id="impact-state" name="state"><option value="">全部</option><option value="CREATED">已创建 / 待开工</option><option value="IN_PROGRESS">进行中</option><option value="COMPLETED">已完成</option><option value="CANCELLED">已取消</option></select>
                <label for="impact-page">结果页码（各模块独立分页）</label><input id="impact-page" name="page" type="number" min="1" max="1000" value="1">
                <label for="impact-size">每页条数</label><input id="impact-size" name="pageSize" type="number" min="1" max="50" value="20">
                <button type="submit">查询关联路线、物料和工单</button>
            </form><div id="impact-result" class="result" role="status" aria-live="polite"></div>
        </section>
        <section><h2>工单工艺快照对比</h2><p>比较创建时保存的工艺与物料当前关联路线。历史快照保持不变，参数和发布版本以实际记录为准。</p>
            <form data-endpoint="/orders/compare-routing" data-method="GET" data-result="comparison-result">
                <label for="comparison-order">工单编号</label><input id="comparison-order" name="workOrderCode" maxlength="64" required autocomplete="off">
                <button type="submit">对比工艺</button>
            </form><div id="comparison-result" class="result" role="status" aria-live="polite"></div>
        </section>
    </div>
</main>
<script src="${request.contextPath}/js/mes-manufacturing.js"></script>
</body>
</html>
