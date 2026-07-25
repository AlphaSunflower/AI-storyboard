# AI Storyboard

AI-powered storyboard generation platform. Spring Boot 4 backend + React/TypeScript frontend.

## Tech Stack

- **Backend**: Spring Boot 4.0.0, JDK 21, MyBatis-Plus 3.5.16 (spring-boot4-starter), PostgreSQL, JDK HttpClient
- **Frontend**: React 19 + TypeScript 6 + Vite 8, Zustand 5, Tailwind CSS 4, Axios (timeout 120s), React Router 7
- **AI**: Laozhang API v2 (`api2.laozhang.ai`), models: `gpt-image-2`, `gemini-3-flash-preview`, `veo-3.1-fast`
- **Auth**: JWT (jjwt), scrypt password hashing (lambdaworks 1.4.0), cross-system token exchange via `/api/auth/unlogin`
- **Ports**: Backend 8082, Frontend 5173

## Design System

From `claude/DESIGN.md` — Anthropic-inspired warm editorial:

| Token | Value |
|-------|-------|
| Primary (coral) | `#cc785c` |
| Canvas (cream) | `#faf9f5` |
| Ink (dark) | `#141413` / `#181715` |
| Surface card | `#efe9de` |
| Border radius | 8px (buttons/inputs), 12px (cards), 16px (hero) |

## Project Structure

```
AI-storyboard/
├── AIStoryboardBackend/          # Spring Boot backend
│   └── src/main/java/com/storyboard/
│       ├── controller/           # REST controllers
│       ├── service/              # Business logic
│       │   └── ai/               # AI generation services
│       ├── entity/               # JPA entities
│       ├── dto/                  # Request/Response DTOs
│       ├── mapper/               # MyBatis-Plus mappers
│       ├── config/               # Security, CORS, MyBatis config
│       ├── security/             # JWT provider, filters, scrypt
│       └── exception/            # Global exception handler
├── AIStoryboardClient/           # React frontend
│   └── src/
│       ├── pages/                # EditorPage, LoginPage
│       ├── components/
│       │   ├── editor/           # PreviewPanel, SceneListPanel, LeftSidebar
│       │   ├── scene/            # SceneCard
│       │   ├── ai/               # ImageRefineModal, VideoRefineModal
│       │   ├── layout/           # AppHeader
│       │   └── common/           # GenerationProgress, DraftRecoverBanner
│       ├── stores/               # Zustand stores
│       ├── api/                  # Axios API clients
│       └── types/                # TypeScript types
├── docs/                         # Chinese documentation
│   ├── 大模型调用文档.md
│   ├── 系统一接入指南.md
│   └── 系统一调用参数更新.md
└── claude/DESIGN.md              # Design tokens spec
```

## Databases

- **Primary**: PostgreSQL `newworkflow` (postgres/123456), shared `public.users` table
- **Local dev**: Configure via `application-local` profile

## Critical Pitfalls

### 1. PostgreSQL `timestamptz` → `OffsetDateTime`

Database `timestamptz` columns MUST use `OffsetDateTime` in entities, NOT `LocalDateTime`.
PostgreSQL JDBC driver returns `OffsetDateTime` for `timestamptz`; mapping to `LocalDateTime` causes silent SELECT failures → page 500.

```java
// Correct
import java.time.OffsetDateTime;
private OffsetDateTime createdAt;
```

### 2. Spring Boot 4 incompatible with spring-dotenv

`spring-dotenv` does NOT work with SB4. Use manual `.env` reading in `StoryboardApplication.main()` via `System.setProperty()`.

`application.yml` `${VAR:default}` reads system properties first, so `.env` values override defaults. For fixed values (API URLs), hardcode in yml directly.

### 3. Laozhang API v2 differences

- **URL**: `api2.laozhang.ai` (NOT `api.laozhang.ai`)
- **Image size**: OpenAI format `"1024x1024"` (NOT `"2K"`)
- **Image response**: prefer `data[0].b64_json`, fallback to `data[0].url`. URLs may be `data:image/png;base64,...` — strip prefix before Base64 decode
- **Video generation**: Async — POST creates task → returns `taskId` → poll `GET /v1/videos/{taskId}` every 5s, timeout 5min

### 4. Image local storage & serving

Generated images/videos download to `uploads/images/` / `uploads/videos/`, served via `/api/files/`:
- `FileStorageService.saveImage(url)` handles both URL download and base64 decode
- `FileController` — `GET /api/files/images/{filename}`
- `SecurityConfig` must permit `/api/files/**`
- `uploads/` in `.gitignore`

### 5. Frontend image URLs need backend prefix

Backend returns `/api/files/images/xxx.png`. Frontend `<img src={...}>` in dev requests `http://localhost:5173/...`.
Always prepend backend URL:

```tsx
const BACKEND = 'http://localhost:8082';
function assetUrl(path: string) {
  if (!path) return '';
  if (path.startsWith('http')) return path;
  return BACKEND + path;
}
```

### 6. Cross-origin downloads

`<a href download>` fails across ports. Use fetch + blob:

```tsx
function downloadAsset(url: string, filename: string) {
  fetch(url).then(r => r.blob()).then(blob => {
    const u = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = u; a.download = filename; a.click();
    URL.revokeObjectURL(u);
  }).catch(() => window.open(url, '_blank'));
}
```

