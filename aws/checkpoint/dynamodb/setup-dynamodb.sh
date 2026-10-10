#!/bin/bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
AWS_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

source "${AWS_DIR}/resources/config.sh"

METRICS_TABLE="${HHS_DDB_TABLE:-hhs-workload-metrics}"
MODELS_TABLE="${HHS_MODEL_TABLE:-hhs-regression-models}"

if [ -z "${AWS_DEFAULT_REGION:-}" ]; then
    echo "AWS_DEFAULT_REGION is not set in aws/resources/config.sh"
    exit 1
fi

echo "Setting up DynamoDB in region ${AWS_DEFAULT_REGION}..."

# Check whether the metrics table already exists.
if aws dynamodb describe-table --table-name "$METRICS_TABLE" --region "$AWS_DEFAULT_REGION" >/dev/null 2>&1; then
    echo "Table already exists: $METRICS_TABLE"
else
    # Create the metrics table with pk (S) + sk (S).
    echo "Creating table: $METRICS_TABLE"
    aws dynamodb create-table \
        --table-name "$METRICS_TABLE" \
        --attribute-definitions AttributeName=pk,AttributeType=S AttributeName=sk,AttributeType=S \
        --key-schema AttributeName=pk,KeyType=HASH AttributeName=sk,KeyType=RANGE \
        --billing-mode PAY_PER_REQUEST \
        --region "$AWS_DEFAULT_REGION" >/dev/null
fi
# Block until the metrics table reaches ACTIVE state.
aws dynamodb wait table-exists --table-name "$METRICS_TABLE" --region "$AWS_DEFAULT_REGION"
echo "Table active: $METRICS_TABLE"

# Check whether the models table already exists.
if aws dynamodb describe-table --table-name "$MODELS_TABLE" --region "$AWS_DEFAULT_REGION" >/dev/null 2>&1; then
    echo "Table already exists: $MODELS_TABLE"
else
    # Create the models table with pk (S) + sk (N).
    echo "Creating table: $MODELS_TABLE"
    aws dynamodb create-table \
        --table-name "$MODELS_TABLE" \
        --attribute-definitions AttributeName=pk,AttributeType=S AttributeName=sk,AttributeType=N \
        --key-schema AttributeName=pk,KeyType=HASH AttributeName=sk,KeyType=RANGE \
        --billing-mode PAY_PER_REQUEST \
        --region "$AWS_DEFAULT_REGION" >/dev/null
fi
# Block until the models table reaches ACTIVE state.
aws dynamodb wait table-exists --table-name "$MODELS_TABLE" --region "$AWS_DEFAULT_REGION"
echo "Table active: $MODELS_TABLE"

echo "DynamoDB setup complete."
