param([string]$ServerJar='', [string]$DependencyPath='')
$ErrorActionPreference = 'Stop'
$classes = [IO.Path]::GetFullPath("$PSScriptRoot/../../artifacts/bridge-tests")
$repository = [IO.Path]::GetFullPath("$PSScriptRoot/../../..")
if (-not $ServerJar) {
    $ServerJar = Join-Path $repository 'build/server.jar'
    & python (Join-Path $repository 'scripts/build.py') server
    if ($LASTEXITCODE -ne 0) { throw 'Server source build failed' }
}
if (-not $DependencyPath) { $DependencyPath = Join-Path $repository '.local/deps' }
$serverClasspath = "$ServerJar;$DependencyPath/*"
New-Item -ItemType Directory -Path $classes -Force | Out-Null
$sources = @(Get-ChildItem -LiteralPath "$PSScriptRoot/src/bscp/next", "$PSScriptRoot/tests" -Filter *.java -File)
& javac -source 17 -target 17 --add-exports java.base/jdk.internal.org.objectweb.asm=ALL-UNNAMED -encoding UTF-8 -cp $serverClasspath -d $classes $sources.FullName
if ($LASTEXITCODE -ne 0) { throw 'Bridge test compilation failed' }
& java --add-exports java.base/jdk.internal.org.objectweb.asm=ALL-UNNAMED -cp $classes bscp.next.BridgeHooksTest $classes
if ($LASTEXITCODE -ne 0) { throw 'Bridge tests failed' }
$classpath = "$classes;$serverClasspath"
& java --add-exports java.base/jdk.internal.org.objectweb.asm=ALL-UNNAMED -cp $classpath bscp.next.RouteOriginTest
if ($LASTEXITCODE -ne 0) { throw 'Signed connection origin tests failed' }
$manifest = Join-Path $classes 'route-test-agent.mf'
[IO.File]::WriteAllText($manifest, "Premain-Class: bscp.next.RouteTestAgent`n`n")
$agent = Join-Path $classes 'route-test-agent.jar'
& jar cfm $agent $manifest -C $classes bscp/next/RouteTestAgent.class -C $classes 'bscp/next/RouteTestAgent$1.class'
if ($LASTEXITCODE -ne 0) { throw 'Test transformer archive failed' }
$fixture = Join-Path $classes ('route-origin-'+[Guid]::NewGuid().ToString('N'))
& java --add-exports java.base/jdk.internal.org.objectweb.asm=ALL-UNNAMED "-javaagent:$agent" -cp $classpath bscp.next.NativeRouteOriginTest "$fixture-managed"
if ($LASTEXITCODE -ne 0) { throw 'Managed backend connection origin tests failed' }
& java -cp $classpath bscp.next.NativeRouteOriginTest "$fixture-standalone"
if ($LASTEXITCODE -ne 0) { throw 'Standalone backend connection origin tests failed' }
