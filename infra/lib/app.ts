import { App, AppProps, Aspects, Environment } from 'aws-cdk-lib';
import { AwsSolutionsChecks } from 'cdk-nag';

import {
  CiStack,
  ComputeStack,
  DataStack,
  EdgeStack,
  NetworkStack,
  ObservabilityStack,
} from './stacks';

export const STACK_NAMES = [
  'SoundcheckNetwork',
  'SoundcheckData',
  'SoundcheckCompute',
  'SoundcheckEdge',
  'SoundcheckObservability',
  'SoundcheckCi',
] as const;

export interface SoundcheckAppOptions extends AppProps {
  readonly env: Environment;
  readonly environmentName?: string;
}

/**
 * Builds the whole app so `bin/soundcheck.ts` and the tests construct exactly the same stacks.
 * cdk-nag's AwsSolutions checks run as an aspect: their findings are errors on the construct tree, which
 * fail the CLI's synth; suppressions must carry a reason.
 */
export function createSoundcheckApp(options: SoundcheckAppOptions): App {
  const app = new App(options);
  const props = { env: options.env, environmentName: options.environmentName ?? 'demo' };

  const network = new NetworkStack(app, STACK_NAMES[0], props);
  const data = new DataStack(app, STACK_NAMES[1], props);
  const compute = new ComputeStack(app, STACK_NAMES[2], props);
  const edge = new EdgeStack(app, STACK_NAMES[3], props);
  const observability = new ObservabilityStack(app, STACK_NAMES[4], props);
  new CiStack(app, STACK_NAMES[5], props);

  data.addDependency(network);
  compute.addDependency(data);
  edge.addDependency(compute);
  observability.addDependency(compute);

  Aspects.of(app).add(new AwsSolutionsChecks({ verbose: true }));
  return app;
}
