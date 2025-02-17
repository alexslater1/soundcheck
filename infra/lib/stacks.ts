import { Stack, StackProps, Tags } from 'aws-cdk-lib';
import { Construct } from 'constructs';

/**
 * The six stacks. Empty for now; resources are added later. Each stack is its
 * own class so CloudFormation stack names, dependencies and tests stay stable from the first commit.
 */
export interface SoundcheckStackProps extends StackProps {
  /** Short environment label used for tagging (e.g. `demo`). */
  readonly environmentName: string;
}

abstract class SoundcheckStack extends Stack {
  protected constructor(scope: Construct, id: string, props: SoundcheckStackProps) {
    super(scope, id, props);
    Tags.of(this).add('Project', 'soundcheck');
    Tags.of(this).add('Environment', props.environmentName);
  }
}

/** VPC (public + private-isolated subnets, no NAT), S3 gateway endpoint, security groups. */
export class NetworkStack extends SoundcheckStack {
  constructor(scope: Construct, id: string, props: SoundcheckStackProps) {
    super(scope, id, props);
  }
}

/** RDS PostgreSQL + PostGIS, S3 media and web buckets, SQS transcode queue + DLQ, ECR, secrets. */
export class DataStack extends SoundcheckStack {
  constructor(scope: Construct, id: string, props: SoundcheckStackProps) {
    super(scope, id, props);
  }
}

/** ECS cluster, API service behind the internal ALB, queue-scaled worker service (Spot), lab task. */
export class ComputeStack extends SoundcheckStack {
  constructor(scope: Construct, id: string, props: SoundcheckStackProps) {
    super(scope, id, props);
  }
}

/** CloudFront distribution: web bucket, /api/* via VPC origin, /media/* via OAC. */
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

/** GitHub OIDC provider and the deploy role restricted to main + the production environment. */
export class CiStack extends SoundcheckStack {
  constructor(scope: Construct, id: string, props: SoundcheckStackProps) {
    super(scope, id, props);
  }
}
