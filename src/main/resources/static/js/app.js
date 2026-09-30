
//已有对象，大括号里写从对象里取出的方法
import { CONFIG } from './config.js';
import { api } from './api.js';

//没有对象，打包对象，整体导入
import * as ui from './ui.js';
import * as chat from './chat.js';
import * as sessionManager from './session.js';
import * as Knowledge from './knowledge.js';
import { Theme, encryptPassword, escapeHtml } from './utils.js';

class App {
    constructor() {
        this.token = null;
        this.username = '';
        this.currentSessionId = null;
        this.isProcessing = false;
        this.sessions = [];
        this.isRegisterMode = false;
        this.activeToolWrappers = new Map();
        this.useRag = localStorage.getItem('useRag') === 'true';

        // 改为存对象 {id, fileName}，方便恢复 UI 和去重
        this.pendingAttachments = [];
        this.sessionAttachments = new Map(); // sessionId -> {id, fileName}[]
        this._lastSentAttachments = [];      // 临时存储，解决新建会话 sessionId 延迟
    }

    init() {
        Theme.init();
        this.restoreSession();
        this.bindEvents();
        ui.renderNav(CONFIG.DEFAULT_PAGE);
    }

    restoreSession() {
        const savedToken = localStorage.getItem('token');
        const savedUser = localStorage.getItem('username');
        if (savedToken && savedUser) {
            this.token = savedToken;
            this.username = savedUser;
            api.setToken(this.token);
            this.enterApp();
        }
    }

    bindEvents() {
        ui.$.authBtn().addEventListener('click', () => {
            this.isRegisterMode ? this.register() : this.login();
        });
        ui.$.switchAuthBtn().addEventListener('click', () => {
            this.isRegisterMode = !this.isRegisterMode;
            ui.toggleAuthMode(this.isRegisterMode);
        });

        ui.$.sidebarNav().addEventListener('click', (e) => {
            const item = e.target.closest('.nav-item');
            if (!item) return;
            this.switchPage(item.dataset.page);
        });

        ui.$.sessionList().addEventListener('click', (e) => {
            const delBtn = e.target.closest('.del');
            if (delBtn) {
                e.stopPropagation();
                const id = parseInt(delBtn.closest('.session-item').dataset.sessionId);
                this.handleDeleteSession(id);
                return;
            }
            const item = e.target.closest('.session-item');
            if (item) this.switchSession(parseInt(item.dataset.sessionId));
        });

        ui.$.sessionSearch().addEventListener('input', (e) => {
            ui.renderSessions(this.sessions, this.currentSessionId, e.target.value);
        });

        ui.$.newSessionBtn().addEventListener('click', () => this.startNewSession());

        ui.$.logoutLink().addEventListener('click', (e) => {
            e.preventDefault();
            this.logout();
        });

        ui.$.menuToggle().addEventListener('click', () => ui.toggleSidebar());
        ui.$.themeToggleBtn().addEventListener('click', () => Theme.toggle());

        ui.$.sendBtn().addEventListener('click', () => this.sendMessage());
        ui.$.userInput().addEventListener('keydown', (e) => {
            if (e.key === 'Enter' && !e.shiftKey) {
                e.preventDefault();
                this.sendMessage();
            }
        });
        ui.$.userInput().addEventListener('input', function() {
            this.style.height = 'auto';
            this.style.height = Math.min(this.scrollHeight, 140) + 'px';
        });

        // ========== 新增：附件上传事件绑定 ==========
        const attachBtn = document.getElementById('attachBtn');
        const fileInput = document.getElementById('chatFileInput');
        if (attachBtn && fileInput) {
            // addEventListener是给DOM元素绑定的事件监听函数，当某事件发生，运行指定代码
            //元素.addEventListener('事件类型'，(变量)==>待执行的代码);    变量接收代码结果

            //当按钮被点击，触发输入框
            attachBtn.addEventListener('click', () => fileInput.click());
            //当输入框内文件发生变化，执行上传函数
            fileInput.addEventListener('change', (e) => this.handleFileUpload(e));
        }

        ui.$.saveKeyBtn().addEventListener('click', () => this.saveKey());
        ui.$.keysListContainer().addEventListener('click', (e) => {
            const btn = e.target.closest('.delete-key-btn');
            if (btn) this.deleteKey(parseInt(btn.dataset.id));
        });

        ui.$.updateProfileBtn().addEventListener('click', () => this.updateProfile());
        ui.$.changePwdBtn().addEventListener('click', () => this.changePassword());

        ui.$.darkModeToggle().addEventListener('change', (e) => Theme.set(e.target.checked));
        ui.$.clearAllBtn().addEventListener('click', () => this.clearAllSessions());

        ui.$.messageContainer().addEventListener('click', (e) => {
            const btn = e.target.closest('.msg-action-btn, .copy-code-btn');
            if (btn) {
                chat.handleMessageAction(btn, (text) => {
                    ui.$.userInput().value = text;
                    this.sendMessage();
                });
            }
        });

        const docUploadBtn = document.getElementById('docUploadBtn');
        if (docUploadBtn) {
            docUploadBtn.addEventListener('click', () => {
                const file = document.getElementById('docUploadInput')?.files[0];
                const desc = document.getElementById('docDescription')?.value.trim() || '';
                Knowledge.uploadDocument(file, desc);
            });
        }
        const docListContainer = document.getElementById('docListContainer');
        if (docListContainer) {
            docListContainer.addEventListener('click', (e) => {
                const btn = e.target.closest('.doc-delete');
                if (btn) Knowledge.deleteDocument(parseInt(btn.dataset.id));
            });
        }
        const ragToggle = document.getElementById('ragToggle');
        if (ragToggle) {
            ragToggle.addEventListener('change', (e) => {
                this.useRag = e.target.checked;
                localStorage.setItem('useRag', this.useRag);
            });
        }
    }

