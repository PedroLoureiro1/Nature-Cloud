#!/bin/bash
set -euo pipefail

# Overhead Test Script
# Measures instrumentation overhead for five metric tools:
# - MethodCalls: Count method invocations
# - BasicBlocks: Track execution frequency per basic block
# - BytecodeInstructions: Static instruction count
# - LoopIterations: Count loop-header visits as a proxy for loop iterations
# - weightedInstructions: Weighted instruction count by opcode category

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WORKSPACE_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"
OVERHEAD_DIR="${WORKSPACE_DIR}/research/profiler/overhead"
OUTPUT_DIR="${OVERHEAD_DIR}/output"
RESULTS_DIR="${OVERHEAD_DIR}/results"

AGENT_JAR="${WORKSPACE_DIR}/instrumentation-agent/target/instrumentation-agent-1.0.0-SNAPSHOT.jar"
DNA_JAR="${WORKSPACE_DIR}/dna/target/dna-1.0.0-SNAPSHOT-jar-with-dependencies.jar"
FRACTALS_JAR="${WORKSPACE_DIR}/fractals/target/fractals-1.0.0-SNAPSHOT-jar-with-dependencies.jar"
GRAYSCOTT_JAR="${WORKSPACE_DIR}/grayscott/target/grayscott-1.0.0-SNAPSHOT-jar-with-dependencies.jar"

mkdir -p "$OUTPUT_DIR" "$RESULTS_DIR"

echo "=========================================="
echo "Instrumentation Overhead Test"
echo "=========================================="
echo ""

# Build all modules
echo "[1/5] Building modules..."
cd "$WORKSPACE_DIR"
mvn clean package -DskipTests -q

if [ ! -f "$AGENT_JAR" ]; then
    echo "ERROR: instrumentation-agent JAR not found at $AGENT_JAR"
    exit 1
fi

if [ ! -f "$DNA_JAR" ] || [ ! -f "$FRACTALS_JAR" ] || [ ! -f "$GRAYSCOTT_JAR" ]; then
    echo "ERROR: One or more workload JARs not found"
    exit 1
fi

echo "✓ Build complete"
echo ""

# Helper function to load FASTA sequence
load_fasta_sequence() {
    local fasta_file="$1"
    awk 'NR > 1 { gsub(/\r/, ""); printf "%s", $0 }' "$fasta_file"
}

# Helper function to repeat sequence
repeat_sequence() {
    local base_sequence="$1"
    local length="$2"
    local repeated_sequence=""

    while [ ${#repeated_sequence} -lt "$length" ]; do
        repeated_sequence+="$base_sequence"
    done

    printf '%s' "${repeated_sequence:0:length}"
}

# Extract metric value from output based on metric type
extract_metric_value() {
    local metric_type="$1"
    local log_file="$2"
    
    case "$metric_type" in
        MethodCalls)
            grep -o "Number of executed methods: [0-9]*" "$log_file" | tail -1 | awk '{print $NF}'
            ;;
        BasicBlocks)
            # Sum all basic block execution counts
            grep "was called.*times" "$log_file" | awk -F'called ' '{print $2}' | awk '{sum += $1} END {print sum + 0}'
            ;;
        BytecodeInstructions)
            grep "Total bytecode instructions:" "$log_file" | tail -1 | awk '{print $NF}'
            ;;
        LoopIterations)
            grep "Total loop iterations:" "$log_file" | tail -1 | awk '{print $NF}'
            ;;
        WeightedInstructions|WeightedInstructionsExternalInvocations)
            grep "Total weighted instructions:" "$log_file" | tail -1 | awk '{print $NF}'
            ;;
        *)
            echo "ERROR: Unknown metric type $metric_type"
            exit 1
            ;;
    esac
}

# Run once and measure: baseline (no agent)
run_baseline() {
    local write_dir="$1"
    local log_file="$2"
    shift 2

    rm -rf "$write_dir"
    mkdir -p "$write_dir"

    local start_time end_time duration_ms

    start_time=$(date +%s%N)
    "$@" > "$log_file" 2>&1
    end_time=$(date +%s%N)
    duration_ms=$(( (end_time - start_time) / 1000000 ))

    printf '%s\n' "$duration_ms"
}

