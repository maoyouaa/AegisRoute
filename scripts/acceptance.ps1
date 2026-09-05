[CmdletBinding()]
param(
  [string]$Project = ('aegis-acceptance-' + (Get-Date -Format 'yyyyMMdd-HHmmss')),
  [string]$EvidenceDirectory = ('build-evidence/acceptance-' + (Get-Date -Format 'yyyyMMdd-HHmmss')),
  [int]$PortOffset = 0,
  [switch]$SkipBuild,
  [switch]$Benchmark,
  [switch]$KeepRunning
)

# The old BaseUrl/SkipChaos parameters are rejected by parameter binding.
# This creates a fresh v2 topology and never mutates the default Compose stack.
$ErrorActionPreference = 'Stop'
$arguments = @('scripts/reliability/run.py', '--project', $Project,
  '--output', $EvidenceDirectory, '--port-offset', "$PortOffset")
if ($SkipBuild) { $arguments += '--skip-build' }
if ($Benchmark) { $arguments += '--benchmark' }
if ($KeepRunning) { $arguments += '--keep-running' }
Push-Location (Split-Path $PSScriptRoot -Parent)
try {
  & python @arguments
  if ($LASTEXITCODE -ne 0) { throw "Synthetic v2 acceptance failed: $LASTEXITCODE" }
} finally {
  Pop-Location
}
