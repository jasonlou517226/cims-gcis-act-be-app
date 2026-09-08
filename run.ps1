#
# run.ps1 - dhci2_auto_check one-click script (Windows PowerShell equivalent of run.sh)
#
# Usage:
#   .\run.ps1                     # default = booking (page load check x1 + OCR auto login)
#   .\run.ps1 booking             # spaLoginPageLoadsSuccessfully (x1) + OCR captcha auto login
#   .\run.ps1 booking-all         # run OnlineBookingLoginTest all 5 methods + OCR auto login
#   .\run.ps1 ocr                 # run OnlineBookingOcrLoginTest only (OCR captcha auto login)
#   .\run.ps1 portal              # run LoginTest (School Portal, 4 methods)
#   .\run.ps1 all                 # run all test classes
#   .\run.ps1 'booking#loginFormReflectsInput'   # run a single test method (quote the #)
#   .\run.ps1 install             # install Playwright Chromium browser (run once before first test)
#   .\run.ps1 -Help               # show this help
#
# OCR login credentials (either):
#   1. Environment variables OB_LOGIN / OB_PASSWORD  (e.g.  $env:OB_LOGIN='xxx'; $env:OB_PASSWORD='yyy'; .\run.ps1 ocr)
#   2. Built-in test account when not set
#   OCR captcha attempts: OB_ATTEMPTS (default 5; auto refresh captcha image and retry on OCR mistake)
#
# Flags (can be combined with any target above):
#   -H / -S      headed mode (fullscreen browser) / slow motion (2s per action)
#   -NH / -NS    force headless / normal speed (portal defaults to headed + slowmo)
#
# Notes:
#   - portal target defaults to HEADED + SLOWMO so the page can be inspected visually.
#   - Maven autodetect: system mvn first, otherwise IntelliJ IDEA bundled Maven.
#   - JDK autodetect: JAVA_HOME/system java (17+) first, otherwise IntelliJ bundled JBR (21).
#   - If %USERPROFILE%\.m2\truststore.jks exists it is passed to Maven via MAVEN_OPTS
#     (corporate Artifactory SSL trust).
#   - Tesseract (OCR on Windows): if not on PATH but installed in C:\Program Files\Tesseract-OCR,
#     that folder is prepended to PATH automatically.
#
param(
    [Parameter(Position = 0)] [string]$Target = 'booking',
    [switch]$H,
    [switch]$S,
    [switch]$NH,
    [switch]$NS,
    [switch]$Help
)

$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot

if ($Help) {
    Get-Content -LiteralPath $PSCommandPath | Select-Object -Skip 1 -First 38 | ForEach-Object { $_ -replace '^# ?', '' }
    exit 0
}

# ---------- Locate Maven (system mvn preferred, else IntelliJ bundled Maven) ----------
$MVN = $null
if (Get-Command mvn -ErrorAction SilentlyContinue) {
    $MVN = 'mvn'
} else {
    $MVN = Get-ChildItem 'C:\Program Files\JetBrains\IntelliJ IDEA*\plugins\maven\lib\maven3\bin\mvn.cmd' -ErrorAction SilentlyContinue |
        Sort-Object FullName -Descending | Select-Object -First 1 -ExpandProperty FullName
}
if (-not $MVN) {
    Write-Error 'Maven not found: install Maven (winget install Apache.Maven) or install IntelliJ IDEA.'
    exit 1
}
Write-Host "Maven: $MVN"

