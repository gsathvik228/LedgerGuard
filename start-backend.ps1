param([switch]$CheckConfig, [switch]$Dev, [string]$EnvFile)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'scripts\local-env.ps1')
if ([string]::IsNullOrWhiteSpace($EnvFile)) {
    # An ignored runtime copy lets consolidation preserve both original secret files.
    $runtimeFile = Join-Path $PSScriptRoot '.local\runtime.env'
    $EnvFile = if (Test-Path -LiteralPath $runtimeFile) { $runtimeFile } else { Join-Path $PSScriptRoot '.env' }
} elseif (-not [IO.Path]::IsPathRooted($EnvFile)) {
    $EnvFile = Join-Path $PSScriptRoot $EnvFile
}
if (-not (Test-Path -LiteralPath $EnvFile)) { throw 'Configuration file not found. Run scripts\init-local-env.ps1 or supply -EnvFile.' }
$values = Read-LocalEnv $EnvFile
if ($Dev) {
    $runtimeDev = Join-Path $PSScriptRoot '.local\runtime-dev.env'
    $devFile = if (Test-Path -LiteralPath $runtimeDev) { $runtimeDev } else { Join-Path $PSScriptRoot '.env.development' }
    $devValues = Read-LocalEnv $devFile
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
