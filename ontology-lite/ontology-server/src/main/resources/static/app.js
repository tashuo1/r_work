const API_TIMEOUT_MS = 25000;

const api = async (url, options = {}) => {
  const controller = new AbortController();
  const timeoutId = setTimeout(() => controller.abort(), options.timeoutMs || API_TIMEOUT_MS);
  let response;
  try {
    response = await fetch(`/api/v1${url}`, {
      headers: { 'Content-Type': 'application/json', ...(options.headers || {}) },
      ...options,
      signal: controller.signal
    });
  } catch (error) {
    if (error.name === 'AbortError') {
      throw new Error('请求超时，请检查数据库网络、地址、端口、账号密码或稍后重试');
    }
    throw new Error('无法连接本地服务，请确认 http://127.0.0.1:9000 已启动');
  } finally {
    clearTimeout(timeoutId);
  }
  const body = await response.json().catch(() => ({ message: `请求失败 ${response.status}` }));
  if (!response.ok || body.code !== 200) {
    throw new Error(body.message || `请求失败 ${response.status}`);
  }
  return body.data;
};

const html = (value) => String(value ?? '').replace(/[&<>"']/g, (char) => ({
  '&': '&amp;',
  '<': '&lt;',
  '>': '&gt;',
  '"': '&quot;',
  "'": '&#39;'
}[char]));

const toast = (message, error = false) => {
  const node = document.createElement('div');
  node.className = `toast${error ? ' error' : ''}`;
  node.textContent = message;
  document.body.appendChild(node);
  setTimeout(() => node.remove(), 3200);
};

const renderTable = (rows, columns) => {
  if (!rows || rows.length === 0) return '<p>暂无数据</p>';
  return `<table><thead><tr>${columns.map(col => `<th>${html(col.label)}</th>`).join('')}</tr></thead><tbody>${
    rows.map(row => `<tr>${columns.map(col => `<td>${col.render ? col.render(row) : html(row[col.key])}</td>`).join('')}</tr>`).join('')
  }</tbody></table>`;
};

let lastCandidateVersionId = null;
let lastQueryId = null;
let currentDataSources = [];
let currentComputeResources = [];
let currentSourceFormMode = 'list';
let currentComputeFormMode = 'list';
let currentTermRoots = [];
let currentStandardFields = [];
let currentModelCandidate = null;
let currentModelVersions = [];
let currentModelScan = null;
let currentModelScanError = null;
let currentModelImpact = null;
let currentModelAuditLogs = [];
let selectedModelDetail = null;
let currentModelingTab = 'scan';
const NAV_COLLAPSED_STORAGE_KEY = 'ontology.navCollapsed';
const viewIds = ['dashboard', 'datasources', 'compute-resources', 'modeling', 'dictionary', 'etl', 'query', 'openapi', 'acceptance'];
const datasourceCategories = [
  ['all', '全部（66）'],
  ['aliyun', '阿里云自研（17）'],
  ['relational', '关系型数据库（18）'],
  ['bigdata', '大数据存储（18）'],
  ['semi', '半结构化（8）'],
  ['nosql', 'NoSQL（6）'],
  ['file', '文件/对象存储（6）'],
  ['message', '消息队列（3）'],
  ['saas', 'SaaS（1）'],
  ['open', '开放协议存储（11）'],
  ['shard', '分库分表（6）'],
  ['meta', '元数据（1）']
];
const datasourceTypes = [
  ['Amazon Redshift', 'relational', 'rs'],
  ['AnalyticDB for MySQL (V2.0)', 'aliyun', 'adb'],
  ['AnalyticDB for MySQL (V3.0)', 'aliyun', 'adb'],
  ['AnalyticDB for MySQL (V3.0)（分库分表）', 'shard', 'adb'],
  ['AnalyticDB for PostgreSQL', 'relational', 'pg'],
  ['ApsaraDB for OceanBase', 'relational', 'ob'],
  ['ApsaraDB for OceanBase（分库分表）', 'shard', 'ob'],
  ['Azure Blob Storage', 'file', 'az'],
  ['COS', 'file', 'cos'],
  ['ClickHouse', 'bigdata', 'ck'],
  ['DB2', 'relational', 'db2'],
  ['DLF', 'meta', 'dlf'],
  ['DM', 'relational', 'dm'],
  ['DRDS', 'shard', 'drds'],
  ['Data Lake Analytics', 'bigdata', 'dla'],
  ['Hologres', 'aliyun', 'hg'],
  ['MaxCompute', 'aliyun', 'mc'],
  ['MySQL', 'relational', 'mysql'],
  ['PolarDB', 'relational', 'polar']
];
const datasourceFormTitles = {
  DM: '创建DM数据源',
  MYSQL: '创建MySQL数据源',
  POLARDB: '创建PolarDB数据源'
};
const datasourceDisplayNames = {
  DM: 'DM',
  MYSQL: 'MySQL',
  POLARDB: 'PolarDB'
};

function sourceTypeToDbType(name) {
  const normalized = String(name || '').toUpperCase();
  if (normalized === 'DM') return 'DM';
  if (normalized.includes('MYSQL')) return 'MYSQL';
  if (normalized.includes('POLAR')) return 'POLARDB';
  return '';
}

function newSourceCode(type) {
  const normalizedType = sourceTypeToDbType(type) || 'DM';
  const prefix = normalizedType === 'DM' ? 'DM_SOURCE' : `${normalizedType}_SOURCE`;
  let code = `${prefix}_${Date.now().toString().slice(-6)}`;
  let sequence = 1;
  const usedCodes = new Set(currentDataSources.map(source => source.code));
  while (usedCodes.has(code)) {
    code = `${prefix}_${Date.now().toString().slice(-6)}_${sequence}`;
    sequence += 1;
  }
  return code;
}

function newComputeCode(type) {
  const normalizedType = sourceTypeToDbType(type) || 'MYSQL';
  let code = `${normalizedType}_COMPUTE_${Date.now().toString().slice(-6)}`;
  let sequence = 1;
  const usedCodes = new Set(currentComputeResources.map(resource => resource.code));
  while (usedCodes.has(code)) {
    code = `${normalizedType}_COMPUTE_${Date.now().toString().slice(-6)}_${sequence}`;
    sequence += 1;
  }
  return code;
}

function defaultPort(type) {
  return sourceTypeToDbType(type) === 'DM' ? 5236 : 3306;
}

function buildJdbcUrl(type, host, port, databaseName) {
  const dbType = sourceTypeToDbType(type) || 'DM';
  if (!host || !port || !databaseName) return '';
  if (dbType === 'MYSQL' || dbType === 'POLARDB') {
    return `jdbc:mysql://${host}:${port}/${databaseName}?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true&connectTimeout=10000&socketTimeout=10000`;
  }
  return `jdbc:dm://${host}:${port}/${databaseName}`;
}

function displayJdbcUrl(url) {
  return String(url || '').split('?')[0];
}

function setSidebarCollapsed(collapsed) {
  document.body.classList.toggle('nav-collapsed', collapsed);
  const toggle = document.querySelector('#sidebarToggle');
  if (!toggle) return;
  toggle.setAttribute('aria-expanded', String(!collapsed));
  toggle.setAttribute('aria-label', collapsed ? '展开功能菜单' : '收起功能菜单');
  toggle.setAttribute('title', collapsed ? '展开功能菜单' : '收起功能菜单');
}

function initializeSidebarCollapse() {
  const toggle = document.querySelector('#sidebarToggle');
  if (!toggle) return;
  const storedValue = localStorage.getItem(NAV_COLLAPSED_STORAGE_KEY);
  setSidebarCollapsed(storedValue === 'true');
  toggle.addEventListener('click', () => {
    const collapsed = !document.body.classList.contains('nav-collapsed');
    setSidebarCollapsed(collapsed);
    localStorage.setItem(NAV_COLLAPSED_STORAGE_KEY, String(collapsed));
  });
}

function setActiveView(viewId, updateHash = true) {
  const nextView = viewIds.includes(viewId) ? viewId : 'dashboard';
  document.querySelectorAll('[data-view]').forEach(section => {
    section.classList.toggle('active-view', section.dataset.view === nextView);
  });
  document.querySelectorAll('nav a').forEach(link => {
    link.classList.toggle('active', link.getAttribute('href') === `#${nextView}`);
  });
  if (updateHash && window.location.hash !== `#${nextView}`) {
    history.replaceState(null, '', `#${nextView}`);
  }
  window.scrollTo({ top: 0, left: 0, behavior: 'auto' });
}

async function loadDashboard() {
  const data = await api('/dashboard');
  renderRuntimeTrustBanner(data);
  document.querySelector('#metrics').innerHTML = [
    ['数据源', `${data.activeDataSourceCount}/${data.dataSourceCount}`, '启用 / 总数'],
    ['运行库', runtimeMetricValue(data.runtimeStoreType), data.runtimeStoreSummary || '平台持久化状态'],
    ['计算资源', data.computeResourceStatus || '未配置', '本体规范库'],
    ['元数据库', data.metaStoreStatus || '未初始化', 'ontology_meta'],
    ['实例库', data.instanceStoreStatus || '未初始化', 'ontology_instance'],
    ['生效版本', data.activeVersion, '当前语义模型'],
    ['对象 / 关系', `${data.objectCount}/${data.relationCount}`, '类型资产'],
    ['ETL 状态', data.lastEtlStatus, '最近一次 T+1']
  ].map(([label, value, hint]) => `
    <div class="metric">
      <span class="metric-label">${html(label)}</span>
      <strong>${html(value)}</strong>
      <span>${html(hint)}</span>
    </div>
  `).join('');
  renderDashboardTodos(data);
}

function runtimeMetricValue(runtimeStoreType) {
  const normalized = String(runtimeStoreType || '').toUpperCase();
  if (normalized.includes('H2')) return 'H2';
  if (normalized.includes('MYSQL')) return 'MySQL';
  return runtimeStoreType || 'UNKNOWN';
}

function renderRuntimeTrustBanner(data) {
  const banner = document.querySelector('#runtimeTrustBanner');
  if (!banner) return;
  const warnings = data.environmentWarnings || [];
  const fallbackClass = data.fallbackMode ? ' warning' : ' ready';
  const title = data.fallbackMode ? '当前使用本地兜底库' : '当前连接外部平台库';
  const message = warnings[0] || '平台持久化库已连接，页面数据来自当前外部库。';
  banner.className = `runtime-trust-banner${fallbackClass}`;
  banner.innerHTML = `
    <span class="runtime-dot"></span>
    <div class="runtime-store">
      <strong>${html(title)}</strong>
      <small>${html(data.runtimeStoreType || 'UNKNOWN')} · ${html(data.runtimeStoreSummary || '未识别运行库')}</small>
    </div>
    <p>${html(message)}</p>
  `;
}

function renderDashboardTodos(data) {
  const panel = document.querySelector('#dashboardTodos');
  if (!panel) return;
  const todos = [];
  if (data.fallbackMode) {
    todos.push('核对外部 MySQL/PolarDB 可达性，避免 H2 演示数据覆盖真实判断');
  }
  if ((data.pendingActionCount || 0) > 0) {
    todos.push(`还有 ${data.pendingActionCount} 项配置待处理：优先完成计算资源和数据源连接测试`);
  }
  if (!todos.length) {
    todos.push('平台运行库、计算资源和数据源连接状态已进入可验收巡检队列');
  }
  panel.innerHTML = `
    <div class="todo-head">
      <strong>下一步</strong>
      <span>${html(data.pendingActionCount || 0)} 项</span>
    </div>
    ${todos.map(item => `<div class="todo-item"><span></span><p>${html(item)}</p></div>`).join('')}
  `;
}