    async switchPage(page) {
        ui.switchPage(page);
        if (page === 'keys') await this.loadKeys();
        if (page === 'profile') await this.loadProfile();
        if (page === 'knowledge') await Knowledge.loadDocuments();
    }

    async login() {
        const { username, password } = ui.getAuthFormData();
        if (!username || !password) {
            ui.setAuthError('请输入用户名和密码');
            return;
        }
        ui.setAuthLoading(true);//点过登陆，锁按钮
        ui.setAuthError('');//清空上次登陆失败留下的错误提示
        try {
            const enc = await encryptPassword(password, CONFIG.BASE_URL);//RSA加密，await等待加密完成
            const result = await api.login({ username, encryptedPassword: enc });//发起请求，把用户名和加密后的密码传给后端，等待后端响应
            if (result.code !== 200) throw new Error(result.message || '登录失败');//后端返回码不是200，登录失败，throw手动抛出异常，函数停止执行
            this.token = result.data.token;
            this.username = username;
            //把token，用户名存入浏览器本地存储，setItem往浏览器本地存储保存数据
            localStorage.setItem('token', this.token);//localStorage完整是window.localStorage，作用是给用户浏览器本地，永久存放字符串类型的数据
            localStorage.setItem('username', this.username);
            //把token设置给http请求工具，之后每一次请求自动带上这个令牌
            api.setToken(this.token);
            this.enterApp();//登录成功：切换页面ui，隐藏登录页，就成功登录了
        } catch (e) {
            //捕获所有错误
            ui.setAuthError(e.message);
        } finally {
            //无论登录成功或失败，最后一定执行：解锁登录按钮，使其恢复可用
            ui.setAuthLoading(false);
        }
    }

