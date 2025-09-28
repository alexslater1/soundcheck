import {
  aws_cloudwatch as cloudwatch,
  aws_cloudwatch_actions as cwActions,
  aws_ecs as ecs,
  aws_elasticloadbalancingv2 as elbv2,
  aws_rds as rds,
  aws_sns as sns,
  aws_sns_subscriptions as subscriptions,
  aws_sqs as sqs,
  CfnOutput,
  Duration,
} from 'aws-cdk-lib';
import { Construct } from 'constructs';

import { SoundcheckStack, SoundcheckStackProps } from './base';
import { suppress } from './nag';

export interface ObservabilityStackProps extends SoundcheckStackProps {
  readonly alb: elbv2.IApplicationLoadBalancer;
  readonly apiTargetGroup: elbv2.ApplicationTargetGroup;
  readonly apiService: ecs.FargateService;
  readonly workerService: ecs.FargateService;
  readonly db: rds.DatabaseInstance;
  readonly queue: sqs.IQueue;
  readonly dlq: sqs.IQueue;
}

const MINUTE = Duration.minutes(1);
const FIVE_MINUTES = Duration.minutes(5);

/**
 * Alarms on AWS's own metrics (they work even when telemetry to Grafana Cloud doesn't), sent
 * to one SNS topic that e-mails the alarm address, and one dashboard. Every alarm says what missing data means: most
 * metrics simply stop while there is no traffic or while the stack is paused (`make aws-pause`), which
 * must not page anyone.
 */
export class ObservabilityStack extends SoundcheckStack {
  readonly topic: sns.Topic;
  readonly alarms: cloudwatch.Alarm[] = [];

  constructor(scope: Construct, id: string, props: ObservabilityStackProps) {
    super(scope, id, props);
    const { apiTargetGroup: tg, db, queue, dlq } = props;

    this.topic = new sns.Topic(this, 'AlarmTopic', { displayName: 'Soundcheck alarms', enforceSSL: true });
    suppress(this.topic, {
      rule: 'SNS2',
      reason: 'No KMS key: alarm e-mails carry no secrets, and a key plus a CloudWatch key policy is cost for nothing.',
    });
    // Only from the command line (`aws-deploy` passes AWS_ALARM_EMAIL from .env), so it is never committed.
    if (this.config.alarmEmail !== undefined) {
      this.topic.addSubscription(new subscriptions.EmailSubscription(this.config.alarmEmail));
    }

    // --- The API, seen from the ALB ---
    const requests = tg.metrics.requestCount({ period: MINUTE, statistic: 'Sum' });
    const errors5xx = tg.metrics.httpCodeTarget(elbv2.HttpCodeTarget.TARGET_5XX_COUNT, { period: MINUTE, statistic: 'Sum' });
    const p95 = tg.metrics.targetResponseTime({ period: MINUTE, statistic: 'p95' });
    // The ALB publishes no 5xx datapoint while there are none, hence FILL; no requests gives 0, not 0/0.
    const errorRate = new cloudwatch.MathExpression({
      label: 'API 5xx rate (%)',
      expression: 'IF(requests > 0, 100 * FILL(errors, 0) / requests, 0)',
      usingMetrics: { requests, errors: errors5xx },
      period: MINUTE,
    });
    this.alarm('Api5xxRate', 'More than 2 % of API responses were 5xx for 5 minutes.', {
      metric: errorRate,
      threshold: 2,
      comparisonOperator: cloudwatch.ComparisonOperator.GREATER_THAN_THRESHOLD,
      evaluationPeriods: 5,
      // No requests at all is not an error.
      treatMissingData: cloudwatch.TreatMissingData.NOT_BREACHING,
    });
    this.alarm('ApiLatencyP95', "The API's p95 response time was over 1 s for 10 minutes.", {
      metric: p95,
      threshold: 1,
      comparisonOperator: cloudwatch.ComparisonOperator.GREATER_THAN_THRESHOLD,
      evaluationPeriods: 10,
      // No requests, no response times.
      treatMissingData: cloudwatch.TreatMissingData.NOT_BREACHING,
    });

    // An API task that keeps failing its health check, without Container Insights' task counts (they cost
    // about $15–20 a month of the credits). A paused stack has no targets, so it reports 0 unhealthy.
    this.alarm('ApiUnhealthyTargets', 'An API task failed its health check for 10 minutes.', {
      metric: tg.metrics.unhealthyHostCount({ period: MINUTE, statistic: 'Maximum' }),
      threshold: 0,
      comparisonOperator: cloudwatch.ComparisonOperator.GREATER_THAN_THRESHOLD,
      evaluationPeriods: 10,
      treatMissingData: cloudwatch.TreatMissingData.NOT_BREACHING,
    });
    // No healthy API task at all (or one timing out): the ALB answers 502/503/504 itself, which the
    // target 5xx rate above never sees. CloudFront sends only /api/* here, so these are API requests.
    // It needs traffic: a dead API nobody calls stays quiet, and a visit while paused does alarm.
    this.alarm('ApiUnavailable', 'The load balancer answered 5xx itself (no healthy API task) for 5 minutes.', {
      metric: props.alb.metrics.httpCodeElb(elbv2.HttpCodeElb.ELB_5XX_COUNT, { period: MINUTE, statistic: 'Sum' }),
      threshold: 0,
      comparisonOperator: cloudwatch.ComparisonOperator.GREATER_THAN_THRESHOLD,
      evaluationPeriods: 5,
      treatMissingData: cloudwatch.TreatMissingData.NOT_BREACHING,
    });

    // --- Database ---
    this.alarm('DbCpuHigh', 'Database CPU was over 80 % for 10 minutes.', {
      metric: db.metricCPUUtilization({ period: FIVE_MINUTES }),
      threshold: 80,
      comparisonOperator: cloudwatch.ComparisonOperator.GREATER_THAN_THRESHOLD,
      evaluationPeriods: 2,
      // A stopped (paused) instance publishes nothing.
      treatMissingData: cloudwatch.TreatMissingData.NOT_BREACHING,
    });
    this.alarm('DbStorageLow', 'The database has less than 2 GB of free storage.', {
      metric: db.metricFreeStorageSpace({ period: FIVE_MINUTES, statistic: 'Minimum' }),
      threshold: 2 * 1024 ** 3,
      comparisonOperator: cloudwatch.ComparisonOperator.LESS_THAN_THRESHOLD,
      evaluationPeriods: 1,
      // As above: stopped means no data, not no space.
      treatMissingData: cloudwatch.TreatMissingData.NOT_BREACHING,
    });

    // --- Transcode pipeline ---
    this.alarm('TranscodeQueueStuck', 'A transcode job has waited more than 10 minutes.', {
      metric: queue.metricApproximateAgeOfOldestMessage({ period: FIVE_MINUTES, statistic: 'Maximum' }),
      threshold: 600,
      comparisonOperator: cloudwatch.ComparisonOperator.GREATER_THAN_THRESHOLD,
      evaluationPeriods: 1,
      // SQS stops publishing for a queue idle six hours: an idle queue has nothing old in it.
      treatMissingData: cloudwatch.TreatMissingData.NOT_BREACHING,
    });
    this.alarm('TranscodeDeadLetters', 'A transcode job failed three times and is in the dead-letter queue.', {
      metric: dlq.metricApproximateNumberOfMessagesVisible({ period: FIVE_MINUTES, statistic: 'Maximum' }),
      threshold: 0,
      comparisonOperator: cloudwatch.ComparisonOperator.GREATER_THAN_THRESHOLD,
      evaluationPeriods: 1,
      // A queue holding a message is active and keeps publishing; no data means empty and idle.
      treatMissingData: cloudwatch.TreatMissingData.NOT_BREACHING,
    });

    const dashboard = this.dashboard(props, { requests, errors5xx, p95 });
    new CfnOutput(this, 'AlarmTopicArn', { value: this.topic.topicArn });
    new CfnOutput(this, 'DashboardName', { value: dashboard.dashboardName });
  }

