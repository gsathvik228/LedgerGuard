param(
    [ValidateSet('Start','Stop','Host','Worker')][string]$Mode = 'Start',
    [ValidateSet('backend','frontend')][string]$Service,
    [string]$Token,
    [switch]$NoBrowser,
    [int]$TimeoutSeconds = 180
)

$ErrorActionPreference = 'Stop'
$appRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$stateRoot = Join-Path $appRoot '.local\launcher'
[IO.Directory]::CreateDirectory($stateRoot) | Out-Null
$scriptFile = Join-Path $PSScriptRoot 'app-launcher.ps1'

function State-Path([string]$Name) { Join-Path $stateRoot "$Name.json" }
function Read-State([string]$Name) {
    $path = State-Path $Name
    if (Test-Path -LiteralPath $path) {
        try { return [IO.File]::ReadAllText($path) | ConvertFrom-Json } catch { throw "Invalid launcher state: $path. No process was stopped." }
    }
}
function Is-OwnedHost($State) {
    if (!$State -or $State.token -notmatch '^[a-f0-9]{32}$') { return $false }
    try {
        $process = Get-Process -Id $State.pid -ErrorAction Stop
        $info = Get-CimInstance Win32_Process -Filter "ProcessId=$($process.Id)"
        return $process.StartTime.ToUniversalTime().Ticks.ToString() -eq $State.startedTicks -and
            $info.CommandLine.Contains($scriptFile) -and $info.CommandLine.Contains($State.token)
    } catch { return $false }
}
function Stop-Owned([string]$Name) {
    $state = Read-State $Name
    if (!$state) { return }
    if (Is-OwnedHost $state) {
        $signal = [Threading.EventWaitHandle]::OpenExisting("Local\LedgerGuard-$($state.token)")
        try { $signal.Set() | Out-Null } finally { $signal.Dispose() }
        $deadline = [DateTime]::UtcNow.AddSeconds(20)
        while ((Is-OwnedHost $state) -and [DateTime]::UtcNow -lt $deadline) { Start-Sleep -Milliseconds 200 }
        if (Is-OwnedHost $state) { throw "$Name supervisor did not exit. No unrelated processes were stopped." }
        Write-Output "Stopped launcher-owned $Name."
    } else { Write-Output "Discarded stale $Name tracking; no process was stopped." }
    # Only known files under this repository's ignored launcher directory.
    $current = Read-State $Name
    if ($current -and $current.token -eq $state.token) { Remove-Item -LiteralPath (State-Path $Name) }
}

if ($Mode -eq 'Worker') {
    Set-Location -LiteralPath $appRoot
    $log = Join-Path $stateRoot "$Service.log"
    if ($Service -eq 'backend') {
        # Keep private configuration loading in the existing launcher.
        if ((Test-Path -LiteralPath (Join-Path $appRoot '.env.development')) -or (Test-Path -LiteralPath (Join-Path $appRoot '.local\runtime-dev.env'))) {
            & (Join-Path $appRoot 'start-backend.ps1') -Dev *> $log
        } else { & (Join-Path $appRoot 'start-backend.ps1') *> $log }
    } else {
        Set-Location -LiteralPath (Join-Path $appRoot 'frontend')
        # Use the installed Vite executable directly, with its existing config.
        # Vite otherwise treats closed stdin in a hidden worker as a shutdown signal.
        $env:CI = 'true'
        & node.exe (Join-Path $appRoot 'frontend\node_modules\vite\bin\vite.js') *> $log
        if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    }
    exit 0
}

