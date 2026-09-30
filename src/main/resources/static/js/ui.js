import { escapeHtml, formatDateTime } from './utils.js';
import { NAV_ITEMS, PAGE_TITLES } from './config.js';

//DOM操作封装（把get和set打包成函数 ）
//从html文件获取元素，放入变量
//函数名:()=>document.getElementById()  第一个括号是参数列表，最后的括号管返回值类型，也是html里的id
export const $ = {
    authOverlay: () => document.getElementById('authOverlay'),
    app: () => document.getElementById('app'),
    authBtn: () => document.getElementById('authBtn'),
    switchAuthBtn: () => document.getElementById('switchAuthBtn'),
    authError: () => document.getElementById('authError'),
    emailGroup: () => document.getElementById('emailGroup'),
    username: () => document.getElementById('username'),
    password: () => document.getElementById('password'),
    email: () => document.getElementById('email'),
    sidebar: () => document.getElementById('sidebar'),
    sidebarNav: () => document.getElementById('sidebarNav'),
    sessionList: () => document.getElementById('sessionList'),
    sessionSearch: () => document.getElementById('sessionSearch'),
    newSessionBtn: () => document.getElementById('newSessionBtn'),
    navUserInfo: () => document.getElementById('navUserInfo'),
    logoutLink: () => document.getElementById('logoutLink'),
    menuToggle: () => document.getElementById('menuToggle'),
    pageTitle: () => document.getElementById('pageTitle'),
    themeToggleBtn: () => document.getElementById('themeToggleBtn'),
    pages: () => document.getElementById('pages'),
    messageContainer: () => document.getElementById('messageContainer'),
    userInput: () => document.getElementById('userInput'),
    sendBtn: () => document.getElementById('sendBtn'),
    keysListContainer: () => document.getElementById('keysListContainer'),
    saveKeyBtn: () => document.getElementById('saveKeyBtn'),
    keyStatus: () => document.getElementById('keyStatus'),
    profileUsername: () => document.getElementById('profileUsername'),
    profileEmail: () => document.getElementById('profileEmail'),
    updateProfileBtn: () => document.getElementById('updateProfileBtn'),
    pwdStatus: () => document.getElementById('pwdStatus'),
    changePwdBtn: () => document.getElementById('changePwdBtn'),
    darkModeToggle: () => document.getElementById('darkModeToggle'),
    clearAllBtn: () => document.getElementById('clearAllBtn'),
    // ========== 新增：附件相关选择器 ==========
    attachmentList: () => document.getElementById('attachmentList'),
    attachBtn: () => document.getElementById('attachBtn'),
    chatFileInput: () => document.getElementById('chatFileInput'),
};

export function renderNav(activePage) {
    const nav = $.sidebarNav();
    nav.innerHTML = NAV_ITEMS.map(item => `
        <div class="nav-item ${item.page === activePage ? 'active' : ''}" data-page="${item.page}">
            <span class="nav-icon">${item.icon}</span> ${item.title}
        </div>
    `).join('');
}

export function switchPage(page) {
    document.querySelectorAll('.nav-item').forEach(n => n.classList.remove('active'));
    const navItem = document.querySelector(`.nav-item[data-page="${page}"]`);
    if (navItem) navItem.classList.add('active');
    document.querySelectorAll('.page').forEach(p => p.classList.remove('active'));
    const pageEl = document.getElementById('page-' + page);
    if (pageEl) pageEl.classList.add('active');
    $.pageTitle().textContent = PAGE_TITLES[page] || 'AgentKey';
    if (window.innerWidth < 768) {
        $.sidebar().classList.remove('open');
    }
}

export function toggleSidebar() {
    $.sidebar().classList.toggle('open');
}

export function setLoggedInUI(username) {
    $.authOverlay().style.display = 'none';
    $.app().style.display = 'flex';
    $.navUserInfo().textContent = '👤 ' + username;
}