  private alarm(id: string, description: string, props: Omit<cloudwatch.AlarmProps, 'alarmDescription'>): void {
    const alarm = new cloudwatch.Alarm(this, id, {
      alarmName: `soundcheck-${this.config.env}-${id}`,
      alarmDescription: description,
      datapointsToAlarm: props.evaluationPeriods,
      ...props,
    });
    alarm.addAlarmAction(new cwActions.SnsAction(this.topic));
    this.alarms.push(alarm);
  }

  /** One row each: the API at the ALB, ECS, the database, the queues. */
  private dashboard(
    props: ObservabilityStackProps,
    m: {
      requests: cloudwatch.IMetric;
      errors5xx: cloudwatch.IMetric;
      p95: cloudwatch.IMetric;
    },
  ): cloudwatch.Dashboard {
    const graph = (title: string, left: cloudwatch.IMetric[], right?: cloudwatch.IMetric[]) =>
      new cloudwatch.GraphWidget({ title, left, right, width: 8, height: 6 });
    const { apiService: api, workerService: worker, db, queue, dlq } = props;
    const labelled = (metric: cloudwatch.Metric, label: string) => metric.with({ label });

    const dashboard = new cloudwatch.Dashboard(this, 'Dashboard', {
      dashboardName: `soundcheck-${this.config.env}`,
      defaultInterval: Duration.hours(6),
    });
    dashboard.addWidgets(
      graph('API requests and 5xx (per minute)', [m.requests], [m.errors5xx]),
      graph('API p95 response time (s)', [m.p95]),
      graph('API targets (healthy, unhealthy)', [
        labelled(props.apiTargetGroup.metrics.healthyHostCount(), 'Healthy'),
        labelled(props.apiTargetGroup.metrics.unhealthyHostCount(), 'Unhealthy'),
      ]),
    );
    dashboard.addWidgets(
      graph('CPU (%)', [labelled(api.metricCpuUtilization(), 'API'), labelled(worker.metricCpuUtilization(), 'Workers')]),
      graph('Memory (%)', [
        labelled(api.metricMemoryUtilization(), 'API'),
        labelled(worker.metricMemoryUtilization(), 'Workers'),
      ]),
    );
    dashboard.addWidgets(
      graph('Database CPU (%) and connections', [db.metricCPUUtilization()], [db.metricDatabaseConnections()]),
      graph('Database free storage (bytes)', [db.metricFreeStorageSpace()]),
    );
    dashboard.addWidgets(
      graph(
        'Transcode queue',
        [
          labelled(queue.metricApproximateNumberOfMessagesVisible(), 'Waiting'),
          labelled(queue.metricApproximateNumberOfMessagesNotVisible(), 'In flight'),
        ],
        [labelled(dlq.metricApproximateNumberOfMessagesVisible(), 'Dead letters')],
      ),
      graph('Oldest waiting job (s)', [queue.metricApproximateAgeOfOldestMessage()]),
    );
    return dashboard;
  }
}
