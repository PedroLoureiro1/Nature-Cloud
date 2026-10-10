#!/bin/bash
set -euo pipefail

if [ $# -lt 4 ]; then
    echo "Usage: $0 <workload> <tool_name> <data_dir> <instrumentation_output_dir> [metric_column]"
    echo "Example: $0 dna weightedInstructions /abs/research/data/weighted_instructions /abs/research/profiler/weighted_instructions/output weighted_instructions train"
    exit 1
fi

WORKLOAD="$1"
TOOL_NAME="$2"
DATA_DIR="$3"
OUTPUT_DIR="$4"
METRIC_COLUMN="${5:-}"
DATA_SPLIT="${6:-train}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WORKSPACE_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"
AGENT_JAR="${WORKSPACE_DIR}/instrumentation-agent/target/instrumentation-agent-1.0.0-SNAPSHOT.jar"
DNA_JAR="${WORKSPACE_DIR}/dna/target/dna-1.0.0-SNAPSHOT-jar-with-dependencies.jar"
FRACTALS_JAR="${WORKSPACE_DIR}/fractals/target/fractals-1.0.0-SNAPSHOT-jar-with-dependencies.jar"
GRAYSCOTT_JAR="${WORKSPACE_DIR}/grayscott/target/grayscott-1.0.0-SNAPSHOT-jar-with-dependencies.jar"

default_metric_column() {
    case "$TOOL_NAME" in
        MethodCalls) echo "method_calls" ;;
        BasicBlocks) echo "basic_blocks" ;;
        BytecodeInstructions) echo "bytecode_instructions" ;;
        LoopIterations) echo "loop_iteration" ;;
        WeightedInstructions) echo "weighted_instructions" ;;
        WeightedInstructionsExternalInvocations) echo "weighted_instructions" ;;
        *) echo "metric_value" ;;
    esac
}

if [ -z "$METRIC_COLUMN" ]; then
    METRIC_COLUMN="$(default_metric_column)"
fi

mkdir -p "$DATA_DIR" "$OUTPUT_DIR"

build_project() {
    echo "[1/3] Building project..."
    cd "$WORKSPACE_DIR"
    mvn clean package -DskipTests -q
    if [ ! -f "$AGENT_JAR" ]; then
        echo "ERROR: instrumentation-agent JAR not found"
        exit 1
    fi
    echo "✓ Build complete"
}

load_fasta_sequence() {
    awk 'NR > 1 { gsub(/\r/, ""); printf "%s", $0 }' "$1"
}

repeat_sequence() {
    local base="$1" len="$2" result=""
    while [ ${#result} -lt "$len" ]; do
        result+="$base"
    done
    printf '%s' "${result:0:len}"
}

extract_metric() {
    local log_file="$1"

    case "$TOOL_NAME" in
        MethodCalls)
            grep -o 'Number of executed methods: [0-9]*' "$log_file" | tail -1 | awk '{print $NF}'
            ;;
        BasicBlocks)
            awk '/was called [0-9]+ times$/ { sum += $(NF-1) } END { print sum + 0 }' "$log_file"
            ;;
        BytecodeInstructions)
            grep -o 'Total bytecode instructions: [0-9]*' "$log_file" | tail -1 | awk '{print $NF}'
            ;;
        LoopIterations)
            grep -o 'Total loop iterations: [0-9]*' "$log_file" | tail -1 | awk '{print $NF}'
            ;;
        WeightedInstructions|weightedInstructions|WeightedInstructionsExternalInvocations)
            grep -o 'Total weighted instructions: [0-9]*' "$log_file" | tail -1 | awk '{print $NF}'
            ;;
        *)
            echo "ERROR: Unsupported tool '$TOOL_NAME' for metric extraction"
            exit 1
            ;;
    esac
}

run_once() {
    local write_dir="$1" log_file="$2"
    shift 2
    rm -rf "$write_dir"
    mkdir -p "$write_dir"

    local start=$(date +%s%N)
    "$@" > "$log_file" 2>&1
    local end=$(date +%s%N)
    local duration_ms=$(( (end - start) / 1000000 ))

    local metric
    metric="$(extract_metric "$log_file")"
    [ -z "$metric" ] && { echo "ERROR: Failed to extract metric for $TOOL_NAME"; cat "$log_file"; exit 1; }

    printf '%s %s\n' "$duration_ms" "$metric"
}

read_run_result() {
    local output="$1"
    if [ -z "$output" ]; then
        echo "ERROR: profiler produced no output"
        exit 1
    fi
    printf '%s' "$output"
}

generate_int_points() {
    local min="$1" max="$2" points="$3"
    awk -v min="$min" -v max="$max" -v points="$points" '
        BEGIN {
            if (points <= 1) {
                print int(min)
                exit
            }
            step = (max - min) / (points - 1)
            for (i = 0; i < points; i++) {
                value = min + i * step
                rounded = int(value + 0.5)
                if (i == 0) rounded = int(min)
                if (i == points - 1) rounded = int(max)
                print rounded
            }
        }
    '
}

