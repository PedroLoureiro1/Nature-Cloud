#!/bin/bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

source "$REPO_ROOT/aws/resources/config.sh"

INSTANCE_DNS="$(cat "$REPO_ROOT/aws/resources/instance.dns")"
SSH_TARGET="ec2-user@${INSTANCE_DNS}"

# Install java and maven
cmd="sudo yum update -y; sudo yum install tmux -y; sudo yum install java-11-amazon-corretto-devel.x86_64 maven -y;"
ssh -o StrictHostKeyChecking=no -i "$AWS_EC2_SSH_KEYPAR_PATH" "$SSH_TARGET" "$cmd"

# Copy modules to EC2 instance (model must be copied so it can be installed first)
echo "Copying modules to EC2 instance..."
ssh -o StrictHostKeyChecking=no -i "$AWS_EC2_SSH_KEYPAR_PATH" "$SSH_TARGET" \
  "mkdir -p /home/ec2-user/project"
scp -o StrictHostKeyChecking=no -i "$AWS_EC2_SSH_KEYPAR_PATH" -r \
  "$REPO_ROOT/pom.xml" \
  "$REPO_ROOT/model" \
  "$REPO_ROOT/smart-balancer" \
  "$REPO_ROOT/auto-scaler" \
  "$REPO_ROOT/model-training" \
  "$SSH_TARGET:/home/ec2-user/project/"

# Build in dependency order: root pom → model (install) → smart-balancer, auto-scaler, model-training
echo "Building modules..."
cmd="cd /home/ec2-user/project \
  && mvn install -N -DskipTests \
  && (cd model && mvn clean install -DskipTests) \
  && (cd smart-balancer && mvn clean package -DskipTests) \
  && (cd auto-scaler && mvn clean package -DskipTests) \
  && (cd model-training && mvn clean package -DskipTests)"
ssh -o StrictHostKeyChecking=no -i "$AWS_EC2_SSH_KEYPAR_PATH" "$SSH_TARGET" "$cmd"

# Create state directory for the regression training service (stores per-workload cursors)
ssh -o StrictHostKeyChecking=no -i "$AWS_EC2_SSH_KEYPAR_PATH" "$SSH_TARGET" \
  "sudo mkdir -p /var/lib/cnv-regression && sudo chown ec2-user:ec2-user /var/lib/cnv-regression"

# ── start-smart-lb.sh ────────────────────────────────────────────────────────
ssh -o StrictHostKeyChecking=no -i "$AWS_EC2_SSH_KEYPAR_PATH" "$SSH_TARGET" \
"cat > /home/ec2-user/start-smart-lb.sh << 'EOFSCRIPT'
#!/bin/bash
set -euo pipefail
JAR=\$(find /home/ec2-user/project/smart-balancer/target -maxdepth 1 -name 'smart-balancer-*-jar-with-dependencies.jar' | head -1)
exec java -jar \"\$JAR\"
EOFSCRIPT
chmod +x /home/ec2-user/start-smart-lb.sh
"

# ── cnv-smart-lb.service ─────────────────────────────────────────────────────
ssh -o StrictHostKeyChecking=no -i "$AWS_EC2_SSH_KEYPAR_PATH" "$SSH_TARGET" \
"cat > /tmp/cnv-smart-lb.service << EOFUNIT
[Unit]
Description=CNV Smart Load Balancer
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=ec2-user
WorkingDirectory=/home/ec2-user/project
ExecStart=/home/ec2-user/start-smart-lb.sh
Restart=always
RestartSec=5
Environment=AWS_DEFAULT_REGION=${AWS_DEFAULT_REGION}
Environment=SMART_LB_PORT=9000
Environment=SMART_LB_BACKEND_PORT=8000
Environment=SMART_LB_ASG_NAME=CNV-AutoScalingGroup
Environment=SMART_LB_TARGET_REFRESH_SECONDS=10
Environment=SMART_LB_MODEL_REFRESH_SECONDS=30
Environment=SMART_LB_WORKER_COMPLEXITY_THRESHOLD=1900000000000
Environment=SMART_LB_MODEL_DIR=/home/ec2-user/project/model-training/models
Environment=HHS_MODEL_TABLE=hhs-regression-models

[Install]
WantedBy=multi-user.target
EOFUNIT
sudo mv /tmp/cnv-smart-lb.service /etc/systemd/system/cnv-smart-lb.service
sudo systemctl daemon-reload
sudo systemctl disable cnv-smart-lb.service
"

