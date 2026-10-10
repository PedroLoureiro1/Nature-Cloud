#!/bin/bash

set -euo pipefail

source ../../resources/config.sh

METRICS_TABLE="${HHS_DDB_TABLE:-hhs-workload-metrics}"
MODELS_TABLE="${HHS_MODEL_TABLE:-hhs-regression-models}"

# Step 1: Delete scaling policies
echo "Deleting scaling policies..."
aws autoscaling delete-policy \
  --auto-scaling-group-name CNV-AutoScalingGroup \
  --policy-name CNV-RegressionMetricScaling \
  --region "${AWS_DEFAULT_REGION}" 2>/dev/null || true

echo "Scaling policies deleted (or did not exist)."

# Step 2: update and delete Auto Scaling group.
echo "Updating Auto Scaling group..."
aws autoscaling update-auto-scaling-group \
  --auto-scaling-group-name CNV-AutoScalingGroup \
  --min-size 0 \
  --max-size 0 \
  --desired-capacity 0 \
  --region "${AWS_DEFAULT_REGION}" 2>/dev/null || true

# Optional: wait for instance termination before deletion
echo "Waiting for instances to terminate..."
sleep 60

echo "Deleting Auto Scaling group..."
aws autoscaling delete-auto-scaling-group \
  --auto-scaling-group-name CNV-AutoScalingGroup \
  --force-delete \
  --region "${AWS_DEFAULT_REGION}" 2>/dev/null || true

echo "Auto Scaling group deleted."

# Step 4: Delete launch template.
echo "Deleting launch template..."
aws ec2 delete-launch-template \
  --launch-template-name CNV-LaunchTemplate \
  --region "${AWS_DEFAULT_REGION}" 2>/dev/null || true

echo "Launch template deleted."

# Step 5: Delete load balancer.
echo "Deleting load balancer..."
aws elb delete-load-balancer \
  --load-balancer-name CNV-LoadBalancer \
  --region "${AWS_DEFAULT_REGION}" 2>/dev/null || true

echo "Load balancer deleted."

# Step 6: Delete DynamoDB tables.
echo "Deleting DynamoDB tables..."
aws dynamodb delete-table \
  --table-name "${METRICS_TABLE}" \
  --region "${AWS_DEFAULT_REGION}" 2>/dev/null || true

aws dynamodb delete-table \
  --table-name "${MODELS_TABLE}" \
  --region "${AWS_DEFAULT_REGION}" 2>/dev/null || true

echo "DynamoDB tables deleted (or did not exist)."

echo ""
echo "=========================================="
echo "Deployment teardown complete."
echo "=========================================="
echo ""
echo "Optional: Clean up IAM and DynamoDB:"
echo "  AWS_ACCOUNT_ID=<your account>"
echo ""