if ($Mode -eq 'Host') {
    if (!$Service -or $Token -notmatch '^[a-f0-9]{32}$') { throw 'Invalid supervisor arguments.' }
    # Start suspended, assign to a private Job Object, then resume. This avoids
    # the race where a child could spawn before being assigned to the job.
    Add-Type -TypeDefinition @'
using System;
using System.ComponentModel;
using System.Runtime.InteropServices;
public static class LedgerGuardJob {
 [StructLayout(LayoutKind.Sequential)] struct Basic { public long a,b; public uint flags; public UIntPtr min,max; public uint active; public UIntPtr affinity; public uint priority,scheduling; }
 [StructLayout(LayoutKind.Sequential)] struct Counters { public ulong a,b,c,d,e,f; }
 [StructLayout(LayoutKind.Sequential)] struct Limits { public Basic basic; public Counters io; public UIntPtr processMemory,jobMemory,peakProcess,peakJob; }
 [StructLayout(LayoutKind.Sequential,CharSet=CharSet.Unicode)] struct Startup { public int cb; public string reserved,desktop,title; public uint x,y,xSize,ySize,xChars,yChars,fill,flags; public short show,reservedSize; public IntPtr reservedPtr,input,output,error; }
 [StructLayout(LayoutKind.Sequential)] struct ProcessInfo { public IntPtr process,thread; public uint pid,tid; }
 [DllImport("kernel32.dll",SetLastError=true)] static extern IntPtr CreateJobObject(IntPtr attributes,string name);
 [DllImport("kernel32.dll",SetLastError=true)] static extern bool SetInformationJobObject(IntPtr job,int type,ref Limits info,int size);
 [DllImport("kernel32.dll",SetLastError=true)] static extern bool AssignProcessToJobObject(IntPtr job,IntPtr process);
 [DllImport("kernel32.dll",CharSet=CharSet.Unicode,SetLastError=true)] static extern bool CreateProcess(string application,System.Text.StringBuilder command,IntPtr pa,IntPtr ta,bool inherit,uint flags,IntPtr env,string cwd,ref Startup startup,out ProcessInfo info);
 [DllImport("kernel32.dll",SetLastError=true)] static extern uint ResumeThread(IntPtr thread);
 [DllImport("kernel32.dll")] static extern bool TerminateProcess(IntPtr process,uint code);
 [DllImport("kernel32.dll")] public static extern bool CloseHandle(IntPtr handle);
 public static IntPtr Start(string executable,string command,string cwd) {
   IntPtr job=CreateJobObject(IntPtr.Zero,null); if(job==IntPtr.Zero)throw new Win32Exception();
   ProcessInfo pi=new ProcessInfo();
   try {
     Limits limits=new Limits(); limits.basic.flags=0x2000; // KILL_ON_JOB_CLOSE
     if(!SetInformationJobObject(job,9,ref limits,Marshal.SizeOf(typeof(Limits))))throw new Win32Exception();
     Startup si=new Startup();si.cb=Marshal.SizeOf(typeof(Startup));si.flags=1;si.show=0;
     if(!CreateProcess(executable,new System.Text.StringBuilder(command),IntPtr.Zero,IntPtr.Zero,false,0x08000004,IntPtr.Zero,cwd,ref si,out pi))throw new Win32Exception();
     if(!AssignProcessToJobObject(job,pi.process))throw new Win32Exception();
     if(ResumeThread(pi.thread)==0xffffffff)throw new Win32Exception();
     return job;
   } catch { if(pi.process!=IntPtr.Zero)TerminateProcess(pi.process,1);CloseHandle(job);throw; }
   finally { if(pi.thread!=IntPtr.Zero)CloseHandle(pi.thread);if(pi.process!=IntPtr.Zero)CloseHandle(pi.process); }
 }
}
'@
    $signal = New-Object Threading.EventWaitHandle($false, [Threading.EventResetMode]::ManualReset, "Local\LedgerGuard-$Token")
    $job = [IntPtr]::Zero
    try {
        $exe = Join-Path $PSHOME 'powershell.exe'
        $command = '"{0}" -NoProfile -ExecutionPolicy Bypass -File "{1}" -Mode Worker -Service {2}' -f $exe,$scriptFile,$Service
        $job = [LedgerGuardJob]::Start($exe,$command,$appRoot)
        $state = @{ pid=$PID; startedTicks=(Get-Process -Id $PID).StartTime.ToUniversalTime().Ticks.ToString(); token=$Token }
        $temporary = Join-Path $stateRoot "$Service-$Token.tmp"
        [IO.File]::WriteAllText($temporary,($state | ConvertTo-Json))
        Move-Item -LiteralPath $temporary -Destination (State-Path $Service) -Force
        $signal.WaitOne() | Out-Null
    } finally {
        if ($job -ne [IntPtr]::Zero) { [LedgerGuardJob]::CloseHandle($job) | Out-Null }
        $signal.Dispose()
    }
    exit 0
}

