$ErrorActionPreference = 'Stop'
$envFile = Join-Path (Split-Path $PSScriptRoot -Parent) '.env'
if (Test-Path -LiteralPath $envFile) {
    Write-Output 'Existing .env preserved.'
    return
}
function New-LocalSecret {
    $bytes = New-Object byte[] 32
    $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
    return [BitConverter]::ToString($bytes).Replace('-', '').ToLowerInvariant()
}
$content = @(
    'MYSQL_DATABASE=fraud_db'
    'MYSQL_USER=fraud_app'
    ('MYSQL_PASSWORD=' + (New-LocalSecret))
    ('JWT_SECRET=' + (New-LocalSecret))
    'JWT_EXPIRATION_MS=3600000'
)
# CreateNew also prevents overwriting a concurrently created file.
$stream = [IO.File]::Open($envFile, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write)
$writer = New-Object IO.StreamWriter($stream, (New-Object Text.UTF8Encoding($false)))
try { foreach ($line in $content) { $writer.WriteLine($line) } }
finally { $writer.Dispose() }
Write-Output 'Created root .env with random local credentials (values hidden).'
