#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT_DIR"

MODEL_DIR="regression/models"
REPORT_DIR="regression/accuracy"
TARGET_COL="weighted_instructions"
CSV_DIR="research/data/weighted_instructions_external/train"
JAR_PATH="model-training/target/model-training-1.0.0-SNAPSHOT-jar-with-dependencies.jar"

workloads=(dna fractals grayscott)

echo "[1/4] Building model-training module"
mvn -f model-training/pom.xml -DskipTests package

echo "[2/5] Preparing output directories"
mkdir -p "$MODEL_DIR" "$REPORT_DIR"

echo "[2/4] Training models in Java"
for workload in "${workloads[@]}"; do
  model_path="$MODEL_DIR/${workload}_model.json"
  csv_path="$CSV_DIR/${workload}_profiling_results.csv"
  if [ "$workload" = "dna" ]; then
    model_path="$MODEL_DIR/${workload}_dual_model.json"
    java -cp "$JAR_PATH" pt.ulisboa.tecnico.cnv.modeltraining.LocalTrainer \
      --workload "dna" \
      --dual "true" \
      --partitionFeature "stopOnFirst" \
      --csv "$csv_path" \
      --out "$model_path" \
      --complexityColumn "$TARGET_COL"
  else
    java -cp "$JAR_PATH" pt.ulisboa.tecnico.cnv.modeltraining.LocalTrainer \
      --workload "$workload" \
      --csv "$csv_path" \
      --out "$model_path" \
      --complexityColumn "$TARGET_COL"
  fi
done

echo "[3/4] Accuracy reports are not generated here because the old Python validator is not in this workspace"
echo "[4/4] Done"
echo "Models generated in: $MODEL_DIR"
echo "Output report directory reserved at: $REPORT_DIR"
