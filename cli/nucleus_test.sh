#!/usr/bin/env bash
# nucleus_test.sh — unit tests for the nucleus CLI script
# Tests only pure, non-network functions sourced from the script.
# Run: bash cli/nucleus_test.sh
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
NUCLEUS_SCRIPT="${SCRIPT_DIR}/nucleus"

# ---------------------------------------------------------------------------
# Source helpers only — skip main() execution
# ---------------------------------------------------------------------------
# We set NUCLEUS_URL so require_backend() can be called safely in tests
NUCLEUS_URL="http://localhost:8080"
CONFIG_DIR="/tmp/nucleus_test_config_$$"
CONFIG_FILE="${CONFIG_DIR}/config.yml"
mkdir -p "$CONFIG_DIR"

# Disable set -e within the sourced script's main guard by redefining main()
main() { :; }
# shellcheck source=/dev/null
source "$NUCLEUS_SCRIPT"

# ---------------------------------------------------------------------------
# Test harness
# ---------------------------------------------------------------------------
PASS=0
FAIL=0
ERRORS=()

assert_eq() {
    local desc="$1" expected="$2" actual="$3"
    if [[ "$expected" == "$actual" ]]; then
        echo "  ✓ $desc"
        PASS=$((PASS + 1))
    else
        echo "  ✗ $desc"
        echo "    expected: '$expected'"
        echo "    actual:   '$actual'"
        ERRORS+=("$desc")
        FAIL=$((FAIL + 1))
    fi
}

assert_contains() {
    local desc="$1" needle="$2" haystack="$3"
    if [[ "$haystack" == *"$needle"* ]]; then
        echo "  ✓ $desc"
        PASS=$((PASS + 1))
    else
        echo "  ✗ $desc"
        echo "    expected to contain: '$needle'"
        echo "    actual: '$haystack'"
        ERRORS+=("$desc")
        FAIL=$((FAIL + 1))
    fi
}

assert_empty() {
    local desc="$1" actual="$2"
    if [[ -z "$actual" ]]; then
        echo "  ✓ $desc"
        PASS=$((PASS + 1))
    else
        echo "  ✗ $desc"
        echo "    expected empty, got: '$actual'"
        ERRORS+=("$desc")
        FAIL=$((FAIL + 1))
    fi
}

# ---------------------------------------------------------------------------
# Tests: json_field
# ---------------------------------------------------------------------------
echo ""
echo "json_field (jq fallback)"

JSON='{"sessionId":"abc-123","status":"RUNNING","ciRetryCount":2}'
HAS_JQ=false  # test sed fallback path

assert_eq "extracts sessionId" "abc-123" "$(json_field "$JSON" sessionId)"
assert_eq "extracts status"    "RUNNING"  "$(json_field "$JSON" status)"
assert_empty "returns empty for missing field" "$(json_field "$JSON" branchName)"

# ---------------------------------------------------------------------------
# Tests: json_int
# ---------------------------------------------------------------------------
echo ""
echo "json_int (jq fallback)"

assert_eq "extracts integer field" "2" "$(json_int "$JSON" ciRetryCount)"

# ---------------------------------------------------------------------------
# Tests: config_get / config_set
# ---------------------------------------------------------------------------
echo ""
echo "config_get / config_set"

assert_empty "config_get on missing key returns empty" "$(config_get nucleus_url)"

config_set "nucleus_url" "http://prod:8080"
assert_eq "config_set then config_get" "http://prod:8080" "$(config_get nucleus_url)"

config_set "nucleus_url" "http://staging:8080"
assert_eq "config_set overwrites existing key" "http://staging:8080" "$(config_get nucleus_url)"

config_set "other_key" "other_value"
assert_eq "config_get does not bleed into other keys" "http://staging:8080" "$(config_get nucleus_url)"

# ---------------------------------------------------------------------------
# Tests: colorize_status
# ---------------------------------------------------------------------------
echo ""
echo "colorize_status"

