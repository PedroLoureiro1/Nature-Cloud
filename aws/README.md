# STEPS

1. Set the /resources/config.sh 

```bash
export PATH=<path to aws installation>:$PATH
export AWS_DEFAULT_REGION=<aws region, e.g. us-east-1>
export AWS_ACCOUNT_ID=<insert here your aws account id>
export AWS_ACCESS_KEY_ID=<insert here your aws access key>
export AWS_SECRET_ACCESS_KEY=<insert here your aws secret access key>
export AWS_EC2_SSH_KEYPAR_PATH=<path to aws ssh keypair>
export AWS_SECURITY_GROUP=<name of your security group>
export AWS_KEYPAIR_NAME=<name of your aws keypair>
```

Access keys must be created and exported to resources before

## EC2 webserver image creation

```bash
cd core
./create-Image.sh
```

## Enter EC2 instance

```bash
ssh -o StrictHostKeyChecking=no -i $AWS_EC2_SSH_KEYPAR_PATH ec2-user@$(cat ../resources/instance.dns)
```