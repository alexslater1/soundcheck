#!/usr/bin/env node
import { createSoundcheckApp } from '../lib/app';

// Synth works offline: the account comes from the CDK CLI when deploying and is a placeholder otherwise.
// The region is fixed (eu-north-1, `lib/config.ts`). Context (cdk.json defaults, `-c` overrides) is read
// by the App itself.
createSoundcheckApp({ account: process.env.CDK_DEFAULT_ACCOUNT ?? '000000000000' });
