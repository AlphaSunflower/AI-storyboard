# 从本机原生 PostgreSQL 18 导出两个库，并生成 admin@qq.com 的 scrypt 密码哈希
param(
    [string]$PgBin         = 'D:\Program Files\PostgreSQL\18\bin',
    [string]$PgHost        = '127.0.0.1',
    [int]   $PgPort        = 5432,
    [string]$PgUser        = 'postgres',
    [string]$PgPassword    = '123456',
    [string]$AdminPassword = '123456',
    [string]$SourceEmail   = '771038325@qq.com'
)

$ErrorActionPreference = 'Continue'
$StackDir = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$DbDir    = Join-Path $StackDir 'db'
New-Item -ItemType Directory -Force -Path $DbDir | Out-Null

$env:PGPASSWORD = $PgPassword
$pgDump = Join-Path $PgBin 'pg_dump.exe'
$psql   = Join-Path $PgBin 'psql.exe'
if (-not (Test-Path $pgDump)) { throw "找不到 pg_dump: $pgDump" }

foreach ($db in @('ai_storyboard', 'llm_gateway')) {
    $out = Join-Path $DbDir "$db.dump"
    Write-Host "[dump] $db -> $out"
    & $pgDump -h $PgHost -p $PgPort -U $PgUser -d $db -Fc -f $out
    if ($LASTEXITCODE -ne 0) { throw "pg_dump $db 失败" }
}

# 生成 scrypt 哈希（与 AIStoryboardBackend ScryptPasswordService 参数一致）
$pyCode = "import hashlib,os;salt=os.urandom(16);dk=hashlib.scrypt(b'$AdminPassword',salt=salt,n=16384,r=8,p=1,dklen=64,maxmem=67108864);print('scrypt:'+salt.hex()+':'+dk.hex())"
$hash = (& python -c $pyCode).Trim()
if (-not $hash.StartsWith('scrypt:')) { throw "生成密码哈希失败: $hash" }
Set-Content -Path (Join-Path $DbDir 'admin-scrypt.txt') -Value $hash -NoNewline -Encoding ascii
Write-Host "[dump] 已写入 db/admin-scrypt.txt"

# 源库基线计数（供验收比对）
Write-Host "[dump] 源库基线（$SourceEmail）："
$env:PGPASSWORD = $PgPassword
& $psql -h $PgHost -p $PgPort -U $PgUser -d ai_storyboard -tA -c "select 'users='||count(*) from users where email='$SourceEmail'"
& $psql -h $PgHost -p $PgPort -U $PgUser -d ai_storyboard -tA -c "select 'projects='||count(*) from projects p join users u on u.id=p.user_id where u.email='$SourceEmail'"
& $psql -h $PgHost -p $PgPort -U $PgUser -d ai_storyboard -tA -c "select 'conversations='||count(*) from conversations c join users u on u.id=c.user_id where u.email='$SourceEmail'"
& $psql -h $PgHost -p $PgPort -U $PgUser -d ai_storyboard -tA -c "select 'agent_messages='||count(*) from agent_messages m join conversations c on c.id=m.conversation_id join users u on u.id=c.user_id where u.email='$SourceEmail'"
& $psql -h $PgHost -p $PgPort -U $PgUser -d llm_gateway -tA -c "select 'channel='||(select count(*) from channel)||' model_route='||(select count(*) from model_route)||' gateway_api_key='||(select count(*) from gateway_api_key)"
