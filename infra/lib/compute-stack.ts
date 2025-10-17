import {
  aws_applicationautoscaling as appscaling,
  aws_cloudwatch as cloudwatch,
  aws_cloudwatch_actions as cwActions,
  aws_ec2 as ec2,
  aws_ecr as ecr,
  aws_ecs as ecs,
  aws_elasticloadbalancingv2 as elbv2,
  aws_iam as iam,
  aws_logs as logs,
  aws_rds as rds,
  aws_s3 as s3,
  aws_secretsmanager as secretsmanager,
  aws_sqs as sqs,
  CfnOutput,
  Duration,
  Fn,
  RemovalPolicy,
} from 'aws-cdk-lib';
import { Construct } from 'constructs';

import { SoundcheckStack, SoundcheckStackProps } from './base';
import { requireImageTag } from './config';
import { CONTAINER_PORT } from './network-stack';
import { suppress, suppressWildcardResources } from './nag';
import { appSecretKeys, appSecretName, DB_SECRET_FIELDS, TaskEnvInputs, taskEnvironment, TaskKind } from './task-env';

export interface ComputeStackProps extends SoundcheckStackProps {
  readonly vpc: ec2.IVpc;
  readonly apiTaskSg: ec2.ISecurityGroup;
  readonly workerTaskSg: ec2.ISecurityGroup;
  readonly jobTaskSg: ec2.ISecurityGroup;
  readonly db: rds.DatabaseInstance;
  readonly queue: sqs.IQueue;
  readonly dlq: sqs.IQueue;
  readonly apiRepository: ecr.IRepository;
  readonly workerRepository: ecr.IRepository;
  readonly mediaBucket: s3.IBucket;
  /** Edge's ALB listener; this stack adds its own rule to it (never `listener.addTargets`). */
  readonly listener: elbv2.IApplicationListener;
  readonly publicBaseUrl: string;
}

/** The container name of each task; the job's is a contract output (`JobContainerName`). */
const CONTAINER_NAMES: Record<TaskKind, string> = { api: 'api', worker: 'worker', job: 'job' };

/**
 * The running code: one ECS cluster, the API service behind the ALB, the transcode workers scaled on the
 * queue (mostly Spot, down to zero), and a task definition for one-off jobs (`cli` role) that the
 * `make aws-*` scripts run with a command override.
 */
export class ComputeStack extends SoundcheckStack {
  readonly cluster: ecs.Cluster;
  readonly apiService: ecs.FargateService;
  readonly workerService: ecs.FargateService;
  readonly apiTargetGroup: elbv2.ApplicationTargetGroup;
  readonly jobTaskDefinition: ecs.FargateTaskDefinition;

