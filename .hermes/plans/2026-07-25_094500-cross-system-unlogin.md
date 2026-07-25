# 跨系统无状态登录方案

## 一、当前系统（Spring Boot）

### 新增 `/api/auth/unlogin` 端点

`POST /api/auth/unlogin`

**请求体：**
```json
{
  "jwt": "系统一的accessToken"
}
```

**响应成功：**
```json
{
  "code": 200,
  "message": "success",
  "data": {
    "accessToken": "eyJ...",
    "refreshToken": "eyJ...",
    "userId": "abc123",
    "displayName": "魏辰益"
  }
}
```

**响应失败：**
```json
{
  "code": 40101,
  "message": "登录校验错误"
}
```

**处理逻辑：**
1. 解析 `jwt` → 获取 userId
2. `userMapper.selectById(userId)` → 确认用户存在且 status='enabled'
3. 用当前系统的 access-secret 签发新的 accessToken + refreshToken
4. 返回给前端

**实现步骤：**

### 任务 1：创建 UnloginRequest DTO

- Create: `AIStoryboardBackend/src/main/java/com/storyboard/dto/request/UnloginRequest.java`

```java
package com.storyboard.dto.request;

public record UnloginRequest(String jwt) {}
```

### 任务 2：AuthController 新增端点

- Modify: `AIStoryboardBackend/src/main/java/com/storyboard/controller/AuthController.java`

注入 `JwtTokenProvider`，新增方法：

```java
@PostMapping("/unlogin")
public ApiResponse<Map<String, Object>> unlogin(@RequestBody UnloginRequest req) {
    return authService.handleUnlogin(req);
}
```

### 任务 3：AuthService 实现 handleUnlogin

- Modify: `AIStoryboardBackend/src/main/java/com/storyboard/service/AuthService.java`

注入 `JwtTokenProvider`：

```java
public ApiResponse<Map<String, Object>> handleUnlogin(UnloginRequest req) {
    // 1. 解析系统一的 JWT（用系统一的密钥）
    String userId = jwtTokenProvider.tokenToUserId(req.jwt());

    // 2. 查询用户
    User user = userMapper.selectById(userId);
    if (user == null || !"enabled".equals(user.getStatus())) {
        throw new BusinessException(40101, "登录校验错误");
    }

    // 3. 签发当前系统的 JWT（用当前系统的密钥）
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

### 任务 4：JwtTokenProvider 新增 tokenToUserId

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

### 验证

```bash
mvn compile  # BUILD SUCCESS
```

---

## 二、系统一（Node.js）调用方案

系统一需要在跳转前调用当前系统的 `/unlogin` 端点完成"JWT 交换"。

### 方案 A：服务端代理（推荐）

系统一后端新增一个接口，代理调用当前系统：

```javascript
// routes/storyboard.js
const express = require('express');
const axios = require('axios');
const router = express.Router();

router.post('/storyboard-login', async (req, res) => {
  try {
    // 从请求头取当前用户的 JWT
    const userJwt = req.headers.authorization?.replace('Bearer ', '');

    // 调用分镜系统
    const response = await axios.post('http://localhost:8082/api/auth/unlogin',
      { jwt: userJwt },
      { timeout: 5000 }
    );

    // 返回新 token 给前端
    res.json(response.data);
  } catch (err) {
    res.status(401).json({ error: '登录校验错误' });
  }
});

module.exports = router;
```

**系统一前端调用：**

```javascript
// 点击"跳转分镜系统"
async function goToStoryboard() {
  try {
    const res = await fetch('/storyboard-login', { method: 'POST' });
    const data = await res.json();

    if (data.code === 200) {
      const { accessToken, refreshToken, userId, displayName } = data.data;

      // 方案 A-1：新标签页打开，用 URL 参数传 token
      window.open(
        `http://localhost:5173/?token=${accessToken}&refresh=${refreshToken}&userId=${userId}&name=${encodeURIComponent(displayName)}`
      );
    } else {
      alert('登录校验失败');
    }
  } catch {
    alert('网络错误');
  }
}
```

### 方案 B：前端直连（CORS 配置支持）

如果不想走系统一后端代理，系统一前端直接调当前系统：

```javascript
async function goToStoryboard() {
  const jwt = localStorage.getItem('accessToken'); // 系统一的 token

  const res = await fetch('http://localhost:8082/api/auth/unlogin', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ jwt })
  });

  const data = await res.json();
  if (data.code === 200) {
    const { accessToken, refreshToken, userId, displayName } = data.data;
    // 新标签页打开，URL 传参
    window.open(
      `http://localhost:5173/?token=${accessToken}&refresh=${refreshToken}&userId=${userId}&name=${encodeURIComponent(displayName)}`
    );
  }
}
```

### 当前系统前端接收 token

在 `EditorPage.tsx` 的 useEffect 中：

```tsx
useEffect(() => {
  const params = new URLSearchParams(window.location.search);
  const token = params.get('token');
  const refresh = params.get('refresh');
  const userId = params.get('userId');
  const name = params.get('name');

  if (token) {
    localStorage.setItem('accessToken', token);
    if (refresh) localStorage.setItem('refreshToken', refresh);
    if (userId && name) localStorage.setItem('user', JSON.stringify({ userId, displayName: name }));
    // 清除 URL 参数
    window.history.replaceState({}, '', '/');
    // 设置登录状态
    useAuthStore.getState().checkAuth();
  }
}, []);
```

---

### 总结

| 角色 | 要做的事 |
|------|---------|
| **当前系统后端** | 新增 `/api/auth/unlogin`，接收 `{jwt}` → 验签 → 查用户 → 签发新 token |
| **系统一后端** | 新增代理路由 `/storyboard-login`，调用当前系统 |
| **系统一前端** | 点击跳转 → POST `/storyboard-login` → 拿到新 token → 新标签页带参数跳转 |
| **当前系统前端** | URL 参数提取 token → 存 localStorage → 自动登录 |
