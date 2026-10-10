$ErrorActionPreference = 'Stop'
$path = Join-Path (Split-Path $PSScriptRoot -Parent) '.env.development'
if (Test-Path -LiteralPath $path) { 'Existing development admin configuration preserved.'; return }
$bytes = New-Object byte[] 24
$rng = [Security.Cryptography.RandomNumberGenerator]::Create()
try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
$password = [BitConverter]::ToString($bytes).Replace('-', '').ToLowerInvariant()
$lines = @('DEV_ADMIN_ENABLED=true', 'DEV_ADMIN_EMAIL=admin@ledgerguard.local', ('DEV_ADMIN_PASSWORD=' + $password))
$stream = [IO.File]::Open($path, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write)
$writer = New-Object IO.StreamWriter($stream, (New-Object Text.UTF8Encoding($false)))
try { foreach ($line in $lines) { $writer.WriteLine($line) } } finally { $writer.Dispose() }
'Development admin configuration saved privately in .env.development. Start with start-backend.ps1 -Dev.'
