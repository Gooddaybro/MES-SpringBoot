const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const script = path.join(__dirname, '../../mes/src/main/resources/static/js/mes-manufacturing.js');

class Element {
  constructor(tag = 'div') { this.tagName = tag; this.children = []; this.attrs = {}; this.listeners = {}; this.text = ''; this.value = ''; }
  set innerHTML(_) { throw Error('Untrusted HTML is forbidden'); }
  set textContent(value) { this.text = String(value); this.children = []; }
  get textContent() { return this.text + this.children.map(x => x.textContent).join(''); }
  appendChild(child) { this.children.push(child); child.parentNode = this; return child; }
  getAttribute(key) { return this.attrs[key] ?? null; }
  setAttribute(key, value) { this.attrs[key] = String(value); }
  addEventListener(type, handler) { (this.listeners[type] ||= []).push(handler); }
  emit(type) { for (const handler of this.listeners[type] || []) handler({ preventDefault() {} }); }
  querySelectorAll(selector) { return selector === '[name]' ? this.children.filter(x => x.getAttribute('name')) : this.children.filter(x => x.tagName === 'button'); }
}
function setup() {
  const root = new Element(), results = {}, forms = {}, requests = [];
  root.setAttribute('data-api', '/admin/manufacturing');
  for (const [id, endpoint, method, fields] of [
    ['material', '/materials/search', 'GET', ['materialCode', 'materialName', 'materialStatus', 'page', 'pageSize']], ['operations', '/operations/search', 'GET', ['operationCode', 'operationName', 'materialCode', 'page', 'pageSize']],
    ['routing', '/routing', 'GET', ['materialCode']], ['orders', '/orders/search', 'GET', ['workOrderCode', 'materialCode', 'state', 'createdFrom', 'createdTo', 'page', 'pageSize']],
    ['impact', '/operation-impact', 'GET', ['operationCode', 'operationName', 'state', 'page', 'pageSize']],
    ['comparison', '/orders/compare-routing', 'GET', ['workOrderCode']],
    ['create', '/orders', 'POST', ['orderCode', 'materialCode', 'quantity']],
    ['transition', '/orders/transition', 'POST', ['orderCode', 'action']]
  ]) {
    const form = new Element('form'); form.setAttribute('data-endpoint', endpoint); form.setAttribute('data-method', method); form.setAttribute('data-result', id);
    form.fields = {};
    fields.forEach(name => { const input = new Element('input'); input.setAttribute('name', name); form.fields[name] = input; form.appendChild(input); });
    form.button = form.appendChild(new Element('button')); forms[id] = form; results[id] = new Element();
  }
  root.querySelectorAll = () => Object.values(forms);
  const document = { getElementById: id => id === 'mes-manufacturing' ? root : results[id], createElement: tag => new Element(tag) };
  const window = { jQuery: { ajax(options) {
    const handlers = {}, request = { options,
      done(fn) { handlers.done = fn; return request; }, fail(fn) { handlers.fail = fn; return request; }, always(fn) { handlers.always = fn; return request; },
      resolve(value) { handlers.done?.(value); handlers.always?.(); }, reject(status, data) { handlers.fail?.({ status, responseJSON: data }, 'error'); handlers.always?.(); }
    }; requests.push(request); return request;
  } } };
  const context = vm.createContext({ document, window, console });
  vm.runInContext(fs.existsSync(script) ? fs.readFileSync(script, 'utf8') : '', context);
  return { forms, results, requests };
}

test('read forms send bounded query context without creating or transitioning an order', () => {
  const app = setup(); app.forms.operations.fields.materialCode.value = 'MAT-1'; app.forms.operations.emit('submit');
  assert.equal(app.requests.length, 1); assert.equal(app.requests[0].options.type, 'GET');
  assert.equal(app.requests[0].options.url, '/admin/manufacturing/operations/search');
  assert.equal(app.requests[0].options.data.materialCode, 'MAT-1');
  assert.equal(app.requests[0].options.data.operationCode, undefined);
  app.requests[0].resolve({ code: 0, data: { status: 'SUCCESS', operations: [{ code: 'OP-1', name: '加工' }] } });
  assert.equal(app.requests.length, 1); assert.ok(app.results.operations.textContent.includes('OP-1'));
});

