# asset-client

asset-service 的唯一 Java HTTP 契约模块。消费方只依赖此模块，不直接访问资产表。

统一提供：

- `/api/library/**` 请求与响应 DTO；
- `X-Internal-Token`、`X-Correlation-Id`、`Idempotency-Key` 传递；
- 连接和读取超时；
- 统一 `AssetServiceException` 错误映射。

单独构建消费方前仍需先把共享模块安装到本地仓库，或从仓库根 Reactor 构建：

```bash
mvn -f pom.xml -pl AIStoryboardBackend -am test
mvn -f pom.xml -pl MoonAgent -am test
mvn -f pom.xml -pl asset-service -am test
```
