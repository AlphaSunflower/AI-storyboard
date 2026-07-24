# Java/SpringBoot 项目对接 Node.js 项目 JWT 认证方案

## 概述

Node.js 前端项目（newworkflow2）与 Java/SpringBoot 后端项目共享同一个 PostgreSQL 数据库。Java 项目需要能够：

1. 验证 Node.js 项目签发的 JWT Token
2. 使用相同的密码哈希算法验证用户登录
3. 独立签发兼容的 JWT Token

---

## 一、数据库共享表结构

### users 表

```sql
create table if not exists users (
  id text primary key default gen_random_uuid()::text,
  email text not null unique,
  password_hash text not null,
  display_name text not null,
  role text not null default 'member',
  status text not null default 'enabled',
  last_login_at timestamptz null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint chk_users_role check (role in ('member', 'admin')),
  constraint chk_users_status check (status in ('enabled', 'disabled'))
);
```

| 列名 | 类型 | 说明 |
|------|------|------|
| `id` | `text` (PK) | UUID 字符串，自动生成 |
| `email` | `text` (UNIQUE) | 存储时已 trim + lowercase |
| `password_hash` | `text` | 格式: `scrypt:{salt_hex}:{derived_key_hex}` |
| `display_name` | `text` | 最长 80 字符（应用层校验）|
| `role` | `text` | `'member'` 或 `'admin'` |
| `status` | `text` | `'enabled'` 或 `'disabled'` |
| `last_login_at` | `timestamptz` | 每次登录时更新 |
| `created_at` | `timestamptz` | 创建时间 |
| `updated_at` | `timestamptz` | 更新时间 |

### refresh_tokens 表

```sql
create table if not exists refresh_tokens (
  id text primary key default gen_random_uuid()::text,
  user_id text not null references users(id),
  token_hash text not null unique,
  status text not null default 'active',
  issued_at timestamptz not null default now(),
  expires_at timestamptz not null,
  rotated_from_id text null references refresh_tokens(id),
  revoked_at timestamptz null,
  revoked_reason text null,
  user_agent text null,
  ip_address text null,
  constraint chk_refresh_tokens_status check (status in ('active', 'rotated', 'revoked', 'expired'))
);
```

---

## 二、密码哈希规范

### 算法：Node.js 内置 `crypto.scrypt`

| 参数 | 值 |
|------|-----|
| 算法 | scrypt |
| Salt | 16 字节随机数，hex 编码（32 个 hex 字符）|
| Key length | 64 字节，hex 编码（128 个 hex 字符）|
| 存储格式 | `scrypt:{salt_hex}:{derived_key_hex}` |

Node.js 的 `scrypt(password, salt, keylen)` 使用默认参数：

- **N** = 16384 (2^14)
- **r** = 8
- **p** = 1

### Java 实现

推荐使用 **Bouncy Castle** 的 scrypt 实现：

**Maven 依赖：**

```xml
<dependency>
    <groupId>org.bouncycastle</groupId>
    <artifactId>bcprov-jdk18on</artifactId>
    <version>1.78</version>
</dependency>
```

**密码服务类：**

```java
import org.bouncycastle.crypto.generators.SCrypt;
import java.security.MessageDigest;
import java.security.SecureRandom;

public class ScryptPasswordService {

    private static final String SCRYPT_PREFIX = "scrypt";
    private static final int SALT_BYTES = 16;
    private static final int KEY_LENGTH = 64;
    private static final int SCRYPT_N = 16384;  // 2^14，与 Node.js 默认一致
    private static final int SCRYPT_R = 8;
    private static final int SCRYPT_P = 1;

    /**
     * 验证密码是否匹配存储的哈希
     *
     * @param password   用户输入的明文密码
     * @param storedHash 数据库中的 password_hash，格式: scrypt:{salt_hex}:{derived_hex}
     * @return 是否匹配
     */
    public boolean verifyPassword(String password, String storedHash) {
        try {
            String[] parts = storedHash.split(":");
            if (parts.length != 3 || !SCRYPT_PREFIX.equals(parts[0])) {
                return false;
            }

            byte[] salt = hexToBytes(parts[1]);
            byte[] expectedKey = hexToBytes(parts[2]);

            byte[] derivedKey = SCrypt.generate(
                password.getBytes("UTF-8"), salt,
                SCRYPT_N, SCRYPT_R, SCRYPT_P, KEY_LENGTH
            );

            // 恒定时间比较，防止时序攻击
            return MessageDigest.isEqual(derivedKey, expectedKey);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 创建密码哈希（Java 项目自行注册用户时使用）
     */
    public String hashPassword(String password) throws Exception {
        byte[] salt = new byte[SALT_BYTES];
        SecureRandom.getInstanceStrong().nextBytes(salt);

        byte[] derived = SCrypt.generate(
            password.getBytes("UTF-8"), salt,
            SCRYPT_N, SCRYPT_R, SCRYPT_P, KEY_LENGTH
        );

        return SCRYPT_PREFIX + ":" + bytesToHex(salt) + ":" + bytesToHex(derived);
    }

    private static byte[] hexToBytes(String hex) {
        int len = hex.length();
        byte[] bytes = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            bytes[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                + Character.digit(hex.charAt(i + 1), 16));
        }
        return bytes;
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
```

