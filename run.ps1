#
# run.ps1 - dhci2_auto_check one-click script (Windows PowerShell equivalent of run.sh)
#
# Usage:
#   .\run.ps1                     # default = booking (OnlineBookingLoginPage smoke x1 + OCR auto login)
#   .\run.ps1 booking             # OnlineBookingLoginPage standalone main() smoke (x1) + OCR captcha auto login
#   .\run.ps1 ocr                 # run OnlineBookingOcrLoginTest (OCR captcha auto login + post-login journey)
#   .\run.ps1 stdhs               # OnlineBookingLoginPageStdHS standalone main() (Student Health Service journey)
#   .\run.ps1 pdqa                # OnlineBookingLoginPagePDQA standalone main() (Families Clinics, login-only)
#   .\run.ps1 portal              # run LoginTest (School Portal, 4 methods)
#   .\run.ps1 upload              # LoginPage standalone main() (login → Upload Student List → upload Excel → confirm → delete classes)
#   .\run.ps1 all                 # run all test classes (LoginTest + OnlineBookingOcrLoginTest)
#   .\run.ps1 'portal#loginWithValidCredentials'  # run a single test method (quote the #)
#   .\run.ps1 install             # install Playwright Chromium browser (run once before first test)
#   .\run.ps1 -Help               # show this help
#
# OCR login credentials (either):
#   1. Environment variables OB_LOGIN / OB_PASSWORD  (e.g.  $env:OB_LOGIN='xxx'; $env:OB_PASSWORD='yyy'; .\run.ps1 ocr)
#   2. Built-in test account when not set
#   OCR captcha attempts: OB_ATTEMPTS (default 5; auto refresh captcha image and retry on OCR mistake)
#
# StdHS journey credentials (target: stdhs):
#   1. Environment variables STDHS_LOGIN / STDHS_PASSWORD
#   2. Built-in Student Health Service test account when not set
#
# PDQA login credentials (target: pdqa):
#   1. Environment variables PDQA_LOGIN / PDQA_PASSWORD
#   2. Built-in Families Clinics test account when not set
#
# Portal / upload credentials (targets: portal, upload):
#   1. Environment variables TEST_USERNAME / TEST_PASSWORD
#   2. Built-in School Portal test account when not set
#
# Upload Excel (target: upload) — bundled with the app:
#   default: upload_excel\for_load_test2.xlsx (found relative to the project root,
#   working-directory independent); override with env var UPLOAD_FILE
#
# Flags (can be combined with any target above):
#   -H / -S      headed mode (fullscreen browser) / slow motion (2s per action)
#   -NH / -NS    force headless / normal speed (portal & upload default to headed + slowmo)
#   Upload journey also pauses 2s after each step (-Dstep.pause.ms=N, 0 disables).
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
    Get-Content -LiteralPath $PSCommandPath | Select-Object -Skip 1 -First 29 | ForEach-Object { $_ -replace '^# ?', '' }
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
# Idempotent: strip truststore flags added by earlier runs before appending one
# clean copy. ($env:MAVEN_OPTS persists in the PowerShell session after this
# script ends, so plain appending snowballs it run after run until the cmd.exe
# 8191-char command-line limit is hit and java fails with a garbage main class.)
$env:MAVEN_OPTS = (@($env:MAVEN_OPTS -split ' ') |
    Where-Object { $_ -and $_ -notlike '-Djavax.net.ssl.trustStore*' }) -join ' '
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
if ($Target -like 'portal*' -or $Target -eq 'upload') {   # portal/upload default to headed + slowmo for visual inspection
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
    "-Dob.password=$(if ($env:OB_PASSWORD) { $env:OB_PASSWORD } else { 'Gold1234{}8' })",
    "-Dob.attempts=$(if ($env:OB_ATTEMPTS) { $env:OB_ATTEMPTS } else { 15 })"
)
$CredStdHS = @(
    "-Dob.login=$(if ($env:STDHS_LOGIN) { $env:STDHS_LOGIN } else { '7620550380' })",
    "-Dob.password=$(if ($env:STDHS_PASSWORD) { $env:STDHS_PASSWORD } else { 'Gold1234{}NN2' })",
    "-Dob.attempts=$(if ($env:OB_ATTEMPTS) { $env:OB_ATTEMPTS } else { 15 })"
)
$CredPDQA = @(
    "-Dob.login=$(if ($env:PDQA_LOGIN) { $env:PDQA_LOGIN } else { '7620552960' })",
    "-Dob.password=$(if ($env:PDQA_PASSWORD) { $env:PDQA_PASSWORD } else { 'Gold1234{}' })",
    "-Dob.attempts=$(if ($env:OB_ATTEMPTS) { $env:OB_ATTEMPTS } else { 15 })"
)

