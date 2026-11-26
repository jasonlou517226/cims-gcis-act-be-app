# cims-gcis-act-smoke-lambda

Java 版 Playwright 煙霧測試的 **AWS Lambda 移植版**：在 Lambda 上以 **Node.js 22 + `playwright-core`** 執行，瀏覽器使用 **`@sparticuz/chromium`**（專為無伺服器環境打造的 headless Chromium），以 **Lambda Layer** 掛載，由 **EventBridge 排程**觸發，部署工具為 **OSS-Serverless（`osls`，Serverless Framework v3 的分支）**。

Java 版（倉庫根目錄）保留供本機使用，兩者並存；本專案位於 `nodejs` 分支的 `lambda/`。

## 測試目標

名稱與 `run.ps1` 相同，一次 invocation 跑一個：

| Target | 內容 | 憑證環境變數 | 排程 |
| --- | --- | --- | --- |
| `portal` | School Portal：登入頁渲染、正確帳密登入、錯誤帳密被拒、Clear 清空欄位 | `TEST_USERNAME` / `TEST_PASSWORD` | ✅ |
| `booking` | Online Booking ／ Child Health Service：captcha 登入 + 16 步走訪（英文 → 繁體 → 简体 → 登出） | `OB_LOGIN` / `OB_PASSWORD` | ✅ |
| `stdhs` | Online Booking ／ Student Health Service：captcha 登入 + 步驟 17–34 | `STDHS_LOGIN` / `STDHS_PASSWORD` | ✅ |
| `pdqa` | Online Booking ／ Families Clinics：僅登入 | `PDQA_LOGIN` / `PDQA_PASSWORD` | ✅ |
| `upload` | School Portal：登入 → Upload Student List → 上傳 Excel → 確認儲存 → 刪除班別 | `TEST_USERNAME` / `TEST_PASSWORD` | ❌ 預設不排程 |

`upload` 會對入口網站**寫入資料**（結束後自行清除），所以預設只手動執行。

## 運作方式

```
EventBridge (cron) ──▶ Lambda "smoke"  {"target": "booking"}
                          │  憑證：Secrets Manager（啟動時注入，不進部署包）
                          │  瀏覽器：Chromium Layer (/opt/node_modules/@sparticuz/chromium)
                          ▼
                   playwright-core 跑旅程，逐項記錄 PASS / FAIL
                          │
             全部通過 ──▶ 正常結束
             任一失敗 ──▶ 拋出錯誤 ──▶ Lambda Errors 指標 ──▶ CloudWatch 告警 ──▶ SNS e-mail
```

## 目錄

```
lambda/
├── serverless.yml              # 函式、排程、Layer、IAM、告警（部署設定）
├── package.json                # 依賴與 npm scripts
├── .env.example                # 本機設定範本（複製為 .env，勿提交）
├── scripts/
│   ├── build-package-assets.mjs  # 建 Chromium Layer zip、複製上傳用 Excel
│   ├── build-function.mjs         # esbuild：src/handler.ts → dist/handler.js
│   └── install-browser.mjs        # 本機用 Chromium → .browsers/（見「已知限制」）
├── src/
│   ├── handler.ts              # Lambda 入口（handler: dist/handler.run）
│   ├── runner.ts               # 單一 target 的完整執行：憑證 → 瀏覽器 → 旅程 → 清理
│   ├── local.ts                # 本機 CLI（npm run local）
│   ├── config.ts               # 目標、憑證、執行選項、檔案路徑
│   ├── browser.ts              # 啟動 Chromium（Lambda：Layer；本機：playwright-core）
│   ├── report.ts               # 檢查記錄與摘要
│   ├── pages/
│   │   ├── school-portal.ts    # School Portal 登入 + Upload Student List（LoginPage.java）
│   │   └── online-booking.ts   # Online Booking 三個服務共用的頁面物件
│   └── journeys/
│       ├── portal.ts           # LoginTest 的 4 個測試
│       ├── upload.ts           # LoginPage.main() 的上傳旅程
│       ├── online-booking.ts   # captcha 登入 + 依服務分派
│       ├── booking.ts          # 步驟 1–16
│       └── stdhs.ts            # 步驟 17–34
└── test/                       # 單元測試（Node 內建測試框架，不需 jest）
```

倉庫根目錄 `.github/workflows/` 另有 GitHub Actions 設定（見下）。

## 環境需求

