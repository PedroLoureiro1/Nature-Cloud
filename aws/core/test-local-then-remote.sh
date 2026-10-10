#!/bin/bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"

usage() {
    cat <<'EOF'
Usage:
  ./test-local-then-remote.sh --workload <fractals|grayscott|dna> --query "<k=v&...>" [options]

Required:
  --workload <name>            Workload endpoint: fractals, grayscott, or dna
  --query "<k=v&...>"          Query string without leading '?'

Optional:
  --remote-ip <ip>             Remote IP (default: 13.60.46.206)
  --local-host <host>          Local host (default: 127.0.0.1)
  --local-port <port>          Local port (default: 8000)
  --remote-port <port>         Remote port (default: 8000)
  --csv <path>                 CSV file to read complexity from
  --timeout <seconds>          curl timeout (default: 300)
  --help                       Show help

Examples:
  ./test-local-then-remote.sh --workload fractals --query "w=800&h=600&iterations=1000"
  ./test-local-then-remote.sh --workload grayscott --query "size=400&maxIterations=600&f=0.030&k=0.062&stopOnExtinction=false&seedMode=center"
  ./test-local-then-remote.sh --workload dna --query "seq1=seq1:ATGCATGC&seq2=seq2:ATGCATGC&minLength=4&stopOnFirst=false"
EOF
}

WORKLOAD=""
QUERY=""
REMOTE_IP="13.60.46.206"
LOCAL_HOST="127.0.0.1"
LOCAL_PORT=8000
REMOTE_PORT=8000
TIMEOUT_SECONDS=300
CSV_FILE=""

while [[ $# -gt 0 ]]; do
    case "$1" in
        --workload)
            WORKLOAD="${2:-}"
            shift 2
            ;;
        --query)
            QUERY="${2:-}"
            shift 2
            ;;
        --remote-ip)
            REMOTE_IP="${2:-}"
            shift 2
            ;;
        --local-host)
            LOCAL_HOST="${2:-}"
            shift 2
            ;;
        --local-port)
            LOCAL_PORT="${2:-}"
            shift 2
            ;;
        --remote-port)
            REMOTE_PORT="${2:-}"
            shift 2
            ;;
        --csv)
            CSV_FILE="${2:-}"
            shift 2
            ;;
        --timeout)
            TIMEOUT_SECONDS="${2:-}"
            shift 2
            ;;
        --help)
            usage
            exit 0
            ;;
        *)
            echo "Unknown option: $1"
            usage
            exit 1
            ;;
    esac
done

if [[ -z "${WORKLOAD}" || -z "${QUERY}" ]]; then
    echo "Error: --workload and --query are required."
    usage
    exit 1
fi

case "${WORKLOAD}" in
    fractals|grayscott|dna) ;;
    *)
        echo "Invalid workload '${WORKLOAD}'. Use fractals, grayscott, or dna."
        exit 1
        ;;
esac

if [[ -z "${CSV_FILE}" ]]; then
    CSV_FILE="${REPO_ROOT}/research/data/weighted_instructions/${WORKLOAD}_profiling_results.csv"
fi

if [[ ! -f "${CSV_FILE}" ]]; then
    echo "CSV file not found: ${CSV_FILE}"
    exit 1
fi

urldecode() {
    local encoded="${1//+/ }"
    printf '%b' "${encoded//%/\\x}"
}

lowercase() {
    printf '%s' "$1" | tr '[:upper:]' '[:lower:]'
}

seq_length_from_param() {
    local raw="$1"
    local sequence="$raw"
    if [[ "${raw}" == *:* ]]; then
        sequence="${raw#*:}"
    fi
    printf '%s' "${sequence}" | awk '
        BEGIN { len = 0 }
        {
            gsub(/\r/, "", $0)
            if ($0 !~ /^[>;]/) {
                gsub(/[[:space:]]/, "", $0)
                len += length(toupper($0))
            }
        }
        END { print len + 0 }
    '
}

declare -A PARAMS
IFS='&' read -ra pairs <<< "${QUERY#\?}"
for pair in "${pairs[@]}"; do
    key="${pair%%=*}"
    value=""
    if [[ "${pair}" == *"="* ]]; then
        value="${pair#*=}"
    fi
    if [[ -n "${key}" ]]; then
        PARAMS["$key"]="$(urldecode "$value")"
    fi
done

get_param_or_default() {
    local key="$1"
    local default_value="$2"
    if [[ -v "PARAMS[$key]" && -n "${PARAMS[$key]}" ]]; then
        printf '%s' "${PARAMS[$key]}"
    else
        printf '%s' "${default_value}"
    fi
}

