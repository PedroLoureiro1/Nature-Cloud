#!/bin/bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CHECKPOINT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

source "$CHECKPOINT_ROOT/resources/config.sh"
INSTANCE_PROFILE_NAME="${AWS_INSTANCE_PROFILE_NAME:-CNV-Ec2InstanceProfile}"

# Ensure IAM role/profile exist before launching the VM.
"$SCRIPT_DIR/setup-ec2-iam-profile.sh"

# Run new instance.
echo "Launching instance with IAM Instance Profile: ${INSTANCE_PROFILE_NAME}"
aws ec2 run-instances \
	--image-id resolve:ssm:/aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-x86_64 \
	--instance-type t3.micro \
	--key-name $AWS_KEYPAIR_NAME \
	--security-group-ids $AWS_SECURITY_GROUP \
	--monitoring Enabled=true \
	--iam-instance-profile Name="$INSTANCE_PROFILE_NAME" | jq -r ".Instances[0].InstanceId" > "$CHECKPOINT_ROOT/resources/instance.id"
echo "New instance with id $(cat "$CHECKPOINT_ROOT/resources/instance.id")."

# Wait for instance to be running.
aws ec2 wait instance-running --instance-ids "$(cat "$CHECKPOINT_ROOT/resources/instance.id")"
echo "New instance with id $(cat "$CHECKPOINT_ROOT/resources/instance.id") is now running."

# Extract DNS name.
aws ec2 describe-instances \
	--instance-ids "$(cat "$CHECKPOINT_ROOT/resources/instance.id")" | jq -r ".Reservations[0].Instances[0].NetworkInterfaces[0].PrivateIpAddresses[0].Association.PublicDnsName" > "$CHECKPOINT_ROOT/resources/instance.dns"
echo "New instance with id $(cat "$CHECKPOINT_ROOT/resources/instance.id") has address $(cat "$CHECKPOINT_ROOT/resources/instance.dns")."

# Wait for instance to have SSH ready.
while ! nc -z "$(cat "$CHECKPOINT_ROOT/resources/instance.dns")" 22; do
	echo "Waiting for $(cat "$CHECKPOINT_ROOT/resources/instance.dns"):22 (SSH)..."
	sleep 0.5
done
echo "New instance with id $(cat "$CHECKPOINT_ROOT/resources/instance.id") is ready for SSH access."
