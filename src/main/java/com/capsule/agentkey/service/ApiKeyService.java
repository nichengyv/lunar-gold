package com.capsule.agentkey.service;

import com.capsule.agentkey.entity.UserApiKey;
import com.capsule.agentkey.exception.BusinessException;
import com.capsule.agentkey.exception.ErrorCode;
import com.capsule.agentkey.repository.UserApiKeyRepository;
import com.capsule.agentkey.util.AesUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
//把用户填写的第三方API-KEY加密后存入数据库
@Service
@Slf4j
public class ApiKeyService {
    private final UserApiKeyRepository repo;
    private final AesUtil aesUtil;

    public ApiKeyService(UserApiKeyRepository repo, AesUtil aesUtil) {
        this.repo = repo;
        this.aesUtil = aesUtil;
    }

    //保存API密钥
    //事务保证原子性：旧实现"先删后插"中途异常（如 AES 加密失败）会丢掉用户已存的 Key，
    //且 delete+insert 会导致主键变化；改为查询命中则原地更新（upsert）
    @Transactional
    public void saveApiKey(Long userId, String provider, String plainKey, String alias) throws Exception {
        UserApiKey key = repo.findByUserIdAndProvider(userId, provider)
                .orElseGet(() -> {
                    UserApiKey created = new UserApiKey();
                    created.setUserId(userId);
                    created.setProvider(provider);
                    return created;
                });
        key.setEncryptedKey(aesUtil.encrypt(plainKey));//plainKey就是用户输入的明文apikey
        key.setKeyAlias(alias);
        repo.save(key);//存在则更新，不存在则插入
        log.info("API Key 已保存 [userId={}, provider={}]", userId, provider);
    }

    //用户发起聊天请求时，AgentChatController 调用
    //解密后的明文 Key 被传给 SpringAiAgentClient，用于调用 DeepSeek API
    //明文只在内存中存在，不落盘
    public String getDecryptedKey(Long userId, String provider) throws Exception {
        UserApiKey key = repo.findByUserIdAndProvider(userId, provider)
                .orElseThrow(() -> new BusinessException("未找到API Key，请先配置"));
        return aesUtil.decrypt(key.getEncryptedKey());
    }

    public List<UserApiKey> listByUserId(Long userId) {
        return repo.findByUserId(userId);
    }

    public void deleteById(Long id, Long userId) {
        UserApiKey key = repo.findById(id).orElseThrow(() -> new BusinessException("Key不存在"));
        if (!key.getUserId().equals(userId)) {
            throw new BusinessException("无权操作");
        }
        repo.delete(key);
    }

    /**
     * 测试 API Key 是否可用：按 provider 调一次最便宜的只读端点。
     *
     * 背景：用户保存了错误的 Key 只能等到聊天时才发现，体验差。
     * 这里在保存前/列表中提供"测试连接"，成功即返回，失败抛 BusinessException 并附带可读原因。
     *
     * 各 provider 选择的探活端点原则：成本最低、不改用户数据、能验证鉴权。
     * OpenAI 兼容协议的 provider（DEEPSEEK / 智谱 GLM / Moonshot / Ollama 等）
     * 统一走 GET /models，由 provider 配置的 base-url 决定目标地址。
     */
    public void testConnection(String provider, String plainKey) {
        if (provider == null || plainKey == null || plainKey.isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "provider 与 apiKey 不能为空");
        }
        try {
            switch (provider.trim().toUpperCase()) {
                // 每个 provider 必须打自己的鉴权端点，不能统一指向 DeepSeek，
                // 否则 OpenAI/智谱/Moonshot 的正确 Key 会被误判为失败
                case "DEEPSEEK" -> testOpenAiCompat(plainKey.trim(), "https://api.deepseek.com/models", provider);
                case "OPENAI" -> testOpenAiCompat(plainKey.trim(), "https://api.openai.com/v1/models", provider);
                case "ZHIPU" -> testOpenAiCompat(plainKey.trim(), "https://open.bigmodel.cn/api/paas/v4/models", provider);
                case "MOONSHOT" -> testOpenAiCompat(plainKey.trim(), "https://api.moonshot.cn/v1/models", provider);
                case "OLLAMA" -> testOpenAiCompat(plainKey.trim(), "http://localhost:11434/v1/models", provider);
                case "SILICONFLOW" -> testSiliconFlow(plainKey.trim());
                case "TAVILY" -> testTavily(plainKey.trim());
                default -> throw new BusinessException(ErrorCode.PARAM_INVALID,
                        "暂不支持的 provider: " + provider + "（目前支持 DEEPSEEK / OPENAI / ZHIPU / MOONSHOT / OLLAMA / SILICONFLOW / TAVILY）");
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("API Key 测试连接失败 [provider={}]: {}", provider, e.getMessage());
            throw new BusinessException(ErrorCode.BUSINESS_ERROR, "连接失败: " + e.getMessage());
        }
    }

