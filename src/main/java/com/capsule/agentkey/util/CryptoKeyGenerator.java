package com.capsule.agentkey.util;

import javax.crypto.KeyGenerator;//对称密钥生成器
import javax.crypto.SecretKey;//对称密钥对象
import java.security.*;//通用安全基础类
import java.util.Base64;//Base64编码工具

/**
 * 加密密钥生成工具
 * 运行 main 方法生成生产环境所需的所有密钥
 */
public class CryptoKeyGenerator {

    public static void main(String[] args) throws Exception {
        System.out.println("========================================");
        System.out.println("  AgentKey 生产环境密钥生成器");
        System.out.println("========================================\n");

        // 1. AES-256 Master Key (用于加密 API Key)
        System.out.println("【1】AES-256 Master Key (API Key 加密)");
        System.out.println("    用途: 加密用户存储的 DeepSeek/OpenAI API Key");
        System.out.println("    配置项: app.crypto.master-key");
        KeyGenerator aesGen = KeyGenerator.getInstance("AES");
        aesGen.init(256);
        SecretKey aesKey = aesGen.generateKey();
        String aesBase64 = Base64.getEncoder().encodeToString(aesKey.getEncoded());
        System.out.println("    值: " + aesBase64);
        System.out.println("    环境变量: export AES_MASTER_KEY=" + aesBase64 + "\n");

        // 2. JWT Secret (至少 256-bit / 32 字节)
        System.out.println("【2】JWT HMAC-SHA256 Secret");
        System.out.println("    用途: 签名 JWT Token");
        System.out.println("    配置项: app.jwt.secret");
        byte[] jwtBytes = new byte[32];
        new SecureRandom().nextBytes(jwtBytes);
        String jwtSecret = Base64.getEncoder().encodeToString(jwtBytes);
        System.out.println("    值: " + jwtSecret);
        System.out.println("    环境变量: export JWT_SECRET=" + jwtSecret + "\n");

        // 3. RSA-2048 密钥对 (用于登录密码传输加密)
        //生成Base64手动放进配置文件，供RsaUtil类读取
        System.out.println("【3】RSA-2048 密钥对 (登录密码传输加密)");
        System.out.println("    用途: 前端 RSA 加密密码，后端私钥解密");
        System.out.println("    配置项: app.rsa.private-key / app.rsa.public-key");

        //创建一个RSA密钥对生成器工具
        KeyPairGenerator rsaGen = KeyPairGenerator.getInstance("RSA");
        //initalize参数规定密钥长度和随机源
        rsaGen.initialize(2048, new SecureRandom());
        //真正开始数学运算：随机生成一对绑定好的公私钥对象
        KeyPair pair = rsaGen.generateKeyPair();
        //getPrivate()取出私钥
        //getEncoded()导出原始二进制数组（也就是密钥本体）
        //Base64.getEncoder().encodeToString把二进制字节编码成一长串可读的Base64字符串
        String privateKey = Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded());
        String publicKey = Base64.getEncoder().encodeToString(pair.getPublic().getEncoded());
        System.out.println("    私钥 (PRIVATE): " + privateKey);
        System.out.println("    公钥 (PUBLIC):  " + publicKey);
        System.out.println("    环境变量:");
        System.out.println("    export RSA_PRIVATE_KEY=" + privateKey);
        System.out.println("    export RSA_PUBLIC_KEY=" + publicKey + "\n");

        // 4. 测试用户密码 (BCrypt)
        System.out.println("【4】测试用户密码 (BCrypt)");
        System.out.println("    用途: 替换 V1__init.sql 中的测试用户密码");
        System.out.println("    默认密码: Test@1234");
        String bcryptHash = new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode("Test@1234");
        System.out.println("    哈希: " + bcryptHash + "\n");

        System.out.println("========================================");
        System.out.println("  请将上述环境变量配置到服务器或 .env 文件");
        System.out.println("  切勿将这些密钥提交到 Git！");
        System.out.println("========================================");
    }
}