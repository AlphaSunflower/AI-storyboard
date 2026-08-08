/* ============================================================
 * LLM 网关管理后台 — app.js
 * 零依赖原生实现：登录 + hash 路由 6 视图 + 通用交互组件
 * API 契约：
 *   成功  HTTP 200  body = {"code":0,"message":"success","data":...}
 *   失败  HTTP 4xx/5xx body = {"error":{"message":"<可读中文文案>"}}
 *   未认证 HTTP 401    body = {"error":{"message":"unauthorized"}} → 全局踢回登录
 * ============================================================ */
'use strict';

/* ================= 全局状态 ================= */
const LS_TOKEN_KEY = 'admin_token';          // 登录 token 的 localStorage 键
let channelMap = {};                          // 渠道 id → {id,name,type,enabled,...} 缓存
let channelsLoaded = false;                   // 渠道缓存是否已加载
let selfUsername = null;                      // 当前登录用户名（JWT sub 解码，用于用户页防自锁）
let plainKeyCache = null;                     // 一次性 plainKey，关闭弹层即置空
let modalCloseHandler = null;                 // 弹窗关闭回调（confirm 用）
const logQuery = { page: 1, size: 20, model: '' }; // 日志页查询状态

const ROUTES = ['dashboard', 'channels', 'routes', 'api-keys', 'logs', 'users'];

/* ================= 工具函数 ================= */
const $ = (sel) => document.querySelector(sel);

/** HTML 转义，所有后端数据渲染前必须经过 */
function esc(s) {
  if (s === null || s === undefined) return '';
  return String(s).replace(/[&<>"']/g, (c) => (
    { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]
  ));
}

/** 超长文本截断 */
function trunc(s, n) {
  if (s === null || s === undefined) return '';
  s = String(s);
  return s.length > n ? s.slice(0, n) + '…' : s;
}

