import { api } from './api.js';
import * as ui from './ui.js';


//刷新知识库文档列表ui
export async function loadDocuments() {
    try {
        const result = await api.listDocuments();//发起请求调用后端接口拿到全部文档数据
        if (result.code !== 200) throw new Error(result.message);//业务状态码校验，后端那你code不等于200，手动抛出异常
        ui.renderDocuments(result.data || []);//调用ui渲染函数，传入文档数组，后端无数据则传入空数组避免报错
    } catch (e) {
        const container = document.getElementById('docListContainer');
        if (container) {
            container.innerHTML = `<div class="empty-state" style="color:var(--danger)">加载失败: ${e.message}</div>`;
        }
    }
}

//app.bindevents调用这里
export async function uploadDocument(file, description) {
    if (!file) {
        ui.setDocUploadStatus('❌ 请选择文件', true);
        return;
    }
    const allowed = ['pdf','doc','docx','xls','xlsx','txt','md','csv','json','java','py','js','html','xml','sql','yml','yaml'];//白名单数组，存放允许上传的文件后缀
    const ext = file.name.split('.').pop().toLowerCase();//file.name获取完整文件名  split()按照括号内参数切分字符串  pop取出数组最后一项   toLowerCase转小写
    if (!allowed.includes(ext)) {//判断后缀是否属于白名单
        ui.setDocUploadStatus('❌ 不支持的文件类型: ' + ext, true);
        return;
    }
    if (file.size > 50 * 1024 * 1024) {
        ui.setDocUploadStatus('❌ 文件超过 50MB 限制', true);
        return;
    }

    ui.setDocUploadStatus('⏳ 上传中...');
    const btn = document.getElementById('docUploadBtn');//获取上传按钮DOM元素
    if (btn) btn.disabled = true;//若找到，则禁用

    const formData = new FormData();
    formData.append('file', file);//新建formdata对象并加入字段和值
    if (description) formData.append('description', description);//若描述参数存在，就再加字段并放入

    try {
        const result = await api.uploadDocument(formData);
        if (result.code === 200) {
            ui.setDocUploadStatus('✅ ' + (result.data.message || '上传成功'));
            //获取文本选择框和描述输入框的DOM
            const input = document.getElementById('docUploadInput');
            const desc = document.getElementById('docDescription');
            //清空输入内容
            if (input) input.value = '';
            if (desc) desc.value = '';

            loadDocuments();//重新请求文档列表，刷新页面表格
            pollDocumentStatus(result.data.id);//轮询函数：因为文档上传只是进入服务器，后端还要解析和向量化，这里传入文档id，定时循环请求后端，查看文档解析是否完成
        } else {
            throw new Error(result.message);
        }
    } catch (e) {
        ui.setDocUploadStatus('❌ ' + e.message, true);
    } finally {
        if (btn) btn.disabled = false;
    }
}
//根据文档id轮询文档处理状态，若状态变成Ready（文档解析完成），则刷新ui
async function pollDocumentStatus(docId) {
    let attempts = 0;//记录轮询次数，
    //开启定时器，每3000毫秒开启一次回调
    const interval = setInterval(async () => {
        attempts++;
        //最大轮询60次：60*3=180s，超时直接停止轮询，防止无限请求
        if (attempts > 60) { clearInterval(interval); return; }
        try {
            const result = await api.listDocuments();//调用接口获取全部文档列表
            const doc = (result.data || []).find(d => d.id === docId);//确保有个数组，在数组里找到第一个id等于docid的对象并返回，这个对象，没有就fanhuiundefined
            if (doc && (doc.status === 'READY' || doc.status === 'ERROR')) {//没有找到队友id，就继续跑定时器
                clearInterval(interval);//停止定时器，结束轮询
                loadDocuments();//重新加载文档列表，刷新页面ui
            }
        } catch (e) {}
    }, 3000);
}

export async function deleteDocument(id) {
    if (!confirm('确定删除该文档？相关向量数据也将被清除。')) return;
    try {
        await api.deleteDocument(id);
        loadDocuments();
    } catch (e) {
        alert('删除失败: ' + e.message);
    }
}
