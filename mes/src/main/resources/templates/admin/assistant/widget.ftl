<div id="mes-ai-widget" class="mes-ai-widget" data-api="${request.contextPath}/admin/assistant/chat">
    <button id="mes-ai-launcher" class="mes-ai-launcher" type="button" aria-controls="mes-ai-panel" aria-expanded="false">
        <i class="fa fa-comments-o" aria-hidden="true"></i> MES 智能助手
    </button>
    <section id="mes-ai-panel" class="mes-ai-panel" aria-labelledby="mes-ai-title" hidden>
        <header class="mes-ai-header">
            <div><strong id="mes-ai-title">MES 智能助手</strong><p>工艺查询 · PDF资料与页码引用</p></div>
            <div class="mes-ai-actions">
                <button id="mes-ai-clear" type="button">清空</button>
                <button id="mes-ai-close" type="button" aria-label="关闭助手">关闭</button>
            </div>
        </header>
        <div id="mes-ai-messages" class="mes-ai-messages" role="log" aria-live="polite" aria-relevant="additions"></div>
        <div class="mes-ai-shortcuts">
            <button type="button" data-question="我能做什么？">当前能力</button>
            <button type="button" data-question="如何查询物料工艺路线？">查询方法</button>
            <button type="button" data-question="质量管理数字化文档把什么作为推进发展的主线？">检索质量文档</button>
        </div>
        <form id="mes-ai-form" class="mes-ai-form">
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