//设置页面为未登录
export function setLoggedOutUI() {
    $.authOverlay().style.display = 'flex';
    $.app().style.display = 'none';
    $.navUserInfo().textContent = '👤 未登录';
    $.messageContainer().innerHTML = '';
    $.sessionList().innerHTML = '';
}

export function setAuthLoading(loading) {
    $.authBtn().disabled = loading;//disable是按钮禁用属性
}


//msg：展示给用户的文字
export function setAuthError(msg, isSuccess = false) {
    const el = $.authError();
    el.textContent = msg;//往标签内容放文本
    el.style.color = isSuccess ? 'var(--success)' : 'var(--danger)';//判断真假，使用对应颜色
}

//切换页面模式的函数
export function toggleAuthMode(isRegister) {
    $.authBtn().textContent = isRegister ? '注 册' : '登 录';//根据当前模式更改按钮文字
    $.switchAuthBtn().textContent = isRegister ? '已有账号？去登录' : '还没有账号？去注册';
    $.emailGroup().style.display = isRegister ? 'block' : 'none';//display控制显隐，注册页隐，登录页显
    setAuthError('');
}

export function addUserMessage(text) {
    const container = $.messageContainer();
    const div = document.createElement('div');
    div.className = 'message user';
    div.textContent = text;
    container.appendChild(div);
    container.scrollTop = container.scrollHeight;
    return div;
}

export function createAssistantContainer() {
    const container = $.messageContainer();
    const div = document.createElement('div');
    div.className = 'message assistant';
    div.innerHTML = `
        <div class="thinking-box" style="display:none;">
            <div class="thinking-title">💭 思考中...</div>
            <div class="thinking-content"></div>
        </div>
        <div class="tool-chain"></div>
        <div class="ai-answer">
            <div class="md-content">
                <div class="typing"><span></span><span></span><span></span></div>
            </div>
        </div>
        <div class="msg-actions">
            <button class="msg-action-btn" data-action="copy">📋 复制</button>
            <button class="msg-action-btn" data-action="regenerate">🔄 重新生成</button>
        </div>
    `;
    container.appendChild(div);
    container.scrollTop = container.scrollHeight;
    return {
        el: div,
        thinkingBox: div.querySelector('.thinking-box'),
        thinkingContent: div.querySelector('.thinking-content'),
        toolChain: div.querySelector('.tool-chain'),
        mdContent: div.querySelector('.md-content')
    };
}

export function renderSessions(sessions, activeSessionId, keyword = '') {
    const list = $.sessionList();
    const kw = keyword.toLowerCase();
    list.innerHTML = '';
    sessions.forEach(s => {
        if (kw && !(s.title || '').toLowerCase().includes(kw)) return;
        const item = document.createElement('div');
        item.className = 'session-item' + (s.id === activeSessionId ? ' active' : '');
        item.dataset.sessionId = s.id;
        item.innerHTML = `
            <div class="session-title">${escapeHtml(s.title || '会话' + s.id)}</div>
            <div class="session-preview">${formatDateTime(s.updateTime)}</div>
            <span class="del" title="删除">×</span>
        `;
        list.appendChild(item);
    });
}

export function showWelcomeMessage() {
    const container = $.messageContainer();
    container.innerHTML = '';
    const div = document.createElement('div');
    div.className = 'message assistant';
    div.innerHTML = `
        <div class="ai-answer">
            <div class="md-content">
                <p>你好！我是 AgentKey Pro 智能助手。</p>
                <p>你可以直接发送消息，或上传文件（PDF、Word、代码等）让我分析。</p>
                <ul>
                    <li>今天北京天气怎么样？</li>
                    <li>上传代码文件并问：这段代码有什么 Bug？</li>
                    <li>上传 PDF 并问：总结这份报告的核心观点</li>
                </ul>
            </div>
        </div>
    `;
    container.appendChild(div);
}

