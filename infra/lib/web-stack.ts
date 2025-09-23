import { aws_cloudfront as cloudfront, aws_s3 as s3, aws_s3_deployment as s3deploy } from 'aws-cdk-lib';
import { Construct } from 'constructs';

import { SoundcheckStack, SoundcheckStackProps } from './base';
import { suppress, suppressAllWildcards } from './nag';

export interface WebStackProps extends SoundcheckStackProps {
  readonly webDist: string;
  readonly webBucket: s3.IBucket;
  readonly distribution: cloudfront.IDistribution;
}

/**
 * The exported web app (`make web`, same-origin), copied into the web bucket. A separate stack, added
 * only with `-c webDist=…`, because a BucketDeployment needs its files at synth time: the infra tests
 * and CI synthesise without building the web app. `index.html` is invalidated after each copy; the
 * hashed bundles under `_expo/` never need it.
 */
export class WebStack extends SoundcheckStack {
  constructor(scope: Construct, id: string, props: WebStackProps) {
    super(scope, id, props);

    const deployment = new s3deploy.BucketDeployment(this, 'WebDeployment', {
      sources: [s3deploy.Source.asset(props.webDist)],
      destinationBucket: props.webBucket,
      prune: true,
      distribution: props.distribution,
      distributionPaths: ['/index.html', '/'],
      memoryLimit: 512,
    });

    // The copying is done by CDK's own Lambda, whose role and runtime we don't choose.
    const reason = "CDK's own BucketDeployment Lambda (copies the web export, invalidates index.html); not our code.";
    const handler = deployment.handlerRole.node.scope!;
    suppress(handler, { rule: 'L1', reason });
    suppress(deployment.handlerRole, {
      rule: 'IAM4[Policy::arn:<AWS::Partition>:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole]',
      reason,
    });
    suppressAllWildcards(deployment.handlerRole, reason);
  }
}