generate_float_points() {
    local min="$1" max="$2" points="$3" decimals="${4:-3}"
    awk -v min="$min" -v max="$max" -v points="$points" -v decimals="$decimals" '
        BEGIN {
            if (points <= 1) {
                printf "%.*f\n", decimals, min
                exit
            }
            step = (max - min) / (points - 1)
            for (i = 0; i < points; i++) {
                value = min + i * step
                if (i == 0) value = min
                if (i == points - 1) value = max
                printf "%.*f\n", decimals, value
            }
        }
    '
}

run_dna() {
    local csv_dir="${DATA_DIR}/${DATA_SPLIT}"
    local csv="${csv_dir}/dna_profiling_results.csv"
    mkdir -p "$csv_dir"
    echo "seq1_length,seq2_length,minLength,stopOnFirst,sweep_variable,execution_time_ms,${METRIC_COLUMN}" > "$csv"

    local seq1 seq2
    seq1=$(load_fasta_sequence "$WORKSPACE_DIR/dna/src/main/resources/human_HBB.fasta")
    seq2=$(load_fasta_sequence "$WORKSPACE_DIR/dna/src/main/resources/chimpanzee_HBB.fasta")

    local configs=()
    local v

    while IFS= read -r v; do
        configs+=("$v 1000 10 seq1_length")
    done < <(generate_int_points 50 25000 19)

    while IFS= read -r v; do
        configs+=("1000 $v 10 seq2_length")
    done < <(generate_int_points 50 25000 19)

    while IFS= read -r v; do
        configs+=("1000 1000 $v minLength")
    done < <(generate_int_points 1 250 16)

    configs+=(
        "250 250 1 paired_small"
        "250 1000 20 asymmetric_short"
        "500 2000 10 mixed_medium"
        "1000 4000 40 asymmetric_long"
        "2000 2000 5 paired_mid"
        "4000 250 40 cross_extreme"
        "50 50 1 tiny_pair"
        "10000 10000 80 giant_pair"
        "15000 15000 80 large_pair_15k"
        "20000 20000 80 large_pair_20k"
        "25000 25000 80 large_pair_25k"
        "25000 25000 250 prod_scale"
    )

    for config in "${configs[@]}"; do
        read -r len1 len2 minlen sweep <<< "$config"
        local s1 s2 log total_t total_m run_result t m
        s1=$(repeat_sequence "$seq1" "$len1")
        s2=$(repeat_sequence "$seq2" "$len2")
        log=$(mktemp)
        for stop_on_first in false true; do
            total_t=0
            total_m=0

            for _ in 1 2 3; do
                run_result=$(run_once "$OUTPUT_DIR/dna_${len1}_${len2}_${minlen}_${stop_on_first}" "$log" \
                    java "-javaagent:${AGENT_JAR}=${TOOL_NAME}:pt.ulisboa.tecnico.cnv.dna:${OUTPUT_DIR}/dna" \
                    -cp "$DNA_JAR" pt.ulisboa.tecnico.cnv.dna.DnaHandler "seq1:${s1}" "seq2:${s2}" "$minlen" "$stop_on_first")
                read -r t m <<< "$(read_run_result "$run_result")"
                total_t=$((total_t + t))
                total_m=$((total_m + m))
            done

            echo "${len1},${len2},${minlen},${stop_on_first},${sweep},$((total_t/3)),$((total_m/3))" >> "$csv"
            printf "  [%-11s] seq1=%4d seq2=%4d minLen=%2d stopOnFirst=%5s -> %6dms %12d %s\n" \
                "$sweep" "$len1" "$len2" "$minlen" "$stop_on_first" $((total_t/3)) $((total_m/3)) "$METRIC_COLUMN"
        done
        rm -f "$log"
    done
}

run_fractals() {
    local csv_dir="${DATA_DIR}/${DATA_SPLIT}"
    local csv="${csv_dir}/fractals_profiling_results.csv"
    mkdir -p "$csv_dir"
    echo "width,height,iterations,sweep_variable,execution_time_ms,${METRIC_COLUMN}" > "$csv"

    local configs=()
    local v

    while IFS= read -r v; do
        configs+=("$v 800 800 width")
    done < <(generate_int_points 50 3200 13)

    while IFS= read -r v; do
        configs+=("800 $v 800 height")
    done < <(generate_int_points 50 3200 13)

    while IFS= read -r v; do
        configs+=("800 800 $v iterations")
    done < <(generate_int_points 10 4000 13)

    configs+=(
        "200 200 100 compact_corner"
        "200 1600 800 tall_frame"
        "400 1200 2000 deep_zoom"
        "800 1600 300 mixed_high"
        "1600 200 200 wide_frame"
        "1600 1600 100 dense_canvas"
        "50 50 10 tiny_canvas"
        "3200 3200 4000 giant_canvas"
    )

    for config in "${configs[@]}"; do
        read -r w h it sweep <<< "$config"
        local out log total_t total_m run_result t m
        out=$(mktemp "$OUTPUT_DIR/frac_XXXXXX.png")
        log=$(mktemp)
        total_t=0
        total_m=0

        for _ in 1 2 3; do
            run_result=$(run_once "$OUTPUT_DIR/frac_${w}_${h}_${it}" "$log" \
                java "-javaagent:${AGENT_JAR}=${TOOL_NAME}:pt.ulisboa.tecnico.cnv.fractals:${OUTPUT_DIR}/frac" \
                -cp "$FRACTALS_JAR" pt.ulisboa.tecnico.cnv.fractals.FractalsHandler "$w" "$h" "$it" "$out")
            read -r t m <<< "$(read_run_result "$run_result")"
            total_t=$((total_t + t))
            total_m=$((total_m + m))
        done

        echo "${w},${h},${it},${sweep},$((total_t/3)),$((total_m/3))" >> "$csv"
        printf "  [%-11s] width=%4d height=%4d iter=%4d -> %6dms %12d %s\n" \
            "$sweep" "$w" "$h" "$it" $((total_t/3)) $((total_m/3)) "$METRIC_COLUMN"
        rm -f "$out" "$log"
    done
}