export function addHistoryMessage(role, content) {
    if (role === 'USER') {
        addUserMessage(content);
    } else if (role === 'ASSISTANT') {
        const container = $.messageContainer();
        const div = document.createElement('div');
        div.className = 'message assistant';
        div.innerHTML = `
            <div class="ai-answer"><div class="md-content">${DOMPurify.sanitize(marked.parse(content))}</div></div>
            <div class="msg-actions">
                <button class="msg-action-btn" data-action="copy">📋 复制</button>
                <button class="msg-action-btn" data-action="regenerate">🔄 重新生成</button>
            </div>
        `;
        container.appendChild(div);
    }
}

export function renderKeys(keys) {
    const container = $.keysListContainer();
    if (!keys || keys.length === 0) {
        container.innerHTML = `<div class="empty-state"><div class="empty-state-icon">🔐</div><div>暂无 API Key</div></div>`;
        return;
    }
    let html = `<table class="keys-table">
        <thead><tr><th>提供商</th><th>别名</th><th>创建时间</th><th style="width:80px">操作</th></tr></thead>
        <tbody>`;
    keys.forEach(k => {
        html += `<tr>
            <td><span class="key-badge">${escapeHtml(k.provider)}</span></td>
            <td>${escapeHtml(k.alias || '-')}</td>
            <td>${formatDateTime(k.createTime)}</td>
            <td><button class="btn-small btn-small-danger delete-key-btn" data-id="${k.id}" style="padding:4px 10px;font-size:12px;">删除</button></td>
        </tr>`;
    });
    html += '</tbody></table>';
    container.innerHTML = html;
}

export function renderKeysError(msg) {
    $.keysListContainer().innerHTML = `<div class="empty-state" style="color:var(--danger)">加载失败: ${escapeHtml(msg)}</div>`;
}

export function setKeyStatus(msg, isError = false) {
    const el = $.keyStatus();
    el.textContent = msg;
    el.style.color = isError ? 'var(--danger)' : 'var(--success)';
}

export function clearKeyInputs() {
    document.getElementById('newKeyInput').value = '';
    document.getElementById('newKeyAlias').value = '';
}

export function setProfile(user) {
    if (!user) return;
    $.profileUsername().value = user.username || '';
    $.profileEmail().value = user.email || '';
}

export function setPwdStatus(msg, isError = false) {
    const el = $.pwdStatus();
    el.textContent = msg;
    el.style.color = isError ? 'var(--danger)' : 'var(--success)';
}

export function clearPwdInputs() {
    document.getElementById('oldPassword').value = '';
    document.getElementById('newPassword').value = '';
    document.getElementById('confirmPassword').value = '';
}

export function setInputLoading(loading) {
    $.sendBtn().disabled = loading;
    $.userInput().disabled = loading;
}

export function highlightCodeBlocks(container) {
    container.querySelectorAll('pre code').forEach(block => {
        hljs.highlightElement(block);
        const pre = block.parentElement;
        if (pre.querySelector('.code-header')) return;
        const lang = block.className.replace('language-', '').replace('hljs', '').trim() || 'code';
        const header = document.createElement('div');
        header.className = 'code-header';
        header.innerHTML = `
            <span class="code-lang">${escapeHtml(lang)}</span>
            <button class="copy-code-btn" data-action="copy-code">复制</button>
        `;
        pre.insertBefore(header, block);
    });
}

export function updateMarkdown(mdContent, markdown) {
    mdContent.innerHTML = DOMPurify.sanitize(marked.parse(markdown));
    highlightCodeBlocks(mdContent);
}

