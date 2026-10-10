#!/bin/bash

source ../resources/config.sh

LAMBDA_ROLE_NAME="CNV-WorkloadLambdaRole"
METRICS_TABLE="${HHS_DDB_TABLE:-hhs-workload-metrics}"
MODELS_TABLE="${HHS_MODELS_DDB_TABLE:-hhs-regression-models}"

# Delete Lambda functions.
echo "Deleting Lambda functions..."
for fn in CNV-FractalsHandler CNV-GrayScottHandler CNV-DnaHandler; do
    if aws lambda delete-function \
            --function-name "${fn}" \
            --region "${AWS_DEFAULT_REGION}" 2>/dev/null; then
        echo "Deleted ${fn}."
    else
        echo "${fn} not found, skipping."
    fi
done

# Clean up Lambda IAM role.
echo "Cleaning up Lambda IAM role: ${LAMBDA_ROLE_NAME}..."
aws iam detach-role-policy \
    --role-name "${LAMBDA_ROLE_NAME}" \
    --policy-arn arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole 2>/dev/null || true
aws iam delete-role \
    --role-name "${LAMBDA_ROLE_NAME}" 2>/dev/null || true
echo "Lambda IAM role cleaned up."

# Delete DynamoDB tables.
echo "Deleting DynamoDB tables..."
aws dynamodb delete-table \
    --table-name "${METRICS_TABLE}" \
    --region "${AWS_DEFAULT_REGION}" 2>/dev/null || true

aws dynamodb delete-table \
    --table-name "${MODELS_TABLE}" \
    --region "${AWS_DEFAULT_REGION}" 2>/dev/null || true

# Terminate all CNV instances by tag.
echo "Finding CNV instances..."
INSTANCE_IDS="$(aws ec2 describe-instances \
    --filters "Name=tag:CNV-Project,Values=cnv" \
              "Name=instance-state-name,Values=pending,running,stopping,stopped" \
    --query "Reservations[].Instances[].InstanceId" \
    --output text)"

if [[ -z "$INSTANCE_IDS" ]]; then
    echo "No running CNV instances found."
else
    echo "Terminating: $INSTANCE_IDS"
    # shellcheck disable=SC2086
    aws ec2 terminate-instances --instance-ids $INSTANCE_IDS \
        --region "$AWS_DEFAULT_REGION" >/dev/null
    # shellcheck disable=SC2086
    aws ec2 wait instance-terminated --instance-ids $INSTANCE_IDS \
        --region "$AWS_DEFAULT_REGION"
    echo "Instances terminated."
fi

# Backward compatibility: remove old launch templates if they exist from a previous deployment.
for LT in CNV-WorkerLaunchTemplate CNV-BalancerLaunchTemplate CNV-LaunchTemplate; do
    aws ec2 delete-launch-template --launch-template-name "$LT" \
        --region "$AWS_DEFAULT_REGION" 2>/dev/null || true
done


echo ""
echo "=========================================="
echo "Deployment teardown complete."
echo "=========================================="

