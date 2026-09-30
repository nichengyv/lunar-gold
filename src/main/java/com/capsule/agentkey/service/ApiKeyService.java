package com.capsule.agentkey.service;

import com.capsule.agentkey.entity.UserApiKey;
import com.capsule.agentkey.exception.BusinessException;
import com.capsule.agentkey.repository.UserApiKeyRepository;
import com.capsule.agentkey.util.AesUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

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
    public void saveApiKey(Long userId, String provider, String plainKey, String alias) throws Exception {
        //如果该用户已经存过这个服务商的key，先删除旧记录
        repo.findByUserIdAndProvider(userId, provider).ifPresent(repo::delete);
        UserApiKey key = new UserApiKey();
        key.setUserId(userId);
        key.setProvider(provider);
        key.setEncryptedKey(aesUtil.encrypt(plainKey));//plainKey就是用户输入的明文apikey
        key.setKeyAlias(alias);
        repo.save(key);//存入数据库
        log.info("API Key 已保存 [userId={}, provider={}]", userId, provider);
    }

    //用户发起聊天请求时，AgentChatController 调用
    //解密后的明文 Key 被传给 DeepSeekAgentClient，用于调用 DeepSeek API
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
}
