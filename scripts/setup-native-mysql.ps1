param([string]$MySqlExe = 'C:\Program Files\MySQL\MySQL Server 8.4\bin\mysql.exe')

$ErrorActionPreference = 'Stop'
if (-not (Test-Path -LiteralPath $MySqlExe)) {
    $client = Get-Command mysql.exe -ErrorAction SilentlyContinue
    if ($client) { $MySqlExe = $client.Source }
    else { throw 'Native MySQL client not found. Install MySQL Server 8.4 with MySQL Configurator first.' }
}
. (Join-Path $PSScriptRoot 'local-env.ps1')
$values = Read-LocalEnv (Join-Path (Split-Path $PSScriptRoot -Parent) '.env')
if ($values['MYSQL_DATABASE'] -ne 'fraud_db' -or $values['MYSQL_USER'] -ne 'fraud_app') {
    throw 'This setup script requires MYSQL_DATABASE=fraud_db and MYSQL_USER=fraud_app in the root .env.'
}
$appPassword = $values['MYSQL_PASSWORD']
if ([string]::IsNullOrWhiteSpace($appPassword)) { throw 'Set MYSQL_PASSWORD in the root .env first.' }

function Invoke-LocalMySql {
    param([string]$User, [string]$Password, [string]$Sql)
    $info = New-Object Diagnostics.ProcessStartInfo
    $info.FileName = $MySqlExe
    $info.Arguments = "--no-defaults --protocol=TCP --host=127.0.0.1 --port=3306 --user=$User --batch --skip-column-names --connect-timeout=5"
    $info.UseShellExecute = $false
    $info.CreateNoWindow = $true
    $info.RedirectStandardInput = $true
    $info.RedirectStandardOutput = $true
    $info.RedirectStandardError = $true
    # Credentials exist only in this child process, not arguments or saved files.
    $info.EnvironmentVariables['MYSQL_PWD'] = $Password
    $process = New-Object Diagnostics.Process
    $process.StartInfo = $info
    try {
        [void]$process.Start()
        $stdout = $process.StandardOutput.ReadToEndAsync()
        $stderr = $process.StandardError.ReadToEndAsync()
        $process.StandardInput.WriteLine($Sql)
        $process.StandardInput.Close()
        $process.WaitForExit()
        [void]$stdout.GetAwaiter().GetResult()
        $errorText = $stderr.GetAwaiter().GetResult()
        if ($process.ExitCode -ne 0) {
            $code = if ($errorText -match 'ERROR\s+(\d+)') { $matches[1] } else { 'unknown' }
            throw "MySQL failed for $User (error $code). Check the service and credentials; an existing user's password is never reset."
        }
    } finally { $process.Dispose() }
}

$rootSecure = Read-Host 'MySQL root password (not saved)' -AsSecureString
$pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($rootSecure)
try {
    $rootPassword = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
    # NO_BACKSLASH_ESCAPES plus doubled quotes encodes literal passwords safely.
    $escapedPassword = $appPassword.Replace("'", "''")
    $sql = @"
SET SESSION sql_mode = 'NO_BACKSLASH_ESCAPES';
CREATE DATABASE IF NOT EXISTS fraud_db CHARACTER SET utf8mb4;
CREATE USER IF NOT EXISTS 'fraud_app'@'localhost' IDENTIFIED BY '$escapedPassword';
GRANT ALL PRIVILEGES ON fraud_db.* TO 'fraud_app'@'localhost';
"@
    Invoke-LocalMySql 'root' $rootPassword $sql
    Invoke-LocalMySql 'fraud_app' $appPassword 'USE fraud_db; SELECT 1;'
    Write-Output 'fraud_db and fraud_app are ready. Flyway will create application tables on backend startup.'
} finally {
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)
    $rootSecure.Dispose()
    $rootPassword = $null
    $sql = $null
    $escapedPassword = $null
}
