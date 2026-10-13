# 切到「开发模式」：停掉容器里的业务服务，只保留依赖（Redis/MinIO/沙箱/Postgres）
# 业务代码改在本机用 IDE 或 mvn spring-boot:run 跑（local profile），前端用 npm run dev

$ErrorActionPreference = 'Continue'
$StackDir = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$compose  = Join-Path $StackDir 'docker-compose.yml'
$envFile  = Join-Path $StackDir '.env'

Push-Location $StackDir
try {
    $appServices = @('frontend', 'api-gateway', 'backend', 'llm-gateway', 'moon-agent', 'asset-service', 'canvas')
    Write-Host "[dev-up] 停止容器内业务服务: $($appServices -join ', ')"
    docker compose --env-file $envFile -f $compose stop @appServices 2>&1 | Out-Null

    $depServices = @('postgres', 'redis', 'minio', 'sandbox-runner', 'sandbox-egress-proxy')
    Write-Host "[dev-up] 启动依赖: $($depServices -join ', ')"
    docker compose --env-file $envFile -f $compose up -d @depServices 2>&1 | Out-Null

    # 兜底：compose 在停/起过程中偶尔会漏起个别依赖，这里逐个确认并补一次
    Start-Sleep -Seconds 8
    foreach ($svc in $depServices) {
        $state = (docker compose --env-file $envFile -f $compose ps --format '{{.Service}}|{{.State}}' |
            Where-Object { $_ -like "$svc|*" }) -split '\|' | Select-Object -Last 1
        if ($state -ne 'running') {
            Write-Host "[dev-up] $svc 未运行（$state），重新拉起"
            docker compose --env-file $envFile -f $compose up -d $svc 2>&1 | Out-Null
        }
    }
    Start-Sleep -Seconds 6
    Write-Host ''
    docker compose --env-file $envFile -f $compose ps --format '{{.Service}}|{{.State}}|{{.Health}}|{{.Ports}}' |
        Where-Object { $_ -match '^(postgres|redis|minio|sandbox)' }
} finally { Pop-Location }

Write-Host ''
Write-Host '依赖端口（本机应用按 local profile 直连这些地址）:'
Write-Host '  PostgreSQL 5433(容器库 ai_storyboard/llm_gateway) · 5432(本机原生库，开发默认)'
Write-Host '  Redis 6379 · MinIO 9000/9001 · 沙箱 8090'
Write-Host ''
Write-Host '服务发现默认关闭（NACOS_DISCOVERY_ENABLED=false），ApiGateway 走直连默认值，'
Write-Host '本机起业务服务无需额外传参（网关默认指向 http://localhost:8082/8085/9090/8084）。'
Write-Host ''
Write-Host '本机起业务服务（任选，local profile 为默认）:'
Write-Host '  mvn -pl ApiGateway          spring-boot:run   # :8080'
Write-Host '  mvn -pl AIStoryboardBackend spring-boot:run   # :8082'
Write-Host '  mvn -pl AILLMGateway        spring-boot:run   # :8083'
Write-Host '  mvn -pl MoonAgent           spring-boot:run   # :8084'
Write-Host '  mvn -pl asset-service       spring-boot:run   # :8085'
Write-Host '  mvn -pl XiaoYueCanvas       spring-boot:run   # :9090'
Write-Host '前端: cd AIStoryboardClient && npm run dev(:5173) ; cd XiaoYueCanvasAgent && npm run dev(:3000)'
