import * as fs from 'node:fs';
import * as path from 'node:path';

import { App, Stack } from 'aws-cdk-lib';
import { Template } from 'aws-cdk-lib/assertions';

import { createSoundcheckApp } from '../lib/app';

/** cdk.json's context: the same defaults the CLI applies, so tests synthesise what `cdk synth` would. */
const CDK_JSON_CONTEXT = (JSON.parse(fs.readFileSync(path.join(__dirname, '..', 'cdk.json'), 'utf8')) as {
  context: Record<string, unknown>;
}).context;

export const TEST_ACCOUNT = '000000000000';

/** cdk.json's defaults plus what CI passes (`imageTag=ci`), then the test's own overrides. */
export function testContext(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    ...CDK_JSON_CONTEXT,
    env: 'test',
    imageTag: 'ci',
    musicbrainzUserAgent: 'Soundcheck/test (ops@example.com)',
    ...overrides,
  };
}

/** Builds the whole app offline with the test context. */
export function buildApp(overrides: Record<string, unknown> = {}): App {
  return createSoundcheckApp({ account: TEST_ACCOUNT, context: testContext(overrides) });
}

export function stackOf<T extends Stack = Stack>(app: App, name: string): T {
  return app.node.findChild(name) as T;
}

export function templateOf(app: App, name: string): Template {
  return Template.fromStack(stackOf(app, name));
}

/** Every resource of a type in a template, as [logicalId, properties]. */
export function resourcesOf(template: Template, type: string): [string, Record<string, unknown>][] {
  return Object.entries(template.findResources(type)).map(([id, r]) => [
    id,
    ((r as { Properties?: Record<string, unknown> }).Properties ?? {}),
  ]);
}
