import { Construct } from 'constructs';

import { SoundcheckStack, SoundcheckStackProps } from './base';
import { requireImageTag } from './config';

// Placeholders, replaced one by one.

/** RDS PostgreSQL, SQS transcode queue + DLQ, ECR. */
export class DataStack extends SoundcheckStack {
  constructor(scope: Construct, id: string, props: SoundcheckStackProps) {
    super(scope, id, props);
  }
}

/** ECS cluster, API service behind the internal ALB, queue-scaled worker service (Spot), job task. */
export class ComputeStack extends SoundcheckStack {
  constructor(scope: Construct, id: string, props: SoundcheckStackProps) {
    super(scope, id, props);
    requireImageTag(props.config);
  }
}

/** CloudFront, the internal ALB and both buckets. */
export class EdgeStack extends SoundcheckStack {
  constructor(scope: Construct, id: string, props: SoundcheckStackProps) {
    super(scope, id, props);
  }
}

/** SNS topic, CloudWatch alarms and dashboard. */
export class ObservabilityStack extends SoundcheckStack {
  constructor(scope: Construct, id: string, props: SoundcheckStackProps) {
    super(scope, id, props);
  }
}
