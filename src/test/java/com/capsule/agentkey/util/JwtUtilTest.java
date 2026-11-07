package com.capsule.agentkey.util;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JwtUtil 纯单元测试：不启 Spring 上下文，用反射注入 @Value 字段。
 * 覆盖：生成/解析、userId 提取、jti 生成（撤销白名单依赖）、过期拒绝、签名篡改拒绝。
 */
class JwtUtilTest {

    private JwtUtil jwtUtil;

    @BeforeEach
    void setUp() {
        jwtUtil = new JwtUtil();
        //至少 32 字节，否则 HS256 密钥强度不足
        ReflectionTestUtils.setField(jwtUtil, "secret",
                "test-secret-key-01234567890123456789012345678901");
        ReflectionTestUtils.setField(jwtUtil, "expiration", 60_000L);//1 分钟，够测试用
    }

    @Test
    void generateAndParse_roundTrip() {
        String token = jwtUtil.generateToken(42L, "alice");
        assertNotNull(token);

        Claims claims = jwtUtil.parseToken(token);
        assertEquals("alice", claims.getSubject());
        assertEquals(42L, claims.get("userId", Long.class));
        assertNotNull(claims.getIssuedAt());
        assertNotNull(claims.getExpiration());
    }

    @Test
    void getUserIdFromToken_extractsUserId() {
        String token = jwtUtil.generateToken(7L, "bob");
        assertEquals(7L, jwtUtil.getUserIdFromToken(token));
    }

    @Test
    void generateToken_includesJti_forRevocationWhitelist() {
        //可撤销机制依赖 jti：缺失 jti 的老 token 会被过滤器拒绝
        String token1 = jwtUtil.generateToken(1L, "u");
        String token2 = jwtUtil.generateToken(1L, "u");

        String jti1 = jwtUtil.getJtiFromToken(token1);
        String jti2 = jwtUtil.getJtiFromToken(token2);
        assertNotNull(jti1, "token 必须携带 jti，否则无法加入 Redis 白名单");
        assertNotNull(jti2);
        assertNotEquals(jti1, jti2, "每次签发的 jti 必须唯一，否则登出会误伤其他会话");
    }

    @Test
    void parseToken_expired_throwsExpiredJwtException() throws Exception {
        ReflectionTestUtils.setField(jwtUtil, "expiration", -1000L);//已过期
        String token = jwtUtil.generateToken(1L, "u");
        //签发后立即过期
        assertThrows(ExpiredJwtException.class, () -> jwtUtil.parseToken(token));
    }

    @Test
    void parseToken_tamperedSignature_rejected() {
        String token = jwtUtil.generateToken(1L, "u");
        //篡改最后一段签名
        String tampered = token.substring(0, token.length() - 2) + "XX";
        assertThrows(Exception.class, () -> jwtUtil.parseToken(tampered));
    }
}
