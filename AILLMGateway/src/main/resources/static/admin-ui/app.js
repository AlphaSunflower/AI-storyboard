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
let routesCache = [];                         // 路由列表缓存（测试弹窗模型候选用）
let routeParamsLoaded = false;                // 编辑路由时共享参数是否已回显（未回显不允许「空=清空」）
let schemaLang = 'json';                      // 路由弹窗 Schema 当前格式：json | yaml
let fallbacksLoaded = false;                  // 编辑路由时回退链是否已回显（未回显不覆盖）
let mcRows = [];                              // 模型配置工作台：全部模型行
let mcState = null;                           // 当前编辑的 {row, spec, dirty}
let mcPreviewMode = '';                       // 预览选中的模式
let mcLastKey = '';                           // 记住上次选中的模型/类型
let mcTemplateNote = null;                    // 模板创建后的提示 {key, keys}
let mcFilter = '';                            // 顶部模型列表筛选词
let mcTypeFilter = '';                        // 按生成类型筛选：image/video/text/vision
let mcChannelFilter = '';                     // 按渠道筛选：渠道 ID / __none
let mcRoutes = [];                            // 模型 → 渠道绑定，用于渠道筛选
let selfUsername = null;                      // 当前登录用户名（JWT sub 解码，用于用户页防自锁）
let plainKeyCache = null;                     // 一次性 plainKey，关闭弹层即置空
let modalCloseHandler = null;                 // 弹窗关闭回调（confirm 用）
const logQuery = { page: 1, size: 20, model: '' }; // 日志页查询状态

const ROUTES = ['dashboard', 'channels', 'routes', 'models', 'yaml', 'api-keys', 'logs', 'users', 'config'];

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

/** 整数千分位；null/undefined 显示 - */
function fmtInt(n) {
  if (n === null || n === undefined || n === '') return '-';
  const v = Number(n);
  return Number.isFinite(v) ? v.toLocaleString('zh-CN') : String(n);
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
    + '<button type="button" class="eye-btn" data-eye="' + esc(id) + '" title="显示/隐藏">显示</button>'
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
    models: loadModelConfig,
    yaml: loadYamlConfig,
    'api-keys': loadApiKeys,
    logs: loadLogs,
    users: loadUsers,
    config: loadConfig,
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

const loadingHtml = '<div class="loading"><div class="spinner"></div>加载中…</div>';

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
  // hover tooltip
  const tip = document.createElement('div');
  tip.className = 'chart-tooltip';
  tip.style.display = 'none';
  document.body.appendChild(tip);
  container.querySelectorAll('.bar').forEach((bar, i) => {
    bar.addEventListener('mouseenter', () => {
      const t = trend[i];
      tip.textContent = (t.date || '') + ': ' + (t.count || 0) + ' 次';
      tip.style.display = 'block';
    });
    bar.addEventListener('mousemove', (e) => {
      tip.style.left = e.clientX + 'px';
      tip.style.top = (e.clientY - 10) + 'px';
    });
    bar.addEventListener('mouseleave', () => { tip.style.display = 'none'; });
  });
}