function Listener([int]$Port) { @(Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue | Select-Object -ExpandProperty OwningProcess -Unique) }
function Project-Listener([string]$Name,[int]$Port) {
    $owners = @(Listener $Port)
    if (!$owners.Count) { return $false }
    foreach ($owner in $owners) {
        $info = Get-CimInstance Win32_Process -Filter "ProcessId=$owner"
        $command = $info.CommandLine
        $owned = $false
        $state = Read-State $Name
        $parent = $info
        $seen = @{}
        while ($parent -and !$seen.ContainsKey([string]$parent.ProcessId)) {
            $seen[[string]$parent.ProcessId] = $true
            if ($state -and $parent.ProcessId -eq $state.pid -and (Is-OwnedHost $state)) { $owned = $true; break }
            # Maven may launch Java using an argument file; identify the
            # project-specific Maven ancestor without reading private args.
            if ($Name -eq 'backend' -and $parent.CommandLine -and
                $parent.CommandLine.IndexOf($appRoot,[StringComparison]::OrdinalIgnoreCase) -ge 0 -and
                $parent.CommandLine -match 'MavenWrapperMain|start-backend\.ps1') { $owned = $true; break }
            if (!$parent.ParentProcessId) { break }
            $ancestor = Get-CimInstance Win32_Process -Filter "ProcessId=$($parent.ParentProcessId)"
            if (!$ancestor -or $ancestor.CreationDate -gt $parent.CreationDate) { break }
            $parent = $ancestor
        }
        if (!$owned -and (!$command -or $command.IndexOf($appRoot,[StringComparison]::OrdinalIgnoreCase) -lt 0 -or
            ($Name -eq 'backend' -and $command -notmatch 'com\.portfolio\.fraud\.FraudApplication|transaction-fraud-api') -or
            ($Name -eq 'frontend' -and $command -notmatch 'vite'))) {
            throw "Port $Port is occupied by an unrecognized process. Close or reconfigure that process yourself; it has not been stopped."
        }
    }
    return $true
}
function Http-Ready([string]$Name) {
    try {
        if ($Name -eq 'backend') {
            $r = Invoke-WebRequest 'http://127.0.0.1:8080/v3/api-docs' -UseBasicParsing -TimeoutSec 3
            return $r.StatusCode -eq 200 -and $r.Content.Contains('/api/transactions')
        }
        $r = Invoke-WebRequest 'http://127.0.0.1:5173/' -UseBasicParsing -TimeoutSec 3
        return $r.StatusCode -eq 200 -and $r.Content.Contains('LedgerGuard') -and $r.Content.Contains('/@vite/client')
    } catch { return $false }
}

