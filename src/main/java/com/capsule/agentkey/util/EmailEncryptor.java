package com.capsule.agentkey.util;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * JPA 字段加密转换器：自动加密/解密数据库中的邮箱字段
 *
 * 使用方式：
 * @Convert(converter = EmailEncryptor.class)
 * private String email;
 */
@Component
@Converter(autoApply = false)
public class EmailEncryptor implements AttributeConverter<String, String> {

    private static AesUtil aesUtil;

    @Autowired
    public void setAesUtil(AesUtil util) {
        EmailEncryptor.aesUtil = util;
    }

    @Override
    public String convertToDatabaseColumn(String attribute) {
        if (attribute == null || aesUtil == null) return attribute;
        try {
            return aesUtil.encrypt(attribute);
        } catch (Exception e) {
            throw new RuntimeException("邮箱加密失败", e);
        }
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        if (dbData == null || aesUtil == null) return dbData;
        try {
            return aesUtil.decrypt(dbData);
        } catch (Exception e) {
            // 如果解密失败，可能是旧数据未加密，直接返回原值
            return dbData;
        }
    }
}