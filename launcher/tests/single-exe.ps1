param([Parameter(Mandatory=$true)][string]$Executable)
$ErrorActionPreference='Stop'
$root=Join-Path ([IO.Path]::GetTempPath()) ('bscp-single-exe-'+[Guid]::NewGuid().ToString('N'))
$package=Join-Path $root 'package'
New-Item -ItemType Directory -Path $package,(Join-Path $root 'config') | Out-Null
Write-Output "Test profile: $root"
$binary=Join-Path $package 'BSCPCRAFT.exe'
Copy-Item -LiteralPath (Resolve-Path -LiteralPath $Executable).Path -Destination $binary
@{GameBranch='';SetupCompleted=$false;DataPath='GameData';FeedUrl=''} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $root 'config/launcher.json') -Encoding utf8NoBOM

function Invoke-Rpc([string]$command) {
    $info=[Diagnostics.ProcessStartInfo]::new($binary)
    $info.UseShellExecute=$false; $info.CreateNoWindow=$true
    $info.RedirectStandardOutput=$true; $info.RedirectStandardError=$true
    foreach($arg in @('--root',$root,'--rpc',$command)) {$info.ArgumentList.Add($arg)}
    $process=[Diagnostics.Process]::Start($info)
    $stdout=$process.StandardOutput.ReadToEndAsync();$stderr=$process.StandardError.ReadToEndAsync()
    if(-not $process.WaitForExit(15000)){throw 'RPC did not finish'}
    if($process.ExitCode -ne 0){throw $stderr.GetAwaiter().GetResult()}
    return ($stdout.GetAwaiter().GetResult() | ConvertFrom-Json)
}
function Get-OwnedHost {
    @(Get-CimInstance Win32_Process -Filter "Name='BSCPCRAFT.exe'" | Where-Object {
        $_.ExecutablePath -eq $binary -and $_.CommandLine.Contains('--host') -and $_.CommandLine.Contains($root)
    })
}
function Capture-Ui([string]$name) {
    $info=[Diagnostics.ProcessStartInfo]::new($binary)
    $info.UseShellExecute=$false;$info.CreateNoWindow=$true
    foreach($arg in @('--root',$root,'--capture',(Join-Path $root $name))) {$info.ArgumentList.Add($arg)}
    $ui=[Diagnostics.Process]::Start($info)
    if(-not $ui.WaitForExit(30000) -or $ui.ExitCode -ne 0){throw 'UI capture failed'}
    if((Get-Item -LiteralPath (Join-Path $root $name)).Length -lt 1000){throw 'UI frame missing'}
}
try {
    if(Test-Path -LiteralPath (Join-Path $package 'bscp-host.exe')){throw 'Unexpected separate host'}
    Capture-Ui 'first.ppm'
    $expected=(Get-FileHash -LiteralPath $binary -Algorithm SHA256).Hash.ToLowerInvariant()
    if((Invoke-Rpc 'status').HostBuild -ne $expected){throw 'Supervisor is not this executable'}
    $first=@(Get-OwnedHost)
    if($first.Count -ne 1){throw 'Expected one background process after UI exit'}
    Capture-Ui 'second.ppm'
    $second=@(Get-OwnedHost)
    if($second.Count -ne 1 -or $second[0].ProcessId -ne $first[0].ProcessId){throw 'Repeated UI spawned a duplicate host'}
    Write-Output 'Single EXE self-spawn, UI rendering, persistent background and reuse: PASS'
} finally {
    try {Invoke-Rpc 'shutdown' | Out-Null} catch {Write-Warning $_}
}
