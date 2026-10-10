#!/bin/bash

set -euo pipefail

source ../resources/config.sh

TABLE_NAME="${HHS_DDB_TABLE:-hhs-workload-metrics}"
CSV_DIR="${1:-../../research/data/wheighted_instructions}"
WINDOW_MS=300000

if [ -z "${AWS_DEFAULT_REGION:-}" ]; then
    echo "AWS_DEFAULT_REGION is not set in aws/resources/config.sh"
    exit 1
fi

# Ensure the target table exists before importing.
if ! aws dynamodb describe-table --table-name "$TABLE_NAME" --region "$AWS_DEFAULT_REGION" >/dev/null 2>&1; then
    echo "DynamoDB table not found: $TABLE_NAME"
    echo "Run: bash setup-dynamodb.sh"
    exit 1
fi

now_ms() {
    echo "$(( $(date +%s) * 1000 ))"
}

trim_cr() {
    local value="$1"
    echo "${value%$'\r'}"
}

put_metric_item() {
    local workload="$1"
    local row_id="$2"
    local complexity="$3"
    local inputs_json="$4"

    local request_epoch_ms
    request_epoch_ms="$(now_ms)"
    request_epoch_ms="$((request_epoch_ms + row_id))"
    local window_start_ms
    window_start_ms="$((request_epoch_ms - (request_epoch_ms % WINDOW_MS)))"

    local pk="csv-import-${workload}"
    local request_id="csv-${workload}-${row_id}"
    local sk
    sk="$(printf "%013d#%s" "$request_epoch_ms" "$request_id")"

    local item
    item="{\"pk\":{\"S\":\"${pk}\"},\"sk\":{\"S\":\"${sk}\"},\"workload\":{\"S\":\"${workload}\"},\"windowStartEpochMs\":{\"N\":\"${window_start_ms}\"},\"requestEpochMs\":{\"N\":\"${request_epoch_ms}\"},\"complexity\":{\"N\":\"${complexity}\"},\"inputs\":{\"M\":${inputs_json}}}"

    # Persist one historical metric row to DynamoDB.
    aws dynamodb put-item \
        --table-name "$TABLE_NAME" \
        --region "$AWS_DEFAULT_REGION" \
        --item "$item" >/dev/null
}

import_dna_csv() {
    local file="$1"
    local imported=0

    # CSV columns: seq1_length,seq2_length,minLength,...,wheighted_instructions
    while IFS=, read -r seq1_length seq2_length min_length _ _ wheighted_instructions; do
        seq1_length="$(trim_cr "$seq1_length")"
        seq2_length="$(trim_cr "$seq2_length")"
        min_length="$(trim_cr "$min_length")"
        wheighted_instructions="$(trim_cr "$wheighted_instructions")"

        imported=$((imported + 1))

        local inputs
        inputs="{\"minLength\":{\"S\":\"${min_length}\"},\"stopOnFirst\":{\"S\":\"false\"},\"seq1Length\":{\"S\":\"${seq1_length}\"},\"seq2Length\":{\"S\":\"${seq2_length}\"}}"

        put_metric_item "dna" "$imported" "$wheighted_instructions" "$inputs"
    done < <(tail -n +2 "$file")

    echo "Imported ${imported} DNA rows from ${file}"
}

import_fractals_csv() {
    local file="$1"
    local imported=0

    # CSV columns: width,height,iterations,...,wheighted_instructions
    while IFS=, read -r width height iterations _ _ wheighted_instructions; do
        width="$(trim_cr "$width")"
        height="$(trim_cr "$height")"
        iterations="$(trim_cr "$iterations")"
        wheighted_instructions="$(trim_cr "$wheighted_instructions")"

        imported=$((imported + 1))

        local inputs
        inputs="{\"w\":{\"S\":\"${width}\"},\"h\":{\"S\":\"${height}\"},\"iterations\":{\"S\":\"${iterations}\"}}"

        put_metric_item "fractals" "$imported" "$wheighted_instructions" "$inputs"
    done < <(tail -n +2 "$file")

    echo "Imported ${imported} fractals rows from ${file}"
}

import_grayscott_csv() {
    local file="$1"
    local imported=0

    # CSV columns: size,maxIterations,f,k,stopOnExtinction,seedMode,...,wheighted_instructions
    while IFS=, read -r size max_iterations f k stop_on_extinction seed_mode _ _ wheighted_instructions; do
        size="$(trim_cr "$size")"
        max_iterations="$(trim_cr "$max_iterations")"
        f="$(trim_cr "$f")"
        k="$(trim_cr "$k")"
        stop_on_extinction="$(trim_cr "$stop_on_extinction")"
        seed_mode="$(trim_cr "$seed_mode")"
        wheighted_instructions="$(trim_cr "$wheighted_instructions")"

        imported=$((imported + 1))

        local inputs
        inputs="{\"size\":{\"S\":\"${size}\"},\"maxIterations\":{\"S\":\"${max_iterations}\"},\"f\":{\"S\":\"${f}\"},\"k\":{\"S\":\"${k}\"},\"stopOnExtinction\":{\"S\":\"${stop_on_extinction}\"},\"seedMode\":{\"S\":\"${seed_mode}\"}}"

        put_metric_item "grayscott" "$imported" "$wheighted_instructions" "$inputs"
    done < <(tail -n +2 "$file")

    echo "Imported ${imported} grayscott rows from ${file}"
}

DNA_CSV="${CSV_DIR}/dna_profiling_results.csv"
FRACTALS_CSV="${CSV_DIR}/fractals_profiling_results.csv"
GRAYSCOTT_CSV="${CSV_DIR}/grayscott_profiling_results.csv"

for csv_file in "$DNA_CSV" "$FRACTALS_CSV" "$GRAYSCOTT_CSV"; do
    if [ ! -f "$csv_file" ]; then
        echo "Missing CSV file: $csv_file"
        exit 1
    fi
done

import_dna_csv "$DNA_CSV"
import_fractals_csv "$FRACTALS_CSV"
import_grayscott_csv "$GRAYSCOTT_CSV"

echo "CSV import complete. Target table: ${TABLE_NAME}"
