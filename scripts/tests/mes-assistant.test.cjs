// Tests the shipped browser script with a small DOM and controllable AJAX transport.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const source = fs.readFileSync(path.join(__dirname, '../../mes/src/main/resources/static/js/mes-assistant.js'), 'utf8');

class Element {
  constructor(tag = 'div') { this.tagName = tag; this.children = []; this.attributes = {}; this.listeners = {}; this.hidden = false; this.value = ''; this.text = ''; }
  set innerHTML(_) { throw new Error('Untrusted HTML rendering is forbidden'); }
  get textContent() { return this.text + this.children.map(x => x.textContent).join(''); }
  set textContent(value) { this.text = String(value); this.children = []; }
  get firstChild() { return this.children[0] || null; }
  appendChild(child) { child.parentNode = this; this.children.push(child); return child; }
  removeChild(child) { this.children.splice(this.children.indexOf(child), 1); child.parentNode = null; }
  setAttribute(key, value) { this.attributes[key] = String(value); }
  getAttribute(key) { return this.attributes[key] ?? null; }
  addEventListener(type, listener) { (this.listeners[type] ||= []).push(listener); }
  emit(type, props = {}) { const event = { target: this, preventDefault() { this.defaultPrevented = true; }, ...props }; (this.listeners[type] || []).forEach(fn => fn(event)); return event; }
  focus() { this.focused = true; if (this.ownerDocument) this.ownerDocument.activeElement = this; }
  select() {}
  querySelectorAll(selector) { return walk(this).filter(node => selector === '[data-question]' ? node.getAttribute('data-question') !== null : node.tagName === selector); }
}
function walk(node) { return node.children.flatMap(x => [x, ...walk(x)]); }
function setup() {
  const names = ['widget', 'launcher', 'panel', 'close', 'clear', 'form', 'input', 'send', 'messages', 'feedback'];
  const nodes = Object.fromEntries(names.map(name => [name, new Element()]));
  nodes.panel.hidden = true;
  nodes.widget.setAttribute('data-api', '/admin/assistant/chat');
  names.filter(name => name !== 'widget').forEach(name => nodes.widget.appendChild(nodes[name]));
  const document = new Element('document');
  Object.values(nodes).forEach(element => { element.ownerDocument = document; });
  document.body = document;
  document.appendChild(nodes.widget);
  document.getElementById = id => nodes[id.replace('mes-ai-', '')] || null;
  document.createElement = tag => { const element = new Element(tag); element.ownerDocument = document; return element; };
  document.createTextNode = text => { const el = new Element('#text'); el.textContent = text; return el; };
  document.execCommand = () => true;
  const requests = [], copied = [];
  const ajax = options => {
    const handlers = {};
    const xhr = { options, status: 0,
      done(fn) { handlers.done = fn; return this; },
      fail(fn) { handlers.fail = fn; return this; },
      always(fn) { handlers.always = fn; return this; },
      resolve(data) { handlers.done?.(data); handlers.always?.(); },
      reject(status, reason) { this.status = status; handlers.fail?.(this, reason); handlers.always?.(); },
      abort() { this.aborted = true; this.reject(0, 'abort'); }
    };
    requests.push(xhr); return xhr;
  };
  const navigator = { clipboard: { writeText(value) { copied.push(value); return Promise.resolve(); } } };
  const window = { jQuery: { ajax }, navigator, isSecureContext: true };
  const context = vm.createContext({ document, window, navigator, console, setTimeout, clearTimeout });
  const run = () => vm.runInContext(source, context);
  run();
  const submit = text => { nodes.input.value = text; nodes.form.emit('submit'); };
  return { nodes, document, requests, copied, run, submit };
}

test('opens and closes a single initialized drawer without duplicate requests', () => {
  const app = setup(); app.run();
  app.nodes.launcher.emit('click'); assert.equal(app.nodes.panel.hidden, false);
  app.submit('100112'); assert.equal(app.requests.length, 1);
  app.nodes.form.emit('submit'); assert.equal(app.requests.length, 1);
  assert.equal(app.requests[0].options.headers['X-Requested-With'], 'XMLHttpRequest');
  assert.equal(app.requests[0].options.timeout, 15000);
  app.document.emit('keydown', { key: 'Escape' }); assert.equal(app.nodes.panel.hidden, true);
});

