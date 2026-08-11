[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)] [string] $SkillPath
)

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path -LiteralPath $SkillPath).Path
$index = Join-Path $root 'references/official-source-index.md'
if (-not (Test-Path -LiteralPath $index)) { throw "Missing official source index: $index" }

$required = @(
    'SKILL.md', 'agents/openai.yaml', 'references/version-and-compatibility.md',
    'references/getting-started.md', 'references/chat-and-models.md',
    'references/agent-patterns.md', 'references/data-and-retrieval.md',
    'references/mcp-and-multimodal.md', 'references/production-operations.md',
    'references/official-source-index.md'
)
$missing = $required | Where-Object { -not (Test-Path -LiteralPath (Join-Path $root $_)) }
if ($missing) { throw "Missing required files: $($missing -join ', ')" }

$indexText = Get-Content -Raw -LiteralPath $index
$urls = [regex]::Matches($indexText, 'https?://[^\s)]+') | ForEach-Object Value
if ($urls.Count -lt 5) { throw "Expected at least 5 official URLs, found $($urls.Count)" }
if ($indexText -notmatch '2\.0\.0') { throw 'Source index must state Spring AI 2.0.0' }
if ($indexText -notmatch 'Spring Boot 4\.0|spring-boot/4\.0') { throw 'Spring Boot 4.0 source is missing' }
if ($indexText -notmatch 'docs\.spring\.io/spring-ai/docs/current/api') { throw 'Javadoc source is missing' }
if ($indexText -notmatch 'spring-ai-examples') { throw 'Official examples source is missing' }
if ($indexText -notmatch 'advisors-recursive') { throw 'Recursive Advisors source is missing' }
if ($indexText -notmatch 'tools-migration') { throw 'ToolCallback migration source is missing' }
if ($indexText -notmatch 'llm-as-judge') { throw 'LLM-as-a-Judge source is missing' }
if ($indexText -notmatch 'mcp-client-boot-starter-docs') { throw 'MCP client starter source is missing' }

Write-Host "Valid source index: $($urls.Count) official URLs; required files present."
