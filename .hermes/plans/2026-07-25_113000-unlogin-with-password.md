# /api/auth/unlogin 增强方案——加入账号密码校验

> **For Hermes:** Use subagent-driven-development to implement.

**Goal:** 扩展 `POST /api/auth/unlogin` 支持 `{account, password, jwt}`，防止纯 JWT 绕过。

**Architecture:** 系统一传入用户凭据+JWT，当前系统校验三者一致（密码正确 + userId匹配）后签发 token。

---

## 变更点（相对于当前实现）

当前实现只接收 `{jwt}`，直接信任 JWT。需改为：

### 1. UnloginRequest 扩展

```java
// AIStoryboardBackend/src/main/java/com/storyboard/dto/request/UnloginRequest.java
package com.storyboard.dto.request;

public record UnloginRequest(
    String account,   // email
    String password,
    String jwt        // 系统一的 accessToken
) {}
```

### 2. AuthService.handleUnlogin 重写

```java
// AuthService.java
public ApiResponse<Map<String, Object>> handleUnlogin(
        UnloginRequest req,
        String authHeader) {

    String userIdFromHeader = null;

    // 1. 检查请求头是否已有 token
    if (authHeader != null && authHeader.startsWith("Bearer ")) {
        try {
            userIdFromHeader = jwtTokenProvider.tokenToUserId(
                authHeader.substring(7)
            );
        } catch (Exception ignored) {}
    }

    // 2. 解析系统一传来的 JWT → userId_b
    String userIdFromJwt;
    try {
        userIdFromJwt = jwtTokenProvider.tokenToUserId(req.jwt());
    } catch (Exception e) {
        throw new BusinessException(40101, "JWT 无效");
    }

    // 3. 校验账号密码
    User user = userMapper.findByEmail(req.account());
    if (user == null) {
        throw new BusinessException(40101, "账号不存在");
    }
    if (!scryptPasswordService.verifyPassword(req.password(), user.getPasswordHash())) {
        throw new BusinessException(40101, "密码错误");
    }

    // 4. 校验账号的 userId 与 JWT 的 userId 一致
    if (!user.getId().equals(userIdFromJwt)) {
        throw new BusinessException(40101, "账号与JWT不匹配");
    }

    // 5. 如果已有 token 且是同一用户 → 无需重新登录
    if (userIdFromHeader != null && userIdFromHeader.equals(userIdFromJwt)) {
        return ApiResponse.ok(Map.of(
            "alreadyLoggedIn", true,
            "userId", user.getId(),
            "displayName", user.getDisplayName()
        ));
    }

    // 6. 签发新 token
    String accessToken = jwtTokenProvider.generateAccessToken(user.getId());
    String refreshToken = jwtTokenProvider.generateRefreshToken(user.getId());

    return ApiResponse.ok(Map.of(
        "accessToken", accessToken,
        "refreshToken", refreshToken,
        "userId", user.getId(),
        "displayName", user.getDisplayName()
    ));
}
```

### 3. AuthController 传递 auth header

```java
@PostMapping("/unlogin")
public ApiResponse<Map<String, Object>> unlogin(
        @RequestBody UnloginRequest req,
        @RequestHeader(value = "Authorization", required = false) String authHeader) {
    return authService.handleUnlogin(req, authHeader);
}
```

---

## 系统一调用方式更新

```javascript
// 系统一后端调用分镜系统时
const response = await axios.post('http://localhost:8082/api/auth/unlogin', {
  account: user.email,       // 用户的邮箱
  password: user.password,   // 用户的密码（系统一需持有或从输入获取）
  jwt: systemOneAccessToken  // 系统一的 JWT
});
```

**注意：** 系统一需要持有或能获取用户的明文密码。如果系统一不存明文密码，则：
- 用户首次跳转时弹窗输入密码
- 或者系统一存储加密后的密码（可逆），跳转时解密传递

---

## 验证

```bash
# 不带 token → 校验通过，下发新 token
curl -X POST http://localhost:8082/api/auth/unlogin \
  -H "Content-Type: application/json" \
  -d '{
    "account": "771038325@qq.com",
    "password": "WEIDK771038325@@",
    "jwt": "系统一的JWT"
  }'

# 预期: {code:200, data:{accessToken, refreshToken, userId, displayName}}

# 带已有 token 且同一用户 → alreadyLoggedIn
curl -X POST http://localhost:8082/api/auth/unlogin \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <已有token>" \
  -d '{
    "account": "771038325@qq.com",
    "password": "WEIDK771038325@@",
    "jwt": "系统一的JWT"
  }'

# 预期: {code:200, data:{alreadyLoggedIn:true, userId, displayName}}
```
