import { Stack, StackProps, Tags } from 'aws-cdk-lib';
import { Construct } from 'constructs';

import type { SoundcheckConfig } from './config';

export interface SoundcheckStackProps extends StackProps {
  /** The validated CDK context (`lib/config.ts`). */
  readonly config: SoundcheckConfig;
}

/**
 * What every Soundcheck stack shares: the validated config and the `Project`/`Environment` tags, which
 * AWS Budgets and Cost Explorer can group by.
 */
export abstract class SoundcheckStack extends Stack {
  protected readonly config: SoundcheckConfig;

  protected constructor(scope: Construct, id: string, props: SoundcheckStackProps) {
    super(scope, id, props);
    this.config = props.config;
    Tags.of(this).add('Project', 'soundcheck');
    Tags.of(this).add('Environment', props.config.env);
  }
}
