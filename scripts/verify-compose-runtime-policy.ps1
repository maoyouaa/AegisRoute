[CmdletBinding()]
param(
  [string]$ComposeFile = (Join-Path $PSScriptRoot "../compose.yml")
)

$ErrorActionPreference = "Stop"
$resolvedCompose = (Resolve-Path -LiteralPath $ComposeFile).Path
$configOutput = & docker compose -f $resolvedCompose config --format json 2>&1
if ($LASTEXITCODE -ne 0) {
  throw "docker compose config failed:`n$($configOutput -join "`n")"
}
$config = ($configOutput -join "`n") | ConvertFrom-Json

$longRunningServices = @(
  "edge",
  "gateway",
  "control",
  "worker",
  "baseline",
  "candidate",
  "postgres",
  "redpanda",
  "prometheus",
  "grafana"
)

foreach ($serviceName in $longRunningServices) {
  $property = $config.services.PSObject.Properties[$serviceName]
  if ($null -eq $property) {
    throw "Compose service '$serviceName' is missing"
  }
  if ($property.Value.restart -ne "unless-stopped") {
    throw "Compose service '$serviceName' must use restart: unless-stopped"
  }
}

foreach ($dependency in @("postgres", "redpanda")) {
  $healthcheck = $config.services.PSObject.Properties[$dependency].Value.healthcheck
  if ($null -eq $healthcheck -or $null -eq $healthcheck.test) {
    throw "Compose dependency '$dependency' must define an explicit healthcheck"
  }
}

$controlPorts = $config.services.control.ports
if ($null -ne $controlPorts -and @($controlPorts).Count -gt 0) {
  throw "Control must not publish a host port in the default Compose topology"
}

$workerStore = $config.services.worker.environment.AEGIS_WORKER_STORE
if ($workerStore -ne '/var/lib/aegis/evidence.sqlite') {
  throw 'Worker must use its explicit durable store path'
}
$storeVolume = @($config.services.worker.volumes | Where-Object {
  $_.type -eq 'volume' -and $_.target -eq '/var/lib/aegis' -and -not $_.read_only
})
if ($storeVolume.Count -ne 1) { throw 'Worker needs one writable evidence volume' }

Write-Host "Verified restart policy for $($longRunningServices.Count) services, dependency healthchecks, durable Worker storage, and the internal-only Control boundary."
