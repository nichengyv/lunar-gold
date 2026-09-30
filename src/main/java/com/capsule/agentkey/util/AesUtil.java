package com.capsule.agentkey.util;
//长数据加密（文件，api-key）
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

@Component
public class AesUtil {
    private static final String ALGORITHM = "AES/GCM/NoPadding";//AES 算法 + GCM 模式 + 无填充
    private static final int GCM_TAG_LENGTH = 128;//认证标签长度，解密时就能验证是否被篡改
    private static final int GCM_IV_LENGTH = 12;//初始化向量长度

    @Value("${app.crypto.master-key}")
    private String base64MasterKey;

    //加密
    public String encrypt(String plainText) throws Exception {
        byte[] iv = new byte[GCM_IV_LENGTH];//创建长度12字节的数组vi
        new SecureRandom().nextBytes(iv);//随机生成安全数填充到vi（每次每次调用encrypt加密，用全新的vi）

        //将base64字符串转换为java加密库能识别使用的AES密钥对象
        //getDecoder()获取解码器   decode将上一步获取的字符串进行解码   括号里的AES是声明该包装器用的就是AES算法
        //SecretKey是AES专用的包装容器
        SecretKey key = new SecretKeySpec(Base64.getDecoder().decode(base64MasterKey), "AES");

        //获取算法实例，指定AES-GCM算法
        Cipher cipher = Cipher.getInstance(ALGORITHM);

        //Cipher.ENCRYPT_MODE设置Cipher工作模式为加密        init初始化加密引擎
        //init三个参数：工作模式，密钥对象，参数规范对象（传入两种长度，前者规定tag长度，后者传入初始化向量），tag是加密结束后自动生成的
        //iv与key分工：key用于加密算法   iv被key加密成密钥流，再与对应块明文异或
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
        //doFinal执行，
        byte[] cipherText = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
        //把iv拼接到密文后
        //allocate开辟字节缓冲区，参数规定大小，大小固定
        ByteBuffer byteBuffer = ByteBuffer.allocate(iv.length + cipherText.length);
        byteBuffer.put(iv);
        byteBuffer.put(cipherText);
        //把拼接好的字节数组编码为 base64 并返回
        return Base64.getEncoder().encodeToString(byteBuffer.array());
    }

    public String decrypt(String cipherText) throws Exception {
        //解码base64为二进制字节数组
        byte[] decoded = Base64.getDecoder().decode(cipherText);
        //wrap包装字节数组进入bytebuffer对象
        ByteBuffer byteBuffer = ByteBuffer.wrap(decoded);
        //提取iv
        byte[] iv = new byte[GCM_IV_LENGTH];
        byteBuffer.get(iv);
        //remaining方法用于返回当前缓冲区还没读取或写入的元素数量
        byte[] encryptedBytes = new byte[byteBuffer.remaining()];
        //从当前position开始读取参数长度个元素，指针是自增的
        byteBuffer.get(encryptedBytes);

        SecretKey key = new SecretKeySpec(Base64.getDecoder().decode(base64MasterKey), "AES");

        Cipher cipher = Cipher.getInstance(ALGORITHM);
        //初始化，这里把算法实例设置为解密模式
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
        //传入密文+tag
        //输出原始明文字节，转UTF-8字符串并返回
        return new String(cipher.doFinal(encryptedBytes), StandardCharsets.UTF_8);
    }
}