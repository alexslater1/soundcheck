#!/usr/bin/env node
import { createSoundcheckApp } from '../lib/app';

// Synth works without credentials: account/region come from the CDK CLI environment when deploying
// and from these placeholders otherwise (eu-west-2). Deploying needs the owner's go-ahead.
createSoundcheckApp({
  env: {
    account: process.env.CDK_DEFAULT_ACCOUNT ?? '000000000000',
    region: process.env.CDK_DEFAULT_REGION ?? 'eu-west-2',
  },
  environmentName: process.env.SOUNDCHECK_ENV ?? 'demo',
});