- **Node.js ≥ 22.17**（`@sparticuz/chromium` 與 `osls` 的要求，與 Lambda `nodejs22.x` 一致）
- `osls`：由 `npm ci` 安裝在本專案，不需全域安裝

## 本機執行

```powershell
cd lambda
npm ci                      # 安裝依賴；同時建好 Layer zip 與 handler bundle
npm run install-browser      # 一次性：下載本機用的 Chromium
Copy-Item .env.example .env # 填入要跑的目標的帳密

npm run local -- booking            # 無頭
npm run local -- portal -H -S       # 有頭（全螢幕）+ 慢速觀察
npm run local -- all                # portal + booking + stdhs + pdqa
npm run local -- upload             # 會寫入入口網站，請確認後再跑
```

- 目標：`portal`、`upload`、`booking`（別名 `ocr`）、`stdhs`、`pdqa`、`all`
- 選項：`-H` / `--headed`、`-S` / `--slowmo`、`--slowmo-ms N`、`--no-headed`、`--no-slowmo`（等同 `run.ps1` 的 `-NH` / `-NS`）、`--pause-ms N`、`-h`
- `portal` 與 `upload` 預設有頭 + 慢速，其餘預設無頭（與 `run.ps1` 一致）
- 任一目標失敗時結束碼為 1

```powershell
npm run typecheck           # TypeScript 型別檢查
npm test                    # 單元測試
```

## 設定

### 環境變數

| 變數 | 用於 | 說明 |
| --- | --- | --- |
| `TEST_USERNAME` / `TEST_PASSWORD` | portal、upload | School Portal 帳密 |
| `OB_LOGIN` / `OB_PASSWORD` | booking | Child Health Service 帳密 |
| `STDHS_LOGIN` / `STDHS_PASSWORD` | stdhs | Student Health Service 帳密 |
| `PDQA_LOGIN` / `PDQA_PASSWORD` | pdqa | Families Clinics 帳密 |
| `OB_ATTEMPTS` | Online Booking 登入 | captcha 嘗試次數，預設 5 |
| `UPLOAD_FILE` | upload | 覆蓋內建的上傳 Excel |
| `AWS_PROFILE`、`AWS_REGION`、`STAGE` | 部署 | 預設 `ap-east-1` / `dev` |
| `CREDENTIALS_SECRET_NAME` | 部署 | Secrets Manager secret 名稱（見下） |
| `SMOKE_SCHEDULE` | 部署 | 排程 cron（**UTC**），預設 `cron(0 18 * * ? *)`＝香港 02:00 |
| `DEFAULT_TARGET` | 部署 | 事件沒指定 target 時的預設，預設 `portal` |
| `ALARM_EMAIL` | 部署 | 失敗告警收件信箱；未設定則不建立訂閱 |
| `SERVICE_NAME` | 部署 | 服務名稱，預設 `cims-gcis-act-smoke` |

憑證一律來自環境變數（本機）或 Secrets Manager（Lambda），程式碼與部署包內**沒有任何帳密**；缺少時該目標立即失敗並指出缺少的變數名稱。

### Secrets Manager

一個 secret、一份 JSON、八個 key：

```json
{
  "portal_username": "...",
  "portal_password": "...",
  "ob_login": "...",
  "ob_password": "...",
  "stdhs_login": "...",
  "stdhs_password": "...",
  "pdqa_login": "...",
  "pdqa_password": "..."
}
```

```bash
aws secretsmanager create-secret --name cims/cims-gcis-act/smoke-credentials --secret-string file://credentials.json
```

Lambda 在函式啟動時透過執行角色讀取（`serverless.yml` 的 `Environment.Resources`），secret 的值不會出現在 CloudFormation 範本或部署包裡；secret 或任一 key 不存在時，函式無法啟動。執行角色只有該 secret 的 `secretsmanager:GetSecretValue` 與 CloudWatch Logs 權限。

## 部署

```powershell
cd lambda
npm ci
npx osls deploy --stage dev --region ap-east-1
```

部署內容：

| 項目 | 內容 | 大小 |
| --- | --- | --- |
| Lambda `…-dev-smoke` | `dist/handler.js`（我們的程式碼）、`node_modules/playwright-core`、`build/upload_excel/*.xlsx` | 解壓後約 13 MB |
| Layer `…-dev-chromium` | `node_modules/@sparticuz/chromium` 及其依賴 | 約 74 MB |
| EventBridge 規則 | 每個排程目標一條（`upload` 那條為 DISABLED） | — |
| 告警 | Lambda `Errors ≥ 1` → SNS（設定了 `ALARM_EMAIL` 才建立訂閱） | — |