# Run once and measure: with instrumentation
run_instrumented() {
    local metric_type="$1"
    local write_dir="$2"
    local log_file="$3"
    shift 3

    rm -rf "$write_dir"
    mkdir -p "$write_dir"

    local start_time end_time duration_ms metric_value

    start_time=$(date +%s%N)
    "$@" > "$log_file" 2>&1
    end_time=$(date +%s%N)
    duration_ms=$(( (end_time - start_time) / 1000000 ))

    metric_value=$(extract_metric_value "$metric_type" "$log_file")
    
    if [ -z "$metric_value" ] || [ "$metric_value" = "0" ]; then
        echo "ERROR: Failed to extract $metric_type metric from $log_file"
        cat "$log_file"
        exit 1
    fi

    printf '%s %s\n' "$duration_ms" "$metric_value"
}

# Calculate overhead percentage
calc_overhead() {
    local baseline=$1
    local instrumented=$2
    printf "%.2f" $(echo "scale=2; (($instrumented - $baseline) * 100) / $baseline" | bc)
}

# Test DNA with 3 sizes
test_dna() {
    local package_name="pt.ulisboa.tecnico.cnv.dna"
    local main_class="pt.ulisboa.tecnico.cnv.dna.DnaHandler"
    
    echo "[2/5] Testing DNA (Small, Medium, Large)..."
    echo ""

    local seq1_base seq2_base
    seq1_base=$(load_fasta_sequence "$WORKSPACE_DIR/dna/src/main/resources/human_HBB.fasta")
    seq2_base=$(load_fasta_sequence "$WORKSPACE_DIR/dna/src/main/resources/chimpanzee_HBB.fasta")

    # Test configurations: (seq1_length, seq2_length, minLength, size_label)
    local configs=(
        "100 100 1 small"
        "500 500 5 medium"
        "1000 1000 10 large"
    )

    # Initialize CSV files per metric
    for metric in MethodCalls BasicBlocks BytecodeInstructions LoopIterations WeightedInstructions WeightedInstructionsExternalInvocations; do
        local csv_file="${RESULTS_DIR}/${metric}_dna_overhead.csv"
        echo "workload,size,seq1_len,seq2_len,minLen,baseline_ms,instrumented_ms,overhead_percent,metric_value" > "$csv_file"
    done

    for config in "${configs[@]}"; do
        read -r seq1_len seq2_len min_len size_label <<< "$config"
        
        local seq1 seq2 log_file
        seq1=$(repeat_sequence "$seq1_base" "$seq1_len")
        seq2=$(repeat_sequence "$seq2_base" "$seq2_len")
        log_file=$(mktemp /tmp/overhead_dna_XXXXXX.log)

        printf "  %-15s seq1=%4dbp seq2=%4dbp minLen=%2d" "DNA/$size_label" "$seq1_len" "$seq2_len" "$min_len"

        # Run baseline 3 times
        local baseline_total=0
        for _ in 1 2 3; do
            read -r baseline_time < <(
                run_baseline "$OUTPUT_DIR/dna_baseline" "$log_file" \
                    java -cp "$DNA_JAR" "$main_class" "seq1:${seq1}" "seq2:${seq2}" "$min_len" false
            )
            baseline_total=$((baseline_total + baseline_time))
        done
        local baseline_avg=$((baseline_total / 3))

        printf " | baseline=%5dms" "$baseline_avg"

        # Test each metric
        for metric in MethodCalls BasicBlocks BytecodeInstructions LoopIterations WeightedInstructions WeightedInstructionsExternalInvocations; do
            local instrumented_total=0
            local metric_total=0

            for _ in 1 2 3; do
                read -r instrumented_time metric_value < <(
                    run_instrumented "$metric" "$OUTPUT_DIR/dna_${metric}" "$log_file" \
                        java "-javaagent:${AGENT_JAR}=${metric}:${package_name}:${OUTPUT_DIR}/dna_${metric}" \
                        -cp "$DNA_JAR" "$main_class" "seq1:${seq1}" "seq2:${seq2}" "$min_len" false
                )
                instrumented_total=$((instrumented_total + instrumented_time))
                metric_total=$((metric_total + metric_value))
            done

            local instrumented_avg=$((instrumented_total / 3))
            local metric_avg=$((metric_total / 3))
            local overhead=$(calc_overhead "$baseline_avg" "$instrumented_avg")

            printf " | %s: %5dms (+%5.1f%%)" "$metric" "$instrumented_avg" "$overhead"

            # Append to CSV
            local csv_file="${RESULTS_DIR}/${metric}_dna_overhead.csv"
            echo "DNA,$size_label,$seq1_len,$seq2_len,$min_len,$baseline_avg,$instrumented_avg,$overhead,$metric_avg" >> "$csv_file"
        done

        printf "\n"
        rm -f "$log_file"
    done
    echo ""
}

