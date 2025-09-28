import {
  aws_ec2 as ec2,
  aws_ecr as ecr,
  aws_rds as rds,
  aws_sqs as sqs,
  CfnOutput,
  Duration,
  RemovalPolicy,
} from 'aws-cdk-lib';
import { Construct } from 'constructs';

import { SoundcheckStack, SoundcheckStackProps } from './base';
import { POSTGRES_VERSION } from './config';
import { DB_PORT } from './network-stack';
import { suppress } from './nag';

export interface DataStackProps extends SoundcheckStackProps {
  readonly vpc: ec2.IVpc;
  readonly dbSg: ec2.ISecurityGroup;
}

/** The ECR repositories (names the scripts rely on) and the output each one's URI goes to. */
const REPOSITORIES = [
  { id: 'ApiRepository', name: 'soundcheck-api', output: 'ApiRepositoryUri' },
  { id: 'WorkerRepository', name: 'soundcheck-worker', output: 'WorkerRepositoryUri' },
  { id: 'LabRepository', name: 'soundcheck-lab', output: 'LabRepositoryUri' },
] as const;

/**
 * State: the PostgreSQL database, the transcode queue and its dead-letter queue, and the image
 * repositories. The buckets live in the Edge stack beside CloudFront.
 */
export class DataStack extends SoundcheckStack {
  readonly db: rds.DatabaseInstance;
  readonly queue: sqs.Queue;
  readonly dlq: sqs.Queue;
  readonly apiRepository: ecr.Repository;
  readonly workerRepository: ecr.Repository;
  readonly labRepository: ecr.Repository;

  constructor(scope: Construct, id: string, props: DataStackProps) {
    super(scope, id, props);

    const engine = rds.DatabaseInstanceEngine.postgres({ version: POSTGRES_VERSION });
    // pg_stat_statements must be preloaded before V001's CREATE EXTENSION can use it (perf analysis reads it).
    // rds.force_ssl keeps RDS's default (on since PG 15); the JDBC driver negotiates TLS by itself.
    const parameterGroup = new rds.ParameterGroup(this, 'DbParameters', {
      engine,
      description: 'Soundcheck PostgreSQL 17',
      parameters: { shared_preload_libraries: 'pg_stat_statements' },
    });

    this.db = new rds.DatabaseInstance(this, 'Db', {
      engine,
      instanceType: ec2.InstanceType.of(ec2.InstanceClass.T4G, ec2.InstanceSize.MICRO),
      vpc: props.vpc,
      vpcSubnets: { subnetType: ec2.SubnetType.PRIVATE_ISOLATED },
      securityGroups: [props.dbSg],
      port: DB_PORT,
      publiclyAccessible: false,
      // The master user is RDS-generated into its own secret; Flyway runs as it (V001 creates extensions).
      credentials: rds.Credentials.fromGeneratedSecret('soundcheck'),
      databaseName: 'soundcheck',
      allocatedStorage: 20,
      storageType: rds.StorageType.GP3,
      storageEncrypted: true,
      multiAz: false,
      // 1 day: the Free plan refuses more ("exceeds the maximum available to free tier customers", first
      // deploy 2025-09-28; 1 day rather than the Paid plan). The data comes from a repeatable seed.
      backupRetention: Duration.days(1),
      parameterGroup,
      deletionProtection: false,
      removalPolicy: RemovalPolicy.SNAPSHOT,
    });
    suppress(
      this.db,
      { rule: 'RDS3', reason: 'Single-AZ on purpose: a demo database, half the cost of Multi-AZ.' },
      {
        rule: 'RDS10',
        reason: 'No deletion protection so make aws-destroy can remove it; RemovalPolicy.SNAPSHOT keeps a final snapshot.',
      },
      {
        rule: 'RDS11',
        reason: 'Default port 5432: the database is in isolated subnets and admits only the task security groups.',
      },
      // On the instance so it reaches its generated secret (Db/Secret), not the attachment `db.secret` returns.
      {
        rule: 'SMG4',
        reason: 'No rotation: running tasks read the password at start-up and would fail after a rotation (demo; revisit).',
      },
    );

    // A message that fails three receives (lease expiry or FAILED) moves here and stays 14 days to be read.
    this.dlq = new sqs.Queue(this, 'TranscodeDlq', {
      queueName: 'soundcheck-transcode-dlq',
      retentionPeriod: Duration.days(14),
      encryption: sqs.QueueEncryption.SQS_MANAGED,
      enforceSSL: true,
    });

    // Visibility 600 s matches the worker's 10-minute visibility; it extends it while a long job runs.
    this.queue = new sqs.Queue(this, 'TranscodeQueue', {
      queueName: 'soundcheck-transcode',
      visibilityTimeout: Duration.seconds(600),
      receiveMessageWaitTime: Duration.seconds(20),
      deadLetterQueue: { queue: this.dlq, maxReceiveCount: 3 },
      encryption: sqs.QueueEncryption.SQS_MANAGED,
      enforceSSL: true,
    });

    const repos = REPOSITORIES.map(({ id: repoId, name, output }) => {
      const repository = new ecr.Repository(this, repoId, {
        repositoryName: name,
        imageScanOnPush: true,
        lifecycleRules: [{ description: 'Keep the last 10 images', maxImageCount: 10 }],
        removalPolicy: RemovalPolicy.DESTROY,
        emptyOnDelete: true,
      });
      new CfnOutput(this, output, { value: repository.repositoryUri });
      return repository;
    });
    [this.apiRepository, this.workerRepository, this.labRepository] = repos as [
      ecr.Repository,
      ecr.Repository,
      ecr.Repository,
    ];

    new CfnOutput(this, 'DbInstanceId', { value: this.db.instanceIdentifier });
    new CfnOutput(this, 'DbEndpoint', { value: this.db.dbInstanceEndpointAddress });
    new CfnOutput(this, 'DbSecretArn', { value: this.db.secret!.secretArn });
    new CfnOutput(this, 'QueueUrl', { value: this.queue.queueUrl });
    new CfnOutput(this, 'DlqUrl', { value: this.dlq.queueUrl });
  }
}
