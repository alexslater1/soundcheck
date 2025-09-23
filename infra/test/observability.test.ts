import { buildApp, resourcesOf, templateOf } from './helpers';

/** The alarms, each e-mailing through one SNS topic, and the dashboard. */
describe('SoundcheckObservability', () => {
  const template = templateOf(buildApp(), 'SoundcheckObservability');
  const alarms = resourcesOf(template, 'AWS::CloudWatch::Alarm').map(([, p]) => p);
  const topicId = resourcesOf(template, 'AWS::SNS::Topic')[0]![0];
  const byName = (suffix: string) => alarms.find((a) => a.AlarmName === `soundcheck-test-${suffix}`)!;

  it('has at least 7 alarms, each notifying the SNS topic', () => {
    expect(alarms.length).toBeGreaterThanOrEqual(7);
    for (const alarm of alarms) {
      expect(alarm.AlarmActions).toEqual([{ Ref: topicId }]);
      expect(alarm.TreatMissingData).toBeDefined();
    }
  });

  it('alarms on the 5xx rate, p95 latency and missing API tasks', () => {
    expect(byName('Api5xxRate')).toMatchObject({ Threshold: 2, EvaluationPeriods: 5, DatapointsToAlarm: 5 });
    expect(JSON.stringify(byName('Api5xxRate').Metrics)).toContain('HTTPCode_Target_5XX_Count');
    expect(byName('ApiLatencyP95')).toMatchObject({
      MetricName: 'TargetResponseTime',
      ExtendedStatistic: 'p95',
      Threshold: 1,
      EvaluationPeriods: 10,
    });
    expect(byName('ApiTasksMissing')).toMatchObject({ Threshold: 0, EvaluationPeriods: 10 });
    expect(JSON.stringify(byName('ApiTasksMissing').Metrics)).toContain('RunningTaskCount');
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

  it('has a dashboard and the contract outputs', () => {
    template.resourceCountIs('AWS::CloudWatch::Dashboard', 1);
    expect(Object.keys(template.toJSON().Outputs ?? {})).toEqual(expect.arrayContaining(['AlarmTopicArn', 'DashboardName']));
  });
});