test('IME Enter and Shift+Enter preserve input, ordinary Enter sends once', () => {
  const app = setup(); app.nodes.input.value = '100112';
  app.nodes.input.emit('compositionstart');
  app.nodes.input.emit('keydown', { key: 'Enter', keyCode: 229 });
  assert.equal(app.requests.length, 0);
  app.nodes.input.emit('compositionend');
  app.nodes.input.emit('keydown', { key: 'Enter', shiftKey: true });
  app.nodes.input.emit('keydown', { key: 'Enter', isComposing: true });
  assert.equal(app.requests.length, 0);
  app.nodes.input.emit('keydown', { key: 'Enter' }); assert.equal(app.requests.length, 1);
});

test('untrusted user, database and evidence strings remain text and can be copied', async () => {
  const app = setup(), attack = '<img src=x onerror=alert(1)>';
  app.submit(attack);
  app.requests[0].resolve({ code: 0, data: { status: 'SUCCESS', mode: 'database', answer: attack,
    material: { id: 'm1', code: 'MAT-1', name: attack }, routing: { id: 'r1', code: 'ROUTE-1', name: attack },
    steps: [{ relationId: 's1', operationId: 'o1', order: 1, code: 'OP-1', name: attack }],
    source: attack, queriedAt: '2026-10-26T00:00:00Z', traceId: 'trace-test', elapsedMs: 8 } });
  assert.ok(app.nodes.messages.textContent.includes(attack));
  assert.ok(app.nodes.messages.textContent.includes('OP-1'));
  assert.ok(app.nodes.messages.textContent.includes('trace-test'));
  assert.equal(walk(app.nodes.messages).some(x => x.tagName === 'img'), false);
  const copy = walk(app.nodes.messages).filter(x => x.tagName === 'button' && x.textContent === '复制回答').at(-1);
  assert.ok(copy); copy.emit('click'); await Promise.resolve();
  assert.ok(app.copied[0].includes('OP-1')); assert.ok(app.copied[0].includes('trace-test'));
});

test('clear aborts in-flight work and ignores stale replies without replaying any scenario', () => {
  const app = setup(); app.submit('100112'); const old = app.requests[0];
  app.nodes.clear.emit('click'); assert.equal(old.aborted, true);
  assert.equal(app.nodes.messages.textContent, ''); assert.equal(app.nodes.send.disabled, false);
  app.submit('不存在');
  old.resolve({ code: 0, data: { answer: '迟到的数据' } });
  assert.equal(app.nodes.messages.textContent.includes('迟到的数据'), false);
  assert.equal(app.nodes.send.disabled, true);
  app.requests[1].resolve({ code: 0, data: { status: 'NO_MATERIAL', answer: '未找到可用物料' } });
  assert.ok(app.nodes.messages.textContent.includes('未找到可用物料'));
  assert.equal(app.nodes.send.disabled, false);
});

for (const [status, reason, hint] of [[401, 'error', '登录'], [403, 'error', '权限'], [0, 'timeout', '超时'], [503, 'error', '不可用']]) {
  test(`request failure ${status}/${reason} explains recovery and allows retry`, () => {
    const app = setup(); app.submit('100112'); app.requests[0].reject(status, reason);
    assert.ok(app.nodes.messages.textContent.includes(hint));
    assert.equal(app.nodes.send.disabled, false);
    app.submit('100112'); assert.equal(app.requests.length, 2);
  });
}

test('rejects overlong text without truncating or sending it', () => {
  const app = setup(); app.submit('A'.repeat(1001));
  assert.equal(app.requests.length, 0); assert.ok(app.nodes.feedback.textContent.includes('1000'));
});

test('reply completion preserves focus in an underlying MES form', () => {
  const app = setup(); app.nodes.launcher.emit('click'); app.submit('100112');
  const businessInput = app.document.createElement('input');
  app.document.appendChild(businessInput); businessInput.focus();
  app.requests[0].resolve({ code: 0, data: { status: 'NO_MATERIAL', answer: '未找到物料' } });
  assert.ok(app.document.activeElement === businessInput, 'Reply must preserve focus outside the drawer');
});
