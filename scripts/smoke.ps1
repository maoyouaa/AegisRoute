param(
  [string]$BaseUrl = "http://127.0.0.1:8080"
)

$ErrorActionPreference = "Stop"
$ready = Invoke-RestMethod -Uri "$BaseUrl/health/ready"
if ($ready.status -ne "UP") { throw "Gateway is not ready" }

$body = @{
  model = "support-assistant"
  stream = $false
  messages = @(@{ role = "user"; content = "Synthetic smoke request" })
} | ConvertTo-Json -Depth 5

$response = Invoke-RestMethod -Method Post -Uri "$BaseUrl/v1/chat/completions" -ContentType "application/json" -Body $body
if (-not $response.choices[0].message.content) { throw "Completion body is missing" }
$response | ConvertTo-Json -Depth 8
