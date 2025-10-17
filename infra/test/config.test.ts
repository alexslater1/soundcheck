import * as fs from 'node:fs';
import * as os from 'node:os';
import * as path from 'node:path';

import { App } from 'aws-cdk-lib';

import { ConfigError, readConfig } from '../lib/config';
import { buildApp, testContext } from './helpers';

/** Context validation: every error names the key, so a typo on the command line is obvious. */
describe('config', () => {
  const read = (overrides: Record<string, unknown>) =>
    readConfig(new App({ context: testContext(overrides) }).node);

  it('reads cdk.json defaults with typed values', () => {
    const config = read({});
    expect(config).toMatchObject({
      env: 'test',
      cloudFrontPrefixListId: 'pl-fab65393',
      apiCpu: 512,
      apiMemoryMiB: 1024,
      apiMinTasks: 1,
      apiMaxTasks: 2,
      workerCpu: 4096,
      workerMemoryMiB: 8192,
      workerMinTasks: 0,
      workerMaxTasks: 2,
      pushEnabled: false,
      otlpEnabled: false,
      ingestionMode: 'live',
      setlistBackfill: true,
    });
    expect(config.alarmEmail).toBeUndefined();
    expect(config.webDist).toBeUndefined();
  });

  it('accepts -c strings as the CLI passes them', () => {
    expect(read({ apiMaxTasks: '3', pushEnabled: 'true' })).toMatchObject({ apiMaxTasks: 3, pushEnabled: true });
  });

  it('fails without imageTag, naming the key', () => {
    expect(() => buildApp({ imageTag: undefined })).toThrow(/'imageTag' is required by SoundcheckCompute/);
  });

  it.each([
    [{ cloudFrontPrefixListId: 'sg-123' }, 'cloudFrontPrefixListId'],
    [{ apiCpu: 300 }, 'apiCpu'],
    [{ apiMemoryMiB: 512 }, 'apiMemoryMiB'],
    [{ workerCpu: 3000 }, 'workerCpu'],
    [{ workerMemoryMiB: 4096 }, 'workerMemoryMiB'],
    [{ apiMinTasks: 3, apiMaxTasks: 2 }, 'apiMaxTasks'],
    [{ workerMaxTasks: -1 }, 'workerMaxTasks'],
    [{ pushEnabled: 'yes' }, 'pushEnabled'],
    [{ otlpEnabled: true }, 'otlpEndpoint'],
    [{ ingestionMode: 'mock' }, 'ingestionMode'],
    [{ webOrigin: 'https://d1.cloudfront.net/' }, 'webOrigin'],
    [{ alarmEmail: 'nobody' }, 'alarmEmail'],
    [{ env: undefined }, 'env'],
    [{ imageTag: 'not a tag' }, 'imageTag'],
  ])('rejects %j naming %s', (overrides, key) => {
    expect(() => read(overrides)).toThrow(ConfigError);
    expect(() => read(overrides)).toThrow(`'${key}'`);
  });

  it('requires webDist to hold index.html', () => {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'soundcheck-web-'));
    try {
      expect(() => read({ webDist: dir })).toThrow(/'webDist' must be a directory holding index.html/);
      fs.writeFileSync(path.join(dir, 'index.html'), '<!doctype html>');
      expect(read({ webDist: dir }).webDist).toBe(dir);
    } finally {
      fs.rmSync(dir, { recursive: true, force: true });
    }
  });
});
