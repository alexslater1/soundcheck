import type { App } from 'aws-cdk-lib';
import type { Template } from 'aws-cdk-lib/assertions';

import { buildApp, resourcesOf, templateOf } from './helpers';

type Json = Record<string, unknown>;
interface Container {
  Name: string;
  Environment?: { Name: string; Value: unknown }[];
  Secrets?: { Name: string; ValueFrom: unknown }[];
  ReadonlyRootFilesystem?: boolean;
  MountPoints?: { ContainerPath: string; SourceVolume: string; ReadOnly: boolean }[];
  PortMappings?: Json[];
  StopTimeout?: number;
  Image?: unknown;
}
interface Statement {
  Sid?: string;
  Action: string | string[];
  Resource: unknown;
}

const taskDefinitions = (template: Template) =>
  Object.fromEntries(
    resourcesOf(template, 'AWS::ECS::TaskDefinition').map(([, p]) => {
      const container = (p.ContainerDefinitions as Container[])[0]!;
      return [container.Name, { props: p, container }];
    }),
  ) as Record<'api' | 'worker' | 'job', { props: Json; container: Container }>;

const names = (entries: { Name: string }[] | undefined) => (entries ?? []).map((e) => e.Name).sort();
const actions = (s: Statement) => (Array.isArray(s.Action) ? s.Action : [s.Action]);

/** The task-role policy statements of one task, found through the task definition's TaskRoleArn. */
function taskRoleStatements(template: Template, task: { props: Json }): Statement[] {
  const roleId = (task.props.TaskRoleArn as { 'Fn::GetAtt': [string, string] })['Fn::GetAtt'][0];
  return resourcesOf(template, 'AWS::IAM::Policy')
    .filter(([, p]) => JSON.stringify(p.Roles) === JSON.stringify([{ Ref: roleId }]))
    .flatMap(([, p]) => (p.PolicyDocument as { Statement: Statement[] }).Statement);
}

const COMMON_ENV = [
  'AWS_REGION',
  'DB_POOL_SIZE',
  'DB_URL',
  'OTLP_ENABLED',
  'PUBLIC_BASE_URL',
  'PUSH_ENABLED',
  'S3_BUCKET',
  'SOUNDCHECK_ENV',
  'SOUNDCHECK_ROLES',
  'SPRING_PROFILES_ACTIVE',
  'SQS_QUEUE_URL',
];
const INGESTION_ENV = ['INGESTION_MODE', 'INGESTION_SETLISTFM_BACKFILL', 'MUSICBRAINZ_USER_AGENT'];
const NOT_LEADER_ENV = ['SPRING_FLYWAY_ENABLED', 'SPRING_MODULITH_EVENTS_REPUBLISH_OUTSTANDING_EVENTS_ON_RESTART'];
const DB_SECRETS = ['DB_PASSWORD', 'DB_USER'];
const INGESTION_SECRETS = ['LASTFM_API_KEY', 'SETLISTFM_API_KEY', 'TICKETMASTER_API_KEY'];