    //asy声明异步函数
    async register() {
        //从页面表单读取用户输入
        const { username, password, email } = ui.getAuthFormData();
        //最终的邮箱要么是用户输入，要么给个默认邮箱（注意：后续将邮箱改为必填项）
        const finalEmail = email || username + '@example.com';

        if (!username || !password) {
            ui.setAuthError('请输入用户名和密码');
            return;
        }
        //加载中
        ui.setAuthLoading(true);
        //清空之前可能残留的错误提示
        ui.setAuthError('');
        try {

            //加密源码···
            //输入明文password，加密输出enc
            // 这里的enc就是后端里的encryptedBase64（详见RsaUtil类的加密方法）
            const enc = await encryptPassword(password, CONFIG.BASE_URL);
            //调用api.register发起HTTP请求
            const result = await api.register({ username, encryptedPassword: enc, email: finalEmail });
            if (result.code !== 200) throw new Error(result.message || '注册失败');
            ui.setAuthError('✅ 注册成功，请登录', true);
            //定时器，一定时间后执行代码块
            setTimeout(() => {
                this.isRegisterMode = false;//判断当前页面是否处于注册模式
                ui.toggleAuthMode(false);//调用切换页面模式的函数
            }, 1200);
        } catch (e) {
            ui.setAuthError(e.message);
        } finally {
            ui.setAuthLoading(false);
        }
    }

    logout() {
        this.token = null;
        this.username = '';
        this.currentSessionId = null;
        this.sessions = [];
        this.pendingAttachments = [];  // ← 新增：清空附件
        localStorage.removeItem('token');
        localStorage.removeItem('username');
        api.clearToken();
        ui.setLoggedOutUI();
    }

    //切换ui界面
    enterApp() {
        ui.setLoggedInUI(this.username);//
        this.loadSessions();//加载侧边栏会话列表
        this.startNewSession(false);//新建一个空白会话，不自动切换到聊天
        const ragToggle = document.getElementById('ragToggle');
        if (ragToggle) ragToggle.checked = this.useRag;//恢复RAG开关状态
    }

    async loadSessions() {
        this.sessions = await sessionManager.loadSessions(this.currentSessionId);
    }

    async handleDeleteSession(id) {
        const ok = await sessionManager.deleteSession(id);
        if (ok) {
            if (this.currentSessionId === id) this.startNewSession(false);
            this.loadSessions();
        }
    }

    startNewSession(switchToChat = true) {
        this.currentSessionId = null;
        this.pendingAttachments = [];
        this._lastSentAttachments = [];
        ui.clearAttachments();
        ui.showWelcomeMessage();
        this.loadSessions();
        if (switchToChat) this.switchPage('chat');
    }

    async switchSession(sessionId) {
        this.currentSessionId = sessionId;
        this.pendingAttachments = this.sessionAttachments.get(sessionId) || [];
        ui.clearAttachments();

        // 恢复已关联的附件 chips，并绑定正确的 remove 事件
        this.pendingAttachments.forEach(att => {
            const chip = ui.addAttachmentChip(att.fileName, 'done', att.id);
            if (chip) {
                const removeBtn = chip.querySelector('.remove');
                removeBtn.replaceWith(removeBtn.cloneNode(true));
                chip.querySelector('.remove').addEventListener('click', () => {
                    chip.remove();
                    this.pendingAttachments = this.pendingAttachments.filter(a => a.id !== att.id);
                });
            }
        });

        ui.$.messageContainer().innerHTML = '';
        ui.renderSessions(this.sessions, sessionId);
        const messages = await sessionManager.loadSessionHistory(sessionId);
        messages.forEach(m => ui.addHistoryMessage(m.role, m.content));
        this.switchPage('chat');
    }

    async clearAllSessions() {
        const ok = await sessionManager.clearAllSessions();
        if (ok) {
            this.startNewSession(false);
            alert('✅ 已清空所有会话');
        }
    }

