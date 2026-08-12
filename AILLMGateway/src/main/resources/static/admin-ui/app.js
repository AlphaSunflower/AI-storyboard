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
    + field('模型列表（逗号分隔）', '<input class="input" id="f-models" value="' + esc(channel ? channel.models || '' : '') + '" placeholder="如：deepseek-v4-pro, deepseek-v4-flash（测试弹窗候选用，留空则仅用已配路由模型）">')
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

  const body = { name, type, baseUrl, models: $('#f-models').value.trim(), enabled, priority: isNaN(priority) ? 0 : priority };
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
    routesCache = routes;     // 缓存供测试弹窗模型候选
    view.innerHTML =
      '<div class="view-head"><h2>模型路由</h2>'
      + '<button type="button" class="btn btn-primary" data-action="new-route">+ 新建路由</button></div>'
      + '<div class="card table-card">'
      + (routes.length
        ? '<table class="table"><thead><tr>'
          + '<th>模型名</th><th>类型</th><th>渠道</th><th>默认参数 (JSON)</th><th>创建时间</th><th class="td-actions">操作</th>'
          + '</tr></thead><tbody>'
          + routes.map((r) => {
            const ch = channelMap[r.channelId];
            const channelName = ch ? ch.name + (ch.enabled === false ? '（停用）' : '') : '未知渠道';
            return '<tr>'
              + '<td class="td-strong">' + esc(r.modelName) + '</td>'
              + '<td>' + typeBadge(r.type) + '</td>'
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

/** 滑块控件：range input + 实时值显示（数值参数避免手填） */
const sliderField = (id, label, min, max, step, def) =>
  '<label>' + label
  + ' <input type="range" class="p-range" id="' + id + '" min="' + min + '" max="' + max + '" step="' + step + '" value="' + def + '">'
  + ' <span class="p-val" id="' + id + '-val">' + def + '</span></label>';

/** 参数配置区（按类型分组表单）：数值参数用滑块（n 范围/temperature/max_tokens/top_p/时长），字符串枚举保持输入框 */
const paramsGroups = {
  text: sliderField('p-temperature', 'temperature', '0', '2', '0.1', '0.7')
      + sliderField('p-maxtokens', 'max_tokens', '100', '8192', '100', '1024')
      + sliderField('p-topp', 'top_p', '0', '1', '0.05', '0.9'),
  image: sliderField('p-nmin', '数量min', '1', '10', '1', '1')
       + sliderField('p-nmax', '数量max', '1', '10', '1', '4')
       + sliderField('p-ndefault', '数量默认', '1', '10', '1', '1')
       + '<label>尺寸(逗号) <input class="input" id="p-sizes" placeholder="1024x1024,1536x1024"></label>'
       + '<label>尺寸默认 <input class="input" id="p-sizedefault" placeholder="1024x1024"></label>'
       + '<label>质量(逗号) <input class="input" id="p-qualities" placeholder="standard,hd"></label>'
       + '<label>质量默认 <input class="input" id="p-qualitydefault" placeholder="hd"></label>'
       + '<label>风格(逗号) <input class="input" id="p-styles" placeholder="vivid,natural"></label>'
       + '<label>风格默认 <input class="input" id="p-styledefault" placeholder="vivid"></label>',
  video: sliderField('p-dmin', '时长min(秒)', '4', '15', '1', '4')
       + sliderField('p-dmax', '时长max(秒)', '4', '15', '1', '8')
       + sliderField('p-durationdefault', '时长默认(秒)', '4', '15', '1', '6')
       + '<label>分辨率(逗号) <input class="input" id="p-resolutions" placeholder="768P,2K"></label>'
       + '<label>分辨率默认 <input class="input" id="p-resolutiondefault" placeholder="768P"></label>'
       + '<label>画幅(逗号) <input class="input" id="p-aspects" placeholder="16:9,9:16,4:3,1:1"></label>'
       + '<label>画幅默认 <input class="input" id="p-aspectdefault" placeholder="16:9"></label>'
       + sliderField('p-refimagesmin', '参考图min(张)', '0', '10', '1', '0')
       + sliderField('p-refimagesmax', '参考图max(张)', '0', '10', '1', '3')
       + sliderField('p-refvideosmin', '参考视频min(个)', '0', '5', '1', '0')
       + sliderField('p-refvideosmax', '参考视频max(个)', '0', '5', '1', '1')
       + sliderField('p-audiocountmin', '音频个数min', '0', '10', '1', '0')
       + sliderField('p-audiocountmax', '音频个数max', '0', '10', '1', '2')
       + sliderField('p-audiodmin', '音频单段min(秒)', '1', '300', '1', '5')
       + sliderField('p-audiodmax', '音频单段max(秒)', '1', '300', '1', '60')
       + sliderField('p-videodmin', '视频单段min(秒)', '1', '300', '1', '5')
       + sliderField('p-videodmax', '视频单段max(秒)', '1', '300', '1', '60')
       + sliderField('p-maxtotalduration', '总时长上限(秒)', '1', '3600', '1', '300')
       + sliderField('p-maxtotalfiles', '文件总数上限', '1', '50', '1', '10')
       + sliderField('p-maxvideosize', '视频单个(MB)', '1', '500', '1', '100')
       + sliderField('p-maximagesize', '图片单个(MB)', '1', '100', '1', '10')
       + sliderField('p-maxaudiosize', '音频单个(MB)', '1', '100', '1', '15')
       + sliderField('p-maxbody', '请求体上限(MB)', '1', '200', '1', '64')
       + sliderField('p-maxprompt', '提示词上限(字符)', '100', '20000', '100', '2000'),
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
          + esc(c.name) + (c.enabled === false ? '（停用）' : '') + '</option>'
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
    ['p-temperature', 'p-maxtokens', 'p-topp', 'p-nmin', 'p-nmax', 'p-ndefault', 'p-dmin', 'p-dmax', 'p-durationdefault',
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
    + field('参数配置', '<div class="params-grid" id="params-group">' + (paramsGroups[curType] || paramsGroups.text) + '</div>')
    + field('默认参数（JSON）', '<textarea class="input" id="r-params" rows="4" placeholder=\'{"temperature":0.7,"size":"1024x1024"}\'>'
      + esc(route ? route.defaultParams || '' : '') + '</textarea>');

  openModal(isEdit ? '编辑路由' : '新建路由', body, footerHtml('保存'));
  // 按默认渠道拉取模型列表（编辑时预填路由配置的模型名）
  loadModelsFor(selCh, 'r-model', route ? route.modelName : '');
  // 渠道切换 → 重新拉取该渠道模型列表
  $('#r-channel').onchange = () => {
    $('#r-model').value = '';
    loadModelsFor($('#r-channel').value, 'r-model');
  };
  // 模型类型切换 → 重建参数配置组（按新类型显示对应字段组；切换后已填值不保留，先选类型再填）
  $('#r-type').onchange = () => {
    const t = $('#r-type').value;
    $('#params-group').innerHTML = paramsGroups[t] || paramsGroups.text;
    bindParamSliders();
  };
  // 初始渲染后绑定滑块（input 实时值 + min<=max 联动）
  bindParamSliders();
  // 编辑回显：模型名存在 → 拉取已保存参数填当前类型组（type 与路由一致才填）
  if (isEdit && route.modelName) {
    loadModelParamsForEdit(route.modelName, curType);
  }
  $('#form-submit').onclick = () => submitRoute(isEdit ? route.id : null);
}

/**
 * 编辑回显：GET /admin/model-params/{modelName}，type 与路由一致时填当前类型组输入框
 * 未配置 / 类型不一致 → 不填（提示先选类型）；回显失败不阻塞编辑
 */
async function loadModelParamsForEdit(modelName, type) {
  try {
    const mp = await api('/admin/model-params/' + encodeURIComponent(modelName));
    if (!mp || (mp.type && mp.type !== type)) return;
    // 设置控件值：滑块同时更新右侧值显示；字符串输入直接赋值
    const set = (id, v) => {
      if (v === null || v === undefined || v === '') return;
      const el = $('#' + id);
      if (!el) return;
      el.value = v;
      const span = $(id + '-val');
      if (span) span.textContent = v;
    };
    set('p-temperature', mp.temperature);
    set('p-maxtokens', mp.maxTokens);
    set('p-topp', mp.topP);
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
    // 其余字段（p-durations 旧输入框已由滑块替代，无对应元素自动跳过）
  } catch (e) {
    console.warn('模型参数回显失败（不阻塞编辑）：', e && e.message ? e.message : e);
  }
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

  const body = { modelName, channelId, type: $('#r-type').value, defaultParams };
  const btn = $('#form-submit');
  btn.disabled = true;
  try {
    await api(id ? '/admin/routes/' + id : '/admin/routes', {
      method: id ? 'PUT' : 'POST',
      body: JSON.stringify(body),
    });
    // 保存路由成功后并行保存模型参数（upsert）：按当前类型组输入框取值；失败静默跳过不阻塞路由保存
    try {
      await saveModelParams(modelName, $('#r-type').value);
    } catch (pe) {
      console.warn('模型参数保存失败（已跳过，路由已保存）：', pe && pe.message ? pe.message : pe);
    }
    closeModal();
    toast(id ? '路由已更新' : '路由已创建', 'success');
    loadRoutes();
  } catch (e) {
    btn.disabled = false;
    handleErr(e);
  }
}

/**
 * 组装 ModelParamsRequest 并 PUT /admin/model-params（upsert）：
 * 从当前类型组输入框取值（空输入 → null），type 非法/模型名为空时静默跳过
 */
async function saveModelParams(modelName, type) {
  if (!modelName || !paramsGroups[type]) return;
  const val = (id) => { const el = $('#' + id); const s = el ? el.value.trim() : ''; return s === '' ? null : s; };
  const num = (id) => { const s = val(id); return s === null ? null : Number(s); };
  const req = { modelName, type };
  if (type === 'text') {
    req.temperature = val('p-temperature');
    req.maxTokens = num('p-maxtokens');
    req.topP = val('p-topp');
  } else if (type === 'image') {
    req.nMin = num('p-nmin');
    req.nMax = num('p-nmax');
    req.nDefault = num('p-ndefault');
    req.sizes = val('p-sizes');
    req.sizeDefault = val('p-sizedefault');
    req.qualities = val('p-qualities');
    req.qualityDefault = val('p-qualitydefault');
    req.styles = val('p-styles');
    req.styleDefault = val('p-styledefault');
  } else if (type === 'video') {
    // 时长范围滑块（p-dmin/p-dmax）→ 展开连续整数逗号分隔（如 4~8 → "4,5,6,7,8"）
    const dMin = num('p-dmin'), dMax = num('p-dmax');
    if (dMin !== null && dMax !== null && dMin <= dMax) {
      const seq = [];
      for (let s = dMin; s <= dMax; s++) seq.push(s);
      req.durations = seq.join(',');
    }
    req.durationDefault = num('p-durationdefault') !== null ? String(num('p-durationdefault')) : null;
    req.resolutions = val('p-resolutions');
    req.resolutionDefault = val('p-resolutiondefault');
    req.aspectRatios = val('p-aspects');
    req.aspectRatioDefault = val('p-aspectdefault');
    req.refImagesMin = num('p-refimagesmin');
    req.refImagesMax = num('p-refimagesmax');
    req.refVideosMin = num('p-refvideosmin');
    req.refVideosMax = num('p-refvideosmax');
    req.audioCountMin = num('p-audiocountmin');
    req.audioCountMax = num('p-audiocountmax');
    req.audioSegmentDurationMin = num('p-audiodmin');
    req.audioSegmentDurationMax = num('p-audiodmax');
    req.videoSegmentDurationMin = num('p-videodmin');
    req.videoSegmentDurationMax = num('p-videodmax');
    req.maxTotalDuration = num('p-maxtotalduration');
    req.maxTotalFiles = num('p-maxtotalfiles');
    req.maxVideoSizeMb = num('p-maxvideosize');
    req.maxImageSizeMb = num('p-maximagesize');
    req.maxAudioSizeMb = num('p-maxaudiosize');
    req.maxRequestBodyMb = num('p-maxbody');
    req.maxPromptChars = num('p-maxprompt');
  }
  await api('/admin/model-params', { method: 'PUT', body: JSON.stringify(req) });
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