async function loadDataSources() {
  const data = await api('/internal/datasources');
  currentDataSources = data.rows;
  renderDatasourceList();
  renderDatasourceTypeGrid();
  renderDataStandardSources();
}

async function loadComputeResources() {
  const data = await api('/internal/compute-resources');
  currentComputeResources = data.rows;
  renderComputeResourceList();
}

function filteredDataSources() {
  const type = document.querySelector('#sourceTypeFilter')?.value || '';
  const keyword = (document.querySelector('#sourceNameFilter')?.value || '').trim().toLowerCase();
  return currentDataSources.filter(source => {
    const typeMatched = !type || source.dbType.toUpperCase().includes(type);
    const keywordMatched = !keyword || `${source.code} ${source.name} ${source.domain}`.toLowerCase().includes(keyword);
    return typeMatched && keywordMatched;
  });
}

function renderDatasourceList() {
  const rows = filteredDataSources();
  document.querySelector('#datasourceTable').innerHTML = `
    <table class="datasource-table">
      <colgroup>
        <col class="select-col">
        <col class="source-col">
        <col class="connection-col">
        <col class="status-col">
        <col class="time-col">
        <col class="time-col">
        <col class="owner-col">
        <col class="operation-col">
      </colgroup>
      <thead>
        <tr>
          <th class="checkbox-cell"><input type="checkbox" aria-label="全选数据源"></th>
          <th>数据源信息</th>
          <th>连接信息</th>
          <th>描述</th>
          <th>创建时间</th>
          <th>修改时间</th>
          <th>责任人</th>
          <th>操作</th>
        </tr>
      </thead>
      <tbody>
        ${rows.length ? rows.map(row => datasourceRow(row)).join('') : '<tr><td colspan="8"><div class="empty-state">没有数据</div></td></tr>'}
      </tbody>
    </table>
  `;
}

function filteredComputeResources() {
  const type = document.querySelector('#computeTypeFilter')?.value || '';
  const keyword = (document.querySelector('#computeNameFilter')?.value || '').trim().toLowerCase();
  return currentComputeResources.filter(resource => {
    const typeMatched = !type || resource.dbType.toUpperCase().includes(type);
    const keywordMatched = !keyword || `${resource.code} ${resource.name} ${resource.host}`.toLowerCase().includes(keyword);
    return typeMatched && keywordMatched;
  });
}

function renderComputeResourceList() {
  const rows = filteredComputeResources();
  const table = document.querySelector('#computeResourceTable');
  if (!table) return;
  table.innerHTML = `
    <table class="datasource-table">
      <colgroup>
        <col class="select-col">
        <col class="source-col">
        <col class="connection-col">
        <col class="status-col">
        <col class="time-col">
        <col class="time-col">
        <col class="owner-col">
        <col class="operation-col">
      </colgroup>
      <thead>
        <tr>
          <th class="checkbox-cell"><input type="checkbox" aria-label="全选计算资源"></th>
          <th>资源信息</th>
          <th>连接与库</th>
          <th>状态</th>
          <th>最近巡检</th>
          <th>更新时间</th>
          <th>责任人</th>
          <th>操作</th>
        </tr>
      </thead>
      <tbody>
        ${rows.length ? rows.map(row => computeResourceRow(row)).join('') : '<tr><td colspan="8"><div class="empty-state">暂无计算资源</div></td></tr>'}
      </tbody>
    </table>
  `;
}

function computeResourceRow(row) {
  const statusClass = row.active ? 'success' : row.initialized ? 'warning' : row.healthStatus === 'ERROR' ? 'danger' : 'warning';
  return `
    <tr>
      <td class="checkbox-cell"><input type="checkbox" aria-label="选择 ${html(row.name)}"></td>
      <td>
        <div class="source-name-line">
          <span class="source-logo ${row.dbType.toLowerCase()}">${html(row.dbType.slice(0, 2))}</span>
          <div>
            <strong>${html(row.name)}</strong>
            <small>${html(row.code)} · ${html(row.dbType)}</small>
          </div>
        </div>
      </td>
      <td class="connection-cell">
        <code class="jdbc-preview">${html(displayJdbcUrl(row.jdbcUrl))}</code>
        <small>${html(row.username)} · meta=${html(row.metaDatabaseName)} · instance=${html(row.instanceDatabaseName)}</small>
      </td>
      <td><span class="tag ${statusClass}">${row.active ? 'ACTIVE' : html(row.status)}</span><small>${html(row.initialized ? '已初始化' : '待初始化')} · ${html(row.healthMessage)}</small></td>
      <td>${formatDate(row.lastHealthTime)}</td>
      <td>${formatDate(row.lastHealthTime)}</td>
      <td>admin</td>
      <td class="compute-actions">
        <button class="link-button" data-fill-compute="${row.id}">编辑</button>
        <button class="link-button" data-activate-compute="${row.id}">设为当前</button>
        <button class="link-button danger-link" data-delete-compute="${row.id}">删除</button>
      </td>
    </tr>
  `;
}

function datasourceRow(row) {
  const statusClass = row.healthStatus === 'OK' ? 'success' : row.healthStatus === 'ERROR' ? 'danger' : 'warning';
  const demoSource = isDemoDataSource(row);
  return `
    <tr>
      <td class="checkbox-cell"><input type="checkbox" aria-label="选择 ${html(row.name)}"></td>
      <td>
        <div class="source-name-line">
          <span class="source-logo ${row.dbType.toLowerCase()}">${html(row.dbType.slice(0, 2))}</span>
          <div>
            <strong>${html(row.name)}</strong>
            <small>${html(row.code)} · ${html(row.domain)} ${demoSource ? '<span class="tag demo">DEMO</span>' : '<span class="tag real">REAL</span>'}</small>
          </div>
        </div>
      </td>
      <td class="connection-cell"><code class="jdbc-preview">${html(displayJdbcUrl(row.jdbcUrl))}</code><small>${html(row.username)} · ${row.passwordConfigured ? '已保存密码' : '待填密码'}</small></td>
      <td><span class="tag ${statusClass}">${html(row.healthStatus)}</span><small>${html(row.healthMessage)}</small></td>
      <td>${formatDate(row.lastHealthTime)}</td>
      <td>${formatDate(row.lastHealthTime)}</td>
      <td>admin</td>
      <td>
        <button class="link-button" data-fill-source="${row.id}">编辑</button>
        <button class="link-button danger-link" data-delete-source="${row.id}">删除</button>
      </td>
    </tr>
  `;
}

function isDemoDataSource(row) {
  const demoCodes = new Set(['DM_FINANCE', 'DM_INVEST', 'DM_HR']);
  return demoCodes.has(row.code) && String(row.jdbcUrl || '').includes('.dm.local');
}

function renderDatasourceTypeGrid(category = 'all', keyword = '') {
  const row = document.querySelector('#sourceCategoryRow');
  const grid = document.querySelector('#datasourceTypeGrid');
  if (!row || !grid) return;
  row.innerHTML = datasourceCategories.map(([key, label]) => `<button class="${key === category ? 'active' : ''}" data-source-category="${key}">${html(label)}</button>`).join('');
  const normalizedKeyword = keyword.trim().toLowerCase();
  const visibleTypes = datasourceTypes.filter(([name, group]) => {
    const categoryMatched = category === 'all' || group === category;
    const keywordMatched = !normalizedKeyword || name.toLowerCase().includes(normalizedKeyword);
    return categoryMatched && keywordMatched;
  });
  grid.innerHTML = visibleTypes.map(([name, , logo]) => {
    const supportedType = sourceTypeToDbType(name);
    const action = supportedType ? `data-pick-source-type="${supportedType}"` : `data-disabled-source-type="${html(name)}"`;
    return `
    <button class="datasource-type-card${supportedType ? '' : ' disabled'}" ${action}>
      <span class="source-logo ${html(logo)}">${html(logo.slice(0, 2).toUpperCase())}</span>
      <strong>${html(name)}</strong>
    </button>
  `;
  }).join('');
}

function openDatasourcePicker() {
  document.querySelector('#datasourceModal').classList.remove('hidden');
  document.querySelector('#datasourceModal').setAttribute('aria-hidden', 'false');
  renderDatasourceTypeGrid();
}

function closeDatasourcePicker() {
  document.querySelector('#datasourceModal').classList.add('hidden');
  document.querySelector('#datasourceModal').setAttribute('aria-hidden', 'true');
}

function openDatasourceForm(type = 'DM', source = null) {
  currentSourceFormMode = source ? 'edit' : 'create';
  closeDatasourcePicker();
  document.querySelector('#datasourceTabs')?.classList.add('hidden');
  document.querySelector('#datasourceToolbar')?.classList.add('hidden');
  document.querySelector('#datasourceTable')?.classList.add('hidden');
  const normalizedType = sourceTypeToDbType(type) || 'DM';
  const defaults = source || {
    id: '',
    code: newSourceCode(normalizedType),
    name: '',
    domain: '财务域',
    dbType: normalizedType,
    host: '',
    port: defaultPort(normalizedType),
    databaseName: '',
    username: '',
    jdbcUrl: '',
    passwordConfigured: false
  };
  const panel = document.querySelector('#datasourceCreatePanel');
  panel.classList.remove('hidden');
  panel.innerHTML = `
    <div class="create-panel-head">
      <button class="back-button" id="closeSourceForm">‹</button>
      <div>
        <h3>${source ? `编辑${html(datasourceDisplayNames[normalizedType] || normalizedType)}数据源` : html(datasourceFormTitles[normalizedType] || `创建${normalizedType}数据源`)}</h3>
        <p>按 DataWorks 的基础信息与生产环境连接信息模式填写，保存后执行真实 JDBC 测试。</p>
      </div>
    </div>
    <div class="form-section-title">基础信息</div>
    <div class="dw-form">
      <input id="sourceId" type="hidden" value="${html(defaults.id)}">
      <label class="required" for="sourceCode">数据源名称：</label>
      <input id="sourceCode" value="${html(defaults.code || '')}" placeholder="数据源名称工作空间内唯一；必须以字母、数字、下划线组合">
      <label for="sourceName">显示名称：</label>
      <input id="sourceName" value="${html(defaults.name || '')}" placeholder="请输入业务显示名称">
      <label for="sourceDomain">业务域：</label>
      <input id="sourceDomain" value="${html(defaults.domain || '')}" placeholder="财务域 / 投资域 / 人事域">
      <label for="sourceDescription">数据源描述：</label>
      <textarea id="sourceDescription" rows="3" placeholder="请输入数据源描述"></textarea>
    </div>
    <div class="env-box">
      <div class="env-title"><input type="checkbox" checked aria-label="生产环境"> <strong>生产环境</strong></div>
      <div class="dw-form">
        <label class="required" for="sourceJdbcUrl">JDBC 连接串预览：</label>
        <input id="sourceJdbcUrl" value="${html(defaults.jdbcUrl || '')}" placeholder="留空时按主机、端口、库名自动生成" data-auto-jdbc="${defaults.jdbcUrl ? 'false' : 'true'}">
        <label class="required" for="sourceHost">主机地址/IP：</label>
        <input id="sourceHost" value="${html(defaults.host || '')}">
        <label class="required" for="sourcePort">端口：</label>
        <input id="sourcePort" type="number" value="${html(defaults.port || defaultPort(normalizedType))}">
        <label class="required" for="sourceDatabase">库/模式名：</label>
        <input id="sourceDatabase" value="${html(defaults.databaseName || '')}">
        <label class="required" for="sourceUsername">用户名：</label>
        <input id="sourceUsername" value="${html(defaults.username || '')}">
        <label class="required" for="sourcePassword">密码：</label>
        <input id="sourcePassword" type="password" value="" placeholder="${defaults.passwordConfigured ? '留空表示不修改已保存密码' : ''}">
        <input id="sourceDbType" type="hidden" value="${html(normalizedType)}">
      </div>
    </div>
    <div class="form-actions">
      <button id="saveSource">保存连接信息</button>
      <button id="testEditedSource" class="outline">测试连通性</button>
      <button id="cancelSourceForm" class="ghost-lite">取消</button>
    </div>
  `;
  refreshJdbcPreview();
}

