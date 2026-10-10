#!/bin/bash
set -euo pipefail

WORKLOAD="${1:-}"
if [ -z "$WORKLOAD" ]; then
    echo "Usage: $0 <workload>"
    echo "Workload options: dna, fractals, grayscott, all"
    exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WORKSPACE_DIR="$(cd "${SCRIPT_DIR}/../../.." && pwd)"

bash "${WORKSPACE_DIR}/research/profiler/run_profile_generic.sh" \
    "$WORKLOAD" \
    "WeightedInstructions" \
    "${WORKSPACE_DIR}/research/data/weighted_instructions" \
    "${WORKSPACE_DIR}/research/profiler/weighted_instructions/output" \
    "weighted_instructions"
