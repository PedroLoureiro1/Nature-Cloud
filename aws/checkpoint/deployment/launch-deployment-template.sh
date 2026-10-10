#!/bin/bash

set -euo pipefail

source ../resources/config.sh

INSTANCE_PROFILE_NAME="${AWS_INSTANCE_PROFILE_NAME:-CNV-Ec2InstanceProfile}"

# Ensure DynamoDB tables exist before deployment.
bash ../dynamodb/setup-dynamodb.sh

# Ensure IAM role/profile exist before creating launch templates.
bash ../core/setup-ec2-iam-profile.sh

# Create load balancer and configure health check.
aws elb create-load-balancer \
    --load-balancer-name CNV-LoadBalancer \
    --listeners "Protocol=HTTP,LoadBalancerPort=80,InstanceProtocol=HTTP,InstancePort=8000" \
    --availability-zones "${AWS_DEFAULT_REGION}b"

aws elb configure-health-check \
    --load-balancer-name CNV-LoadBalancer \
    --health-check Target=HTTP:8000/test,Interval=30,UnhealthyThreshold=2,HealthyThreshold=10,Timeout=5

WORKER_USER_DATA_B64="$(printf '%s' '#!/bin/bash
set -euxo pipefail
systemctl enable cnv-webserver.service || true
systemctl restart cnv-webserver.service || true
systemctl disable cnv-smart-lb.service || true
systemctl stop cnv-smart-lb.service || true
systemctl disable cnv-regression-refresh.timer || true
systemctl stop cnv-regression-refresh.timer || true
' | base64 | tr -d '\n')"

aws ec2 create-launch-template \
    --launch-template-name CNV-LaunchTemplate \
    --version-description "v1" \
    --launch-template-data "{
    \"ImageId\": \"$(cat ../resources/image.id)\",
    \"InstanceType\": \"t3.micro\",
    \"KeyName\": \"${AWS_KEYPAIR_NAME}\",
    \"SecurityGroupIds\": [\"${AWS_SECURITY_GROUP}\"],
    \"Monitoring\": {\"Enabled\": true},
    \"UserData\": \"${WORKER_USER_DATA_B64}\",
    \"IamInstanceProfile\": {\"Name\": \"${INSTANCE_PROFILE_NAME}\"}
}"

# Create auto scaling group with launch template.
aws autoscaling create-auto-scaling-group \
    --auto-scaling-group-name CNV-AutoScalingGroup \
    --launch-template LaunchTemplateName=CNV-LaunchTemplate,Version=1 \
    --load-balancer-names CNV-LoadBalancer \
    --availability-zones "${AWS_DEFAULT_REGION}b" \
    --health-check-type ELB \
    --health-check-grace-period 60 \
    --min-size "${ASG_MIN_SIZE}" \
    --max-size "${ASG_MAX_SIZE}" \
    --desired-capacity "${ASG_DESIRED_CAPACITY}"


SCALE_OUT_POLICY_ARN="$(
    aws autoscaling put-scaling-policy \
        --auto-scaling-group-name CNV-AutoScalingGroup \
        --policy-name CNV-CpuScaleOutPolicy \
        --policy-type StepScaling \
        --adjustment-type ChangeInCapacity \
        --cooldown 60 \
        --step-adjustments '[
            {
                "MetricIntervalLowerBound": 0,
                "ScalingAdjustment": 1
            }
        ]' \
        --query 'PolicyARN' \
        --output text
)"

SCALE_IN_POLICY_ARN="$(
    aws autoscaling put-scaling-policy \
        --auto-scaling-group-name CNV-AutoScalingGroup \
        --policy-name CNV-CpuScaleInPolicy \
        --policy-type StepScaling \
        --adjustment-type ChangeInCapacity \
        --cooldown 300 \
        --step-adjustments '[
            {
                "MetricIntervalUpperBound": 0,
                "ScalingAdjustment": -1
            }
        ]' \
        --query 'PolicyARN' \
        --output text
)"

aws cloudwatch put-metric-alarm \
    --alarm-name CNV-CpuHighAlarm \
    --alarm-description "Scale out when CPU stays above 70%" \
    --namespace AWS/EC2 \
    --metric-name CPUUtilization \
    --dimensions Name=AutoScalingGroupName,Value=CNV-AutoScalingGroup \
    --statistic Average \
    --period 30 \
    --evaluation-periods 2 \
    --datapoints-to-alarm 2 \
    --threshold 70 \
    --comparison-operator GreaterThanOrEqualToThreshold \
    --treat-missing-data notBreaching \
    --alarm-actions "${SCALE_OUT_POLICY_ARN}"

    aws cloudwatch put-metric-alarm \
    --alarm-name CNV-CpuLowAlarm \
    --alarm-description "Scale in when CPU stays below 40%" \
    --namespace AWS/EC2 \
    --metric-name CPUUtilization \
    --dimensions Name=AutoScalingGroupName,Value=CNV-AutoScalingGroup \
    --statistic Average \
    --period 60 \
    --evaluation-periods 5 \
    --datapoints-to-alarm 5 \
    --threshold 40 \
    --comparison-operator LessThanOrEqualToThreshold \
    --treat-missing-data notBreaching \
    --alarm-actions "${SCALE_IN_POLICY_ARN}"

# For easy metrics verification
aws autoscaling enable-metrics-collection \
    --auto-scaling-group-name CNV-AutoScalingGroup \
    --granularity "1Minute"

# Simple Dashboard for quick monitoring.
DASHBOARD_BODY='{
    "widgets": [
        {
            "type": "metric",
            "x": 0,
            "y": 0,
            "width": 8,
            "height": 6,
            "properties": {
                "metrics": [
                    [ "AWS/AutoScaling", "GroupInServiceCapacity", "AutoScalingGroupName", "CNV-AutoScalingGroup" ]
                ],
                "view": "singleValue",
                "region": "'"${AWS_DEFAULT_REGION}"'",
                "stat": "Maximum",
                "period": 60,
                "title": "Total Live Instances"
            }
        },
        {
            "type": "metric",
            "x": 8,
            "y": 0,
            "width": 16,
            "height": 6,
            "properties": {
                "metrics": [
                    [ "AWS/EC2", "CPUUtilization", "AutoScalingGroupName", "CNV-AutoScalingGroup" ]
                ],
                "view": "timeSeries",
                "stacked": false,
                "region": "'"${AWS_DEFAULT_REGION}"'",
                "stat": "Average",
                "period": 60,
                "title": "Average Group CPU Utilization",
                "yAxis": {
                    "left": {
                        "min": 0,
                        "max": 100
                    }
                }
            }
        },
        {
            "type": "alarm",
            "x": 0,
            "y": 6,
            "width": 24,
            "height": 6,
            "properties": {
                "title": "Scaling Alarms Status",
                "alarms": [
                    "arn:aws:cloudwatch:'"${AWS_DEFAULT_REGION}"':'"${AWS_ACCOUNT_ID}"':alarm:CNV-CpuHighAlarm",
                    "arn:aws:cloudwatch:'"${AWS_DEFAULT_REGION}"':'"${AWS_ACCOUNT_ID}"':alarm:CNV-CpuLowAlarm"
                ]
            }
        }
    ]
}'

# Create or update the CloudWatch Dashboard
aws cloudwatch put-dashboard \
    --dashboard-name "CNV-Infrastructure-Dashboard" \
    --dashboard-body "$DASHBOARD_BODY"