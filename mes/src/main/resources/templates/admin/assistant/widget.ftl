<div id="mes-ai-widget" class="mes-ai-widget" data-api="${request.contextPath}/admin/assistant/chat">
    <button id="mes-ai-launcher" class="mes-ai-launcher" type="button" aria-controls="mes-ai-panel" aria-expanded="false">
        <i class="fa fa-comments-o" aria-hidden="true"></i> MES 智能助手
    </button>
    <section id="mes-ai-panel" class="mes-ai-panel" aria-labelledby="mes-ai-title" hidden>
        <header class="mes-ai-header">
            <div><strong id="mes-ai-title">MES 智能助手</strong><p>工艺查询 · 规则优先，模型按配置辅助</p></div>
            <div class="mes-ai-actions">
                <button id="mes-ai-clear" type="button">清空</button>
                <button id="mes-ai-close" type="button" aria-label="关闭助手">关闭</button>
            </div>
        </header>
        <div id="mes-ai-messages" class="mes-ai-messages" role="log" aria-live="polite" aria-relevant="additions"></div>
        <div class="mes-ai-shortcuts">
            <button type="button" data-question="我能做什么？">当前能力</button>
            <button type="button" data-question="如何查询物料工艺路线？">查询方法</button>
            <button type="button" data-question="BOM、报工和知识库接入了吗？">后续能力</button>
        </div>
        <form id="mes-ai-form" class="mes-ai-form">
            <label for="mes-ai-input">物料编码或问题</label>
            <div class="mes-ai-composer">
                <textarea id="mes-ai-input" name="message" rows="3" maxlength="1000"
                          placeholder="输入库中物料编码，或询问该物料的工艺路线"></textarea>
                <button id="mes-ai-send" type="submit">发送</button>
            </div>
        </form>
        <p id="mes-ai-feedback" class="mes-ai-feedback" role="status" aria-live="polite"></p>
        <p class="mes-ai-hint">Enter 发送 · Shift+Enter 换行 · 只读查询<br>模型补位可能发送本次问题；不发送数据库结果，请勿填写敏感信息。</p>
    </section>
</div>
