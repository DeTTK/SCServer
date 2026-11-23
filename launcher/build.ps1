param(
    [switch]$SkipBuild,
    [switch]$NoJava,
    [switch]$IncludeJava,
    [string]$OutputPath = "$PSScriptRoot\dist",
    [string]$JavaRoot = "$PSScriptRoot\..\artifacts\release\modules\java17",
    [string]$BridgeJar = ''
)
$ErrorActionPreference = 'Stop'
if (-not $SkipBuild) {
    Push-Location $PSScriptRoot
    try {
        & cargo build --locked --release --workspace -j 4
        if ($LASTEXITCODE -ne 0) { throw 'Rust release build failed.' }
    } finally { Pop-Location }
}
$output = [IO.Path]::GetFullPath($OutputPath)
New-Item -ItemType Directory -Path "$output\modules", "$output\licenses" -Force | Out-Null
if (Test-Path -LiteralPath "$output\bscp-host.exe") { throw 'Use a fresh output directory for the single-EXE launcher.' }
foreach ($exe in @('BSCPCRAFT.exe')) {
    Copy-Item -LiteralPath "$PSScriptRoot\target\release\$exe" -Destination "$output\$exe" -Force
}
if ([string]::IsNullOrWhiteSpace($BridgeJar)) {
    $BridgeJar = "$PSScriptRoot\artifacts\game-modules\bscp-bridge.jar"
    New-Item -ItemType Directory -Path "$PSScriptRoot\artifacts\game-modules" -Force | Out-Null
    $bridgeClasses = Join-Path "$PSScriptRoot\artifacts\bridge-classes" ([Guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $bridgeClasses -Force | Out-Null
    & javac -source 17 -target 17 --add-exports java.base/jdk.internal.org.objectweb.asm=ALL-UNNAMED -encoding UTF-8 -d $bridgeClasses (Get-ChildItem -LiteralPath "$PSScriptRoot\modules\bridge\src\bscp\next" -Filter *.java).FullName
    if ($LASTEXITCODE -ne 0) { throw 'Bridge compilation failed.' }
    & jar cfm $BridgeJar "$PSScriptRoot\modules\bridge\manifest.mf" -C $bridgeClasses .
    if ($LASTEXITCODE -ne 0) { throw 'Bridge archive failed.' }
}
if (-not (Test-Path -LiteralPath $BridgeJar -PathType Leaf)) { throw 'Missing explicit bridge archive.' }
Copy-Item -LiteralPath $BridgeJar -Destination "$output\modules\bscp-bridge.jar" -Force
if ($IncludeJava -and -not $NoJava) {
    if (-not (Test-Path -LiteralPath "$JavaRoot\bin\java.exe")) { throw "Missing Java game component: $JavaRoot" }
    New-Item -ItemType Directory -Path "$output\modules\java17" -Force | Out-Null
    Get-ChildItem -LiteralPath $JavaRoot -Force | Copy-Item -Destination "$output\modules\java17" -Recurse -Force
}
Copy-Item -LiteralPath "$PSScriptRoot\crates\launcher-app\ui\assets\RussoOne-OFL.txt" -Destination "$output\licenses" -Force
Copy-Item -LiteralPath "$PSScriptRoot\licenses\Slint-Royalty-Free-2.0.md" -Destination "$output\licenses" -Force
Copy-Item -LiteralPath "$PSScriptRoot\README.md" -Destination $output -Force
Push-Location $PSScriptRoot
try {
    $metadata = & cargo metadata --locked --format-version 1 2>$null | ConvertFrom-Json
    if ($LASTEXITCODE -ne 0) { throw 'Cargo license metadata failed.' }
    $linked = @(& cargo tree --locked --target x86_64-pc-windows-msvc --edges normal --prefix none --format '{p}' | ForEach-Object { ($_ -replace ' \(.*$', '').Trim() } | Sort-Object -Unique)
    if ($LASTEXITCODE -ne 0) { throw 'Cargo dependency tree failed.' }
    $notices = [Text.StringBuilder]::new()
    foreach ($package in $metadata.packages) {
        if ("$($package.name) v$($package.version)" -notin $linked) { continue }
        [void]$notices.AppendLine("=== $($package.name) $($package.version) | $($package.license) | $($package.repository) ===")
        $directory = Split-Path $package.manifest_path
        $licenseFiles = @(Get-ChildItem -LiteralPath $directory -File | Where-Object { $_.Name -match '^(LICENSE|LICENCE|COPYING|NOTICE)' })
        if ($package.license_file -and (Test-Path -LiteralPath (Join-Path $directory $package.license_file) -PathType Leaf)) {
            $licenseFiles += Get-Item -LiteralPath (Join-Path $directory $package.license_file)
        }
        foreach ($license in ($licenseFiles | Sort-Object FullName -Unique)) {
            [void]$notices.AppendLine($license.Name)
            [void]$notices.AppendLine([IO.File]::ReadAllText($license.FullName))
        }
    }
    [IO.File]::WriteAllText("$output\licenses\THIRD-PARTY-NOTICES.txt", $notices.ToString())
} finally { Pop-Location }
# Preserve portable config, worlds and existing user files. No cleanup or registry writes.
$files = @(Get-Item -LiteralPath "$output\BSCPCRAFT.exe", "$output\README.md")
$files += @(Get-ChildItem -LiteralPath "$output\modules", "$output\licenses" -Recurse -File)
$native = ($files | Where-Object { $_.Name -in @('BSCPCRAFT.exe') } | Measure-Object Length -Sum).Sum
$java = ($files | Where-Object { $_.FullName.StartsWith("$output\modules\java17\", [StringComparison]::OrdinalIgnoreCase) } | Measure-Object Length -Sum).Sum
[ordered]@{
    version = ($metadata.packages | Where-Object name -eq 'bscp-launcher-app' | Select-Object -First 1).version; nativeBytes = $native; javaBytes = $java
    totalBytes = ($files | Measure-Object Length -Sum).Sum
    dotnetRequired = $false; webviewRequired = $false
    files = @($files | ForEach-Object { [ordered]@{ path=$_.FullName.Substring($output.Length+1); bytes=$_.Length; sha256=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant() } })
} | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath "$output\build-manifest.json" -Encoding UTF8
Write-Host ('Native executables: {0:N2} MiB; Java game component: {1:N2} MiB' -f ($native/1MB), ($java/1MB))
Write-Host "Ready: $output\BSCPCRAFT.exe"
