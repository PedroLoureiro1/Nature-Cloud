#!/bin/bash

set -euo pipefail

source ../resources/config.sh

ACCOUNT_ID="${AWS_ACCOUNT_ID}"
REGION="${AWS_DEFAULT_REGION}"
METRICS_TABLE="hhs-workload-metrics"
MODELS_TABLE="hhs-regression-models"

# ============================================
# PROFILE 1: WORKER (read-only)
# ============================================

# Create trust policy
cat > /tmp/trust.json <<EOF
{
  "Version": "2012-10-17",
  "Statement": [{
    "Effect": "Allow",
    "Principal": {"Service": "ec2.amazonaws.com"},
    "Action": "sts:AssumeRole"
  }]
}
EOF

cat > /tmp/worker-policy.json <<EOF
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": ["dynamodb:BatchWriteItem", "dynamodb:PutItem", "dynamodb:GetItem", "dynamodb:Query", "dynamodb:Scan"],
      "Resource": [
        "arn:aws:dynamodb:${REGION}:${ACCOUNT_ID}:table/${METRICS_TABLE}",
        "arn:aws:dynamodb:${REGION}:${ACCOUNT_ID}:table/${MODELS_TABLE}"
      ]
    }
  ]
}
EOF

if aws iam get-role --role-name "WorkerReadOnly" >/dev/null 2>&1; then
  aws iam update-assume-role-policy --role-name "WorkerReadOnly" --policy-document file:///tmp/trust.json
else
  aws iam create-role --role-name "WorkerReadOnly" --assume-role-policy-document file:///tmp/trust.json
fi

# Attach policy to role
aws iam put-role-policy --role-name "WorkerReadOnly" --policy-name "ReadOnlyAccess" --policy-document file:///tmp/worker-policy.json

# Create instance profile for worker
if ! aws iam get-instance-profile --instance-profile-name "WorkerInstanceProfile" >/dev/null 2>&1; then
  aws iam create-instance-profile --instance-profile-name "WorkerInstanceProfile"
fi

if ! aws iam get-instance-profile --instance-profile-name "WorkerInstanceProfile" --query "InstanceProfile.Roles[?RoleName=='WorkerReadOnly'] | length(@)" --output text | grep -qx "1"; then
  aws iam add-role-to-instance-profile --instance-profile-name "WorkerInstanceProfile" --role-name "WorkerReadOnly"
fi

# ============================================
# PROFILE 2: LOAD BALANCER/AUTOSCALER (read-write)
# ============================================

# Create load balancer policy (read-write)
cat > /tmp/lb-policy.json <<EOF
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": [
        "dynamodb:PutItem",
        "dynamodb:GetItem",
        "dynamodb:Query",
        "dynamodb:Scan"
      ],
      "Resource": [
        "arn:aws:dynamodb:${REGION}:${ACCOUNT_ID}:table/${METRICS_TABLE}",
        "arn:aws:dynamodb:${REGION}:${ACCOUNT_ID}:table/${MODELS_TABLE}"
      ]
    },
    {
      "Effect": "Allow",
      "Action": [
        "ec2:AssociateAddress",
        "ec2:DescribeInstances",
        "ec2:RunInstances",
        "ec2:TerminateInstances",
        "ec2:CreateTags",
        "iam:PassRole",
        "lambda:InvokeFunction",
        "cloudwatch:GetMetricStatistics"
      ],
      "Resource": "*"
    }
  ]
}
EOF

if aws iam get-role --role-name "LBAutoscalerReadWrite" >/dev/null 2>&1; then
  aws iam update-assume-role-policy --role-name "LBAutoscalerReadWrite" --policy-document file:///tmp/trust.json
else
  aws iam create-role --role-name "LBAutoscalerReadWrite" --assume-role-policy-document file:///tmp/trust.json
fi

# Attach policy to role
aws iam put-role-policy --role-name "LBAutoscalerReadWrite" --policy-name "ReadWriteAccess" --policy-document file:///tmp/lb-policy.json

# Create instance profile for load balancer
if ! aws iam get-instance-profile --instance-profile-name "LBAutoscalerInstanceProfile" >/dev/null 2>&1; then
  aws iam create-instance-profile --instance-profile-name "LBAutoscalerInstanceProfile"
fi

if ! aws iam get-instance-profile --instance-profile-name "LBAutoscalerInstanceProfile" --query "InstanceProfile.Roles[?RoleName=='LBAutoscalerReadWrite'] | length(@)" --output text | grep -qx "1"; then
  aws iam add-role-to-instance-profile --instance-profile-name "LBAutoscalerInstanceProfile" --role-name "LBAutoscalerReadWrite"
fi

# Cleanup
rm -f /tmp/trust.json /tmp/worker-policy.json /tmp/lb-policy.json

# IAM changes take a few seconds to propagate globally before EC2 can use them.
sleep 15

echo "Done!"
echo ""
echo "Use:"
echo "  Worker EC2               -> Instance Profile: WorkerInstanceProfile"
echo "  Load Balancer/Autoscaler -> Instance Profile: LBAutoscalerInstanceProfile"