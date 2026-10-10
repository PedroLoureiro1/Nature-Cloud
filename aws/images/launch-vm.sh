#!/bin/bash

set -euo pipefail

source ../resources/config.sh

# Run new instance.
echo "Launching instance"
aws ec2 run-instances \
	--image-id resolve:ssm:/aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-x86_64 \
	--instance-type t3.micro \
	--key-name $AWS_KEYPAIR_NAME \
	--security-group-ids $AWS_SECURITY_GROUP \
	--monitoring Enabled=true | jq -r ".Instances[0].InstanceId" > ../resources/instance.id
echo "New instance with id $(cat ../resources/instance.id)."

# Wait for instance to be running.
aws ec2 wait instance-running --instance-ids $(cat ../resources/instance.id)
echo "New instance with id $(cat ../resources/instance.id) is now running."

# Extract DNS name.
aws ec2 describe-instances \
	--instance-ids $(cat ../resources/instance.id) | jq -r ".Reservations[0].Instances[0].NetworkInterfaces[0].PrivateIpAddresses[0].Association.PublicDnsName" > ../resources/instance.dns
echo "New instance with id $(cat ../resources/instance.id) has address $(cat ../resources/instance.dns)."

# Wait for instance to have SSH ready.
while ! nc -z $(cat ../resources/instance.dns) 22; do
	echo "Waiting for $(cat ../resources/instance.dns):22 (SSH)..."
	sleep 0.5
done
echo "New instance with id $(cat ../resources/instance.id) is ready for SSH access."