### 7. HttpClient timeouts

AI calls are slow (image 30-60s, video 2-5min). ALL Java HttpClients must set timeouts:

```java
private final HttpClient httpClient = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(30))
    .build();

// Per-request timeout
HttpRequest request = HttpRequest.newBuilder()
    .timeout(Duration.ofSeconds(120))
    .build();
```

### 8. Error messages to frontend

`GlobalExceptionHandler.handleUnknown` returns `e.getMessage()` (not fixed "server internal error") so frontend sees Laozhang API errors (e.g. content moderation rejections).

## Frontend State Management (Zustand)

### `updateProject` double update

Must update BOTH `currentProject` AND `projects` list, otherwise `ProjectHistoryPanel` misses status changes:

```ts
updateProject: async (id, data) => {
    const res = await projectApi.update(id, data);
    const updated = res.data.data;
    set((s) => ({
        currentProject: s.currentProject?.id === id ? updated : s.currentProject,
        projects: s.projects.map(p => p.id === id ? updated : p),
    }));
},
```

### Async polling for video progress

Video generation is async. Store tracks per-scene progress with `Record<string, number>`:

```ts
videoProgress: Record<string, number>; // sceneId -> progress (0-100)
```

Always clear progress in `finally` block (ensures reset on success AND failure).

### `markDirty` — auto-draft on edit

When `currentProject.status === 'active'`, any edit (generate script/image/video, add/delete scene) auto-downgrades to `draft`:

```ts
markDirty: () => {
    const { currentProject, updateProject } = get();
    if (currentProject && currentProject.status === 'active') {
        updateProject(currentProject.id, { status: 'draft' });
    }
},
```

Called after: `generateScript`, `generateImage`, `generateVideo`, `addScene`, `deleteScene`.

### SceneCard button states

| Status | Has URL | Button text | Click action |
|--------|---------|-------------|--------------|
| `completed` | yes | "完善..." (refine) | Open refine modal |
| `completed` | no | "重试" (retry) | Regenerate directly |
| `generating` | any | "重试" (retry) | Regenerate (zombie state) |
| `pending` | any | "生成..." (generate) | Generate directly |
| `failed` | any | "重试" (retry) | Regenerate directly |

### `soundDesign` field reuse — ref image state

`soundDesign` is repurposed as JSON to share reference image state between `SceneCard` and `PreviewPanel`:

```ts
// Storage format
{ images: string[] (base64 data URIs), useForImage: boolean, useForVideo: boolean }

// Default: both checkboxes checked
// Unchecked → don't send refImages; checked + empty → undefined
```

## Resizable Panels

EditorPage three-column layout with 4px drag handle between SceneListPanel (380-600px) and PreviewPanel (flex:1).
Handle: `cursor: col-resize`, hover turns `var(--color-primary)`, `flexShrink: 0`.

## Cross-System JWT Exchange (`/api/auth/unlogin`)

Allows external system (system one) to sign a JWT with shared secret, carry `userId`, and jump into the editor. Backend performs three-way validation (account + password + JWT) then issues access + refresh tokens.

Frontend `EditorPage` detects URL params `?token=...&refresh=...&userId=...&name=...`, stores in localStorage, clears URL via `history.replaceState`, calls `checkAuth()`.

## Project Save/Draft Mechanism

- Projects have `status`: `draft` | `active`. New projects default to `draft`.
- Save button in AppHeader calls `updateProject(id, { status: 'active' })`
- Draft detection: `ProjectMapper.findLatestDraft` + `AND status = 'draft'`; frontend `checkDraft` double-checks `draft.status === 'draft'`
- Draft recovery: `EditorPage` loads → `checkDraft()` → shows `DraftRecoverBanner` if draft exists

## Verification Commands

```bash
# Backend (MUST use Windows paths for JAVA_HOME)
export JAVA_HOME="C:\\Program Files\\Java\\jdk-21"
"/e/Development/apache-maven-3.9.15/bin/mvn.cmd" -f "E:\\Desktop\\AI-storyboard\\AIStoryboardBackend\\pom.xml" compile -q

# Frontend
cd AIStoryboardClient && npx tsc --noEmit && npm run build
```

**Maven gotcha**: `JAVA_HOME` must use Windows path format (`C:\\...`), not POSIX (`/c/...`). `mvn.cmd` is a Windows batch file — cmd.exe doesn't understand POSIX paths. Direct invocation (not bash `mvn` alias) required or `ClassNotFoundException: plexus-classworlds`.

## Coding Conventions

- **Backend**: DTOs use Java records; services are `@Service`; mappers extend `BaseMapper<T>`; entities use Lombok `@Data`
- **Frontend**: Functional components with hooks; Zustand for global state; Axios instances with interceptors for auth token injection
- **API responses**: Wrapped in `ApiResponse<T>` with `code`, `message`, `data`
- **Auth**: JWT access token (short-lived) + refresh token; `JwtAuthenticationFilter` on all routes except `/api/auth/**` and `/api/files/**`
- **CORS**: Configured in `SecurityConfig` via `CorsConfigurationSource` bean
