package com.capsule.agentkey.util;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import javax.crypto.AEADBadTagException;
import java.security.SecureRandom;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AesUtil 纯单元测试：AES-GCM 加解密。
 * 覆盖：加解密往返、同一明文两次密文不同（IV 随机）、篡改密文被 GCM tag 拒绝。
 */
class AesUtilTest {

    private AesUtil aesUtil;

    @BeforeEach
    void setUp() {
        aesUtil = new AesUtil();
        //生成一个 32 字节随机密钥
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        ReflectionTestUtils.setField(aesUtil, "base64MasterKey",
                Base64.getEncoder().encodeToString(key));
    }

    @Test
    void encryptThenDecrypt_roundTrip() throws Exception {
        String plain = "sk-deepseek-abc123-需要加密的APIKey";
        String cipher = aesUtil.encrypt(plain);
        assertNotNull(cipher);
        assertNotEquals(plain, cipher);

        String decrypted = aesUtil.decrypt(cipher);
        assertEquals(plain, decrypted);
    }

    @Test
    void encrypt_samePlainTwice_producesDifferentCiphers() throws Exception {
        //GCM 模式每次随机 IV，相同明文两次加密结果必须不同，否则泄露明文相等性
        String plain = "same-input";
        String c1 = aesUtil.encrypt(plain);
        String c2 = aesUtil.encrypt(plain);
        assertNotEquals(c1, c2, "IV 未随机化会导致密文可比较");
    }

    @Test
    void decrypt_tamperedCipher_rejectedByGcmTag() throws Exception {
        String cipher = aesUtil.encrypt("important-secret");
        byte[] bytes = Base64.getDecoder().decode(cipher);
        //翻转密文最后一个字节（在 GCM tag 范围内）
        bytes[bytes.length - 1] ^= 0x01;
        String tampered = Base64.getEncoder().encodeToString(bytes);

        //GCM 是认证加密，任何篡改都应导致 AEADBadTagException
        assertThrows(AEADBadTagException.class, () -> aesUtil.decrypt(tampered));
    }

    @Test
    void decrypt_invalidBase64_rejected() {
        assertThrows(Exception.class, () -> aesUtil.decrypt("!!!not-base64!!!"));
    }
}