# Test Fractals with 3 sizes
test_fractals() {
    local package_name="pt.ulisboa.tecnico.cnv.fractals"
    local main_class="pt.ulisboa.tecnico.cnv.fractals.FractalsHandler"
    
    echo "[3/5] Testing Fractals (Small, Medium, Large)..."
    echo ""

    # Test configurations: (width, height, iterations, size_label)
    local configs=(
        "512 512 256 small"
        "1024 1024 512 medium"
        "2048 2048 1024 large"
    )

    # Initialize CSV files per metric
    for metric in MethodCalls BasicBlocks BytecodeInstructions LoopIterations WeightedInstructions WeightedInstructionsExternalInvocations; do
        local csv_file="${RESULTS_DIR}/${metric}_fractals_overhead.csv"
        echo "workload,size,width,height,iterations,baseline_ms,instrumented_ms,overhead_percent,metric_value" > "$csv_file"
    done

    for config in "${configs[@]}"; do
        read -r width height iterations size_label <<< "$config"
        
        local output_file log_file
        output_file=$(mktemp "$OUTPUT_DIR/fractals_XXXXXX.png")
        log_file=$(mktemp /tmp/overhead_fractals_XXXXXX.log)

        printf "  %-15s width=%3d height=%3d iter=%4d" "Fractals/$size_label" "$width" "$height" "$iterations"

        # Run baseline 3 times
        local baseline_total=0
        for _ in 1 2 3; do
            read -r baseline_time < <(
                run_baseline "$OUTPUT_DIR/fractals_baseline" "$log_file" \
                    java -cp "$FRACTALS_JAR" "$main_class" "$width" "$height" "$iterations" "$output_file"
            )
            baseline_total=$((baseline_total + baseline_time))
        done
        local baseline_avg=$((baseline_total / 3))

        printf " | baseline=%5dms" "$baseline_avg"

        # Test each metric
        for metric in MethodCalls BasicBlocks BytecodeInstructions LoopIterations WeightedInstructions WeightedInstructionsExternalInvocations; do
            local instrumented_total=0
            local metric_total=0

            for _ in 1 2 3; do
                read -r instrumented_time metric_value < <(
                    run_instrumented "$metric" "$OUTPUT_DIR/fractals_${metric}" "$log_file" \
                        java "-javaagent:${AGENT_JAR}=${metric}:${package_name}:${OUTPUT_DIR}/fractals_${metric}" \
                        -cp "$FRACTALS_JAR" "$main_class" "$width" "$height" "$iterations" "$output_file"
                )
                instrumented_total=$((instrumented_total + instrumented_time))
                metric_total=$((metric_total + metric_value))
            done

            local instrumented_avg=$((instrumented_total / 3))
            local metric_avg=$((metric_total / 3))
            local overhead=$(calc_overhead "$baseline_avg" "$instrumented_avg")

            printf " | %s: %5dms (+%5.1f%%)" "$metric" "$instrumented_avg" "$overhead"

            # Append to CSV
            local csv_file="${RESULTS_DIR}/${metric}_fractals_overhead.csv"
            echo "Fractals,$size_label,$width,$height,$iterations,$baseline_avg,$instrumented_avg,$overhead,$metric_avg" >> "$csv_file"
        done

        printf "\n"
        rm -f "$output_file" "$log_file"
    done
    echo ""
}

