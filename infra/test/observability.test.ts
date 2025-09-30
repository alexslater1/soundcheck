import { buildApp, resourcesOf, templateOf } from './helpers';

/** The alarms, each e-mailing through one SNS topic, and the dashboard. */
describe('SoundcheckObservability', () => {
  const template = templateOf(buildApp(), 'SoundcheckObservability');
  const alarms = resourcesOf(template, 'AWS::CloudWatch::Alarm').map(([, p]) => p);
  const topicId = resourcesOf(template, 'AWS::SNS::Topic')[0]![0];
  const byName = (suffix: string) => alarms.find((a) => a.AlarmName === `soundcheck-test-${suffix}`)!;

  it('has at least 8 alarms, each notifying the SNS topic', () => {
    expect(alarms.length).toBeGreaterThanOrEqual(8);
    for (const alarm of alarms) {
      expect(alarm.AlarmActions).toEqual([{ Ref: topicId }]);
      expect(alarm.TreatMissingData).toBeDefined();
    }
  });

  it('alarms on the 5xx rate, p95 latency, unhealthy API tasks and the ALB answering for a missing API', () => {
    expect(byName('Api5xxRate')).toMatchObject({ Threshold: 2, EvaluationPeriods: 5, DatapointsToAlarm: 5 });
    expect(JSON.stringify(byName('Api5xxRate').Metrics)).toContain('HTTPCode_Target_5XX_Count');
    expect(byName('ApiLatencyP95')).toMatchObject({
      MetricName: 'TargetResponseTime',
      ExtendedStatistic: 'p95',
      Threshold: 1,
      EvaluationPeriods: 10,
    });
    expect(byName('ApiUnhealthyTargets')).toMatchObject({
      Namespace: 'AWS/ApplicationELB',
      MetricName: 'UnHealthyHostCount',
      Threshold: 0,
      EvaluationPeriods: 10,
      TreatMissingData: 'notBreaching',
    });
    expect(byName('ApiUnavailable')).toMatchObject({
      Namespace: 'AWS/ApplicationELB',
      MetricName: 'HTTPCode_ELB_5XX_Count',
      Threshold: 0,
      EvaluationPeriods: 5,
    });
    // No alarm depends on Container Insights, which is off (cost).
    expect(JSON.stringify(alarms)).not.toContain('ContainerInsights');
  });

  it('alarms on the database, the stuck queue and dead letters', () => {
    expect(byName('DbCpuHigh')).toMatchObject({ MetricName: 'CPUUtilization', Threshold: 80, Period: 300, EvaluationPeriods: 2 });
    expect(byName('DbStorageLow')).toMatchObject({
      MetricName: 'FreeStorageSpace',
      Threshold: 2 * 1024 ** 3,
      ComparisonOperator: 'LessThanThreshold',
    });
    expect(byName('TranscodeQueueStuck')).toMatchObject({ MetricName: 'ApproximateAgeOfOldestMessage', Threshold: 600 });
    expect(byName('TranscodeDeadLetters')).toMatchObject({
      MetricName: 'ApproximateNumberOfMessagesVisible',
      Threshold: 0,
      ComparisonOperator: 'GreaterThanThreshold',
    });
  });

  it('only subscribes an e-mail address when alarmEmail is given', () => {
    template.resourceCountIs('AWS::SNS::Subscription', 0);
    const withEmail = templateOf(buildApp({ alarmEmail: 'alerts@example.com' }), 'SoundcheckObservability');
    withEmail.hasResourceProperties('AWS::SNS::Subscription', { Protocol: 'email', Endpoint: 'alerts@example.com' });
  });

  it('refuses plain-HTTP publishing to the topic', () => {
    const policy = resourcesOf(template, 'AWS::SNS::TopicPolicy')[0]![1];
    expect(JSON.stringify(policy)).toContain('"aws:SecureTransport":"false"');
  });

  it("lets this account's CloudWatch alarms publish to the topic", () => {
    // The topic policy replaces SNS's default one, so without an explicit Allow every alarm action fails.
    type Statement = {
      Effect: string;
      Principal?: { Service?: string };
      Action: string;
      Condition?: Record<string, Record<string, unknown>>;
    };
    const policy = resourcesOf(template, 'AWS::SNS::TopicPolicy')[0]![1] as { PolicyDocument: { Statement: Statement[] } };
    const statements = policy.PolicyDocument.Statement;
    const allow = statements.find((s) => s.Effect === 'Allow' && s.Principal?.Service === 'cloudwatch.amazonaws.com');
    expect(allow).toBeDefined();
    expect(allow!.Action).toBe('sns:Publish');
    expect(Object.keys(allow!.Condition ?? {})).toEqual(expect.arrayContaining(['StringEquals', 'ArnLike']));
    expect(JSON.stringify(allow!.Condition!.ArnLike)).toContain(':alarm:*');
  });

  it('has a dashboard and the contract outputs', () => {
    template.resourceCountIs('AWS::CloudWatch::Dashboard', 1);
    expect(Object.keys(template.toJSON().Outputs ?? {})).toEqual(expect.arrayContaining(['AlarmTopicArn', 'DashboardName']));
  });
});
