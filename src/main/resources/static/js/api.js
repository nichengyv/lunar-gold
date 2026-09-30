/**
 * http请求工具
 */

import { CONFIG } from './config.js';

class ApiClient {
    constructor() { this.token = null; }
    setToken(token) { this.token = token; }
    clearToken() { this.token = null; }

    //token放进请求头
    //发生网络请求时：const header=api.getHeader();（详见securityconfig类的白名单，规定了需要token请求头的地址）
    //后端靠Header识别用户
    //'Bearer'本质是一个类型标签，，表示后面是持有者令牌，按Bearer方式验证（一般JWT就用这个）
    getHeaders(contentType = true) {
        const h = {};//创建了一个可变json对象
        if (this.token) h['Authorization'] = 'Bearer ' + this.token;
        if (contentType) h['Content-Type'] = 'application/json';//告诉后端本次请求体的数据格式是json
        return h;
    }
    async request(url, options = {}) {
        const resp = await fetch(url, options);
        //token过期或无效
        if (resp.status === 401) {
            localStorage.removeItem('token');
            localStorage.removeItem('username');
            alert('登录已过期，请重新登录');
            window.location.reload();
            throw new Error('登录已过期');
        }
        return resp;
    }
    async getPublicKey() {
        const resp = await fetch(CONFIG.BASE_URL + '/api/auth/public-key');
        return resp.json();
    }


    async login(body) {
        const resp = await fetch(CONFIG.BASE_URL + '/api/auth/login', {
            method: 'POST', headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(body)
        });
        return resp.json();
     }
    async register(body) {
        const resp = await fetch(CONFIG.BASE_URL + '/api/auth/register', {
            method: 'POST', headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(body)
        });
        return resp.json();
    }
    async getMe() {
        const resp = await this.request(CONFIG.BASE_URL + '/api/auth/me', { headers: this.getHeaders(false) });
        return resp.json();
    }
    async changePassword(body) {
        const resp = await this.request(CONFIG.BASE_URL + '/api/auth/change-password', {
            method: 'POST', headers: this.getHeaders(), body: JSON.stringify(body)
        });
        return resp.json();
    }
    async listSessions() {
        const resp = await this.request(CONFIG.BASE_URL + '/api/agent/sessions', { headers: this.getHeaders(false) });
        return resp.json();
    }
    async getSessionMessages(sessionId) {
        const resp = await this.request(CONFIG.BASE_URL + '/api/agent/sessions/' + sessionId + '/messages', {
            headers: this.getHeaders(false)
        });
        return resp.json();
    }
    async deleteSession(sessionId) {
        const resp = await this.request(CONFIG.BASE_URL + '/api/agent/sessions/' + sessionId, {
            method: 'DELETE', headers: this.getHeaders(false)
        });
        return resp.json();
    }
    async listKeys() {
        const resp = await this.request(CONFIG.BASE_URL + '/api/keys', { headers: this.getHeaders(false) });
        return resp.json();
    }
    async saveKey(body) {
        const resp = await this.request(CONFIG.BASE_URL + '/api/keys', {
            method: 'POST', headers: this.getHeaders(), body: JSON.stringify(body)
        });
        return resp.json();
    }
    async deleteKey(id) {
        const resp = await this.request(CONFIG.BASE_URL + '/api/keys/' + id, {
            method: 'DELETE', headers: this.getHeaders(false)
        });
        return resp.json();
    }
    async streamChat(body) {
        return this.request(CONFIG.BASE_URL + '/api/agent/chat/stream', {
            method: 'POST', headers: this.getHeaders(), body: JSON.stringify(body)
        });
    }
    // ========== 新增：聊天附件上传 ==========
    async uploadChatAttachment(formData) {
        const resp = await this.request(CONFIG.BASE_URL + '/api/agent/chat/attachment', {
            method: 'POST',
            headers: { 'Authorization': 'Bearer ' + this.token },//注意到，h对象声明了文本类型，但是formdata不是，所以这里不用h
            //用了该格式，于是header不再设置 规定请求体格式 的键值对，浏览器底层因为请求体是FormData对象，所自动填充content-type=multipart/form--data
            //需注意，json请求后端用@RequestBody，formdata请求，后端用@RequestPart/RequestParam（这里注解用于赋值）
            body: formData
        });
        return resp.json();
    }
    async uploadDocument(formData) {
        const resp = await this.request(CONFIG.BASE_URL + '/api/knowledge/upload', {
            method: 'POST',
            headers: { 'Authorization': 'Bearer ' + this.token },
            body: formData
        });
        return resp.json();
    }
    async listDocuments() {
        const resp = await this.request(CONFIG.BASE_URL + '/api/knowledge/documents', {
            headers: this.getHeaders(false)
        });
        return resp.json();
    }
    async deleteDocument(id) {
        const resp = await this.request(CONFIG.BASE_URL + '/api/knowledge/documents/' + id, {
            method: 'DELETE', headers: this.getHeaders(false)
        });
        return resp.json();
    }
}

//为ApiClient类实例化一个对象：api
export const api = new ApiClient();