function closeDatasourceForm() {
  document.querySelector('#datasourceCreatePanel').classList.add('hidden');
  document.querySelector('#datasourceCreatePanel').innerHTML = '';
  document.querySelector('#datasourceTabs')?.classList.remove('hidden');
  document.querySelector('#datasourceToolbar')?.classList.remove('hidden');
  document.querySelector('#datasourceTable')?.classList.remove('hidden');
  currentSourceFormMode = 'list';
}

function openComputeResourceForm(type = 'MYSQL', resource = null) {
  currentComputeFormMode = resource ? 'edit' : 'create';
  document.querySelector('#computeResourceTabs')?.classList.add('hidden');
  document.querySelector('#computeResourceToolbar')?.classList.add('hidden');
  document.querySelector('#computeResourceTable')?.classList.add('hidden');
  const normalizedType = sourceTypeToDbType(type) || 'MYSQL';
  const defaults = resource || {
    id: '',
    code: newComputeCode(normalizedType),
    name: '',
    dbType: normalizedType,
    host: '',
    port: defaultPort(normalizedType),
    username: '',
    passwordConfigured: false,
    metaDatabaseName: 'ontology_meta',
    instanceDatabaseName: 'ontology_instance',
    jdbcUrl: ''
  };
  const panel = document.querySelector('#computeResourceCreatePanel');
  panel.classList.remove('hidden');
  panel.innerHTML = `
    <div class="create-panel-head">
      <button class="back-button" id="closeComputeForm">‹</button>
      <div>
        <h3>${resource ? '编辑计算资源' : '创建计算资源'}</h3>
        <p>同一地址下初始化 ontology_meta 与 ontology_instance，作为本体数据平台运行存储目标。保存后将自动测试连通性，连接成功后自动初始化平台库。</p>
      </div>
    </div>
    <div class="form-section-title">基础信息</div>
    <div class="dw-form">
      <input id="computeId" type="hidden" value="${html(defaults.id)}">
      <label class="required" for="computeCode">资源编码：</label>
      <input id="computeCode" value="${html(defaults.code || '')}" placeholder="工作空间内唯一；字母、数字、下划线">
      <label for="computeName">显示名称：</label>
      <input id="computeName" value="${html(defaults.name || '')}" placeholder="请输入资源名称">
      <label for="computeDescription">数据源描述：</label>
      <textarea id="computeDescription" rows="3" placeholder="请输入计算资源描述"></textarea>
      <label for="computeDbType">资源类型：</label>
      <select id="computeDbType">
        <option value="MYSQL" ${normalizedType === 'MYSQL' ? 'selected' : ''}>MySQL</option>
        <option value="POLARDB" ${normalizedType === 'POLARDB' ? 'selected' : ''}>PolarDB</option>
        <option value="DM" ${normalizedType === 'DM' ? 'selected' : ''}>DM</option>
      </select>
    </div>
    <div class="env-box">
      <div class="env-title"><input type="checkbox" checked aria-label="生产环境"> <strong>生产环境</strong></div>
      <div class="dw-form">
        <label class="required" for="computeJdbcUrl">JDBC 连接串预览：</label>
        <input id="computeJdbcUrl" value="${html(defaults.jdbcUrl || '')}" placeholder="留空时按主机、端口自动生成实例级连接串" data-auto-jdbc="${defaults.jdbcUrl ? 'false' : 'true'}">
        <label class="required" for="computeHost">主机地址/IP：</label>
        <input id="computeHost" value="${html(defaults.host || '')}">
        <label class="required" for="computePort">端口：</label>
        <input id="computePort" type="number" value="${html(defaults.port || defaultPort(normalizedType))}">
        <label class="required" for="computeUsername">用户名：</label>
        <input id="computeUsername" value="${html(defaults.username || '')}">
        <label class="required" for="computePassword">密码：</label>
        <input id="computePassword" type="password" value="" placeholder="${defaults.passwordConfigured ? '留空表示不修改已保存密码' : ''}">
        <label class="required" for="computeMetaDatabase">元数据库/Schema：</label>
        <input id="computeMetaDatabase" value="${html(defaults.metaDatabaseName || 'ontology_meta')}">
        <label class="required" for="computeInstanceDatabase">实例库/Schema：</label>
        <input id="computeInstanceDatabase" value="${html(defaults.instanceDatabaseName || 'ontology_instance')}">
      </div>
    </div>
    <div class="form-actions">
      <button id="saveComputeResource">保存资源</button>
      <button id="cancelComputeForm" class="ghost-lite">取消</button>
    </div>
  `;
  refreshComputeJdbcPreview();
}

function closeComputeResourceForm() {
  document.querySelector('#computeResourceCreatePanel').classList.add('hidden');
  document.querySelector('#computeResourceCreatePanel').innerHTML = '';
  document.querySelector('#computeResourceTabs')?.classList.remove('hidden');
  document.querySelector('#computeResourceToolbar')?.classList.remove('hidden');
  document.querySelector('#computeResourceTable')?.classList.remove('hidden');
  currentComputeFormMode = 'list';
}

function sourcePayloadFromForm() {
  return {
    code: document.querySelector('#sourceCode').value.trim(),
    name: document.querySelector('#sourceName').value.trim() || document.querySelector('#sourceCode').value.trim(),
    domain: document.querySelector('#sourceDomain').value.trim(),
    dbType: document.querySelector('#sourceDbType').value.trim(),
    host: document.querySelector('#sourceHost').value.trim(),
    port: Number(document.querySelector('#sourcePort').value || 5236),
    databaseName: document.querySelector('#sourceDatabase').value.trim(),
    username: document.querySelector('#sourceUsername').value.trim(),
    password: document.querySelector('#sourcePassword').value,
    jdbcUrl: document.querySelector('#sourceJdbcUrl').value.trim()
  };
}

function computePayloadFromForm() {
  return {
    code: document.querySelector('#computeCode').value.trim(),
    name: document.querySelector('#computeName').value.trim() || document.querySelector('#computeCode').value.trim(),
    dbType: document.querySelector('#computeDbType').value.trim(),
    host: document.querySelector('#computeHost').value.trim(),
    port: Number(document.querySelector('#computePort').value || 3306),
    username: document.querySelector('#computeUsername').value.trim(),
    password: document.querySelector('#computePassword').value,
    jdbcUrl: document.querySelector('#computeJdbcUrl').value.trim(),
    metaDatabaseName: document.querySelector('#computeMetaDatabase').value.trim() || 'ontology_meta',
    instanceDatabaseName: document.querySelector('#computeInstanceDatabase').value.trim() || 'ontology_instance'
  };
}

function refreshJdbcPreview() {
  const jdbc = document.querySelector('#sourceJdbcUrl');
  if (!jdbc || jdbc.dataset.autoJdbc !== 'true') return;
  const value = buildJdbcUrl(
    document.querySelector('#sourceDbType')?.value || 'DM',
    document.querySelector('#sourceHost')?.value.trim(),
    document.querySelector('#sourcePort')?.value,
    document.querySelector('#sourceDatabase')?.value.trim()
  );
  if (value) jdbc.value = value;
}

function refreshComputeJdbcPreview() {
  const jdbc = document.querySelector('#computeJdbcUrl');
  if (!jdbc || jdbc.dataset.autoJdbc !== 'true') return;
  const type = document.querySelector('#computeDbType')?.value || 'MYSQL';
  const host = document.querySelector('#computeHost')?.value.trim();
  const port = document.querySelector('#computePort')?.value;
  const dbType = sourceTypeToDbType(type) || 'MYSQL';
  if (!host || !port) return;
  const value = dbType === 'DM'
    ? `jdbc:dm://${host}:${port}`
    : buildJdbcUrl(dbType, host, port, 'mysql');
  if (value) jdbc.value = value;
}

async function saveSourceFromForm(testAfterSave = false) {
  const id = document.querySelector('#sourceId').value;
  const url = id ? `/internal/datasources/${id}` : '/internal/datasources';
  const method = id ? 'PUT' : 'POST';
  const saved = await api(url, { method, body: JSON.stringify(sourcePayloadFromForm()) });
  let message = '连接信息已保存';
  if (testAfterSave) {
    const result = await api(`/internal/datasources/${saved.id}/test`, { method: 'POST' });
    message = result.message;
  }
  await Promise.all([loadDataSources(), loadDashboard(), loadAudit()]);
  if (!testAfterSave) closeDatasourceForm();
  toast(message, testAfterSave && !message.includes('成功'));
}

async function deleteSourceFromList(sourceId) {
  const source = currentDataSources.find(item => String(item.id) === String(sourceId));
  const sourceName = source?.name || `ID ${sourceId}`;
  if (!confirm(`确认删除数据源「${sourceName}」？删除后将移除已保存的密码信息。`)) return;
  await api(`/internal/datasources/${sourceId}`, { method: 'DELETE' });
  await Promise.all([loadDataSources(), loadDashboard(), loadAudit()]);
  toast('数据源已删除');
}

async function saveComputeResourceFromForm() {
  const id = document.querySelector('#computeId').value;
  const url = id ? `/internal/compute-resources/${id}` : '/internal/compute-resources';
  const method = id ? 'PUT' : 'POST';
  const saved = await api(url, { method, body: JSON.stringify(computePayloadFromForm()) });
  const message = await autoTestAndInitializeComputeResource(saved.id);
  await Promise.all([loadComputeResources(), loadDashboard(), loadAudit()]);
  closeComputeResourceForm();
  toast(message, message.includes('失败'));
}

async function autoTestAndInitializeComputeResource(resourceId) {
  const testResult = await api(`/internal/compute-resources/${resourceId}/test`, { method: 'POST' });
  if (!testResult.success) {
    return testResult.message || '计算资源连通性测试失败，未执行初始化';
  }
  const initResult = await api(`/internal/compute-resources/${resourceId}/initialize`, { method: 'POST' });
  return initResult.message || '计算资源初始化完成';
}

async function deleteComputeResourceFromList(resourceId) {
  const resource = currentComputeResources.find(item => String(item.id) === String(resourceId));
  const resourceName = resource?.name || `ID ${resourceId}`;
  if (!confirm(`确认删除计算资源「${resourceName}」？`)) return;
  await api(`/internal/compute-resources/${resourceId}`, { method: 'DELETE' });
  await Promise.all([loadComputeResources(), loadDashboard(), loadAudit()]);
  toast('计算资源已删除');
}

function formatDate(value) {
  if (!value) return '-';
  return new Date(value).toLocaleString();
}

async function loadDataStandards() {
  const [roots, fields] = await Promise.all([
    api('/internal/dictionary/term-roots'),
    api('/internal/dictionary/standard-fields')
  ]);
  currentTermRoots = roots;
  currentStandardFields = fields;
  renderDataStandardSources();
  renderTermRoots();
  renderStandardFields();
}

async function loadDictionary() {
  await loadDataStandards();
}

function renderDataStandardSources() {
  const container = document.querySelector('#dataStandardSources');
  if (!container) return;
  container.innerHTML = currentDataSources.length ? currentDataSources.map(source => `
    <label class="standard-source-card">
      <input type="checkbox" value="${source.id}" data-standard-source ${source.status === 'ACTIVE' ? 'checked' : ''}>
      <span class="source-logo ${source.dbType.toLowerCase()}">${html(source.dbType.slice(0, 2))}</span>
      <span>
        <strong>${html(source.name)}</strong>
        <small>${html(source.code)} · ${html(source.domain)} · ${html(displayJdbcUrl(source.jdbcUrl))}</small>
      </span>
      <em class="tag ${source.healthStatus === 'OK' ? 'success' : 'warning'}">${html(source.healthStatus)}</em>
    </label>
  `).join('') : '<div class="empty-state">暂无可选数据源</div>';
}