# Test Gray-Scott with 3 sizes
test_grayscott() {
    local package_name="pt.ulisboa.tecnico.cnv.grayscott"
    local main_class="pt.ulisboa.tecnico.cnv.grayscott.GrayScottHandler"
    
    echo "[4/5] Testing Gray-Scott (Small, Medium, Large + parameter profiles)..."
    echo ""

    # Test configurations: (size, maxIterations, size_label)
    local configs=(
        "64 100 small"
        "200 300 medium"
        "400 600 large"
    )

    # Initialize CSV files per metric
    for metric in MethodCalls BasicBlocks BytecodeInstructions LoopIterations WeightedInstructions WeightedInstructionsExternalInvocations; do
        local csv_file="${RESULTS_DIR}/${metric}_grayscott_overhead.csv"
        echo "workload,size,grid_size,max_iter,f,k,stopOnExtinction,seedMode,baseline_ms,instrumented_ms,overhead_percent,metric_value" > "$csv_file"
    done

    for config in "${configs[@]}"; do
        read -r grid_size max_iter size_label <<< "$config"

        while read -r feed kill stop_on_extinction seed_mode; do
            local output_file log_file
            output_file=$(mktemp "$OUTPUT_DIR/grayscott_XXXXXX.png")
            log_file=$(mktemp /tmp/overhead_grayscott_XXXXXX.log)

            printf "  %-15s size=%3d maxIter=%4d F=%0.3f K=%0.3f stop=%5s seed=%-6s" \
                "GrayScott/$size_label" "$grid_size" "$max_iter" "$feed" "$kill" "$stop_on_extinction" "$seed_mode"

            # Run baseline 3 times
            local baseline_total=0
            for _ in 1 2 3; do
                read -r baseline_time < <(
                    run_baseline "$OUTPUT_DIR/grayscott_baseline" "$log_file" \
                        java -cp "$GRAYSCOTT_JAR" "$main_class" "$grid_size" "$max_iter" "$feed" "$kill" "$stop_on_extinction" "$seed_mode" "$output_file"
                )
                baseline_total=$((baseline_total + baseline_time))
            done
            local baseline_avg=$((baseline_total / 3))

            printf " | baseline=%5dms" "$baseline_avg"

            # Test each metric
            for metric in MethodCalls BasicBlocks BytecodeInstructions LoopIterations WeightedInstructions WeightedInstructionsExternalInvocations; do
                local instrumented_total=0
                local metric_total=0

                for _ in 1 2 3; do
                    read -r instrumented_time metric_value < <(
                        run_instrumented "$metric" "$OUTPUT_DIR/grayscott_${metric}" "$log_file" \
                            java "-javaagent:${AGENT_JAR}=${metric}:${package_name}:${OUTPUT_DIR}/grayscott_${metric}" \
                            -cp "$GRAYSCOTT_JAR" "$main_class" "$grid_size" "$max_iter" "$feed" "$kill" "$stop_on_extinction" "$seed_mode" "$output_file"
                    )
                    instrumented_total=$((instrumented_total + instrumented_time))
                    metric_total=$((metric_total + metric_value))
                done

                local instrumented_avg=$((instrumented_total / 3))
                local metric_avg=$((metric_total / 3))
                local overhead=$(calc_overhead "$baseline_avg" "$instrumented_avg")

                printf " | %s: %5dms (+%5.1f%%)" "$metric" "$instrumented_avg" "$overhead"

                # Append to CSV
                local csv_file="${RESULTS_DIR}/${metric}_grayscott_overhead.csv"
                echo "GrayScott,$size_label,$grid_size,$max_iter,$feed,$kill,$stop_on_extinction,$seed_mode,$baseline_avg,$instrumented_avg,$overhead,$metric_avg" >> "$csv_file"
            done

            printf "\n"
            rm -f "$output_file" "$log_file"
        done << 'EOF'
0.022 0.051 false center
0.030 0.062 false ring
0.038 0.070 true stripe
EOF
    done
    echo ""
}

# Run all tests
test_dna
test_fractals
test_grayscott

echo "[5/5] Results Summary"
echo ""
echo "Overhead results saved to:"
ls -lh "$RESULTS_DIR"/*.csv | awk '{print "  " $9 " (" $5 ")"}'
echo ""
echo "=========================================="
echo "Test complete!"
echo "=========================================="
