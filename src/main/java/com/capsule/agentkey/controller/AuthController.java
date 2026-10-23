package com.capsule.agentkey.controller;

import com.capsule.agentkey.dto.ApiResponse;
import com.capsule.agentkey.dto.LoginRequest;
import com.capsule.agentkey.dto.RegisterRequest;
import com.capsule.agentkey.exception.BusinessException;
import com.capsule.agentkey.exception.ErrorCode;
import com.capsule.agentkey.service.LoginAttemptService;
import com.capsule.agentkey.service.UserService;
import com.capsule.agentkey.util.JwtUtil;
import com.capsule.agentkey.util.RsaUtil;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@Slf4j
public class AuthController {

    private final UserService userService;
    private final JwtUtil jwtUtil;
    private final RsaUtil rsaUtil;
    private final LoginAttemptService loginAttemptService;
    private final com.capsule.agentkey.service.TokenSessionService tokenSessionService;

    public AuthController(UserService userService, JwtUtil jwtUtil, RsaUtil rsaUtil,
                          LoginAttemptService loginAttemptService,
                          com.capsule.agentkey.service.TokenSessionService tokenSessionService) {
        this.userService = userService;
        this.jwtUtil = jwtUtil;
        this.rsaUtil = rsaUtil;
        this.loginAttemptService = loginAttemptService;
        this.tokenSessionService = tokenSessionService;
    }

    //接口1：获取公钥
    @GetMapping("/public-key")
    public ApiResponse<Map<String, String>> getPublicKey() {
        return ApiResponse.success(Map.of("publicKey", rsaUtil.getPublicKeyBase64()));
    }

    //接口2：注册
    @PostMapping("/register")
    public ApiResponse<Void> register(@Valid @RequestBody RegisterRequest req) throws Exception {
        //解密
        String realPassword = decryptPassword(req.getEncryptedPassword(), req.getUsername());
        userService.register(req.getUsername(), realPassword, req.getEmail());
        return ApiResponse.success(null);
    }
    //登录
    @PostMapping("/login")
    //RequestBody注解，自动读取body，根据字段名，在自动把它转换为LoginRequest对象
    //valid搭配实体类里的notblank验证对象字段是否为空     @RequestBody读取http请求体里的json字符串，自动反序列化为java对象
    public ApiResponse<Map<String, String>> login(@Valid @RequestBody LoginRequest req) throws Exception {
        //A7 防爆破：连续失败 5 次锁定 15 分钟，锁定期间直接拒绝，不校验密码
        if (loginAttemptService.isLocked(req.getUsername())) {
            throw new BusinessException(ErrorCode.RATE_LIMITED, "登录尝试次数过多，请15分钟后再试");
        }
        String realPassword = decryptPassword(req.getEncryptedPassword(), req.getUsername());//解码
        var user = userService.findByUsername(req.getUsername())//var通过右边返回值来推断左边变量的类型
                .orElseThrow(() -> new BusinessException("用户不存在或密码错误"));//orElseThrow的作用：前面查找之后，有值则返回，无值则抛异常
        //realPassword就是解码出来的密码，后者是数据库存的密码哈希值，checkPassword就是拿明文和哈希对比
        if (!userService.checkPassword(realPassword, user.getPasswordHash())) {
            loginAttemptService.onFailure(req.getUsername());//失败计数，达 5 次触发锁定
            throw new BusinessException("用户不存在或密码错误");
        }
        loginAttemptService.onSuccess(req.getUsername());//成功即清空失败记录
        String token = jwtUtil.generateToken(user.getId(), user.getUsername());//生成JWT令牌放进token字段
        //登记 jti 到 Redis 白名单，使本 token 可被后续 logout / 改密码主动撤销
        tokenSessionService.register(user.getId(), jwtUtil.getJtiFromToken(token), jwtUtil.getExpirationMillis());
        return ApiResponse.success(Map.of("token", token));//把JWT令牌返回给前端，Map.of创建不可变的Map集合
    }

    /**
     * 登出：撤销当前 token（从 SecurityContext.details 取 jti，无需重新解析 Authorization 头）
     */
    @PostMapping("/logout")
    public ApiResponse<Void> logout() {
        Long userId = getCurrentUserId();
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String jti = auth != null && auth.getDetails() instanceof String ? (String) auth.getDetails() : null;
        if (jti != null) {
            tokenSessionService.revoke(userId, jti);
        }
        return ApiResponse.success(null);
    }

    /**
     * 获取当前登录用户信息
     */
    @GetMapping("/me")
    public ApiResponse<Map<String, Object>> getCurrentUser() {
        Long userId = getCurrentUserId();
        var user = userService.findById(userId)
                .orElseThrow(() -> new BusinessException("用户不存在"));
        Map<String, Object> map = new HashMap<>();
        map.put("id", user.getId());
        map.put("username", user.getUsername());
        map.put("email", user.getEmail());
        map.put("createTime", user.getCreateTime());
        return ApiResponse.success(map);
    }

    /**
     * 修改密码
     */
    @PostMapping("/change-password")
    public ApiResponse<Void> changePassword(@RequestBody Map<String, String> body) throws Exception {
        Long userId = getCurrentUserId();
        String oldEncrypted = body.get("oldPassword");
        String newEncrypted = body.get("newPassword");
        if (oldEncrypted == null || newEncrypted == null) {
            throw new BusinessException("原密码和新密码不能为空");
        }
        String oldPassword = decryptPassword(oldEncrypted, "userId=" + userId);
        String newPassword = decryptPassword(newEncrypted, "userId=" + userId);
        userService.changePassword(userId, oldPassword, newPassword);
        //改密码即视为安全风险事件，撤销该用户所有 token（包括当前会话），强制全端重新登录
        tokenSessionService.revokeAll(userId);
        return ApiResponse.success(null);
    }

    /**
     * 解密前端 RSA 密文，失败统一转成 400 参数错误。
     *
     * 原因：RsaUtil.decryptPassword 对非法 Base64 / 密钥不匹配会直接抛异常。
     * 此前没人接住，异常一路冒到 GlobalExceptionHandler 的兜底分支，结果是：
     * 客户端只是传错了格式，却收到 500「系统繁忙」，既歪曲了语义，
     * 也把本可预期的输入问题混进了服务端故障告警里。
     *
     * 日志只记账号标识与异常摘要，不记录密文本身（可能对应真实密码）。
     *
     * @param encrypted 前端用公钥加密后的 Base64 密文
     * @param accountLabel 日志用的账号标识（用户名或 userId）
     */
    private String decryptPassword(String encrypted, String accountLabel) {
        try {
            return rsaUtil.decryptPassword(encrypted);
        } catch (Exception e) {
            log.warn("密码密文解密失败 [{}]: {}", accountLabel, e.getMessage());
            throw new BusinessException(ErrorCode.PARAM_INVALID, "密码格式不合法，请刷新页面后重试");
        }
    }

    private Long getCurrentUserId() {
        //SecurityContextHolder存放本次登录信息的容器
        //getContext()拿到请求上下文    getAuthentication()拿到认证对象
        //上下文的数据是被JwtAuthenticationFilter放进去的
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        //instanceof Long判断取出的数据的类型是不是Long
        //getPrincipal()：身份主体，这里存的是userid
        if (auth != null && auth.getPrincipal() instanceof Long) {
            return (Long) auth.getPrincipal();
        }
        throw new BusinessException("用户未认证");
    }
}