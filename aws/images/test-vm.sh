#!/bin/bash

set -euo pipefail

source ../resources/config.sh

INSTANCE_ID="$(cat ../resources/instance.id)"
INSTANCE_DNS="$(cat ../resources/instance.dns)"

# Requesting an instance reboot.
aws ec2 reboot-instances --instance-ids "$INSTANCE_ID"
echo "Rebooting instance to test web server auto-start."

echo "Waiting for SSH to become available..."
until nc -z "$INSTANCE_DNS" 22; do
	echo "Waiting for ${INSTANCE_DNS}:22 (SSH)..."
	sleep 1
done

# Wait for port 8000 to become available with timeout.
MAX_RETRIES=120
RETRY=0
until nc -z "$INSTANCE_DNS" 8000; do
	RETRY=$((RETRY + 1))
	if [ "$RETRY" -ge "$MAX_RETRIES" ]; then
		echo "Timed out waiting for ${INSTANCE_DNS}:8000."
		ssh -o StrictHostKeyChecking=no -i "$AWS_EC2_SSH_KEYPAR_PATH" "ec2-user@${INSTANCE_DNS}" "sudo systemctl --no-pager status cnv-webserver.service || true; sudo journalctl -u cnv-webserver.service -n 100 --no-pager || true"
		exit 1
	fi
	echo "Waiting for ${INSTANCE_DNS}:8000... (${RETRY}/${MAX_RETRIES})"
	sleep 1
done

# Sending a query.
echo "Waiting for HTTP /test endpoint to become ready..."
MAX_HTTP_RETRIES=60
HTTP_RETRY=0
until curl -fsS --max-time 3 "http://${INSTANCE_DNS}:8000/test?testing-after-reboot" >/dev/null; do
	HTTP_RETRY=$((HTTP_RETRY + 1))
	if [ "$HTTP_RETRY" -ge "$MAX_HTTP_RETRIES" ]; then
		echo "Timed out waiting for HTTP readiness on ${INSTANCE_DNS}:8000/test."
		ssh -o StrictHostKeyChecking=no -i "$AWS_EC2_SSH_KEYPAR_PATH" "ec2-user@${INSTANCE_DNS}" "sudo systemctl --no-pager status cnv-webserver.service || true; sudo journalctl -u cnv-webserver.service -n 100 --no-pager || true"
		exit 1
	fi
	echo "HTTP not ready yet (${HTTP_RETRY}/${MAX_HTTP_RETRIES})..."
	sleep 1
done
echo "HTTP /test is ready."