test('order writes require an explicit submit and preserve decimal input in JSON', () => {
  const app = setup(); const form = app.forms.create;
  form.fields.orderCode.value = 'WO-1'; form.fields.materialCode.value = 'MAT-1'; form.fields.quantity.value = '2.125';
  assert.equal(app.requests.length, 0); form.emit('submit'); form.emit('submit');
  assert.equal(app.requests.length, 1); assert.equal(form.button.disabled, true);
  const options = app.requests[0].options;
  assert.equal(options.type, 'POST'); assert.match(options.contentType, /application\/json/);
  assert.deepEqual(JSON.parse(options.data), { orderCode: 'WO-1', materialCode: 'MAT-1', quantity: '2.125' });
  app.requests[0].resolve({ code: 0, data: { status: 'SUCCESS', answer: '已创建' } });
  assert.equal(form.button.disabled, false); assert.equal(app.requests.length, 1);
});

test('order transitions send the chosen action only on button submit', () => {
  const app = setup(), form = app.forms.transition;
  form.fields.orderCode.value = 'WO-1'; form.fields.action.value = 'START';
  form.fields.action.emit('change'); assert.equal(app.requests.length, 0);
  form.emit('submit'); assert.equal(app.requests[0].options.url, '/admin/manufacturing/orders/transition');
  assert.deepEqual(JSON.parse(app.requests[0].options.data), { orderCode: 'WO-1', action: 'START' });
});

test('result text and permission failures are safe, readable, and allow retry', () => {
  const app = setup(), attack = '<img src=x onerror=alert(1)>';
  app.forms.material.emit('submit'); app.requests[0].resolve({ code: 0, data: { status: 'SUCCESS', materials: [{ name: attack }] } });
  assert.ok(app.results.material.textContent.includes(attack));
  app.forms.material.emit('submit'); app.requests[1].reject(403);
  assert.ok(app.results.material.textContent.includes('权限')); assert.equal(app.forms.material.button.disabled, false);
});

test('search and dependency forms only submit filters and never trigger writes', () => {
  const app = setup(), form = app.forms.impact;
  form.fields.operationName.value = '老化测试'; form.fields.state.value = 'CREATED'; form.fields.page.value = '2';
  form.emit('submit');
  assert.equal(app.requests.length, 1); assert.equal(app.requests[0].options.type, 'GET');
  assert.equal(app.requests[0].options.data.operationName, '老化测试');
  assert.equal(app.requests[0].options.data.page, '2');
});

test('snapshot changes show before and after with readable labels and escaped database names', () => {
  const app = setup(), attack = '<img src=x onerror=alert(1)>';
  app.forms.comparison.fields.workOrderCode.value = 'WO-1'; app.forms.comparison.emit('submit');
  app.requests[0].resolve({ code: 0, data: { status: 'SUCCESS', comparisonAvailable: true, workOrderCode: 'WO-1',
    materialCode: 'MAT-1', workOrderState: 'CREATED', hasChanges: true,
    snapshotSteps: [{ code: 'OP-1', name: '装配', order: 1 }], currentSteps: [{ code: 'OP-2', name: attack, order: 1 }],
    changes: [{ type: 'ADDED', after: { code: 'OP-2', name: attack, order: 1 }, before: null }],
    limitations: ['历史快照没有温度参数，无法比较。'], impactAssessment: '需要工艺人员确认。' } });
  const text = app.results.comparison.textContent;
  assert.ok(text.includes('工单快照') && text.includes('当前关联路线') && text.includes('工序新增'));
  assert.ok(text.includes(attack) && text.includes('无法比较'));
  assert.equal(app.requests.length, 1);
});