# ---------- Locate JDK (17+): JAVA_HOME / system java, else IntelliJ bundled JBR ----------
$Jdk = $null
$candidates = @()
if ($env:JAVA_HOME) { $candidates += $env:JAVA_HOME }
$JavaCmd = Get-Command java -ErrorAction SilentlyContinue
if ($JavaCmd) {
    $src = $JavaCmd.Source
    $item = Get-Item $src
    if ($item.LinkType -and $item.Target) { $src = $item.Target }   # resolve Oracle javapath symlinks
    $candidates += $item.Directory.Parent.FullName
}
foreach ($c in ($candidates | Select-Object -Unique)) {
    $jj = Join-Path $c 'bin\java.exe'
    if (Test-Path $jj) {
        # major version from "java -version" output (e.g. 21.0.8 / 1.8.0)
        #$verOut = & $jj -version 2>&1 | Out-String
        $verOut = & cmd.exe /d /c "`"$jj`" -version 2>&1" | Out-String
        if ($verOut -match 'version "(\d+)(?:\.(\d+))?') {
            $major = [int]$Matches[1]
            if ($major -eq 1) { $major = [int]$Matches[2] }   # legacy 1.8.0 scheme
            if ($major -ge 17) { $Jdk = $c; break }
        }
    }
}
if (-not $Jdk) {
    $Jbr = Get-ChildItem 'C:\Program Files\JetBrains\IntelliJ IDEA*\jbr' -Directory -ErrorAction SilentlyContinue |
        Sort-Object FullName -Descending | Select-Object -First 1
    if ($Jbr -and (Test-Path (Join-Path $Jbr.FullName 'bin\java.exe'))) { $Jdk = $Jbr.FullName }
}
if (-not $Jdk) {
    Write-Error 'JDK 17+ not found: set JAVA_HOME, or install Temurin 21 (winget install EclipseAdoptium.Temurin.21.JDK) / IntelliJ IDEA.'
    exit 1
}
$env:JAVA_HOME = $Jdk
Write-Host "JAVA_HOME: $Jdk"

# ---------- Corporate Artifactory truststore (see README - Windows section) ----------
$TrustStore = Join-Path $env:USERPROFILE '.m2\truststore.jks'
if (Test-Path $TrustStore) {
    $env:MAVEN_OPTS = (("$env:MAVEN_OPTS" + " -Djavax.net.ssl.trustStore=`"$TrustStore`" -Djavax.net.ssl.trustStorePassword=changeit").Trim())
    Write-Host "MAVEN_OPTS: $env:MAVEN_OPTS"
}

# ---------- Make Tesseract visible for the OCR test (Windows backend) ----------
if (-not (Get-Command tesseract -ErrorAction SilentlyContinue)) {
    $tess = 'C:\Program Files\Tesseract-OCR\tesseract.exe'
    if (Test-Path $tess) { $env:PATH = "C:\Program Files\Tesseract-OCR;$env:PATH"; Write-Host 'Tesseract: added C:\Program Files\Tesseract-OCR to PATH' }
}

# ---------- Resolve flags ----------
$Headed = if ($H) { 'true' } elseif ($NH) { 'false' } else { $null }
$Slowmo = if ($S) { 'true' } elseif ($NS) { 'false' } else { $null }
if ($Target -like 'portal*') {          # portal defaults to headed + slowmo for visual inspection
    if (-not $Headed) { $Headed = 'true' }
    if (-not $Slowmo) { $Slowmo = 'true' }
}
if (-not $Headed) { $Headed = 'false' }
if (-not $Slowmo) { $Slowmo = 'false' }
if ($Headed -eq 'true') { Write-Host 'Headed mode (fullscreen)' }
if ($Slowmo -eq 'true') { Write-Host 'Slow motion (2s per action)' }

$Opts = @("-Dheaded=$Headed", "-Dslowmo=$Slowmo")
$Cred = @(
    "-Dob.login=$(if ($env:OB_LOGIN) { $env:OB_LOGIN } else { '1064984038' })",
    "-Dob.password=$(if ($env:OB_PASSWORD) { $env:OB_PASSWORD } else { 'Gold1234{}7' })",
    "-Dob.attempts=$(if ($env:OB_ATTEMPTS) { $env:OB_ATTEMPTS } else { 5 })"
)

# ---------- Run ----------
switch -Regex ($Target) {
    '^install$' {
        Write-Host 'Installing Playwright Chromium browser...'
        & $MVN -B exec:java -e '-Dexec.mainClass=com.microsoft.playwright.CLI' '-Dexec.args=install chromium'
        exit $LASTEXITCODE
    }
    '^all$' {
        Write-Host 'Running all tests (3 test classes)...'
        & $MVN -B test @Opts @Cred
        exit $LASTEXITCODE
    }
    '^booking$' {
        Write-Host 'Running spaLoginPageLoadsSuccessfully (x1) + OCR auto login...'
        & $MVN -B test @Opts @Cred '-Dtest=OnlineBookingLoginTest#spaLoginPageLoadsSuccessfully,OnlineBookingOcrLoginTest'
        exit $LASTEXITCODE
    }
    '^booking-all$' {
        Write-Host 'Running OnlineBookingLoginTest (5 methods) + OCR auto login...'
        & $MVN -B test @Opts @Cred '-Dtest=OnlineBookingLoginTest,OnlineBookingOcrLoginTest'
        exit $LASTEXITCODE
    }
    '^portal$' {
        Write-Host 'Running LoginTest...'
        & $MVN -B test @Opts '-Dtest=LoginTest'
        exit $LASTEXITCODE
    }
    '^ocr$' {
        Write-Host 'Running OnlineBookingOcrLoginTest (OCR captcha auto login)...'
        & $MVN -B test @Opts @Cred '-Dtest=OnlineBookingOcrLoginTest'
        exit $LASTEXITCODE
    }
    '^(booking|portal)#' {
        $Class, $Method = $Target -split '#', 2
        $TestClass = if ($Class -eq 'booking') { 'OnlineBookingLoginTest' } else { 'LoginTest' }
        Write-Host "Running single test method: $TestClass#$Method"
        & $MVN -B test @Opts "-Dtest=$TestClass#$Method"
        exit $LASTEXITCODE
    }
    default {
        Write-Error "Unknown target: $Target (available: all | booking | booking-all | portal | ocr | booking#method | portal#method | install)"
        exit 1
    }
}