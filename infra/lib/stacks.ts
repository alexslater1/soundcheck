import { Construct } from 'constructs';

import { SoundcheckStack, SoundcheckStackProps } from './base';

// Placeholders, replaced one by one.

/** SNS topic, CloudWatch alarms and dashboard. */
export class ObservabilityStack extends SoundcheckStack {
  constructor(scope: Construct, id: string, props: SoundcheckStackProps) {
    super(scope, id, props);
  }
}
