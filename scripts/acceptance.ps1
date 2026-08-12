[CmdletBinding()]
param(
  [string]$BaseUrl = "http://127.0.0.1:8080",
  [string]$EvidenceDirectory = "build-evidence/compose",
  [int]$TimeoutSeconds = 90,
  [switch]$SkipChaos
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

function Invoke-ControlJson {
  param(
    [Parameter(Mandatory)][string]$Method,
    [Parameter(Mandatory)][string]$Path,
    [Parameter(Mandatory)][string]$Body,
    [hashtable]$Headers = @{}
  )
  $arguments = @(
    "exec", "-T", "control", "curl", "--silent", "--show-error", "--fail-with-body",
    "-X", $Method, "http://localhost:8081$Path", "-H", "Content-Type: application/json"
  )
  foreach ($entry in $Headers.GetEnumerator()) {
    $arguments += @("-H", "$($entry.Key): $($entry.Value)")
  }
  $arguments += @("--data-binary", "@-")
  $output = $Body | & docker compose @arguments 2>&1
  if ($LASTEXITCODE -ne 0) {
    throw "Control request $Method $Path failed:`n$($output -join "`n")"
  }
  return ($output -join "`n") | ConvertFrom-Json
}

function Invoke-PsqlScalar {
  param([Parameter(Mandatory)][string]$Sql)
  $output = Invoke-Compose @(
    "exec", "-T", "postgres", "psql", "-U", "aegis", "-d", "aegisroute",
    "-v", "ON_ERROR_STOP=1", "-At", "-c", $Sql
  )
  return ($output -join "`n").Trim()
}

function Invoke-PsqlReport {
  param([Parameter(Mandatory)][string]$Sql)
  return Invoke-Compose @(
    "exec", "-T", "postgres", "psql", "-U", "aegis", "-d", "aegisroute",
    "-v", "ON_ERROR_STOP=1", "-P", "pager=off", "-c", $Sql
  )
}

function Wait-Until {
  param(
    [Parameter(Mandatory)][scriptblock]$Condition,
    [Parameter(Mandatory)][string]$Description,
    [int]$Seconds = $TimeoutSeconds
  )
  $deadline = (Get-Date).AddSeconds($Seconds)
  do {
    if (& $Condition) { return }
    Start-Sleep -Milliseconds 500
  } while ((Get-Date) -lt $deadline)
  throw "Timed out waiting for $Description"
}

function Get-HttpStatus {
  param(
    [Parameter(Mandatory)][string]$Uri,
    [ValidateSet("GET", "POST")][string]$Method = "GET",
    [string]$Body
  )
  try {
    $parameters = @{
      Uri = $Uri
      Method = $Method
      UseBasicParsing = $true
      TimeoutSec = 10
    }
    if ($null -ne $Body) {
      $parameters.ContentType = "application/json"
      $parameters.Body = $Body
    }
    return [int](Invoke-WebRequest @parameters).StatusCode
  } catch {
    if ($null -ne $_.Exception.Response) {
      return [int]$_.Exception.Response.StatusCode
    }
    throw
  }
}

function Get-GatewayContainerIds {
  $ids = Invoke-Compose @("ps", "-q", "gateway")
  return @($ids | Where-Object { $_ })
}

function Invoke-GatewayRequest {
  param(
    [Parameter(Mandatory)][string]$GatewayId,
    [Parameter(Mandatory)][string]$Path,
    [ValidateSet("GET", "POST")][string]$Method = "GET",
    [string]$Body
  )
  $arguments = @(
    "exec", "-i", $GatewayId, "curl", "--silent", "--show-error", "--fail-with-body",
    "-X", $Method, "http://localhost:8080$Path"
  )
  if ($null -ne $Body) {
    $arguments += @("-H", "Content-Type: application/json", "--data-binary", "@-")
    $output = $Body | & docker @arguments 2>&1
  } else {
    $output = & docker @arguments 2>&1
  }
  if ($LASTEXITCODE -ne 0) {
    throw "Gateway request $Method $Path failed for $GatewayId`:`n$($output -join "`n")"
  }
  return $output -join "`n"
}

function Send-Completion {
  param([Parameter(Mandatory)][string]$RequestId)
  $body = @{
    model = "support-assistant"
    stream = $false
    messages = @(@{ role = "user"; content = "Synthetic acceptance request" })
  } | ConvertTo-Json -Depth 5 -Compress
  try {
    Invoke-WebRequest -UseBasicParsing -Method Post -Uri "$BaseUrl/v1/chat/completions" `
      -ContentType "application/json" -Headers @{ "X-Request-Id" = $RequestId } `
      -Body $body -TimeoutSec 10 | Out-Null
  } catch {
    # Candidate HTTP 500 responses are expected during the deterministic rollback scenario.
    if ($null -eq $_.Exception.Response) { throw }
  }
}

function Assert-Equal {
  param($Actual, $Expected, [string]$Description)
  if ($Actual -ne $Expected) {
    throw "$Description expected '$Expected' but was '$Actual'"
  }
}

New-Item -ItemType Directory -Force -Path $EvidenceDirectory | Out-Null

Wait-Until -Description "Gateway readiness" -Condition {
  try { (Invoke-RestMethod -Uri "$BaseUrl/health/ready" -TimeoutSec 2).status -eq "UP" }
  catch { $false }
}

Assert-Equal (Get-HttpStatus -Uri "$BaseUrl/health/live") 200 "liveness status"
Assert-Equal (Get-HttpStatus -Uri "$BaseUrl/health/ready") 200 "readiness status"
Send-Completion -RequestId "baseline-smoke-$([guid]::NewGuid())"

$rolloutId = Invoke-PsqlScalar "SELECT id FROM rollouts ORDER BY created_at DESC LIMIT 1;"
if (-not $rolloutId) { throw "Demo rollout was not created" }
$state = Invoke-PsqlScalar "SELECT state FROM rollouts WHERE id='$rolloutId';"
if ($state -notin @("DRAFT", "SHADOW")) {
  throw "Acceptance requires a fresh DRAFT/SHADOW demo rollout, but found state '$state'. Run 'docker compose down --volumes' before retrying."
}

if ($state -eq "DRAFT") {
  $version = [long](Invoke-PsqlScalar "SELECT version FROM rollouts WHERE id='$rolloutId';")
  $mutation = @{ actor = "compose-acceptance"; reason = "Start synthetic shadow evaluation" } |
    ConvertTo-Json -Compress
  Invoke-ControlJson -Method POST -Path "/api/v1/rollouts/$rolloutId/shadow:start" `
    -Body $mutation -Headers @{
      "Idempotency-Key" = "acceptance-shadow-$([guid]::NewGuid())"
      "If-Match" = "`"$version`""
    } | Out-Null
  $state = "SHADOW"
}

if ($state -eq "SHADOW") {
  for ($index = 1; $index -le 18; $index++) {
    Send-Completion -RequestId "shadow-$index-$([guid]::NewGuid())"
  }
  Wait-Until -Description "shadow eligibility" -Condition {
    (Invoke-PsqlScalar "SELECT state FROM rollouts WHERE id='$rolloutId';") -eq "ELIGIBLE"
  }
  $state = "ELIGIBLE"
}

Assert-Equal $state "ELIGIBLE" "pre-canary rollout state"

foreach ($ratio in 1, 10, 50, 100) {
  $version = [long](Invoke-PsqlScalar "SELECT version FROM rollouts WHERE id='$rolloutId';")
  $mutation = @{
    actor = "compose-acceptance"
    reason = "Approve synthetic canary at $ratio percent"
    candidateRatio = $ratio
  } | ConvertTo-Json -Compress
  $response = Invoke-ControlJson -Method POST `
    -Path "/api/v1/rollouts/$rolloutId/canary:approve" -Body $mutation -Headers @{
      "Idempotency-Key" = "acceptance-canary-$ratio-$([guid]::NewGuid())"
      "If-Match" = "`"$version`""
    }
  Assert-Equal ([int]$response.candidateRatio) $ratio "approved candidate ratio"
  Start-Sleep -Milliseconds 1200
}

Assert-Equal (Invoke-PsqlScalar "SELECT state FROM rollouts WHERE id='$rolloutId';") "FULL" `
  "post-canary rollout state"

$trafficDeadline = (Get-Date).AddSeconds($TimeoutSeconds)
$requestNumber = 0
do {
  $requestNumber++
  Send-Completion -RequestId "rollback-$requestNumber-$([guid]::NewGuid())"
  Start-Sleep -Milliseconds 200
  $state = Invoke-PsqlScalar "SELECT state FROM rollouts WHERE id='$rolloutId';"
} while ($state -ne "ROLLED_BACK" -and (Get-Date) -lt $trafficDeadline)
Assert-Equal $state "ROLLED_BACK" "automatic rollback state"

$decisionCount = [int](Invoke-PsqlScalar "SELECT count(*) FROM rollout_decisions WHERE rollout_id='$rolloutId';")
Assert-Equal $decisionCount 1 "immutable rollback decision count"
$targetCount = [int](Invoke-PsqlScalar @"
SELECT count(*) FROM rollback_decision_targets t
JOIN rollout_decisions d ON d.decision_id=t.decision_id
WHERE d.rollout_id='$rolloutId';
"@)
if ($targetCount -lt 2) { throw "Rollback decision targeted only $targetCount Gateway instance(s)" }
$convergedCount = [int](Invoke-PsqlScalar @"
SELECT jsonb_array_length(c.converged_instances)
FROM gateway_convergence_evidence c WHERE c.rollout_id='$rolloutId';
"@)
Assert-Equal $convergedCount $targetCount "converged Gateway count"
$targetRoute = [long](Invoke-PsqlScalar "SELECT to_route_version FROM rollout_decisions WHERE rollout_id='$rolloutId';")
$candidateRatio = [int](Invoke-PsqlScalar "SELECT candidate_ratio FROM route_revisions WHERE version=$targetRoute;")
Assert-Equal $candidateRatio 0 "rollback route candidate ratio"
$gatewayIds = Get-GatewayContainerIds
if ($gatewayIds.Count -lt 2) { throw "Acceptance requires at least two Gateway containers" }
foreach ($gatewayId in $gatewayIds) {
  $ready = Invoke-GatewayRequest -GatewayId $gatewayId -Path "/health/ready" | ConvertFrom-Json
  Assert-Equal ([long]$ready.routeVersion) $targetRoute "Gateway route version"
}
$convergenceSeconds = [double](Invoke-PsqlScalar @"
SELECT EXTRACT(EPOCH FROM (c.converged_at - d.created_at))
FROM rollout_decisions d JOIN gateway_convergence_evidence c
  ON c.rollout_id=d.rollout_id AND c.target_route_version=d.to_route_version
WHERE d.rollout_id='$rolloutId';
"@)
if ($convergenceSeconds -ge 5.0) {
  throw "Rollback convergence took $convergenceSeconds seconds (target: <5 seconds)"
}

$decisionJson = Invoke-PsqlScalar @"
SELECT jsonb_pretty(jsonb_build_object(
  'decision', decision,
  'decisionId', decision_id,
  'rolloutId', rollout_id,
  'fromRouteVersion', from_route_version,
  'toRouteVersion', to_route_version,
  'windowStart', window_start,
  'windowEnd', window_end,
  'candidateRequests', candidate_requests,
  'candidateErrors', candidate_errors,
  'errorRate', error_rate,
  'threshold', threshold,
  'policyVersion', policy_version,
  'evidenceDigest', evidence_digest,
  'createdAt', created_at,
  'convergenceSeconds', $convergenceSeconds
))::text FROM rollout_decisions WHERE rollout_id='$rolloutId';
"@
Set-Content -Path (Join-Path $EvidenceDirectory "rollback-decision.json") -Value $decisionJson -Encoding utf8

$databaseReport = Invoke-PsqlReport @"
SELECT id,state,version,candidate_ratio,updated_at FROM rollouts WHERE id='$rolloutId';
SELECT window_start,window_end,candidate_requests,candidate_errors,error_rate,evidence_digest
  FROM evidence_windows WHERE rollout_id='$rolloutId' ORDER BY created_at;
SELECT policy_version,threshold,breached,consecutive_breaches,created_at
  FROM policy_evaluations WHERE rollout_id='$rolloutId' ORDER BY created_at;
SELECT * FROM rollout_decisions WHERE rollout_id='$rolloutId';
SELECT gateway_instance_id,route_version,applied_at,last_seen_at
  FROM gateway_route_acks ORDER BY gateway_instance_id;
SELECT * FROM gateway_convergence_evidence WHERE rollout_id='$rolloutId';
"@
Set-Content -Path (Join-Path $EvidenceDirectory "database.txt") -Value $databaseReport -Encoding utf8

if (-not $SkipChaos) {
  # Existing LKG must continue serving while Control is unavailable, and snapshot age must grow.
  $initialAge = [double](Invoke-PsqlScalar "SELECT 0;") # Initializes a culture-safe numeric value.
  $metricsBefore = (Invoke-WebRequest -UseBasicParsing -Uri "$BaseUrl/actuator/prometheus").Content
  $ageMatch = [regex]::Match($metricsBefore, '(?m)^aegis_route_snapshot_age_seconds\s+([0-9.Ee+-]+)$')
  if ($ageMatch.Success) { $initialAge = [double]::Parse($ageMatch.Groups[1].Value, [cultureinfo]::InvariantCulture) }
  Invoke-Compose @("stop", "control") | Out-Null
  Start-Sleep -Seconds 3
  Assert-Equal (Get-HttpStatus -Uri "$BaseUrl/health/ready") 200 "LKG readiness during Control outage"
  Send-Completion -RequestId "lkg-control-outage-$([guid]::NewGuid())"
  $metricsAfter = (Invoke-WebRequest -UseBasicParsing -Uri "$BaseUrl/actuator/prometheus").Content
  $ageAfterMatch = [regex]::Match($metricsAfter, '(?m)^aegis_route_snapshot_age_seconds\s+([0-9.Ee+-]+)$')
  if (-not $ageAfterMatch.Success) { throw "Snapshot age metric is missing" }
  $ageAfter = [double]::Parse($ageAfterMatch.Groups[1].Value, [cultureinfo]::InvariantCulture)
  if ($ageAfter -le $initialAge) { throw "Snapshot age did not increase during Control outage" }

  # Restarting Gateways clears in-memory LKG. With Control still stopped they are live but unready.
  Invoke-Compose @("restart", "gateway") | Out-Null
  Wait-Until -Description "Gateway liveness without a snapshot" -Condition {
    (Get-HttpStatus -Uri "$BaseUrl/health/live") -eq 200
  }
  Assert-Equal (Get-HttpStatus -Uri "$BaseUrl/health/ready") 503 "readiness without a snapshot"
  $completionBody = @{
    model = "support-assistant"
    stream = $false
    messages = @(@{ role = "user"; content = "No snapshot check" })
  } | ConvertTo-Json -Depth 5 -Compress
  Assert-Equal (Get-HttpStatus -Uri "$BaseUrl/v1/chat/completions" -Method POST -Body $completionBody) `
    503 "completion status without a snapshot"
  Invoke-Compose @("start", "control") | Out-Null
  Wait-Until -Description "Gateway readiness after Control recovery" -Condition {
    (Get-HttpStatus -Uri "$BaseUrl/health/ready") -eq 200
  }

  # Broker loss must not enter the baseline critical path; every Gateway receives a direct request.
  Invoke-Compose @("stop", "redpanda") | Out-Null
  $gatewayIds = Get-GatewayContainerIds
  $latencies = @()
  foreach ($gatewayId in $gatewayIds) {
    $stopwatch = [diagnostics.stopwatch]::StartNew()
    $completionBody | & docker exec -i $gatewayId curl --silent --show-error --fail-with-body `
      -X POST http://localhost:8080/v1/chat/completions -H "Content-Type: application/json" `
      -H "X-Request-Id: broker-outage-$([guid]::NewGuid())" --data-binary '@-' | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Baseline request failed while Redpanda was stopped" }
    $stopwatch.Stop()
    $latencies += $stopwatch.Elapsed.TotalMilliseconds
  }
  if (($latencies | Measure-Object -Maximum).Maximum -ge 2000) {
    throw "A baseline request waited at least two seconds during broker outage"
  }
  Start-Sleep -Seconds 6
  $dropObserved = $false
  foreach ($gatewayId in $gatewayIds) {
    $metrics = & docker exec $gatewayId curl --silent --show-error --fail `
      http://localhost:8080/actuator/prometheus
    if ($LASTEXITCODE -ne 0) { throw "Could not read Gateway metrics" }
    if ($metrics -match 'aegis_shadow_dropped_total\{reason="(publisher_timeout|broker_unavailable)"\}\s+[1-9]') {
      $dropObserved = $true
    }
  }
  if (-not $dropObserved) { throw "No publisher drop reason was observed during broker outage" }
  Invoke-Compose @("start", "redpanda") | Out-Null

  @{
    lkgAgeBeforeSeconds = $initialAge
    lkgAgeAfterSeconds = $ageAfter
    noSnapshotLiveStatus = 200
    noSnapshotReadyStatus = 503
    noSnapshotCompletionStatus = 503
    brokerOutageBaselineLatencyMilliseconds = $latencies
    publisherDropObserved = $dropObserved
  } | ConvertTo-Json -Depth 5 | Set-Content `
    -Path (Join-Path $EvidenceDirectory "chaos-summary.json") -Encoding utf8
}

Invoke-Compose @("ps", "--format", "json") |
  Set-Content -Path (Join-Path $EvidenceDirectory "compose-ps.jsonl") -Encoding utf8
Invoke-Compose @("logs", "--no-color") |
  Set-Content -Path (Join-Path $EvidenceDirectory "compose-logs.txt") -Encoding utf8

$summary = [ordered]@{
  rolloutId = $rolloutId
  finalState = "ROLLED_BACK"
  rollbackRouteVersion = $targetRoute
  candidateRatio = 0
  targetedGateways = $targetCount
  convergedGateways = $convergedCount
  convergenceSeconds = $convergenceSeconds
  evidenceDirectory = (Resolve-Path $EvidenceDirectory).Path
}
$summary | ConvertTo-Json -Depth 5 | Tee-Object -FilePath (Join-Path $EvidenceDirectory "summary.json")