RESET=''  # Strip ANSI codes for assertion comparisons
BLUE='' GREEN='' RED='' YELLOW='' ORANGE=''

assert_contains "RUNNING contains status text"    "RUNNING"    "$(colorize_status RUNNING)"
assert_contains "MERGED contains status text"     "MERGED"     "$(colorize_status MERGED)"
assert_contains "FAILED contains status text"     "FAILED"     "$(colorize_status FAILED)"
assert_contains "PENDING contains status text"    "PENDING"    "$(colorize_status PENDING)"
assert_contains "IN_REVIEW contains status text"  "IN_REVIEW"  "$(colorize_status IN_REVIEW)"
assert_contains "unknown status passes through"   "FOOBAR"     "$(colorize_status FOOBAR)"

# ---------------------------------------------------------------------------
# Tests: detect_language
# ---------------------------------------------------------------------------
echo ""
echo "detect_language"

TMP_DIR=$(mktemp -d)
pushd "$TMP_DIR" > /dev/null

touch pom.xml
assert_eq "detects java via pom.xml" "java" "$(detect_language)"
rm pom.xml

touch package.json
assert_eq "detects node via package.json" "node" "$(detect_language)"
rm package.json

touch requirements.txt
assert_eq "detects python via requirements.txt" "python" "$(detect_language)"
rm requirements.txt

touch go.mod
assert_eq "detects go via go.mod" "go" "$(detect_language)"
rm go.mod

assert_eq "returns unknown when no recognizable file" "unknown" "$(detect_language)"

popd > /dev/null
rm -rf "$TMP_DIR"

# ---------------------------------------------------------------------------
# Tests: detect_package_manager
# ---------------------------------------------------------------------------
echo ""
echo "detect_package_manager"

TMP_DIR=$(mktemp -d)
pushd "$TMP_DIR" > /dev/null

touch pom.xml
assert_eq "detects maven via pom.xml" "maven" "$(detect_package_manager)"
rm pom.xml

touch yarn.lock; touch package.json
assert_eq "detects yarn via yarn.lock" "yarn" "$(detect_package_manager)"
rm yarn.lock package.json

touch pnpm-lock.yaml; touch package.json
assert_eq "detects pnpm via pnpm-lock.yaml" "pnpm" "$(detect_package_manager)"
rm pnpm-lock.yaml package.json

touch package-lock.json; touch package.json
assert_eq "detects npm via package-lock.json" "npm" "$(detect_package_manager)"
rm package-lock.json package.json

assert_eq "returns unknown when no lockfile" "unknown" "$(detect_package_manager)"

popd > /dev/null
rm -rf "$TMP_DIR"

# ---------------------------------------------------------------------------
# Tests: script executable and help output
# ---------------------------------------------------------------------------
echo ""
echo "script meta"

assert_eq "script is executable" "0" "$([ -x "$NUCLEUS_SCRIPT" ]; echo $?)"
assert_contains "help output contains spawn" "spawn" "$(bash "$NUCLEUS_SCRIPT" help)"
assert_contains "help output contains status" "status" "$(bash "$NUCLEUS_SCRIPT" help)"
assert_contains "help output contains send" "send" "$(bash "$NUCLEUS_SCRIPT" help)"
assert_contains "version flag prints version" "0.1.0" "$(bash "$NUCLEUS_SCRIPT" version)"

# ---------------------------------------------------------------------------
# Cleanup
# ---------------------------------------------------------------------------
rm -rf "$CONFIG_DIR"

# ---------------------------------------------------------------------------
# Summary
# ---------------------------------------------------------------------------
echo ""
echo "─────────────────────────────────"
echo "Results: ${PASS} passed, ${FAIL} failed"
if [[ ${FAIL} -gt 0 ]]; then
    echo "Failed tests:"
    for err in "${ERRORS[@]}"; do
        echo "  • $err"
    done
    exit 1
fi
echo "All tests passed."