$hash = [Security.Cryptography.SHA256]::Create()
try { $nameHash = [BitConverter]::ToString($hash.ComputeHash([Text.Encoding]::UTF8.GetBytes($appRoot.ToLowerInvariant()))).Replace('-','') } finally { $hash.Dispose() }
$mutex = New-Object Threading.Mutex($false,"Local\LedgerGuardLauncher-$nameHash")
$locked = $false
$started = @()
try {
    try { $locked = $mutex.WaitOne(0) } catch [Threading.AbandonedMutexException] { $locked = $true }
    if (!$locked) { throw 'Another LedgerGuard start/stop operation is in progress. Wait for it to finish, then retry.' }
    if ($Mode -eq 'Stop') {
        Stop-Owned 'frontend'
        Stop-Owned 'backend'
        Write-Output 'Launcher shutdown finished. Existing external servers, MySQL and unrelated processes were left running.'
    } else {
        # Check both ports before starting anything. Never take ownership of an existing listener.
        $existing = @{backend=(Project-Listener 'backend' 8080); frontend=(Project-Listener 'frontend' 5173)}
        foreach ($entry in @(@{name='backend';port=8080},@{name='frontend';port=5173})) {
            $serviceName = $entry.name
            if ($existing[$serviceName]) { Write-Output "Reusing existing $serviceName on port $($entry.port)."; continue }
            Stop-Owned $serviceName
            if ($serviceName -eq 'backend') {
                if ($env:JAVA_HOME) {
                    if (!(Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin\java.exe'))) { throw 'JAVA_HOME does not contain bin\java.exe. Correct it in this terminal, then retry.' }
                } else { Get-Command java.exe -ErrorAction Stop | Out-Null }
                $dev = (Test-Path -LiteralPath (Join-Path $appRoot '.env.development')) -or (Test-Path -LiteralPath (Join-Path $appRoot '.local\runtime-dev.env'))
                & (Join-Path $appRoot 'start-backend.ps1') -CheckConfig -Dev:$dev
            }
            if ($serviceName -eq 'frontend') {
                Get-Command node.exe -ErrorAction Stop | Out-Null
                if (!(Test-Path -LiteralPath (Join-Path $appRoot 'frontend\node_modules\vite\bin\vite.js'))) { throw 'Frontend dependencies are missing. Run npm.cmd --prefix frontend ci from the repository, then try again.' }
            }
            $newToken = [Guid]::NewGuid().ToString('N')
            $arguments = '-NoProfile -ExecutionPolicy Bypass -File "{0}" -Mode Host -Service {1} -Token {2}' -f $scriptFile,$serviceName,$newToken
            $hostProcess = Start-Process (Join-Path $PSHOME 'powershell.exe') -ArgumentList $arguments -WorkingDirectory $appRoot -WindowStyle Hidden -PassThru -RedirectStandardError (Join-Path $stateRoot "$serviceName-supervisor.log")
            $started += $serviceName
            $until = [DateTime]::UtcNow.AddSeconds(15)
            do {
                Start-Sleep -Milliseconds 200
                $state = Read-State $serviceName
                $hostProcess.Refresh()
                if ($hostProcess.HasExited) { throw "$serviceName supervisor exited. See .local\launcher\$serviceName-supervisor.log." }
            } until (($state -and $state.token -eq $newToken) -or [DateTime]::UtcNow -ge $until)
            if (!$state -or $state.token -ne $newToken) {
                # This exact Process object was created above; closing its job
                # also removes any children, including during initialization.
                $hostProcess.Kill()
                $hostProcess.WaitForExit()
                throw "$serviceName supervisor did not initialize. See .local\launcher logs."
            }
            Write-Output "Starting $serviceName; log: .local\launcher\$serviceName.log"
        }
        $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
        do {
            $backendReady = (Project-Listener 'backend' 8080) -and (Http-Ready 'backend')
            $frontendReady = (Project-Listener 'frontend' 5173) -and (Http-Ready 'frontend')
            if ($backendReady -and $frontendReady) { break }
            Start-Sleep -Milliseconds 750
        } while ([DateTime]::UtcNow -lt $deadline)
        if (!$backendReady -or !$frontendReady) { throw 'Startup timed out. Check .local\launcher logs and confirm native MySQL is running. No MySQL settings were changed.' }
        Write-Output 'LedgerGuard is ready: http://127.0.0.1:5173'
        if (!$NoBrowser) { Start-Process 'http://127.0.0.1:5173' }
    }
} catch {
    Write-Host $_.Exception.Message -ForegroundColor Red
    foreach ($owned in $started) { try { Stop-Owned $owned } catch { Write-Host "Could not finish cleanup for $owned. Run stop-app.cmd." -ForegroundColor Yellow } }
    exit 1
} finally {
    if ($locked) { $mutex.ReleaseMutex() }
    $mutex.Dispose()
}
