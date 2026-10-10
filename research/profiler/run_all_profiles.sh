#!/bin/bash
set -euo pipefail

WORKLOAD="${1:-all}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WORKSPACE_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"
GENERIC_RUNNER="${WORKSPACE_DIR}/research/profiler/run_profile_generic.sh"

if [ ! -f "$GENERIC_RUNNER" ]; then
    echo "ERROR: Generic runner not found at $GENERIC_RUNNER"
    exit 1
fi

METRICS=(
    "MethodCalls method_calls method_calls"
    "BasicBlocks basic_blocks basic_blocks"
    "BytecodeInstructions bytecode_instructions bytecode_instructions"
    "LoopIterations loop_iteration loop_iteration"
    "WeightedInstructions weighted_instructions weighted_instructions"
    "WeightedInstructionsExternalInvocations weighted_instructions_external weighted_instructions"
)

echo "=========================================="
echo "Run All Profilers"
echo "=========================================="
echo "Workload: $WORKLOAD"
echo ""

for metric in "${METRICS[@]}"; do
    read -r tool_name data_key metric_column <<< "$metric"
    data_dir="${WORKSPACE_DIR}/research/data/${data_key}"
    output_dir="${WORKSPACE_DIR}/research/profiler/${data_key}/output"

    echo ">>> Running ${tool_name} (${metric_column})"
    bash "$GENERIC_RUNNER" "$WORKLOAD" "$tool_name" "$data_dir" "$output_dir" "$metric_column"
    echo ""
done

echo "All profiler runs finished."
