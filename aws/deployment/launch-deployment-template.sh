#!/bin/bash

set -euo pipefail

source ../resources/config.sh
WORKER_PROFILE_NAME="${AWS_WORKER_PROFILE_NAME:-WorkerInstanceProfile}"
LB_AS_PROFILE_NAME="${AWS_LB_AS_PROFILE_NAME:-LBAutoscalerInstanceProfile}"

_CONFIG_SH="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/../resources/config.sh"
if [[ -z "${AWS_BALANCER_EIP_ALLOC:-}" || "${AWS_BALANCER_EIP_ALLOC}" == "eipalloc-REPLACE_ME" ]]; then
    echo "No Elastic IP configured — allocating a new one..."
    AWS_BALANCER_EIP_ALLOC="$(aws ec2 allocate-address --domain vpc --query AllocationId --output text)"
    sed -i "s/export AWS_BALANCER_EIP_ALLOC=\"[^\"]*\"/export AWS_BALANCER_EIP_ALLOC=\"${AWS_BALANCER_EIP_ALLOC}\"/" "$_CONFIG_SH"
    echo "Allocated and saved: $AWS_BALANCER_EIP_ALLOC"
elif ! aws ec2 describe-addresses --allocation-ids "$AWS_BALANCER_EIP_ALLOC" --region "${AWS_DEFAULT_REGION}" &>/dev/null; then
    echo "Previous EIP $AWS_BALANCER_EIP_ALLOC no longer exists — allocating a new one..."
    AWS_BALANCER_EIP_ALLOC="$(aws ec2 allocate-address --domain vpc --query AllocationId --output text)"
    sed -i "s/export AWS_BALANCER_EIP_ALLOC=\"[^\"]*\"/export AWS_BALANCER_EIP_ALLOC=\"${AWS_BALANCER_EIP_ALLOC}\"/" "$_CONFIG_SH"
    echo "Allocated and saved: $AWS_BALANCER_EIP_ALLOC"
else
    echo "Reusing existing Elastic IP: $AWS_BALANCER_EIP_ALLOC"
fi
unset _CONFIG_SH

bash ../core/setup-ec2-iam-profile.sh

bash ../dynamodb/setup-dynamodb.sh
bash ../dynamodb/import-csv-metrics.sh
bash ../lambda-workloads/install-workload-lambdas.sh

WORKER_IMAGE_ID="$(cat ../resources/worker-image.id)"
LB_AS_IMAGE_ID="$(cat ../resources/lb_as-image.id)"

# Worker user-data: run workload only (port 8000), no smart-lb.
WORKER_USER_DATA=$(mktemp)
cat > "$WORKER_USER_DATA" <<'EOF'
#!/bin/bash
set -euxo pipefail
systemctl enable cnv-webserver.service || true
systemctl restart cnv-webserver.service || true
EOF

# Balancer user-data: associate Elastic IP on boot, run smart-lb only (port 9000).
# AWS_BALANCER_EIP_ALLOC and AWS_DEFAULT_REGION expand here (at deploy time).
# \$INSTANCE_ID and \$(curl ...) are escaped so they run on the instance at boot.
BALANCER_USER_DATA=$(mktemp)
cat > "$BALANCER_USER_DATA" <<EOF
#!/bin/bash
set -euxo pipefail
TOKEN=\$(curl -s -X PUT "http://169.254.169.254/latest/api/token" -H "X-aws-ec2-metadata-token-ttl-seconds: 21600")
INSTANCE_ID=\$(curl -s -H "X-aws-ec2-metadata-token: \$TOKEN" http://169.254.169.254/latest/meta-data/instance-id)
aws ec2 associate-address --instance-id "\$INSTANCE_ID" --allocation-id "${AWS_BALANCER_EIP_ALLOC}" --region "${AWS_DEFAULT_REGION}" --allow-reassociation
systemctl disable cnv-webserver.service || true
systemctl stop cnv-webserver.service || true
systemctl enable cnv-smart-lb.service || true
systemctl restart cnv-smart-lb.service || true
systemctl enable cnv-model-training.timer || true
systemctl start cnv-model-training.timer || true
systemctl start cnv-model-training.service || true
mkdir -p /etc/systemd/system/cnv-auto-scaler.service.d/
echo '[Service]' > /etc/systemd/system/cnv-auto-scaler.service.d/deploy.conf
echo 'Environment=IMAGE_ID=${WORKER_IMAGE_ID}' >> /etc/systemd/system/cnv-auto-scaler.service.d/deploy.conf
echo 'Environment=AWS_INSTANCE_PROFILE_NAME=WorkerInstanceProfile' >> /etc/systemd/system/cnv-auto-scaler.service.d/deploy.conf
systemctl daemon-reload
systemctl enable cnv-auto-scaler.service || true
systemctl start cnv-auto-scaler.service || true
EOF

echo "Launching ${ASG_DESIRED_CAPACITY} worker instance(s)..."
aws ec2 run-instances \
    --image-id "$WORKER_IMAGE_ID" \
    --instance-type t3.micro \
    --key-name "$AWS_KEYPAIR_NAME" \
    --security-group-ids "$AWS_SECURITY_GROUP" \
    --count "$ASG_DESIRED_CAPACITY" \
    --monitoring Enabled=true \
    --user-data "file://$WORKER_USER_DATA" \
    --iam-instance-profile Name="$WORKER_PROFILE_NAME" \
    --tag-specifications \
        "ResourceType=instance,Tags=[{Key=Name,Value=CNV-Worker},{Key=CNV-Role,Value=worker},{Key=CNV-Project,Value=cnv}]" \
    --query "Instances[].InstanceId" \
    --output text

echo "Launching balancer instance..."
aws ec2 run-instances \
    --image-id "$LB_AS_IMAGE_ID" \
    --instance-type t3.micro \
    --key-name "$AWS_KEYPAIR_NAME" \
    --security-group-ids "$AWS_SECURITY_GROUP" \
    --count 1 \
    --monitoring Enabled=true \
    --user-data "file://$BALANCER_USER_DATA" \
    --iam-instance-profile Name="$LB_AS_PROFILE_NAME" \
    --tag-specifications \
        "ResourceType=instance,Tags=[{Key=Name,Value=CNV-Balancer},{Key=CNV-Role,Value=balancer},{Key=CNV-Project,Value=cnv}]" \
    --query "Instances[].InstanceId" \
    --output text

rm -f "$WORKER_USER_DATA" "$BALANCER_USER_DATA"

BALANCER_EIP="$(aws ec2 describe-addresses \
    --allocation-ids "${AWS_BALANCER_EIP_ALLOC}" \
    --region "${AWS_DEFAULT_REGION}" \
    --query "Addresses[0].PublicIp" \
    --output text)"

echo ""
echo "=========================================="
echo "Deployment complete."
echo "Balancer entry point: http://${BALANCER_EIP}:9000"
echo "=========================================="