function visibleStatuses(showRejected) {
  return showRejected ? ['ACTIVE', 'DRAFT', 'PENDING_REVIEW', 'REJECTED'] : ['ACTIVE', 'DRAFT', 'PENDING_REVIEW'];
}

function statusTag(status) {
  const normalized = status || 'DRAFT';
  const cls = normalized === 'ACTIVE' ? 'success' : normalized === 'REJECTED' ? 'danger' : 'warning';
  const label = normalized === 'ACTIVE' ? '已生效' : normalized === 'REJECTED' ? '已拒绝' : '待审核';
  return `<span class="tag ${cls}">${label}</span>`;
}

function renderTermRoots() {
  const container = document.querySelector('#roots');
  if (!container) return;
  const showRejected = document.querySelector('#showRejectedRoots')?.checked || false;
  const statuses = visibleStatuses(showRejected);
  const roots = currentTermRoots.filter(root => statuses.includes(root.status));
  container.innerHTML = roots.length ? `<div class="standard-review-list">${roots.map(rootRow).join('')}</div>` : '<div class="empty-state">暂无命名词根</div>';
}

function rootRow(root) {
  const pending = root.status !== 'ACTIVE' && root.status !== 'REJECTED';
  return `
    <div class="standard-review-row">
      <div class="standard-row-main">
        <div class="standard-title-line">
          <strong>${html(root.name)}</strong>
          <code>${html(root.code)}</code>
          ${statusTag(root.status)}
        </div>
        <p class="standard-row-desc">${html(root.definition || '-')}</p>
      </div>
      <div class="standard-meta">
          <span>${html(root.domain || '通用')}</span>
          <span>${html(root.sourceType || 'MANUAL')}</span>
          <span>${html(root.confidenceLevel || 'L1')}</span>
      </div>
      <div class="similar-root-list">
          ${(root.similarRoots || []).map(similar => `
            <span title="${html(similar.note || '')}">
              ${html(similar.name)} <code>${html(similar.code)}</code> · ${html(similar.relationType)}
            </span>
          `).join('') || '<small>暂无相近词根</small>'}
      </div>
      <div class="standard-actions">
        ${pending ? `<button data-approve-root="${root.id}" data-approve-root-action="true">确认</button><button class="danger" data-reject-root="${root.id}" data-reject-root-action="true">拒绝</button>` : ''}
      </div>
    </div>
  `;
}

function renderStandardFields() {
  const container = document.querySelector('#fields');
  if (!container) return;
  const showRejected = document.querySelector('#showRejectedFields')?.checked || false;
  const statuses = visibleStatuses(showRejected);
  const fields = currentStandardFields.filter(field => statuses.includes(field.status));
  container.innerHTML = fields.length ? `<div class="standard-review-list">${fields.map(fieldRow).join('')}</div>` : '<div class="empty-state">暂无标准字段</div>';
}

function fieldRow(field) {
  const pending = field.status !== 'ACTIVE' && field.status !== 'REJECTED';
  return `
    <div class="standard-review-row">
      <div class="standard-row-main">
        <div class="standard-title-line">
          <strong>${html(field.name)}</strong>
          <code>${html(field.code)}</code>
          ${statusTag(field.status)}
        </div>
        <p class="standard-row-desc">${html(field.description || '-')}</p>
      </div>
      <div class="standard-meta">
          <span>${html(field.domain || '通用')}</span>
          <span>${html(field.dataType || 'STRING')}</span>
          <span>${html(field.sourceType || 'MANUAL')}</span>
          <span>${html(field.confidenceLevel || 'L1')}</span>
      </div>
      <div class="field-root-chain">
          ${(field.rootCodes || []).map(code => `<span class="tag">${html(code)}</span>`).join('')}
      </div>
      <div class="standard-actions">
        ${pending ? `<button data-approve-field="${field.id}" data-approve-field-action="true">确认</button><button class="danger" data-reject-field="${field.id}" data-reject-field-action="true">拒绝</button>` : ''}
      </div>
    </div>
  `;
}

async function generateDataStandards() {
  const sourceIds = Array.from(document.querySelectorAll('[data-standard-source]:checked')).map(input => Number(input.value));
  const button = document.querySelector('#generateDataStandards');
  if (!sourceIds.length) {
    setDataStandardGenerationStatus('failed', null, '请先选择至少一个数据源');
    toast('请先选择至少一个数据源', true);
    return;
  }
  try {
    if (button) {
      button.disabled = true;
      button.textContent = '生成中...';
    }
    setDataStandardGenerationStatus('running', { sourceCount: sourceIds.length });
    const result = await api('/internal/dictionary/ai-generate', {
      method: 'POST',
      body: JSON.stringify({ dataSourceIds: sourceIds })
    });
    await Promise.all([loadDataStandards(), loadAudit(), loadDashboard()]);
    setDataStandardGenerationStatus('success', result);
    toast(generationToast(result));
  } catch (error) {
    setDataStandardGenerationStatus('failed', null, error.message);
    throw error;
  } finally {
    if (button) {
      button.disabled = false;
      button.textContent = '确认生成';
    }
  }
}

function setDataStandardGenerationStatus(status, result = null, message = '') {
  const container = document.querySelector('#dataStandardGenerationResult');
  if (!container) return;
  const running = status === 'running';
  const success = status === 'success';
  const failed = status === 'failed';
  const sourceCount = result?.sourceCount ?? 0;
  const title = running ? '正在按 cizhu-shengcheng 输出结构生成候选' : success ? '生成完成' : failed ? '生成失败' : '生成进度';
  const tag = running ? '<span class="tag warning">处理中</span>' : success ? '<span class="tag success">SUCCESS</span>' : failed ? '<span class="tag danger">FAILED</span>' : '<span class="tag">待开始</span>';
  const summary = running
    ? `已选择 ${sourceCount} 个数据源，正在提交到本地 Skill 服务适配器并同步候选到平台状态。`
    : success
      ? generationSummary(result)
      : failed
        ? html(message || '生成请求失败，请检查数据源和服务状态')
        : '选择数据源后点击确认生成，页面会展示候选生成进度和结果摘要。';
  const warningItems = success && result.warnings?.length
    ? `<div class="generation-warning-list">${result.warnings.map(item => `<span>${html(item)}</span>`).join('')}</div>`
    : '';
  container.innerHTML = `
    <div class="data-standard-generation-card ${html(status)}" data-standard-generation-progress>
      <div class="generation-status-line">
        <strong>${html(title)}</strong>
        ${tag}
      </div>
      <p>${summary}</p>
      <div class="data-standard-generation-steps">
        <span class="${running || success ? 'active' : ''}">${running ? '<i class="loading-dot"></i>' : ''}读取已选数据源</span>
        <span class="${running || success ? 'active' : ''}">${running ? '<i class="loading-dot"></i>' : ''}调用 cizhu-shengcheng Skill 服务</span>
        <span class="${success ? 'active' : ''}">写入待审核数据标准</span>
      </div>
      <small>说明：当前通过本地 Skill 服务适配器调用 cizhu-shengcheng；若运行环境缺少该 Skill，后端会从程序内置 zip 解包安装并生效，后续可切换为 HTTP/LLM Skill 服务。</small>
      ${success ? `
        <div class="generation-runtime">
          <span><b>执行模式</b>${html(result.executionMode || '-')}</span>
          <span><b>Skill</b>${html(result.skillName || 'cizhu-shengcheng')}</span>
          <span><b>阶段</b>${html(result.stage || '-')}</span>
          <span><b>耗时</b>${html(String(result.durationMs ?? 0))}ms</span>
          <span><b>已存在</b>${html(String((result.skippedRootCount ?? 0) + (result.skippedFieldCount ?? 0)))} 条</span>
          <span class="skill-path"><b>路径</b>${html(result.skillPath || '-')}</span>
        </div>
        ${warningItems}
        <div class="generation-result-actions">
          <button class="secondary" id="viewGeneratedRoots">查看命名词根</button>
          <button class="secondary" id="viewGeneratedFields">查看标准字段</button>
        </div>
      ` : ''}
      ${success ? `<pre>${html(JSON.stringify(result, null, 2))}</pre>` : ''}
    </div>
  `;
}

function generationSummary(result) {
  const skippedRoots = result.skippedRootCount ?? 0;
  const skippedFields = result.skippedFieldCount ?? 0;
  if ((result.createdRootCount ?? 0) === 0 && (result.createdFieldCount ?? 0) === 0 && (skippedRoots + skippedFields) > 0) {
    return `${result.message || '本次未新增候选'}，来源数据源 ${result.sourceCount} 个。`;
  }
  return `${result.message || '生成请求已完成'}；本次新增 ${result.createdRootCount} 个命名词根、${result.createdFieldCount} 个标准字段候选，跳过已存在 ${skippedRoots} 个词根、${skippedFields} 个标准字段，来源数据源 ${result.sourceCount} 个。`;
}

function generationToast(result) {
  const skippedTotal = (result.skippedRootCount ?? 0) + (result.skippedFieldCount ?? 0);
  if ((result.createdRootCount ?? 0) === 0 && (result.createdFieldCount ?? 0) === 0 && skippedTotal > 0) {
    return `本次未新增候选，已跳过 ${skippedTotal} 条已有候选`;
  }
  return `已新增 ${result.createdRootCount} 个词根、${result.createdFieldCount} 个标准字段候选`;
}

function switchDataStandardTab(tabName) {
  document.querySelectorAll('[data-standard-tab]').forEach(button => {
    button.classList.toggle('active', button.dataset.standardTab === tabName);
  });
  document.querySelectorAll('[data-standard-pane]').forEach(pane => {
    pane.classList.toggle('active', pane.dataset.standardPane === tabName);
  });
}

async function loadVersions() {
  await loadModelingWorkbench();
}

async function loadModelingWorkbench() {
  currentModelVersions = await api('/internal/modeling/versions');
  currentModelAuditLogs = await api('/internal/modeling/audit-logs').catch(() => []);
  currentModelCandidate = null;
  currentModelImpact = null;
  lastCandidateVersionId = null;
  const latestDraft = currentModelVersions.find(item => item.status === 'DRAFT');
  const active = currentModelVersions.find(item => item.status === 'ACTIVE');
  const focusVersion = latestDraft || active || currentModelVersions[0];
  if (focusVersion) {
    currentModelCandidate = await api(`/internal/modeling/candidates/${focusVersion.id}`);
    lastCandidateVersionId = latestDraft?.id || null;
    currentModelImpact = await api(`/internal/modeling/versions/${focusVersion.id}/impact`).catch(() => null);
  }
  renderModel(currentModelCandidate, true);
}

function modelPercent(value) {
  const number = Number(value || 0);
  return Math.round(number <= 1 ? number * 100 : number);
}

function focusedModelVersionStatus() {
  const versions = currentModelVersions || [];
  const latestDraft = versions.find(item => item.status === 'DRAFT');
  const activeVersion = versions.find(item => item.status === 'ACTIVE');
  return (latestDraft || activeVersion || versions[0])?.status || '';
}

function modelDetailStatus(itemStatus) {
  return focusedModelVersionStatus() || itemStatus || 'UNKNOWN';
}

function modelStatusTagClass(status) {
  if (status === 'ACTIVE') return 'success';
  if (status === 'DRAFT') return 'warning';
  if (status === 'INACTIVE') return '';
  return 'warning';
}

