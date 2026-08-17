[CmdletBinding()]
param(
  [string]$BaseUrl = "http://127.0.0.1:8080",
  [string]$EvidenceDirectory = "build-evidence/compose/runtime-recovery",
  [int]$TimeoutSeconds = 90
)

$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"

function Invoke-Compose {
  param([Parameter(Mandatory)][string[]]$Arguments)
  $output = & docker compose @Arguments 2>&1
  if ($LASTEXITCODE -ne 0) {
    throw "docker compose $($Arguments -join ' ') failed:`n$($output -join "`n")"
  }
  return $output
}

function Wait-Until {
  param(
    [Parameter(Mandatory)][scriptblock]$Condition,
    [Parameter(Mandatory)][string]$Description
  )
  $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
  do {
    if (& $Condition) { return }
    Start-Sleep -Milliseconds 500
  } while ((Get-Date) -lt $deadline)
  throw "Timed out waiting for $Description"
}

function Get-ServiceContainerId {
  param([Parameter(Mandatory)][string]$Service)
  $id = (Invoke-Compose @("ps", "-q", $Service) | Select-Object -First 1).Trim()
  if (-not $id) { throw "Compose service '$Service' has no container" }
  return $id
}

function Get-RestartCount {
  param([Parameter(Mandatory)][string]$ContainerId)
  $count = & docker inspect --format "{{.RestartCount}}" $ContainerId 2>&1
  if ($LASTEXITCODE -ne 0) { throw "Cannot inspect restart count for $ContainerId" }
  return [int](($count -join "`n").Trim())
}

function Get-HealthStatus {
  param([Parameter(Mandatory)][string]$ContainerId)
  $status = & docker inspect --format "{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}" $ContainerId 2>&1
  if ($LASTEXITCODE -ne 0) { return "missing" }
  return (($status -join "`n").Trim())
}

function Invoke-BaselineProbe {
  param([Parameter(Mandatory)][int]$Index)
  $body = @{
    model = "synthetic"
    stream = $false
    messages = @(@{ role = "user"; content = "dependency recovery probe $Index" })
  } | ConvertTo-Json -Depth 5 -Compress
  $started = Get-Date
  $response = Invoke-WebRequest `
    -UseBasicParsing `
    -Uri "$BaseUrl/v1/chat/completions" `
    -Method Post `
    -ContentType "application/json" `
    -Headers @{ "X-Request-Id" = "compose-recovery-$Index" } `
    -Body $body `
    -TimeoutSec 10
  if ([int]$response.StatusCode -ne 200) {
    throw "Baseline probe $Index returned $($response.StatusCode)"
  }
  return [math]::Round(((Get-Date) - $started).TotalMilliseconds, 3)
}

function Invoke-PsqlScalar {
  param([Parameter(Mandatory)][string]$Sql)
  $output = Invoke-Compose @(
    "exec", "-T", "postgres", "psql", "-U", "aegis", "-d", "aegisroute",
    "-v", "ON_ERROR_STOP=1", "-At", "-c", $Sql
  )
  return ($output -join "`n").Trim()
}

function Invoke-ContainerProcessExit {
  param(
    [Parameter(Mandatory)][string]$ContainerId,
    [Parameter(Mandatory)][ValidateSet("QUIT", "TERM")][string]$Signal
  )
  $output = & docker exec --user 0 $ContainerId sh -c "kill -$Signal 1" 2>&1
  if ($LASTEXITCODE -ne 0) {
    throw "Failed to inject $Signal into container $ContainerId`n$($output -join "`n")"
  }
}

Invoke-Compose @("up", "-d", "--wait") | Out-Null
& (Join-Path $PSScriptRoot "verify-compose-runtime-policy.ps1") | Out-Host
Wait-Until -Description "Gateway route readiness before fault injection" -Condition {
  try {
    $ready = Invoke-RestMethod -Uri "$BaseUrl/health/ready" -TimeoutSec 3
    $ready.status -eq "UP" -and [long]$ready.routeVersion -gt 0
  } catch { $false }
}
$preflightLatency = Invoke-BaselineProbe -Index 0

$postgresId = Get-ServiceContainerId -Service "postgres"
$redpandaId = Get-ServiceContainerId -Service "redpanda"
$postgresRestartsBefore = Get-RestartCount -ContainerId $postgresId
$redpandaRestartsBefore = Get-RestartCount -ContainerId $redpandaId
$routeVersionBefore = [long](Invoke-PsqlScalar -Sql "SELECT max(version) FROM route_revisions;")
$startedAt = (Get-Date).ToUniversalTime()

Invoke-ContainerProcessExit -ContainerId $postgresId -Signal "QUIT"
Invoke-ContainerProcessExit -ContainerId $redpandaId -Signal "TERM"

$baselineLatencies = @()
$baselineFailures = 0
$baselineErrors = @()
for ($index = 1; $index -le 5; $index++) {
  try {
    $baselineLatencies += Invoke-BaselineProbe -Index $index
  } catch {
    $baselineFailures++
    $baselineErrors += $_.Exception.Message
  }
}

Wait-Until -Description "PostgreSQL automatic restart" -Condition {
  (Get-RestartCount -ContainerId $postgresId) -gt $postgresRestartsBefore
}
Wait-Until -Description "Redpanda automatic restart" -Condition {
  (Get-RestartCount -ContainerId $redpandaId) -gt $redpandaRestartsBefore
}
Wait-Until -Description "PostgreSQL health" -Condition {
  (Get-HealthStatus -ContainerId $postgresId) -eq "healthy"
}
Wait-Until -Description "Redpanda health" -Condition {
  (Get-HealthStatus -ContainerId $redpandaId) -eq "healthy"
}
Wait-Until -Description "Control health" -Condition {
  try {
    $health = Invoke-Compose @("exec", "-T", "control", "curl", "--silent", "--fail", "http://localhost:8081/actuator/health")
    (($health -join "`n") | ConvertFrom-Json).status -eq "UP"
  } catch { $false }
}
Wait-Until -Description "Worker health" -Condition {
  try {
    $health = Invoke-Compose @("exec", "-T", "worker", "curl", "--silent", "--fail", "http://localhost:8082/actuator/health")
    (($health -join "`n") | ConvertFrom-Json).status -eq "UP"
  } catch { $false }
}

$routeVersionAfter = [long](Invoke-PsqlScalar -Sql "SELECT max(version) FROM route_revisions;")
if ($routeVersionAfter -lt $routeVersionBefore) {
  throw "Route revision regressed from $routeVersionBefore to $routeVersionAfter"
}

$evidencePath = New-Item -ItemType Directory -Force -Path $EvidenceDirectory
$summary = [ordered]@{
  scenario = "postgres-sigquit-redpanda-sigterm-recovery"
  startedAt = $startedAt.ToString("o")
  completedAt = (Get-Date).ToUniversalTime().ToString("o")
  postgresRestartCountBefore = $postgresRestartsBefore
  postgresRestartCountAfter = Get-RestartCount -ContainerId $postgresId
  redpandaRestartCountBefore = $redpandaRestartsBefore
  redpandaRestartCountAfter = Get-RestartCount -ContainerId $redpandaId
  preflightLatencyMilliseconds = $preflightLatency
  baselineRequests = 5
  baselineFailures = $baselineFailures
  baselineLatencyMilliseconds = $baselineLatencies
  baselineErrors = $baselineErrors
  routeVersionBefore = $routeVersionBefore
  routeVersionAfter = $routeVersionAfter
  controlHealth = "UP"
  workerHealth = "UP"
}
$summary | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $evidencePath "summary.json") -Encoding utf8
$summary | ConvertTo-Json -Depth 5
if ($baselineFailures -gt 0) {
  throw "$baselineFailures of 5 baseline requests failed during dependency recovery"
}
