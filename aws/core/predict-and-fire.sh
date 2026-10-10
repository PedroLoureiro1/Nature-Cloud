#!/bin/bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
MODELS_DIR="${REPO_ROOT}/regression/models"

usage() {
    cat <<'EOF'
Usage:
  ./predict-and-fire.sh --workload <fractals|grayscott|dna> --query "<k=v&...>" [options]

Required:
  --workload <name>       Workload endpoint: fractals, grayscott, or dna
  --query "<k=v&...>"     Query string without leading '?'

Optional:
  --remote-ip <ip>        Remote IP or hostname (default: 127.0.0.1)
  --remote-port <port>    Remote port (default: 9000, the LB)
  --concurrent <n>        Fire N requests in parallel (default: 1)
  --timeout <seconds>     curl timeout (default: 300)
  --help                  Show help

Examples:
  ./predict-and-fire.sh --workload fractals --query "w=1600&h=1600&iterations=2000" --remote-ip 1.2.3.4
  ./predict-and-fire.sh --workload fractals --query "w=1600&h=1600&iterations=2000" --remote-ip 1.2.3.4 --concurrent 5
  ./predict-and-fire.sh --workload grayscott --query "size=800&maxIterations=1500&f=0.030&k=0.062&stopOnExtinction=false&seedMode=center" --remote-ip 1.2.3.4
  ./predict-and-fire.sh --workload dna --query "seq1=seq1:ATGCATGC&seq2=seq2:ATGCATGC&minLength=4" --remote-ip 1.2.3.4
EOF
}

WORKLOAD=""
QUERY=""
REMOTE_IP="127.0.0.1"
REMOTE_PORT=9000
CONCURRENT=1
TIMEOUT_SECONDS=300

while [[ $# -gt 0 ]]; do
    case "$1" in
        --workload)   WORKLOAD="${2:-}";      shift 2 ;;
        --query)      QUERY="${2:-}";         shift 2 ;;
        --remote-ip)  REMOTE_IP="${2:-}";     shift 2 ;;
        --remote-port) REMOTE_PORT="${2:-}";  shift 2 ;;
        --concurrent) CONCURRENT="${2:-}";    shift 2 ;;
        --timeout)    TIMEOUT_SECONDS="${2:-}"; shift 2 ;;
        --help)       usage; exit 0 ;;
        *) echo "Unknown option: $1"; usage; exit 1 ;;
    esac
done

if [[ -z "${WORKLOAD}" || -z "${QUERY}" ]]; then
    echo "Error: --workload and --query are required."
    usage
    exit 1
fi

case "${WORKLOAD}" in
    fractals|grayscott|dna) ;;
    *) echo "Invalid workload '${WORKLOAD}'. Use fractals, grayscott, or dna."; exit 1 ;;
esac

MODEL_FILE="${MODELS_DIR}/${WORKLOAD}_model.json"
if [[ ! -f "${MODEL_FILE}" ]]; then
    echo "Model not found: ${MODEL_FILE}"
    exit 1
fi

urldecode() {
    local encoded="${1//+/ }"
    printf '%b' "${encoded//%/\\x}"
}

declare -A PARAMS
IFS='&' read -ra pairs <<< "${QUERY#\?}"
for pair in "${pairs[@]}"; do
    key="${pair%%=*}"
    value=""
    if [[ "${pair}" == *"="* ]]; then
        value="${pair#*=}"
    fi
    [[ -n "${key}" ]] && PARAMS["${key}"]="$(urldecode "${value}")"
done

get_param() {
    local key="$1" default="$2"
    if [[ -v "PARAMS[${key}]" && -n "${PARAMS[${key}]}" ]]; then
        printf '%s' "${PARAMS[${key}]}"
    else
        printf '%s' "${default}"
    fi
}

seq_length() {
    local raw="$1"
    local seq="${raw}"
    [[ "${raw}" == *:* ]] && seq="${raw#*:}"
    printf '%s' "${seq}" | awk '{ gsub(/[[:space:]]/, ""); print length($0) }'
}

