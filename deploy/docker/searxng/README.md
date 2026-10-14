# SearXNG 自托管搜索（MoonAgent `web_search` 的默认后端）

MoonAgent 的网页检索默认走**你自己部署的 SearXNG**，不依赖任何第三方搜索 API：
搜索请求全部打在这台实例上，没有 key、没有按次计费。

> 为什么还需要一台能出网的机器：SearXNG 是**元搜索引擎**，它替你并发查询上游引擎
> （Google/Bing/DuckDuckGo/Brave/维基…）并去重排序。整个链路里没有第三方**账号**，
> 但出口需要能访问那些上游站点。

## 启动

```bash
cd deploy/docker/searxng
# .env 首次需从样例复制（已存在则跳过）
[ -f .env ] || cp .env.example .env
docker compose --env-file .env up -d
```

验证（这一步同时证明 json 输出已开）：

```powershell
curl.exe -s "http://localhost:8888/search?q=searxng&format=json" | Select-Object -First 1
```

能返回带 `"results": [...]` 的 JSON 就说明可用了；返回 `403 Forbidden` 说明 `search.formats`
里没有 `json`（本目录的 `settings.yml` 已开好这一项）。

## 也可以直接并进统一栈

`deploy/stack/docker-compose.yml` 里已经有一个同款 `searxng` 服务（同一份 `settings.yml`、
同一个 `SEARXNG_SECRET` 变量、栈内地址 `http://searxng:8080`），因此：

- **只想让搜索跟着统一栈启停**：不用单独起本目录，`deploy/stack/scripts/up.ps1` 会把
  SearXNG 一起拉起来，`down.ps1` 也会一起停；栈内 MoonAgent 默认就连它。
- **两边不要同时起**：服务名与 `container_name` 都是 `searxng`，端口也都用 `${SEARXNG_PORT:-8888}`，
  同时启动会撞名撞端口。二选一即可。
- 两个 compose 项目都带 `restart: unless-stopped`：Docker 守护进程或宿主机重启后会自动拉起，
  不需要手工 `up`。

## MoonAgent 侧配置

| 变量 | 默认 | 说明 |
|---|---|---|
| `AGENTSCOPE_WEB_SEARCH_PROVIDER` | `searxng` | `searxng` 或 `deepseek` |
| `AGENTSCOPE_SEARXNG_URL` | `http://localhost:8888` | 实例地址；栈内是 `http://searxng:8080` |
| `AGENTSCOPE_SEARXNG_LANGUAGE` | `zh-CN` | 传给实例的 `language` 参数 |
| `AGENTSCOPE_WEB_SEARCH_MAX_RESULTS` | `8` | 返回给模型的条数上限（服务端截断） |

## 两个必踩的坑

1. **`format=json` 默认是关的**。SearXNG 的 `search.formats` 默认只有 `html`，请求 json 会
   被拒成 403。必须显式加 `- json`（见 `settings.yml`）。
2. **limiter 会把内网服务判成滥用**。`server.limiter: true` 时，没有浏览器会话的调用方
   容易吃到 429。本目录的配置里已关掉；如果你要对外公开实例，改成 `true` 并在前面做认证。

## 与其它后端的关系

- `provider=deepseek`：走 DeepSeek 原生搜索（`web_search_20250305` 服务端工具），
  搜索在 DeepSeek 侧执行、按账号计费、需要 key，但不依赖本机出网去爬上游引擎。
- `web_fetch` 与后端无关：它始终由 MoonAgent 自己抓取并把 HTML 转 Markdown，
  两套后端下行为一致。

## 运维

```bash
docker compose --env-file .env ps        # 状态
docker compose --env-file .env logs -f   # 日志
docker compose --env-file .env down      # 停止（不要加 -v，会删缓存卷）
```

`searxng-cache` 卷存运行期缓存与 uWSGI 状态，删掉只会让下次启动慢一点，不丢配置
（配置来自本目录挂载的 `settings.yml`）。