> **关键**：N、r、p 和 keylen 必须与 Node.js 完全一致，否则无法验证已有密码。

---

## 三、JWT Token 规范

### 算法：HMAC-SHA256 (HS256)

此项目**未使用** `jsonwebtoken` 等第三方库，是手写的 JWT 实现。标准的 `jjwt` 库即可兼容。

### 签名密钥

| 配置项 | 用途 |
|--------|------|
| `accessTokenSecret` | AccessToken 签名密钥 |
| `refreshTokenSecret` | RefreshToken 签名密钥 |
| `issuer` | JWT `iss` 字段，默认 `"newworkflow-backend"` |

> **重要**：两个 token 使用**独立的密钥**，互不通用。

### AccessToken Payload

```json
{
  "typ": "access",
  "iss": "newworkflow-backend",
  "sub": "用户UUID",
  "role": "member",
  "status": "enabled",
  "iat": 1753351200,
  "exp": 1753354800,
  "jti": "随机UUID"
}
```

### RefreshToken Payload

```json
{
  "typ": "refresh",
  "iss": "newworkflow-backend",
  "sub": "用户UUID",
  "iat": 1753351200,
  "exp": 1753610400,
  "jti": "随机UUID"
}
```

> **注意**：RefreshToken payload **不包含** `role` 和 `status`，刷新时从数据库重新读取。

### Token 有效期

| Token | TTL |
|-------|-----|
| AccessToken | 3600 秒（1 小时）|
| RefreshToken | 2592000 秒（30 天）|

---

## 四、Java 签发兼容 JWT

### Maven 依赖

```xml
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-api</artifactId>
    <version>0.12.6</version>
</dependency>
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-impl</artifactId>
    <version>0.12.6</version>
    <scope>runtime</scope>
</dependency>
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-jackson</artifactId>
    <version>0.12.6</version>
    <scope>runtime</scope>
</dependency>
```

### Token 签发工具类

```java
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.util.Date;
import java.util.UUID;

public class JwtTokenProvider {

    private final SecretKey accessKey;
    private final SecretKey refreshKey;
    private final String issuer;

    public JwtTokenProvider(String accessSecret, String refreshSecret, String issuer) {
        this.accessKey = new SecretKeySpec(accessSecret.getBytes(), "HmacSHA256");
        this.refreshKey = new SecretKeySpec(refreshSecret.getBytes(), "HmacSHA256");
        this.issuer = issuer;
    }

    /**
     * 签发 AccessToken
     */
    public String signAccessToken(String userId, String role, String status) {
        long nowSeconds = System.currentTimeMillis() / 1000;

        return Jwts.builder()
            .header()
                .add("alg", "HS256")
                .add("typ", "JWT")
                .and()
            .claim("typ", "access")
            .claim("iss", issuer)
            .claim("sub", userId)
            .claim("role", role)
            .claim("status", status)
            .issuedAt(new Date(nowSeconds * 1000))
            .expiration(new Date((nowSeconds + 3600) * 1000))
            .id(UUID.randomUUID().toString())
            .signWith(accessKey)
            .compact();
    }

    /**
     * 签发 RefreshToken
     */
    public String signRefreshToken(String userId) {
        long nowSeconds = System.currentTimeMillis() / 1000;

        return Jwts.builder()
            .header()
                .add("alg", "HS256")
                .add("typ", "JWT")
                .and()
            .claim("typ", "refresh")
            .claim("iss", issuer)
            .claim("sub", userId)
            .issuedAt(new Date(nowSeconds * 1000))
            .expiration(new Date((nowSeconds + 2592000) * 1000))
            .id(UUID.randomUUID().toString())
            .signWith(refreshKey)
            .compact();
    }

    /**
     * 验证 AccessToken
     */
    public Claims verifyAccessToken(String token) {
        Claims claims = Jwts.parser()
            .verifyWith(accessKey)
            .requireIssuer(issuer)
            .build()
            .parseSignedClaims(token)
            .getPayload();

        validateAccessClaims(claims);
        return claims;
    }

    /**
     * 验证 RefreshToken
     */
    public Claims verifyRefreshToken(String token) {
        Claims claims = Jwts.parser()
            .verifyWith(refreshKey)
            .requireIssuer(issuer)
            .build()
            .parseSignedClaims(token)
            .getPayload();

        String typ = claims.get("typ", String.class);
        if (!"refresh".equals(typ)) {
            throw new IllegalArgumentException("TOKEN_TYPE_INVALID");
        }
        return claims;
    }

    private void validateAccessClaims(Claims claims) {
        String typ = claims.get("typ", String.class);
        if (!"access".equals(typ)) {
            throw new IllegalArgumentException("TOKEN_TYPE_INVALID");
        }

        String role = claims.get("role", String.class);
        if (!"member".equals(role) && !"admin".equals(role)) {
            throw new IllegalArgumentException("TOKEN_ROLE_INVALID");
        }

        String status = claims.get("status", String.class);
        if (!"enabled".equals(status) && !"disabled".equals(status)) {
            throw new IllegalArgumentException("TOKEN_STATUS_INVALID");
        }
    }
}
```

