[CmdletBinding()]
param(
    [string]$WorkflowPath = (Join-Path $PSScriptRoot '../.github/workflows/release-candidate.yml')
)

$ErrorActionPreference = 'Stop'
$resolvedWorkflow = (Resolve-Path -LiteralPath $WorkflowPath).Path
$contents = [IO.File]::ReadAllText($resolvedWorkflow).Replace("`r`n", "`n")

function Assert-WorkflowInvariant {
    param(
        [Parameter(Mandatory)]
        [bool]$Condition,
        [Parameter(Mandatory)]
        [string]$Message
    )

    if (-not $Condition) {
        throw $Message
    }
}

Assert-WorkflowInvariant ($contents -match '(?m)^  workflow_dispatch:$') `
    'Release candidate validation must be manual-only.'
Assert-WorkflowInvariant (-not ($contents -match '(?m)^  (push|pull_request|schedule):')) `
    'Release candidate validation must not run from push, pull request, or schedule events.'
Assert-WorkflowInvariant ($contents.Contains("permissions:`n  contents: read`n  pull-requests: read")) `
    'Top-level release candidate permissions must remain read-only.'

$writePermissions = @(
    [regex]::Matches($contents, '(?m)^\s+([a-z-]+): write\s*$') |
        ForEach-Object { $_.Groups[1].Value }
)
Assert-WorkflowInvariant ($writePermissions.Count -eq 1 -and $writePermissions[0] -eq 'security-events') `
    "Only CodeQL security-events may be writable; found: $($writePermissions -join ', ')"

$actionReferences = [regex]::Matches($contents, '(?m)^\s+(?:-\s+)?uses:\s+([^#\s]+)') |
    ForEach-Object { $_.Groups[1].Value }
$unpinnedActions = $actionReferences | Where-Object { $_ -notmatch '^[^@\s]+@[0-9a-f]{40}$' }
Assert-WorkflowInvariant ($actionReferences.Count -gt 0 -and $unpinnedActions.Count -eq 0) `
    "Every Action must use a full commit SHA; unpinned: $($unpinnedActions -join ', ')"

$checkoutCount = ($actionReferences | Where-Object { $_ -like 'actions/checkout@*' }).Count
$nonPersistentCount = [regex]::Matches($contents, '(?m)^\s+persist-credentials:\s+false\s*$').Count
$candidateRefCount = [regex]::Matches(
    $contents,
    '(?m)^\s+ref:\s+\$\{\{ needs\.validate_candidate\.outputs\.head_sha }}\s*$'
).Count
Assert-WorkflowInvariant ($checkoutCount -gt 0 -and $checkoutCount -eq $nonPersistentCount) `
    'Every release candidate checkout must disable persisted credentials.'
Assert-WorkflowInvariant ($checkoutCount -eq $candidateRefCount) `
    'Every release candidate checkout must use the validated immutable head SHA.'

Assert-WorkflowInvariant ($contents.Contains('[[ "$DISPATCH_SHA" == "$EXPECTED_HEAD_SHA" ]]')) `
    'The dispatch ref must resolve to the requested candidate SHA.'
Assert-WorkflowInvariant ($contents.Contains('[[ "$head_ref" == "release-please--branches--main" ]]')) `
    'The candidate must be the Release Please branch.'
Assert-WorkflowInvariant ($contents.Contains('[[ "$head_repo" == "$GITHUB_REPOSITORY" ]]')) `
    'The candidate must be a same-repository branch.'
Assert-WorkflowInvariant ($contents.Contains('[[ "$file_count" -eq 3 ]]')) `
    'The release candidate must require exactly three changed files.'
foreach ($allowedPath in @('.release-please-manifest.json', 'CHANGELOG.md', 'gradle.properties')) {
    $quotedPath = '"' + $allowedPath + '"'
    Assert-WorkflowInvariant ($contents.Contains($quotedPath)) `
        "The release candidate allowlist is missing: $allowedPath"
}
Assert-WorkflowInvariant ($contents.Contains("'any(.[]; .filename == `$required)'")) `
    'The release candidate must compare required paths as exact JSON strings.'

Assert-WorkflowInvariant ($contents.Contains('base-ref: ${{ needs.validate_candidate.outputs.base_sha }}')) `
    'Dependency Review must compare the validated base SHA.'
Assert-WorkflowInvariant ($contents.Contains('head-ref: ${{ needs.validate_candidate.outputs.head_sha }}')) `
    'Dependency Review must compare the validated head SHA.'
Assert-WorkflowInvariant ($contents.Contains('ref: refs/pull/${{ inputs.pr_number }}/head')) `
    'CodeQL results must be attributed to the Release PR head ref.'
Assert-WorkflowInvariant ($contents.Contains('sha: ${{ needs.validate_candidate.outputs.head_sha }}')) `
    'CodeQL results must be attributed to the validated head SHA.'

Assert-WorkflowInvariant ($contents.Contains('GITHUB_TOKEN: ${{ github.token }}')) `
    'Gitleaks may receive only the ephemeral GitHub token.'
Assert-WorkflowInvariant ($contents.Contains('GITLEAKS_ENABLE_COMMENTS: "false"')) `
    'Gitleaks PR comments must remain disabled.'
Assert-WorkflowInvariant (-not $contents.Contains('secrets.')) `
    'Release candidate validation must not reference repository secrets.'

$forbiddenPatterns = @(
    '(?m)^\s+packages:\s+write\s*$',
    '(?m)^\s+id-token:\s+write\s*$',
    '(?m)^\s+attestations:\s+write\s*$',
    'release-please-action@',
    'docker/login-action@',
    'docker/build-push-action@',
    '(?i)\bgh\s+release\b',
    '(?i)\bgit\s+tag\b'
)
foreach ($pattern in $forbiddenPatterns) {
    Assert-WorkflowInvariant (-not ($contents -match $pattern)) `
        "Release or publication capability is forbidden: $pattern"
}

Write-Output "Release candidate workflow security invariants verified: $resolvedWorkflow"
