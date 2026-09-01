#!/usr/bin/env bash
# Entrypoint for the dhci2_auto_check test container.
#
# Usage: docker run --rm \
#         -e OB_LOGIN=... -e OB_PASSWORD=... \
#         [-e HEADED=false] [-e SLOWMO=false] \
#         dhci2-auto-check [portal|booking|all]
#
# Targets:
#   portal  - School Portal login page tests (LoginTest) + built-in credentials
#   booking - Online Booking page tests + OCR captcha login (needs OB_LOGIN/OB_PASSWORD)
#   all     - everything above
set -euo pipefail

TARGET="${1:-${TARGET:-booking}}"
HEADED="${HEADED:-false}"
SLOWMO="${SLOWMO:-false}"

echo "== dhci2_auto_check container: target=${TARGET} headed=${HEADED} slowmo=${SLOWMO} =="

run_tests() {
    local tests="$1"
    echo "== running: ${tests} =="
    mvn -B test \
        -Dsurefire.printSummary=true \
        -D headed="${HEADED}" \
        -D slowmo="${SLOWMO}" \
        -D test="${tests}" \
        -D failIfNoTests=false
}

case "${TARGET}" in
    portal)
        run_tests "LoginTest"
        ;;
    booking)
        run_tests "OnlineBooking*Test"
        ;;
    all)
        run_tests "*Test"
        ;;
    *)
        echo "Unknown target '${TARGET}' (expected: portal | booking | all)" >&2
        exit 2
        ;;
esac

echo "== done =="