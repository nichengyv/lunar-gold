package com.capsule.agentkey.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.crypto.Cipher;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

@Component
public class RsaUtil {
    private static final Logger log = LoggerFactory.getLogger(RsaUtil.class);
    private final PrivateKey privateKey;
    private final PublicKey publicKey;

    public RsaUtil(@Value("${app.rsa.private-key:}") String privateKeyBase64,
                   @Value("${app.rsa.public-key:}") String publicKeyBase64) throws Exception {
        //拿钥匙
        //先判断两段密钥是否有为空的
        if (StringUtils.hasText(privateKeyBase64) && StringUtils.hasText(publicKeyBase64)) {
            //getInstance方法参数：算法名/算法名，提供者名
            //这里就是生成一个处理对应算法的密钥工厂工具（把文本字符串转换为可用的密钥对象）
            KeyFactory keyFactory = KeyFactory.getInstance("RSA");
            //Base64.getDecoder().decode(privateKeyBase64)：解码：将base64编码的字符串解码为原始的字节数组
            //generatePrivate：根据密钥规范生成私钥对象（解析字节）
            //PKCS8EncodedKeySpec私钥的标准格式包装器（包裹字节数组）
            this.privateKey = keyFactory.generatePrivate(
                    new PKCS8EncodedKeySpec(Base64.getDecoder().decode(privateKeyBase64)));
            this.publicKey = keyFactory.generatePublic(
                    new X509EncodedKeySpec(Base64.getDecoder().decode(publicKeyBase64)));
            log.info("✅ RSA 密钥已从配置加载 (PKCS1Padding)");
        }
        //当配置文件里的公私钥任一为空，则临时随机生成密钥
        else {
            //密钥生成器
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            //密钥长度为2048位
            generator.initialize(2048);

            //随机生成一对捆绑的公私钥
            KeyPair pair = generator.generateKeyPair();
            //分别提取
            this.privateKey = pair.getPrivate();
            this.publicKey = pair.getPublic();
            log.warn("⚠️ RSA 密钥未配置，已临时生成（仅开发环境使用）！");
            log.warn("请将以下环境变量配置到生产环境：");
            log.warn("export RSA_PUBLIC_KEY={}", Base64.getEncoder().encodeToString(publicKey.getEncoded()));
            log.warn("export RSA_PRIVATE_KEY=<请从安全渠道获取或自行生成>");
        }
    }

    //把内存里准备好的publickey对象导出二进制字节编码为base64字符串返回给前端
    //publicKey.getEncoded()获取公钥原始字节数组
    //Base64.getEncoder()获取Base64编码器
    //encodeToString执行转换
    public String getPublicKeyBase64() {
        return Base64.getEncoder().encodeToString(publicKey.getEncoded());
    }

    /**
     * 解密前端传来的密码（JSEncrypt 使用 PKCS1Padding）（前端加密用的是JS-Encrypt）
     */
    // Cipher.getInstance创建密码运算引擎，其参数就是加密填充方案     encryptedBase64是前端加密后的base64字符串
    //Rsa：使用的算法  ECB：分组模式（这里只是占位符）   PKCS1Padding：填充方式（加密前填充，解密后删去，因为rsa数学公式要求了数据长度）
    public String decryptPassword(String encryptedBase64) throws Exception {
        Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        //初始化cipher为解密模式，这里参数规定用私钥解密
        cipher.init(Cipher.DECRYPT_MODE, privateKey);
        //cipher.doFinal执行解密    StandardCharsets.UTF_8将字节数组转换为明文密码字符串，返回给AuthController
        return new String(cipher.doFinal(Base64.getDecoder().decode(encryptedBase64)), StandardCharsets.UTF_8);
    }

    //用公钥对一段明文进行RSA加密（后端想把数据加密发给前端）
    public String encryptWithPublicKey(String plainText) throws Exception {
        Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        //加密模式，用公钥
        cipher.init(Cipher.ENCRYPT_MODE, publicKey);
        //plainText.getBytes(StandardCharsets.UTF_8)把明文字符串转换为UTF-8编码的字节数组
        return Base64.getEncoder().encodeToString(cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8)));
    }
}