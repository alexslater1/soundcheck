import { Stack } from 'aws-cdk-lib';
import { Annotations, Match, Template } from 'aws-cdk-lib/assertions';

import { createSoundcheckApp, STACK_NAMES } from '../lib/app';

/** Every stack synthesises offline, tagged, and passes cdk-nag. */
describe('Soundcheck CDK app', () => {
  const app = createSoundcheckApp({
    env: { account: '000000000000', region: 'eu-west-2' },
    environmentName: 'test',
  });
  // The cdk-nag checks are an aspect: synth() runs them and reports each finding as an error annotation.
  const assembly = app.synth();

  it('synthesises the six stacks in eu-west-2', () => {
    expect(assembly.stacks.map((s) => s.stackName).sort()).toEqual([...STACK_NAMES].sort());
    for (const stack of assembly.stacks) {
      expect(stack.environment.region).toBe('eu-west-2');
    }
  });

  it('tags every stack with the project and environment', () => {
    for (const stackName of STACK_NAMES) {
      const stack = app.node.findChild(stackName) as Stack;
      expect(stack.tags.tagValues()).toEqual({ Project: 'soundcheck', Environment: 'test' });
      expect(Template.fromStack(stack).toJSON()).toBeDefined();
    }
  });

  it('runs cdk-nag AwsSolutionsChecks on every synth', () => {
    // The aspect ran in the synth above; with empty stacks no stack may carry an AwsSolutions error.
    for (const stackName of STACK_NAMES) {
      const stack = app.node.findChild(stackName) as Stack;
      const violations = Annotations.fromStack(stack).findError('*', Match.stringLikeRegexp('AwsSolutions-.*'));
      expect(violations).toEqual([]);
    }
  });
});
