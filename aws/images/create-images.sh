#!/bin/bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

source ../resources/config.sh

# Step 1: launch a vm instance for worker AMI
bash launch-vm.sh
WORKER_INSTANCE_ID="$(cat ../resources/instance.id)"

# Step 2: install worker-specific software
bash install-worker.sh

# Step 3: test worker VM
bash test-vm.sh

# Step 4: create worker AMI
WORKER_IMAGE_ID="$(aws ec2 create-image --instance-id "$WORKER_INSTANCE_ID" --name Nature-Webserver-Worker --query ImageId --output text)"
printf '%s\n' "$WORKER_IMAGE_ID" > ../resources/worker-image.id
echo "New worker AMI with id $WORKER_IMAGE_ID."

# Step 5: wait for worker image
aws ec2 wait image-available --image-ids "$WORKER_IMAGE_ID"
echo "Worker image ready!"

# Step 6: terminate worker instance
aws ec2 terminate-instances --instance-ids "$WORKER_INSTANCE_ID"
aws ec2 wait instance-terminated --instance-ids "$WORKER_INSTANCE_ID"

# Step 7: launch a new vm for load balancer AMI
bash launch-vm.sh
LB_INSTANCE_ID="$(cat ../resources/instance.id)"

# Step 8: install load balancer + auto-scaler only
bash install-lb_as.sh

# Step 9: create load balancer AMI
LB_AS_IMAGE_ID="$(aws ec2 create-image --instance-id "$LB_INSTANCE_ID" --name Nature-Webserver-LB --query ImageId --output text)"
printf '%s\n' "$LB_AS_IMAGE_ID" > ../resources/lb_as-image.id
echo "New load balancer AMI with id $LB_AS_IMAGE_ID."

# Step 10: wait for LB image
aws ec2 wait image-available --image-ids "$LB_AS_IMAGE_ID"
echo "Load balancer image ready!"

# Step 11: terminate LB instance
aws ec2 terminate-instances --instance-ids "$LB_INSTANCE_ID"
aws ec2 wait instance-terminated --instance-ids "$LB_INSTANCE_ID"
