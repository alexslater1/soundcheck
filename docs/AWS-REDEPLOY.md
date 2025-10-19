# Bringing Soundcheck back on AWS

**State: torn down on 2025-10-19.** Nothing of Soundcheck runs on AWS, and nothing there bills. Checked after the teardown: no stacks, buckets, databases or snapshots, ECR repos, log groups, Lambdas, volumes, IPs, CloudFront distributions or live secrets in eu-north-1. This page says what was deleted, what was kept, and how to put it all back from zero. README's "Running on AWS" explains each command in more depth; this is the order to run them in.

## What was deleted (account 037918102228, eu-north-1)

| Gone | Consequence |
|---|---|
| Stacks SoundcheckNetwork, Data, Edge, Compute, Observability, Web (`make aws-destroy`) | VPC, load balancer, ECS cluster and services, SQS queues, alarms, dashboard, log groups, ECR repos `soundcheck-{api,worker,lab}` and their images |
| The database `soundcheckdata-db…` with its final and automated snapshots | Every AWS user, gig, clip record, like and comment. A redeploy starts with an empty database |
| The media bucket (`soundcheckedge-mediabucket…`) | Every uploaded and transcoded clip on AWS |
| The CloudFront distribution `d2yjw795173f12.cloudfront.net` | A redeploy gets a **new** address. The installed `soundcheck-aws.apk` points at the old one and has to be rebuilt |
| App secret `soundcheck/demo/app` (scheduled for deletion, recoverable until 2025-10-26 with `aws secretsmanager restore-secret --secret-id soundcheck/demo/app --profile soundcheck`) | `make aws-secrets` recreates it from `.env` and `.secrets/aws/` |
| CDKToolkit stack, its staging bucket and ECR repo | `make aws-bootstrap` recreates it |

## What was kept
- **The code and infra**: `infra/` (CDK), `ops/aws/` (the scripts) and the Makefile targets are unchanged.
- **Local secrets**: `.env` and `.secrets/aws/` (JWT key, AWS admin password, seed password) are still on this laptop, gitignored. Back them up somewhere private if the laptop is going.
- **The real clips**: `fixtures/clips/mine/` (gitignored, local only) is what the seed uploads, so the AWS clips can be rebuilt from it.
- **The $50 budget** `soundcheck-monthly-50` is free and still alerts.

## Before you start: is the account still there?
The account is on AWS's **Free plan**: AWS closes it when the sign-up credits run out or six months after sign-up, and keeps its content for 90 days. If `aws sso login` fails because the account is closed, either upgrade it to a paid plan in the console or make a new account (set up as in `docs/SETUP.md` "AWS account"). A new account needs three checks:
1. **Region.** eu-north-1 is a choice, not a requirement. To use another region, change `REGION` in `infra/lib/config.ts` and `AWS_REGION`/`AWS_DEFAULT_REGION` in `ops/aws/lib.sh`.
2. **CloudFront prefix list.** `cloudFrontPrefixListId` in `infra/cdk.json` is per region. Find it with `aws ec2 describe-managed-prefix-lists --filters Name=prefix-list-name,Values=com.amazonaws.global.cloudfront.origin-facing --query 'PrefixLists[].PrefixListId' --output text`.
3. **PostgreSQL version.** `POSTGRES_VERSION` in `infra/lib/config.ts` must be one the region offers on db.t4g.micro (`make aws-check` lists them).

## Steps (about an hour, most of it waiting)
```bash
# 1. Sign in (once per laptop or account, first: aws configure sso --profile soundcheck); rerun when the credentials expire
aws sso login --profile soundcheck
aws configure set region eu-north-1 --profile soundcheck

# 2. Read-only checks: sign-in, bootstrap, Fargate quotas, PostgreSQL versions, prefix list, .env keys
make aws-check

# 3. CDK's own stack, once per account and region (it was deleted too)
make aws-bootstrap

# 4. Look at the plan, then deploy everything (billable from here: about $60 a month running)
make aws-deploy DRY_RUN=1
make aws-deploy            # type `demo` when asked; ends with the new https://<id>.cloudfront.net

# 5. Fill it: gigs, then personas and the real clips from fixtures/clips/mine
make aws-ingest
make aws-seed              # add SEED_ARGS="--no-real" to skip the real clips

# 6. The phone app for the new address
make apk TARGET=aws        # → app/build/soundcheck-aws.apk
adb install -r app/build/soundcheck-aws.apk
```
`.env` needs `AWS_ALARM_EMAIL` and the Grafana keys (`GRAFANA_OTLP_ENDPOINT`, `GRAFANA_OTLP_AUTHORIZATION`); `make aws-check` names any that are missing. The ingestion API keys are the local ones.

**For a demo:** `make aws-warm` an hour before keeps one transcode worker running (no 4½-minute cold start). `make aws-cool` afterwards.

## Stopping the bill again
- **For a while:** `make aws-pause` (about $22 a month), `make aws-resume` the day before it's needed.
- **For good:** `make aws-destroy`. On 2025-10-19 its first run stopped at SoundcheckCompute with `DELETE_FAILED … The specified capacity provider is in use` (ECS was still draining the services); running it again a minute later finished. Then delete the leftovers it keeps on purpose:
  ```bash
  P="--profile soundcheck --region eu-north-1"
  aws rds describe-db-snapshots $P --query 'DBSnapshots[].DBSnapshotIdentifier' --output text   # delete each: aws rds delete-db-snapshot $P --db-snapshot-identifier <id>
  aws s3 rb s3://<media bucket> --force $P                                   # if you didn't answer `delete clips`
  aws secretsmanager delete-secret --secret-id soundcheck/demo/app --recovery-window-in-days 7 $P
  aws s3 rb s3://cdk-hnb659fds-assets-<account>-eu-north-1 --force $P        # CDKToolkit's bucket, then the stack:
  aws ecr delete-repository --repository-name cdk-hnb659fds-container-assets-<account>-eu-north-1 --force $P
  aws cloudformation delete-stack --stack-name CDKToolkit $P
  ```
  Check with `aws cloudformation list-stacks $P --stack-status-filter CREATE_COMPLETE UPDATE_COMPLETE` (should be empty).