get_feature_value() {
    local feature="$1"
    case "${WORKLOAD}" in
        fractals)
            case "${feature}" in
                w|width)    get_param "w" "800" ;;
                h|height)   get_param "h" "600" ;;
                iterations) get_param "iterations" "100" ;;
            esac
            ;;
        grayscott)
            case "${feature}" in
                size)             get_param "size" "256" ;;
                maxIterations)    get_param "maxIterations" "5000" ;;
                f)                get_param "f" "0.030" ;;
                k)                get_param "k" "0.062" ;;
                stopOnExtinction)
                    local val; val="$(get_param "stopOnExtinction" "false")"
                    [[ "${val,,}" == "true" ]] && echo "1" || echo "0"
                    ;;
            esac
            ;;
        dna)
            case "${feature}" in
                minLength)   get_param "minLength" "1" ;;
                seq1Length|seq1_length) seq_length "$(get_param "seq1" "seq1:ATGC")" ;;
                seq2Length|seq2_length) seq_length "$(get_param "seq2" "seq2:ATGC")" ;;
            esac
            ;;
    esac
}

mapfile -t features    < <(jq -r '.features[]'      "${MODEL_FILE}")
mapfile -t coeffs      < <(jq -r '.coefficients[]'  "${MODEL_FILE}")
mapfile -t means       < <(jq -r '.featureMeans[]'  "${MODEL_FILE}")
mapfile -t stddevs     < <(jq -r '.featureStdDevs[]' "${MODEL_FILE}")
intercept="$(jq -r '.intercept'  "${MODEL_FILE}")"
r_squared="$(jq -r '.rSquared'   "${MODEL_FILE}")"

values=()
for f in "${features[@]}"; do
    values+=("$(get_feature_value "${f}")")
done

n="${#features[@]}"
vals_str="${values[*]}"
coeffs_str="${coeffs[*]}"
means_str="${means[*]}"
stddevs_str="${stddevs[*]}"

predicted=$(awk -v n="${n}" -v intercept="${intercept}" \
    -v vals="${vals_str}" -v coeffs="${coeffs_str}" \
    -v ms="${means_str}" -v sds="${stddevs_str}" '
    BEGIN {
        split(vals,   v, " ")
        split(coeffs, c, " ")
        split(ms,     m, " ")
        split(sds,    s, " ")
        result = intercept + 0
        for (i = 1; i <= n; i++) {
            result += c[i] * (v[i] - m[i]) / s[i]
        }
        printf "%.0f", result
    }
')

REMOTE_URL="http://${REMOTE_IP}:${REMOTE_PORT}/${WORKLOAD}?${QUERY#\?}"

echo "========================================"
echo "Workload:             ${WORKLOAD}"
echo "URL:                  ${REMOTE_URL}"
echo "----------------------------------------"
for i in "${!features[@]}"; do
    printf "  %-20s %s\n" "${features[$i]}" "${values[$i]}"
done
echo "----------------------------------------"
printf "Predicted complexity: %s weighted instructions\n" "${predicted}"
printf "Model R²:             %s\n" "${r_squared}"
echo "========================================"
echo "Firing ${CONCURRENT} request(s)..."
echo ""

fire_request() {
    local idx="$1"
    local result
    result="$(curl -sS --max-time "${TIMEOUT_SECONDS}" \
        -o /tmp/cnv_fire_$$.${idx} \
        -w "%{http_code},%{time_total}" \
        "${REMOTE_URL}")"
    local status="${result%%,*}"
    local elapsed="${result#*,}"
    printf "  [%d] HTTP %s in %ss\n" "${idx}" "${status}" "${elapsed}"
    rm -f "/tmp/cnv_fire_$$.${idx}"
}

pids=()
for i in $(seq 1 "${CONCURRENT}"); do
    fire_request "${i}" &
    pids+=($!)
done

for pid in "${pids[@]}"; do
    wait "${pid}"
done

echo ""
echo "Done. Check CloudWatch CPU for instance ${REMOTE_IP} to correlate."
