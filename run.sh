#!/usr/bin/env bash
#
# run.sh — dhci2_auto_check 一鍵執行腳本
#
# 用法:
#   ./run.sh                     # 預設 = booking（頁面載入檢查 1 次 + OCR 全自動登入）
#   ./run.sh booking             # spaLoginPageLoadsSuccessfully（1 次）+ OCR 破解 captcha 全自動登入
#   ./run.sh booking-all         # 執行 OnlineBookingLoginTest 全部 5 個測試方法 + OCR 全自動登入
#   ./run.sh ocr                 # 只執行 OnlineBookingOcrLoginTest（OCR 破解 captcha 全自動登入）
#   ./run.sh portal              # 執行 LoginTest（School Portal，4 個測試方法）
#   ./run.sh all                 # 執行全部測試類（LoginTest + OnlineBookingLoginTest + OnlineBookingOcrLoginTest）
#   ./run.sh booking#loginFormReflectsInput        # 執行指定單一測試方法
#   ./run.sh install             # 安裝 Playwright Chromium 瀏覽器（首次執行前需做一次）
#   ./run.sh -h                  # 顯示說明
#
# OCR 登入測試帳密來源（擇一）:
#   1. 環境變數 OB_LOGIN / OB_PASSWORD（如: OB_LOGIN=xxx OB_PASSWORD=yyy ./run.sh ocr）
#
# OCR captcha 嘗試次數：OB_ATTEMPTS=N（預設 5；OCR 讀錯自動換圖重試）
#   2. 未設定時使用內建測試帳號
#
# 旗標（可與上述任一目標合併使用）:
#   -H, --headed                 有頭模式（瀏覽器以全螢幕開啟，方便觀察）
#   -S, --slowmo                 每個操作停留 2 秒，方便肉眼觀察
#   -NH, --no-headed             強制關閉有頭模式（portal 預設開啟時用）
#   -NS, --no-slowmo             強制關閉慢速模式（portal 預設開啟時用）
#
# 注意：portal 目標「預設」即有頭 + 慢速（每步 2 秒），方便肉眼觀察頁面檢查
#
# 範例:
#   ./run.sh portal              # School Portal 測試（預設有頭 + 慢速觀察）
#   ./run.sh portal -NH -NS      # School Portal 測試（無頭 + 正常速度）
#   ./run.sh booking -H -S       # 全螢幕 + 慢速（每步 2 秒）觀察頁面檢查 + OCR 全自動登入（試 5 次）
#
set -euo pipefail
cd "$(dirname "$0")"

# ---------- 偵測 Maven（系統 mvn 優先，否則退回 IntelliJ 內建 Maven） ----------
INTELLIJ_MVN="/Applications/IntelliJ IDEA.app/Contents/plugins/maven/lib/maven3/bin/mvn"
if command -v mvn >/dev/null 2>&1; then
    MVN="mvn"
elif [[ -x "$INTELLIJ_MVN" ]]; then
    MVN="$INTELLIJ_MVN"
else
    echo "❌ 找不到 Maven：請安裝 Maven（brew install maven）或確認 IntelliJ 已安裝。" >&2
    exit 1
fi
echo "🛠  使用 Maven: $MVN"

# ---------- 解析參數 ----------
TARGET="booking"
HEADED=""
SLOWMO=""

usage() {
    sed -n '2,33p' "$0" | sed 's/^# \{0,1\}//'
    exit 0
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        -H|--headed)     HEADED="true";   shift ;;
        -S|--slowmo)     SLOWMO="true";   shift ;;
        -NH|--no-headed) HEADED="false";  shift ;;
        -NS|--no-slowmo) SLOWMO="false";  shift ;;
        -h|--help)       usage ;;
        *)            TARGET="$1";    shift ;;
    esac
done

# portal 目標預設「有頭 + 慢速（每步 2 秒）」方便肉眼觀察頁面檢查；可用 -NH / -NS 關閉
if [[ "$TARGET" == portal* ]]; then
    HEADED="${HEADED:-true}"
    SLOWMO="${SLOWMO:-true}"
fi
HEADED="${HEADED:-false}"
SLOWMO="${SLOWMO:-false}"
if [[ "$HEADED" == true ]]; then echo "🖥  有頭模式（全螢幕）"; fi
if [[ "$SLOWMO" == true ]]; then echo "🐢 慢速模式（每步 2 秒）"; fi

OPTS=(-Dheaded="$HEADED" -Dslowmo="$SLOWMO")
DEFAULT_OB_LOGIN='2175091750'
DEFAULT_OB_PASSWORD='Gold1234{}KKK'
CRED=(-Dob.login="${OB_LOGIN:-$DEFAULT_OB_LOGIN}" -Dob.password="${OB_PASSWORD:-$DEFAULT_OB_PASSWORD}" -Dob.attempts="${OB_ATTEMPTS:-5}")

# ---------- 執行 ----------
case "$TARGET" in
    install)
        echo "📦 安裝 Playwright Chromium 瀏覽器..."
        "$MVN" exec:java -e -D exec.mainClass=com.microsoft.playwright.CLI -D exec.args="install chromium"
        ;;
    all)
        echo "▶️  執行全部測試（3 個測試類）..."
        "$MVN" test "${OPTS[@]}" "${CRED[@]}"
        ;;
    booking)
        echo "▶️  執行 spaLoginPageLoadsSuccessfully（1 次）+ OCR 全自動登入..."
        "$MVN" test "${OPTS[@]}" "${CRED[@]}" \
            -Dtest='OnlineBookingLoginTest#spaLoginPageLoadsSuccessfully,OnlineBookingOcrLoginTest'
        ;;
    booking-all)
        echo "▶️  執行 OnlineBookingLoginTest 全部 5 個測試方法 + OCR 全自動登入..."
        "$MVN" test "${OPTS[@]}" "${CRED[@]}" \
            -Dtest='OnlineBookingLoginTest,OnlineBookingOcrLoginTest'
        ;;
    portal)
        echo "▶️  執行 LoginTest..."
        "$MVN" test "${OPTS[@]}" -Dtest=LoginTest
        ;;
    ocr)
        echo "▶️  執行 OnlineBookingOcrLoginTest（macOS Vision OCR）..."
        "$MVN" test "${OPTS[@]}" "${CRED[@]}" -Dtest=OnlineBookingOcrLoginTest
        ;;
    booking#*|portal#*)
        echo "▶️  執行單一測試方法: $TARGET"
        "$MVN" test "${OPTS[@]}" -Dtest="$TARGET"
        ;;
    *)
        echo "❌ 未知的目標: $TARGET（可用: all | booking | booking-all | portal | ocr | booking#方法 | portal#方法 | install）" >&2
        usage
        ;;
esac