/** ECS: read-only containers, the contract's environment, least-privilege roles, scaling. */
describe('SoundcheckCompute', () => {
  const app: App = buildApp();
  const template = templateOf(app, 'SoundcheckCompute');
  const tasks = taskDefinitions(template);

  it('makes every root filesystem read-only, with a writable /tmp volume', () => {
    for (const { props, container } of Object.values(tasks)) {
      expect(container.ReadonlyRootFilesystem).toBe(true);
      expect(container.MountPoints).toEqual([{ ContainerPath: '/tmp', SourceVolume: 'tmp', ReadOnly: false }]);
      expect(props.Volumes).toEqual([{ Name: 'tmp' }]);
      expect(props.RuntimePlatform).toEqual({ CpuArchitecture: 'X86_64', OperatingSystemFamily: 'LINUX' });
    }
  });

  it('sizes the tasks and gives the worker 120 s to finish a job on stop', () => {
    expect([tasks.api.props.Cpu, tasks.api.props.Memory]).toEqual(['512', '1024']);
    expect([tasks.worker.props.Cpu, tasks.worker.props.Memory]).toEqual(['2048', '4096']);
    expect([tasks.job.props.Cpu, tasks.job.props.Memory]).toEqual(['1024', '2048']);
    expect(tasks.worker.container.StopTimeout).toBe(120);
    expect(tasks.api.container.PortMappings).toEqual([expect.objectContaining({ ContainerPort: 8080 })]);
    expect(tasks.job.container.PortMappings).toBeUndefined();
  });

  it('runs the image tag it is given', () => {
    for (const { container } of Object.values(tasks)) {
      expect(JSON.stringify(container.Image)).toMatch(/:ci"\]\]\}$/);
    }
  });

  // One test per task, listing every name, so a rename on either side fails loudly.
  it('gives the API exactly the contract environment and secrets', () => {
    expect(names(tasks.api.container.Environment)).toEqual([...COMMON_ENV, ...INGESTION_ENV, 'SQS_DLQ_URL'].sort());
    expect(names(tasks.api.container.Secrets)).toEqual(
      [...DB_SECRETS, ...INGESTION_SECRETS, 'JWT_PRIVATE_KEY_PEM', 'SOUNDCHECK_ADMIN_EMAIL', 'SOUNDCHECK_ADMIN_PASSWORD'].sort(),
    );
    const env = Object.fromEntries(tasks.api.container.Environment!.map((e) => [e.Name, e.Value]));
    expect(env).toMatchObject({
      SPRING_PROFILES_ACTIVE: 'aws',
      SOUNDCHECK_ROLES: 'api,scheduler',
      SOUNDCHECK_ENV: 'test',
      DB_POOL_SIZE: '10',
      AWS_REGION: 'eu-north-1',
      PUSH_ENABLED: 'false',
      OTLP_ENABLED: 'false',
      INGESTION_MODE: 'live',
      INGESTION_SETLISTFM_BACKFILL: 'true',
    });
    expect(JSON.stringify(env.DB_URL)).toMatch(/jdbc:postgresql:\/\/.*:5432\/soundcheck/);
  });

  it('gives the worker exactly the contract environment and only the database secret', () => {
    expect(names(tasks.worker.container.Environment)).toEqual([...COMMON_ENV, ...NOT_LEADER_ENV].sort());
    expect(names(tasks.worker.container.Secrets)).toEqual(DB_SECRETS);
    const env = Object.fromEntries(tasks.worker.container.Environment!.map((e) => [e.Name, e.Value]));
    expect(env).toMatchObject({
      SOUNDCHECK_ROLES: 'worker',
      DB_POOL_SIZE: '5',
      SPRING_FLYWAY_ENABLED: 'false',
      SPRING_MODULITH_EVENTS_REPUBLISH_OUTSTANDING_EVENTS_ON_RESTART: 'false',
    });
  });

  it('gives the job exactly the contract environment and the ingestion keys', () => {
    expect(names(tasks.job.container.Environment)).toEqual([...COMMON_ENV, ...INGESTION_ENV, ...NOT_LEADER_ENV].sort());
    expect(names(tasks.job.container.Secrets)).toEqual([...DB_SECRETS, ...INGESTION_SECRETS].sort());
    const env = Object.fromEntries(tasks.job.container.Environment!.map((e) => [e.Name, e.Value]));
    expect(env).toMatchObject({ SOUNDCHECK_ROLES: 'cli', PUSH_ENABLED: 'false', OTLP_ENABLED: 'false' });
  });

  it('reads secrets by JSON key from the app secret and the RDS secret', () => {
    const secrets = Object.fromEntries(tasks.api.container.Secrets!.map((s) => [s.Name, JSON.stringify(s.ValueFrom)]));
    expect(secrets.JWT_PRIVATE_KEY_PEM).toContain('secret:soundcheck/test/app:JWT_PRIVATE_KEY_PEM::');
    expect(secrets.DB_USER).toContain(':username::');
    expect(secrets.DB_PASSWORD).toContain(':password::');
  });

  it('maps the optional secrets and the OTLP endpoint only when their flags are on', () => {
    const flagged = taskDefinitions(
      templateOf(
        buildApp({ pushEnabled: true, otlpEnabled: true, otlpEndpoint: 'https://otlp.example.net/otlp' }),
        'SoundcheckCompute',
      ),
    );
    for (const kind of ['api', 'worker'] as const) {
      expect(names(flagged[kind].container.Secrets)).toEqual(
        expect.arrayContaining(['OTLP_AUTHORIZATION', 'PUSH_CREDENTIALS_JSON']),
      );
      const env = Object.fromEntries(flagged[kind].container.Environment!.map((e) => [e.Name, e.Value]));
      expect(env).toMatchObject({
        PUSH_ENABLED: 'true',
        OTLP_ENABLED: 'true',
        OTEL_EXPORTER_OTLP_ENDPOINT: 'https://otlp.example.net/otlp',
      });
    }
    expect(names(flagged.job.container.Secrets)).toEqual([...DB_SECRETS, ...INGESTION_SECRETS].sort());
    expect(names(flagged.job.container.Environment)).not.toContain('OTEL_EXPORTER_OTLP_ENDPOINT');
  });

  it("scopes the API's S3 access to uploads/* and media/*, with no s3:*", () => {
    const statements = taskRoleStatements(template, tasks.api);
    const s3 = statements.filter((s) => actions(s).some((a) => a.startsWith('s3:')));
    expect(s3.flatMap(actions)).not.toContain('s3:*');
    const objects = s3.find((s) => s.Sid === 'MediaObjects')!;
    expect(actions(objects).sort()).toEqual(['s3:AbortMultipartUpload', 's3:DeleteObject', 's3:GetObject', 's3:PutObject']);
    const resources = (objects.Resource as unknown[]).map((r) => JSON.stringify(r));
    expect(resources).toHaveLength(2);
    expect(resources[0]).toMatch(/\/uploads\/\*"\]\]\}$/);
    expect(resources[1]).toMatch(/\/media\/\*"\]\]\}$/);
    expect(actions(s3.find((s) => s.Sid === 'ListMediaBucket')!)).toEqual(['s3:ListBucket']);
    const sqs = statements.filter((s) => actions(s).some((a) => a.startsWith('sqs:')));
    expect(sqs.flatMap(actions).sort()).toEqual(['sqs:GetQueueAttributes', 'sqs:GetQueueAttributes', 'sqs:SendMessage']);
  });

  it("can't let the worker delete or overwrite an upload", () => {
    const statements = taskRoleStatements(template, tasks.worker);
    expect(statements.flatMap(actions)).not.toContain('s3:DeleteObject');
    expect(statements.flatMap(actions)).not.toContain('s3:*');
    const onUploads = statements.filter((s) => JSON.stringify(s.Resource).includes('/uploads/*'));
    expect(onUploads.flatMap(actions)).toEqual(['s3:GetObject']);
    const onMedia = statements.filter((s) => JSON.stringify(s.Resource).includes('/media/*'));
    expect(onMedia.flatMap(actions)).toEqual(['s3:PutObject']);
  });

  it('gives the job no S3 or SQS access', () => {
    const statements = taskRoleStatements(template, tasks.job);
    expect(statements.flatMap(actions).filter((a) => a.startsWith('s3:') || a.startsWith('sqs:'))).toEqual([]);
  });

  it('routes /api/* from the Edge listener to the API on 8080, checked on readiness', () => {
    template.hasResourceProperties('AWS::ElasticLoadBalancingV2::ListenerRule', {
      Priority: 10,
      Conditions: [{ Field: 'path-pattern', PathPatternConfig: { Values: ['/api/*'] } }],
    });
    template.hasResourceProperties('AWS::ElasticLoadBalancingV2::TargetGroup', {
      Port: 8080,
      Protocol: 'HTTP',
      TargetType: 'ip',
      HealthCheckPath: '/actuator/health/readiness',
      HealthCheckIntervalSeconds: 15,
      HealthyThresholdCount: 2,
      UnhealthyThresholdCount: 3,
    });
    const api = resourcesOf(template, 'AWS::ECS::Service').find(([, p]) => p.LoadBalancers !== undefined)![1];
    expect(api).toMatchObject({
      HealthCheckGracePeriodSeconds: 300,
      DesiredCount: 1,
      LaunchType: 'FARGATE',
      DeploymentConfiguration: {
        DeploymentCircuitBreaker: { Enable: true, Rollback: true },
        MinimumHealthyPercent: 100,
        MaximumPercent: 200,
      },
      NetworkConfiguration: { AwsvpcConfiguration: expect.objectContaining({ AssignPublicIp: 'ENABLED' }) },
    });
  });

  it('runs workers mostly on Spot, from zero', () => {
    const worker = resourcesOf(template, 'AWS::ECS::Service').find(([, p]) => p.CapacityProviderStrategy !== undefined)![1];
    expect(worker.CapacityProviderStrategy).toEqual([
      { CapacityProvider: 'FARGATE_SPOT', Weight: 4 },
      { CapacityProvider: 'FARGATE', Weight: 1, Base: 0 },
    ]);
    expect(worker.DesiredCount).toBe(0);
    expect(worker.LaunchType).toBeUndefined();
  });

  it('scales workers out on waiting jobs and in only after 15 minutes without any', () => {
    const targets = resourcesOf(template, 'AWS::ApplicationAutoScaling::ScalableTarget').map(([, p]) => p);
    expect(targets.map((t) => [t.MinCapacity, t.MaxCapacity]).sort()).toEqual([
      [0, 2],
      [1, 2],
    ]);
    const alarms = resourcesOf(template, 'AWS::CloudWatch::Alarm').map(([, p]) => p);
    const out = alarms.find((a) => a.MetricName === 'ApproximateNumberOfMessagesVisible')!;
    expect(out).toMatchObject({ ComparisonOperator: 'GreaterThanOrEqualToThreshold', Threshold: 1, EvaluationPeriods: 1 });
    const idle = alarms.find((a) => a.Metrics !== undefined)!;
    expect(idle).toMatchObject({
      ComparisonOperator: 'LessThanOrEqualToThreshold',
      Threshold: 0,
      EvaluationPeriods: 15,
      DatapointsToAlarm: 15,
      TreatMissingData: 'breaching',
    });
    expect(JSON.stringify(idle.Metrics)).toContain('FILL(visible, 0) + FILL(inFlight, 0)');
    expect(JSON.stringify(idle.Metrics)).toContain('ApproximateNumberOfMessagesNotVisible');
    const steps = resourcesOf(template, 'AWS::ApplicationAutoScaling::ScalingPolicy')
      .map(([, p]) => p.StepScalingPolicyConfiguration as { StepAdjustments: Json[] } | undefined)
      .filter((c) => c !== undefined)
      .map((c) => c.StepAdjustments);
    expect(steps).toEqual(
      expect.arrayContaining([
        [
          { MetricIntervalLowerBound: 0, MetricIntervalUpperBound: 9, ScalingAdjustment: 1 },
          { MetricIntervalLowerBound: 9, ScalingAdjustment: 2 },
        ],
        [{ MetricIntervalUpperBound: 0, ScalingAdjustment: -1 }],
      ]),
    );
  });

  it('scales the API on CPU and requests per target', () => {
    const tracking = resourcesOf(template, 'AWS::ApplicationAutoScaling::ScalingPolicy')
      .map(([, p]) => p.TargetTrackingScalingPolicyConfiguration as Json | undefined)
      .filter((c) => c !== undefined);
    expect(tracking).toEqual(
      expect.arrayContaining([
        expect.objectContaining({
          TargetValue: 60,
          PredefinedMetricSpecification: expect.objectContaining({ PredefinedMetricType: 'ECSServiceAverageCPUUtilization' }),
        }),
        expect.objectContaining({
          TargetValue: 200,
          PredefinedMetricSpecification: expect.objectContaining({ PredefinedMetricType: 'ALBRequestCountPerTarget' }),
        }),
      ]),
    );
  });

  it('leaves Container Insights off (cost) and keeps logs for 14 days', () => {
    template.hasResourceProperties('AWS::ECS::Cluster', {
      ClusterSettings: [{ Name: 'containerInsights', Value: 'disabled' }],
    });
    const groups = resourcesOf(template, 'AWS::Logs::LogGroup').map(([, p]) => p);
    expect(groups.map((g) => g.LogGroupName).sort()).toEqual(['/soundcheck/test/api', '/soundcheck/test/job', '/soundcheck/test/worker']);
    expect(groups.every((g) => g.RetentionInDays === 14)).toBe(true);
  });

  it('exports the contract outputs', () => {
    expect(Object.keys(template.toJSON().Outputs ?? {})).toEqual(
      expect.arrayContaining([
        'ClusterName',
        'ApiServiceName',
        'WorkerServiceName',
        'JobTaskDefinitionArn',
        'JobContainerName',
        'JobSecurityGroupId',
        'PublicSubnetIds',
        'ApiLogGroup',
        'WorkerLogGroup',
        'JobLogGroup',
      ]),
    );
  });
});
