param([string]$JavaHome = 'C:\Program Files\Java\jdk-25.0.3')
$ErrorActionPreference = 'Stop'
$env:JAVA_HOME = $JavaHome
$env:Path = "$JavaHome\bin;$env:Path"
Push-Location $PSScriptRoot
try {
    & mvn.cmd -B clean package
    if ($LASTEXITCODE -ne 0) { throw 'Сборка Maven завершилась ошибкой' }
    $version = '1.0.0'
    $jar = "AndromedaTopSync-$version.jar"
    New-Item -ItemType Directory -Force releases | Out-Null
    Copy-Item -LiteralPath "target/$jar" -Destination "releases/$jar"
    if (Test-Path -LiteralPath 'E:\') {
        $localBuild = 'E:\AndromedaDev\Builds\AndromedaTopSync'
        New-Item -ItemType Directory -Force $localBuild | Out-Null
        Copy-Item -LiteralPath "target/$jar" -Destination $localBuild
    } else { Write-Warning 'Диск E: отсутствует; сборка сохранена в releases/.' }
    Get-FileHash -Algorithm SHA256 -LiteralPath "releases/$jar"
} finally { Pop-Location }