function deriveModelingCommandState() {
  const versions = currentModelVersions || [];
  const draftVersions = versions.filter(item => item.status === 'DRAFT');
  const latestDraft = draftVersions[0] || null;
  const activeVersion = versions.find(item => item.status === 'ACTIVE') || null;
  const focusVersion = latestDraft || activeVersion || versions[0] || null;
  const objects = currentModelCandidate?.objects || [];
  const relations = currentModelCandidate?.relations || [];
  const candidatePropertyCount = objects.reduce((total, item) => total + (item.properties?.length || 0), 0);
  const objectCount = focusVersion?.objectCount ?? objects.length;
  const propertyCount = focusVersion?.propertyCount ?? candidatePropertyCount;
  const relationCount = focusVersion?.relationCount ?? relations.length;
  const approvalRate = focusVersion ? modelPercent(focusVersion.approvalRate) : 0;
  const scannedTables = currentModelScan?.tableCount || objectCount || objects.length;
  const scannedColumns = currentModelScan?.columnCount || propertyCount || candidatePropertyCount;
  let stageTitle = '待扫描';
  let stageTag = '准备建模';
  let stageTone = 'warning';
  let stageDescription = '先扫描已启用数据源的 Schema，再生成对象、属性、关系候选。';
  let recommendedTab = 'scan';
  let primaryActionKey = 'scan';
  let actionReason = '获取最新源表和字段';

  if (latestDraft) {
    stageTitle = '候选待发布';
    stageTag = `${latestDraft.versionNo} DRAFT`;
    stageTone = 'warning';
    stageDescription = '已有候选模型等待确认生效，发布前请检查影响评估和低置信度对象。';
    recommendedTab = 'versions';
    primaryActionKey = 'publish';
    actionReason = '确认候选版本并生效';
  } else if (activeVersion) {
    stageTitle = '模型已生效';
    stageTag = `${activeVersion.versionNo} ACTIVE`;
    stageTone = 'success';
    stageDescription = '当前模型可支撑 T+1 同步、语义问数和 OpenAPI；如源表变化，下一步生成新候选。';
    recommendedTab = 'graph';
    primaryActionKey = 'generate';
    actionReason = '基于最新输入生成下一版候选';
  } else if (currentModelScan) {
    stageTitle = '已扫描待建模';
    stageTag = 'Schema Ready';
    stageTone = 'warning';
    stageDescription = '元数据已经更新，下一步需要生成候选对象、属性和关系。';
    recommendedTab = 'candidates';
    primaryActionKey = 'generate';
    actionReason = '把扫描结果转成候选模型';
  } else if (objects.length) {
    stageTitle = '候选待确认';
    stageTag = focusVersion?.versionNo || 'Snapshot';
    stageTone = 'warning';
    stageDescription = '已有模型快照可查看，建议进入候选或版本步骤确认当前状态。';
    recommendedTab = 'candidates';
    primaryActionKey = 'generate';
    actionReason = '刷新候选模型';
  }

  const risks = [];
  if (draftVersions.length > 1) {
    risks.push({
      level: 'warning',
      title: 'DRAFT 版本堆积',
      message: `当前存在 ${draftVersions.length} 个待发布候选，建议先发布最新版本或回滚清理。`
    });
  }
  if (latestDraft && approvalRate < 80) {
    risks.push({
      level: 'warning',
      title: '确认率偏低',
      message: `候选确认率 ${approvalRate}%，发布前建议复核低置信度对象和字段映射。`
    });
  }
  if (currentModelScanError) {
    risks.push({
      level: 'danger',
      title: '扫描失败，使用快照',
      message: `源库扫描失败：${currentModelScanError}。源库连接恢复后再重新扫描。`
    });
  }
  if (!activeVersion) {
    risks.push({
      level: 'danger',
      title: '暂无生效版本',
      message: 'T+1 同步、语义问数和 OpenAPI 需要生效模型作为稳定语义层。'
    });
  }

  return {
    versions,
    draftVersions,
    latestDraft,
    activeVersion,
    focusVersion,
    objects,
    relations,
    objectCount,
    propertyCount,
    relationCount,
    approvalRate,
    scannedTables,
    scannedColumns,
    stageTitle,
    stageTag,
    stageTone,
    stageDescription,
    recommendedTab,
    primaryActionKey,
    primaryActionLabel: {
      scan: '扫描元数据',
      generate: '生成候选',
      publish: '确认生效',
      rollback: '回滚最近版本'
    }[primaryActionKey],
    actionReason,
    risks
  };
}

function renderModelingSummary(state = deriveModelingCommandState()) {
  const summary = document.querySelector('#modelingSummary');
  if (!summary) return;
  summary.innerHTML = [
    ['生效版本', state.activeVersion?.versionNo || '未生效', state.activeVersion ? '当前稳定语义层' : '发布后进入稳定层'],
    ['候选版本', state.latestDraft?.versionNo || '无待发布', state.draftVersions.length ? `${state.draftVersions.length} 个 DRAFT` : '当前无候选待发布'],
    ['确认率', state.focusVersion ? `${state.approvalRate}%` : '待生成', '人工确认与规则置信度'],
    ['对象类型', state.objectCount, 'Object Type'],
    ['属性映射', state.propertyCount, '标准字段映射'],
    ['关系类型', state.relationCount, 'Link Type']
  ].map(([label, value, hint]) => `
    <div class="modeling-summary-item">
      <span>${html(label)}</span>
      <strong>${html(value)}</strong>
      <small>${html(hint)}</small>
    </div>
  `).join('');
}

function renderModelingCommandCenter(state = deriveModelingCommandState()) {
  const stage = document.querySelector('#modelingStageCard');
  const actions = document.querySelector('#modelingNextActions');
  if (stage) {
    stage.innerHTML = `
      <div class="modeling-panel-title">
        <span>当前阶段</span>
        <small>${html(state.stageTag)}</small>
      </div>
      <div class="modeling-stage-main">
        <strong>${html(state.stageTitle)}</strong>
        <p>${html(state.stageDescription)}</p>
        <span class="modeling-stage-label">推荐动作：${html(state.primaryActionLabel)} · ${html(state.actionReason)}</span>
      </div>
      <div class="modeling-compact-metrics" aria-label="建模状态指标">
        <div class="modeling-compact-metric">
          <span>生效</span>
          <strong>${html(state.activeVersion?.versionNo || '无')}</strong>
        </div>
        <div class="modeling-compact-metric">
          <span>候选</span>
          <strong>${html(state.latestDraft?.versionNo || '无')}</strong>
        </div>
        <div class="modeling-compact-metric">
          <span>输入</span>
          <strong>${html(state.scannedTables)}表 / ${html(state.scannedColumns)}字段</strong>
        </div>
      </div>
    `;
  }
  if (actions) {
    const title = actions.querySelector('.modeling-panel-title');
    if (title) {
      title.innerHTML = `<span>下一步动作</span><small>${html(state.primaryActionLabel)} · ${html(state.actionReason)}</small>`;
    }
    actions.querySelectorAll('[data-modeling-action]').forEach(button => {
      const actionKey = button.dataset.modelingAction;
      const active = actionKey === state.primaryActionKey;
      const disabled = (actionKey === 'publish' && !state.latestDraft) || (actionKey === 'rollback' && !state.activeVersion);
      button.classList.toggle('secondary', !active);
      button.classList.toggle('modeling-primary-action', active);
      button.disabled = disabled;
    });
  }
}

function renderModelingRisks(state = deriveModelingCommandState()) {
  const panel = document.querySelector('#modelingRiskPanel');
  if (!panel) return;
  const risks = state.risks || [];
  panel.classList.toggle('is-compact', risks.length <= 1);
  panel.classList.toggle('is-clear', !risks.length);
  panel.innerHTML = `
    <div class="modeling-panel-title">
      <span>风险提示</span>
      <small>${risks.length ? `${risks.length} 项需关注` : '无阻塞'}</small>
    </div>
    <div class="modeling-risk-list ${risks.length ? '' : 'is-clear'}">
      ${risks.length ? risks.map(item => `
        <div class="modeling-risk-item ${html(item.level)}">
          <strong>${html(item.title)}</strong>
          <span>${html(item.message)}</span>
        </div>
      `).join('') : `
        <div class="modeling-risk-item clear">
          <strong>未发现阻塞项</strong>
          <span>当前可继续按推荐动作推进建模流程。</span>
        </div>
      `}
    </div>
  `;
}

function renderModelingSteps(state = deriveModelingCommandState()) {
  const rail = document.querySelector('#modelingStepRail');
  if (!rail) return;
  const steps = [
    {
      key: 'scan',
      index: '01',
      title: '元数据扫描',
      hint: state.scannedTables ? `${state.scannedTables} 表 / ${state.scannedColumns} 字段` : '源表、字段、主键识别',
      status: state.scannedTables || state.objectCount ? 'done' : 'pending',
      statusText: state.scannedTables || state.objectCount ? '已就绪' : '待扫描'
    },
    {
      key: 'candidates',
      index: '02',
      title: '候选确认',
      hint: `${state.objectCount} 对象 / ${state.propertyCount} 属性`,
      status: state.latestDraft ? 'attention' : state.objectCount ? 'done' : 'pending',
      statusText: state.latestDraft ? '待确认' : state.objectCount ? '已生成' : '待生成'
    },
    {
      key: 'graph',
      index: '03',
      title: '语义图谱',
      hint: `${state.relationCount} 条关系`,
      status: state.objectCount ? 'done' : 'pending',
      statusText: state.objectCount ? '可查看' : '待生成'
    },
    {
      key: 'versions',
      index: '04',
      title: '版本发布',
      hint: state.latestDraft ? `${state.latestDraft.versionNo} 待发布` : (state.activeVersion?.versionNo || '无生效版本'),
      status: state.latestDraft ? 'attention' : state.activeVersion ? 'done' : 'pending',
      statusText: state.latestDraft ? '待发布' : state.activeVersion ? '已生效' : '待发布'
    }
  ];
  rail.innerHTML = steps.map(step => `
    <button class="${currentModelingTab === step.key ? 'active' : ''} ${state.recommendedTab === step.key ? 'recommended' : ''} ${html(step.status)}" data-modeling-tab="${html(step.key)}" ${currentModelingTab === step.key ? 'aria-current="step"' : ''}>
      <span class="step-index">${html(step.index)}</span>
      <span class="step-copy">
        <strong>${html(step.title)}</strong>
        <small>${html(step.hint)}</small>
      </span>
      <span class="step-status">${html(step.statusText)}</span>
    </button>
  `).join('');
}