# ---------- Run ----------
switch -Regex ($Target) {
    '^install$' {
        Write-Host 'Installing Playwright Chromium browser...'
        & $MVN -B exec:java -e '-Dexec.mainClass=com.microsoft.playwright.CLI' '-Dexec.args=install chromium'
        exit $LASTEXITCODE
    }
    '^all$' {
        Write-Host 'Running all test classes (LoginTest + OnlineBookingOcrLoginTest)...'
        & $MVN -B test @Opts @Cred
        exit $LASTEXITCODE
    }
    '^booking$' {
        Write-Host 'Running OnlineBookingLoginPage standalone smoke + OCR auto login...'
        # 1) Direct local run of the page object's main() (no JUnit involved).
        & $MVN -B test-compile exec:java @Opts '-Dexec.mainClass=com.gcis.pages.OnlineBookingLoginPage' '-Dexec.classpathScope=test'
        if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
        # 2) OCR captcha auto login.
        & $MVN -B test @Opts @Cred '-Dtest=OnlineBookingOcrLoginTest'
        exit $LASTEXITCODE
    }
    '^stdhs$' {
        Write-Host 'Running OnlineBookingLoginPageStdHS standalone (Student Health Service, login + steps 17-34)...'
        # Direct local run of the StdHS page object main(): service selection →
        # login → English walk (17-22) → 繁體中文 walk (23-28) → 简体中文 walk (29-34).
        & $MVN -B test-compile exec:java @Opts @CredStdHS '-Dexec.mainClass=com.gcis.pages.OnlineBookingLoginPageStdHS' '-Dexec.classpathScope=test'
        exit $LASTEXITCODE
    }
    '^pdqa$' {
        Write-Host 'Running OnlineBookingLoginPagePDQA standalone (Families Clinics, login-only)...'
        # Direct local run of the PDQAS page object main(): service selection →
        # login (no JUnit involved).
        & $MVN -B test-compile exec:java @Opts @CredPDQA '-Dexec.mainClass=com.gcis.pages.OnlineBookingLoginPagePDQA' '-Dexec.classpathScope=test'
        exit $LASTEXITCODE
    }
    '^portal$' {
        Write-Host 'Running LoginTest...'
        & $MVN -B test @Opts '-Dtest=LoginTest'
        exit $LASTEXITCODE
    }
    '^upload$' {
        Write-Host 'Running LoginPage standalone (School Portal login → Upload Student List → upload Excel → confirm → delete uploaded classes)...'
        # Direct local run of the LoginPage main(): login at SchoolPortalWeb/login.htm →
        # session-conflict confirm → navigate to SchoolPortalTrust/upload.htm?sel=upload →
        # declaration checkbox → 瀏覽 (pick Excel) → 上載 → declaration → 確認及儲存 →
        # "Save Student List" Yes → delete classes under 已上載班別.
        $UploadFile = if ($env:UPLOAD_FILE) { @("-Dupload.file=$env:UPLOAD_FILE") } else { @() }
        & $MVN -B test-compile exec:java @Opts @UploadFile '-Dexec.mainClass=com.gcis.pages.LoginPage' '-Dexec.classpathScope=test'
        exit $LASTEXITCODE
    }
    '^ocr$' {
        Write-Host 'Running OnlineBookingOcrLoginTest (OCR captcha auto login + post-login journey)...'
        & $MVN -B test @Opts @Cred '-Dtest=OnlineBookingOcrLoginTest'
        exit $LASTEXITCODE
    }
    '^portal#' {
        Write-Host "Running single test method: $Target"
        & $MVN -B test @Opts "-Dtest=$Target"
        exit $LASTEXITCODE
    }
    default {
        Write-Error "Unknown target: $Target (available: all | booking | ocr | stdhs | pdqa | portal | upload | portal#method | install)"
        exit 1
    }
}