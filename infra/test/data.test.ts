import { Match } from 'aws-cdk-lib/assertions';

import { buildApp, resourcesOf, templateOf } from './helpers';

/** The database, the queues and the image repositories. */
describe('SoundcheckData', () => {
  const template = templateOf(buildApp(), 'SoundcheckData');

  it('runs PostgreSQL 17 privately, encrypted, single-AZ, with 7-day backups', () => {
    template.resourceCountIs('AWS::RDS::DBInstance', 1);
    template.hasResourceProperties('AWS::RDS::DBInstance', {
      Engine: 'postgres',
      EngineVersion: '17.5',
      DBInstanceClass: 'db.t4g.micro',
      AllocatedStorage: '20',
      StorageType: 'gp3',
      PubliclyAccessible: false,
      StorageEncrypted: true,
      MultiAZ: false,
      BackupRetentionPeriod: 1,
      DeletionProtection: false,
      DBName: 'soundcheck',
    });
    template.hasResource('AWS::RDS::DBInstance', { DeletionPolicy: 'Snapshot', UpdateReplacePolicy: 'Snapshot' });
  });

  it('puts the database in the isolated subnets', () => {
    const [[, group]] = resourcesOf(template, 'AWS::RDS::DBSubnetGroup') as [[string, { SubnetIds: unknown[] }]];
    expect(group.SubnetIds).toHaveLength(2);
    expect(JSON.stringify(group.SubnetIds)).toMatch(/isolated/i);
    expect(JSON.stringify(group.SubnetIds)).not.toMatch(/public/i);
  });

  it('preloads pg_stat_statements', () => {
    template.hasResourceProperties('AWS::RDS::DBParameterGroup', {
      Family: 'postgres17',
      Parameters: { shared_preload_libraries: 'pg_stat_statements' },
    });
  });

  it('keeps the master password in a generated secret', () => {
    template.hasResourceProperties('AWS::SecretsManager::Secret', {
      GenerateSecretString: Match.objectLike({ SecretStringTemplate: '{"username":"soundcheck"}' }),
    });
  });

  it('has an encrypted, TLS-only transcode queue that dead-letters after 3 receives', () => {
    template.hasResourceProperties('AWS::SQS::Queue', {
      QueueName: 'soundcheck-transcode',
      VisibilityTimeout: 600,
      ReceiveMessageWaitTimeSeconds: 20,
      SqsManagedSseEnabled: true,
      RedrivePolicy: { deadLetterTargetArn: Match.anyValue(), maxReceiveCount: 3 },
    });
    template.hasResourceProperties('AWS::SQS::Queue', {
      QueueName: 'soundcheck-transcode-dlq',
      MessageRetentionPeriod: 14 * 24 * 3600,
      SqsManagedSseEnabled: true,
    });
    // enforceSSL: each queue's policy denies requests without TLS.
    const policies = resourcesOf(template, 'AWS::SQS::QueuePolicy').map(([, p]) => JSON.stringify(p));
    expect(policies).toHaveLength(2);
    for (const policy of policies) {
      expect(policy).toContain('"aws:SecureTransport":"false"');
      expect(policy).toContain('"Effect":"Deny"');
    }
  });

  it('has the three repositories, scanned on push and keeping the last 10 images', () => {
    const repos = resourcesOf(template, 'AWS::ECR::Repository').map(([, p]) => p);
    expect(repos.map((r) => r.RepositoryName).sort()).toEqual(['soundcheck-api', 'soundcheck-lab', 'soundcheck-worker']);
    for (const repo of repos) {
      expect(repo.ImageScanningConfiguration).toEqual({ ScanOnPush: true });
      const policy = JSON.parse((repo.LifecyclePolicy as { LifecyclePolicyText: string }).LifecyclePolicyText);
      expect(policy.rules).toEqual([
        expect.objectContaining({ selection: expect.objectContaining({ countType: 'imageCountMoreThan', countNumber: 10 }) }),
      ]);
    }
  });

  it('exports the contract outputs', () => {
    const outputs = Object.keys(template.toJSON().Outputs ?? {});
    expect(outputs).toEqual(
      expect.arrayContaining([
        'DbInstanceId',
        'DbEndpoint',
        'DbSecretArn',
        'QueueUrl',
        'DlqUrl',
        'ApiRepositoryUri',
        'WorkerRepositoryUri',
        'LabRepositoryUri',
      ]),
    );
  });
});
