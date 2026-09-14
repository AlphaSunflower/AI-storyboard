# asset-service

独立媒体资产服务，端口 `8085`，独占 PostgreSQL `asset` schema。

## 启动前置

- PostgreSQL 已创建可用数据库和账号。
- 数据库管理员已允许应用账号创建 `asset` schema，或预先安装 `pg_trgm` 扩展。
- MinIO/S3 兼容存储可访问；默认使用 `storyboard` bucket。
- Nacos 地址、JWT 密钥、LLM 网关地址与主后端保持一致。

## 关键环境变量

```text
SPRING_DATASOURCE_URL
SPRING_DATASOURCE_USERNAME
SPRING_DATASOURCE_PASSWORD
STORYBOARD_STORAGE_ENDPOINT
STORYBOARD_STORAGE_ACCESS_KEY
STORYBOARD_STORAGE_SECRET_KEY
STORYBOARD_INTERNAL_URL
INTERNAL_SECRET
JWT_ACCESS_SECRET
JWT_ISSUER
LLM_GATEWAY_BASE_URL
LLM_GATEWAY_API_KEY
ASSET_SIGNING_SECRET
```

## 接口边界

- 对外：`/api/library/**`。
- 内部：`/api/internal/library/**`，要求 `X-Internal-Token` 与 `X-User-Id`。
- 媒体内容：`/api/library/media/{mediaId}/content?token=...`，支持 HTTP Range。
- 主后端继续通过 `/api/files/**` 提供历史兼容读取；新 UI 使用签名媒体 URL。

## 构建

```bash
mvn -f pom.xml -pl asset-service -am test
```