  constructor(scope: Construct, id: string, props: ComputeStackProps) {
    super(scope, id, props);
    const { config } = this;
    const imageTag = requireImageTag(config);

    this.cluster = new ecs.Cluster(this, 'Cluster', {
      vpc: props.vpc,
      // Off: about $15–20 a month of the Free plan's credits for task-level metrics. The alarms read the
      // ALB's, SQS's and RDS's own metrics, and ECS still publishes service CPU and memory for free.
      containerInsightsV2: ecs.ContainerInsights.DISABLED,
      enableFargateCapacityProviders: true,
    });
    suppress(this.cluster, {
      rule: 'ECS4',
      reason: 'Container Insights off: about $15-20 a month of the Free plan credits; alarms use ALB, SQS and RDS metrics.',
    });

    const logGroups = Object.fromEntries(
      (['api', 'worker', 'job'] as const).map((kind) => [
        kind,
        new logs.LogGroup(this, `${capitalise(kind)}Logs`, {
          logGroupName: `/soundcheck/${config.env}/${kind}`,
          retention: logs.RetentionDays.TWO_WEEKS,
          removalPolicy: RemovalPolicy.DESTROY,
        }),
      ]),
    ) as Record<TaskKind, logs.LogGroup>;

    // Created by `make aws-secrets`, never by CloudFormation, so a destroy can't lose the values and they
    // never appear in a template. ECS reads it at task start (execution role).
    const appSecret = secretsmanager.Secret.fromSecretNameV2(this, 'AppSecret', appSecretName(config.env));
    const dbSecret = props.db.secret!;

    const envInputs: TaskEnvInputs = {
      config,
      region: this.region,
      dbEndpoint: props.db.dbInstanceEndpointAddress,
      mediaBucketName: props.mediaBucket.bucketName,
      queueUrl: props.queue.queueUrl,
      dlqUrl: props.dlq.queueUrl,
      publicBaseUrl: props.publicBaseUrl,
    };

    const taskDefinition = (
      kind: TaskKind,
      size: { cpu: number; memoryLimitMiB: number },
      repository: ecr.IRepository,
      options: { port?: boolean; stopTimeout?: Duration } = {},
    ): ecs.FargateTaskDefinition => {
      const definition = new ecs.FargateTaskDefinition(this, `${capitalise(kind)}Task`, {
        family: `soundcheck-${config.env}-${kind}`,
        ...size,
        runtimePlatform: {
          cpuArchitecture: ecs.CpuArchitecture.X86_64,
          operatingSystemFamily: ecs.OperatingSystemFamily.LINUX,
        },
        volumes: [{ name: 'tmp' }],
      });
      const secrets: Record<string, ecs.Secret> = {};
      for (const [name, field] of Object.entries(DB_SECRET_FIELDS)) {
        secrets[name] = ecs.Secret.fromSecretsManager(dbSecret, field);
      }
      for (const key of appSecretKeys(kind, config)) {
        secrets[key] = ecs.Secret.fromSecretsManager(appSecret, key);
      }
      const container = definition.addContainer(CONTAINER_NAMES[kind], {
        image: ecs.ContainerImage.fromEcrRepository(repository, imageTag),
        environment: taskEnvironment(kind, envInputs),
        secrets,
        // Nothing in the image can be changed at run time; scratch files go to the /tmp volume.
        readonlyRootFilesystem: true,
        logging: ecs.LogDrivers.awsLogs({ logGroup: logGroups[kind], streamPrefix: kind }),
        portMappings: options.port ? [{ containerPort: CONTAINER_PORT, name: 'http' }] : undefined,
        stopTimeout: options.stopTimeout,
      });
      container.addMountPoints({ containerPath: '/tmp', sourceVolume: 'tmp', readOnly: false });
      suppress(definition, {
        rule: 'ECS2',
        reason: 'Plain environment holds only non-secret settings (URLs, flags); every secret comes from Secrets Manager.',
      });
      suppress(definition.obtainExecutionRole(), {
        rule: 'IAM5[Resource::*]',
        reason: 'ecr:GetAuthorizationToken has no resource-level permissions; AWS requires Resource "*" for it.',
      });
      return definition;
    };

    const apiTask = taskDefinition('api', { cpu: config.apiCpu, memoryLimitMiB: config.apiMemoryMiB }, props.apiRepository, {
      port: true,
    });
    // 120 s is Fargate's maximum: the worker waits up to 90 s for FFmpeg, then 15 s to release the job.
    const workerTask = taskDefinition('worker', { cpu: config.workerCpu, memoryLimitMiB: config.workerMemoryMiB }, props.workerRepository, {
      stopTimeout: Duration.seconds(120),
    });
    this.jobTaskDefinition = taskDefinition('job', { cpu: 1024, memoryLimitMiB: 2048 }, props.apiRepository);

    this.grantTaskPermissions(apiTask, workerTask, props);

    // --- API service: on-demand Fargate in the public subnets, behind the ALB. ---
    this.apiService = new ecs.FargateService(this, 'ApiService', {
      cluster: this.cluster,
      taskDefinition: apiTask,
      desiredCount: config.apiMinTasks,
      vpcSubnets: { subnetType: ec2.SubnetType.PUBLIC },
      assignPublicIp: true,
      securityGroups: [props.apiTaskSg],
      circuitBreaker: { rollback: true },
      minHealthyPercent: 100,
      maxHealthyPercent: 200,
      // The first boot migrates V001 onwards on half a vCPU.
      healthCheckGracePeriod: Duration.seconds(300),
    });
    this.apiTargetGroup = new elbv2.ApplicationTargetGroup(this, 'ApiTargets', {
      vpc: props.vpc,
      port: CONTAINER_PORT,
      protocol: elbv2.ApplicationProtocol.HTTP,
      targetType: elbv2.TargetType.IP,
      healthCheck: {
        path: '/actuator/health/readiness',
        interval: Duration.seconds(15),
        healthyThresholdCount: 2,
        unhealthyThresholdCount: 3,
      },
      deregistrationDelay: Duration.seconds(30),
      targets: [this.apiService.loadBalancerTarget({ containerName: CONTAINER_NAMES.api, containerPort: CONTAINER_PORT })],
    });
    // The rule lives in this stack. `listener.addTargets` would add it to Edge's template instead, and
    // Edge would then depend on Compute while Compute depends on Edge: a cycle.
    new elbv2.ApplicationListenerRule(this, 'ApiRule', {
      listener: props.listener,
      priority: 10,
      conditions: [elbv2.ListenerCondition.pathPatterns(['/api/*'])],
      targetGroups: [this.apiTargetGroup],
    });
    const apiScaling = this.apiService.autoScaleTaskCount({
      minCapacity: config.apiMinTasks,
      maxCapacity: config.apiMaxTasks,
    });
    apiScaling.scaleOnCpuUtilization('Cpu', { targetUtilizationPercent: 60 });
    apiScaling.scaleOnRequestCount('Requests', { requestsPerTarget: 200, targetGroup: this.apiTargetGroup });

    // --- Workers: mostly Spot (4 of every 5 tasks), from zero, scaled on the queue. ---
    this.workerService = new ecs.FargateService(this, 'WorkerService', {
      cluster: this.cluster,
      taskDefinition: workerTask,
      desiredCount: config.workerMinTasks,
      capacityProviderStrategies: [
        { capacityProvider: 'FARGATE_SPOT', weight: 4 },
        { capacityProvider: 'FARGATE', weight: 1, base: 0 },
      ],
      vpcSubnets: { subnetType: ec2.SubnetType.PUBLIC },
      assignPublicIp: true,
      securityGroups: [props.workerTaskSg],
      minHealthyPercent: 0,
      maxHealthyPercent: 200,
      circuitBreaker: { rollback: true },
    });
    this.scaleWorkersOnQueue(props.queue);

    new CfnOutput(this, 'ClusterName', { value: this.cluster.clusterName });
    new CfnOutput(this, 'ApiServiceName', { value: this.apiService.serviceName });
    new CfnOutput(this, 'WorkerServiceName', { value: this.workerService.serviceName });
    new CfnOutput(this, 'JobTaskDefinitionArn', { value: this.jobTaskDefinition.taskDefinitionArn });
    new CfnOutput(this, 'JobContainerName', { value: CONTAINER_NAMES.job });
    new CfnOutput(this, 'JobSecurityGroupId', { value: props.jobTaskSg.securityGroupId });
    new CfnOutput(this, 'PublicSubnetIds', { value: Fn.join(',', props.vpc.publicSubnets.map((s) => s.subnetId)) });
    new CfnOutput(this, 'ApiLogGroup', { value: logGroups.api.logGroupName });
    new CfnOutput(this, 'WorkerLogGroup', { value: logGroups.worker.logGroupName });
    new CfnOutput(this, 'JobLogGroup', { value: logGroups.job.logGroupName });
  }

