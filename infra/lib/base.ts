import { Stack, StackProps, Tags } from 'aws-cdk-lib';
import { Construct } from 'constructs';

import { AVAILABILITY_ZONES, type SoundcheckConfig } from './config';

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

  /**
   * Fixed, so no construct triggers CDK's availability-zone lookup. With a concrete account that lookup
   * is an AWS call at synth time: it would use whatever credentials are around, and fail in CI, which has
   * none. `infra/test/stacks.test.ts` checks the synth needs no lookups at all.
   */
  override get availabilityZones(): string[] {
    return AVAILABILITY_ZONES;
  }
}