export function createToolUI(toolChain, toolName, argsJson) {
    const wrapper = document.createElement('div');
    wrapper.className = 'tool-wrapper';
    wrapper.dataset.tool = toolName;
    wrapper.dataset.args = argsJson || '{}';
    let city = '';
    try { city = JSON.parse(argsJson).city || ''; } catch (e) {}
    const cityLabel = city ? `(${escapeHtml(city)})` : '';
    const statusBar = document.createElement('div');
    statusBar.className = 'tool-status-bar';
    statusBar.innerHTML = `
        <span class="tool-name">🔧 ${escapeHtml(toolName)} ${cityLabel}</span>
        <span class="tool-badge">查询中...</span>
        <span class="tool-toggle">▼ 展开</span>
    `;
    const detail = document.createElement('div');
    detail.className = 'tool-detail';
    let formattedArgs = argsJson || '{}';
    try { formattedArgs = JSON.stringify(JSON.parse(argsJson), null, 2); } catch (e) {}
    detail.innerHTML = `
        <div class="detail-row"><span class="detail-label">工具:</span> <span class="detail-value">${escapeHtml(toolName)}</span></div>
        <div class="detail-row"><span class="detail-label">参数:</span> <span class="detail-value">${escapeHtml(formattedArgs)}</span></div>
        <div class="detail-row"><span class="detail-label">结果:</span></div>
        <div class="detail-result" style="display:none;"></div>
    `;
    statusBar.addEventListener('click', () => {
        detail.classList.toggle('show');
        const toggle = statusBar.querySelector('.tool-toggle');
        toggle.textContent = detail.classList.contains('show') ? '▲ 收起' : '▼ 展开';
    });
    wrapper.appendChild(statusBar);
    wrapper.appendChild(detail);
    toolChain.appendChild(wrapper);
    $.messageContainer().scrollTop = $.messageContainer().scrollHeight;
    return wrapper;
}

export function updateToolResult(wrapper, success, content, error, costMs) {
    const badge = wrapper.querySelector('.tool-badge');
    const resultDiv = wrapper.querySelector('.detail-result');
    if (success) {
        badge.textContent = '✅ 完成';
        badge.className = 'tool-badge success';
        resultDiv.textContent = content;
    } else {
        badge.textContent = '❌ 失败';
        badge.className = 'tool-badge error';
        resultDiv.textContent = error || '未知错误';
    }
    resultDiv.style.display = 'block';
    if (costMs) {
        const costRow = document.createElement('div');
        costRow.className = 'detail-row';
        costRow.innerHTML = `<span class="detail-label">耗时:</span> <span class="detail-value">${costMs}ms</span>`;
        wrapper.querySelector('.tool-detail').appendChild(costRow);
    }
    $.messageContainer().scrollTop = $.messageContainer().scrollHeight;
}

// ========== 知识库 UI ==========
//接收文档数组docs，绘制文档列表，写入docListContainner容器
export function renderDocuments(docs) {
    const container = document.getElementById('docListContainer');//找到这个dom对象
    if (!container) return;//没有该容器
    if (!docs || docs.length === 0) {//容器内没有文档
        //直接写入【暂无文档】占位html
        container.innerHTML = `
            <div class="empty-state">
                <div class="empty-state-icon">📚</div>
                <div>暂无文档</div>
                <div style="font-size:13px;margin-top:8px;">上传 PDF、Word、Excel、TXT 等文件构建知识库</div>
            </div>`;
        return;
    }
    //状态映射字典STATUS_MAP
    //后端返回状态字符串，前端展示文字，样式，类名
    const STATUS_MAP = {
        'PENDING': { text: '⏳ 待处理', class: 'status-pending' },
        'PARSING': { text: '🔍 解析中', class: 'status-processing' },
        'CHUNKING': { text: '✂️ 分块中', class: 'status-processing' },
        'EMBEDDING': { text: '🔢 Embedding中', class: 'status-processing' },
        'READY': { text: '✅ 可用', class: 'status-ready' },
        'ERROR': { text: '❌ 失败', class: 'status-error' }
    };
    //初始化html字符串，外层网格容器
    let html = `<div class="doc-grid">`;
    //遍历后端传回的文档数组docs，一份文档生成一张卡片
    docs.forEach(d => {
        //查找状态对应的前端文案，若后端返回未知状态就原样展示
        const st = STATUS_MAP[d.status] || { text: d.status, class: '' };
        //转换文件字节大小（2024字节-》2kb）
        const size = formatFileSize(d.fileSize);
        //拼接单篇文档卡片的html字符串
        html += `
            <div class="doc-card" data-id="${d.id}">
                <div class="doc-icon">${getFileIcon(d.fileType)}</div>
                <div class="doc-info">
                    <div class="doc-name" title="${escapeHtml(d.fileName)}">${escapeHtml(d.fileName)}</div>
                    <div class="doc-meta">
                        <span class="doc-type">${d.fileType.toUpperCase()}</span>
                        <span class="doc-size">${size}</span>
                        <span class="doc-status ${st.class}">${st.text}</span>
                    </div>
                    <div class="doc-meta">${d.chunkCount || 0} 个片段 · ${formatDateTime(d.createTime)}</div>
                    ${d.description ? `<div class="doc-desc">${escapeHtml(d.description)}</div>` : ''}
                </div>
                <button class="doc-delete" data-id="${d.id}" title="删除">×</button>
            </div>
        `;
    });
    //闭合外层网格div
    html += `</div>`;
    //一次性把拼好的html放入docListContainer，覆盖原有全部 内容
    container.innerHTML = html;
}

