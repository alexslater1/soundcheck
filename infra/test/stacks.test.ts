import { Stack } from 'aws-cdk-lib';

import { STACK_NAMES } from '../lib/app';
import { SoundcheckNagChecks } from '../lib/nag';
import { buildApp } from './helpers';

/** Every stack synthesises offline, in eu-north-1, tagged, and passes cdk-nag. */
describe('Soundcheck CDK app', () => {
  const app = buildApp();
  // The cdk-nag checks run as a policy-validation plugin during synth; the last test reads their report.
  const assembly = app.synth();

  it('synthesises the stacks in eu-north-1', () => {
    expect(assembly.stacks.map((s) => s.stackName).sort()).toEqual([...STACK_NAMES].sort());
    for (const stack of assembly.stacks) {
      expect(stack.environment.region).toBe('eu-north-1');
    }
  });

  it('tags every stack with the project and environment', () => {
    for (const stackName of STACK_NAMES) {
      const stack = app.node.findChild(stackName) as Stack;
      expect(stack.tags.tagValues()).toEqual({ Project: 'soundcheck', Environment: 'test' });
    }
  });

  it('is cdk-nag clean (AwsSolutionsChecks) apart from acknowledged findings', () => {
    const report = new SoundcheckNagChecks(app).validateScope(app);
    expect(report.violations ?? []).toEqual([]);
  });
});