/** ISO 时间 → 'YYYY-MM-DD HH:mm' */
function fmtTime(iso) {
  if (!iso) return '-';
  const d = new Date(iso);
  if (isNaN(d.getTime())) return String(iso);
  const p = (n) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`;
}

/** 成功率 0.95 → '95%' */
function fmtRate(r) {
  if (r === null || r === undefined) return '-';
  return Math.round(r * 100) + '%';
}

/** base64url 解码 JWT payload，取 sub（用户名）。失败返回 null（不阻塞流程，由后端 40301 兜底） */
function decodeJwtSub(token) {
  try {
    const parts = String(token).split('.');
    if (parts.length < 2) return null;
    // base64url → base64：'-'/'_' 替换为 '+'/'/'，并按 4 的倍数补 '=' padding
    const b64 = parts[1].replace(/-/g, '+').replace(/_/g, '/');
    const padded = b64 + '='.repeat((4 - b64.length % 4) % 4);
    // 按 UTF-8 字节解码，避免中文用户名（如 sub:"管理员"）被 latin1→UTF-16 误读为乱码
    const bin = atob(padded);
    const bytes = Uint8Array.from(bin, c => c.charCodeAt(0));
    const payload = JSON.parse(new TextDecoder('utf-8').decode(bytes));
    return payload && payload.sub ? payload.sub : null;
  } catch (e) {
    return null;
  }
}

/* ================= API 封装 ================= */

/** 统一错误类型：401 已踢回登录，调用方不应重复 toast */
class AuthError extends Error {
  constructor(message) {
    super(message);
    this.name = 'AuthError';   // Error 子类默认 name 为 'Error'，显式命名便于日志/调试识别
  }
}

/**
 * 请求封装：自动带 Bearer token；非 2xx 或 code!==0 抛 Error(后端 message)
 * 任何 401（非登录请求）→ 清 token → 回登录视图
 */
async function api(path, options = {}) {
  const headers = Object.assign({ 'Content-Type': 'application/json' }, options.headers || {});
  const token = localStorage.getItem(LS_TOKEN_KEY);
  const isLogin = path === '/admin/login';
  if (token && !isLogin) headers['Authorization'] = 'Bearer ' + token;

  let res;
  try {
    res = await fetch(path, Object.assign({}, options, { headers }));
  } catch (e) {
    throw new Error('网络请求失败：' + (e.message || e));
  }

  // 401 全局踢回（登录请求的 401 是业务失败，走下方透传文案）
  if (res.status === 401 && !isLogin) {
    forceLogout('登录已过期，请重新登录');
    throw new AuthError('unauthorized');
  }

  let json = null;
  try { json = await res.json(); } catch (e) { /* 非 JSON 响应 */ }

  if (res.ok && json && json.code === 0) return json.data;
  const msg = (json && json.error && json.error.message) || ('HTTP ' + res.status + ' ' + res.statusText);
  throw new Error(msg);
}

/** 错误统一处理：401 已提示并踢回，其余 toast 透传后端 message */
function handleErr(e) {
  if (e instanceof AuthError) return;
  toast((e && e.message) ? e.message : String(e), 'error');
}

/** 强制登出：清 token → 回登录视图 */
function forceLogout(msg) {
  localStorage.removeItem(LS_TOKEN_KEY);
  selfUsername = null;
  showLoginView();
  if (msg) toast(msg, 'error');
}

/* ================= toast ================= */

/** 成功/失败提示：失败红色边框，文案透传后端 message */
function toast(message, type) {
  if (!message) return;
  const el = document.createElement('div');
  el.className = 'toast ' + (type === 'error' ? 'toast-error' : 'toast-success');
  el.textContent = message;
  $('#toast-box').appendChild(el);
  setTimeout(() => {
    el.classList.add('hide');
    setTimeout(() => el.remove(), 320);
  }, 3200);
}

/* ================= 弹窗系统 ================= */

/** 打开通用弹窗；onClose 在弹窗以任何方式关闭时调用（confirm 用） */
function openModal(title, bodyHtml, footerHtml, onClose) {
  $('#modal-title').textContent = title;
  $('#modal-body').innerHTML = bodyHtml || '';
  $('#modal-footer').innerHTML = footerHtml || '';
  modalCloseHandler = onClose || null;
  $('#modal').classList.add('open');
}

/** 关闭弹窗：清理内容并触发关闭回调 */
function closeModal() {
  if (!$('#modal').classList.contains('open')) return;
  $('#modal').classList.remove('open');
  const cb = modalCloseHandler;
  modalCloseHandler = null;
  $('#modal-body').innerHTML = '';
  $('#modal-footer').innerHTML = '';
  if (cb) cb();
}

/** 表单弹窗底部按钮（取消 + 提交） */
function footerHtml(okText) {
  return '<button type="button" class="btn" data-close>取消</button>'
    + '<button type="button" class="btn btn-primary" id="form-submit">' + esc(okText || '保存') + '</button>';
}

/** Promise 化的确认弹窗：点确定 resolve(true)，取消/遮罩/Esc resolve(false) */
function confirmDialog(message, opts = {}) {
  return new Promise((resolve) => {
    let confirmed = false;
    openModal(
      opts.title || '确认操作',
      '<p class="confirm-text">' + esc(message) + '</p>',
      '<button type="button" class="btn" data-close>取消</button>'
        + '<button type="button" class="btn btn-danger" id="confirm-ok">' + esc(opts.okText || '确定') + '</button>',
      () => resolve(confirmed)
    );
    $('#confirm-ok').onclick = () => { confirmed = true; closeModal(); };
  });
}

/** 表单字段容器 */
function field(label, control) {
  return '<div class="field"><span class="field-label">' + esc(label) + '</span>' + control + '</div>';
}

/** 密码输入 + 小眼睛切换明文 */
function passwordField(id, value, placeholder) {
  return '<div class="pwd-wrap">'
    + '<input class="input pwd-input" id="' + esc(id) + '" type="password" autocomplete="new-password"'
    + ' value="' + esc(value || '') + '" placeholder="' + esc(placeholder || '') + '">'
    + '<button type="button" class="eye-btn" data-eye="' + esc(id) + '" title="显示/隐藏">👁</button>'
    + '</div>';
}

/* ================= 视图切换（hash 路由） ================= */

function currentSection() {
  const h = location.hash.replace(/^#\/?/, '');
  return ROUTES.includes(h) ? h : 'dashboard';
}

/** 路由渲染：未登录一律登录视图；已登录按 hash 显示对应 section 并加载数据 */
function render() {
  if (!localStorage.getItem(LS_TOKEN_KEY)) { showLoginView(); return; }
  showMainView();

  const sec = currentSection();
  document.querySelectorAll('.nav-item').forEach((a) => {
    a.classList.toggle('active', a.dataset.section === sec);
  });
  document.querySelectorAll('.view').forEach((v) => {
    v.classList.toggle('active', v.id === 'view-' + sec);
  });

  const loaders = {
    dashboard: loadDashboard,
    channels: loadChannels,
    routes: loadRoutes,
    'api-keys': loadApiKeys,
    logs: loadLogs,
    users: loadUsers,
  };
  loaders[sec]();
}

function showLoginView() {
  document.body.classList.remove('main-mode');
  document.body.classList.add('login-mode');
  setTimeout(() => { const u = $('#login-username'); if (u) u.focus(); }, 50);
}

function showMainView() {
  document.body.classList.remove('login-mode');
  document.body.classList.add('main-mode');
  $('#sidebar-username').textContent = selfUsername || '-';
}

/* ================= 登录 ================= */

async function doLogin(e) {
  e.preventDefault();
  const username = $('#login-username').value.trim();
  const password = $('#login-password').value;
  if (!username || !password) { toast('请输入用户名和密码', 'error'); return; }

  const btn = $('#login-btn');
  btn.disabled = true;
  btn.textContent = '登录中…';
  try {
    const data = await api('/admin/login', {
      method: 'POST',
      body: JSON.stringify({ username, password }),
    });
    localStorage.setItem(LS_TOKEN_KEY, data.accessToken);
    // 登录响应只有 token：从 JWT sub 解码当前用户名（用户页防自锁用）
    selfUsername = decodeJwtSub(data.accessToken) || username;
    toast('登录成功', 'success');
    if (!location.hash || location.hash === '#/') location.hash = '#/dashboard';
    render();
  } catch (err) {
    handleErr(err);
  } finally {
    btn.disabled = false;
    btn.textContent = '登 录';
  }
}

function doLogout() {
  localStorage.removeItem(LS_TOKEN_KEY);
  selfUsername = null;
  showLoginView();
  toast('已退出登录');
}

/* ================= 渠道缓存 ================= */

/** 拉取渠道列表并刷新缓存（路由页/日志页映射渠道名用） */
async function refreshChannels() {
  const channels = await api('/admin/channels');
  channelMap = {};
  channels.forEach((c) => { channelMap[c.id] = c; });
  channelsLoaded = true;
  return channels;
}

/** 惰性加载渠道缓存（失败静默，调用方各自处理） */
async function ensureChannels() {
  if (!channelsLoaded) await refreshChannels();
  return channelMap;
}

/* ================= 仪表盘 #/dashboard ================= */

const loadingHtml = '<div class="loading">加载中…</div>';

function errHtml(msg) {
  return '<div class="empty">加载失败：' + esc(msg || '未知错误') + '</div>';
}

function emptyHtml(text) {
  return '<div class="empty">' + esc(text || '暂无数据') + '</div>';
}

/** 统计卡片 */
function statCard(label, value, sub) {
  return '<div class="stat-card">'
    + '<div class="stat-label">' + esc(label) + '</div>'
    + '<div class="stat-value">' + esc(String(value)) + '</div>'
    + '<div class="stat-sub">' + esc(sub) + '</div>'
    + '</div>';
}

/** 内联 SVG 柱状图（零依赖手写坐标轴，<title> 悬浮提示） */
function renderTrendChart(container, trend) {
  const W = 720, H = 230, padL = 38, padR = 10, padT = 14, padB = 30;
  const innerW = W - padL - padR;
  const innerH = H - padT - padB;
  const max = Math.max.apply(null, trend.map((t) => t.count || 0).concat([1]));
  const n = trend.length;
  const gap = innerW / n;
  const barW = Math.min(54, gap * 0.55);

  let svg = '<svg viewBox="0 0 ' + W + ' ' + H + '" class="trend-chart" role="img" aria-label="近 7 天调用趋势">';

  // Y 轴网格线（4 等分）+ 刻度
  const steps = 4;
  for (let i = 0; i <= steps; i++) {
    const y = padT + innerH - (innerH * i / steps);
    const val = Math.round(max * i / steps);
    svg += '<line x1="' + padL + '" y1="' + y + '" x2="' + (W - padR) + '" y2="' + y + '" class="grid-line"/>';
    svg += '<text x="' + (padL - 7) + '" y="' + (y + 4) + '" class="axis-label" text-anchor="end">' + val + '</text>';
  }
  // X 轴基线 + 日期标签 + 柱子
  svg += '<line x1="' + padL + '" y1="' + (padT + innerH) + '" x2="' + (W - padR) + '" y2="' + (padT + innerH)
    + '" stroke="#cfc5b0" stroke-width="1"/>';
  trend.forEach((t, i) => {
    const x = padL + gap * i + (gap - barW) / 2;
    const bh = (t.count || 0) === 0 ? 2 : Math.max(2, innerH * (t.count || 0) / max);
    const y = padT + innerH - bh;
    svg += '<rect class="bar" x="' + x.toFixed(1) + '" y="' + y.toFixed(1) + '" width="' + barW.toFixed(1)
      + '" height="' + bh.toFixed(1) + '" rx="4">'
      + '<title>' + esc(t.date) + '：' + (t.count || 0) + ' 次</title></rect>';
    svg += '<text x="' + (x + barW / 2).toFixed(1) + '" y="' + (H - 10) + '" class="axis-label" text-anchor="middle">'
      + esc(t.date) + '</text>';
  });
  svg += '</svg>';
  container.innerHTML = svg;
}

async function loadDashboard() {
  const view = $('#view-dashboard');
  view.innerHTML = loadingHtml;
  try {
    const s = await api('/admin/stats/overview');
    const hasCalls = (s.calls && s.calls.total > 0);
    const trend = s.trend7d || [];
    const topModels = s.topModels || [];
    const showTrend = hasCalls && trend.length > 0 && trend.some((t) => (t.count || 0) > 0);

    view.innerHTML =
      '<div class="view-head"><h2>仪表盘</h2></div>'
      + '<div class="stat-grid">'
      + statCard('渠道', (s.channels ? s.channels.enabled : 0) + ' / ' + (s.channels ? s.channels.total : 0), '已启用')
      + statCard('模型路由', (s.routes ? s.routes.total : 0), '条路由')
      + statCard('API Key', (s.apiKeys ? s.apiKeys.enabled : 0) + ' / ' + (s.apiKeys ? s.apiKeys.total : 0), '已启用')
      + statCard('用户', (s.users ? s.users.total : 0), '个账号')
      + statCard('今日调用', (s.todayCalls ? s.todayCalls.total : 0), '成功率 ' + fmtRate(s.todayCalls ? s.todayCalls.successRate : null))
      + statCard('累计调用', (s.calls ? s.calls.total : 0), '成功率 ' + fmtRate(s.calls ? s.calls.successRate : null))
      + '</div>'
      + '<div class="card"><h3 class="card-title">近 7 天调用趋势</h3>'
      + '<div id="trend-chart">' + (showTrend ? '' : emptyHtml('暂无调用数据')) + '</div></div>'
      + '<div class="card table-card"><h3 class="card-title">Top 模型</h3>'
      + (topModels.length
        ? '<table class="table"><thead><tr><th>模型</th><th>调用次数</th><th>成功率</th></tr></thead><tbody>'
          + topModels.map((m) => {
            const rate = Math.max(0, Math.min(100, Math.round((m.successRate || 0) * 100)));
            return '<tr>'
              + '<td class="td-strong">' + esc(m.model) + '</td>'
              + '<td>' + esc(m.count) + '</td>'
              + '<td><div class="rate-cell"><div class="rate-track"><div class="rate-fill" style="width:' + rate + '%"></div></div>'
              + '<span class="rate-text">' + fmtRate(m.successRate) + '</span></div></td>'
              + '</tr>';
          }).join('')
          + '</tbody></table>'
        : emptyHtml('暂无调用数据'))
      + '</div>';

    if (showTrend) renderTrendChart($('#trend-chart'), trend);
  } catch (e) {
    view.innerHTML = errHtml(e.message);
    handleErr(e);
  }
}

/* ================= 渠道管理 #/channels ================= */

async function loadChannels() {
  const view = $('#view-channels');
  view.innerHTML = loadingHtml;
  try {
    const channels = await refreshChannels();
    view.innerHTML =
      '<div class="view-head"><h2>渠道管理</h2>'
      + '<button type="button" class="btn btn-primary" data-action="new-channel">+ 新建渠道</button></div>'
      + '<div class="card table-card">'
      + (channels.length
        ? '<table class="table"><thead><tr>'
          + '<th>名称</th><th>类型</th><th>BaseURL</th><th>状态</th><th>优先级</th><th>创建时间</th><th class="td-actions">操作</th>'
          + '</tr></thead><tbody>'
          + channels.map((c) =>
            '<tr>'
            + '<td class="td-strong">' + esc(c.name) + '</td>'
            + '<td><span class="badge badge-type">' + esc(c.type || '-') + '</span></td>'
            + '<td class="td-muted td-url" title="' + esc(c.baseUrl || '') + '">' + esc(trunc(c.baseUrl, 42)) + '</td>'
            + '<td><label class="switch"><input type="checkbox" data-toggle="channel" data-id="' + esc(c.id) + '"'
            + (c.enabled ? ' checked' : '') + '><span class="slider"></span></label></td>'
            + '<td>' + esc(c.priority === null || c.priority === undefined ? 0 : c.priority) + '</td>'
            + '<td class="td-muted">' + fmtTime(c.createdAt) + '</td>'
            + '<td class="td-actions">'
            + '<button type="button" class="btn btn-sm btn-table" data-action="test-channel" data-id="' + esc(c.id) + '">测试</button>'
            + '<button type="button" class="btn btn-sm btn-table" data-action="edit-channel" data-id="' + esc(c.id) + '">编辑</button>'
            + '<button type="button" class="btn btn-sm btn-table btn-table-danger" data-action="del-channel" data-id="' + esc(c.id) + '">删除</button>'
            + '</td></tr>').join('')
          + '</tbody></table>'
        : emptyHtml('暂无渠道，点击右上角「新建渠道」添加'))
      + '</div>';
  } catch (e) {
    view.innerHTML = errHtml(e.message);
    handleErr(e);
  }
}

/** 新建/编辑渠道弹窗（编辑时 apiKey 留空=不更换） */
function openChannelModal(channel) {
  const isEdit = !!channel;
  const body =
    field('名称', '<input class="input" id="f-name" value="' + esc(channel ? channel.name : '') + '" placeholder="如：OpenAI 主渠道">')
    + field('类型',
      '<select class="input" id="f-type">'
      + ['openai_compatible', 'gemini', 'minimax']
        .map((t) => '<option value="' + t + '"' + (channel && channel.type === t ? ' selected' : '') + '>' + t + '</option>').join('')
      + '</select>')
    + field('BaseURL', '<input class="input" id="f-baseurl" value="' + esc(channel ? channel.baseUrl : '') + '" placeholder="https://api.openai.com">')
    + field('API Key', passwordField('f-apikey', '', isEdit ? '留空则不修改' : '必填'))
    + field('优先级', '<input class="input" id="f-priority" type="number" value="'
      + esc(channel && channel.priority !== null && channel.priority !== undefined ? channel.priority : 0) + '" placeholder="数字越小越优先">')
    + '<div class="field field-inline"><span class="field-label">启用</span>'
    + '<label class="switch"><input type="checkbox" id="f-enabled"' + (channel ? (channel.enabled ? ' checked' : '') : ' checked')
    + '><span class="slider"></span></label></div>';

  openModal(isEdit ? '编辑渠道' : '新建渠道', body, footerHtml('保存'));
  $('#form-submit').onclick = () => submitChannel(isEdit ? channel.id : null);
}

async function submitChannel(id) {
  const name = $('#f-name').value.trim();
  const type = $('#f-type').value;
  const baseUrl = $('#f-baseurl').value.trim();
  const apiKey = $('#f-apikey').value;
  const priority = parseInt($('#f-priority').value, 10);
  const enabled = $('#f-enabled').checked;

  if (!name || !baseUrl) { toast('名称和 BaseURL 不能为空', 'error'); return; }
  if (!id && !apiKey) { toast('新建渠道必须填写 API Key', 'error'); return; }

  const body = { name, type, baseUrl, enabled, priority: isNaN(priority) ? 0 : priority };
  if (apiKey) body.apiKey = apiKey;   // 编辑留空 = 不更换 Key

  const btn = $('#form-submit');
  btn.disabled = true;
  try {
    await api(id ? '/admin/channels/' + id : '/admin/channels', {
      method: id ? 'PUT' : 'POST',
      body: JSON.stringify(body),
    });
    closeModal();
    toast(id ? '渠道已更新' : '渠道已创建', 'success');
    loadChannels();
  } catch (e) {
    btn.disabled = false;
    handleErr(e);
  }
}

/** 渠道测试：调 test 端点，结果弹窗 */
async function testChannel(id, btn) {
  const orig = btn.textContent;
  btn.disabled = true;
  btn.textContent = '测试中…';
  try {
    const data = await api('/admin/channels/' + id + '/test', { method: 'POST' });
    showTestResult(data);
  } catch (e) {
    handleErr(e);
  } finally {
    btn.disabled = false;
    btn.textContent = orig;
  }
}

/** 测试结果弹窗：成功绿勾+耗时；失败红叉+error 文案 */
function showTestResult(data) {
  const ok = !!data.ok;
  const icon = ok
    ? '<svg viewBox="0 0 24 24" class="result-icon ok"><path d="M4 12.5l5 5L20 6.5" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"/></svg>'
    : '<svg viewBox="0 0 24 24" class="result-icon fail"><path d="M6 6l12 12M18 6L6 18" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round"/></svg>';
  const statusLine = data.status ? ' · HTTP ' + data.status : '';
  const body =
    '<div class="test-result">'
    + icon
    + '<div class="test-title">' + (ok ? '测试成功' : '测试失败') + '</div>'
    + '<div class="test-meta">耗时 ' + (data.durationMs === null || data.durationMs === undefined ? '-' : data.durationMs) + ' ms' + statusLine + '</div>'
    + (ok ? '' : '<div class="test-error">' + esc(data.error || '未知错误') + '</div>')
    + '</div>';
  openModal('测试结果', body, '<button type="button" class="btn" data-close>关闭</button>');
}

/** 渠道页按钮委托（新建/编辑/删除/测试） */
function onChannelsClick(e) {
  const btn = e.target.closest('button[data-action]');
  if (!btn) return;
  const action = btn.dataset.action;
  const id = btn.dataset.id;
  if (action === 'new-channel') {
    openChannelModal(null);
  } else if (action === 'edit-channel') {
    const ch = channelMap[id];
    if (ch) openChannelModal(ch);
  } else if (action === 'del-channel') {
    deleteChannel(id);
  } else if (action === 'test-channel') {
    testChannel(id, btn);
  }
}

/** 渠道启停 switch：PUT 只传 enabled */
async function onChannelToggle(checkbox) {
  const id = checkbox.dataset.id;
  const enabled = checkbox.checked;
  checkbox.disabled = true;
  try {
    await api('/admin/channels/' + id, { method: 'PUT', body: JSON.stringify({ enabled }) });
    toast(enabled ? '渠道已启用' : '渠道已停用', 'success');
    loadChannels();
  } catch (e) {
    checkbox.checked = !enabled;   // 失败回滚
    checkbox.disabled = false;
    handleErr(e);
  }
}

function onChannelsChange(e) {
  const cb = e.target.closest('input[data-toggle="channel"]');
  if (cb) onChannelToggle(cb);
}

async function deleteChannel(id) {
  const name = channelMap[id] ? channelMap[id].name : id;
  const ok = await confirmDialog('确定删除渠道「' + name + '」吗？删除后不可恢复。', { okText: '删除' });
  if (!ok) return;
  try {
    await api('/admin/channels/' + id, { method: 'DELETE' });
    toast('渠道已删除', 'success');
    loadChannels();
  } catch (e) {
    handleErr(e);
  }
}

/* ================= 模型路由 #/routes ================= */

async function loadRoutes() {
  const view = $('#view-routes');
  view.innerHTML = loadingHtml;
  try {
    await ensureChannels();   // 渠道 id→name 映射（停用渠道灰显）
    const routes = await api('/admin/routes');
    view.innerHTML =
      '<div class="view-head"><h2>模型路由</h2>'
      + '<button type="button" class="btn btn-primary" data-action="new-route">+ 新建路由</button></div>'
      + '<div class="card table-card">'
      + (routes.length
        ? '<table class="table"><thead><tr>'
          + '<th>模型名</th><th>渠道</th><th>默认参数 (JSON)</th><th>创建时间</th><th class="td-actions">操作</th>'
          + '</tr></thead><tbody>'
          + routes.map((r) => {
            const ch = channelMap[r.channelId];
            const channelName = ch ? ch.name + (ch.enabled === false ? '（停用）' : '') : '未知渠道';
            return '<tr>'
              + '<td class="td-strong">' + esc(r.modelName) + '</td>'
              + '<td class="' + (ch && ch.enabled === false ? 'td-muted' : '') + '">' + esc(channelName) + '</td>'
              + '<td class="td-params" title="' + esc(r.defaultParams || '') + '">'
              + (r.defaultParams ? esc(trunc(r.defaultParams, 48)) : '<span class="td-muted">-</span>') + '</td>'
              + '<td class="td-muted">' + fmtTime(r.createdAt) + '</td>'
              + '<td class="td-actions">'
              + '<button type="button" class="btn btn-sm btn-table" data-action="test-route" data-id="' + esc(r.id) + '">测试</button>'
              + '<button type="button" class="btn btn-sm btn-table" data-action="edit-route" data-id="' + esc(r.id) + '">编辑</button>'
              + '<button type="button" class="btn btn-sm btn-table btn-table-danger" data-action="del-route" data-id="' + esc(r.id) + '">删除</button>'
              + '</td></tr>';
          }).join('')
          + '</tbody></table>'
        : emptyHtml('暂无路由，点击右上角「新建路由」添加'))
      + '</div>';
  } catch (e) {
    view.innerHTML = errHtml(e.message);
    handleErr(e);
  }
}

/** 新建/编辑路由弹窗：渠道下拉（停用渠道灰显）+ defaultParams JSON 校验 */
function openRouteModal(route) {
  const isEdit = !!route;
  const options = Object.keys(channelMap).length
    ? Object.values(channelMap)
        .slice().sort((a, b) => (a.priority || 0) - (b.priority || 0))
        .map((c) =>
          '<option value="' + esc(c.id) + '"' + (route && route.channelId === c.id ? ' selected' : '')
          + (c.enabled === false ? ' class="opt-disabled"' : '') + '>'
          + esc(c.name) + (c.enabled === false ? '（停用）' : '') + '</option>'
        ).join('')
    : '<option value="">暂无渠道，请先到渠道管理创建</option>';

  const body =
    field('模型名', '<input class="input" id="r-model" value="' + esc(route ? route.modelName : '') + '" placeholder="如：gpt-image-2">')
    + field('渠道', '<select class="input" id="r-channel">' + options + '</select>')
    + field('默认参数（JSON）', '<textarea class="input" id="r-params" rows="4" placeholder=\'{"temperature":0.7,"size":"1024x1024"}\'>'
      + esc(route ? route.defaultParams || '' : '') + '</textarea>');

  openModal(isEdit ? '编辑路由' : '新建路由', body, footerHtml('保存'));
  $('#form-submit').onclick = () => submitRoute(isEdit ? route.id : null);
}

async function submitRoute(id) {
  const modelName = $('#r-model').value.trim();
  const channelId = $('#r-channel').value;
  const paramsText = $('#r-params').value.trim();

  if (!modelName) { toast('模型名不能为空', 'error'); return; }
  if (!channelId) { toast('请选择渠道（需先在渠道管理创建）', 'error'); return; }

  // JSON 合法性校验：合法才提交
  let defaultParams = null;
  if (paramsText) {
    try {
      JSON.parse(paramsText);
      defaultParams = paramsText;
    } catch (err) {
      toast('默认参数不是合法 JSON：' + (err.message || err), 'error');
      return;
    }
  }

  const body = { modelName, channelId, defaultParams };
  const btn = $('#form-submit');
  btn.disabled = true;
  try {
    await api(id ? '/admin/routes/' + id : '/admin/routes', {
      method: id ? 'PUT' : 'POST',
      body: JSON.stringify(body),
    });
    closeModal();
    toast(id ? '路由已更新' : '路由已创建', 'success');
    loadRoutes();
  } catch (e) {
    btn.disabled = false;
    handleErr(e);
  }
}

/** 路由页按钮委托（新建/编辑/删除/测试） */
function onRoutesClick(e) {
  const btn = e.target.closest('button[data-action]');
  if (!btn) return;
  const action = btn.dataset.action;
  const id = btn.dataset.id;
  if (action === 'new-route') {
    openRouteModal(null);
  } else if (action === 'edit-route') {
    loadRoutesForEdit(id);
  } else if (action === 'del-route') {
    deleteRoute(id);
  } else if (action === 'test-route') {
    testRoute(id, btn);
  }
}

/** 编辑路由：需要完整路由对象，列表行里没有存，重新拉一次（简单可靠） */
async function loadRoutesForEdit(id) {
  try {
    const routes = await api('/admin/routes');
    const route = routes.find((r) => r.id === id);
    if (route) openRouteModal(route);
    else toast('路由不存在，可能已被删除', 'error');
  } catch (e) {
    handleErr(e);
  }
}

/** 路由测试：走真实网关链路（会落一条 CallLog，设计接受） */
async function testRoute(id, btn) {
  const orig = btn.textContent;
  btn.disabled = true;
  btn.textContent = '测试中…';
  try {
    const data = await api('/admin/routes/' + id + '/test', { method: 'POST' });
    showTestResult(data);
  } catch (e) {
    handleErr(e);
  } finally {
    btn.disabled = false;
    btn.textContent = orig;
  }
}

async function deleteRoute(id) {
  const ok = await confirmDialog('确定删除该模型路由吗？删除后不可恢复。', { okText: '删除' });
  if (!ok) return;
  try {
    await api('/admin/routes/' + id, { method: 'DELETE' });
    toast('路由已删除', 'success');
    loadRoutes();
  } catch (e) {
    handleErr(e);
  }
}

/* ================= API Key #/api-keys ================= */

async function loadApiKeys() {
  const view = $('#view-api-keys');
  view.innerHTML = loadingHtml;
  try {
    const keys = await api('/admin/api-keys');
    view.innerHTML =
      '<div class="view-head"><h2>API Key</h2>'
      + '<button type="button" class="btn btn-primary" data-action="new-key">+ 签发 Key</button></div>'
      + '<div class="card table-card">'
      + (keys.length
        ? '<table class="table"><thead><tr>'
          + '<th>名称</th><th>状态</th><th>创建时间</th><th class="td-actions">操作</th>'
          + '</tr></thead><tbody>'
          + keys.map((k) =>
            '<tr>'
            + '<td class="td-strong">' + esc(k.name) + '</td>'
            + '<td><label class="switch"><input type="checkbox" data-toggle="apikey" data-id="' + esc(k.id) + '"'
            + (k.enabled ? ' checked' : '') + '><span class="slider"></span></label></td>'
            + '<td class="td-muted">' + fmtTime(k.createdAt) + '</td>'
            + '<td class="td-actions">'
            + '<button type="button" class="btn btn-sm btn-table btn-table-danger" data-action="del-key" data-id="' + esc(k.id) + '">删除</button>'
            + '</td></tr>').join('')
          + '</tbody></table>'
        : emptyHtml('暂无 Key，点击右上角「签发 Key」创建'))
      + '</div>';
  } catch (e) {
    view.innerHTML = errHtml(e.message);
    handleErr(e);
  }
}

/** 签发 Key 弹窗：仅名称 */
function openKeyModal() {
  openModal('签发 API Key',
    field('名称', '<input class="input" id="k-name" placeholder="用途标识，如：主项目 Backend">')
    + '<p class="field-hint">签发后仅显示一次明文 Key，请立即复制保存。</p>',
    footerHtml('签发'));
  $('#form-submit').onclick = async () => {
    const name = $('#k-name').value.trim();
    if (!name) { toast('请输入 Key 名称', 'error'); return; }
    const btn = $('#form-submit');
    btn.disabled = true;
    try {
      const data = await api('/admin/api-keys', { method: 'POST', body: JSON.stringify({ name }) });
      closeModal();
      showPlainKey(data);
      loadApiKeys();
    } catch (e) {
      btn.disabled = false;
      handleErr(e);
    }
  };
}

/** 一次性 plainKey 全屏弹层：关闭即从内存清除 */
function showPlainKey(data) {
  plainKeyCache = data.plainKey || null;
  $('#plainkey-name').textContent = data.name ? '名称：' + data.name : '';
  $('#plainkey-value').textContent = plainKeyCache || '(未返回明文)';
  $('#plainkey-overlay').classList.add('open');
}

function closePlainKey() {
  plainKeyCache = null;                       // 关键：内存清除，无法再次查看
  $('#plainkey-value').textContent = '';
  $('#plainkey-overlay').classList.remove('open');
}

/** 复制 plainKey（clipboard API + execCommand 降级） */
async function copyPlainKey() {
  if (!plainKeyCache) return;
  try {
    await navigator.clipboard.writeText(plainKeyCache);
    toast('已复制到剪贴板', 'success');
  } catch (e) {
    try {
      const ta = document.createElement('textarea');
      ta.value = plainKeyCache;
      ta.style.position = 'fixed';
      ta.style.opacity = '0';
      document.body.appendChild(ta);
      ta.select();
      document.execCommand('copy');
      ta.remove();
      toast('已复制到剪贴板', 'success');
    } catch (e2) {
      toast('复制失败，请手动选择复制', 'error');
    }
  }
}

async function onKeyToggle(checkbox) {
  const id = checkbox.dataset.id;
  const enabled = checkbox.checked;
  checkbox.disabled = true;
  try {
    await api('/admin/api-keys/' + id, { method: 'PUT', body: JSON.stringify({ enabled }) });
    toast(enabled ? 'Key 已启用' : 'Key 已停用', 'success');
    loadApiKeys();
  } catch (e) {
    checkbox.checked = !enabled;
    checkbox.disabled = false;
    handleErr(e);
  }
}

function onKeysClick(e) {
  const btn = e.target.closest('button[data-action]');
  if (!btn) return;
  if (btn.dataset.action === 'new-key') openKeyModal();
  else if (btn.dataset.action === 'del-key') deleteKey(btn.dataset.id);
}

function onKeysChange(e) {
  const cb = e.target.closest('input[data-toggle="apikey"]');
  if (cb) onKeyToggle(cb);
}

async function deleteKey(id) {
  const ok = await confirmDialog('确定删除该 API Key 吗？使用该 Key 的调用将立即失效。', { okText: '删除' });
  if (!ok) return;
  try {
    await api('/admin/api-keys/' + id, { method: 'DELETE' });
    toast('Key 已删除', 'success');
    loadApiKeys();
  } catch (e) {
    handleErr(e);
  }
}

/* ================= 调用日志 #/logs ================= */

function statusBadge(s) {
  if (s === 'success') return '<span class="badge badge-success">成功</span>';
  if (s === 'error' || s === 'failed') return '<span class="badge badge-danger">失败</span>';
  if (s === 'created') return '<span class="badge badge-neutral">已创建</span>';
  return '<span class="badge badge-neutral">' + esc(s || '-') + '</span>';
}

async function loadLogs() {
  const view = $('#view-logs');
  view.innerHTML = loadingHtml;
  // 渠道 id→name 映射：失败不阻塞日志页（显示 '-'），但先等它完成避免首屏竞态
  try { await ensureChannels(); } catch (e) { /* 忽略：仅影响渠道名列 */ }
  try {
    const qs = '?page=' + logQuery.page + '&size=' + logQuery.size
      + (logQuery.model ? '&model=' + encodeURIComponent(logQuery.model) : '');
    const data = await api('/admin/call-logs' + qs);
    renderLogs(view, data);
  } catch (e) {
    view.innerHTML = errHtml(e.message);
    handleErr(e);
  }
}

function renderLogs(view, data) {
  const records = data.records || [];
  const total = data.total || 0;
  const size = data.size || logQuery.size;
  const page = data.page || 1;
  const totalPages = Math.max(1, Math.ceil(total / size));

  view.innerHTML =
    '<div class="view-head"><h2>调用日志</h2>'
    + '<div class="filter-bar">'
    + '<input class="input" id="log-model" placeholder="按模型筛选，如 gpt-image-2" value="' + esc(logQuery.model) + '">'
    + '<button type="button" class="btn btn-primary" id="log-search">查询</button>'
    + '<button type="button" class="btn" id="log-reset">重置</button>'
    + '</div></div>'
    + '<div class="card table-card">'
    + (records.length
      ? '<table class="table"><thead><tr>'
        + '<th>模型</th><th>渠道</th><th>状态</th><th>耗时</th><th>错误</th><th>时间</th>'
        + '</tr></thead><tbody>'
        + records.map((r, idx) => {
          const ch = channelMap[r.channelId];
          const channelName = ch ? ch.name : '-';
          const hasError = !!r.error;
          return '<tr class="' + (hasError ? 'row-clickable' : '') + '" data-expand="' + idx + '"'
            + (hasError ? ' title="点击展开错误详情"' : '') + '>'
            + '<td class="td-strong">' + esc(r.model || '-') + '</td>'
            + '<td class="td-muted">' + esc(channelName) + '</td>'
            + '<td>' + statusBadge(r.status) + '</td>'
            + '<td>' + (r.durationMs === null || r.durationMs === undefined ? '-' : esc(r.durationMs) + ' ms') + '</td>'
            + '<td class="td-error">' + (hasError ? esc(trunc(r.error, 60)) : '<span class="td-muted">-</span>') + '</td>'
            + '<td class="td-muted">' + fmtTime(r.createdAt) + '</td>'
            + '</tr>'
            + (hasError
              ? '<tr class="log-detail" id="log-detail-' + idx + '" hidden><td colspan="6">' + esc(r.error) + '</td></tr>'
              : '');
        }).join('')
        + '</tbody></table>'
      : emptyHtml(logQuery.model ? '没有匹配「' + logQuery.model + '」的调用记录' : '暂无调用记录'))
    + '</div>'
    + '<div class="pager">'
    + '<span class="pager-info">共 ' + total + ' 条</span>'
    + '<div class="pager-btns">'
    + '<button type="button" class="btn btn-sm" id="log-prev"' + (page <= 1 ? ' disabled' : '') + '>上一页</button>'
    + '<span class="pager-page">第 ' + page + ' / ' + totalPages + ' 页</span>'
    + '<button type="button" class="btn btn-sm" id="log-next"' + (page >= totalPages ? ' disabled' : '') + '>下一页</button>'
    + '</div></div>';

  // 事件绑定
  $('#log-search').onclick = () => { logQuery.model = $('#log-model').value.trim(); logQuery.page = 1; loadLogs(); };
  $('#log-reset').onclick = () => { logQuery.model = ''; logQuery.page = 1; loadLogs(); };
  $('#log-prev').onclick = () => { if (page > 1) { logQuery.page = page - 1; loadLogs(); } };
  $('#log-next').onclick = () => { if (page < totalPages) { logQuery.page = page + 1; loadLogs(); } };
  $('#log-model').addEventListener('keydown', (ev) => {
    if (ev.key === 'Enter') { logQuery.model = ev.target.value.trim(); logQuery.page = 1; loadLogs(); }
  });
}

/** 日志行点击：错误行展开/收起全文 */
function onLogsClick(e) {
  const tr = e.target.closest('tr[data-expand]');
  if (!tr) return;
  const detail = document.getElementById('log-detail-' + tr.dataset.expand);
  if (detail) detail.hidden = !detail.hidden;
}

/* ================= 用户管理 #/users ================= */

async function loadUsers() {
  const view = $('#view-users');
  view.innerHTML = loadingHtml;
  try {
    const users = await api('/admin/users');
    view.innerHTML =
      '<div class="view-head"><h2>用户管理</h2>'
      + '<button type="button" class="btn btn-primary" data-action="new-user">+ 新建用户</button></div>'
      + '<div class="card table-card">'
      + (users.length
        ? '<table class="table"><thead><tr>'
          + '<th>用户名</th><th>角色</th><th>状态</th><th>创建时间</th><th class="td-actions">操作</th>'
          + '</tr></thead><tbody>'
          + users.map((u) => {
            const isSelf = selfUsername && u.username === selfUsername;
            return '<tr>'
              + '<td class="td-strong">' + esc(u.username) + (isSelf ? ' <span class="badge badge-neutral">当前登录</span>' : '') + '</td>'
              + '<td><span class="badge badge-type">' + esc(u.role || 'admin') + '</span></td>'
              + '<td><label class="switch" title="' + (isSelf ? '不能操作当前登录账号' : '') + '">'
              + '<input type="checkbox" data-toggle="user" data-id="' + esc(u.id) + '"' + (u.status === 'enabled' ? ' checked' : '')
              + (isSelf ? ' disabled' : '') + '><span class="slider"></span></label></td>'
              + '<td class="td-muted">' + fmtTime(u.createdAt) + '</td>'
              + '<td class="td-actions">'
              + '<button type="button" class="btn btn-sm btn-table" data-action="reset-pwd" data-id="' + esc(u.id) + '" data-name="' + esc(u.username) + '"'
              + (isSelf ? ' disabled title="不能操作当前登录账号"' : '') + '>重置密码</button>'
              + '<button type="button" class="btn btn-sm btn-table btn-table-danger" data-action="del-user" data-id="' + esc(u.id) + '" data-name="' + esc(u.username) + '"'
              + (isSelf ? ' disabled title="不能操作当前登录账号"' : '') + '>删除</button>'
              + '</td></tr>';
          }).join('')
          + '</tbody></table>'
        : emptyHtml('暂无用户'))
      + '</div>'
      + '<p class="field-hint">当前登录账号（' + esc(selfUsername || '-') + '）不可被禁用、删除或重置密码（后端同样校验）。</p>';
  } catch (e) {
    view.innerHTML = errHtml(e.message);
    handleErr(e);
  }
}

/** 新建用户弹窗：用户名 + 初始密码 */
function openUserModal() {
  const body =
    field('用户名', '<input class="input" id="u-name" placeholder="登录用户名">')
    + field('初始密码', passwordField('u-password', '', '至少 8 位'));
  openModal('新建用户', body, footerHtml('创建'));
  $('#form-submit').onclick = async () => {
    const username = $('#u-name').value.trim();
    const password = $('#u-password').value;
    if (!username || !password) { toast('用户名和初始密码不能为空', 'error'); return; }
    const btn = $('#form-submit');
    btn.disabled = true;
    try {
      await api('/admin/users', { method: 'POST', body: JSON.stringify({ username, password }) });
      closeModal();
      toast('用户已创建', 'success');
      loadUsers();
    } catch (e) {
      btn.disabled = false;
      handleErr(e);
    }
  };
}

/** 重置密码弹窗：新密码 + 确认 */
function openResetPwdModal(userId, username) {
  const body =
    '<p class="confirm-text">为用户 <strong>' + esc(username) + '</strong> 重置密码。</p>'
    + field('新密码', passwordField('u-newpwd', '', '至少 8 位'))
    + field('确认新密码', passwordField('u-newpwd2', '', '再次输入新密码'));
  openModal('重置密码', body, footerHtml('重置'));
  $('#form-submit').onclick = async () => {
    const pwd = $('#u-newpwd').value;
    const pwd2 = $('#u-newpwd2').value;
    if (!pwd) { toast('新密码不能为空', 'error'); return; }
    if (pwd !== pwd2) { toast('两次输入的密码不一致', 'error'); return; }
    const btn = $('#form-submit');
    btn.disabled = true;
    try {
      await api('/admin/users/' + userId, { method: 'PUT', body: JSON.stringify({ password: pwd }) });
      closeModal();
      toast('密码已重置', 'success');
    } catch (e) {
      btn.disabled = false;
      handleErr(e);
    }
  };
}

async function onUserToggle(checkbox) {
  const id = checkbox.dataset.id;
  const status = checkbox.checked ? 'enabled' : 'disabled';
  checkbox.disabled = true;
  try {
    await api('/admin/users/' + id, { method: 'PUT', body: JSON.stringify({ status }) });
    toast(status === 'enabled' ? '用户已启用' : '用户已停用', 'success');
    loadUsers();
  } catch (e) {
    checkbox.checked = !checkbox.checked;
    checkbox.disabled = false;
    handleErr(e);   // 后端 40301 文案（如「必须至少保留一个启用的管理员」）透传
  }
}

function onUsersClick(e) {
  const btn = e.target.closest('button[data-action]');
  if (!btn || btn.disabled) return;
  const action = btn.dataset.action;
  if (action === 'new-user') openUserModal();
  else if (action === 'reset-pwd') openResetPwdModal(btn.dataset.id, btn.dataset.name);
  else if (action === 'del-user') deleteUser(btn.dataset.id, btn.dataset.name);
}

function onUsersChange(e) {
  const cb = e.target.closest('input[data-toggle="user"]');
  if (cb && !cb.disabled) onUserToggle(cb);
}

async function deleteUser(id, username) {
  const ok = await confirmDialog('确定删除用户「' + username + '」吗？删除后该账号无法登录。', { okText: '删除' });
  if (!ok) return;
  try {
    await api('/admin/users/' + id, { method: 'DELETE' });
    toast('用户已删除', 'success');
    loadUsers();
  } catch (e) {
    handleErr(e);
  }
}

/* ================= 全局事件绑定 & 启动 ================= */

function onModalBodyClick(e) {
  // 密码小眼睛：切换明文/密文
  const eye = e.target.closest('.eye-btn');
  if (!eye) return;
  const input = document.getElementById(eye.dataset.eye);
  if (!input) return;
  const show = input.type === 'password';
  input.type = show ? 'text' : 'password';
  eye.textContent = show ? '🙈' : '👁';
}

function onGlobalKeydown(e) {
  if (e.key !== 'Escape') return;
  if ($('#plainkey-overlay').classList.contains('open')) closePlainKey();
  else if ($('#modal').classList.contains('open')) closeModal();
}

function init() {
  // 登录
  $('#login-form').addEventListener('submit', doLogin);

  // 弹窗
  $('#modal-close').addEventListener('click', closeModal);
  $('#modal').addEventListener('click', (e) => {
    if (e.target === $('#modal')) closeModal();                    // 点遮罩关闭
    else if (e.target.closest('[data-close]')) closeModal();       // 取消按钮
  });
  $('#modal-body').addEventListener('click', onModalBodyClick);    // 密码小眼睛

  // 一次性 plainKey 弹层
  $('#plainkey-close').addEventListener('click', closePlainKey);
  $('#plainkey-copy').addEventListener('click', copyPlainKey);
  $('#plainkey-overlay').addEventListener('click', (e) => {
    if (e.target === $('#plainkey-overlay')) closePlainKey();      // 点遮罩 = 放弃查看
  });

  // 退出登录
  $('#logout-btn').addEventListener('click', doLogout);

  // 各视图事件委托（容器常驻，数据刷新不丢监听）
  $('#view-channels').addEventListener('click', onChannelsClick);
  $('#view-channels').addEventListener('change', onChannelsChange);
  $('#view-routes').addEventListener('click', onRoutesClick);
  $('#view-api-keys').addEventListener('click', onKeysClick);
  $('#view-api-keys').addEventListener('change', onKeysChange);
  $('#view-logs').addEventListener('click', onLogsClick);
  $('#view-users').addEventListener('click', onUsersClick);
  $('#view-users').addEventListener('change', onUsersChange);

  // Esc 关闭（plainKey 弹层优先，其次普通弹窗）
  document.addEventListener('keydown', onGlobalKeydown);

  // hash 路由
  window.addEventListener('hashchange', render);

  // 首次渲染（无 token 直接进登录视图）
  render();
}

init();
