import * as fs from 'node:fs';
import * as os from 'node:os';
import * as path from 'node:path';

import type { App } from 'aws-cdk-lib';

import { ACKNOWLEDGED_RULES_METADATA_KEY } from '../lib/nag';
import { buildApp } from './helpers';

/**
 * Every cdk-nag finding the app accepts, by rule. A new kind of suppression must be added here on
 * purpose (and listed in the PR), so none slips in unnoticed. The reasons are in the code beside each.
 */
const ACCEPTED_RULES = [
  'CFR1', // no geo restriction
  'CFR2', // no WAF (cost)
  'CFR3', // no CloudFront access logs (cost)
  'CFR4', // default *.cloudfront.net certificate allows TLSv1
  'ECS2', // non-secret environment variables
  'ECS4', // Container Insights off (cost)
  'ELB2', // no ALB access logs (cost)
  'IAM4', // CDK's BucketDeployment Lambda uses AWSLambdaBasicExecutionRole
  'IAM5', // object-prefix wildcards; ecr:GetAuthorizationToken; CDK's BucketDeployment Lambda
  'L1', // CDK's BucketDeployment Lambda runtime
  'RDS3', // single-AZ
  'RDS10', // no deletion protection (aws-destroy), snapshot on delete
  'RDS11', // default port
  'S1', // no S3 access logs (cost)
  'SMG4', // no rotation of the RDS secret
  'SNS2', // no KMS key on the alarm topic
  'VPC7', // no flow logs (cost)
];

/** Every acknowledgement in the app as [construct path, rule id, reason]. */
function acknowledgements(app: App): [string, string, string][] {
  return app.node.findAll().flatMap((construct) =>
    construct.node.metadata
      .filter((entry) => entry.type === ACKNOWLEDGED_RULES_METADATA_KEY)
      .flatMap((entry) =>
        Object.entries(entry.data as Record<string, string>).map(
          ([id, reason]): [string, string, string] => [construct.node.path, id, reason],
        ),
      ),
  );
}

describe('cdk-nag suppressions', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'soundcheck-web-'));
  fs.writeFileSync(path.join(dir, 'index.html'), '<!doctype html>');
  // With the Web stack too, and synthesised, so the late (resolved) acknowledgements are included.
  const app = buildApp({ webDist: dir });
  app.synth();
  const acks = acknowledgements(app);
  afterAll(() => fs.rmSync(dir, { recursive: true, force: true }));

  it('accepts only the listed AwsSolutions rules', () => {
    const rules = new Set(
      acks.filter(([, id]) => id.startsWith('AwsSolutions-')).map(([, id]) => id.replace(/^AwsSolutions-([A-Z]+\d+).*$/, '$1')),
    );
    expect([...rules].sort()).toEqual([...ACCEPTED_RULES].sort());
  });

  it('gives every suppression a reason', () => {
    for (const [where, id, reason] of acks) {
      expect({ where, id, reasonLength: reason.trim().length >= 10 }).toEqual({ where, id, reasonLength: true });
    }
  });

  it('suppresses per resource, never for a whole stack', () => {
    const stackLevel = acks.filter(([where, id]) => !where.includes('/') && id.startsWith('AwsSolutions-'));
    expect(stackLevel).toEqual([]);
  });
});
