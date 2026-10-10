#!/bin/bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"

source "${REPO_ROOT}/aws/resources/config.sh"

LAMBDA_ROLE_NAME="CNV-WorkloadLambdaRole"
RUNTIME="java11"
MEMORY_MB="1024"
TIMEOUT_SECONDS="360"

ASSUME_ROLE_DOC='{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Principal": { "Service": "lambda.amazonaws.com" },
      "Action": "sts:AssumeRole"
    }
  ]
}'

echo "Building workload modules..."
(cd "${REPO_ROOT}" && mvn -pl fractals,dna,grayscott -DskipTests clean package)

echo "Ensuring IAM role: ${LAMBDA_ROLE_NAME}"
if ! aws iam get-role --role-name "${LAMBDA_ROLE_NAME}" >/dev/null 2>&1; then
  aws iam create-role \
    --role-name "${LAMBDA_ROLE_NAME}" \
    --assume-role-policy-document "${ASSUME_ROLE_DOC}" \
    --region "${AWS_DEFAULT_REGION}" >/dev/null
fi

aws iam attach-role-policy \
  --role-name "${LAMBDA_ROLE_NAME}" \
  --policy-arn arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole >/dev/null

ROLE_ARN="$(aws iam get-role --role-name "${LAMBDA_ROLE_NAME}" --query 'Role.Arn' --output text)"
echo "Role ARN: ${ROLE_ARN}"

echo "Waiting for IAM role propagation..."
sleep 10

deploy_function() {
  local name="$1"
  local jar="$2"
  local handler="$3"

  if [[ ! -f "${jar}" ]]; then
    echo "ERROR: jar not found: ${jar}"
    exit 1
  fi

  if aws lambda get-function --function-name "${name}" --region "${AWS_DEFAULT_REGION}" >/dev/null 2>&1; then
    echo "Updating ${name}..."
    aws lambda update-function-code \
      --function-name "${name}" \
      --zip-file "fileb://${jar}" \
      --region "${AWS_DEFAULT_REGION}" >/dev/null
    aws lambda update-function-configuration \
      --function-name "${name}" \
      --role "${ROLE_ARN}" \
      --handler "${handler}" \
      --runtime "${RUNTIME}" \
      --timeout "${TIMEOUT_SECONDS}" \
      --memory-size "${MEMORY_MB}" \
      --region "${AWS_DEFAULT_REGION}" >/dev/null
  else
    echo "Creating ${name}..."
    aws lambda create-function \
      --function-name "${name}" \
      --role "${ROLE_ARN}" \
      --handler "${handler}" \
      --zip-file "fileb://${jar}" \
      --runtime "${RUNTIME}" \
      --timeout "${TIMEOUT_SECONDS}" \
      --memory-size "${MEMORY_MB}" \
      --region "${AWS_DEFAULT_REGION}" >/dev/null
  fi

  aws lambda wait function-active-v2 --function-name "${name}" --region "${AWS_DEFAULT_REGION}"
  echo "Active: ${name}"
}

deploy_function \
  "CNV-FractalsHandler" \
  "${REPO_ROOT}/fractals/target/fractals-1.0.0-SNAPSHOT-jar-with-dependencies.jar" \
  "pt.ulisboa.tecnico.cnv.fractals.FractalsHandler::handleRequest"

deploy_function \
  "CNV-GrayScottHandler" \
  "${REPO_ROOT}/grayscott/target/grayscott-1.0.0-SNAPSHOT-jar-with-dependencies.jar" \
  "pt.ulisboa.tecnico.cnv.grayscott.GrayScottHandler::handleRequest"

deploy_function \
  "CNV-DnaHandler" \
  "${REPO_ROOT}/dna/target/dna-1.0.0-SNAPSHOT-jar-with-dependencies.jar" \
  "pt.ulisboa.tecnico.cnv.dna.DnaHandler::handleRequest"

echo ""
echo "All 3 workload Lambda functions deployed."
echo "High-complexity requests will now overflow to Lambda instead of returning 502."