//
export function setDocUploadStatus(msg, isError = false) {
    const el = document.getElementById('docUploadStatus');
    if (!el) return;
    el.textContent = msg;
    el.style.color = isError ? 'var(--danger)' : 'var(--success)';
}

export function getFileIcon(ext) {
    const map = { pdf: '📕', doc: '📘', docx: '📘', xls: '📗', xlsx: '📗', txt: '📄', md: '📝', csv: '📊' };
    return map[ext?.toLowerCase()] || '📄';
}

export function formatFileSize(bytes) {
    if (!bytes) return '0 B';
    const units = ['B', 'KB', 'MB', 'GB'];
    let i = 0;
    while (bytes >= 1024 && i < units.length - 1) { bytes /= 1024; i++; }
    return bytes.toFixed(1) + ' ' + units[i];
}

//拿取输入框信息
export function getAuthFormData() {
    return {
        username: $.username().value.trim(),//$.username()拿取输入框创建对象，value读取输入输入框内的内容
        password: $.password().value.trim(),
        email: $.email().value.trim()
    };
}

export function getKeyFormData() {
    return {
        provider: document.getElementById('newKeyProvider').value,
        key: document.getElementById('newKeyInput').value.trim(),
        alias: document.getElementById('newKeyAlias').value.trim()
    };
}

export function getPasswordFormData() {
    return {
        oldPwd: document.getElementById('oldPassword').value.trim(),
        newPwd: document.getElementById('newPassword').value.trim(),
        confirmPwd: document.getElementById('confirmPassword').value.trim()
    };
}

// ========== 新增：附件 UI 函数 ==========
export function addAttachmentChip(fileName, status, id) {
    const list = document.getElementById('attachmentList');
    if (!list) return null;
    const chip = document.createElement('div');
    chip.className = 'attachment-chip' + (status === 'uploading' ? ' uploading' : '') + (status === 'error' ? ' error' : '');
    chip.dataset.id = id || '';
    chip.innerHTML = `
        <span>📎 ${escapeHtml(fileName)}</span>
        <span class="remove" title="移除">×</span>
    `;
    chip.querySelector('.remove').addEventListener('click', () => chip.remove());
    list.appendChild(chip);
    return chip;
}

export function clearAttachments() {
    const list = document.getElementById('attachmentList');
    if (list) list.innerHTML = '';
}

export function addAttachmentHint(count) {
    const container = document.getElementById('messageContainer');
    if (!container) return;
    const div = document.createElement('div');
    div.className = 'message attachment-hint';
    div.textContent = `📎 已上传 ${count} 个文件`;
    container.appendChild(div);
    container.scrollTop = container.scrollHeight;
}