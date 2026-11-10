# Runs the integration scenarios on real servers.
#
#   .\tools\run-integration.ps1 -Servers C:\testes\jars -Work C:\testes\work -Only paper-1.20.4,folia-26.3
#
# Each jar is expected at <Servers>\<type>-<version>.jar, as named below. The servers listen on 127.0.0.1
# only, in offline mode, and this script accepts the Minecraft EULA for these local test instances.
param(
    [Parameter(Mandatory = $true)][string]$Servers,
    [Parameter(Mandatory = $true)][string]$Work,
    [string[]]$Only = @(),
    [string]$Results = "$PSScriptRoot\..\target\integration",
    [string[]]$ExtraPlugins = @(),
    # PacketEvents constant of the version the bots pretend to be, to test ViaVersion. Example: V_1_20_4
    [string]$BotVersion = '',
    # Comma separated scenario names. Empty runs all of them.
    [string]$Scenarios = '',
    # Added to the result file name, to keep runs of the same server apart.
    [string]$Tag = ''
)

$java8 = 'C:\Program Files\Eclipse Adoptium\jdk-8.0.492.9-hotspot'
$java11 = 'C:\Program Files\Eclipse Adoptium\jdk-11.0.31.11-hotspot'
$java17 = 'C:\Program Files\Java\jdk-17'
$java21 = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.10.7-hotspot'
$java25 = 'C:\Program Files\Java\jdk-25.0.2'

# Name, Java required by that Minecraft version, PacketEvents constant of the version.
$matrix = @(
    @('paper-1.8.8', $java8, 'V_1_8_8'),
    @('paper-1.12.2', $java8, 'V_1_12_2'),
    @('paper-1.16.5', $java11, 'V_1_16_5'),
    @('paper-1.18.2', $java17, 'V_1_18_2'),
    @('paper-1.19.4', $java17, 'V_1_19_4'),
    @('paper-1.20.4', $java17, 'V_1_20_4'),
    @('paper-1.20.6', $java21, 'V_1_20_6'),
    @('paper-1.21.4', $java21, 'V_1_21_4'),
    @('paper-1.21.11', $java21, 'V_1_21_11'),
    @('paper-26.1.2', $java25, 'V_26_1_2'),
    @('paper-26.2', $java25, 'V_26_2'),
    @('paper-26.3', $java25, 'V_26_3'),
    @('folia-1.20.4', $java17, 'V_1_20_4'),
    @('folia-1.21.11', $java21, 'V_1_21_11'),
    @('folia-26.3', $java25, 'V_26_3'),
    @('purpur-1.16.5', $java11, 'V_1_16_5'),
    @('purpur-1.21.11', $java21, 'V_1_21_11'),
    @('purpur-26.3', $java25, 'V_26_3'),
    @('spigot-1.8.8', $java8, 'V_1_8_8'),
    @('spigot-1.21.11', $java21, 'V_1_21_11')
)

$root = Resolve-Path "$PSScriptRoot\.."
$classpathFile = Join-Path $root 'target\test-classpath.txt'
if (-not (Test-Path $classpathFile)) {
    throw "Rode antes: mvn package dependency:build-classpath '-Dmdep.outputFile=target/test-classpath.txt' '-Dmdep.includeScope=test'"
}
$classpath = "$root\target\test-classes;$root\target\classes;" + (Get-Content $classpathFile -Raw).Trim()
$plugin = Join-Path $root 'target\ShAntiCrash-Free.jar'
New-Item -ItemType Directory -Force $Results | Out-Null

$summary = @()
foreach ($entry in $matrix) {
    $name, $javaHome, $constant = $entry
    if ($Only.Count -gt 0 -and $Only -notcontains $name) { continue }
    $jar = Join-Path $Servers "$name.jar"
    if (-not (Test-Path $jar)) { continue }
    $dir = Join-Path $Work $name
    New-Item -ItemType Directory -Force $dir | Out-Null
    $label = "$name$Tag"
    $log = Join-Path $Results "$label.txt"
    Write-Output "==> $label"
    $properties = @()
    if ($BotVersion) { $properties += "-Dbot.version=$BotVersion" }
    if ($Scenarios) { $properties += "-Dbot.scenarios=$Scenarios" }
    $arguments = $properties + @('-cp', $classpath, 'br.com.singlehope.shanticrash.integration.IntegrationRunner', $jar, $javaHome, $dir, $plugin, $constant) + $ExtraPlugins
    & "$java21\bin\java.exe" @arguments 2>&1 | Tee-Object -FilePath $log | Where-Object { $_ -match '^(FAIL|RESULTADO|PLUGIN)' }
    $summary += "$label exit=$LASTEXITCODE"
    Copy-Item (Join-Path $dir 'logs\latest.log') (Join-Path $Results "$label.server.log") -ErrorAction SilentlyContinue
}
Write-Output ''
Write-Output 'Resumo:'
$summary | ForEach-Object { Write-Output $_ }