lookup_complexity_fractals() {
    local w="$1"
    local h="$2"
    local it="$3"

    local exact
    exact="$(awk -F, -v w="${w}" -v h="${h}" -v it="${it}" '
        NR == 1 {
            for (i = 1; i <= NF; i++) idx[$i] = i
            next
        }
        $idx["width"] == w && $idx["height"] == h && $idx["iterations"] == it {
            printf "exact,%s,width=%s;height=%s;iterations=%s", $idx["weighted_instructions"], $idx["width"], $idx["height"], $idx["iterations"]
            exit
        }
    ' "${CSV_FILE}")"

    if [[ -n "${exact}" ]]; then
        printf '%s' "${exact}"
        return
    fi

    awk -F, -v w="${w}" -v h="${h}" -v it="${it}" '
        function abs(x) { return x < 0 ? -x : x }
        NR == 1 {
            for (i = 1; i <= NF; i++) idx[$i] = i
            next
        }
        {
            dw = abs($idx["width"] - w)
            dh = abs($idx["height"] - h)
            di = abs($idx["iterations"] - it)
            dist = dw + dh + di
            if (bestDist == "" || dist < bestDist) {
                bestDist = dist
                bestC = $idx["weighted_instructions"]
                bw = $idx["width"]
                bh = $idx["height"]
                bi = $idx["iterations"]
            }
        }
        END {
            if (bestC == "") exit 1
            printf "nearest,%s,width=%s;height=%s;iterations=%s", bestC, bw, bh, bi
        }
    ' "${CSV_FILE}"
}

lookup_complexity_grayscott() {
    local size="$1"
    local max_iterations="$2"
    local f="$3"
    local k="$4"
    local stop="$5"
    local seed="$6"

    local exact
    exact="$(awk -F, -v sz="${size}" -v it="${max_iterations}" -v fv="${f}" -v kv="${k}" -v st="${stop}" -v sd="${seed}" '
        function abs(x) { return x < 0 ? -x : x }
        function is_close(a, b) { return abs(a - b) < 0.000001 }
        NR == 1 {
            for (i = 1; i <= NF; i++) idx[$i] = i
            next
        }
        ($idx["size"] == sz) &&
        ($idx["maxIterations"] == it) &&
        (is_close($idx["f"] + 0, fv + 0)) &&
        (is_close($idx["k"] + 0, kv + 0)) &&
        (tolower($idx["stopOnExtinction"]) == tolower(st)) &&
        (tolower($idx["seedMode"]) == tolower(sd)) {
            printf "exact,%s,size=%s;maxIterations=%s;f=%s;k=%s;stopOnExtinction=%s;seedMode=%s",
                $idx["weighted_instructions"], $idx["size"], $idx["maxIterations"], $idx["f"], $idx["k"], $idx["stopOnExtinction"], $idx["seedMode"]
            exit
        }
    ' "${CSV_FILE}")"

    if [[ -n "${exact}" ]]; then
        printf '%s' "${exact}"
        return
    fi

    awk -F, -v sz="${size}" -v it="${max_iterations}" -v fv="${f}" -v kv="${k}" -v st="${stop}" -v sd="${seed}" '
        function abs(x) { return x < 0 ? -x : x }
        NR == 1 {
            for (i = 1; i <= NF; i++) idx[$i] = i
            next
        }
        {
            dist = abs(($idx["size"] + 0) - (sz + 0))
            dist += abs(($idx["maxIterations"] + 0) - (it + 0))
            dist += (abs(($idx["f"] + 0) - (fv + 0)) * 100000)
            dist += (abs(($idx["k"] + 0) - (kv + 0)) * 100000)
            if (tolower($idx["stopOnExtinction"]) != tolower(st)) dist += 1000000
            if (tolower($idx["seedMode"]) != tolower(sd)) dist += 1000000

            if (bestDist == "" || dist < bestDist) {
                bestDist = dist
                bestC = $idx["weighted_instructions"]
                bsize = $idx["size"]
                bit = $idx["maxIterations"]
                bf = $idx["f"]
                bk = $idx["k"]
                bst = $idx["stopOnExtinction"]
                bsd = $idx["seedMode"]
            }
        }
        END {
            if (bestC == "") exit 1
            printf "nearest,%s,size=%s;maxIterations=%s;f=%s;k=%s;stopOnExtinction=%s;seedMode=%s",
                bestC, bsize, bit, bf, bk, bst, bsd
        }
    ' "${CSV_FILE}"
}