    // ========== 新增：附件上传处理 ==========
    async handleFileUpload(e) {
        //拿取第一个文件
        //e是事件对象  taget调用DOM实例，
        const file = e.target.files[0];
        if (!file) return;
        if (file.size > 20 * 1024 * 1024) {
            alert('文件大小超过 20MB 限制');//过大直接弹窗
            return;
        }
        //ui操作，页面生成一个小标签块chip，显示文件名，状态标记uploading，给用户正在上传文件的视觉效果
        const chip = ui.addAttachmentChip(file.name, 'uploading');
        //新建formdata对象，把文件传入，字段名是file，要和后端接口接受参数名保持一致
        const formData = new FormData();
        formData.append('file', file);
        try {
            const result = await api.uploadChatAttachment(formData);
            if (result.code === 200) {
                const att = { id: result.data.id, fileName: result.data.fileName };
                this.pendingAttachments.push(att);//pendingAttachments附件ID数组，存已经上传，等待随消息一同发出的附件id
                if (chip) {
                    chip.classList.remove('uploading');//把“上传中”的样式去掉
                    chip.dataset.id = result.data.id;//把后端返回文件id存到DOM标签dataset-id上（dataset由框架自己生成）
                    // 覆盖 ui.js 里的 remove 监听器，确保从数组中同步删除

                    //querySelector从父元素，找匹配的子元素，返回找到的第一个DOM节点
                    const removeBtn = chip.querySelector('.remove');
                    //cloneNode深度克隆DOM节点，true则把这个按钮连同里面所有子元素完整复制一份全新DOM（复制一个外观一样的按钮，逻辑不复制）
                    //replaceWith ，把页面上的旧DOM节点，换为新节点
                    //多此一举的原因：上一条语句获取的删除按钮可能自带click监听，这里需要防止重复执行
                    removeBtn.replaceWith(removeBtn.cloneNode(true));
                    chip.querySelector('.remove').addEventListener('click', () => {
                        //执行点击后，移除附件方块
                        chip.remove();
                        //filter：数组自带方法，用于遍历数组内元素
                        //数组.filter(回调数组)
                        //回调数组true：这个元素保留到新数组反之则丢弃
                        //箭头函数a：代表当前循环的每一个对象    att.id：当前要删掉的附件id（若当前该对象id不等于要删除的id，则保留）
                        this.pendingAttachments = this.pendingAttachments.filter(a => a.id !== att.id);
                    });
                }
            } else {
                throw new Error(result.message);
            }
        } catch (err) {
            if (chip) {
                chip.classList.add('error');
                chip.querySelector('span').textContent = `❌ ${file.name}`;
            }
            console.error('附件上传失败', err);
            alert('上传失败: ' + err.message);
        }
        e.target.value = '';//清空fileinput选中的值
    }

