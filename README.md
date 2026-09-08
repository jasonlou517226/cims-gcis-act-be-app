# dhci2_auto_check

以 **Java + Playwright + JUnit 5** 建立的網站自動化測試專案（依據 [Playwright for Java 官方文件](https://playwright.dev/java/docs/intro)）。

## 測試目標

### 1. School Portal（傳統 JSP 網站）

| 項目 | 內容 |
| --- | --- |
| 登入頁網址 | https://www1.dhsisp.gov.hk/SchoolPortalWeb/login.htm |
| 帳號 | `ehr-test` |
| 密碼 | `Sira!!2117` |

### 2. DH Online Booking SPA（React 單頁應用）

| 項目 | 內容 |
| --- | --- |
| 登入頁網址 | https://www.clinical.dh.gov.hk/OnlineBookingWeb/#/FHS-CH/login |
| 測試重點 | SPA 可載入渲染、後端 API（`siteParams/map`、`generateCaptcha/image`）健康檢查、錯誤 captcha 被正常拒絕、**OCR 全自動登入** |

> Online Booking 登入需要圖形驗證碼（CAPTCHA）。除健康檢查外，本專案另提供 `OnlineBookingOcrLoginTest`：攔截 captcha 圖片後以 **OCR**（macOS 用 Vision framework、Windows/Linux 用 Tesseract，依平台自動選擇）辨識，自動填入並完成真實登入；OCR 失敗時自動換圖重試（`OB_ATTEMPTS` 環境變數控制，預設 5 次）。

## 專案結構

```
dhci2_auto_check/
├── pom.xml                                     # Maven 設定（Playwright、JUnit 5）
├── run.sh                                      # 一鍵執行腳本（macOS/Linux，自動偵測 Maven）
├── run.ps1                                     # 一鍵執行腳本（Windows PowerShell，自動偵測 Maven/JDK/Tesseract）
├── tools/
│   └── captcha-ocr.swift                       # macOS Vision OCR CLI（swiftc 編譯）
└── src/test/java/com/dhci2/
    ├── pages/
    │   ├── LoginPage.java                      # School Portal 登入頁 Page Object
    │   └── OnlineBookingLoginPage.java         # Online Booking SPA 登入頁 Page Object（含 captcha 攔截）
    ├── support/
    │   └── CaptchaOcr.java                     # 呼叫 macOS Vision OCR 的 captcha 辨識器
    └── tests/
        ├── TestBase.java                       # Playwright 生命週期管理（Base 類別）
        ├── LoginTest.java                      # School Portal 登入測試
        ├── OnlineBookingLoginTest.java         # Online Booking SPA 健康檢查測試
        └── OnlineBookingOcrLoginTest.java      # OCR 破解 captcha 全自動登入測試
```

## 環境需求

- JDK 17 以上（Windows 可直接使用 IntelliJ IDEA 內建 JBR 21，免另外安裝）
- Maven 3.6+（若無 Maven，IntelliJ IDEA 內建 Maven 亦可）
- OCR 後端（依平台自動偵測，可用 `-Docr.engine=vision|tesseract` 覆寫）：
  - macOS → Vision framework（需 Xcode command line tools / `swiftc`）
  - Windows / Linux → Tesseract OCR CLI（`tesseract` 需在 PATH 上）

## Windows 11 本機設置（一次性）

1. **JDK / Maven**：可不另外安裝 — `run.ps1` 會自動偵測 IntelliJ IDEA 內建 Maven 與 JBR 21；若要獨立安裝可執行 `winget install EclipseAdoptium.Temurin.21.JDK` 與 `winget install Apache.Maven`。
2. **公司 Artifactory SSL 憑證**（`~\.m2\settings.xml` 指向 `https://artifactrepo:55743`，Java 預設不信任企業 CA，需匯入 truststore）：

   ```powershell
   $jbr = (Get-ChildItem 'C:\Program Files\JetBrains\IntelliJ IDEA*\jbr' | Sort-Object FullName -Descending | Select-Object -First 1).FullName
   $kt  = "$jbr\bin\keytool.exe"
   & $kt -printcert -sslserver artifactrepo:55743 -rfc | Out-File "$env:USERPROFILE\.m2\artifactory-chain.pem" -Encoding ascii
   Copy-Item "$jbr\lib\security\cacerts" "$env:USERPROFILE\.m2\truststore.jks" -Force
   $raw = Get-Content "$env:USERPROFILE\.m2\artifactory-chain.pem" -Raw
   $i = 0
   [regex]::Matches($raw, '-----BEGIN CERTIFICATE-----.+?-----END CERTIFICATE-----', 'Singleline') | ForEach-Object {
       $i++
       $f = "$env:USERPROFILE\.m2\artifactory-cert-$i.pem"
       $_.Value | Out-File $f -Encoding ascii
       & $kt -importcert -noprompt -alias "artifactory-$i" -file $f -keystore "$env:USERPROFILE\.m2\truststore.jks" -storepass changeit
   }
   ```

   之後 `run.ps1` 偵測到 `~\.m2\truststore.jks` 會自動透過 `MAVEN_OPTS` 套用（亦適用其他 JDK 的 `lib\security\cacerts` 來源）。
3. **Tesseract（Windows OCR 後端）**：`winget install -e --id UB-Mannheim.TesseractOCR`（安裝於 `C:\Program Files\Tesseract-OCR`；`run.ps1` 會自動加入當前 session 的 PATH）。
4. **Playwright 瀏覽器**：`.\run.ps1 install`（首次執行測試前需做一次）。

## 快速開始

### 0. 最簡單的方式：使用 `run.sh` 一鍵腳本（推薦）

```bash
./run.sh                     # 預設 = booking（頁面載入檢查 1 次 + OCR 全自動登入）
./run.sh booking             # spaLoginPageLoadsSuccessfully（1 次）+ OCR 破解 captcha 全自動登入
./run.sh booking-all         # 執行 OnlineBookingLoginTest 全部 5 個測試方法 + OCR 全自動登入
./run.sh ocr                 # 只執行 OCR 破解 captcha 全自動登入（OnlineBookingOcrLoginTest）
./run.sh portal              # 執行 LoginTest（School Portal）— 預設即有頭（全螢幕）+ 慢速（每步 2 秒）
./run.sh portal -NH          # portal 以無頭模式執行（關閉有頭預設）
./run.sh portal -NS          # portal 以正常速度執行（關閉慢速預設）
./run.sh all                 # 執行全部測試類（LoginTest + OnlineBookingLoginTest + OnlineBookingOcrLoginTest）
./run.sh install             # 安裝 Playwright Chromium 瀏覽器（首次執行前需做一次）
./run.sh booking -H -S       # 全螢幕 + 慢速（每步 2 秒）觀察頁面檢查 + OCR 全自動登入（試 5 次）
./run.sh booking#loginFormReflectsInput        # 執行指定單一測試方法
./run.sh -h                  # 顯示完整說明
```

### 0-1. Windows：使用 `run.ps1`（PowerShell 對應腳本）

```powershell
.\run.ps1                     # 預設 = booking（頁面載入檢查 1 次 + OCR 全自動登入）
.\run.ps1 booking-all         # OnlineBookingLoginTest 全部 5 個方法 + OCR 全自動登入
.\run.ps1 ocr                 # 只執行 OCR 全自動登入（Windows 用 Tesseract）
.\run.ps1 portal              # LoginTest（預設有頭 + 慢速，方便觀察）
.\run.ps1 portal -NH -NS      # LoginTest（無頭 + 正常速度）
.\run.ps1 booking -H -S       # 全螢幕 + 慢速觀察
.\run.ps1 'booking#loginFormReflectsInput'   # 單一測試方法（# 在 PowerShell 需加引號）
.\run.ps1 install             # 安裝 Playwright Chromium 瀏覽器（首次執行前需做一次）
```

OCR 測試帳密（擇一）：

```bash
OB_LOGIN=你的帳號 OB_PASSWORD=你的密碼 ./run.sh ocr   # 環境變數（建議）
./run.sh ocr                                          # 內建測試帳號
```

腳本會自動偵測 Maven（系統 `mvn` 優先，否則使用 IntelliJ 內建 Maven），無需手動設定。

### 1. 安裝 Playwright 瀏覽器（首次執行前需做一次）

```bash
mvn exec:java -e -D exec.mainClass=com.microsoft.playwright.CLI -D exec.args="install chromium"
```

> 若使用 IntelliJ 內建 Maven，可執行：
> ```bash
> "/Applications/IntelliJ IDEA.app/Contents/plugins/maven/lib/maven3/bin/mvn" \
>   exec:java -e -D exec.mainClass=com.microsoft.playwright.CLI -D exec.args="install chromium"
> ```

### 2. 執行全部測試

```bash
mvn test
```

### 3. 常用選項

| 選項 | 說明 |
| --- | --- |
| `-Dheaded=true` | 以有頭模式執行，瀏覽器以全螢幕開啟 |
| `-Dslowmo=true` | 每個操作停留 2 秒（可用 `-Dslowmo.ms=N` 自訂），方便肉眼觀察 |
| `-Dtest=LoginTest#loginWithValidCredentials` | 只執行單一測試方法 |
| `-Dob.login=... -Dob.password=...` | OCR 測試帳密（未提供時自動略過） |

例如以有頭 + 慢速模式執行登入成功測試：

```bash
mvn test -Dheaded=true -Dslowmo=true -Dtest=LoginTest#loginWithValidCredentials
```

## 測試案例說明

### LoginTest（School Portal）

| 測試 | 說明 |
| --- | --- |
| `loginPageLoadsSuccessfully` | 登入頁可正常載入，表單元素（帳號/密碼/Submit）皆顯示 |
| `loginWithValidCredentials` | 使用正確帳密登入，應導向 `SchoolPortalTrust/post_login_Action.action`（Login Success 頁面） |
| `loginWithInvalidCredentials` | 使用錯誤密碼登入，應停留在登入頁或顯示錯誤訊息（若頁面中途被關閉／自行關閉，測試具容錯不誤判） |
| `clearButtonEmptiesFields` | 點擊 Clear 按鈕應清空帳號與密碼欄位 |

### OnlineBookingLoginTest（Online Booking SPA）

| 測試 | 說明 |
| --- | --- |
| `spaLoginPageLoadsSuccessfully` | SPA 登入路由可載入並渲染表單（帳號/密碼/captcha/登入按鈕） |
| `siteParamsApiIsHealthy` | 後端 `siteParams/map` API 回傳 HTTP 200 與站點參數清單 |
| `captchaApiIsHealthy` | 後端 `generateCaptcha/image` API 回傳 HTTP 200 與 base64 圖片 |
| `wrongCaptchaIsRejectedGracefully` | 輸入錯誤 captcha 應顯示「Verification code is incorrect」且停留在登入頁 |
| `loginFormReflectsInput` | 表單欄位可正常輸入並反映輸入值 |

### OnlineBookingOcrLoginTest（Online Booking SPA — 全自動登入）

| 測試 | 說明 |
| --- | --- |
| `loginWithOcrCaptcha` | 攔截 `generateCaptcha/image` 回應 → 以 macOS Vision OCR 辨識（多變體放大 + 正規化取最佳候選）→ 自動填入並登入；captcha 遭拒（respCode 113）自動換圖重試，成功後應離開登入頁進入 `#/FHS-CH` |

執行方式（帳密未提供時測試會自動略過）：

```bash
mvn test -Dtest=OnlineBookingOcrLoginTest -Dob.login=你的帳號 -Dob.password=你的密碼
```

## 注意事項

- 登入頁為舊式政府網站，測試已設定 `ignoreHTTPSErrors` 以避免 HTTPS 憑證問題。
- 登入成功後會導向 `SchoolPortalTrust/post_login_Action.action`（標題為 `Department of Health - Login Success`），測試以離開 `SchoolPortalWeb/login` 頁面作為成功判斷。
- 若同一帳號已在其他裝置登入，網站會彈出「There is another active session for the same User Name」對話框，測試會自動點擊「I understood and wanted to proceed with logging in here」繼續登入。
- 帳密目前寫在 `LoginTest.java` 中；若要上 CI，建議改用環境變數（`TEST_USERNAME` / `TEST_PASSWORD`）。
- Online Booking SPA 為 React 應用（hash routing），測試需等待 React 渲染完成後才進行元素斷言；後端 API 檢查透過 `page.request()` 以瀏覽器 context 呼叫，藉此沿用網站的連線與憑證設定。
- **OCR 後端**：macOS 使用 Vision framework（首次使用需以 `swiftc tools/captcha-ocr.swift -o tools/captcha-ocr` 編譯）；Windows/Linux 使用 Tesseract CLI（Windows 安裝：`winget install -e --id UB-Mannheim.TesseractOCR`）。`CaptchaOcr` 依平台自動選擇（可用 `-Docr.engine=vision|tesseract` 覆寫）。captcha 錯誤（respCode 113）不計入帳號鎖定計數，重試安全。