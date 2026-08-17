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

Write-Host "Verified restart policy for $($longRunningServices.Count) services, dependency healthchecks, and the internal-only Control boundary."
