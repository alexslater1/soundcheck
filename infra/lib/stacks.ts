import { Construct } from 'constructs';

import { SoundcheckStack, SoundcheckStackProps } from './base';
import { requireImageTag } from './config';

// Placeholders, replaced one by one.

/** ECS cluster, API service behind the internal ALB, queue-scaled worker service (Spot), job task. */
export class ComputeStack extends SoundcheckStack {
  constructor(scope: Construct, id: string, props: SoundcheckStackProps) {
    super(scope, id, props);
    requireImageTag(props.config);
  }
}

/** SNS topic, CloudWatch alarms and dashboard. */
export class ObservabilityStack extends SoundcheckStack {
  constructor(scope: Construct, id: string, props: SoundcheckStackProps) {
    super(scope, id, props);
  }
}
