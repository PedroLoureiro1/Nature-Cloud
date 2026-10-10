#!/bin/bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CHECKPOINT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

source "$CHECKPOINT_ROOT/resources/config.sh"

# Step 1: launch a vm instance.
bash "$SCRIPT_DIR/launch-vm.sh"

# Step 2: install software in the VM instance.
bash "$SCRIPT_DIR/install-vm.sh"

# Step 3: test VM instance.
bash "$SCRIPT_DIR/test-vm.sh"

# Step 4: create VM image (AIM).
IMAGE_NAME="Nature-Webserver-$(date +%Y%m%d-%H%M%S)"
INSTANCE_ID="$(cat "$CHECKPOINT_ROOT/resources/instance.id")"
aws ec2 create-image --instance-id "$INSTANCE_ID" --name "$IMAGE_NAME" \
	| jq -r .ImageId > "$CHECKPOINT_ROOT/resources/image.id"
IMAGE_ID="$(cat "$CHECKPOINT_ROOT/resources/image.id")"
echo "New VM image with id ${IMAGE_ID} (${IMAGE_NAME})."

# Step 5: Wait for image to become available.
echo "Waiting for image to be ready... (this can take a couple of minutes)"
aws ec2 wait image-available --image-ids "$IMAGE_ID"
echo "Waiting for image to be ready... done! \o/"

# Step 6: terminate the vm instance.
aws ec2 terminate-instances --instance-ids "$INSTANCE_ID"
