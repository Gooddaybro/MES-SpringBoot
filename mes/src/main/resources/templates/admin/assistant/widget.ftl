<div id="mes-ai-widget" class="mes-ai-widget" data-api="${request.contextPath}/admin/assistant/chat" data-multi-api="${request.contextPath}/admin/assistant/multi/chat">
    <button id="mes-ai-launcher" class="mes-ai-launcher" type="button" aria-controls="mes-ai-panel" aria-expanded="false">
        <i class="fa fa-comments-o" aria-hidden="true"></i> MES 智能助手
    </button>
    <section id="mes-ai-panel" class="mes-ai-panel" aria-labelledby="mes-ai-title" hidden>
        <header class="mes-ai-header">
            <div><strong id="mes-ai-title">MES 智能助手</strong><p>单 Agent / 四业务多 Agent</p></div>
            <div class="mes-ai-actions">
                <button id="mes-ai-clear" type="button">清空</button>
                <button id="mes-ai-close" type="button" aria-label="关闭助手">关闭</button>
            </div>
        </header>
        <div class="mes-ai-mode"><label for="mes-ai-mode">查询模式</label>
            <select id="mes-ai-mode"><option value="single">单 Agent · 工艺与知识</option><option value="multi">多 Agent · 物料 / 工序 / 路线 / 工单</option></select>
            <a href="${request.contextPath}/admin/manufacturing" target="_blank" rel="noopener">业务工作台</a>
        </div>
        <div id="mes-ai-messages" class="mes-ai-messages" role="log" aria-live="polite" aria-relevant="additions"></div>
        <div class="mes-ai-shortcuts">
            <button type="button" data-question="我能做什么？">当前能力</button>
            <button type="button" data-question="如何查询物料工艺路线？">查询方法</button>
            <button type="button" data-question="质量管理数字化文档把什么作为推进发展的主线？">检索质量文档</button>
        </div>
        <form id="mes-ai-form" class="mes-ai-form">
            <details id="mes-ai-context" class="mes-ai-context" hidden><summary>补充查询条件（可选）</summary>
                <label for="mes-ai-material-code">物料编码</label><input id="mes-ai-material-code" maxlength="255" autocomplete="off">
                <label for="mes-ai-operation-code">工序编码</label><input id="mes-ai-operation-code" maxlength="255" autocomplete="off">
                <label for="mes-ai-work-order-code">工单编号</label><input id="mes-ai-work-order-code" maxlength="64" autocomplete="off">
                <label for="mes-ai-material-name">物料名称（模糊搜索）</label><input id="mes-ai-material-name" maxlength="100" autocomplete="off">
                <label for="mes-ai-operation-name">工序名称（模糊搜索）</label><input id="mes-ai-operation-name" maxlength="100" autocomplete="off">
                <label for="mes-ai-state">工单状态</label><select id="mes-ai-state"><option value="">全部状态</option><option value="CREATED">已创建 / 待开工</option><option value="IN_PROGRESS">进行中</option><option value="COMPLETED">已完成</option><option value="CANCELLED">已取消</option></select>
                <label for="mes-ai-created-from">创建时间起点（包含）</label><input id="mes-ai-created-from" type="datetime-local">
                <label for="mes-ai-created-to">创建时间终点（不包含）</label><input id="mes-ai-created-to" type="datetime-local">
                <label for="mes-ai-page">页码</label><input id="mes-ai-page" type="number" min="1" max="1000" placeholder="默认1">
                <label for="mes-ai-page-size">每页条数</label><input id="mes-ai-page-size" type="number" min="1" max="50" placeholder="默认20，最多50">
            </details>
            <label for="mes-ai-input">物料编码或问题</label>
            <div class="mes-ai-composer">
                <textarea id="mes-ai-input" name="message" rows="3" maxlength="1000"
                          placeholder="输入物料编码，或询问已收录的制造文档"></textarea>
                <button id="mes-ai-send" type="submit">发送</button>
            </div>
        </form>
        <p id="mes-ai-feedback" class="mes-ai-feedback" role="status" aria-live="polite"></p>
        <p class="mes-ai-hint">Enter 发送 · Shift+Enter 换行 · 只读查询<br>知识查询使用相关问题与授权资料；外部向量服务接收检索文本。请勿填写敏感信息。</p>
    </section>
</div>