  /**
   * Exactly what the code calls, on object prefixes rather
   * than the whole bucket. The job runs ingestion only and gets no S3 or SQS access.
   */
  private grantTaskPermissions(
    apiTask: ecs.TaskDefinition,
    workerTask: ecs.TaskDefinition,
    props: ComputeStackProps,
  ): void {
    const bucket = props.mediaBucket;
    const uploads = bucket.arnForObjects('uploads/*');
    const media = bucket.arnForObjects('media/*');
    // HeadBucket (the readiness check) and ListObjectsV2 (deleting a prefix) need ListBucket.
    const listBucket = new iam.PolicyStatement({ sid: 'ListMediaBucket', actions: ['s3:ListBucket'], resources: [bucket.bucketArn] });

    // API: presigned uploads (single and multipart) into uploads/, photos and deletes in both prefixes.
    apiTask.addToTaskRolePolicy(
      new iam.PolicyStatement({
        sid: 'MediaObjects',
        actions: ['s3:PutObject', 's3:GetObject', 's3:DeleteObject', 's3:AbortMultipartUpload'],
        resources: [uploads, media],
      }),
    );
    apiTask.addToTaskRolePolicy(listBucket);
    apiTask.addToTaskRolePolicy(
      new iam.PolicyStatement({
        sid: 'SendTranscodeJobs',
        actions: ['sqs:SendMessage', 'sqs:GetQueueAttributes'],
        resources: [props.queue.queueArn],
      }),
    );
    apiTask.addToTaskRolePolicy(
      new iam.PolicyStatement({
        sid: 'ReadDeadLetterDepth',
        actions: ['sqs:GetQueueAttributes'],
        resources: [props.dlq.queueArn],
      }),
    );

    // Worker: reads an original, writes renditions. It can't delete or overwrite an upload.
    workerTask.addToTaskRolePolicy(
      new iam.PolicyStatement({ sid: 'ReadUploads', actions: ['s3:GetObject'], resources: [uploads] }),
    );
    workerTask.addToTaskRolePolicy(
      new iam.PolicyStatement({ sid: 'WriteRenditions', actions: ['s3:PutObject'], resources: [media] }),
    );
    workerTask.addToTaskRolePolicy(listBucket);
    workerTask.addToTaskRolePolicy(
      new iam.PolicyStatement({
        sid: 'ConsumeTranscodeJobs',
        actions: ['sqs:ReceiveMessage', 'sqs:DeleteMessage', 'sqs:ChangeMessageVisibility', 'sqs:GetQueueAttributes'],
        resources: [props.queue.queueArn],
      }),
    );

    const prefixes = 'Object-prefix wildcards: keys are made per upload, so the prefix (uploads/, media/) is the scope.';
    suppressWildcardResources(apiTask.taskRole, [uploads, media], prefixes);
    suppressWildcardResources(workerTask.taskRole, [uploads, media], prefixes);
  }