async function loadDashboard() {
  const view = $('#view-dashboard');
  view.innerHTML = loadingHtml;
  try {
    const s = await api('/admin/stats/overview');
    let metrics = null;
    try { metrics = await api('/admin/metrics'); } catch (e) { console.warn('指标加载失败：', e && e.message); }
    const hasCalls = (s.calls && s.calls.total > 0);
    const trend = s.trend7d || [];
    const topModels = s.topModels || [];
    const showTrend = hasCalls && trend.length > 0 && trend.some((t) => (t.count || 0) > 0);
    const tokenToday = metrics && metrics.tokensToday ? metrics.tokensToday.tokens : 0;
    const token7d = metrics && metrics.tokens7d ? metrics.tokens7d.tokens : 0;
    const channelHealth = metrics && metrics.channels ? metrics.channels : [];
    const modelHealth = metrics && metrics.models ? metrics.models : [];

    const channelTable = channelHealth.length
      ? '<table class="table"><thead><tr><th>渠道</th><th>调用</th><th>成功率</th><th>P95</th><th>Token</th><th>最近错误</th></tr></thead><tbody>'
        + channelHealth.map((c) => '<tr>'
          + '<td class="td-strong">' + esc(c.channelName || c.channelId)
          + (c.enabled === false ? ' <span class="badge badge-frozen">冻结</span>' : '') + '</td>'
          + '<td>' + fmtInt(c.calls) + '</td>'
          + '<td>' + fmtRate(c.successRate) + '</td>'
          + '<td>' + fmtInt(c.p95Ms) + ' ms</td>'
          + '<td>' + fmtInt(c.tokens) + '</td>'
          + '<td class="td-muted" title="' + esc(c.lastError || '') + '">' + esc(trunc(c.lastError || '-', 46)) + '</td>'
          + '</tr>').join('')
        + '</tbody></table>'
      : emptyHtml('暂无渠道调用数据');

    const modelTable = modelHealth.length
      ? '<table class="table"><thead><tr><th>模型</th><th>调用</th><th>成功率</th><th>P95</th><th>Token</th></tr></thead><tbody>'
        + modelHealth.map((m) => '<tr>'
          + '<td class="td-strong">' + esc(m.model) + '</td>'
          + '<td>' + fmtInt(m.calls) + '</td>'
          + '<td>' + fmtRate(m.successRate) + '</td>'
          + '<td>' + fmtInt(m.p95Ms) + ' ms</td>'
          + '<td>' + fmtInt(m.tokens) + '</td>'
          + '</tr>').join('')
        + '</tbody></table>'
      : (topModels.length
        ? '<table class="table"><thead><tr><th>模型</th><th>调用次数</th><th>成功率</th></tr></thead><tbody>'
          + topModels.map((m) => '<tr>'
            + '<td class="td-strong">' + esc(m.model) + '</td>'
            + '<td>' + esc(m.count) + '</td>'
            + '<td>' + fmtRate(m.successRate) + '</td>'
            + '</tr>').join('')
          + '</tbody></table>'
        : emptyHtml('暂无调用数据'));

    view.innerHTML =
      '<div class="view-head"><h2>仪表盘</h2></div>'
      + '<div class="stat-grid">'
      + statCard('渠道', (s.channels ? s.channels.enabled : 0) + ' / ' + (s.channels ? s.channels.total : 0), '已启动')
      + statCard('模型路由', (s.routes ? s.routes.total : 0), '条路由')
      + statCard('API Key', (s.apiKeys ? s.apiKeys.enabled : 0) + ' / ' + (s.apiKeys ? s.apiKeys.total : 0), '已启用')
      + statCard('今日调用', (s.todayCalls ? s.todayCalls.total : 0), '成功率 ' + fmtRate(s.todayCalls ? s.todayCalls.successRate : null))
      + statCard('今日 Token', fmtInt(tokenToday), '近 7 天 ' + fmtInt(token7d))
      + statCard('累计调用', (s.calls ? s.calls.total : 0), '成功率 ' + fmtRate(s.calls ? s.calls.successRate : null))
      + '</div>'
      + '<div class="card"><h3 class="card-title">近 7 天调用趋势</h3>'
      + '<div id="trend-chart">' + (showTrend ? '' : emptyHtml('暂无调用数据')) + '</div></div>'
      + '<div class="card table-card"><h3 class="card-title">渠道健康（近 7 天）</h3>' + channelTable + '</div>'
      + '<div class="card table-card"><h3 class="card-title">模型延迟与 Token（近 7 天）</h3>' + modelTable + '</div>';

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
        ? '<table class="table table-channels"><thead><tr>'
          + '<th>名称</th><th>类型</th><th>BaseURL</th><th>状态</th><th>优先级/权重</th><th>创建时间</th><th class="td-actions">操作</th>'
          + '</tr></thead><tbody>'
          + channels.map((c) =>
            '<tr>'
            + '<td class="td-strong">' + esc(c.name) + '</td>'
            + '<td><span class="badge badge-type">' + esc(c.type || '-') + '</span></td>'
            + '<td class="td-muted td-url" title="' + esc(c.baseUrl || '') + '">' + esc(trunc(c.baseUrl, 42)) + '</td>'
            + '<td><div class="status-inline"><span class="badge ' + (c.enabled !== false ? 'badge-running' : 'badge-frozen') + '">'
            + (c.enabled !== false ? '启动' : '冻结') + '</span>'
            + '<label class="switch"><input type="checkbox" data-toggle="channel" data-id="' + esc(c.id) + '"'
            + (c.enabled !== false ? ' checked' : '') + '><span class="slider"></span></label></div></td>'
            + '<td>' + esc(c.priority === null || c.priority === undefined ? 0 : c.priority)
            + ' / ' + esc(c.weight === null || c.weight === undefined ? 1 : c.weight) + '</td>'
            + '<td class="td-muted">' + fmtTime(c.createdAt) + '</td>'
            + '<td class="td-actions">'
            + (c.type === 'ollama' ? '<button type="button" class="btn btn-sm btn-table" data-action="sync-channel" data-id="' + esc(c.id) + '">同步模型</button>' : '')
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
      + ['openai_compatible', 'gemini', 'minimax', 'ollama']
        .map((t) => '<option value="' + t + '"' + (channel && channel.type === t ? ' selected' : '') + '>' + t + '</option>').join('')
      + '</select>')
    + field('BaseURL', '<input class="input" id="f-baseurl" value="' + esc(channel ? channel.baseUrl : '') + '" placeholder="https://api.openai.com">')
    + field('API Key', passwordField('f-apikey', '', isEdit ? '留空则不修改' : '必填'))
    + field('模型列表（逗号分隔）', '<input class="input" id="f-models" value="' + esc(channel ? channel.models || '' : '') + '" placeholder="如：deepseek-v4-pro, deepseek-v4-flash（测试弹窗候选用，留空则仅用已配路由模型）">')
    + field('优先级', '<input class="input" id="f-priority" type="number" value="'
      + esc(channel && channel.priority !== null && channel.priority !== undefined ? channel.priority : 0) + '" placeholder="数字越小越优先">')
    + '<div class="field-grid-3">'
      + field('权重', '<input class="input" id="f-weight" type="number" min="1" value="'
        + esc(channel && channel.weight !== null && channel.weight !== undefined ? channel.weight : 1) + '">')
      + field('超时（毫秒，空=默认）', '<input class="input" id="f-timeout" type="number" min="1" value="'
        + esc(channel && channel.timeoutMs !== null && channel.timeoutMs !== undefined ? channel.timeoutMs : '') + '">')
      + field('额外重试', '<input class="input" id="f-retries" type="number" min="0" max="3" value="'
        + esc(channel && channel.maxRetries !== null && channel.maxRetries !== undefined ? channel.maxRetries : 0) + '">')
    + '</div>'
    + '<div class="field field-inline"><span class="field-label">状态（启动 / 冻结）</span>'
    + '<label class="switch"><input type="checkbox" id="f-enabled"' + (channel ? (channel.enabled !== false ? ' checked' : '') : ' checked')
    + '><span class="slider"></span></label>'
    + '<span class="field-hint">冻结后，该渠道下所有模型路由都会变为不可用</span></div>'
    + (isEdit
      ? field('Key 池（可选，多 Key 加权轮换）', '<div id="channel-keys">加载中…</div>')
      : '<p class="field-hint">保存后重新编辑渠道，可添加多个 Key 组成 Key 池。</p>');

  openModal(isEdit ? '编辑渠道' : '新建渠道', body, footerHtml('保存'));
  $('#form-submit').onclick = () => submitChannel(isEdit ? channel.id : null);
  if (isEdit) loadChannelKeys(channel.id);
}

/** 渠道 Key 池：列表 + 新增（Key 只写不读） */
async function loadChannelKeys(channelId) {
  const box = $('#channel-keys');
  if (!box) return;
  try {
    const keys = await api('/admin/channels/' + channelId + '/keys');
    renderChannelKeys(channelId, keys || []);
  } catch (e) {
    box.innerHTML = errHtml(e.message);
  }
}

function renderChannelKeys(channelId, keys) {
  const box = $('#channel-keys');
  if (!box) return;
  const rows = keys.length
    ? '<table class="table key-table"><thead><tr><th>标签</th><th>Key</th><th>权重</th><th>启用</th><th></th></tr></thead><tbody>'
      + keys.map((k) => '<tr>'
        + '<td class="td-strong">' + esc(k.label || '(未命名)') + '</td>'
        + '<td class="td-muted">' + esc(k.maskedKey) + '</td>'
        + '<td>' + esc(k.weight === null || k.weight === undefined ? 1 : k.weight) + '</td>'
        + '<td><label class="switch"><input type="checkbox" data-key-toggle="' + esc(k.id) + '"'
        + (k.enabled ? ' checked' : '') + '><span class="slider"></span></label></td>'
        + '<td class="td-actions"><button type="button" class="btn btn-sm btn-table btn-table-danger" data-key-del="'
        + esc(k.id) + '">删除</button></td></tr>').join('')
      + '</tbody></table>'
    : '<p class="field-hint">尚未配置多 Key，调用时使用渠道主 Key。</p>';
  box.innerHTML = rows
    + '<div class="key-add">'
    + '<input class="input" id="key-label" placeholder="标签，如 key-1">'
    + '<input class="input" id="key-value" placeholder="新 Key（必填）">'
    + '<input class="input" id="key-weight" type="number" min="1" value="1" style="width:82px">'
    + '<button type="button" class="btn btn-sm" id="key-add-btn">添加 Key</button></div>';

  box.querySelectorAll('[data-key-del]').forEach((btn) => {
    btn.onclick = async () => {
      const ok = await confirmDialog('删除这个 Key？', { okText: '删除' });
      if (!ok) return;
      try {
        await api('/admin/channels/' + channelId + '/keys/' + btn.dataset.keyDel, { method: 'DELETE' });
        toast('Key 已删除', 'success');
        loadChannelKeys(channelId);
      } catch (e) { handleErr(e); }
    };
  });
  box.querySelectorAll('[data-key-toggle]').forEach((cb) => {
    cb.onchange = async () => {
      try {
        await api('/admin/channels/' + channelId + '/keys/' + cb.dataset.keyToggle, {
          method: 'PUT', body: JSON.stringify({ enabled: cb.checked }),
        });
        toast(cb.checked ? 'Key 已启用' : 'Key 已停用', 'success');
      } catch (e) {
        cb.checked = !cb.checked;
        handleErr(e);
      }
    };
  });
  const addBtn = $('#key-add-btn');
  if (addBtn) addBtn.onclick = async () => {
    const label = $('#key-label').value.trim();
    const apiKey = $('#key-value').value.trim();
    const weight = parseInt($('#key-weight').value, 10);
    if (!apiKey) { toast('新 Key 不能为空', 'error'); return; }
    addBtn.disabled = true;
    try {
      await api('/admin/channels/' + channelId + '/keys', {
        method: 'POST',
        body: JSON.stringify({ label, apiKey, weight: Number.isFinite(weight) ? Math.max(1, weight) : 1 }),
      });
      toast('Key 已添加', 'success');
      loadChannelKeys(channelId);
    } catch (e) {
      addBtn.disabled = false;
      handleErr(e);
    }
  };
}

async function submitChannel(id) {
  const name = $('#f-name').value.trim();
  const type = $('#f-type').value;
  const baseUrl = $('#f-baseurl').value.trim();
  const apiKey = $('#f-apikey').value;
  const priority = parseInt($('#f-priority').value, 10);
  const weight = parseInt($('#f-weight').value, 10);
  const timeoutRaw = $('#f-timeout').value.trim();
  const timeoutMs = timeoutRaw === '' ? null : parseInt(timeoutRaw, 10);
  const maxRetries = parseInt($('#f-retries').value, 10);
  const enabled = $('#f-enabled').checked;

  if (!name || !baseUrl) { toast('名称和 BaseURL 不能为空', 'error'); return; }
  if (!id && !apiKey && type !== 'ollama') { toast('新建渠道必须填写 API Key（Ollama 本地可留空）', 'error'); return; }

  const body = {
    name, type, baseUrl,
    models: $('#f-models').value.trim(),
    enabled,
    priority: isNaN(priority) ? 0 : priority,
    weight: isNaN(weight) ? 1 : Math.max(1, weight),
    timeoutMs: timeoutMs === null || isNaN(timeoutMs) ? 0 : timeoutMs,
    maxRetries: isNaN(maxRetries) ? 0 : Math.max(0, maxRetries),
  };
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

/** 渠道模型候选：渠道 models 字段拆分（中英文逗号）+ 该渠道已配路由的模型，去重 */
function channelModelCandidates(channelId) {
  const ch = channelMap[channelId] || {};
  const fromField = (ch.models || '').split(/[,，]/).map((s) => s.trim()).filter(Boolean);
  const fromRoutes = routesCache.filter((r) => r.channelId === channelId)
    .map((r) => r.modelName).filter(Boolean);
  return [...new Set([...fromField, ...fromRoutes])];
}

/** 模型类型徽标（text/image/video/vision → 中文标签） */
function typeBadge(type) {
  const labels = { text: '文本模型', image: '生图模型', video: '视频模型', vision: '理解模型' };
  const t = labels[type] ? type : 'text';
  return '<span class="badge badge-type badge-type-' + esc(t) + '">' + esc(labels[t]) + '</span>';
}

/** 更新 datalist 候选（渠道切换时刷新模型列表） */
function updateModelList(inputId, candidates) {
  const dl = document.getElementById(inputId + '-list');
  if (dl) dl.innerHTML = (candidates || []).map((m) => '<option value="' + esc(m) + '"></option>').join('');
}

/** 拉取渠道模型列表填充 datalist（不自动选中，由用户选择；preset 存在则预填输入框） */
function loadModelsFor(channelId, inputId, preset) {
  const el = document.getElementById(inputId);
  if (!el) return Promise.resolve([]);
  el.placeholder = '加载模型中…';
  return api('/admin/channels/' + channelId + '/models')
    .then((d) => {
      const models = d.models || [];
      updateModelList(inputId, models);
      el.placeholder = models.length ? '选择或输入模型名' : '未获取到模型列表，请手动输入模型名';
      if (preset) el.value = preset;
      return models;
    })
    .catch(() => {
      updateModelList(inputId, channelModelCandidates(channelId));
      el.placeholder = '选择或输入模型名';
      if (preset) el.value = preset;
      return channelModelCandidates(channelId);
    });
}

/** 渠道测试：弹窗打开时异步拉取该渠道模型列表（不默认选中）→ 选择（可手动输入）→ 调 test 端点 */
function testChannel(id, btn) {
  openModal(
    '测试渠道连通性',
    field('测试模型', '<input class="input" id="test-model" list="test-model-list" placeholder="加载模型中…">'
      + '<datalist id="test-model-list"></datalist>')
      + '<p class="test-hint">将以最小请求（max_tokens=1）直连上游验证连通性，不落业务日志</p>',
    footerHtml('开始测试'),
    null
  );
  // 异步拉取模型候选（上游优先，失败回退本地）；不默认选中，由用户选择
  loadModelsFor(id, 'test-model');
  $('#form-submit').onclick = async () => {
    const model = $('#test-model').value.trim();
    if (!model) { toast('请选择或输入测试模型', 'error'); return; }
    closeModal();
    const orig = btn.textContent;
    btn.disabled = true;
    btn.textContent = '测试中…';
    try {
      const data = await api('/admin/channels/' + id + '/test', {
        method: 'POST',
        body: JSON.stringify({ modelName: model }),
      });
      showTestResult(data);
    } catch (e) {
      handleErr(e);
    } finally {
      btn.disabled = false;
      btn.textContent = orig;
    }
  };
}

/** 测试结果弹窗：成功绿勾+耗时；失败红叉+error 文案 */
function showTestResult(data) {
  const ok = !!data.ok;
  const icon = ok
    ? '<svg viewBox="0 0 24 24" class="result-icon ok"><path d="M4 12.5l5 5L20 6.5" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"/></svg>'
    : '<svg viewBox="0 0 24 24" class="result-icon fail"><path d="M6 6l12 12M18 6L6 18" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round"/></svg>';
  const statusLine = data.status ? ' · HTTP ' + data.status : '';
  // 路由测试会锁定该行渠道；显示实际测的是谁，避免同模型多渠道时误读结果
  const ch = data.channelId ? channelMap[data.channelId] : null;
  const channelLine = data.channelId ? ' · 渠道 ' + esc(ch ? ch.name : data.channelId) : '';
  const body =
    '<div class="test-result">'
    + icon
    + '<div class="test-title">' + (ok ? '测试成功' : '测试失败') + '</div>'
    + '<div class="test-meta">耗时 ' + (data.durationMs === null || data.durationMs === undefined ? '-' : data.durationMs) + ' ms' + statusLine + channelLine + '</div>'
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
  } else if (action === 'sync-channel') {
    syncChannelModels(id, btn);
  }
}

/** Ollama 渠道同步模型：POST /admin/channels/{id}/sync-models，拉全量模型建路由后刷新渠道与路由 */
async function syncChannelModels(id, btn) {
  const orig = btn.textContent;
  btn.disabled = true;
  btn.textContent = '同步中…';
  try {
    const data = await api('/admin/channels/' + id + '/sync-models', { method: 'POST' });
    toast('已同步 ' + data.total + ' 个模型（仅刷新测试候选，未建路由）', 'success');
    loadChannels();
  } catch (e) {
    handleErr(e);
  } finally {
    btn.disabled = false;
    btn.textContent = orig;
  }
}

/** 渠道启停 switch：PUT 只传 enabled */
async function onChannelToggle(checkbox) {
  const id = checkbox.dataset.id;
  const enabled = checkbox.checked;
  checkbox.disabled = true;
  try {
    await api('/admin/channels/' + id, { method: 'PUT', body: JSON.stringify({ enabled }) });
    toast(enabled ? '渠道已启动' : '渠道已冻结，关联模型路由将不可用', 'success');
    channelsLoaded = false;
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
    await ensureChannels();   // 渠道 id→name 映射（冻结渠道灰显）
    const routes = await api('/admin/routes');
    // 同模型名按默认优先排序（isDefault=true 排前面），避免同名路由顺序随机导致误点
    routes.sort((a, b) => (a.modelName || '').localeCompare(b.modelName || '') || (b.isDefault ? 1 : 0) - (a.isDefault ? 1 : 0));
    routesCache = routes;     // 缓存供测试弹窗模型候选
    view.innerHTML =
      '<div class="view-head"><h2>模型路由</h2>'
      + '<button type="button" class="btn btn-primary" data-action="new-route">+ 新建路由</button></div>'
      + '<div class="card table-card">'
      + (routes.length
        ? '<table class="table table-routes"><thead><tr>'
          + '<th>模型 / 上游</th><th>类型</th><th>渠道</th><th>优先级/权重</th><th>状态</th><th class="td-actions">操作</th>'
          + '</tr></thead><tbody>'
          + routes.map((r) => {
            const ch = channelMap[r.channelId];
            const chFrozen = !ch || ch.enabled === false;
            const routeFrozen = r.enabled === false;
            const channelName = ch ? ch.name + (chFrozen ? '（冻结）' : '') : '未知渠道';
            const upstream = r.upstreamModel && r.upstreamModel !== r.modelName
              ? '<div class="td-sub">→ ' + esc(r.upstreamModel) + '</div>' : '';
            return '<tr>'
              + '<td class="td-strong">' + esc(r.modelName)
              + (r.isDefault ? ' <span class="badge badge-default">默认</span>' : '') + upstream + '</td>'
              + '<td>' + typeBadge(r.type) + '</td>'
              + '<td class="' + (chFrozen ? 'td-muted' : '') + '">' + esc(channelName) + '</td>'
              + '<td>' + esc(r.priority === null || r.priority === undefined ? 0 : r.priority)
              + ' / ' + esc(r.weight === null || r.weight === undefined ? 1 : r.weight) + '</td>'
              + '<td><span class="badge ' + (chFrozen || routeFrozen ? 'badge-frozen' : 'badge-running') + '"'
              + (chFrozen ? ' title="渠道已冻结，该模型路由不可用"' : '') + '>'
              + (chFrozen ? '冻结（渠道）' : (routeFrozen ? '冻结' : '启动')) + '</span></td>'
              + '<td class="td-actions">'
              + '<button type="button" class="btn btn-sm btn-table" data-action="test-route" data-id="' + esc(r.id) + '">测试</button>'
              + '<button type="button" class="btn btn-sm btn-table" data-action="edit-route" data-id="' + esc(r.id) + '">编辑</button>'
              + '<button type="button" class="btn btn-sm btn-table" data-action="toggle-route" data-id="' + esc(r.id)
              + '" data-next="' + (routeFrozen ? 'true' : 'false') + '"'
              + (chFrozen ? ' disabled title="渠道已冻结，请先到渠道管理启动渠道"' : '') + '>'
              + (chFrozen ? '渠道冻结' : (routeFrozen ? '启动' : '冻结')) + '</button>'
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

/** 滑块控件：range input + 实时值显示（数值参数避免手填） */
const sliderField = (id, label, min, max, step, def) =>
  '<label>' + label
  + ' <input type="range" class="p-range" id="' + id + '" min="' + min + '" max="' + max + '" step="' + step + '" value="' + def + '">'
  + ' <span class="p-val" id="' + id + '-val">' + def + '</span></label>';

/** 本地模型（Ollama）扩展参数：思考开关/等级 + num_ctx/seed/stop/keep_alive，仅 ollama 渠道或已开本地模型时显示 */
const ollamaBlockHtml = () =>
  '<div class="params-grid">'
  + '<label><input type="checkbox" id="p-local"> 本地模型（Ollama）</label>'
  + '<label><input type="checkbox" id="p-think"> 开启思考</label>'
  + '<label>思考等级 <select class="input" id="p-thinklevel">'
  + ['high', 'medium', 'low', 'max']
    .map((v) => '<option value="' + v + '">' + v + '</option>').join('')
  + '</select></label>'
  + '<label>num_ctx <input class="input" id="p-numctx" type="number" placeholder="8192"></label>'
  + '<label>seed <input class="input" id="p-seed" type="number" placeholder="-1"></label>'
  + '<label>stop（逗号分隔） <input class="input" id="p-stop" placeholder="\\n\\n"></label>'
  + '<label>keep_alive <input class="input" id="p-keepalive" placeholder="5m"></label>'
  + '</div>';

/** 刷新「本地模型（Ollama）」区块显隐：text/vision 且（渠道为 ollama 或已开启本地模型）时显示 */
function refreshOllamaBlock() {
  const section = $('#ollama-section');
  if (!section) return;
  const type = ($('#r-type') || {}).value || 'text';
  const channelId = ($('#r-channel') || {}).value || '';
  const chType = (channelMap[channelId] || {}).type;
  const local = $('#p-local') ? $('#p-local').checked : false;
  const show = (type === 'text' || type === 'vision') && (chType === 'ollama' || local);
  section.style.display = show ? '' : 'none';
}

/**
 * 参数配置区（按类型分组表单）：数值参数用滑块（n 范围/时长），字符串枚举保持输入框。
 *
 * text 类型**不再暴露 temperature / max_tokens / top_p**：
 * 网关只在 /v1/models 的 defaults 里透出它们，从不注入 OpenAI 兼容请求，
 * 放在这里等于假旋钮——改了不起作用，还会让人误以为 1024 是真实输出上限。
 * 采样与输出长度由调用方（MoonAgent 的 OpenAiChatOptions）显式决定。
 * 文本模型真正需要维护的只有「上下文窗口」，它决定压缩阈值。
 */
const paramsGroups = {
  text: '<label>上下文窗口(token) <input class="input" id="p-contextwindow" type="number" placeholder="如 1000000；空=未配置"></label>'
      + '<p class="test-hint">temperature / max_tokens / top_p 由调用方显式指定，网关不注入，故不在此配置。</p>',
  image: sliderField('p-nmin', '数量min', '1', '10', '1', '1')
       + sliderField('p-nmax', '数量max', '1', '10', '1', '4')
       + sliderField('p-ndefault', '数量默认', '1', '10', '1', '1')
       + '<label>尺寸(逗号) <input class="input" id="p-sizes" placeholder="1024x1024,1536x1024"></label>'
       + '<label>尺寸默认 <input class="input" id="p-sizedefault" placeholder="1024x1024"></label>'
       + '<label>质量(逗号) <input class="input" id="p-qualities" placeholder="standard,hd"></label>'
       + '<label>质量默认 <input class="input" id="p-qualitydefault" placeholder="hd"></label>'
       + '<label>风格(逗号) <input class="input" id="p-styles" placeholder="vivid,natural"></label>'
       + '<label>风格默认 <input class="input" id="p-styledefault" placeholder="vivid"></label>',
  video: '<details open><summary>基础参数</summary>'
       + sliderField('p-dmin', '时长min(秒)', '4', '15', '1', '4')
       + sliderField('p-dmax', '时长max(秒)', '4', '15', '1', '8')
       + sliderField('p-durationdefault', '时长默认(秒)', '4', '15', '1', '6')
       + '<label>分辨率(逗号) <input class="input" id="p-resolutions" placeholder="768P,2K"></label>'
       + '<label>分辨率默认 <input class="input" id="p-resolutiondefault" placeholder="768P"></label>'
       + '<label>画幅(逗号) <input class="input" id="p-aspects" placeholder="16:9,9:16,4:3,1:1"></label>'
       + '<label>画幅默认 <input class="input" id="p-aspectdefault" placeholder="16:9"></label>'
       + '</details>'
       + '<details open><summary>参考素材</summary>'
       + sliderField('p-refimagesmin', '参考图min(张)', '0', '10', '1', '0')
       + sliderField('p-refimagesmax', '参考图max(张)', '0', '10', '1', '3')
       + sliderField('p-refvideosmin', '参考视频min(个)', '0', '5', '1', '0')
       + sliderField('p-refvideosmax', '参考视频max(个)', '0', '5', '1', '1')
       + '</details>'
       + '<details><summary>音频参数</summary>'
       + sliderField('p-audiocountmin', '音频个数min', '0', '10', '1', '0')
       + sliderField('p-audiocountmax', '音频个数max', '0', '10', '1', '2')
       + sliderField('p-audiodmin', '音频单段min(秒)', '1', '300', '1', '5')
       + sliderField('p-audiodmax', '音频单段max(秒)', '1', '300', '1', '60')
       + '</details>'
       + '<details><summary>高级限制</summary>'
       + sliderField('p-videodmin', '视频单段min(秒)', '1', '300', '1', '5')
       + sliderField('p-videodmax', '视频单段max(秒)', '1', '300', '1', '60')
       + sliderField('p-maxtotalduration', '总时长上限(秒)', '1', '3600', '1', '300')
       + sliderField('p-maxtotalfiles', '文件总数上限', '1', '50', '1', '10')
       + sliderField('p-maxvideosize', '视频单个(MB)', '1', '500', '1', '100')
       + sliderField('p-maximagesize', '图片单个(MB)', '1', '100', '1', '10')
       + sliderField('p-maxaudiosize', '音频单个(MB)', '1', '100', '1', '15')
       + sliderField('p-maxbody', '请求体上限(MB)', '1', '200', '1', '64')
       + sliderField('p-maxprompt', '提示词上限(字符)', '100', '20000', '100', '2000')
       + '</details>',
};

/** 新建/编辑路由弹窗：渠道下拉（停用渠道灰显）+ defaultParams JSON 校验 */
function openRouteModal(route) {
  const isEdit = !!route;
  const options = Object.keys(channelMap).length
    ? Object.values(channelMap)
        .slice().sort((a, b) => (a.priority || 0) - (b.priority || 0))
        .map((c) =>
          '<option value="' + esc(c.id) + '"' + (route && route.channelId === c.id ? ' selected' : '')
          + (c.enabled === false ? ' class="opt-disabled"' : '') + '>'
          + esc(c.name) + (c.enabled === false ? '（冻结）' : '') + '</option>'
        ).join('')
    : '<option value="">暂无渠道，请先到渠道管理创建</option>';
  // 默认选中渠道：编辑用路由的 channelId，新建用优先级最高的渠道
  const selCh = route ? route.channelId : (Object.values(channelMap).slice().sort((a, b) => (a.priority || 0) - (b.priority || 0))[0] || {}).id || '';
  // 模型类型下拉（text 文本 / image 生图 / video 视频生成 / vision 图片视频理解）
  const typeOpts = [
    ['text', '文本模型'], ['image', '生图模型'], ['video', '视频模型（生成视频）'], ['vision', '图片/视频理解模型'],
  ].map(([v, label]) => '<option value="' + v + '"' + ((route ? route.type : 'text') === v ? ' selected' : '') + '>' + label + '</option>').join('');

  // 绑定滑块：input 事件实时刷新值显示；n 范围/时长范围 min<=max 联动（拖动 min 时 max 下限跟随，反之亦然）
  const bindParamSliders = () => {
    const bind = (id) => {
      const el = document.getElementById(id);
      if (!el) return;
      el.addEventListener('input', () => {
        const v = document.getElementById(id + '-val');
        if (v) v.textContent = el.value;
        if (id === 'p-nmin') { const mx = document.getElementById('p-nmax'); if (mx && Number(mx.value) < Number(el.value)) mx.value = el.value; }
        if (id === 'p-nmax') { const mn = document.getElementById('p-nmin'); if (mn && Number(mn.value) > Number(el.value)) mn.value = el.value; }
        if (id === 'p-dmin') { const mx = document.getElementById('p-dmax'); if (mx && Number(mx.value) < Number(el.value)) mx.value = el.value; }
        if (id === 'p-dmax') { const mn = document.getElementById('p-dmin'); if (mn && Number(mn.value) > Number(el.value)) mn.value = el.value; }
        if (id === 'p-refimagesmin') { const mx = document.getElementById('p-refimagesmax'); if (mx && Number(mx.value) < Number(el.value)) mx.value = el.value; }
        if (id === 'p-refimagesmax') { const mn = document.getElementById('p-refimagesmin'); if (mn && Number(mn.value) > Number(el.value)) mn.value = el.value; }
        if (id === 'p-refvideosmin') { const mx = document.getElementById('p-refvideosmax'); if (mx && Number(mx.value) < Number(el.value)) mx.value = el.value; }
        if (id === 'p-refvideosmax') { const mn = document.getElementById('p-refvideosmin'); if (mn && Number(mn.value) > Number(el.value)) mn.value = el.value; }
        if (id === 'p-audiocountmin') { const mx = document.getElementById('p-audiocountmax'); if (mx && Number(mx.value) < Number(el.value)) mx.value = el.value; }
        if (id === 'p-audiocountmax') { const mn = document.getElementById('p-audiocountmin'); if (mn && Number(mn.value) > Number(el.value)) mn.value = el.value; }
        if (id === 'p-audiodmin') { const mx = document.getElementById('p-audiodmax'); if (mx && Number(mx.value) < Number(el.value)) mx.value = el.value; }
        if (id === 'p-audiodmax') { const mn = document.getElementById('p-audiodmin'); if (mn && Number(mn.value) > Number(el.value)) mn.value = el.value; }
        if (id === 'p-videodmin') { const mx = document.getElementById('p-videodmax'); if (mx && Number(mx.value) < Number(el.value)) mx.value = el.value; }
        if (id === 'p-videodmax') { const mn = document.getElementById('p-videodmin'); if (mn && Number(mn.value) > Number(el.value)) mn.value = el.value; }
      });
    };
    ['p-nmin', 'p-nmax', 'p-ndefault', 'p-dmin', 'p-dmax', 'p-durationdefault',
     'p-refimagesmin', 'p-refimagesmax', 'p-refvideosmin', 'p-refvideosmax',
     'p-audiocountmin', 'p-audiocountmax', 'p-audiodmin', 'p-audiodmax', 'p-videodmin', 'p-videodmax',
     'p-maxtotalduration', 'p-maxtotalfiles', 'p-maxvideosize', 'p-maximagesize', 'p-maxaudiosize', 'p-maxbody', 'p-maxprompt']
      .forEach(bind);
  };
  // 当前类型：编辑取路由 type（默认 text），决定初始渲染哪组参数表单
  const curType = route ? (route.type || 'text') : 'text';

  const body =
    field('渠道', '<select class="input" id="r-channel">' + options + '</select>')
    + field('模型类型', '<select class="input" id="r-type">' + typeOpts + '</select>')
    + field('模型', '<input class="input" id="r-model" list="r-model-list" placeholder="加载模型中…">'
      + '<datalist id="r-model-list"></datalist>')
    + '<div id="r-model-config-warning" class="route-config-warning" style="display:none"></div>'
    + field('上游模型名', '<input class="input" id="r-upstream" placeholder="留空=与公共模型名相同；用于渠道命名不一致时映射">')
    + '<div class="field-grid-3">'
      + field('优先级（越小越先）', '<input class="input" id="r-priority" type="number" value="0">')
      + field('权重', '<input class="input" id="r-weight" type="number" min="1" value="1">')
      + field('重试次数', '<input class="input" id="r-maxretries" type="number" min="0" max="3" value="0">')
    + '</div>'
    + '<div class="field field-inline"><span class="field-label">状态（启动 / 冻结）</span>'
      + '<label class="switch"><input type="checkbox" id="r-enabled" checked><span class="slider"></span></label>'
      + '<span class="field-hint">冻结只下线这条渠道绑定；渠道冻结时该路由整体不可用</span></div>'
    + field('回退模型（逗号分隔，按顺序）', '<input class="input" id="r-fallbacks" placeholder="主模型全部渠道失败后依次尝试，如 backup-model, gpt-4o-mini">')
    + '<div class="field field-inline"><span class="field-label">默认模型</span>'
      + '<label class="switch"><input type="checkbox" id="r-isdefault"><span class="slider"></span></label>'
      + '<span class="field-label">该类型兜底默认模型（单一权威源）</span></div>'
    + '<p class="field-hint">参数默认值、键/值映射、响应映射都在「模型配置」维护；模型路由只负责绑定渠道和上游模型名。</p>'
    + '<div id="r-legacy-params" style="display:none">'
    + field('参数配置', '<div class="params-grid" id="params-group">' + (paramsGroups[curType] || paramsGroups.text) + '</div>')
    + field('本地模型（Ollama）', '<div id="ollama-section" style="display:none">' + ollamaBlockHtml() + '</div>')
    + field('参数覆盖（仅本路由）',
      '<p class="test-hint">发往该渠道前改写请求体：<code>set</code> 强制覆盖、<code>setIfAbsent</code> 仅缺失时补、<code>delete</code> 删除该渠道不支持的字段；支持点路径（如 <code>style.type</code>）。</p>'
      + '<div id="r-override-rows"></div>'
      + '<button type="button" class="btn btn-sm" id="r-override-add">+ 添加规则</button>')
    + field('参数 Schema（可选，JSON/YAML）',
      '<div style="display:flex;gap:6px;margin-bottom:6px;flex-wrap:wrap">'
      + '<button type="button" class="btn btn-sm" id="r-import-schema">从请求体 JSON 一键生成</button>'
      + '<button type="button" class="btn btn-sm" id="r-schema-example">填入示例</button>'
      + '<button type="button" class="btn btn-sm" id="r-schema-convert">转 YAML</button>'
      + '<button type="button" class="btn btn-sm" id="r-schema-validate">校验</button></div>'
      + '<div style="font-size:12px;color:var(--muted);margin-bottom:6px;line-height:1.8">'
      + '枚举(画幅/尺寸/风格)→改 options：<code>"options":["1:1","4:3","16:9"]</code><br>'
      + '范围(个数/时长/强度)→改 min 和 max：<code>"min":1,"max":8</code><br>'
      + '是否展示→改 show：<code>"show":true</code>(显示) / <code>"show":false</code>(隐藏但带默认值)<br>'
      + '响应映射→<code>"response":{"sync":{...}}</code> / <code>{"async":{...}}</code>，保存前点「校验」可定位错误</div>'
      + '<div class="schema-errors" id="r-schema-errors" style="display:none"></div>'
      + '<textarea class="input" id="r-schema" rows="12" spellcheck="false" placeholder=\'点「填入示例」得到可改的完整样例；留空=清空该模型的 Schema（走固定列）\'></textarea>')
    + '</div>'
    + '<details class="route-preview">'
      + '<summary>请求预览（不调用上游，不扣费）</summary>'
      + '<p class="test-hint">输入一段业务请求，查看经过「模型默认值 / 键值映射 / 上游模型名」后真正发给上游的 JSON。</p>'
      + '<textarea class="input" id="r-preview-body" rows="5" spellcheck="false" placeholder=\'{"model":"...","prompt":"a red apple"}\'></textarea>'
      + '<div style="display:flex;gap:8px;align-items:center;margin-top:6px">'
      + '<button type="button" class="btn btn-sm" id="r-preview-btn">预览最终请求</button>'
      + '<span class="field-hint">只计算，不发请求</span></div>'
      + '<pre class="mc-result" id="r-preview-out" style="display:none"></pre>'
    + '</details>';

  openModal(isEdit ? '编辑路由' : '新建路由', body, footerHtml('保存'));
  // 按默认渠道拉取模型列表（编辑时预填路由配置的模型名）
  loadModelsFor(selCh, 'r-model', route ? route.modelName : '').then(() => refreshRouteModelWarning());
  // 渠道切换 → 重新拉取该渠道模型列表
  $('#r-channel').onchange = () => {
    $('#r-model').value = '';
    loadModelsFor($('#r-channel').value, 'r-model');
    const lb = $('#p-local');
    if (lb) lb.checked = (channelMap[$('#r-channel').value] || {}).type === 'ollama';
    refreshOllamaBlock();
  };
  // 模型类型切换 → 重建参数配置组（按新类型显示对应字段组；切换后已填值不保留，先选类型再填）
  $('#r-type').onchange = () => {
    const t = $('#r-type').value;
    $('#params-group').innerHTML = paramsGroups[t] || paramsGroups.text;
    bindParamSliders();
    refreshOllamaBlock();
    refreshRouteModelWarning();
  };
  // 初始渲染后绑定滑块（input 实时值 + min<=max 联动）
  bindParamSliders();
  // 本地模型勾选：新建默认按渠道类型（ollama 勾选）；编辑由已保存 ollama_params.local 回显（见 loadModelParamsForEdit）
  const localCb = $('#p-local');
  if (localCb) localCb.checked = (channelMap[selCh] || {}).type === 'ollama';
  refreshOllamaBlock();
  // 编辑回显：默认模型 toggle 从路由数据加载（is_default 在 model_route 表）
  if (isEdit) {
    const cb = $('#r-isdefault');
    if (cb) cb.checked = !!route.isDefault;
  }
  // 路由运行参数回显
  $('#r-upstream').value = route ? (route.upstreamModel || '') : '';
  $('#r-priority').value = route && route.priority !== null && route.priority !== undefined ? route.priority : 0;
  $('#r-weight').value = route && route.weight !== null && route.weight !== undefined ? route.weight : 1;
  $('#r-maxretries').value = route && route.maxRetries !== null && route.maxRetries !== undefined ? route.maxRetries : 0;
  $('#r-enabled').checked = route ? route.enabled !== false : true;
  initOverrideRows(route ? route.paramOverrides : null);
  $('#r-override-add').onclick = () => addOverrideRow('set', '', '');
  $('#r-override-rows').addEventListener('click', (e) => {
    const btn = e.target.closest('.override-remove');
    if (btn) btn.closest('.override-row').remove();
  });
  schemaLang = 'json';
  const convertBtn = $('#r-schema-convert');
  if (convertBtn) convertBtn.textContent = '转 YAML';
  fallbacksLoaded = false;
  if (isEdit && route.modelName) loadFallbacksFor(route.modelName, curType);
  // 新建/切换模型时也回显该模型的回退链，避免用空数组误清
  $('#r-model').addEventListener('change', () => {
    const picked = $('#r-model').value.trim();
    if (!picked) return;
    fallbacksLoaded = false;
    loadFallbacksFor(picked, $('#r-type').value);
    refreshRouteModelWarning();
  });
  // 编辑回显：模型名存在 → 拉取已保存参数填当前类型组（type 与路由一致才填）
  routeParamsLoaded = false;
  if (isEdit && route.modelName) {
    loadModelParamsForEdit(route.modelName, curType);
  }
  $('#form-submit').onclick = () => submitRoute(isEdit ? route.id : null);
  const importSchemaBtn = $('#r-import-schema');
  if (importSchemaBtn) importSchemaBtn.onclick = importSchemaFromRequest;
  const schemaExampleBtn = $('#r-schema-example');
  if (schemaExampleBtn) schemaExampleBtn.onclick = fillSchemaExample;
  const schemaValidateBtn = $('#r-schema-validate');
  if (schemaValidateBtn) schemaValidateBtn.onclick = validateSchemaDraft;
  const schemaConvertBtn = $('#r-schema-convert');
  if (schemaConvertBtn) schemaConvertBtn.onclick = toggleSchemaLang;
  const schemaInput = $('#r-schema');
  if (schemaInput) schemaInput.addEventListener('input', () => renderSchemaErrors([], []));
  const previewBody = $('#r-preview-body');
  if (previewBody) {
    const pType = $('#r-type').value || 'image';
    const pModel = $('#r-model').value || (route && route.modelName) || '';
    previewBody.value = JSON.stringify(
      pType === 'video'
        ? { model: pModel, prompt: '一只橘猫在窗边', duration: 6, resolution: '768P' }
        : { model: pModel, prompt: '一只橘猫在窗边' },
      null, 2);
  }
  const previewBtn = $('#r-preview-btn');
  if (previewBtn) previewBtn.onclick = previewRouteRequest;
  const warnBox = $('#r-model-config-warning');
  if (warnBox) {
    warnBox.onclick = (e) => {
      if (!e.target.closest('[data-action="go-model-config"]')) return;
      const model = warnBox.dataset.model || '';
      const type = warnBox.dataset.type || 'text';
      closeModal();
      mcLastKey = model + '|' + type;
      mcFilter = model;
      mcTypeFilter = type;
      mcChannelFilter = '';
      location.hash = '#/models';
    };
  }
  setTimeout(refreshRouteModelWarning, 600);
}

/** 路由弹窗：模型没有 Schema 时提示去模型配置，而不是让路由页重复配参数 */
async function refreshRouteModelWarning() {
  const box = $('#r-model-config-warning');
  if (!box) return;
  const model = (($('#r-model') && $('#r-model').value) || '').trim();
  const type = ($('#r-type') && $('#r-type').value) || 'text';
  if (!model) { box.style.display = 'none'; return; }
  try {
    const mp = await api('/admin/model-params?modelName=' + encodeURIComponent(model)
      + '&type=' + encodeURIComponent(type));
    const hasSpec = mp && mp.paramsSchema && String(mp.paramsSchema).trim();
    if (hasSpec) { box.style.display = 'none'; return; }
    box.dataset.model = model;
    box.dataset.type = type;
    box.innerHTML = '<div><strong>该模型还没有参数配置</strong>：请求默认值、键/值映射、响应解析不会生效。'
      + '请先到「模型配置」补上，再回来绑定渠道。</div>'
      + '<button type="button" class="btn btn-sm" data-action="go-model-config">去模型配置</button>';
    box.style.display = '';
  } catch (e) {
    box.style.display = 'none';
  }
}

/** 路由弹窗：预览最终发给上游的请求（不调用上游） */
async function previewRouteRequest() {
  const btn = $('#r-preview-btn');
  const out = $('#r-preview-out');
  const body = ($('#r-preview-body') && $('#r-preview-body').value.trim()) || '';
  if (!body) { toast('先填一段请求 JSON', 'info'); return; }
  try { JSON.parse(body); } catch (e) { toast('请求体不是合法 JSON：' + (e.message || e), 'error'); return; }
  const type = $('#r-type').value;
  const payload = {
    modelName: $('#r-model').value.trim(),
    type,
    channelId: $('#r-channel').value,
    body,
  };
  if (type === 'image') payload.path = '/images/generations';
  if (type === 'text' || type === 'vision') payload.path = '/chat/completions';
  if (btn) btn.disabled = true;
  try {
    const r = await api('/admin/routes/preview', { method: 'POST', body: JSON.stringify(payload) });
    const pretty = (s) => { try { return JSON.stringify(JSON.parse(s), null, 2); } catch (e) { return s || ''; } };
    const lines = [
      '渠道：' + (r.channelName || '') + '（' + (r.channelType || '') + '）',
      '目标 URL：' + (r.targetUrl || '由适配器构建'),
      '上游模型名：' + (r.upstreamModel || ''),
      '模型 Schema：' + (r.specFound ? '已配置' : '未配置'),
    ];
    if (r.note) lines.push('说明：' + r.note);
    lines.push('', '步骤：');
    (r.steps || []).forEach((s) => lines.push('  - ' + s));
    lines.push('', '[原始请求]', pretty(r.original));
    lines.push('', '[模型默认值/映射后]', pretty(r.afterSpec));
    lines.push('', '[最终发给上游]', pretty(r.forwarded));
    out.style.display = '';
    out.textContent = lines.join('\n');
  } catch (e) {
    handleErr(e);
  } finally {
    if (btn) btn.disabled = false;
  }
}

/** 读取该模型的跨模型回退链，填入路由弹窗 */
async function loadFallbacksFor(modelName, type) {
  try {
    const list = await api('/admin/fallbacks?modelName=' + encodeURIComponent(modelName)
      + '&type=' + encodeURIComponent(type));
    const el = $('#r-fallbacks');
    if (el) el.value = (list || []).join(', ');
    fallbacksLoaded = true;
  } catch (e) {
    console.warn('回退链回显失败（不阻塞编辑）：', e && e.message ? e.message : e);
  }
}

/** 参数覆盖行编辑器：把 route.paramOverrides 解析成 set/setIfAbsent/delete 三类行 */
function initOverrideRows(raw) {
  const container = $('#r-override-rows');
  if (!container) return;
  container.innerHTML = '';
  let parsed = null;
  try { parsed = raw ? JSON.parse(raw) : null; } catch (e) { parsed = null; }
  if (parsed && (parsed.set || parsed.setIfAbsent || parsed.delete || parsed.rename)) {
    Object.entries(parsed.rename || {}).forEach(([k, v]) => addOverrideRow('rename', k, String(v)));
    Object.entries(parsed.set || {}).forEach(([k, v]) => addOverrideRow('set', k, formatOverrideValue(v)));
    Object.entries(parsed.setIfAbsent || {}).forEach(([k, v]) => addOverrideRow('setIfAbsent', k, formatOverrideValue(v)));
    (parsed.delete || []).forEach((k) => addOverrideRow('delete', String(k), ''));
  } else if (parsed) {
    // 兼容旧 default_params：整个对象按 set 展示
    Object.entries(parsed).forEach(([k, v]) => addOverrideRow('set', k, formatOverrideValue(v)));
  }
  if (!container.children.length) addOverrideRow('set', '', '');
}

function formatOverrideValue(value) {
  return typeof value === 'string' ? value : JSON.stringify(value);
}

/** 追加一行覆盖规则 */
function addOverrideRow(op, key, value) {
  const container = $('#r-override-rows');
  if (!container) return;
  const row = document.createElement('div');
  row.className = 'override-row';
  row.innerHTML = '<select class="input ov-op">'
    + '<option value="set">set（强制覆盖）</option>'
    + '<option value="setIfAbsent">setIfAbsent（缺失才补）</option>'
    + '<option value="delete">delete（删除字段）</option>'
    + '<option value="rename">rename（改键名）</option>'
    + '</select>'
    + '<input class="input ov-key" placeholder="参数名，如 pictures">'
    + '<input class="input ov-value" placeholder="值；rename 时填新键名">'
    + '<button type="button" class="btn btn-sm override-remove" title="删除">×</button>';
  container.appendChild(row);
  row.querySelector('.ov-op').value = op;
  row.querySelector('.ov-key').value = key || '';
  row.querySelector('.ov-value').value = value || '';
  const opEl = row.querySelector('.ov-op');
  const valueEl = row.querySelector('.ov-value');
  const sync = () => {
    valueEl.disabled = opEl.value === 'delete';
    if (valueEl.disabled) valueEl.value = '';
  };
  opEl.addEventListener('change', sync);
  sync();
}

/** 收集覆盖行 → param_overrides JSON；无有效行返回 null */
function collectOverrides() {
  const rows = Array.from(document.querySelectorAll('#r-override-rows .override-row'));
  const set = {};
  const setIfAbsent = {};
  const rename = {};
  const del = [];
  rows.forEach((row) => {
    const op = row.querySelector('.ov-op').value;
    const key = row.querySelector('.ov-key').value.trim();
    if (!key) return;
    if (op === 'delete') {
      del.push(key);
      return;
    }
    const raw = row.querySelector('.ov-value').value;
    if (op === 'rename') {
      if (raw.trim()) rename[key] = raw.trim();
      return;
    }
    let value = raw;
    try { value = JSON.parse(raw); } catch (e) { /* 文本值原样保留 */ }
    (op === 'set' ? set : setIfAbsent)[key] = value;
  });
  const out = {};
  if (Object.keys(set).length) out.set = set;
  if (Object.keys(setIfAbsent).length) out.setIfAbsent = setIfAbsent;
  if (Object.keys(rename).length) out.rename = rename;
  if (del.length) out.delete = del;
  return Object.keys(out).length ? JSON.stringify(out) : null;
}

/** Schema JSON ↔ YAML 切换（服务端转换，避免前端引 YAML 库） */
async function toggleSchemaLang() {
  const el = $('#r-schema');
  const btn = $('#r-schema-convert');
  const text = el ? el.value.trim() : '';
  if (!text) { toast('先填入 Schema 再转换格式', 'info'); return; }
  const target = schemaLang === 'json' ? 'yaml' : 'json';
  if (btn) btn.disabled = true;
  try {
    const res = await api('/admin/model-params/schema/convert', {
      method: 'POST',
      body: JSON.stringify({ content: text, from: schemaLang }),
    });
    el.value = target === 'yaml' ? res.yaml : res.json;
    schemaLang = target;
    if (btn) btn.textContent = target === 'yaml' ? '转 JSON' : '转 YAML';
    renderSchemaErrors([], []);
  } catch (e) {
    handleErr(e);
  } finally {
    if (btn) btn.disabled = false;
  }
}

/** 当前编辑器里的 Schema 统一转成 JSON（YAML 模式先转换） */
async function schemaAsJson() {
  const text = ($('#r-schema') && $('#r-schema').value.trim()) || '';
  if (!text || schemaLang === 'json') return text;
  const res = await api('/admin/model-params/schema/convert', {
    method: 'POST',
    body: JSON.stringify({ content: text, from: 'yaml' }),
  });
  return res.json;
}

/**
 * 编辑回显：GET /admin/model-params?modelName=X（查询参数，兼容含 / 的 Ollama 模型名），type 与路由一致时填当前类型组输入框
 * 未配置 / 类型不一致 → 不填（提示先选类型）；回显失败不阻塞编辑
 */
async function loadModelParamsForEdit(modelName, type) {
  try {
    const mp = await api('/admin/model-params?modelName=' + encodeURIComponent(modelName) + '&type=' + encodeURIComponent(type));
    if (!mp) { routeParamsLoaded = true; return; }
    // 按 (model_name, type) 返回，同一模型 text/vision 各一套参数，此处已按当前路由类型取到对应行
    // isDefault 由路由数据回显（见 openRouteModal 编辑分支），此处不覆盖
    // 设置控件值：滑块同时更新右侧值显示；字符串输入直接赋值
    const set = (id, v) => {
      if (v === null || v === undefined || v === '') return;
      const el = $('#' + id);
      if (!el) return;
      el.value = v;
      const span = $(id + '-val');
      if (span) span.textContent = v;
    };
    set('p-contextwindow', mp.contextWindow);
    set('p-nmin', mp.nMin);
    set('p-nmax', mp.nMax);
    set('p-ndefault', mp.nDefault);
    set('p-sizes', mp.sizes);
    set('p-sizedefault', mp.sizeDefault);
    set('p-qualities', mp.qualities);
    set('p-qualitydefault', mp.qualityDefault);
    set('p-styles', mp.styles);
    set('p-styledefault', mp.styleDefault);
    // 时长：存的是逗号分隔档位（如 4,6,8 或滑块展开的连续 4,5,6,7,8）→ 拆出 min/max 设范围滑块
    if (mp.durations) {
      const ds = String(mp.durations).split(',').map((s) => Number(s.trim())).filter((n) => !isNaN(n));
      if (ds.length) {
        const mn = Math.min(...ds), mx = Math.max(...ds);
        set('p-dmin', mn);
        set('p-dmax', mx);
      }
    }
    set('p-durationdefault', mp.durationDefault);
    set('p-resolutions', mp.resolutions);
    set('p-resolutiondefault', mp.resolutionDefault);
    set('p-aspects', mp.aspectRatios);
    set('p-aspectdefault', mp.aspectRatioDefault);
    set('p-refimagesmin', mp.refImagesMin);
    set('p-refimagesmax', mp.refImagesMax);
    set('p-refvideosmin', mp.refVideosMin);
    set('p-refvideosmax', mp.refVideosMax);
    set('p-audiocountmin', mp.audioCountMin);
    set('p-audiocountmax', mp.audioCountMax);
    set('p-audiodmin', mp.audioSegmentDurationMin);
    set('p-audiodmax', mp.audioSegmentDurationMax);
    set('p-videodmin', mp.videoSegmentDurationMin);
    set('p-videodmax', mp.videoSegmentDurationMax);
    set('p-maxtotalduration', mp.maxTotalDuration);
    set('p-maxtotalfiles', mp.maxTotalFiles);
    set('p-maxvideosize', mp.maxVideoSizeMb);
    set('p-maximagesize', mp.maxImageSizeMb);
    set('p-maxaudiosize', mp.maxAudioSizeMb);
    set('p-maxbody', mp.maxRequestBodyMb);
    set('p-maxprompt', mp.maxPromptChars);
    // 本地/Ollama 参数回显：解析 ollama_params JSON 填充本地模型区块
    if (mp.ollamaParams) {
      try {
        const o = typeof mp.ollamaParams === 'string' ? JSON.parse(mp.ollamaParams) : mp.ollamaParams;
        if (o && typeof o === 'object') {
          const localEl = $('#p-local'); if (localEl) localEl.checked = !!o.local;
          const thinkEl = $('#p-think'); if (thinkEl) thinkEl.checked = !!o.think;
          set('p-thinklevel', o.think_level || 'high');
          set('p-numctx', o.num_ctx);
          set('p-seed', o.seed);
          set('p-stop', Array.isArray(o.stop) ? o.stop.join(',') : (o.stop || ''));
          set('p-keepalive', o.keep_alive);
        }
      } catch (pe) {
        console.warn('ollama_params 解析失败：', pe && pe.message ? pe.message : pe);
      }
    }
    const schemaEl = $('#r-schema');
    if (schemaEl && mp.paramsSchema) schemaEl.value = mp.paramsSchema;
    // 回显后按渠道/本地状态刷新本地模型区块显隐
    refreshOllamaBlock();
    // 只有成功回显过，才允许把空输入当「清空」
    routeParamsLoaded = true;
    // 其余字段（p-durations 旧输入框已由滑块替代，无对应元素自动跳过）
  } catch (e) {
    console.warn('模型参数回显失败（不阻塞编辑）：', e && e.message ? e.message : e);
  }
}

async function submitRoute(id) {
  const modelName = $('#r-model').value.trim();
  const channelId = $('#r-channel').value;

  if (!modelName) { toast('模型名不能为空', 'error'); return; }
  if (!channelId) { toast('请选择渠道（需先在渠道管理创建）', 'error'); return; }

  const intOf = (sel, def) => {
    const n = parseInt(($(sel) || {}).value, 10);
    return Number.isFinite(n) ? n : def;
  };
  const upstreamModel = $('#r-upstream').value.trim();
  const fallbacks = $('#r-fallbacks').value
    .split(/[,，]/).map((s) => s.trim()).filter(Boolean);

  const body = {
    modelName,
    channelId,
    type: $('#r-type').value,
    isDefault: $('#r-isdefault') ? $('#r-isdefault').checked : false,
    enabled: $('#r-enabled') ? $('#r-enabled').checked : true,
    priority: intOf('#r-priority', 0),
    weight: Math.max(1, intOf('#r-weight', 1)),
    upstreamModel: upstreamModel,
    maxRetries: Math.max(0, intOf('#r-maxretries', 0)),
    fallbacks,
  };
  // 只有「已回显」或用户明确填了值时才提交回退链，否则不覆盖已有配置
  if (fallbacks.length > 0 || fallbacksLoaded) {
    body.fallbacks = fallbacks;
  } else {
    delete body.fallbacks;
  }
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

/**
 * 组装 ModelParamsRequest 并 PUT /admin/model-params（upsert）。
 *
 * 空输入不再序列化成 null 直接丢弃（服务端把 null 当「不修改」，会清不掉旧值），
 * 而是收集进 clearFields，由服务端显式 SET col = null。
 *
 * @param {boolean} allowClear 仅编辑态为 true：新建路由没有回显过共享参数，
 *        空值必须继续按「不修改」处理，否则会误删同一模型在其它渠道上的配置。
 */
async function saveModelParams(modelName, type, allowClear, schemaJson) {
  if (!modelName) return;
  const clearFields = [];
  const req = { modelName, type };
  const val = (id) => { const el = $('#' + id); const s = el ? el.value.trim() : ''; return s === '' ? null : s; };
  const num = (id) => { const s = val(id); return s === null ? null : Number(s); };
  /** 有值提交；空值在编辑态登记为「清空」，新建态按不修改处理 */
  const put = (field, value) => {
    if (value === null || value === undefined || value === '') {
      if (allowClear) clearFields.push(field);
    } else {
      req[field] = value;
    }
  };
  // isDefault 已随路由保存（PUT /admin/routes），此处不再重复
  if (type === 'text' || type === 'vision') {
    // temperature / max_tokens / top_p 不在这里提交：网关从不注入它们，
    // 提交 null 反而会把历史值清掉；需要采样参数时由调用方在请求里显式给。
    put('contextWindow', num('p-contextwindow'));
    // 本地/Ollama 扩展参数：仅「本地模型」开关勾选时落库；未勾选登记清空
    const localEl = $('#p-local');
    if (localEl && localEl.checked) {
      const thinkEl = $('#p-think');
      const lvlEl = $('#p-thinklevel');
      const stopVal = val('p-stop');
      const o = {
        local: true,
        think: thinkEl ? thinkEl.checked : false,
        think_level: lvlEl ? lvlEl.value : 'high',
        num_ctx: num('p-numctx'),
        seed: num('p-seed'),
        stop: stopVal ? stopVal.split(',').map((s) => s.trim()).filter(Boolean) : null,
        keep_alive: val('p-keepalive'),
      };
      // 空值不落库，避免 "keep_alive":null 被后端解析成字符串 "null" 传给 Ollama
      Object.keys(o).forEach((k) => {
        const v = o[k];
        if (v === null || v === undefined || (Array.isArray(v) && v.length === 0)) delete o[k];
      });
      req.ollamaParams = JSON.stringify(o);
    } else {
      if (allowClear) clearFields.push('ollamaParams');
    }
  } else if (type === 'image') {
    put('nMin', num('p-nmin'));
    put('nMax', num('p-nmax'));
    put('nDefault', num('p-ndefault'));
    put('sizes', val('p-sizes'));
    put('sizeDefault', val('p-sizedefault'));
    put('qualities', val('p-qualities'));
    put('qualityDefault', val('p-qualitydefault'));
    put('styles', val('p-styles'));
    put('styleDefault', val('p-styledefault'));
  } else if (type === 'video') {
    // 时长范围滑块（p-dmin/p-dmax）→ 展开连续整数逗号分隔（如 4~8 → "4,5,6,7,8"）
    const dMin = num('p-dmin'), dMax = num('p-dmax');
    const seq = [];
    if (dMin !== null && dMax !== null && dMin <= dMax) {
      for (let s = dMin; s <= dMax; s++) seq.push(s);
    }
    put('durations', seq.length ? seq.join(',') : null);
    put('durationDefault', num('p-durationdefault') !== null ? String(num('p-durationdefault')) : null);
    put('resolutions', val('p-resolutions'));
    put('resolutionDefault', val('p-resolutiondefault'));
    put('aspectRatios', val('p-aspects'));
    put('aspectRatioDefault', val('p-aspectdefault'));
    put('refImagesMin', num('p-refimagesmin'));
    put('refImagesMax', num('p-refimagesmax'));
    put('refVideosMin', num('p-refvideosmin'));
    put('refVideosMax', num('p-refvideosmax'));
    put('audioCountMin', num('p-audiocountmin'));
    put('audioCountMax', num('p-audiocountmax'));
    put('audioSegmentDurationMin', num('p-audiodmin'));
    put('audioSegmentDurationMax', num('p-audiodmax'));
    put('videoSegmentDurationMin', num('p-videodmin'));
    put('videoSegmentDurationMax', num('p-videodmax'));
    put('maxTotalDuration', num('p-maxtotalduration'));
    put('maxTotalFiles', num('p-maxtotalfiles'));
    put('maxVideoSizeMb', num('p-maxvideosize'));
    put('maxImageSizeMb', num('p-maximagesize'));
    put('maxAudioSizeMb', num('p-maxaudiosize'));
    put('maxRequestBodyMb', num('p-maxbody'));
    put('maxPromptChars', num('p-maxprompt'));
  }
  const schemaText = schemaJson !== undefined
    ? String(schemaJson || '').trim()
    : (function () { const el = $('#r-schema'); return el ? el.value.trim() : ''; })();
  if (schemaText) {
    // 保存前已由 submitRoute 校验过一次；这里再兜底一次（避免从其它入口直接调）
    try { JSON.parse(schemaText); } catch (e) {
      toast('参数 Schema 不是合法 JSON：' + (e.message || e), 'error');
      return;
    }
    req.paramsSchema = schemaText;
  } else {
    if (allowClear) clearFields.push('paramsSchema');
  }
  if (clearFields.length) req.clearFields = [...new Set(clearFields)];
  await api('/admin/model-params', { method: 'PUT', body: JSON.stringify(req) });
}

/** 渲染 Schema 校验结果：错误（阻止保存）与提示（可保存）分开列 */
function renderSchemaErrors(errors, warnings) {
  const box = $('#r-schema-errors');
  if (!box) return;
  const es = Array.isArray(errors) ? errors : [];
  const ws = Array.isArray(warnings) ? warnings : [];
  if (!es.length && !ws.length) {
    box.style.display = 'none';
    box.innerHTML = '';
    return;
  }
  const block = (items, cls, title) =>
    '<div class="schema-errors-title ' + cls + '">' + title + '（' + items.length + '）</div>'
    + '<ul>' + items.slice(0, 8).map((i) =>
      '<li><code>' + esc(i.path || '$') + '</code> ' + esc(i.message || '') + '</li>').join('') + '</ul>'
    + (items.length > 8 ? '<div class="schema-errors-more">还有 ' + (items.length - 8) + ' 项未显示</div>' : '');
  box.innerHTML = (es.length ? block(es, 'is-error', '错误') : '')
    + (ws.length ? block(ws, 'is-warn', '提示') : '');
  box.style.display = 'block';
}

/** 校验当前 Schema 草稿（保存前也会自动调用） */
async function validateSchemaDraft() {
  let text = ($('#r-schema') && $('#r-schema').value.trim()) || '';
  if (!text) {
    renderSchemaErrors([], []);
    toast('Schema 为空：保存后将清空该模型的 Schema，回到固定列', 'info');
    return;
  }
  const btn = $('#r-schema-validate');
  if (btn) btn.disabled = true;
  try {
    if (schemaLang === 'yaml') {
      const converted = await api('/admin/model-params/schema/convert', {
        method: 'POST',
        body: JSON.stringify({ content: text, from: 'yaml' }),
      });
      text = converted.json;
    }
    const res = await api('/admin/model-params/validate', {
      method: 'POST',
      body: JSON.stringify({ paramsSchema: text }),
    });
    renderSchemaErrors(res.errors, res.warnings);
    if (res.valid) {
      toast('Schema 校验通过' + (res.warnings && res.warnings.length ? '（' + res.warnings.length + ' 条提示）' : ''), 'success');
    } else {
      toast('Schema 有 ' + res.errors.length + ' 处错误', 'error');
    }
  } catch (e) {
    handleErr(e);
  } finally {
    if (btn) btn.disabled = false;
  }
}

/** 从请求体 JSON 生成参数 Schema 骨架。 */
function _isUrlLike(v) { return typeof v === 'string' && /^(https?:\/\/|data:image)/i.test(v.trim()); }
function _guessField(k, val) {
  const p = { key: k, label: k };
  if (val === null || val === undefined) {
    p.type = 'string';
  } else if (typeof val === 'string') {
    p.type = 'string';
    if (_isUrlLike(val)) { p.role = 'image_upload'; p.cardinality = 'single'; }
  } else if (typeof val === 'number') {
    // 数值骨架：min/max 必须包住 default，否则刚生成的 Schema 过不了校验（如 n:0 / seed:-1）
    p.type = 'range';
    p.min = Math.min(0, val);
    p.max = Math.max(1, val);
    p.default = val;
  } else if (typeof val === 'boolean') {
    p.type = 'bool'; p.default = val;
  } else if (Array.isArray(val)) {
    if (val.length && typeof val[0] === 'object') { p.type = 'arrayofobject'; p.fields = _guessFields(val[0]); }
    else if (val.length && _isUrlLike(val[0])) { p.type = 'array'; p.role = 'image_upload'; p.cardinality = 'multi'; }
    else p.type = 'array';
  } else if (val && typeof val === 'object') {
    p.type = 'object'; p.fields = _guessFields(val);
  }
  return p;
}
function _guessFields(obj) {
  return Object.entries(obj || {}).filter((e) => e[0] !== 'model' && e[0] !== 'prompt').map((e) => _guessField(e[0], e[1]));
}
function importSchemaFromRequest() {
  const raw = prompt('粘贴完整请求体 JSON（含 model/prompt 等键值对），自动解析生成参数 Schema 骨架：');
  if (raw === null) return;
  let req;
  try { req = JSON.parse(raw); } catch (e) { toast('请求体不是合法 JSON：' + (e.message || e), 'error'); return; }
  const spec = { modes: [], promptKey: 'prompt', request: _guessFields(req) };
  const sc = $('#r-schema');
  if (sc) sc.value = JSON.stringify(spec, null, 2);
  schemaLang = 'json';
  const convertBtn = $('#r-schema-convert');
  if (convertBtn) convertBtn.textContent = '转 YAML';
  toast('已生成 Schema 骨架，请补充 enum 选项/range 范围/角色标注后保存', 'success');
}

function fillSchemaExample() {
  const ex = '{\n'
    + '  "modes": ["text-to-image", "image-to-image"],\n'
    + '  "promptKey": "prompt",\n'
    + '  "request": [\n'
    + '    {"key": "aspect_ratio", "label": "画幅", "type": "enum", "options": ["1:1", "4:3", "16:9", "21:9"], "default": "4:3", "show": true},\n'
    + '    {"key": "n", "label": "生成个数", "type": "range", "min": 1, "max": 4, "default": 1, "show": true},\n'
    + '    {"key": "style", "label": "风格", "type": "object", "fields": [\n'
    + '      {"key": "style_type", "label": "风格类型", "type": "enum", "options": ["www", "photo"], "default": "www", "show": true},\n'
    + '      {"key": "style_weight", "label": "强度", "type": "range", "min": 1, "max": 10, "default": 5, "show": true}],\n'
    + '    "show": true},\n'
    + '    {"key": "prompt_optimizer", "label": "提示词优化", "type": "bool", "default": true, "show": false}\n'
    + '  ]\n'
    + '}';
  const sc = $('#r-schema');
  if (sc) sc.value = ex;
  schemaLang = 'json';
  const convertBtn = $('#r-schema-convert');
  if (convertBtn) convertBtn.textContent = '转 YAML';
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
  } else if (action === 'toggle-route') {
    toggleRoute(id, btn.dataset.next === 'true', btn);
  }
}

/** 路由启动/冻结：只改 enabled，渠道冻结时按钮已禁用 */
async function toggleRoute(id, enabled, btn) {
  if (btn) btn.disabled = true;
  try {
    await api('/admin/routes/' + id, { method: 'PUT', body: JSON.stringify({ enabled }) });
    toast(enabled ? '路由已启动' : '路由已冻结', 'success');
    loadRoutes();
  } catch (e) {
    if (btn) btn.disabled = false;
    handleErr(e);
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

/** 路由测试：直接测该路由当前配置的模型（走真实网关链路，会落一条 CallLog） */
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
    + '<button type="button" class="btn" id="log-refresh">刷新</button>'
    + '<label class="field-inline" style="font-size:13px;color:var(--ink-soft);cursor:pointer"><input type="checkbox" id="log-autopoll" style="accent-color:var(--primary)"> 自动刷新</label>'
    + '</div></div>'
    + '<div class="card table-card">'
    + (records.length
      ? '<table class="table"><thead><tr>'
        + '<th>模型</th><th>渠道</th><th>状态</th><th>HTTP</th><th>耗时</th><th>Tokens</th><th>错误</th><th>时间</th>'
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
            + '<td class="td-muted">' + (r.httpStatus ? esc(r.httpStatus) : '-') + '</td>'
            + '<td>' + (r.durationMs === null || r.durationMs === undefined ? '-' : esc(r.durationMs) + ' ms') + '</td>'
            + '<td class="td-muted">' + (r.totalTokens ? esc(r.totalTokens) : '-') + '</td>'
            + '<td class="td-error">' + (hasError ? esc(trunc(r.error, 60)) : '<span class="td-muted">-</span>') + '</td>'
            + '<td class="td-muted">' + fmtTime(r.createdAt) + '</td>'
            + '</tr>'
            + (hasError
              ? '<tr class="log-detail" id="log-detail-' + idx + '" hidden><td colspan="8">' + esc(r.error) + '</td></tr>'
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
  // 刷新按钮
  const refreshBtn = $('#log-refresh');
  if (refreshBtn) refreshBtn.onclick = () => loadLogs();
  // 自动轮询（30s）
  const autoPoll = $('#log-autopoll');
  if (autoPoll) {
    let pollTimer = null;
    autoPoll.onchange = () => {
      if (autoPoll.checked) {
        pollTimer = setInterval(() => { if (currentSection() === 'logs') loadLogs(); }, 30000);
      } else {
        clearInterval(pollTimer); pollTimer = null;
      }
    };
  }
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
    if (pwd.length < 8) { toast('密码至少8位', 'error'); return; }
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

/** 系统配置（sys_config）：GET 回显键值表单，PUT 批量保存（落库，重启网关后生效） */
async function loadConfig() {
  const view = $('#view-config');
  view.innerHTML = loadingHtml;
  try {
    const items = await api('/admin/config');
    view.innerHTML =
      '<div class="view-head"><h2>系统配置</h2>'
      + '<div class="filter-bar"><input class="input" id="cfg-search" placeholder="搜索配置键或说明…" style="width:240px"></div></div>'
      + '<div class="card table-card">'
      + '<table class="table" id="cfg-table"><thead><tr><th style="width:34%">配置键</th><th>说明</th><th>值</th><th style="width:14%">更新时间</th></tr></thead><tbody>'
      + (items.length
        ? items.map((c) => '<tr>'
            + '<td class="td-strong"><code>' + esc(c.key) + '</code></td>'
            + '<td class="td-muted">' + esc(c.remark || '') + '</td>'
            + '<td><input class="input cfg-value" data-key="' + esc(c.key) + '" value="' + esc(c.value) + '"></td>'
            + '<td class="td-muted">' + fmtTime(c.updatedAt) + '</td>'
            + '</tr>').join('')
        : '<tr><td colspan="4">' + emptyHtml('暂无配置项') + '</td></tr>')
      + '</tbody></table></div>'
      + '<div class="view-actions" style="margin-top:12px;display:flex;align-items:center;gap:12px">'
      + '<p class="field-hint" style="margin:0">修改仅落库，重启网关后生效（不做热更新）；非法值会被后端拒绝。</p>'
      + '<button type="button" class="btn btn-primary" data-action="save-config">保存</button>'
      + '</div>';
  } catch (e) {
    view.innerHTML = errHtml(e.message);
    handleErr(e);
  }
}

/** 保存：收集表单全部键值 → PUT /admin/config → 重新回显 */
async function saveConfig() {
  const inputs = Array.from(document.querySelectorAll('#view-config .cfg-value'));
  if (!inputs.length) return;
  const items = inputs.map((el) => ({ key: el.dataset.key, value: el.value.trim() }));
  const btn = document.querySelector('#view-config [data-action="save-config"]');
  if (btn) { btn.disabled = true; btn.textContent = '保存中…'; }
  try {
    const updated = await api('/admin/config', { method: 'PUT', body: JSON.stringify({ items }) });
    // 回显后端返回的最新值
    updated.forEach((c) => {
      const el = document.querySelector('#view-config .cfg-value[data-key="' + CSS.escape(c.key) + '"]');
      if (el) el.value = c.value;
    });
    toast('已保存，重启网关后生效', 'success');
  } catch (e) {
    handleErr(e);
  } finally {
    if (btn) { btn.disabled = false; btn.textContent = '保存'; }
  }
}

/* ================= 模型配置 #/models（无代码工作台） ================= */

const MC_MODES = {
  image: ['text-to-image', 'image-to-image'],
  video: ['text-to-video', 'image-to-video', 'reference-to-video'],
  text: ['chat'],
  vision: ['vision'],
};
const MC_PARAM_TYPES = ['enum', 'range', 'number', 'bool', 'string', 'object', 'array', 'arrayofobject'];
const MC_PROTOCOLS = ['openai_json', 'openai_compatible', 'multipart', 'gemini', 'minimax', 'ollama'];

/** 标准参数目录：键 = 业务/Agent 真实请求里的字段名；选标准键可自动补显示名/类型/角色 */
const MC_STANDARD_PARAMS = {
  image: [
    { key: 'size', label: '尺寸', type: 'enum' },
    { key: 'aspect_ratio', label: '画幅', type: 'enum' },
    { key: 'n', label: '生成数量', type: 'range', min: 1, max: 15, default: 1 },
    { key: 'image', label: '参考图', type: 'string', role: 'image_upload', cardinality: 'multi' },
    { key: 'mask', label: '蒙版', type: 'string', role: 'image_upload', cardinality: 'single' },
    { key: 'reference_images', label: '参考图列表', type: 'array', role: 'image_upload', cardinality: 'multi' },
    { key: 'quality', label: '质量', type: 'enum' },
    { key: 'style', label: '风格', type: 'string' },
    { key: 'response_format', label: '响应格式', type: 'enum', options: ['url', 'b64_json'] },
    { key: 'watermark', label: '水印', type: 'bool', default: false },
    { key: 'seed', label: '随机种子', type: 'number' },
    { key: 'negative_prompt', label: '负向提示词', type: 'string' },
  ],
  video: [
    { key: 'duration', label: '时长（秒）', type: 'range', min: 4, max: 15 },
    { key: 'resolution', label: '分辨率', type: 'enum' },
    { key: 'aspect_ratio', label: '画幅', type: 'enum' },
    { key: 'image', label: '首帧图', type: 'string', role: 'image_upload', cardinality: 'single' },
    { key: 'reference_images', label: '参考图', type: 'array', role: 'image_upload', cardinality: 'multi' },
    { key: 'negative_prompt', label: '负向提示词', type: 'string' },
    { key: 'seed', label: '随机种子', type: 'number' },
    { key: 'fps', label: '帧率', type: 'number' },
  ],
  text: [
    { key: 'temperature', label: '温度', type: 'number' },
    { key: 'max_tokens', label: '最大输出', type: 'number' },
    { key: 'top_p', label: 'Top P', type: 'number' },
    { key: 'frequency_penalty', label: '频率惩罚', type: 'number' },
    { key: 'presence_penalty', label: '存在惩罚', type: 'number' },
    { key: 'response_format', label: '响应格式', type: 'string' },
    { key: 'stream', label: '流式', type: 'bool' },
    { key: 'seed', label: '随机种子', type: 'number' },
  ],
  vision: [
    { key: 'image_url', label: '图片地址', type: 'string' },
    { key: 'detail', label: '细节级别', type: 'enum', options: ['auto', 'low', 'high'] },
    { key: 'max_tokens', label: '最大输出', type: 'number' },
  ],
};

/** 一键模板：模型配置 + 路由一次生成；渠道可复用或新建 */
const MC_TEMPLATES = {
  'doubao-4': {
    label: '豆包 Seedream 4.0（生图）',
    type: 'image',
    model: 'doubao-seedream-4-0-250828',
    channelName: '豆包生成',
    channelType: 'openai_compatible',
    baseUrl: 'https://ark.cn-beijing.volces.com/api/v3',
    spec: () => ({
      schemaVersion: 2, protocol: 'openai_json', promptKey: 'prompt',
      modes: ['text-to-image', 'image-to-image'],
      request: [
        { key: 'size', label: '尺寸', type: 'enum', options: ['1K', '2K', '4K'], default: '2K', injectDefault: true },
        { key: 'n', label: '生成数量', type: 'range', min: 1, max: 15, default: 1 },
        { key: 'image', label: '参考图', type: 'string', role: 'image_upload', cardinality: 'multi',
          maxItems: 14, visibleWhen: { mode: 'image-to-image' }, requiredWhen: { mode: 'image-to-image' } },
        { key: 'watermark', label: '水印', type: 'bool', default: false, injectDefault: true, show: false },
        { key: 'response_format', label: '响应格式', type: 'enum', options: ['url', 'b64_json'], default: 'url', injectDefault: true, show: false },
      ],
      response: { sync: { dataPath: 'data', mediaField: 'b64_json', fallbackField: 'url', stripDataUri: true } },
    }),
  },
  'doubao-5-flash': {
    label: '豆包 Seedream 5.0 Flash（生图，单图）',
    type: 'image',
    model: 'doubao-seedream-5-0-flash-260915',
    channelName: '豆包生成',
    channelType: 'openai_compatible',
    baseUrl: 'https://ark.cn-beijing.volces.com/api/v3',
    spec: () => ({
      schemaVersion: 2, protocol: 'openai_json', promptKey: 'prompt',
      modes: ['text-to-image', 'image-to-image'],
      request: [
        { key: 'size', label: '尺寸', type: 'enum', options: ['1K', '1.5K', '2K'], default: '2K', injectDefault: true },
        { key: 'image', label: '参考图', type: 'string', role: 'image_upload', cardinality: 'multi',
          maxItems: 10, visibleWhen: { mode: 'image-to-image' }, requiredWhen: { mode: 'image-to-image' } },
        { key: 'watermark', label: '水印', type: 'bool', default: false, injectDefault: true, show: false },
        { key: 'response_format', label: '响应格式', type: 'enum', options: ['url', 'b64_json'], default: 'url', injectDefault: true, show: false },
      ],
      response: { sync: { dataPath: 'data', mediaField: 'b64_json', fallbackField: 'url', stripDataUri: true } },
    }),
  },
  'minimax-h3': {
    label: 'MiniMax H3（生视频）',
    type: 'video',
    model: 'MiniMax-H3',
    channelName: 'minimax',
    channelType: 'minimax',
    baseUrl: 'https://api.minimaxi.com',
    spec: () => ({
      schemaVersion: 2, protocol: 'openai_json', promptKey: 'prompt',
      modes: ['text-to-video', 'image-to-video'],
      request: [
        { key: 'duration', label: '时长（秒）', type: 'range', min: 4, max: 15, default: 6, injectDefault: true },
        { key: 'resolution', label: '分辨率', type: 'enum', options: ['768P', '2K'], default: '768P', injectDefault: true },
        { key: 'aspect_ratio', label: '画幅', type: 'enum', options: ['16:9', '9:16', '4:3', '1:1'], default: '16:9', injectDefault: true },
        { key: 'image', label: '首帧图', type: 'string', role: 'image_upload', cardinality: 'single',
          visibleWhen: { mode: 'image-to-video' }, requiredWhen: { mode: 'image-to-video' } },
      ],
      response: { async: {
        taskIdPath: 'task_id', statusPath: 'task.status',
        stateMap: { PENDING: 'queued', PROCESSING: 'running', SUCCESS: 'succeeded', FAIL: 'failed' },
        mediaPath: 'task.content.url', mediaMode: 'media_url', pollIntervalSec: 5, timeoutSec: 600,
      } },
    }),
  },
  'openai-image': {
    label: 'OpenAI 兼容生图（gpt-image-2）',
    type: 'image',
    model: 'gpt-image-2',
    channelName: 'openai',
    channelType: 'openai_compatible',
    baseUrl: 'https://api.openai.com/v1',
    spec: () => ({
      schemaVersion: 2, protocol: 'openai_json', promptKey: 'prompt',
      modes: ['text-to-image'],
      request: [
        { key: 'size', label: '尺寸', type: 'enum', options: ['1024x1024', '1536x1024', '1024x1536'], default: '1024x1024', injectDefault: true },
        { key: 'n', label: '生成数量', type: 'range', min: 1, max: 4, default: 1, injectDefault: true },
      ],
      response: { sync: { dataPath: 'data', mediaField: 'b64_json', fallbackField: 'url', stripDataUri: true } },
    }),
  },
};

function mcKey(row) {
  return row.modelName + '|' + row.type;
}

function mcDefaultSpec(type) {
  const spec = {
    schemaVersion: 2,
    protocol: type === 'image' || type === 'video' ? 'openai_json' : 'openai_compatible',
    modes: [...(MC_MODES[type] || [])],
    request: [],
    response: {},
  };
  if (type === 'video') {
    spec.response.async = {
      taskIdPath: 'task_id', statusPath: 'status',
      stateMap: { queued: 'queued', running: 'running', succeeded: 'succeeded', failed: 'failed' },
      mediaPath: 'video_url', mediaMode: 'media_url',
      pollIntervalSec: 5, timeoutSec: 300,
    };
  } else {
    spec.response.sync = {
      dataPath: 'data', mediaField: 'b64_json', fallbackField: 'url', stripDataUri: true,
    };
  }
  return spec;
}

function mcDefaultParam() {
  return { key: '', label: '', type: 'string', show: true };
}

async function loadModelConfig() {
  const view = $('#view-models');
  try { await ensureChannels(); } catch (e) { /* 渠道筛选候选失败不影响列表 */ }
  const channels = Object.values(channelMap).sort((a, b) => String(a.name).localeCompare(String(b.name)));
  if (mcChannelFilter && mcChannelFilter !== '__none'
      && !channels.some((c) => String(c.id) === String(mcChannelFilter))) {
    mcChannelFilter = '';
  }
  const typeLabels = { image: '生图', video: '视频', text: '文本', vision: '视觉' };
  const typeOptions = ['', 'image', 'video', 'text', 'vision']
    .map((t) => '<option value="' + t + '"' + (mcTypeFilter === t ? ' selected' : '') + '>'
      + (t ? typeLabels[t] : '全部类型') + '</option>').join('');
  const channelOptions = '<option value="">全部渠道</option><option value="__none"'
    + (mcChannelFilter === '__none' ? ' selected' : '') + '>未绑定渠道</option>'
    + channels.map((c) => '<option value="' + esc(c.id) + '"'
      + (String(mcChannelFilter) === String(c.id) ? ' selected' : '') + '>'
      + esc(c.name) + '</option>').join('');

  view.innerHTML =
    '<div class="view-head"><h2>模型配置</h2>'
    + '<div class="mc-filters">'
    + '<input class="input" id="mc-filter" aria-label="搜索模型" placeholder="搜索模型" value="' + esc(mcFilter) + '">'
    + '<select class="input" id="mc-type-filter" aria-label="按生成类型筛选">' + typeOptions + '</select>'
    + '<select class="input" id="mc-channel-filter" aria-label="按渠道筛选">' + channelOptions + '</select>'
    + '</div>'
    + '<div class="yaml-actions"><button type="button" class="btn btn-sm" data-action="mc-refresh">刷新</button>'
    + '<button type="button" class="btn btn-sm" data-action="mc-template">从模板创建</button>'
    + '<button type="button" class="btn btn-primary" data-action="mc-new">+ 新建模型配置</button></div></div>'
    + '<div class="model-workbench">'
    + '<div class="mc-topbar"><span class="mc-topbar-label">模型</span>'
    + '<div class="mc-list mc-list-horizontal" id="mc-list">加载中…</div>'
    + '</div>'
    + '<section class="mc-editor" id="mc-editor"></section></div>';

  const filterInput = $('#mc-filter');
  if (filterInput) filterInput.oninput = () => { mcFilter = filterInput.value; renderMcList(); };
  const typeFilter = $('#mc-type-filter');
  if (typeFilter) typeFilter.onchange = () => { mcTypeFilter = typeFilter.value; renderMcList(); };
  const channelFilter = $('#mc-channel-filter');
  if (channelFilter) channelFilter.onchange = () => { mcChannelFilter = channelFilter.value; renderMcList(); };

  try {
    mcRows = await api('/admin/model-params/all');
    try { mcRoutes = await api('/admin/routes'); } catch (e) { mcRoutes = []; }
    renderMcList();
    const target = mcRows.find((r) => mcKey(r) === mcLastKey) || mcRows[0];
    if (target) editModelConfig(target);
    else $('#mc-editor').innerHTML = emptyHtml('暂无模型配置，点右上角新建');
  } catch (e) {
    $('#mc-editor').innerHTML = errHtml(e.message);
    handleErr(e);
  }
}

function mcRowChannelIds(row) {
  const type = row.type || 'text';
  return [...new Set(mcRoutes
    .filter((r) => r.modelName === row.modelName && (r.type || 'text') === type)
    .map((r) => String(r.channelId)))];
}

/** 模型的全部路由都不可用（路由冻结或渠道冻结）→ 模型视为冻结 */
function mcRowFrozen(row) {
  const type = row.type || 'text';
  const rs = mcRoutes.filter((r) => r.modelName === row.modelName && (r.type || 'text') === type);
  if (!rs.length) return false;
  return rs.every((r) => {
    const ch = channelMap[r.channelId];
    return r.enabled === false || !ch || ch.enabled === false;
  });
}

function renderMcList() {
  const box = $('#mc-list');
  if (!box) return;
  const needle = mcFilter.trim().toLowerCase();
  const rows = mcRows.filter((r) => {
    if (mcTypeFilter && (r.type || 'text') !== mcTypeFilter) return false;
    if (mcChannelFilter) {
      const ids = mcRowChannelIds(r);
      if (mcChannelFilter === '__none') {
        if (ids.length) return false;
      } else if (!ids.includes(String(mcChannelFilter))) {
        return false;
      }
    }
    return !needle || String(r.modelName || '').toLowerCase().includes(needle);
  });
  box.innerHTML = rows.length
    ? rows.map((r) => {
      const i = mcRows.indexOf(r);
      return '<button type="button" class="mc-list-item'
        + (mcState && mcState.row.id === r.id ? ' active' : '')
        + '" data-action="mc-select" data-idx="' + i + '" title="' + esc(r.modelName) + '">'
        + '<span class="mc-list-name">' + esc(r.modelName) + '</span>'
        + '<span class="mc-list-meta">' + typeBadge(r.type)
        + (r.paramsSchema ? ' <span class="badge badge-success">Spec</span>' : '')
        + (mcRowFrozen(r) ? ' <span class="badge badge-frozen">冻结</span>' : '')
        + '</span>'
        + '</button>';
    }).join('')
    : emptyHtml(needle || mcTypeFilter || mcChannelFilter ? '没有匹配的模型' : '暂无模型');
  const active = box.querySelector('.mc-list-item.active');
  if (active) active.scrollIntoView({ block: 'nearest', inline: 'nearest' });
}

function editModelConfig(row) {
  let spec;
  try {
    spec = row.paramsSchema ? JSON.parse(row.paramsSchema) : null;
  } catch (e) {
    spec = null;
  }
  if (!spec || typeof spec !== 'object') spec = mcDefaultSpec(row.type);
  spec.request = Array.isArray(spec.request) ? spec.request : [];
  spec.response = spec.response && typeof spec.response === 'object' ? spec.response : {};
  if (row.type === 'video' && !spec.response.async) spec.response.async = mcDefaultSpec('video').response.async;
  if (row.type !== 'video' && !spec.response.sync) spec.response.sync = mcDefaultSpec(row.type).response.sync;
  mcState = { row, spec, dirty: false };
  mcPreviewMode = (spec.modes && spec.modes[0]) || '';
  mcLastKey = mcKey(row);
  renderMcList();
  renderMcEditor();
}

function mcInput(path, label, value, placeholder) {
  return '<label class="mc-field">' + esc(label)
    + '<input class="input" data-resp="' + esc(path) + '" value="' + esc(value == null ? '' : value)
    + '" placeholder="' + esc(placeholder || '') + '"></label>';
}

function mcCheck(path, label, checked) {
  return '<label class="mc-check"><input type="checkbox" data-resp="' + esc(path) + '"'
    + (checked ? ' checked' : '') + '> ' + esc(label) + '</label>';
}

function mcParamRow(p, idx, modes) {
  const opts = (p.options || []).join(', ');
  const map = Object.entries(p.map || {}).map(([k, v]) => k + '=' + v).join(', ');
  const when = (p.requiredWhen && p.requiredWhen.mode) || (p.visibleWhen && p.visibleWhen.mode) || '';
  return '<tr data-pidx="' + idx + '">'
    + '<td><input class="input" list="mc-standard-keys" data-pfield="key" value="' + esc(p.key || '') + '" placeholder="如 size / duration"></td>'
    + '<td><input class="input" data-pfield="upstreamKey" value="' + esc(p.upstreamKey || '') + '" placeholder="同名留空"></td>'
    + '<td><input class="input" data-pfield="aliases" value="' + esc((p.aliases || []).join(', ')) + '" placeholder="旧键，可空"></td>'
    + '<td><input class="input" data-pfield="label" value="' + esc(p.label || '') + '" placeholder="尺寸"></td>'
    + '<td><select class="input" data-pfield="type">'
    + MC_PARAM_TYPES.map((t) => '<option value="' + t + '"' + (p.type === t ? ' selected' : '') + '>' + t + '</option>').join('')
    + '</select></td>'
    + '<td><input class="input" type="number" data-pfield="min" value="' + esc(p.min == null ? '' : p.min) + '" placeholder="min"></td>'
    + '<td><input class="input" type="number" data-pfield="max" value="' + esc(p.max == null ? '' : p.max) + '" placeholder="max"></td>'
    + '<td><input class="input" data-pfield="options" value="' + esc(opts) + '" placeholder="1K,2K"></td>'
    + '<td><input class="input" data-pfield="default" value="' + esc(p.default == null ? '' : p.default) + '"></td>'
    + '<td><input type="checkbox" data-pfield="injectDefault"' + (p.injectDefault ? ' checked' : '') + ' title="缺失时由网关补这个默认值"></td>'
    + '<td><input class="input" data-pfield="map" value="' + esc(map) + '" placeholder="2K=2048x2048"></td>'
    + '<td><select class="input" data-pfield="whenMode"><option value="">始终</option>'
    + modes.map((m) => '<option value="' + esc(m) + '"' + (when === m ? ' selected' : '') + '>' + esc(m) + '</option>').join('')
    + '</select></td>'
    + '<td class="mc-center"><input type="checkbox" data-pfield="show"' + (p.show !== false ? ' checked' : '') + '></td>'
    + '<td><select class="input" data-pfield="role"><option value="">-</option>'
    + ['image_upload', 'reference', 'response'].map((r) => '<option value="' + r + '"' + (p.role === r ? ' selected' : '') + '>' + r + '</option>').join('')
    + '</select></td>'
    + '<td><select class="input" data-pfield="cardinality"><option value="">-</option>'
    + ['single', 'multi'].map((c) => '<option value="' + c + '"' + (p.cardinality === c ? ' selected' : '') + '>' + c + '</option>').join('')
    + '</select></td>'
    + '<td><input class="input" type="number" min="1" data-pfield="maxItems" value="' + esc(p.maxItems == null ? '' : p.maxItems) + '"></td>'
    + '<td><button type="button" class="btn btn-sm btn-table btn-table-danger" data-action="mc-del-param" data-idx="' + idx + '">×</button></td>'
    + '</tr>';
}

function mcStandardPreset(type, key) {
  return (MC_STANDARD_PARAMS[type] || []).find((p) => p.key === key);
}

/** 选标准键后，只在字段还空着时补显示名/类型/角色，不覆盖用户已改的值 */
function mcApplyStandardPreset(p, preset) {
  if (!preset) return;
  if (!p.label || p.label === p.key) p.label = preset.label;
  const hasUsableEnum = preset.type !== 'enum' || (preset.options && preset.options.length);
  if ((!p.type || p.type === 'string') && hasUsableEnum) p.type = preset.type;
  if (preset.options && !(p.options || []).length) p.options = [...preset.options];
  if (preset.role && !p.role) p.role = preset.role;
  if (preset.cardinality && !p.cardinality) p.cardinality = preset.cardinality;
  if (preset.min != null && p.min == null) p.min = preset.min;
  if (preset.max != null && p.max == null) p.max = preset.max;
}

/** 从一条真实业务请求推断参数：键名直接用请求里的字段名，避免配置人员自己发明标准名 */
function mcInferParam(type, key, val) {
  const preset = mcStandardPreset(type, key);
  const p = { key, label: preset ? preset.label : key };
  if (Array.isArray(val)) {
    if (val.length && val.every((v) => v && typeof v === 'object' && !Array.isArray(v))) {
      p.type = 'arrayofobject';
      p.fields = Object.keys(val[0]).map((k) => mcInferParam(type, k, val[0][k]));
    } else {
      p.type = 'array';
      p.maxItems = val.length || undefined;
    }
  } else if (val && typeof val === 'object') {
    p.type = 'object';
    p.fields = Object.keys(val).map((k) => mcInferParam(type, k, val[k]));
  } else if (typeof val === 'boolean') {
    p.type = 'bool';
  } else if (typeof val === 'number') {
    p.type = 'number';
  } else {
    p.type = 'string';
  }
  if (preset && preset.role) {
    p.role = preset.role;
    p.cardinality = Array.isArray(val) ? 'multi' : 'single';
  } else if (/image|photo|mask|ref/i.test(key)) {
    p.role = 'image_upload';
    p.cardinality = Array.isArray(val) ? 'multi' : 'single';
  }
  return p;
}

function mcImportRequestParams() {
  if (!mcState) return;
  const raw = prompt('粘贴一条业务/Agent 真实请求 JSON（含 model/prompt 等字段），自动按请求里的字段名生成参数：');
  if (raw === null) return;
  let req;
  try { req = JSON.parse(raw); } catch (e) { toast('请求体不是合法 JSON：' + (e.message || e), 'error'); return; }
  if (!req || typeof req !== 'object' || Array.isArray(req)) {
    toast('请求体必须是 JSON 对象', 'error');
    return;
  }
  const type = mcState.row.type || 'text';
  const existing = new Set((mcState.spec.request || []).map((p) => p.key));
  const skip = new Set(['model', 'prompt', 'stream', 'messages']);
  let added = 0;
  Object.keys(req).forEach((k) => {
    if (skip.has(k) || existing.has(k)) return;
    if (req[k] === null || req[k] === undefined) return;
    mcState.spec.request.push(mcInferParam(type, k, req[k]));
    existing.add(k);
    added++;
  });
  if (!added) {
    toast('没有新增参数：字段都已存在，或只有 model/prompt/stream', 'info');
    return;
  }
  mcState.dirty = true;
  renderMcEditor();
  toast('已按真实请求识别 ' + added + ' 个参数；键名就是请求里的字段名', 'success');
}

function renderMcEditor() {
  const box = $('#mc-editor');
  if (!box || !mcState) return;
  const { row, spec } = mcState;
  const type = row.type || 'text';
  const modes = [...new Set([...(MC_MODES[type] || []), ...(spec.modes || [])])];
  const sync = spec.response.sync || {};
  const asyncCfg = spec.response.async || {};
  const stateMapText = Object.entries(asyncCfg.stateMap || {}).map(([k, v]) => k + '=' + v).join('\n');

  box.innerHTML =
    '<div class="mc-head"><div><span class="mc-title">' + esc(row.modelName) + '</span> '
    + typeBadge(type) + ' <span class="field-hint">' + esc(mcKey(row)) + '</span></div>'
    + '<div class="yaml-actions"><button type="button" class="btn btn-sm" data-action="mc-validate">校验</button>'
    + '<button type="button" class="btn btn-primary" data-action="mc-save">保存并发布</button></div></div>'
    + (mcTemplateNote && mcTemplateNote.key === mcKey(row)
      ? '<div class="mc-template-note">模板已写入 ' + mcTemplateNote.keys.length + ' 个请求参数：'
        + esc(mcTemplateNote.keys.join(' / ')) + '。勾了「注入」的参数会在请求缺失时由网关补齐。'
        + '<button type="button" class="btn btn-sm" data-action="mc-note-dismiss">知道了</button></div>'
      : '')
    + '<div id="mc-errors" class="schema-errors" style="display:none"></div>'
    + '<div class="mc-grid"><div class="mc-main">'
    + '<div class="card mc-section"><h3 class="card-title">能力与协议</h3>'
    + '<div class="mc-cap-row"><label class="mc-field">协议模板<select class="input" id="mc-protocol">'
    + [...new Set([...MC_PROTOCOLS, spec.protocol || ''])].filter(Boolean)
      .map((p) => '<option value="' + esc(p) + '"' + (spec.protocol === p ? ' selected' : '') + '>' + esc(p) + '</option>').join('')
    + '</select></label><div class="mc-modes">'
    + modes.map((m) => '<label class="mc-check"><input type="checkbox" data-modes="' + esc(m) + '"'
      + ((spec.modes || []).includes(m) ? ' checked' : '') + '> ' + esc(m) + '</label>').join('')
    + '</div></div></div>'
    + '<div class="card mc-section"><div class="mc-section-head"><h3 class="card-title">请求参数</h3>'
    + '<div class="yaml-actions"><button type="button" class="btn btn-sm" data-action="mc-import-request">从请求体识别</button>'
    + '<button type="button" class="btn btn-sm" data-action="mc-add-param">+ 参数</button></div></div>'
    + '<p class="field-hint">「键」= 业务/Agent 真实请求里的字段名，不是我们随便起的名字；不确定就点「从请求体识别」，粘贴一条真实请求。</p>'
    + '<datalist id="mc-standard-keys">'
    + (MC_STANDARD_PARAMS[type] || []).map((p) => '<option value="' + esc(p.key) + '">' + esc(p.label) + '</option>').join('')
    + '</datalist>'
    + '<div class="table-card"><table class="table spec-table"><thead><tr>'
    + '<th>键</th><th>上游键</th><th>别名</th><th>显示名</th><th>类型</th><th>最小</th><th>最大</th><th>选项</th><th>默认</th><th>注入</th><th>值映射</th><th>模式条件</th><th>展示</th><th>角色</th><th>单/多</th><th>上限</th><th></th>'
    + '</tr></thead><tbody>'
    + ((spec.request || []).length
      ? spec.request.map((p, i) => mcParamRow(p, i, modes)).join('')
      : '<tr><td colspan="17">' + emptyHtml('还没有参数，点「+ 参数」添加，例如 size / duration / image') + '</td></tr>')
    + '</tbody></table></div></div>'
    + '<div class="card mc-section"><h3 class="card-title">响应映射</h3>'
    + '<details ' + (type === 'video' ? '' : 'open') + '><summary>同步（图片/文本）</summary>'
    + '<div class="mc-fields">'
    + mcInput('sync.dataPath', '结果路径', sync.dataPath, 'data')
    + mcInput('sync.mediaField', '媒体字段', sync.mediaField, 'b64_json')
    + mcInput('sync.fallbackField', '回退字段', sync.fallbackField, 'url')
    + mcInput('sync.errorPath', '错误路径', sync.errorPath, 'error.message')
    + mcCheck('sync.itemsAreUrls', 'items 是裸 URL 数组', sync.itemsAreUrls)
    + mcCheck('sync.dataIsArray', 'data 是数组', sync.dataIsArray)
    + mcCheck('sync.stripDataUri', '剥 data URI 前缀', sync.stripDataUri !== false)
    + '</div></details>'
    + '<details ' + (type === 'video' ? 'open' : '') + '><summary>异步（视频：创建→轮询→下载）</summary>'
    + '<div class="mc-fields">'
    + mcInput('async.taskIdPath', 'taskId 路径', asyncCfg.taskIdPath, 'task_id')
    + mcInput('async.statusPath', '状态路径', asyncCfg.statusPath, 'status')
    + mcInput('async.mediaPath', '媒体路径', asyncCfg.mediaPath, 'video_url')
    + mcInput('async.downloadPath', '下载路径', asyncCfg.downloadPath, '/videos/{taskId}/content')
    + mcInput('async.errorPath', '错误路径', asyncCfg.errorPath, 'error.message')
    + '<label class="mc-field">媒体模式<select class="input" data-resp="async.mediaMode">'
    + ['media_url', 'content_endpoint'].map((m) => '<option value="' + m + '"' + (asyncCfg.mediaMode === m ? ' selected' : '') + '>' + m + '</option>').join('')
    + '</select></label>'
    + '<label class="mc-field">状态映射（原始=归一）<textarea class="input" data-resp="async.stateMap" rows="4" placeholder="queued=queued">'
    + esc(stateMapText) + '</textarea></label>'
    + mcInput('async.pollIntervalSec', '轮询间隔（秒）', asyncCfg.pollIntervalSec, '5')
    + mcInput('async.timeoutSec', '超时（秒）', asyncCfg.timeoutSec, '300')
    + '</div></details></div>'
    + '<div class="card mc-section"><details><summary>高级：完整 JSON 视图（嵌套 object/array 用这里）</summary>'
    + '<textarea class="input" id="mc-json" rows="14" spellcheck="false">' + esc(JSON.stringify(spec, null, 2)) + '</textarea>'
    + '<button type="button" class="btn btn-sm" data-action="mc-json-apply">应用 JSON</button></details></div>'
    + '</div><aside class="mc-side">'
    + '<div class="card mc-section"><div class="mc-section-head"><h3 class="card-title">表单预览</h3>'
    + '<button type="button" class="btn btn-sm" data-action="mc-refresh-preview">刷新</button></div>'
    + '<div id="mc-preview" class="mc-preview"></div></div>'
    + '<div class="card mc-section"><h3 class="card-title">样例试运行</h3>'
    + '<p class="field-hint">粘贴一段真实上游响应，验证 response 映射；不会调用上游、不落库。</p>'
    + '<textarea class="input" id="mc-sample" rows="7" spellcheck="false" placeholder=\'{"data":[{"b64_json":"..."}]}\'></textarea>'
    + '<div class="mc-row"><select class="input" id="mc-kind"><option value="sync">同步</option><option value="async">异步</option></select>'
    + '<input class="input" id="mc-taskid" placeholder="taskId（异步可选）"></div>'
    + '<button type="button" class="btn btn-sm" data-action="mc-preview-run">试运行归一化</button>'
    + '<pre id="mc-preview-result" class="mc-result"></pre></div>'
    + '</aside></div>';

  renderMcPreview();
}

function mcParamVisible(p, mode) {
  const requiredMode = p.requiredWhen && p.requiredWhen.mode;
  const visibleMode = p.visibleWhen && p.visibleWhen.mode;
  if (mode && requiredMode && requiredMode !== mode) return false;
  if (mode && visibleMode && visibleMode !== mode) return false;
  return p.show !== false;
}

function mcPreviewControl(p, mode, prefix) {
  if (!mcParamVisible(p, mode)) return '';
  const key = prefix + (p.key || '');
  const label = '<span class="mc-preview-label">' + esc(p.label || p.key || '(未命名)') + '</span>';
  let control;
  if (p.type === 'enum') {
    const opts = p.options || [];
    control = '<select class="input">' + opts.map((o) => '<option' + (String(o) === String(p.default) ? ' selected' : '') + '>' + esc(o) + '</option>').join('') + '</select>';
  } else if (p.type === 'range' || p.type === 'number') {
    control = '<input class="input" type="number" value="' + esc(p.default == null ? '' : p.default)
      + '"' + (p.min != null ? ' min="' + esc(p.min) + '"' : '') + (p.max != null ? ' max="' + esc(p.max) + '"' : '') + '>';
  } else if (p.type === 'bool') {
    control = '<input type="checkbox"' + (p.default ? ' checked' : '') + '>';
  } else if ((p.type === 'object' || p.type === 'arrayofobject') && p.fields) {
    control = '<div class="mc-preview-group">' + p.fields.map((f) => mcPreviewControl(f, mode, '')).join('') + '</div>';
  } else {
    control = '<input class="input" value="' + esc(p.default == null ? '' : p.default) + '">';
  }
  return '<label class="mc-preview-field" data-preview-key="' + esc(key) + '">' + label + control + '</label>';
}

function renderMcPreview() {
  const box = $('#mc-preview');
  if (!box || !mcState) return;
  const spec = mcState.spec;
  const modes = spec.modes || [];
  const mode = mcPreviewMode || modes[0] || '';
  const fields = (spec.request || []).filter((p) => mcParamVisible(p, mode));
  box.innerHTML = (modes.length
    ? '<label class="mc-field">预览模式<select class="input" data-preview-mode>'
      + modes.map((m) => '<option value="' + esc(m) + '"' + (m === mode ? ' selected' : '') + '>' + esc(m) + '</option>').join('')
      + '</select></label>'
    : '')
    + (fields.length
      ? fields.map((p) => mcPreviewControl(p, mode, '')).join('')
      : '<p class="field-hint">还没有可展示的参数。</p>');
}

function parseMcPairs(text) {
  const out = {};
  String(text || '').split(/[\n;,，]/).forEach((line) => {
    const idx = line.indexOf('=');
    if (idx <= 0) return;
    const key = line.slice(0, idx).trim();
    const value = line.slice(idx + 1).trim();
    if (key) out[key] = value;
  });
  return out;
}

function mcSyncJsonView() {
  const el = $('#mc-json');
  if (el && document.activeElement !== el) el.value = JSON.stringify(mcState.spec, null, 2);
}

/** 表格里的“默认”是文本输入；按类型还原成 bool/number/JSON，否则校验会把它当字符串拒绝 */
function mcCoerceDefault(type, raw) {
  const text = String(raw == null ? '' : raw).trim();
  if (text === '') return undefined;
  if (type === 'bool') {
    if (text.toLowerCase() === 'true') return true;
    if (text.toLowerCase() === 'false') return false;
    return text;
  }
  if (type === 'range' || type === 'number') {
    const n = Number(text);
    return Number.isFinite(n) ? n : text;
  }
  if (type === 'array' || type === 'arrayofobject' || type === 'object') {
    try { return JSON.parse(text); } catch (e) { return text; }
  }
  return text;
}

function mcSetPath(root, path, value) {
  const parts = path.split('.');
  let cur = root;
  for (let i = 0; i < parts.length - 1; i++) {
    if (!cur[parts[i]] || typeof cur[parts[i]] !== 'object') cur[parts[i]] = {};
    cur = cur[parts[i]];
  }
  cur[parts[parts.length - 1]] = value;
}

function onModelConfigChange(e) {
  if (!mcState) return;
  const t = e.target;
  if (t.dataset.previewMode !== undefined) {
    mcPreviewMode = t.value;
    renderMcPreview();
    return;
  }
  if (t.dataset.modes !== undefined) {
    const set = new Set(mcState.spec.modes || []);
    if (t.checked) set.add(t.dataset.modes); else set.delete(t.dataset.modes);
    mcState.spec.modes = [...set];
    if (!set.has(mcPreviewMode)) mcPreviewMode = [...set][0] || '';
    renderMcPreview();
    mcSyncJsonView();
    return;
  }
  if (t.id === 'mc-protocol') {
    mcState.spec.protocol = t.value;
    mcSyncJsonView();
    return;
  }
  if (t.dataset.pfield) {
    const tr = t.closest('tr[data-pidx]');
    if (!tr) return;
    const p = mcState.spec.request[Number(tr.dataset.pidx)];
    if (!p) return;
    const f = t.dataset.pfield;
    if (f === 'key') {
      p.key = t.value.trim();
      if (e.type === 'change' && p.key) {
        const preset = mcStandardPreset(mcState.row.type || 'text', p.key);
        if (preset) {
          mcApplyStandardPreset(p, preset);
          mcState.dirty = true;
          renderMcEditor();
          return;
        }
      }
      renderMcPreview();
      mcSyncJsonView();
      return;
    }
    if (f === 'options') {
      p.options = t.value.split(/[,，]/).map((s) => s.trim()).filter(Boolean);
    } else if (f === 'aliases') {
      p.aliases = t.value.split(/[,，]/).map((s) => s.trim()).filter(Boolean);
      if (!p.aliases.length) delete p.aliases;
    } else if (f === 'upstreamKey') {
      if (t.value.trim()) p.upstreamKey = t.value.trim(); else delete p.upstreamKey;
    } else if (f === 'default') {
      const coerced = mcCoerceDefault(p.type, t.value);
      if (coerced === undefined) delete p.default; else p.default = coerced;
    } else if (f === 'min' || f === 'max') {
      const n = Number(t.value);
      if (t.value.trim() !== '' && Number.isFinite(n)) p[f] = n; else delete p[f];
    } else if (f === 'injectDefault') {
      if (t.checked) p.injectDefault = true; else delete p.injectDefault;
    } else if (f === 'map') {
      p.map = parseMcPairs(t.value);
      if (!Object.keys(p.map).length) delete p.map;
    } else if (f === 'show') {
      p.show = t.checked;
    } else if (f === 'whenMode') {
      if (!t.value) {
        delete p.visibleWhen;
        delete p.requiredWhen;
      } else {
        p.visibleWhen = { mode: t.value };
        p.requiredWhen = { mode: t.value };
      }
    } else if (f === 'maxItems') {
      const n = Number(t.value);
      if (n > 0) p.maxItems = n; else delete p.maxItems;
    } else {
      p[f] = t.value;
    }
    renderMcPreview();
    mcSyncJsonView();
    return;
  }
  if (t.dataset.resp) {
    const path = t.dataset.resp;
    let value = t.type === 'checkbox' ? t.checked : t.value;
    if (path.endsWith('stateMap')) value = parseMcPairs(t.value);
    if (path.endsWith('pollIntervalSec') || path.endsWith('timeoutSec')) value = Number(t.value) || 0;
    mcSetPath(mcState.spec, 'response.' + path, value);
    renderMcPreview();
    mcSyncJsonView();
  }
}

function renderMcErrors(errors, warnings) {
  const box = $('#mc-errors');
  if (!box) return;
  const es = errors || [];
  const ws = warnings || [];
  if (!es.length && !ws.length) {
    box.style.display = 'none';
    box.innerHTML = '';
    return;
  }
  const list = (items) => '<ul>' + items.slice(0, 12).map((i) => '<li><code>' + esc(i.path || '$') + '</code> ' + esc(i.message || '') + '</li>').join('') + '</ul>';
  box.className = 'schema-errors' + (es.length ? '' : ' is-warn');
  box.innerHTML = (es.length ? '<div class="schema-errors-title">错误（' + es.length + '）</div>' + list(es) : '')
    + (ws.length ? '<div class="schema-errors-title is-warn">提示（' + ws.length + '）</div>' + list(ws) : '');
  box.style.display = 'block';
}

async function validateModelConfig() {
  if (!mcState) return false;
  try {
    const check = await api('/admin/model-params/validate', {
      method: 'POST', body: JSON.stringify({ paramsSchema: JSON.stringify(mcState.spec) }),
    });
    renderMcErrors(check.errors, check.warnings);
    if (!check.valid) toast('配置有 ' + check.errors.length + ' 处错误', 'error');
    else toast('校验通过', 'success');
    return check.valid;
  } catch (e) {
    handleErr(e);
    return false;
  }
}

async function saveModelConfig() {
  if (!mcState) return;
  const ok = await validateModelConfig();
  if (!ok) return;
  const btn = document.querySelector('[data-action="mc-save"]');
  if (btn) { btn.disabled = true; btn.textContent = '保存中…'; }
  try {
    await api('/admin/model-params', {
      method: 'PUT',
      body: JSON.stringify({
        modelName: mcState.row.modelName,
        type: mcState.row.type,
        paramsSchema: JSON.stringify(mcState.spec),
      }),
    });
    toast('模型配置已保存', 'success');
    mcLastKey = mcKey(mcState.row);
    await loadModelConfig();
  } catch (e) {
    handleErr(e);
  } finally {
    if (btn) { btn.disabled = false; btn.textContent = '保存并发布'; }
  }
}

async function runMcPreview() {
  if (!mcState) return;
  const sample = ($('#mc-sample') && $('#mc-sample').value.trim()) || '';
  if (!sample) { toast('请先粘贴一段上游响应样例', 'error'); return; }
  const kind = ($('#mc-kind') && $('#mc-kind').value) || 'sync';
  const taskId = ($('#mc-taskid') && $('#mc-taskid').value.trim()) || null;
  try {
    const res = await api('/admin/model-params/normalize-preview', {
      method: 'POST',
      body: JSON.stringify({ paramsSchema: JSON.stringify(mcState.spec), sample, kind, taskId }),
    });
    renderMcErrors(res.errors, res.warnings);
    const out = $('#mc-preview-result');
    if (out) out.textContent = JSON.stringify(res.normalized != null ? res.normalized : res, null, 2);
    toast(res.valid ? '试运行完成' : 'Schema 校验未通过', res.valid ? 'success' : 'error');
  } catch (e) {
    handleErr(e);
  }
}

async function openTemplateWizard() {
  try { await ensureChannels(); } catch (e) { /* 忽略，仍可新建渠道 */ }
  const templates = Object.entries(MC_TEMPLATES);
  const channels = Object.values(channelMap).sort((a, b) => String(a.name).localeCompare(String(b.name)));
  const body =
    field('模板', '<select class="input" id="tpl-template">'
      + templates.map(([k, t]) => '<option value="' + k + '">' + esc(t.label) + '</option>').join('')
      + '</select>')
    + '<div class="tpl-summary" id="tpl-summary"></div>'
    + field('渠道', '<select class="input" id="tpl-channel"><option value="__new">+ 新建渠道</option>'
      + channels.map((c) => '<option value="' + esc(c.id) + '">' + esc(c.name) + '（' + esc(c.type) + '）</option>').join('')
      + '</select>')
    + '<div class="tpl-new-channel" id="tpl-new-channel">'
      + field('渠道名称', '<input class="input" id="tpl-ch-name">')
      + field('BaseURL', '<input class="input" id="tpl-ch-url">')
      + field('API Key', passwordField('tpl-apikey', '', '新建渠道必填'))
    + '</div>'
    + '<div class="field-grid-3">'
      + field('模型名', '<input class="input" id="tpl-model">')
      + field('上游模型名（可空）', '<input class="input" id="tpl-upstream" placeholder="留空=同名">')
      + field('优先级', '<input class="input" id="tpl-priority" type="number" value="0">')
    + '</div>'
    + field('权重', '<input class="input" id="tpl-weight" type="number" min="1" value="1">');

  openModal('从模板创建模型 + 路由', body, footerHtml('创建并绑定'));

  const applyTemplate = (key) => {
    const tpl = MC_TEMPLATES[key];
    if (!tpl) return;
    $('#tpl-model').value = tpl.model;
    $('#tpl-upstream').value = '';
    $('#tpl-priority').value = '0';
    $('#tpl-weight').value = '1';
    const existing = channels.find((c) => c.name === tpl.channelName || c.baseUrl === tpl.baseUrl);
    $('#tpl-channel').value = existing ? existing.id : '__new';
    $('#tpl-ch-name').value = tpl.channelName;
    $('#tpl-ch-url').value = tpl.baseUrl;
    $('#tpl-apikey').value = '';
    $('#tpl-new-channel').style.display = existing ? 'none' : '';
    const modes = tpl.spec().modes || [];
    $('#tpl-summary').innerHTML = '<strong>' + esc(tpl.label) + '</strong>'
      + '<div class="field-hint">类型 ' + esc(tpl.type) + ' · 渠道协议 ' + esc(tpl.channelType)
      + '<br>BaseURL：<code>' + esc(tpl.baseUrl) + '</code>'
      + '<br>能力：' + esc(modes.join(' / '))
      + '<br>会创建：模型配置（Schema） + 模型路由；渠道可复用已有，也可新建。'
      + '</div>';
  };
  $('#tpl-template').onchange = () => applyTemplate($('#tpl-template').value);
  $('#tpl-channel').onchange = () => {
    $('#tpl-new-channel').style.display = $('#tpl-channel').value === '__new' ? '' : 'none';
  };
  applyTemplate(templates[0][0]);

  $('#form-submit').onclick = async () => {
    const key = $('#tpl-template').value;
    const tpl = MC_TEMPLATES[key];
    if (!tpl) return;
    const modelName = $('#tpl-model').value.trim();
    if (!modelName) { toast('模型名不能为空', 'error'); return; }
    const existingConfig = mcRows.find((r) => r.modelName === modelName && r.type === tpl.type);
    if (existingConfig) {
      const ok = await confirmDialog('该模型已有配置，模板会覆盖它的 Schema，继续？', { okText: '覆盖' });
      if (!ok) return;
    }

    const btn = $('#form-submit');
    btn.disabled = true;
    let createdChannelId = null;
    try {
      let channelId = $('#tpl-channel').value;
      if (channelId === '__new') {
        const chName = $('#tpl-ch-name').value.trim();
        const baseUrl = $('#tpl-ch-url').value.trim();
        const apiKey = $('#tpl-apikey').value.trim();
        if (!chName || !baseUrl) { toast('渠道名称和 BaseURL 不能为空', 'error'); btn.disabled = false; return; }
        if (!apiKey && tpl.channelType !== 'ollama') { toast('新建渠道必须填写 API Key', 'error'); btn.disabled = false; return; }
        const channel = await api('/admin/channels', {
          method: 'POST',
          body: JSON.stringify({
            name: chName, type: tpl.channelType, baseUrl, apiKey,
            models: modelName, enabled: true, priority: 0,
          }),
        });
        channelId = channel.id;
        createdChannelId = channel.id;
        channels.push(channel);
      }

      await api('/admin/model-params', {
        method: 'PUT',
        body: JSON.stringify({
          modelName, type: tpl.type,
          paramsSchema: JSON.stringify(tpl.spec()),
        }),
      });

      const routeBody = {
        modelName,
        channelId,
        type: tpl.type,
        enabled: true,
        priority: parseInt($('#tpl-priority').value, 10) || 0,
        weight: Math.max(1, parseInt($('#tpl-weight').value, 10) || 1),
        upstreamModel: $('#tpl-upstream').value.trim(),
        isDefault: false,
      };
      const routes = await api('/admin/routes');
      const existingRoute = (routes || []).find((r) =>
        r.modelName === modelName && String(r.channelId) === String(channelId));
      if (existingRoute) {
        if (!routeBody.upstreamModel) routeBody.upstreamModel = existingRoute.upstreamModel || '';
        await api('/admin/routes/' + existingRoute.id, { method: 'PUT', body: JSON.stringify(routeBody) });
      } else {
        routeBody.fallbacks = [];
        await api('/admin/routes', { method: 'POST', body: JSON.stringify(routeBody) });
      }

      closeModal();
      mcLastKey = modelName + '|' + tpl.type;
      const createdSpec = tpl.spec();
      mcTemplateNote = { key: mcLastKey, keys: createdSpec.request.map((p) => p.key) };
      toast('模板已写入 ' + createdSpec.request.length + ' 个请求参数（'
        + createdSpec.request.map((p) => p.key).join(' / ') + '），并绑定路由', 'success');
      loadModelConfig();
    } catch (e) {
      if (createdChannelId) {
        toast('渠道已创建，但后续步骤失败：' + (e.message || e), 'error');
      } else {
        handleErr(e);
      }
      btn.disabled = false;
    }
  };
}

function openNewModelConfig() {
  openModal('新建模型配置',
    field('模型名', '<input class="input" id="mc-new-name" placeholder="如 doubao-seedream-4-0">')
    + field('类型', '<select class="input" id="mc-new-type">'
      + ['image', 'video', 'text', 'vision'].map((t) => '<option value="' + t + '">' + t + '</option>').join('')
      + '</select>'),
    footerHtml('创建'));
  $('#form-submit').onclick = async () => {
    const name = $('#mc-new-name').value.trim();
    const type = $('#mc-new-type').value;
    if (!name) { toast('模型名不能为空', 'error'); return; }
    const btn = $('#form-submit');
    btn.disabled = true;
    try {
      await api('/admin/model-params', {
        method: 'PUT',
        body: JSON.stringify({ modelName: name, type, paramsSchema: JSON.stringify(mcDefaultSpec(type)) }),
      });
      closeModal();
      mcLastKey = name + '|' + type;
      toast('模型配置已创建', 'success');
      loadModelConfig();
    } catch (e) {
      btn.disabled = false;
      handleErr(e);
    }
  };
}

function onModelConfigClick(e) {
  const btn = e.target.closest('[data-action]');
  if (!btn || btn.disabled) return;
  const action = btn.dataset.action;
  if (action === 'mc-refresh') { loadModelConfig(); return; }
  if (action === 'mc-new') { openNewModelConfig(); return; }
  if (action === 'mc-import-request') { mcImportRequestParams(); return; }
  if (action === 'mc-template') { openTemplateWizard(); return; }
  if (action === 'mc-note-dismiss') { mcTemplateNote = null; renderMcEditor(); return; }
  if (action === 'mc-select') {
    const row = mcRows[Number(btn.dataset.idx)];
    if (row) editModelConfig(row);
    return;
  }
  if (!mcState) return;
  if (action === 'mc-add-param') {
    mcState.spec.request = mcState.spec.request || [];
    mcState.spec.request.push(mcDefaultParam());
    renderMcEditor();
    return;
  }
  if (action === 'mc-del-param') {
    mcState.spec.request.splice(Number(btn.dataset.idx), 1);
    renderMcEditor();
    return;
  }
  if (action === 'mc-save') { saveModelConfig(); return; }
  if (action === 'mc-validate') { validateModelConfig(); return; }
  if (action === 'mc-preview-run') { runMcPreview(); return; }
  if (action === 'mc-json-apply') {
    try {
      const parsed = JSON.parse(($('#mc-json') && $('#mc-json').value) || '{}');
      if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) throw new Error('顶层必须是对象');
      parsed.request = Array.isArray(parsed.request) ? parsed.request : [];
      parsed.response = parsed.response && typeof parsed.response === 'object' ? parsed.response : {};
      mcState.spec = parsed;
      mcPreviewMode = (parsed.modes && parsed.modes[0]) || '';
      renderMcEditor();
      toast('JSON 已应用，记得保存', 'success');
    } catch (e) {
      toast('JSON 不合法：' + (e.message || e), 'error');
    }
    return;
  }
  if (action === 'mc-refresh-preview') { renderMcPreview(); }
}



async function loadYamlConfig() {
  const view = $('#view-yaml');
  view.innerHTML =
    '<div class="view-head"><h2>配置即代码</h2>'
    + '<div class="yaml-actions">'
    + '<button type="button" class="btn btn-sm" id="yaml-load">载入当前配置</button>'
    + '<button type="button" class="btn btn-sm" id="yaml-download">下载 YAML</button>'
    + '<button type="button" class="btn btn-sm" id="yaml-validate">校验</button>'
    + '<button type="button" class="btn btn-primary" id="yaml-import">应用导入</button>'
    + '</div></div>'
    + '<div class="card">'
    + '<p class="field-hint">导出不包含 Key 明文；导入按名称 upsert（渠道 / Key 元数据 / 模型参数与 Schema / 路由 / 回退链），不会删除 YAML 里没有的配置。'
    + '新增 Key 必须在 <code>keys[].api_key</code> 里提供明文，已有 Key 留空表示保持不变。</p>'
    + '<div id="yaml-status" class="yaml-status" style="display:none"></div>'
    + '<textarea class="input yaml-editor" id="yaml-editor" rows="26" spellcheck="false" placeholder="点击「载入当前配置」或直接粘贴 YAML"></textarea>'
    + '</div>';

  $('#yaml-load').onclick = async () => {
    try {
      const data = await api('/admin/config-yaml');
      $('#yaml-editor').value = data.yaml || '';
      renderYamlStatus('已载入当前配置', 'ok');
    } catch (e) { handleErr(e); }
  };
  $('#yaml-download').onclick = () => {
    const text = $('#yaml-editor').value;
    if (!text.trim()) { toast('YAML 为空', 'error'); return; }
    const blob = new Blob([text], { type: 'application/yaml;charset=utf-8' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = 'llm-gateway-config.yaml';
    a.click();
    URL.revokeObjectURL(url);
  };
  $('#yaml-validate').onclick = async () => {
    const yaml = $('#yaml-editor').value;
    try {
      const res = await api('/admin/config-yaml/validate', {
        method: 'POST', body: JSON.stringify({ yaml }),
      });
      renderYamlValidation(res);
    } catch (e) { handleErr(e); }
  };
  $('#yaml-import').onclick = async () => {
    const yaml = $('#yaml-editor').value;
    if (!yaml.trim()) { toast('YAML 为空', 'error'); return; }
    const ok = await confirmDialog('导入会按名称覆盖同名渠道/路由/参数配置，确定继续？', { okText: '导入' });
    if (!ok) return;
    const btn = $('#yaml-import');
    btn.disabled = true;
    try {
      const res = await api('/admin/config-yaml/import', {
        method: 'POST', body: JSON.stringify({ yaml }),
      });
      renderYamlStatus('导入完成：渠道 ' + res.channels + '、Key ' + res.keys + '、模型 ' + res.models
        + '、路由 ' + res.routes + '、回退 ' + res.fallbacks
        + (res.warnings && res.warnings.length ? '（' + res.warnings.length + ' 条提示，见控制台）' : ''), 'ok');
      if (res.warnings && res.warnings.length) console.warn('配置导入提示：', res.warnings);
      toast('配置已导入', 'success');
      channelsLoaded = false;
      loadYamlConfig();
    } catch (e) {
      handleErr(e);
    } finally {
      if (btn) btn.disabled = false;
    }
  };

  try {
    const data = await api('/admin/config-yaml');
    $('#yaml-editor').value = data.yaml || '';
  } catch (e) {
    renderYamlStatus('载入失败：' + (e.message || e), 'error');
  }
}

function renderYamlStatus(message, kind) {
  const box = $('#yaml-status');
  if (!box) return;
  box.className = 'yaml-status ' + (kind === 'error' ? 'is-error' : kind === 'warn' ? 'is-warn' : 'is-ok');
  box.textContent = message;
  box.style.display = 'block';
}

function renderYamlValidation(res) {
  const box = $('#yaml-status');
  if (!box) return;
  const errors = res.errors || [];
  const warnings = res.warnings || [];
  if (!errors.length && !warnings.length) {
    renderYamlStatus('校验通过，可以导入', 'ok');
    return;
  }
  const list = (items) => '<ul>' + items.slice(0, 12).map((i) => '<li>' + esc(i) + '</li>').join('') + '</ul>';
  box.className = 'yaml-status ' + (errors.length ? 'is-error' : 'is-warn');
  box.innerHTML = (errors.length ? '<strong>错误（' + errors.length + '）</strong>' + list(errors) : '')
    + (warnings.length ? '<strong>提示（' + warnings.length + '）</strong>' + list(warnings) : '');
  box.style.display = 'block';
}


function onConfigClick(e) {
  const btn = e.target.closest('button[data-action]');
  if (!btn || btn.disabled) return;
  if (btn.dataset.action === 'save-config') saveConfig();
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
  eye.textContent = show ? '隐藏' : '显示';
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
  $('#view-models').addEventListener('click', onModelConfigClick);
  $('#view-models').addEventListener('input', onModelConfigChange);
  $('#view-models').addEventListener('change', onModelConfigChange);
  $('#view-api-keys').addEventListener('click', onKeysClick);
  $('#view-api-keys').addEventListener('change', onKeysChange);
  $('#view-logs').addEventListener('click', onLogsClick);
  $('#view-users').addEventListener('click', onUsersClick);
  $('#view-users').addEventListener('change', onUsersChange);
  $('#view-config').addEventListener('click', onConfigClick);
  // 配置搜索过滤
  $('#view-config').addEventListener('input', (e) => {
    if (e.target.id !== 'cfg-search') return;
    const q = e.target.value.toLowerCase();
    const rows = document.querySelectorAll('#cfg-table tbody tr');
    rows.forEach((tr) => {
      tr.style.display = tr.textContent.toLowerCase().includes(q) ? '' : 'none';
    });
  });

  // Esc 关闭（plainKey 弹层优先，其次普通弹窗）
  document.addEventListener('keydown', onGlobalKeydown);

  // hash 路由
  window.addEventListener('hashchange', render);

  // 首次渲染（无 token 直接进登录视图）
  render();
}

init();