    /** OpenAI 兼容协议通用鉴权测试：GET /models，最便宜、不消耗 token */
    private void testOpenAiCompat(String apiKey, String modelsUrl, String provider) throws Exception {
        okhttp3.Request req = new okhttp3.Request.Builder()
                .url(modelsUrl)
                .addHeader("Authorization", "Bearer " + apiKey)
                .get().build();
        try (okhttp3.Response resp = newOkhttp().newCall(req).execute()) {
            if (!resp.isSuccessful()) {
                throw new BusinessException(ErrorCode.BUSINESS_ERROR,
                        provider + " 鉴权失败（HTTP " + resp.code() + "），请检查 Key 是否正确");
            }
        }
    }

    /** SiliconFlow：发一次 1 字符的 embedding，成本可忽略 */
    private void testSiliconFlow(String apiKey) throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.node.ObjectNode body = mapper.createObjectNode();
        body.put("model", "BAAI/bge-m3");
        body.putArray("input").add("ping");
        okhttp3.Request req = new okhttp3.Request.Builder()
                .url("https://api.siliconflow.cn/v1/embeddings")
                .addHeader("Authorization", "Bearer " + apiKey)
                .addHeader("Content-Type", "application/json")
                .post(okhttp3.RequestBody.create(mapper.writeValueAsString(body),
                        okhttp3.MediaType.get("application/json; charset=utf-8")))
                .build();
        try (okhttp3.Response resp = newOkhttp().newCall(req).execute()) {
            if (!resp.isSuccessful()) {
                throw new BusinessException(ErrorCode.BUSINESS_ERROR,
                        "SiliconFlow 鉴权失败（HTTP " + resp.code() + "），请检查 Key 是否正确");
            }
        }
    }

    /** Tavily：发一次最简 search */
    private void testTavily(String apiKey) throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.node.ObjectNode body = mapper.createObjectNode();
        body.put("api_key", apiKey);
        body.put("query", "ping");
        body.put("max_results", 1);
        okhttp3.Request req = new okhttp3.Request.Builder()
                .url("https://api.tavily.com/search")
                .addHeader("Content-Type", "application/json")
                .post(okhttp3.RequestBody.create(mapper.writeValueAsString(body),
                        okhttp3.MediaType.get("application/json; charset=utf-8")))
                .build();
        try (okhttp3.Response resp = newOkhttp().newCall(req).execute()) {
            if (!resp.isSuccessful()) {
                throw new BusinessException(ErrorCode.BUSINESS_ERROR,
                        "Tavily 鉴权失败（HTTP " + resp.code() + "），请检查 Key 是否正确");
            }
        }
    }

    /** 测试连接用短超时，避免错误 Key 卡住上传流程 */
    private okhttp3.OkHttpClient newOkhttp() {
        return new okhttp3.OkHttpClient.Builder()
                .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .build();
    }
}
