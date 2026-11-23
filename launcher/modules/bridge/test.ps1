$ErrorActionPreference = 'Stop'
$classes = [IO.Path]::GetFullPath("$PSScriptRoot/../../artifacts/bridge-tests")
New-Item -ItemType Directory -Path $classes -Force | Out-Null
$sources = @(Get-ChildItem -LiteralPath "$PSScriptRoot/src/bscp/next", "$PSScriptRoot/tests" -Filter *.java -File)
& javac -source 17 -target 17 --add-exports java.base/jdk.internal.org.objectweb.asm=ALL-UNNAMED -encoding UTF-8 -d $classes $sources.FullName
if ($LASTEXITCODE -ne 0) { throw 'Bridge test compilation failed' }
& java --add-exports java.base/jdk.internal.org.objectweb.asm=ALL-UNNAMED -cp $classes bscp.next.BridgeHooksTest $classes
if ($LASTEXITCODE -ne 0) { throw 'Bridge tests failed' }