function renderMetadataScanPane(scanResult = currentModelScan) {
  const summary = document.querySelector('#metadataScanSummary');
  const list = document.querySelector('#metadataTableList');
  if (!summary || !list) return;
  const objects = currentModelCandidate?.objects || [];
  const tables = scanResult?.tables || objects.map(item => ({
    tableName: item.sourceTable,
    domain: item.domain,
    primaryKey: item.properties?.[0]?.sourceColumn || 'id',
    columns: item.properties?.map(prop => ({
      columnName: prop.sourceColumn,
      dataType: prop.dataType,
      standardFieldCode: prop.standardFieldCode,
      confidence: prop.confidence
    })) || []
  }));
  const scanStatus = scanResult ? '已扫描' : currentModelScanError ? '扫描失败，使用快照' : '使用当前模型快照';
  const scanStatusClass = scanResult ? 'success' : 'warning';
  const scanDescription = scanResult
    ? `数据源 ${scanResult.dataSourceId}，扫描时间 ${new Date(scanResult.scannedAt).toLocaleString()}`
    : currentModelScanError
      ? `源库扫描失败：${currentModelScanError}。当前已使用最近模型快照作为建模输入，可继续生成候选；源库连接恢复后再重新扫描。`
      : '点击“扫描元数据”后会显示源库表、字段与主键信息。';
  summary.innerHTML = `
    <div class="scan-status-block">
      <span class="tag ${scanStatusClass}">${html(scanStatus)}</span>
      <strong>${html(scanResult?.scanId || '等待下一次扫描')}</strong>
      <p>${html(scanDescription)}</p>
    </div>
    <div class="scan-metrics">
      <div><span>表数量</span><strong>${html(scanResult?.tableCount || tables.length)}</strong></div>
      <div><span>字段数量</span><strong>${html(scanResult?.columnCount || tables.reduce((total, table) => total + (table.columns?.length || 0), 0))}</strong></div>
      <div><span>建模输入</span><strong>${html(objects.length || tables.length)}</strong></div>
    </div>
  `;
  list.innerHTML = `
    <div class="metadata-table-list">
      ${tables.length ? tables.map(table => `
        <div class="metadata-table-card">
          <div class="metadata-table-head">
            <div>
              <strong>${html(table.tableName || table.name || table.sourceTable)}</strong>
              <span>${html(table.domain || table.comment || '业务域待识别')}</span>
            </div>
            <span class="tag">PK ${html(table.primaryKey || table.pk || (typeof table.columns?.[0] === 'string' ? table.columns[0] : table.columns?.[0]?.columnName) || '待确认')}</span>
          </div>
          <div class="metadata-column-list">
            ${(table.columns || []).map(column => {
              const name = typeof column === 'string' ? column : (column.columnName || column.name || column.sourceColumn);
              const dataType = typeof column === 'string' ? 'STRING' : (column.dataType || column.type || 'STRING');
              const mapping = typeof column === 'string' ? (table.comment || '待映射') : (column.standardFieldCode || column.comment || '待映射');
              return `
                <div>
                  <code>${html(name)}</code>
                  <span>${html(dataType)}</span>
                  <small>${html(mapping)}</small>
                </div>
              `;
            }).join('')}
          </div>
        </div>
      `).join('') : '<div class="empty-state">暂无扫描结果</div>'}
    </div>
  `;
}

function renderCandidateModelPane(candidate = currentModelCandidate) {
  const objects = candidate?.objects || [];
  const relations = candidate?.relations || [];
  const objectPanel = document.querySelector('#modelObjects');
  const relationPanel = document.querySelector('#relationModelList');
  if (!objectPanel || !relationPanel) return;
  objectPanel.innerHTML = objects.length ? objects.map(object => `
    <div class="candidate-object-row" data-model-node="${html(object.apiName)}">
      <div class="candidate-object-main">
        <div class="candidate-title">
          <strong>${html(object.displayName)}</strong>
          <code>${html(object.apiName)}</code>
          <span class="tag ${object.confidence >= 9 ? 'success' : 'warning'}">置信度 ${html(object.confidence)}</span>
        </div>
        <p>${html(object.domain)} · 来源表 ${html(object.sourceTable)} · ${html(object.status)}</p>
      </div>
      <div class="candidate-property-grid">
        ${(object.properties || []).map(prop => `
          <div class="property-chip">
            <strong>${html(prop.displayName)}</strong>
            <code>${html(prop.apiName)}</code>
            <span>${html(prop.dataType)} · ${html(prop.sourceColumn)}</span>
            <small>标准字段 ${html(prop.standardFieldCode || '待映射')} · ${html(prop.confidence)}</small>
          </div>
        `).join('')}
      </div>
    </div>
  `).join('') : '<div class="empty-state">暂无候选对象，请先生成候选</div>';
  relationPanel.innerHTML = `
    <div class="relation-panel-head">
      <h3>关系候选</h3>
      <span class="tag">${html(relations.length)} 条关系</span>
    </div>
    ${relations.length ? relations.map(relation => `
      <button class="relation-row" data-model-edge="${html(relation.code)}">
        <span>${html(relation.displayName)}</span>
        <code>${html(relation.sourceObject)} → ${html(relation.targetObject)}</code>
        <strong>${html(relation.confidence)}</strong>
      </button>
    `).join('') : '<div class="empty-state">暂无候选关系</div>'}
    ${(candidate?.suggestions || []).length ? `
      <div class="modeling-suggestion-list">
        <h3>建模建议</h3>
        ${candidate.suggestions.map(item => `<p>${html(item)}</p>`).join('')}
      </div>
    ` : ''}
  `;
}

function setModelCandidateGenerationStatus(running) {
  const objectPanel = document.querySelector('#modelObjects');
  const relationPanel = document.querySelector('#relationModelList');
  if (!objectPanel || !relationPanel) return;
  if (!running) {
    renderCandidateModelPane(currentModelCandidate);
    return;
  }
  objectPanel.innerHTML = `
    <div class="candidate-generation-state">
      <span class="tag warning"><i class="loading-dot"></i>候选生成中</span>
      <strong>正在生成对象、属性与关系候选</strong>
      <p>系统正在读取元数据扫描结果并按本体建模规则生成候选模型，完成后会自动展示对象候选、关系候选和建模建议。</p>
    </div>
  `;
  relationPanel.innerHTML = `
    <div class="relation-panel-head">
      <h3>关系候选</h3>
      <span class="tag">处理中</span>
    </div>
    <div class="empty-state">候选生成完成后展示关系类型</div>
  `;
}

function renderModel(candidate, selectRecommendedTab = false) {
  if (candidate) currentModelCandidate = candidate;
  if (!selectedModelDetail && currentModelCandidate?.objects?.length) {
    selectedModelDetail = { type: 'node', id: currentModelCandidate.objects[0].apiName };
  }
  const state = deriveModelingCommandState();
  renderModelingCommandCenter(state);
  renderModelingSummary(state);
  renderModelingSteps(state);
  renderModelingRisks(state);
  renderMetadataScanPane();
  renderCandidateModelPane(currentModelCandidate);
  renderSemanticGraph(currentModelCandidate);
  renderVersionReleasePane(currentModelVersions);
  if (selectRecommendedTab) {
    switchModelingTab(state.recommendedTab);
  }
}

function domainClass(domain) {
  if (String(domain || '').includes('财务')) return 'finance';
  if (String(domain || '').includes('投资')) return 'investment';
  if (String(domain || '').includes('人事')) return 'hr';
  return 'general';
}

function modelNodePosition(index, count) {
  const positions = [
    [18, 22],
    [62, 18],
    [22, 66],
    [68, 64],
    [44, 42],
    [80, 38]
  ];
  return positions[index] || [18 + ((index * 19) % 64), 20 + ((index * 23) % 58)];
}

function renderSemanticGraph(candidate = currentModelCandidate) {
  const graph = document.querySelector('#semanticGraph');
  if (!graph) return;
  const objects = candidate?.objects || [];
  const relations = candidate?.relations || [];
  if (!objects.length) {
    graph.innerHTML = '<div class="empty-state">暂无语义图谱，请先生成候选模型</div>';
    renderModelDetailPanel(null);
    return;
  }
  const nodeMap = new Map(objects.map((object, index) => [object.apiName, { ...object, position: modelNodePosition(index, objects.length) }]));
  const edgeLines = relations.map(relation => {
    const source = nodeMap.get(relation.sourceObject);
    const target = nodeMap.get(relation.targetObject);
    if (!source || !target) return '';
    const selected = selectedModelDetail?.type === 'edge' && selectedModelDetail.id === relation.code;
    return `<line class="model-edge ${selected ? 'active' : ''}" data-model-edge="${html(relation.code)}" x1="${source.position[0]}%" y1="${source.position[1]}%" x2="${target.position[0]}%" y2="${target.position[1]}%"></line>`;
  }).join('');
  graph.innerHTML = `
    <svg class="model-edge-layer" viewBox="0 0 100 100" preserveAspectRatio="none" aria-hidden="true">
      ${edgeLines}
    </svg>
    ${objects.map((object, index) => {
      const [left, top] = modelNodePosition(index, objects.length);
      const selected = selectedModelDetail?.type === 'node' && selectedModelDetail.id === object.apiName;
      return `
        <button class="model-node ${domainClass(object.domain)} ${selected ? 'active' : ''}" data-model-node="${html(object.apiName)}" style="left:${left}%; top:${top}%;">
          <strong>${html(object.displayName)}</strong>
          <span>${html(object.domain)}</span>
          <small>${html(object.properties?.length || 0)} 属性 · ${html(object.confidence)}</small>
        </button>
      `;
    }).join('')}
    ${relations.map(relation => {
      const source = nodeMap.get(relation.sourceObject);
      const target = nodeMap.get(relation.targetObject);
      if (!source || !target) return '';
      const left = (source.position[0] + target.position[0]) / 2;
      const top = (source.position[1] + target.position[1]) / 2;
      return `<button class="edge-label" data-model-edge="${html(relation.code)}" style="left:${left}%; top:${top}%;">${html(relation.displayName)}</button>`;
    }).join('')}
  `;
  renderModelDetailPanel(selectedModelDetail);
}

function renderModelDetailPanel(selection = selectedModelDetail) {
  const panel = document.querySelector('#modelDetailPanel');
  if (!panel) return;
  const candidate = currentModelCandidate;
  if (!candidate || !selection) {
    panel.innerHTML = '<div class="empty-state">点击图谱节点或关系查看详情</div>';
    return;
  }
  if (selection.type === 'edge') {
    const relation = (candidate.relations || []).find(item => item.code === selection.id);
    if (!relation) {
      panel.innerHTML = '<div class="empty-state">关系不存在</div>';
      return;
    }
    const status = modelDetailStatus(relation.status);
    panel.innerHTML = `
      <span class="tag warning">关系类型</span>
      <h3>${html(relation.displayName)}</h3>
      <dl>
        <dt>API</dt><dd>${html(relation.apiName)}</dd>
        <dt>源对象</dt><dd>${html(relation.sourceObject)}</dd>
        <dt>目标对象</dt><dd>${html(relation.targetObject)}</dd>
        <dt>状态</dt><dd><span class="tag ${modelStatusTagClass(status)}">${html(status)}</span></dd>
        <dt>置信度</dt><dd>${html(relation.confidence)}</dd>
      </dl>
    `;
    return;
  }
  const object = (candidate.objects || []).find(item => item.apiName === selection.id);
  if (!object) {
    panel.innerHTML = '<div class="empty-state">对象不存在</div>';
    return;
  }
  const status = modelDetailStatus(object.status);
  panel.innerHTML = `
    <span class="tag success">对象类型</span>
    <h3>${html(object.displayName)}</h3>
    <dl>
      <dt>API</dt><dd>${html(object.apiName)}</dd>
      <dt>业务域</dt><dd>${html(object.domain)}</dd>
      <dt>来源表</dt><dd>${html(object.sourceTable)}</dd>
      <dt>状态</dt><dd><span class="tag ${modelStatusTagClass(status)}">${html(status)}</span></dd>
      <dt>置信度</dt><dd>${html(object.confidence)}</dd>
    </dl>
    <div class="detail-property-list">
      ${(object.properties || []).map(prop => `
        <div>
          <strong>${html(prop.displayName)}</strong>
          <span>${html(prop.apiName)} · ${html(prop.dataType)}</span>
          <small>${html(prop.sourceColumn)} → ${html(prop.standardFieldCode)}</small>
        </div>
      `).join('')}
    </div>
  `;
}