lookup_complexity_dna() {
    local seq1_length="$1"
    local seq2_length="$2"
    local min_length="$3"

    local exact
    exact="$(awk -F, -v s1="${seq1_length}" -v s2="${seq2_length}" -v ml="${min_length}" '
        NR == 1 {
            for (i = 1; i <= NF; i++) idx[$i] = i
            next
        }
        ($idx["seq1_length"] == s1) && ($idx["seq2_length"] == s2) && ($idx["minLength"] == ml) {
            printf "exact,%s,seq1_length=%s;seq2_length=%s;minLength=%s", $idx["weighted_instructions"], $idx["seq1_length"], $idx["seq2_length"], $idx["minLength"]
            exit
        }
    ' "${CSV_FILE}")"

    if [[ -n "${exact}" ]]; then
        printf '%s' "${exact}"
        return
    fi

    awk -F, -v s1="${seq1_length}" -v s2="${seq2_length}" -v ml="${min_length}" '
        function abs(x) { return x < 0 ? -x : x }
        NR == 1 {
            for (i = 1; i <= NF; i++) idx[$i] = i
            next
        }
        {
            dist = abs(($idx["seq1_length"] + 0) - (s1 + 0))
            dist += abs(($idx["seq2_length"] + 0) - (s2 + 0))
            dist += abs(($idx["minLength"] + 0) - (ml + 0))
            if (bestDist == "" || dist < bestDist) {
                bestDist = dist
                bestC = $idx["weighted_instructions"]
                bs1 = $idx["seq1_length"]
                bs2 = $idx["seq2_length"]
                bml = $idx["minLength"]
            }
        }
        END {
            if (bestC == "") exit 1
            printf "nearest,%s,seq1_length=%s;seq2_length=%s;minLength=%s", bestC, bs1, bs2, bml
        }
    ' "${CSV_FILE}"
}

LOCAL_URL="http://${LOCAL_HOST}:${LOCAL_PORT}/${WORKLOAD}?${QUERY#\?}"
REMOTE_URL="http://${REMOTE_IP}:${REMOTE_PORT}/${WORKLOAD}?${QUERY#\?}"

echo "Local request:  ${LOCAL_URL}"
echo "Remote request: ${REMOTE_URL}"
echo "CSV source:     ${CSV_FILE}"
echo

local_result="$(curl -sS --max-time "${TIMEOUT_SECONDS}" -o /tmp/cnv_local_response.$$ -w "%{http_code},%{time_total}" "${LOCAL_URL}")"
local_status="${local_result%%,*}"
local_time="${local_result#*,}"

echo "Local response: HTTP ${local_status} in ${local_time}s"

complexity_mode=""
complexity_value=""
matched_row=""

case "${WORKLOAD}" in
    fractals)
        w="$(get_param_or_default "w" "800")"
        h="$(get_param_or_default "h" "600")"
        iterations="$(get_param_or_default "iterations" "100")"
        read -r complexity_mode complexity_value matched_row <<< "$(lookup_complexity_fractals "${w}" "${h}" "${iterations}" | tr ',' ' ')"
        ;;
    grayscott)
        size="$(get_param_or_default "size" "256")"
        max_iterations="$(get_param_or_default "maxIterations" "5000")"
        f="$(get_param_or_default "f" "0.030")"
        k="$(get_param_or_default "k" "0.062")"
        stop_on_extinction="$(lowercase "$(get_param_or_default "stopOnExtinction" "false")")"
        seed_mode="$(get_param_or_default "seedMode" "center")"
        read -r complexity_mode complexity_value matched_row <<< "$(lookup_complexity_grayscott "${size}" "${max_iterations}" "${f}" "${k}" "${stop_on_extinction}" "${seed_mode}" | tr ',' ' ')"
        ;;
    dna)
        seq1_raw="$(get_param_or_default "seq1" "seq1:ATGC")"
        seq2_raw="$(get_param_or_default "seq2" "seq2:ATGC")"
        min_length="$(get_param_or_default "minLength" "1")"
        seq1_length="$(seq_length_from_param "${seq1_raw}")"
        seq2_length="$(seq_length_from_param "${seq2_raw}")"
        read -r complexity_mode complexity_value matched_row <<< "$(lookup_complexity_dna "${seq1_length}" "${seq2_length}" "${min_length}" | tr ',' ' ')"
        ;;
esac

echo "CSV complexity (${complexity_mode} match): ${complexity_value}"
echo "Matched CSV row keys: ${matched_row}"

remote_result="$(curl -sS --max-time "${TIMEOUT_SECONDS}" -o /tmp/cnv_remote_response.$$ -w "%{http_code},%{time_total}" "${REMOTE_URL}")"
remote_status="${remote_result%%,*}"
remote_time="${remote_result#*,}"

echo "Remote response: HTTP ${remote_status} in ${remote_time}s"
echo
echo "Summary:"
echo "workload=${WORKLOAD}"
echo "csv_complexity=${complexity_value}"
echo "local_http=${local_status}"
echo "remote_http=${remote_status}"

rm -f /tmp/cnv_local_response.$$ /tmp/cnv_remote_response.$$