  /**
   * Workers follow the queue. The backlog is every message not yet finished: waiting (visible) plus
   * being transcoded (in flight, invisible to other consumers).
   * - Out: when work is waiting (visible ≥ 1 for a minute) add a task, two at ≥ 10. A busy worker's own
   *   message is in flight, not visible, so one long job doesn't keep adding workers.
   * - In: only after the whole backlog has been 0 for 15 minutes, one task at a time. Scaling in on
   *   "visible = 0" would stop a worker in the middle of a job, because its message is in flight.
   */
  private scaleWorkersOnQueue(queue: sqs.IQueue): void {
    const minute = Duration.minutes(1);
    const visible = queue.metricApproximateNumberOfMessagesVisible({ period: minute, statistic: 'Maximum' });
    const inFlight = queue.metricApproximateNumberOfMessagesNotVisible({ period: minute, statistic: 'Maximum' });
    const backlog = new cloudwatch.MathExpression({
      label: 'Transcode backlog (visible + in flight)',
      expression: 'FILL(visible, 0) + FILL(inFlight, 0)',
      usingMetrics: { visible, inFlight },
      period: minute,
    });

    const scaling = this.workerService.autoScaleTaskCount({
      minCapacity: this.config.workerMinTasks,
      maxCapacity: this.config.workerMaxTasks,
    });
    // The step actions below take the scalable target itself, which autoScaleTaskCount keeps as `Target`.
    const scalingTarget = scaling.node.findChild('Target') as appscaling.ScalableTarget;

    const scaleOut = new appscaling.StepScalingAction(this, 'WorkerScaleOut', {
      scalingTarget,
      adjustmentType: appscaling.AdjustmentType.CHANGE_IN_CAPACITY,
      metricAggregationType: appscaling.MetricAggregationType.MAXIMUM,
      // A new task takes a minute or two to start pulling; don't add another before it has.
      cooldown: Duration.seconds(180),
    });
    // Bounds are relative to the alarm's threshold (1): visible 1–9 → +1, ≥ 10 → +2.
    scaleOut.addAdjustment({ lowerBound: 0, upperBound: 9, adjustment: 1 });
    scaleOut.addAdjustment({ lowerBound: 9, adjustment: 2 });
    new cloudwatch.Alarm(this, 'WorkerWorkWaiting', {
      alarmDescription: 'Transcode jobs are waiting: add a worker (scaling, no notification).',
      metric: visible,
      threshold: 1,
      comparisonOperator: cloudwatch.ComparisonOperator.GREATER_THAN_OR_EQUAL_TO_THRESHOLD,
      evaluationPeriods: 1,
      // SQS stops publishing for a queue idle for six hours: no data means nothing is waiting.
      treatMissingData: cloudwatch.TreatMissingData.NOT_BREACHING,
    }).addAlarmAction(new cwActions.ApplicationScalingAction(scaleOut));

    const scaleIn = new appscaling.StepScalingAction(this, 'WorkerScaleIn', {
      scalingTarget,
      adjustmentType: appscaling.AdjustmentType.CHANGE_IN_CAPACITY,
      metricAggregationType: appscaling.MetricAggregationType.MAXIMUM,
      cooldown: Duration.seconds(60),
    });
    scaleIn.addAdjustment({ upperBound: 0, adjustment: -1 });
    new cloudwatch.Alarm(this, 'WorkerIdle', {
      alarmDescription: 'No transcode work, waiting or running, for 15 minutes: remove a worker.',
      metric: backlog,
      threshold: 0,
      comparisonOperator: cloudwatch.ComparisonOperator.LESS_THAN_OR_EQUAL_TO_THRESHOLD,
      evaluationPeriods: 15,
      datapointsToAlarm: 15,
      // No data means an idle queue (see above), so it counts as empty.
      treatMissingData: cloudwatch.TreatMissingData.BREACHING,
    }).addAlarmAction(new cwActions.ApplicationScalingAction(scaleIn));
  }
}

function capitalise(word: string): string {
  return word.charAt(0).toUpperCase() + word.slice(1);
}