function renderVersionReleasePane(versions = currentModelVersions) {
  const list = document.querySelector('#versions');
  const impact = document.querySelector('#modelingImpactPanel');
  const audit = document.querySelector('#modelingAuditLogs');
  const impactVersion = currentModelImpact
    ? versions.find(item => String(item.id) === String(currentModelImpact.versionId))
    : null;
  if (list) {
    list.innerHTML = versions.length ? versions.map(version => `
      <div class="version-row ${version.status === 'ACTIVE' ? 'active' : ''}">
        <div>
          <strong>${html(version.versionNo)}</strong>
          <span class="tag ${version.status === 'ACTIVE' ? 'success' : version.status === 'DRAFT' ? 'warning' : ''}">${html(version.status)}</span>
        </div>
        <div class="version-stats">
          <span>${html(version.objectCount)} 对象</span>
          <span>${html(version.propertyCount)} 属性</span>
          <span>${html(version.relationCount)} 关系</span>
          <span>${Math.round((version.approvalRate || 0) * 100)}% 确认率</span>
        </div>
        <small>${html(new Date(version.createdAt).toLocaleString())}${version.activatedAt ? ` · 生效 ${html(new Date(version.activatedAt).toLocaleString())}` : ''}</small>
      </div>
    `).join('') : '<div class="empty-state">暂无模型版本</div>';
  }
  if (impact) {
    impact.innerHTML = currentModelImpact ? `
      <div class="impact-card">
        <span class="tag">影响评估</span>
        <strong>${html(impactVersion?.versionNo || `版本 ${currentModelImpact.versionId}`)}</strong>
        <div class="impact-grid">
          <div><span>对象</span><b>${html(currentModelImpact.affectedObjects)}</b></div>
          <div><span>属性</span><b>${html(currentModelImpact.affectedProperties)}</b></div>
          <div><span>关系</span><b>${html(currentModelImpact.affectedRelations)}</b></div>
          <div><span>任务</span><b>${html(currentModelImpact.affectedTasks)}</b></div>
        </div>
        ${(currentModelImpact.warnings || []).map(item => `<p>${html(item)}</p>`).join('')}
      </div>
    ` : '<div class="empty-state">生成候选后展示影响评估</div>';
  }
  if (audit) {
    audit.innerHTML = `
      <h3>最近建模审计</h3>
      ${currentModelAuditLogs.length ? currentModelAuditLogs.slice(0, 6).map(item => `
        <div class="modeling-audit-row">
          <strong>${html(item.entityType)} / ${html(item.action)}</strong>
          <span>${html(item.summary)}</span>
          <small>${html(item.operator)} · ${html(new Date(item.createdAt).toLocaleString())}</small>
        </div>
      `).join('') : '<div class="empty-state">暂无建模审计记录</div>'}
    `;
  }
}

async function openModelPublishConfirm() {
  if (!lastCandidateVersionId) {
    const versions = await api('/internal/modeling/versions');
    currentModelVersions = versions;
    lastCandidateVersionId = versions.find(v => v.status === 'DRAFT')?.id || null;
  }
  if (!lastCandidateVersionId) {
    throw new Error('没有待生效候选版本，请先生成候选');
  }
  const impact = await api(`/internal/modeling/versions/${lastCandidateVersionId}/impact`);
  const version = currentModelVersions.find(item => item.id === lastCandidateVersionId);
  currentModelImpact = impact;
  renderVersionReleasePane(currentModelVersions);
  switchModelingTab('versions');
  closeModelPublishConfirm();
  document.body.insertAdjacentHTML('beforeend', `
    <div class="model-publish-modal" id="modelPublishModal" role="dialog" aria-modal="true" aria-labelledby="modelPublishTitle">
      <div class="model-publish-backdrop" data-close-model-publish="true"></div>
      <div class="model-publish-panel">
        <button class="modal-close" data-close-model-publish="true" aria-label="关闭">×</button>
        <span class="tag warning">发布确认</span>
        <h3 id="modelPublishTitle">确认发布${version ? ` ${html(version.versionNo)}` : '候选版本'}？</h3>
        <p>发布后将替换当前生效语义模型，并触发后续 T+1 DAG 按新对象、属性、关系重新构建。</p>
        <div class="publish-impact-grid">
          <div><span>对象</span><strong>${html(impact.affectedObjects)}</strong></div>
          <div><span>属性</span><strong>${html(impact.affectedProperties)}</strong></div>
          <div><span>关系</span><strong>${html(impact.affectedRelations)}</strong></div>
          <div><span>任务</span><strong>${html(impact.affectedTasks)}</strong></div>
        </div>
        <div class="publish-warning-list">
          ${(impact.warnings || []).map(item => `<p>${html(item)}</p>`).join('')}
        </div>
        <div class="publish-modal-actions">
          <button id="confirmModelPublish">确认发布</button>
          <button class="ghost-lite" data-close-model-publish="true">取消</button>
        </div>
      </div>
    </div>
  `);
}

function closeModelPublishConfirm() {
  document.querySelector('#modelPublishModal')?.remove();
}

async function confirmModelPublish() {
  if (!lastCandidateVersionId) {
    throw new Error('没有可发布的候选版本');
  }
  await api(`/internal/modeling/versions/${lastCandidateVersionId}/publish`, {
    method: 'POST',
    body: JSON.stringify({ confirmed: true, operator: 'admin', remark: '页面确认生效' })
  });
  closeModelPublishConfirm();
  await Promise.all([loadVersions(), loadDashboard(), loadAudit()]);
  switchModelingTab('versions');
  toast('模型已生效');
}

function switchModelingTab(tabName) {
  currentModelingTab = tabName;
  document.querySelectorAll('[data-modeling-tab]').forEach(button => {
    button.classList.toggle('active', button.dataset.modelingTab === tabName);
    button.toggleAttribute('aria-current', button.dataset.modelingTab === tabName);
  });
  document.querySelectorAll('[data-modeling-pane]').forEach(pane => {
    pane.classList.toggle('active', pane.dataset.modelingPane === tabName);
  });
}

async function loadEtlJobs() {
  const jobs = await api('/internal/etl/jobs');
  document.querySelector('#etlJobs').innerHTML = renderTable(jobs, [
    { label: '任务ID', key: 'jobId' },
    { label: '状态', render: row => `<span class="tag ${row.status === 'SUCCESS' ? 'success' : 'danger'}">${html(row.status)}</span>` },
    { label: '对象任务', key: 'objectTasks' },
    { label: '关系任务', key: 'relationTasks' },
    { label: '质量门禁', render: row => row.qualityResults.map(q => `${html(q.objectName)}: ${html(q.status)}, 重复率${q.duplicateRate}`).join('<br>') },
    { label: 'Watermark', render: row => row.watermarks.map(w => `${html(w.sourceCode)} ${html(w.strategy)} ${html(w.value)}`).join('<br>') }
  ]);
}

async function loadSchedule() {
  const schedule = await api('/internal/schedules/t1');
  document.querySelector('#schedulePanel').innerHTML = `
    <div class="inline-panel">
      <span class="tag success">${html(schedule.status)}</span>
      <strong>T+1 ${html(schedule.runAt)}</strong>
      <span>每源库并发 ${html(schedule.maxThreadsPerSource)}</span>
      <span>${schedule.dagNodes.map(node => html(node)).join(' -> ')}</span>
    </div>
  `;
}

async function loadAudit() {
  const logs = await api('/internal/audit/change-logs');
  document.querySelector('#auditLog').innerHTML = logs.slice(0, 12).map(item => `
    <div class="audit-item">
      <strong>${html(item.entityType)} / ${html(item.action)}</strong>
      <p>${html(item.summary)}</p>
      <small>${html(item.operator)} · ${html(new Date(item.createdAt).toLocaleString())}</small>
    </div>
  `).join('');
}

async function loadAcceptance() {
  const data = await api('/acceptance');
  const rows = Object.entries(data).map(([id, status]) => ({ id, status }));
  document.querySelector('#acceptanceList').innerHTML = renderTable(rows, [
    { label: '用例', key: 'id' },
    { label: '状态', render: row => `<span class="tag ${acceptanceTagClass(row.status)}">${html(row.status)}</span>` }
  ]);
}

function acceptanceTagClass(status) {
  const value = String(status || '');
  if (value.startsWith('PASS')) return 'success';
  if (value.startsWith('DEFERRED')) return 'warning';
  if (value.startsWith('PENDING')) return 'warning';
  return 'danger';
}

async function loadOpenapi() {
  const [meta, properties, instances] = await Promise.all([
    api('/open/ontology/meta'),
    api('/open/ontology/properties'),
    api('/open/instances/investment_project')
  ]);
  document.querySelector('#openapiResult').innerHTML = `<pre>${html(JSON.stringify({ meta, properties, instances }, null, 2))}</pre>`;
}

async function askQuestion(sqlOverride) {
  const question = document.querySelector('#question').value.trim() || '查询财务收入利润';
  const payload = {
    question,
    sessionId: 'web',
    requestId: `req-${Date.now()}`,
    sqlOverride
  };
  const result = await api('/internal/query/jobs', { method: 'POST', body: JSON.stringify(payload) });
  lastQueryId = result.queryId;
  document.querySelector('#queryResult').innerHTML = `
    <p><span class="tag success">${html(result.status)}</span> 查询ID：${html(result.queryId)}，耗时 ${html(result.elapsedMs)}ms</p>
    <p>${html(result.summary)}</p>
    <pre>${html(result.generatedSql)}</pre>
    ${renderTable(result.rows, Object.keys(result.rows[0] || {}).map(key => ({ label: key, key })))}
  `;
  await loadAudit();
}

async function chartQuery() {
  if (!lastQueryId) {
    await askQuestion();
  }
  const chart = await api(`/internal/query/jobs/${lastQueryId}/chart`, { method: 'POST', body: JSON.stringify({ chartType: 'bar' }) });
  document.querySelector('#queryResult').insertAdjacentHTML('beforeend', `<pre>${html(JSON.stringify(chart, null, 2))}</pre>`);
}

async function downloadQuery() {
  if (!lastQueryId) {
    await askQuestion();
  }
  const data = await api(`/internal/query/jobs/${lastQueryId}/download`);
  document.querySelector('#queryResult').insertAdjacentHTML('beforeend', `<pre>${html(JSON.stringify(data, null, 2))}</pre>`);
}

async function refreshAll() {
  await Promise.all([
    loadDashboard(),
    loadDataSources(),
    loadComputeResources(),
    loadDataStandards(),
    loadVersions(),
    loadEtlJobs(),
    loadSchedule(),
    loadAudit(),
    loadAcceptance()
  ]);
}

