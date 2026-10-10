#!/bin/bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
AWS_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

source "${AWS_DIR}/resources/config.sh"

ROLE_NAME="${EC2_ROLE_NAME:-CNV-Ec2MetricsRole}"
INSTANCE_PROFILE_NAME="${AWS_INSTANCE_PROFILE_NAME:-CNV-Ec2InstanceProfile}"
INLINE_POLICY_NAME="${EC2_INLINE_POLICY_NAME:-CNV-Ec2RuntimeAccess}"
METRICS_TABLE="${HHS_DDB_TABLE:-hhs-workload-metrics}"
MODELS_TABLE="${HHS_MODEL_TABLE:-hhs-regression-models}"

if [[ -z "${AWS_DEFAULT_REGION:-}" ]]; then
    echo "AWS_DEFAULT_REGION is not set in aws/resources/config.sh"
    exit 1
fi

TRUST_DOC="$(mktemp)"
POLICY_DOC="$(mktemp)"

cat > "${TRUST_DOC}" <<'EOF'
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Principal": {
        "Service": "ec2.amazonaws.com"
      },
      "Action": "sts:AssumeRole"
    }
  ]
}
EOF

ACCOUNT_ID="${AWS_ACCOUNT_ID:-$(aws sts get-caller-identity --query Account --output text)}"
METRICS_TABLE_ARN="arn:aws:dynamodb:${AWS_DEFAULT_REGION}:${ACCOUNT_ID}:table/${METRICS_TABLE}"
MODELS_TABLE_ARN="arn:aws:dynamodb:${AWS_DEFAULT_REGION}:${ACCOUNT_ID}:table/${MODELS_TABLE}"

cat > "${POLICY_DOC}" <<EOF
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": [
        "dynamodb:BatchWriteItem",
        "dynamodb:PutItem",
        "dynamodb:GetItem",
        "dynamodb:Query",
        "dynamodb:Scan",
        "dynamodb:DescribeTable"
      ],
      "Resource": [
        "${METRICS_TABLE_ARN}",
        "${MODELS_TABLE_ARN}"
      ]
    },
    {
      "Effect": "Allow",
      "Action": [
        "autoscaling:DescribeAutoScalingGroups",
        "ec2:DescribeInstances",
        "lambda:InvokeFunction"
      ],
      "Resource": "*"
    }
  ]
}
EOF

# 1) Create role if needed.
if aws iam get-role --role-name "${ROLE_NAME}" >/dev/null 2>&1; then
    echo "Role exists: ${ROLE_NAME}"
else
    echo "Creating role: ${ROLE_NAME}"
    aws iam create-role \
        --role-name "${ROLE_NAME}" \
        --assume-role-policy-document "file://${TRUST_DOC}" >/dev/null
fi

# 2) Apply/refresh inline policy.
aws iam put-role-policy \
    --role-name "${ROLE_NAME}" \
    --policy-name "${INLINE_POLICY_NAME}" \
    --policy-document "file://${POLICY_DOC}" >/dev/null

# 3) Create instance profile if needed.
if aws iam get-instance-profile --instance-profile-name "${INSTANCE_PROFILE_NAME}" >/dev/null 2>&1; then
    echo "Instance profile exists: ${INSTANCE_PROFILE_NAME}"
else
    echo "Creating instance profile: ${INSTANCE_PROFILE_NAME}"
    aws iam create-instance-profile \
        --instance-profile-name "${INSTANCE_PROFILE_NAME}" >/dev/null
fi

# 4) Attach role to profile if missing.
PROFILE_ROLES="$(aws iam get-instance-profile \
    --instance-profile-name "${INSTANCE_PROFILE_NAME}" \
    --query "InstanceProfile.Roles[].RoleName" \
    --output text)"

if [[ " ${PROFILE_ROLES} " == *" ${ROLE_NAME} "* ]]; then
    echo "Role already attached."
else
    echo "Attaching role to profile."
    aws iam add-role-to-instance-profile \
        --instance-profile-name "${INSTANCE_PROFILE_NAME}" \
        --role-name "${ROLE_NAME}" >/dev/null
fi

# IAM propagation delay.
sleep 10

rm -f "${TRUST_DOC}" "${POLICY_DOC}"
echo "Done."
