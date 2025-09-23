import { App } from 'aws-cdk-lib';

import { ComputeStack } from './compute-stack';
import { readConfig, REGION } from './config';
import { DataStack } from './data-stack';
import { EdgeStack } from './edge-stack';
import { SoundcheckNagChecks } from './nag';
import { NetworkStack } from './network-stack';
import { ObservabilityStack } from './stacks';

/** Stack ids, in deploy order (scripts address stacks by these names). */
export const STACK_NAMES = [
  'SoundcheckNetwork',
  'SoundcheckData',
  'SoundcheckEdge',
  'SoundcheckCompute',
  'SoundcheckObservability',
] as const;

export interface SoundcheckAppOptions {
  /** AWS account id; `000000000000` offline (tests, CI). */
  readonly account: string;
  /** CDK context; the CLI passes cdk.json and `-c` values itself, tests pass them here. */
  readonly context?: Record<string, unknown>;
  readonly outdir?: string;
}

/**
 * Builds the whole app so `bin/soundcheck.ts` and the tests construct exactly the same stacks.
 * cdk-nag's AwsSolutions checks run as a CDK policy-validation plugin: an unacknowledged finding fails
 * the synth, and every acknowledgement carries a reason (`lib/nag.ts`).
 */
export function createSoundcheckApp(options: SoundcheckAppOptions): App {
  const app = new App({ context: options.context, outdir: options.outdir });
  const config = readConfig(app.node);
  const props = { env: { account: options.account, region: REGION }, config };

  const network = new NetworkStack(app, 'SoundcheckNetwork', props);
  const data = new DataStack(app, 'SoundcheckData', { ...props, vpc: network.vpc, dbSg: network.dbSg });
  const edge = new EdgeStack(app, 'SoundcheckEdge', { ...props, vpc: network.vpc, albSg: network.albSg });
  const compute = new ComputeStack(app, 'SoundcheckCompute', {
    ...props,
    vpc: network.vpc,
    apiTaskSg: network.apiTaskSg,
    workerTaskSg: network.workerTaskSg,
    jobTaskSg: network.jobTaskSg,
    db: data.db,
    queue: data.queue,
    dlq: data.dlq,
    apiRepository: data.apiRepository,
    workerRepository: data.workerRepository,
    mediaBucket: edge.mediaBucket,
    listener: edge.listener,
    publicBaseUrl: edge.publicBaseUrl,
  });
  const observability = new ObservabilityStack(app, 'SoundcheckObservability', props);

  data.addDependency(network);
  edge.addDependency(data);
  compute.addDependency(edge);
  observability.addDependency(compute);

  app.policyValidationBeta1.push(new SoundcheckNagChecks(app, { verbose: true }));
  return app;
}