document.addEventListener('click', async (event) => {
  const target = event.target;
  const actionTarget = target.closest('button') || target;
  const modelTarget = target.closest('[data-model-node], [data-model-edge]');
  try {
    if (actionTarget.id === 'refreshAll') await refreshAll();
    if (actionTarget.id === 'inspectSources') {
      await api('/internal/datasources/inspect', { method: 'POST' });
      await loadDataSources();
      toast('巡检完成');
    }
    if (actionTarget.id === 'addSource') {
      openDatasourcePicker();
    }
    if (actionTarget.id === 'batchAddSource') {
      toast('一期先按单个数据源录入，批量新增后续补齐', true);
    }
    if (actionTarget.id === 'sourceSettings') {
      toast('列表设置已按一期默认列固定：数据源信息、连接信息、描述、创建时间、修改时间、责任人、操作');
    }
    if (actionTarget.id === 'addComputeResource') {
      openComputeResourceForm('MYSQL');
    }
    if (actionTarget.id === 'batchAddComputeResource') {
      toast('一期先按单个计算资源录入，批量新增后续补齐', true);
    }
    if (actionTarget.id === 'inspectComputeResources') {
      await Promise.all([loadComputeResources(), loadDashboard()]);
      toast('计算资源状态已刷新');
    }
    if (actionTarget.id === 'computeResourceSettings') {
      toast('列表设置已按一期默认列固定：资源信息、连接与库、状态、最近巡检、更新时间、责任人、操作');
    }
    if (actionTarget.dataset.closeSourceModal) closeDatasourcePicker();
    if (actionTarget.dataset.sourceCategory) {
      renderDatasourceTypeGrid(actionTarget.dataset.sourceCategory, document.querySelector('#sourceTypeSearch')?.value || '');
    }
    if (actionTarget.id === 'sourceTypeSearchButton') {
      renderDatasourceTypeGrid('all', document.querySelector('#sourceTypeSearch')?.value || '');
    }
    if (actionTarget.dataset.pickSourceType) {
      openDatasourceForm(actionTarget.dataset.pickSourceType);
    }
    if (actionTarget.dataset.disabledSourceType) {
      toast(`一期暂不接入 ${actionTarget.dataset.disabledSourceType}，当前支持 DM / MySQL / PolarDB`, true);
    }
    if (actionTarget.dataset.fillSource) {
      const source = currentDataSources.find(item => String(item.id) === String(actionTarget.dataset.fillSource));
      if (source) openDatasourceForm(source.dbType, source);
    }
    if (actionTarget.dataset.deleteSource) {
      await deleteSourceFromList(actionTarget.dataset.deleteSource);
    }
    if (actionTarget.dataset.fillCompute) {
      const resource = currentComputeResources.find(item => String(item.id) === String(actionTarget.dataset.fillCompute));
      if (resource) openComputeResourceForm(resource.dbType, resource);
    }
    if (actionTarget.dataset.activateCompute) {
      await api(`/internal/compute-resources/${actionTarget.dataset.activateCompute}/activate`, { method: 'POST' });
      await Promise.all([loadComputeResources(), loadDashboard(), loadAudit()]);
      toast('已设为当前计算资源');
    }
    if (actionTarget.dataset.deleteCompute) {
      await deleteComputeResourceFromList(actionTarget.dataset.deleteCompute);
    }
    if (actionTarget.dataset.standardTab) {
      switchDataStandardTab(actionTarget.dataset.standardTab);
    }
    if (actionTarget.dataset.modelingTab) {
      switchModelingTab(actionTarget.dataset.modelingTab);
    }
    if (modelTarget?.dataset.modelNode) {
      selectedModelDetail = { type: 'node', id: modelTarget.dataset.modelNode };
      renderSemanticGraph(currentModelCandidate);
      switchModelingTab('graph');
    }
    if (modelTarget?.dataset.modelEdge) {
      selectedModelDetail = { type: 'edge', id: modelTarget.dataset.modelEdge };
      renderSemanticGraph(currentModelCandidate);
      switchModelingTab('graph');
    }
    if (actionTarget.id === 'closeSourceForm' || actionTarget.id === 'cancelSourceForm') closeDatasourceForm();
    if (actionTarget.id === 'closeComputeForm' || actionTarget.id === 'cancelComputeForm') closeComputeResourceForm();
    if (actionTarget.id === 'saveSource') await saveSourceFromForm(false);
    if (actionTarget.id === 'testEditedSource') await saveSourceFromForm(true);
    if (actionTarget.id === 'saveComputeResource') await saveComputeResourceFromForm();
    if (actionTarget.dataset.testSource) {
      const result = await api(`/internal/datasources/${actionTarget.dataset.testSource}/test`, { method: 'POST' });
      await loadDataSources();
      toast(result.message, !result.success);
    }
    if (actionTarget.dataset.toggleSource) {
      const action = actionTarget.dataset.active === 'true' ? 'enable' : 'disable';
      await api(`/internal/datasources/${actionTarget.dataset.toggleSource}/${action}`, { method: 'POST' });
      await Promise.all([loadDataSources(), loadDashboard(), loadAudit()]);
      toast(action === 'enable' ? '已启用' : '已停用');
    }
    if (actionTarget.id === 'scanMetadata') {
      currentModelScanError = null;
      try {
        currentModelScan = await api('/internal/modeling/scan', { method: 'POST', body: JSON.stringify({}) });
      } catch (error) {
        currentModelScan = null;
        currentModelScanError = error.message;
        toast('源库扫描失败，已使用当前模型快照继续建模', true);
      }
      renderModel(currentModelCandidate);
      switchModelingTab('scan');
      if (!currentModelScanError) toast('元数据扫描完成');
    }
    if (actionTarget.id === 'generateCandidates') {
      switchModelingTab('candidates');
      setModelCandidateGenerationStatus(true);
      toast('正在生成候选模型');
      const result = await api('/internal/modeling/candidates/generate', { method: 'POST', body: JSON.stringify({}) });
      lastCandidateVersionId = result.versionId;
      selectedModelDetail = { type: 'node', id: result.objects?.[0]?.apiName };
      await loadModelingWorkbench();
      currentModelCandidate = result;
      currentModelImpact = await api(`/internal/modeling/versions/${result.versionId}/impact`).catch(() => null);
      renderModel(result);
      switchModelingTab('candidates');
      await loadAudit();
      toast(`候选版本已生成：${result.versionId}`);
    }
    if (actionTarget.id === 'publishLatest') {
      await openModelPublishConfirm();
    }
    if (actionTarget.id === 'confirmModelPublish') {
      await confirmModelPublish();
    }
    if (actionTarget.dataset.closeModelPublish) {
      closeModelPublishConfirm();
    }
    if (actionTarget.id === 'rollbackLatest') {
      await api('/internal/modeling/rollback-latest?count=1', { method: 'POST' });
      await Promise.all([loadVersions(), loadDashboard(), loadAudit()]);
      toast('已回滚最近版本');
    }
    if (actionTarget.id === 'generateDataStandards') {
      await generateDataStandards();
    }
    if (actionTarget.id === 'viewGeneratedRoots') {
      switchDataStandardTab('roots');
    }
    if (actionTarget.id === 'viewGeneratedFields') {
      switchDataStandardTab('fields');
    }
    if (actionTarget.dataset.approveRoot) {
      await api(`/internal/dictionary/term-roots/${actionTarget.dataset.approveRoot}/approve`, { method: 'POST', body: JSON.stringify({ operator: 'admin', remark: '页面人工确认' }) });
      await Promise.all([loadDataStandards(), loadAudit(), loadDashboard()]);
      toast('词根已确认生效');
    }
    if (actionTarget.dataset.rejectRoot) {
      await api(`/internal/dictionary/term-roots/${actionTarget.dataset.rejectRoot}/reject`, { method: 'POST', body: JSON.stringify({ operator: 'admin', remark: '页面人工拒绝' }) });
      await Promise.all([loadDataStandards(), loadAudit(), loadDashboard()]);
      toast('词根已拒绝');
    }
    if (actionTarget.dataset.approveField) {
      await api(`/internal/dictionary/standard-fields/${actionTarget.dataset.approveField}/approve`, { method: 'POST', body: JSON.stringify({ operator: 'admin', remark: '页面人工确认' }) });
      await Promise.all([loadDataStandards(), loadAudit(), loadDashboard()]);
      toast('标准字段已确认生效');
    }
    if (actionTarget.dataset.rejectField) {
      await api(`/internal/dictionary/standard-fields/${actionTarget.dataset.rejectField}/reject`, { method: 'POST', body: JSON.stringify({ operator: 'admin', remark: '页面人工拒绝' }) });
      await Promise.all([loadDataStandards(), loadAudit(), loadDashboard()]);
      toast('标准字段已拒绝');
    }
    if (actionTarget.id === 'addRoot') {
      const suffix = Date.now().toString().slice(-3);
      await api('/internal/dictionary/term-roots', { method: 'POST', body: JSON.stringify({ code: `demo${suffix}`, name: `演示词根${suffix}`, domain: '演示域', definition: '页面新增词根' }) });
      await Promise.all([loadDataStandards(), loadAudit()]);
      toast('词根已保存');
    }
    if (actionTarget.id === 'addField') {
      const roots = await api('/internal/dictionary/term-roots');
      const suffix = Date.now().toString().slice(-3);
      await api('/internal/dictionary/standard-fields', { method: 'POST', body: JSON.stringify({ code: `demo_field_${suffix}`, name: `演示字段${suffix}`, dataType: 'STRING', domain: '演示域', description: '页面新增标准字段', rootCodes: [roots[0].code] }) });
      await Promise.all([loadDataStandards(), loadAudit()]);
      toast('标准字段已保存');
    }
    if (actionTarget.id === 'exportDictionary') {
      const data = await api('/internal/dictionary/export');
      document.querySelector('#fields').innerHTML = `<pre>${html(JSON.stringify(data, null, 2))}</pre>`;
      toast('词典CSV已生成');
    }
    if (actionTarget.id === 'triggerEtl') {
      await api('/internal/etl/jobs/t1/trigger', { method: 'POST' });
      await Promise.all([loadEtlJobs(), loadDashboard(), loadAudit()]);
      toast('T+1 同步完成');
    }
    if (actionTarget.id === 'loadSchedule') {
      await loadSchedule();
      toast('调度配置已加载');
    }
    if (actionTarget.id === 'updateSchedule') {
      await api('/internal/schedules/t1', { method: 'PUT', body: JSON.stringify({ runAt: '02:30', status: 'ACTIVE' }) });
      await Promise.all([loadSchedule(), loadAudit()]);
      toast('调度时点已更新');
    }
    if (actionTarget.id === 'askQuestion') await askQuestion();
    if (actionTarget.id === 'chartQuery') await chartQuery();
    if (actionTarget.id === 'downloadQuery') await downloadQuery();
    if (actionTarget.id === 'unsafeSql') await askQuestion('DELETE FROM ontology_instance.employee');
    if (actionTarget.id === 'loadOpenapi') await loadOpenapi();
    if (actionTarget.id === 'loadAcceptance') await loadAcceptance();
  } catch (error) {
    toast(error.message, true);
    if (actionTarget.id === 'unsafeSql') {
      document.querySelector('#queryResult').innerHTML = `<pre>${html(error.message)}</pre>`;
    }
  }
});

document.addEventListener('input', (event) => {
  if (event.target.id === 'sourceTypeFilter' || event.target.id === 'sourceNameFilter') {
    renderDatasourceList();
  }
  if (event.target.id === 'computeTypeFilter' || event.target.id === 'computeNameFilter') {
    renderComputeResourceList();
  }
  if (event.target.id === 'showRejectedRoots') {
    renderTermRoots();
  }
  if (event.target.id === 'showRejectedFields') {
    renderStandardFields();
  }
  if (event.target.id === 'sourceTypeSearch') {
    renderDatasourceTypeGrid('all', event.target.value);
  }
  if (event.target.id === 'sourceJdbcUrl') {
    event.target.dataset.autoJdbc = event.target.value.trim() ? 'false' : 'true';
  }
  if (event.target.id === 'computeJdbcUrl') {
    event.target.dataset.autoJdbc = event.target.value.trim() ? 'false' : 'true';
  }
  if (['sourceHost', 'sourcePort', 'sourceDatabase'].includes(event.target.id)) {
    refreshJdbcPreview();
  }
  if (['computeHost', 'computePort'].includes(event.target.id)) {
    refreshComputeJdbcPreview();
  }
});

document.addEventListener('change', (event) => {
  if (event.target.id === 'sourceTypeFilter') {
    renderDatasourceList();
  }
  if (event.target.id === 'computeTypeFilter') {
    renderComputeResourceList();
  }
  if (event.target.id === 'computeDbType') {
    const type = event.target.value;
    const port = document.querySelector('#computePort');
    if (port) port.value = defaultPort(type);
    refreshComputeJdbcPreview();
  }
});

document.querySelectorAll('a[href^="#"]').forEach(link => {
  link.addEventListener('click', (event) => {
    const viewId = link.getAttribute('href').slice(1);
    if (!viewIds.includes(viewId)) return;
    event.preventDefault();
    setActiveView(viewId);
  });
});

window.addEventListener('hashchange', () => {
  setActiveView(window.location.hash.slice(1), false);
});

initializeSidebarCollapse();
setActiveView(window.location.hash.slice(1), false);
refreshAll().catch(error => toast(error.message, true));