# ── start-auto-scaler.sh ─────────────────────────────────────────────────────
ssh -o StrictHostKeyChecking=no -i "$AWS_EC2_SSH_KEYPAR_PATH" "$SSH_TARGET" \
"cat > /home/ec2-user/start-auto-scaler.sh << 'EOFSCRIPT'
#!/bin/bash
set -euo pipefail
TOKEN=\$(curl -sf --connect-timeout 2 -X PUT "http://169.254.169.254/latest/api/token" -H "X-aws-ec2-metadata-token-ttl-seconds: 21600" 2>/dev/null || true)
if [[ -n "\$TOKEN" ]]; then
    export IMAGE_ID=\$(curl -sf --connect-timeout 2 -H "X-aws-ec2-metadata-token: \$TOKEN" "http://169.254.169.254/latest/meta-data/ami-id" 2>/dev/null || true)
fi
JAR=\$(find /home/ec2-user/project/auto-scaler/target -maxdepth 1 -name 'auto-scaler-*-jar-with-dependencies.jar' | head -1)
exec java -jar "\$JAR"
EOFSCRIPT
chmod +x /home/ec2-user/start-auto-scaler.sh
"

# ── cnv-auto-scaler.service ──────────────────────────────────────────────────
ssh -o StrictHostKeyChecking=no -i "$AWS_EC2_SSH_KEYPAR_PATH" "$SSH_TARGET" \
"cat > /tmp/cnv-auto-scaler.service << EOFUNIT
[Unit]
Description=CNV Auto Scaler
After=network-online.target cnv-smart-lb.service
Wants=network-online.target

[Service]
Type=simple
User=ec2-user
ExecStart=/home/ec2-user/start-auto-scaler.sh
Restart=always
RestartSec=10
Environment=AWS_DEFAULT_REGION=${AWS_DEFAULT_REGION}
Environment=AWS_KEYPAIR_NAME=${AWS_KEYPAIR_NAME}
Environment=AWS_SECURITY_GROUP=${AWS_SECURITY_GROUP}
Environment=AWS_INSTANCE_PROFILE_NAME=${AWS_WORKER_PROFILE_NAME:-WorkerInstanceProfile}
Environment=ASG_MIN_SIZE=${ASG_MIN_SIZE}
Environment=ASG_MAX_SIZE=${ASG_MAX_SIZE}
Environment=SMART_LB_WORKER_COMPLEXITY_THRESHOLD=1900000000000

[Install]
WantedBy=multi-user.target
EOFUNIT
sudo mv /tmp/cnv-auto-scaler.service /etc/systemd/system/cnv-auto-scaler.service
sudo systemctl daemon-reload
sudo systemctl disable cnv-auto-scaler.service
"

# ── start-model-training.sh ──────────────────────────────────────────────────
ssh -o StrictHostKeyChecking=no -i "$AWS_EC2_SSH_KEYPAR_PATH" "$SSH_TARGET" \
"cat > /home/ec2-user/start-model-training.sh << 'EOFSCRIPT'
#!/bin/bash
set -euo pipefail
JAR=\$(find /home/ec2-user/project/model-training/target -maxdepth 1 -name 'model-training-*-jar-with-dependencies.jar' | head -1)
exec java -jar \"\$JAR\"
EOFSCRIPT
chmod +x /home/ec2-user/start-model-training.sh
"

# ── cnv-model-training.service + cnv-model-training.timer ───────────────────
ssh -o StrictHostKeyChecking=no -i "$AWS_EC2_SSH_KEYPAR_PATH" "$SSH_TARGET" \
"cat > /tmp/cnv-model-training.service << EOFUNIT
[Unit]
Description=CNV Model Training (oneshot, triggered by timer)

[Service]
Type=oneshot
User=ec2-user
WorkingDirectory=/home/ec2-user/project
ExecStart=/home/ec2-user/start-model-training.sh
Environment=AWS_DEFAULT_REGION=${AWS_DEFAULT_REGION}
Environment=HHS_DDB_TABLE=hhs-workload-metrics
Environment=HHS_MODEL_TABLE=hhs-regression-models
Environment=CNV_REGRESSION_STATE_DIR=/var/lib/cnv-regression
EOFUNIT

cat > /tmp/cnv-model-training.timer << EOFUNIT
[Unit]
Description=CNV Model Training Timer (every 5 minutes)

[Timer]
OnBootSec=2min
OnUnitActiveSec=5min

[Install]
WantedBy=timers.target
EOFUNIT

sudo mv /tmp/cnv-model-training.service /etc/systemd/system/cnv-model-training.service
sudo mv /tmp/cnv-model-training.timer /etc/systemd/system/cnv-model-training.timer
sudo systemctl daemon-reload
sudo systemctl disable cnv-model-training.timer
"

echo "Load balancer installation complete"
