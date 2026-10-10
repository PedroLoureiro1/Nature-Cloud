# nature-at-cloud-aws

# Setup

## Step 1 — Manual AWS setup (do this once)

### 1.1 Create a Security Group

Follow the same approach used in the CNV labs:

1. Go to **EC2 → Security Groups → Create security group**
2. Add the following **Inbound rules**:

| Type | Protocol | Port range | Source | Purpose |
|---|---|---|---|---|
| SSH | TCP | 22 | My IP (or `0.0.0.0/0` for lab convenience) | SSH access |
| Custom TCP | TCP | 8000 | `0.0.0.0/0` | Worker webserver |
| Custom TCP | TCP | 9000 | `0.0.0.0/0` | Smart load balancer |
| All traffic | All | All | the security group's own ID | Instance-to-instance communication |

3. Leave outbound as **All traffic** (default)
4. The **Security Group ID** will be nedeed for the Step 2

### 1.2 Allocate an Elastic IP for the Load Balancer

```bash
aws ec2 allocate-address --domain vpc --region eu-north-1 
#choose the same region that will be used in the config.sh
```

Note the `AllocationId` from the output (e.g. `eipalloc-0abc123...`) needs to be filled in step 2.

---

## Step 2 — Fill in `aws/resources/config.sh`

Open `aws/resources/config.sh` and set every variable:

```bash
export AWS_DEFAULT_REGION=<your region, e.g. eu-north-1>
export AWS_ACCOUNT_ID=<your 12-digit account ID>
export AWS_ACCESS_KEY_ID=<your IAM access key>
export AWS_SECRET_ACCESS_KEY=<your IAM secret key>
export AWS_EC2_SSH_KEYPAR_PATH=<local path to your .pem key file>
export AWS_SECURITY_GROUP=<security group ID from step 1.1>
export AWS_KEYPAIR_NAME=<name of your EC2 key pair>
export AWS_BALANCER_EIP_ALLOC=<elastic IP allocation ID from step 1.2>
export ASG_MIN_SIZE=1
export ASG_MAX_SIZE=2
export ASG_DESIRED_CAPACITY=1
```

Access keys are created in **IAM → Users → Security credentials → Create access key**, the same way it was done in the labs.

---

## Step 3 — Create the AMIs (one-time, ~10 minutes)


```bash
bash aws/images/create-images.sh
```

---

## Step 4 — Deploy

```bash
cd aws/deployment
bash launch-deployment-template.sh
```

This script automatically:
1. Sets up the EC2 IAM instance profile (`setup-ec2-iam-profile.sh`)
2. Creates DynamoDB tables (`aws/dynamodb/setup-dynamodb.sh`)
3. Deploys Lambda functions for workload overflow (`aws/lambda-workloads/install-workload-lambdas.sh`)
4. Launches worker EC2 instances and the balancer VM
5. Assigns the Elastic IP to the balancer

The balancer's public IP is the Elastic IP you allocated in step 1.2.

---

## Step 5 — Teardown

```bash
cd aws/deployment
bash terminate-deployment-template.sh
```

This terminates all EC2 instances launched by the deployment. The Elastic IP, DynamoDB tables, Lambda functions, and AMIs are **not** deleted automatically — remove them manually in the console if needed.
