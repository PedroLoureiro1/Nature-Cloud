#!/bin/bash

set -euo pipefail

source ../resources/config.sh

INSTANCE_DNS="$(cat ../resources/instance.dns)"
SSH_TARGET="ec2-user@${INSTANCE_DNS}"

# Install java and maven.
cmd="sudo yum update -y; sudo yum install java-11-amazon-corretto-devel.x86_64 maven -y;"
ssh -o StrictHostKeyChecking=no -i "$AWS_EC2_SSH_KEYPAR_PATH" "$SSH_TARGET" "$cmd"

# Copy project modules to EC2 instance
echo "Copying project to EC2 instance..."
ssh -o StrictHostKeyChecking=no -i "$AWS_EC2_SSH_KEYPAR_PATH" "$SSH_TARGET" "mkdir -p /home/ec2-user/project"
scp -o StrictHostKeyChecking=no -i "$AWS_EC2_SSH_KEYPAR_PATH" -r \
  ../../../pom.xml \
  ../../../instrumentation-agent \
  ../../../webserver \
  ../../../dna \
  ../../../fractals \
  ../../../grayscott \
  "$SSH_TARGET:/home/ec2-user/project/"

# Build Maven project
echo "Building multi-module Maven project..."
cmd="cd /home/ec2-user/project && mvn -DskipTests clean package"
ssh -o StrictHostKeyChecking=no -i "$AWS_EC2_SSH_KEYPAR_PATH" "$SSH_TARGET" "$cmd"

# Check if build succeeded (match actual shaded jar naming)
cmd="test -f /home/ec2-user/project/instrumentation-agent/target/instrumentation-agent-*.jar && echo 'Build OK' || echo 'Build FAILED'"
ssh -o StrictHostKeyChecking=no -i "$AWS_EC2_SSH_KEYPAR_PATH" "$SSH_TARGET" "$cmd"

# Verify instrumentation agent JAR built
cmd="ls -lh /home/ec2-user/project/instrumentation-agent/target/instrumentation-agent-1.0.0-SNAPSHOT.jar 2>&1"
ssh -o StrictHostKeyChecking=no -i "$AWS_EC2_SSH_KEYPAR_PATH" "$SSH_TARGET" "$cmd"

# Setup web server to start on instance boot (persistent script + systemd)
ssh -o StrictHostKeyChecking=no -i "$AWS_EC2_SSH_KEYPAR_PATH" "$SSH_TARGET" \
"cat > /home/ec2-user/start-server.sh << 'EOFSCRIPT'
#!/bin/bash
set -euo pipefail
cd /home/ec2-user/project
AGENT_JAR=\$(find instrumentation-agent/target -maxdepth 1 -type f -name 'instrumentation-agent-*.jar' | head -1)
WEBSERVER_JAR=\$(find webserver/target -maxdepth 1 -type f -name 'webserver-*-jar-with-dependencies.jar' | head -1)
exec java -javaagent:\$AGENT_JAR=WheightedInstructions:pt.ulisboa.tecnico.cnv:/tmp/instrumentation -cp \$WEBSERVER_JAR pt.ulisboa.tecnico.cnv.webserver.WebServer
EOFSCRIPT
chmod +x /home/ec2-user/start-server.sh
"

ssh -o StrictHostKeyChecking=no -i "$AWS_EC2_SSH_KEYPAR_PATH" "$SSH_TARGET" \
"cat > /tmp/cnv-webserver.service << 'EOFUNIT'
[Unit]
Description=CNV Webserver
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=ec2-user
WorkingDirectory=/home/ec2-user/project
ExecStart=/home/ec2-user/start-server.sh
Restart=always
RestartSec=2

[Install]
WantedBy=multi-user.target
EOFUNIT
sudo mv /tmp/cnv-webserver.service /etc/systemd/system/cnv-webserver.service
sudo systemctl daemon-reload
sudo systemctl enable cnv-webserver.service
sudo systemctl restart cnv-webserver.service
sudo systemctl --no-pager --full status cnv-webserver.service
"
echo "Installation complete"
