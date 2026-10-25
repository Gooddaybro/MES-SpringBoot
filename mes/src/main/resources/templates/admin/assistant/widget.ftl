<div id="mes-ai-widget" class="mes-ai-widget" data-api="${request.contextPath}/admin/assistant/chat">
  <button id="mes-ai-launcher" class="mes-ai-launcher" type="button" aria-controls="mes-ai-panel" aria-expanded="false">
    <i class="fa fa-comments-o" aria-hidden="true"></i> 智能助手
  </button>
  <section id="mes-ai-panel" class="mes-ai-panel" aria-label="MES 工艺查询助手" hidden>
    <header class="mes-ai-header">
      <div><strong>MES 智能助手</strong><small>工艺查询版 · 暂未接入大模型</small></div>
      <button type="button" id="mes-ai-close" aria-label="关闭助手">&times;</button>
    </header>
    <div id="mes-ai-messages" class="mes-ai-messages" role="log" aria-live="polite">
      <div class="mes-ai-message mes-ai-bot">你好！我可以按物料编码查询数据库中的真实工艺路线，不会编造工序。</div>
    </div>
    <div class="mes-ai-shortcuts">
      <button type="button" data-question="我能做什么？">我能做什么？</button>
      <button type="button" data-question="工艺路线和工单有什么区别？">工艺路线与工单</button>
      <button type="button" data-question="如何查询物料工艺路线？">怎样查询？</button>
    </div>
    <form id="mes-ai-form" class="mes-ai-form">
      <label for="mes-ai-input" class="mes-ai-sr-only">输入问题</label>
      <textarea id="mes-ai-input" name="message" rows="2" maxlength="200"
        placeholder="物料编码为 MAT-001 的工序有哪些？（请使用真实编码）"></textarea>
      <button id="mes-ai-send" type="submit" aria-label="发送消息"><i class="fa fa-paper-plane"></i></button>
    </form>
    <p class="mes-ai-hint">Enter 发送 · Shift+Enter 换行 · 只读查询</p>
  </section>
</div>