package com.capsule.agentkey.util;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.util.Date;

//JWT 编解码：签发、解 jti、解 userId、验签、验过期
@Component
public class JwtUtil {
    //从配置里获取JWT签名密钥，密钥本身不参与解密，只做防伪校验，JWT载荷本身是BASE64
    @Value("${app.jwt.secret}")
    private String secret;
    //读取token有效时长
    @Value("${app.jwt.expiration}")
    private Long expiration;

    //把配置文件里的密钥字符串，转换为JJWT库可用来签名，验签的加密密钥对象
    private SecretKey getKey() {
        return Keys.hmacShaKeyFor(secret.getBytes());
    }

    //生成令牌token（登录时调用）
    public String generateToken(Long userId, String username) {
        return Jwts.builder()//开始构建JWT令牌
                .setSubject(username)//存放用户名
                .setId(java.util.UUID.randomUUID().toString())//jti：本次会话唯一编号，撤销白名单依赖它
                .claim("userId", userId)//自定义载荷数据
                .setIssuedAt(new Date())//签发时间（什么时候生成令牌）
                .setExpiration(new Date(System.currentTimeMillis() + expiration))//过期时间（当前时间+有效时间）
                .signWith(getKey())//用密钥生成方位签名（防止令牌被篡改）
                .compact();//把三部分（头部，载荷，签名）用“.”拼接成一长串的JWT字符串，返回给前端
    }

    //校验并解析令牌
    public Claims parseToken(String token) {
        //返回JwtParserBuilder对象，构建一个JWT解析对象
        return Jwts.parserBuilder()

                //获取配置里的签名密钥，告诉解析器必须使用这个密钥
                .setSigningKey(getKey())

                //使用上面配置好的密钥，生成真正可用的解析器对象JwtParser
                .build()

                //传入前端发来的token，拆开三部分，校验签名，判断是否过期（有问题则抛异常），校验成功后返回对象Jws<Claims>（里面包括头部信息，载荷Claims，签名）
                .parseClaimsJws(token)

                //从Jws<Claims>对象里取出载荷部分（也就是name和id那些数据）
                .getBody();

    }//校验通过后，取出载荷里面存放的所有数据，返回claims对象

    //封存快捷方法，先解析token，再取出载荷里存的userid，返回给程序用
    public Long getUserIdFromToken(String token) {
        return parseToken(token).get("userId", Long.class);
    }

    //取 jti（JWT ID），撤销白名单的键；老 token（无 jti）返回 null
    public String getJtiFromToken(String token) {
        return parseToken(token).getId();
    }

    //返回 JWT 配置的过期毫秒数，用于把 jti 写入 Redis 时对齐 TTL
    public long getExpirationMillis() {
        return expiration;
    }
}