兩個壓縮檔都在 `npm ci` 時由 npm 套件與倉庫內的 Excel 產生，不提交任何二進位檔。

手動執行一個目標（例如未排程的 `upload`）：

```powershell
# Windows PowerShell 5.1（內層雙引號需要以反斜線跳脫）
npx osls invoke --stage dev --region ap-east-1 --function smoke --log --data '{\"target\":\"upload\"}'
```
```bash
# bash
npx osls invoke --stage dev --region ap-east-1 --function smoke --log --data '{"target":"upload"}'
```

指令會等到旅程結束，印出每一項檢查與摘要；目標失敗時結束碼非 0。

### GitHub Actions

倉庫根目錄 `.github/workflows/`，步驟、輸入與 secrets 沿用 CDRA 的 `workflow-template-serverless`（僅參考，未修改該倉庫）：

| Workflow | 作用 |
| --- | --- |
| `development-workflow.yml` | push 到 `feature**` / `hotfix**` / `bugfix**` / `cht**` / `nodejs`：OSS 掃描 → SAST → 部署 dev → 送出部署紀錄 |
| `deploy-sls-workflow.yml` | 上面的部署步驟：`npm ci` → `osls deploy`（與 CDRA 版相同，只是加上 `working-directory: lambda`） |
| `run-smoke-test.yml` | 手動在 Lambda 上跑一個目標並顯示每項檢查 |

掃描步驟使用 CDRA 的可呼叫 workflow，只存在於 HA 的 Public GHES（`hagithub.ha.org.hk`）；掃描工具由內部 Artifactory 下載，連不到 Artifactory 的 runner 會在該步失敗。

## 與 Java 版的差異

| 項目 | Java 版 | 本專案 |
| --- | --- | --- |
| OCR 備援 | `captchaForACT` 缺少時退回 Tesseract / Vision | **沒有 OCR**；缺少 `captchaForACT` 時明確失敗並說明原因 |
| `all` | `LoginTest` + `OnlineBookingOcrLoginTest` | `portal` + `booking` + `stdhs` + `pdqa` |
| `booking` | `main()` 冒煙 + JUnit 各登入一次 | 登入一次 + 完整 16 步 |
| Student Health Service 步驟 30 | 路由字串誤植（`spa/spa/…`），該步必定失敗 | 已修正為 `/StdHS/spa/spa-wsmash/findingAndRecommendation` |
| 失敗時 | 測試失敗 | 拋出錯誤 → Lambda `Errors` → 告警 |

## 已知限制

- **無法在 Windows / macOS 本機重現 Lambda 的瀏覽器**：`@sparticuz/chromium` 只含 Linux 二進位檔。本機執行用的是 Playwright 自己的 Chromium；Layer 路徑、Node 版本與 Lambda 組態需部署後以 `osls invoke` 驗證。
- **本機瀏覽器目錄是 `lambda/.browsers/`，不是共享的 Playwright 快取**。原因：`playwright install` 會清除「沒有登錄安裝在使用」的瀏覽器，而 Java 專案（Playwright 1.54）不在登錄名單內，共用快取會互相刪除對方的 Chromium。請一律用 `npm run install-browser`，不要直接執行 `npx playwright-core install`。
- **與真人共用測試帳號**：排程登入時若同一帳號正被其他人使用，會互相取代連線。School Portal 的「another active session」對話框會自動繼續；建議排在非辦公時間。
- **cron 以 UTC 表示**。

## 疑難排解

| 訊息 | 原因 |
| --- | --- |
| `Missing credentials for target "…": set X and Y.` | 環境變數或 secret 的 key 缺少 |
| `Cannot load "@sparticuz/chromium" from the Chromium layer (/opt/node_modules)` | Layer 沒有附加到函式（或 Layer 版本與 `package.json` 的 `@sparticuz/chromium` 不一致） |
| `Landed on: #/notFound — the SPA itself gave up … Backend API errors seen: HTTP 500 …` | 受測網站的後端故障，不是測試的問題 |
| `Local Chromium is not installed — run "npm run install-browser" once.` | 本機尚未下載 Chromium |
| 部署時 `AWS provider credentials not found` | `osls` 需要 AWS 憑證（連 `osls package` 也會查詢既有堆疊） |
