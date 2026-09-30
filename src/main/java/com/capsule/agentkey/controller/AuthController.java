package com.capsule.agentkey.controller;

import com.capsule.agentkey.dto.ApiResponse;
import com.capsule.agentkey.dto.LoginRequest;
import com.capsule.agentkey.dto.RegisterRequest;
import com.capsule.agentkey.exception.BusinessException;
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

    public AuthController(UserService userService, JwtUtil jwtUtil, RsaUtil rsaUtil) {
        this.userService = userService;
        this.jwtUtil = jwtUtil;
        this.rsaUtil = rsaUtil;
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
        String realPassword = rsaUtil.decryptPassword(req.getEncryptedPassword());
        userService.register(req.getUsername(), realPassword, req.getEmail());
        return ApiResponse.success(null);
    }
    //登录
    @PostMapping("/login")
    //RequestBody注解，自动读取body，根据字段名，在自动把它转换为LoginRequest对象
    public ApiResponse<Map<String, String>> login(@Valid @RequestBody LoginRequest req) throws Exception {
        String realPassword = rsaUtil.decryptPassword(req.getEncryptedPassword());//解码
        var user = userService.findByUsername(req.getUsername())//var通过右边返回值来推断左边变量的类型
                .orElseThrow(() -> new BusinessException("用户不存在或密码错误"));//orElseThrow的作用：前面查找之后，有值则返回，无值则抛异常
        //realPassword就是解码出来的密码，后者是数据库存的密码哈希值，checkPassword就是拿明文和哈希对比
        if (!userService.checkPassword(realPassword, user.getPasswordHash())) {
            throw new BusinessException("用户不存在或密码错误");
        }
        String token = jwtUtil.generateToken(user.getId(), user.getUsername());//生成JWT令牌放进token字段
        return ApiResponse.success(Map.of("token", token));//把JWT令牌返回给前端，Map.of创建不可变的Map集合
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
        String oldPassword = rsaUtil.decryptPassword(oldEncrypted);
        String newPassword = rsaUtil.decryptPassword(newEncrypted);
        userService.changePassword(userId, oldPassword, newPassword);
        return ApiResponse.success(null);
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