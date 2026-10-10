param([switch]$CheckConfig, [switch]$Dev)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'scripts\local-env.ps1')
$values = Read-LocalEnv (Join-Path $PSScriptRoot '.env')
if ($Dev) {
    $devValues = Read-LocalEnv (Join-Path $PSScriptRoot '.env.development')
    foreach ($name in $devValues.Keys) { $values[$name] = $devValues[$name] }
    $values['SPRING_PROFILES_ACTIVE'] = 'dev'
}
$loaded = @()
try {
    foreach ($name in $values.Keys) {
        # Existing process variables take precedence over the root .env.
        if ($null -eq [Environment]::GetEnvironmentVariable($name, 'Process')) {
            [Environment]::SetEnvironmentVariable($name, $values[$name], 'Process')
            $loaded += $name
        }
    }
    foreach ($name in @('MYSQL_PASSWORD', 'JWT_SECRET')) {
        if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($name, 'Process'))) {
            throw "Missing $name. Run scripts\init-local-env.ps1 or configure the root .env."
        }
    }
    if ($CheckConfig) {
        Write-Output 'Configuration parsed successfully (values hidden).'
        return
    }
    & (Join-Path $PSScriptRoot 'backend\mvnw.cmd') spring-boot:run
    if ($LASTEXITCODE -ne 0) { throw "Backend exited with code $LASTEXITCODE." }
} finally {
    foreach ($name in $loaded) {
        [Environment]::SetEnvironmentVariable($name, $null, 'Process')
    }
}