    async sendMessage() {

        if (this.isProcessing) return;//防止重复点击
        const text = ui.$.userInput().value.trim();
        if (!text && this.pendingAttachments.length === 0) return;

        ui.$.userInput().value = '';
        ui.$.userInput().style.height = 'auto';
        if (text) ui.addUserMessage(text);
        if (this.pendingAttachments.length > 0) {
            ui.addAttachmentHint(this.pendingAttachments.length);
        }
        this.isProcessing = true;
        ui.setInputLoading(true);

        const uiCtx = ui.createAssistantContainer();
        let fullText = '';
        const toolWrappers = new Map();

        const currentAttachments = [...this.pendingAttachments];
        this._lastSentAttachments = currentAttachments; // 暂存，等 sessionId 回来

        // 如果当前已有 sessionId，立即保存
        if (this.currentSessionId != null && currentAttachments.length > 0) {
            const existing = this.sessionAttachments.get(this.currentSessionId) || [];
            const map = new Map();
            [...existing, ...currentAttachments].forEach(a => map.set(a.id, a));
            this.sessionAttachments.set(this.currentSessionId, [...map.values()]);
        }

        this.pendingAttachments = [];
        ui.clearAttachments();

        try {
            await chat.sendStreamMessage(text, this.currentSessionId, this.useRag,
                currentAttachments.map(a => a.id), {
                    onSessionId: (id) => {
                        this.currentSessionId = id;
                        this.loadSessions();
                        // 🔑 新建会话：后端返回 sessionId 后，把刚才的附件正式关联
                        if (this._lastSentAttachments.length > 0) {
                            const existing = this.sessionAttachments.get(id) || [];
                            const map = new Map();
                            [...existing, ...this._lastSentAttachments].forEach(a => map.set(a.id, a));
                            this.sessionAttachments.set(id, [...map.values()]);
                            this._lastSentAttachments = [];
                        }
                    },
                    onThinking: (thought) => {
                        uiCtx.thinkingBox.style.display = 'block';
                        uiCtx.thinkingContent.textContent = thought;
                    },
                    onToolCall: (toolName, args) => {
                        const wrapper = ui.createToolUI(uiCtx.toolChain, toolName, args);
                        toolWrappers.set(toolName, wrapper);
                    },
                    onToolResult: (toolName, data) => {
                        const wrapper = toolWrappers.get(toolName);
                        if (wrapper) {
                            ui.updateToolResult(wrapper, data.success, data.content, data.error, data.costMs);
                        }
                    },
                    onContent: (full, chunk) => {
                        fullText = full;
                        ui.updateMarkdown(uiCtx.mdContent, fullText);
                    },
                    onDone: () => {
                        uiCtx.thinkingBox.style.display = 'none';
                    },
                    onError: (err) => {
                        uiCtx.mdContent.innerHTML = '❌ ' + escapeHtml(err.message);
                    }
                });
        } catch (e) {
            uiCtx.mdContent.innerHTML = '❌ ' + escapeHtml(e.message);
        } finally {
            this.isProcessing = false;
            ui.setInputLoading(false);
            ui.$.userInput().focus();
        }
    }

    async loadKeys() {
        try {
            const result = await api.listKeys();
            if (result.code !== 200) throw new Error(result.message);
            ui.renderKeys(result.data || []);
        } catch (e) {
            ui.renderKeysError(e.message);
        }
    }

    async saveKey() {
        const { provider, key, alias } = ui.getKeyFormData();
        if (!key) {
            ui.setKeyStatus('❌ Key 不能为空', true);
            return;
        }
        try {
            const result = await api.saveKey({ provider, apiKey: key, alias: alias || provider });
            if (result.code === 200) {
                ui.setKeyStatus('✅ 保存成功');
                ui.clearKeyInputs();
                this.loadKeys();
            } else {
                throw new Error(result.message);
            }
        } catch (e) {
            ui.setKeyStatus('❌ ' + e.message, true);
        }
    }

    async deleteKey(id) {
        if (!confirm('确定删除该 Key？')) return;
        try {
            await api.deleteKey(id);
            this.loadKeys();
        } catch (e) {
            alert('删除失败: ' + e.message);
        }
    }

    async loadProfile() {
        try {
            const result = await api.getMe();
            if (result.code === 200 && result.data) {
                ui.setProfile(result.data);
            }
        } catch (e) {
            console.error('加载个人信息失败', e);
        }
    }

    updateProfile() {
        alert('保存成功（演示）');
    }

    async changePassword() {
        const { oldPwd, newPwd, confirmPwd } = ui.getPasswordFormData();
        if (!oldPwd || !newPwd) {
            ui.setPwdStatus('❌ 密码不能为空', true);
            return;
        }
        if (newPwd !== confirmPwd) {
            ui.setPwdStatus('❌ 两次输入不一致', true);
            return;
        }
        try {
            const oldEnc = await encryptPassword(oldPwd, CONFIG.BASE_URL);
            const newEnc = await encryptPassword(newPwd, CONFIG.BASE_URL);
            const result = await api.changePassword({ oldPassword: oldEnc, newPassword: newEnc });
            if (result.code === 200) {
                ui.setPwdStatus('✅ 修改成功，请重新登录');
                ui.clearPwdInputs();
                setTimeout(() => this.logout(), 1500);
            } else {
                throw new Error(result.message);
            }
        } catch (e) {
            ui.setPwdStatus('❌ ' + e.message, true);
        }
    }
}

const app = new App();
app.init();