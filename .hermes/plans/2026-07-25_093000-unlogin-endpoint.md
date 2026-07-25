# 跨系统登录校验接口 /api/auth/unlogin 实现计划

> **For Hermes:** Use subagent-driven-development skill to implement this plan task-by-task.

**Goal:** 新增 `POST /api/auth/unlogin` 端点，接收系统一的 JWT + 账号密码，校验后自动登录或拒绝。

**Architecture:** 在 AuthController 新增端点，Service 层实现校验逻辑。JWT 解析复用现有 JwtTokenProvider，用户查询复用 UserMapper。

**Tech Stack:** Spring Boot 4, MyBatis-Plus, jjwt, BouncyCastle scrypt

---

### 上下文

- 系统一（Node.js）和当前系统共享 `public.users` 表
- 两套系统共用同一套 JWT 密钥和签名算法（HS256，access-secret）
- 当前系统已实现：`JwtTokenProvider.tokenToUserId(token)` 解析 JWT，`ScryptPasswordService.verifyPassword()`

---

### 任务 1：创建 UnloginRequest DTO

**Files:**
- Create: `AIStoryboardBackend/src/main/java/com/storyboard/dto/request/UnloginRequest.java`

```java
package com.storyboard.dto.request;

public record UnloginRequest(
    String account,   // email
    String password,
    String jwt        // 系统一传来的 jwt
) {}
```

---

### 任务 2：新增 UnloginResponse DTO

**Files:**
- Create: `AIStoryboardBackend/src/main/java/com/storyboard/dto/response/UnloginResponse.java`

```java
package com.storyboard.dto.response;

public record UnloginResponse(
    String accessToken,
    String refreshToken,
    String userId,
    String displayName,
    boolean alreadyLoggedIn
) {}
```

---

### 任务 3：AuthController 新增 /unlogin 端点

**Files:**
- Modify: `AIStoryboardBackend/src/main/java/com/storyboard/controller/AuthController.java`

在 AuthController 中注入 `JwtTokenProvider`：

```java
@PostMapping("/unlogin")
public ResponseEntity<?> unlogin(@RequestBody UnloginRequest req,
                                  @RequestHeader(value = "Authorization", required = false) String authHeader) {
    return authService.handleUnlogin(req, authHeader);
}
```

---

### 任务 4：AuthService 实现 handleUnlogin 逻辑

**Files:**
- Modify: `AIStoryboardBackend/src/main/java/com/storyboard/service/AuthService.java`

注入 `JwtTokenProvider jwtTokenProvider`。

```java
public ResponseEntity<?> handleUnlogin(UnloginRequest req, String authHeader) {
    // 1. 解析请求体中的 JWT → userId_b
    String userIdFromBody;
    try {
        userIdFromBody = jwtTokenProvider.tokenToUserId(req.jwt());
    } catch (Exception e) {
        return ResponseEntity.status(401).body(Map.of("error", "无效的JWT"));
    }

    // 2. 检查请求头是否有 token
    String headerToken = null;
    if (authHeader != null && authHeader.startsWith("Bearer ")) {
        headerToken = authHeader.substring(7);
    }

    // 3. 查询用户
    User user = userMapper.findByEmail(req.account());
    if (user == null) {
        return ResponseEntity.status(401).body(Map.of("error", "登录校验错误"));
    }

    // 4. 校验密码
    if (!scryptPasswordService.verifyPassword(req.password(), user.getPasswordHash())) {
        return ResponseEntity.status(401).body(Map.of("error", "登录校验错误"));
    }

    // 5. 校验账号的 userId 与 JWT 的 userId 是否一致
    if (!user.getId().equals(userIdFromBody)) {
        return ResponseEntity.status(401).body(Map.of("error", "登录校验错误"));
    }

    // 6. 已有 token 的情况：检查是否同一用户
    if (headerToken != null) {
        try {
            String userIdFromHeader = jwtTokenProvider.tokenToUserId(headerToken);
            if (userIdFromHeader.equals(userIdFromBody)) {
                // 同一用户，已是登录状态
                return ResponseEntity.ok(Map.of(
                    "alreadyLoggedIn", true,
                    "userId", user.getId(),
                    "displayName", user.getDisplayName()
                ));
            }
        } catch (Exception ignored) {
            // header token 无效，忽略，继续下发新 token
        }
    }

    // 7. 下发新 JWT
    String accessToken = jwtTokenProvider.generateAccessToken(user.getId());
    String refreshToken = jwtTokenProvider.generateRefreshToken(user.getId());

    return ResponseEntity.ok(Map.of(
        "accessToken", accessToken,
        "refreshToken", refreshToken,
        "userId", user.getId(),
        "displayName", user.getDisplayName(),
        "alreadyLoggedIn", false
    ));
}
```

**注意**：需要检查 `JwtTokenProvider` 是否有 `tokenToUserId(String token)` 方法，没有则新增。

---

### 任务 5：JwtTokenProvider 新增 tokenToUserId 方法（如无）

**Files:**
- Modify: `AIStoryboardBackend/src/main/java/com/storyboard/config/JwtTokenProvider.java`

```java
public String tokenToUserId(String token) {
    return Jwts.parser()
        .verifyWith(accessKey)
        .build()
        .parseSignedClaims(token)
        .getPayload()
        .getSubject();
}
```

---

### 任务 6：SecurityConfig 放行 /api/auth/unlogin

**Files:**
- Modify: `AIStoryboardBackend/src/main/java/com/storyboard/config/SecurityConfig.java`

在 `.requestMatchers()` 列表中加入 `"/api/auth/**"`（如果已有则跳过）。

---

### 验证

```bash
mvn compile  # BUILD SUCCESS
mvn test     # 24/24 PASS
```

API 测试：
```bash
# 无 token 头 → 校验通过，下发新 token
curl -X POST http://localhost:8082/api/auth/unlogin \
  -H "Content-Type: application/json" \
  -d '{"account":"771038325@qq.com","password":"WEIDK771038325@@","jwt":"系统一的jwt"}'

# 已有 token 头且同一用户 → alreadyLoggedIn=true
curl -X POST http://localhost:8082/api/auth/unlogin \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <existing-token>" \
  -d '{"account":"771038325@qq.com","password":"WEIDK771038325@@","jwt":"系统一的jwt"}'
```

---

### 提交

```
git add -A && git commit -m "feat: add /api/auth/unlogin cross-system login validation endpoint"
```