---

## 五、SpringBoot 认证过滤器

```java
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Map;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtTokenProvider jwtTokenProvider;

    public JwtAuthenticationFilter(JwtTokenProvider jwtTokenProvider) {
        this.jwtTokenProvider = jwtTokenProvider;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                     HttpServletResponse response,
                                     FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");

        if (header == null || !header.startsWith("Bearer ")) {
            chain.doFilter(request, response);
            return;
        }

        String token = header.substring(7);

        try {
            var claims = jwtTokenProvider.verifyAccessToken(token);

            String userId = claims.getSubject();
            String role = claims.get("role", String.class);

            List<SimpleGrantedAuthority> authorities = List.of(
                new SimpleGrantedAuthority("ROLE_" + role.toUpperCase())
            );

            UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(userId, null, authorities);
            auth.setDetails(Map.of(
                "role", role,
                "status", claims.get("status", String.class)
            ));

            SecurityContextHolder.getContext().setAuthentication(auth);
        } catch (Exception e) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(
                "{\"code\":40101,\"message\":\"未授权\",\"error\":\"" + e.getMessage() + "\"}"
            );
            return;
        }

        chain.doFilter(request, response);
    }
}
```

---

## 六、校验清单

Java 项目验证 JWT 时必须执行的检查（与 Node.js 项目对齐）：

| 检查项 | 错误码 | 说明 |
|--------|--------|------|
| Token 格式（3 段） | `TOKEN_FORMAT_INVALID` | `.` 分割必须恰好 3 段 |
| Header: `alg=HS256`, `typ=JWT` | — | jjwt 自动处理 |
| 签名正确性 | `TOKEN_SIGNATURE_INVALID` | HMAC-SHA256 验签 |
| `iss` 匹配 | `TOKEN_ISSUER_INVALID` | 必须为 `"newworkflow-backend"` |
| `typ` 匹配 | `TOKEN_TYPE_INVALID` | `"access"` 或 `"refresh"` |
| `sub` 非空 | `TOKEN_SUBJECT_INVALID` | 用户 ID |
| `jti` 非空 | `TOKEN_ID_INVALID` | Token 唯一 ID |
| `iat`/`exp` 有效 | `TOKEN_TIME_INVALID` | 有限正整数 |
| `exp > now` | `TOKEN_EXPIRED` | 未过期 |
| `role` 合法 | — | `"member"` 或 `"admin"` |
| `status` 合法 | — | `"enabled"` 或 `"disabled"` |

---

## 七、注意事项

1. **Base64URL 编码**：Node.js 使用 `+` → `-`、`/` → `_`、去掉 `=` 的 base64url 编码，标准 jjwt 库默认就是此格式，无需额外处理。

2. **时间戳单位**：JWT 的 `iat` 和 `exp` 是**秒**级 Unix 时间戳，不是毫秒。

3. **`typ` 字段**：JWT 标准中 header 的 `typ` 为 `"JWT"`，此项目 payload 中也有一个自定义 `typ`（`"access"` / `"refresh"`），两者独立，jjwt 可正确处理。

4. **RefreshToken 会话表**：Java 项目若需刷新 token，需向 `refresh_tokens` 表写入记录，`token_hash` 是 refresh token 的 SHA-256 哈希。

5. **密码哈希兼容性**：scrypt 参数必须完全一致（N=16384, r=8, p=1, keylen=64），否则无法验证已有密码。

6. **响应格式**：Node.js 项目的 API 响应使用统一信封格式 `{ code, message, data?, error?, timestamp }`，Java 项目建议保持一致。