run_grayscott() {
    local csv_dir="${DATA_DIR}/${DATA_SPLIT}"
    local csv="${csv_dir}/grayscott_profiling_results.csv"
    mkdir -p "$csv_dir"
    echo "size,maxIterations,f,k,stopOnExtinction,seedMode,sweep_variable,execution_time_ms,${METRIC_COLUMN}" > "$csv"

    local configs=()
    local v

    while IFS= read -r v; do
        configs+=("$v 600 0.030 0.062 false center size")
    done < <(generate_int_points 32 2400 13)

    while IFS= read -r v; do
        configs+=("400 $v 0.030 0.062 false center maxIterations")
    done < <(generate_int_points 50 4000 13)

    while IFS= read -r v; do
        configs+=("400 600 $v 0.062 false center f")
    done < <(generate_float_points 0.010 0.080 13 3)

    while IFS= read -r v; do
        configs+=("400 600 0.030 $v false center k")
    done < <(generate_float_points 0.030 0.090 13 3)

    configs+=("400 600 0.030 0.062 false center stopOnExtinction")
    configs+=("400 600 0.030 0.062 true center stopOnExtinction")
    configs+=("400 600 0.030 0.062 false center seedMode")
    configs+=("400 600 0.030 0.062 false ring seedMode")
    configs+=("400 600 0.030 0.062 false stripe seedMode")

    configs+=(
        "64 100 0.018 0.045 false center paired_small"
        "64 600 0.046 0.075 true ring multi_low"
        "400 1000 0.030 0.045 false stripe mixed_depth"
        "800 1500 0.046 0.062 true center high_load"
        "1200 2000 0.018 0.075 false ring extreme_mix"
        "32 50 0.010 0.030 false center tiny_reaction"
        "2400 4000 0.080 0.090 true stripe giant_reaction"
    )

    for config in "${configs[@]}"; do
        read -r sz it feed kill stop seed sweep <<< "$config"
        local out log total_t total_m run_result t m
        out=$(mktemp "$OUTPUT_DIR/gs_XXXXXX.png")
        log=$(mktemp)
        total_t=0
        total_m=0

        for _ in 1 2 3; do
            run_result=$(run_once "$OUTPUT_DIR/gs_${sz}_${it}_${feed}_${kill}_${stop}_${seed}" "$log" \
                java "-javaagent:${AGENT_JAR}=${TOOL_NAME}:pt.ulisboa.tecnico.cnv.grayscott:${OUTPUT_DIR}/gs" \
                -cp "$GRAYSCOTT_JAR" pt.ulisboa.tecnico.cnv.grayscott.GrayScottHandler "$sz" "$it" "$feed" "$kill" "$stop" "$seed" "$out")
            read -r t m <<< "$(read_run_result "$run_result")"
            total_t=$((total_t + t))
            total_m=$((total_m + m))
        done

        echo "${sz},${it},${feed},${kill},${stop},${seed},${sweep},$((total_t/3)),$((total_m/3))" >> "$csv"
        printf "  [%-16s] size=%4d iter=%4d F=%0.3f K=%0.3f stop=%5s seed=%-6s -> %6dms %12d %s\n" \
            "$sweep" "$sz" "$it" "$feed" "$kill" "$stop" "$seed" $((total_t/3)) $((total_m/3)) "$METRIC_COLUMN"
        rm -f "$out" "$log"
    done
}

echo "=========================================="
echo "Generic Profiler ($TOOL_NAME)"
echo "=========================================="
echo ""

build_project
echo "[2/3] Running $WORKLOAD profiling..."
echo ""

case "$WORKLOAD" in
    dna) run_dna ;;
    fractals) run_fractals ;;
    grayscott) run_grayscott ;;
    all)
        run_dna
        run_fractals
        run_grayscott
        ;;
    *) echo "ERROR: Unsupported workload '$WORKLOAD' (use dna, fractals, grayscott, all)"; exit 1 ;;
esac

echo ""
echo "[3/3] Complete! Results in: $DATA_DIR"
