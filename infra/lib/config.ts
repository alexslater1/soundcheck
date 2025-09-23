import * as fs from 'node:fs';
import * as path from 'node:path';

import { aws_rds as rds } from 'aws-cdk-lib';
import type { Node } from 'constructs';

/**
 * The only region Soundcheck deploys to, eu-north-1 by choice. It is fixed here rather than taken from the
 * CLI profile (`soundcheck`, an IAM Identity Center sign-in), whose region could be anything.
 */
export const REGION = 'eu-north-1';

/** The availability zones the stacks may use (the VPC takes both). Fixed so synth never looks them up. */
export const AVAILABILITY_ZONES = [`${REGION}a`, `${REGION}b`];

/**
 * The PostgreSQL engine version, on one line so it is easy to move. 17.5 is the newest minor eu-north-1
 * offers on db.t4g.micro (`describe-orderable-db-instance-options`, 2025-09-23), with PostGIS 3.5.
 */
export const POSTGRES_VERSION = rds.PostgresEngineVersion.of('17.5', '17');

/** Ingestion modes the backend understands (`IngestionProperties.mode`). Fixtures need the repo, so not on AWS. */
const INGESTION_MODES = ['live', 'fixtures'] as const;
export type IngestionMode = (typeof INGESTION_MODES)[number];

/**
 * Every CDK context key the stacks read. Defaults live in
 * `infra/cdk.json`'s `context`; `-c key=value` overrides them. Keys marked optional have no default.
 */
export interface SoundcheckConfig {
  /** Environment label: stack tags, log-group and secret names (`soundcheck/<env>/app`). */
  readonly env: string;
  /** Image tag to run (`git rev-parse --short=12 HEAD`). Optional here; Compute requires it (`requireImageTag`). */
  readonly imageTag?: string;
  /** Id of `com.amazonaws.global.cloudfront.origin-facing` in this region: the only source the ALB admits. */
  readonly cloudFrontPrefixListId: string;
  /** The web app's origin for the media bucket's CORS; unset allows `https://*.cloudfront.net`. */
  readonly webOrigin?: string;
  readonly apiCpu: number;
  readonly apiMemoryMiB: number;
  readonly apiMinTasks: number;
  readonly apiMaxTasks: number;
  readonly workerMinTasks: number;
  readonly workerMaxTasks: number;
  /** Alarm e-mail address; passed on the command line only, never committed. */
  readonly alarmEmail?: string;
  /** Directory of the exported web app (`app/dist`); when set the Web stack is added. */
  readonly webDist?: string;
  readonly pushEnabled: boolean;
  readonly otlpEnabled: boolean;
  /** Grafana Cloud's OTLP gateway; required when `otlpEnabled`. */
  readonly otlpEndpoint?: string;
  readonly ingestionMode: IngestionMode;
  /** setlist.fm past-gig backfill on AWS too. */
  readonly setlistBackfill: boolean;
  /** MusicBrainz asks for a contact in the user agent; unset leaves the backend's default. */
  readonly musicbrainzUserAgent?: string;
}

/** A context value that is missing or invalid. The message always names the key. */
export class ConfigError extends Error {
  constructor(key: string, problem: string) {
    super(`CDK context key '${key}' ${problem}`);
    this.name = 'ConfigError';
  }
}

/** Fargate's valid CPU units and, for each, the memory sizes (MiB) it accepts. */
const FARGATE_MEMORY: Record<number, (memory: number) => boolean> = {
  256: (m) => [512, 1024, 2048].includes(m),
  512: (m) => m >= 1024 && m <= 4096 && m % 1024 === 0,
  1024: (m) => m >= 2048 && m <= 8192 && m % 1024 === 0,
  2048: (m) => m >= 4096 && m <= 16384 && m % 1024 === 0,
  4096: (m) => m >= 8192 && m <= 30720 && m % 1024 === 0,
};

/** Reads and validates the context. Throws `ConfigError` naming the first bad key. */
export function readConfig(node: Node): SoundcheckConfig {
  const raw = (key: string): unknown => {
    const value: unknown = node.tryGetContext(key);
    return value === '' ? undefined : value;
  };
  const required = (key: string): unknown => {
    const value = raw(key);
    if (value === undefined || value === null) {
      throw new ConfigError(key, 'is missing: it has a default in infra/cdk.json, or pass -c ' + key + '=…');
    }
    return value;
  };
  const text = (key: string, value: unknown): string => {
    if (typeof value !== 'string') throw new ConfigError(key, `must be a string, got ${JSON.stringify(value)}`);
    return value;
  };
  const optionalText = (key: string): string | undefined => {
    const value = raw(key);
    return value === undefined || value === null ? undefined : text(key, value);
  };
  const int = (key: string, min: number): number => {
    const value = required(key);
    const n = typeof value === 'number' ? value : Number(text(key, value));
    if (!Number.isInteger(n) || n < min) {
      throw new ConfigError(key, `must be an integer ≥ ${min}, got ${JSON.stringify(value)}`);
    }
    return n;
  };
  const bool = (key: string): boolean => {
    const value = required(key);
    if (value === true || value === 'true') return true;
    if (value === false || value === 'false') return false;
    throw new ConfigError(key, `must be true or false, got ${JSON.stringify(value)}`);
  };
  const matching = (key: string, value: string, pattern: RegExp, what: string): string => {
    if (!pattern.test(value)) throw new ConfigError(key, `must be ${what}, got ${JSON.stringify(value)}`);
    return value;
  };

  const env = matching('env', text('env', required('env')), /^[a-z][a-z0-9-]{1,15}$/, 'lower-case letters, digits and dashes');

  const imageTagRaw = optionalText('imageTag');
  const imageTag =
    imageTagRaw === undefined
      ? undefined
      : matching('imageTag', imageTagRaw, /^[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}$/, 'a valid image tag');

  const cloudFrontPrefixListId = matching(
    'cloudFrontPrefixListId',
    text('cloudFrontPrefixListId', required('cloudFrontPrefixListId')),
    /^pl-[0-9a-f]{8,17}$/,
    'a managed prefix-list id (pl-…)',
  );

  const webOriginRaw = optionalText('webOrigin');
  const webOrigin =
    webOriginRaw === undefined
      ? undefined
      : matching('webOrigin', webOriginRaw, /^https:\/\/[a-z0-9.-]+$/, 'an https origin with no path or trailing slash');

  const apiCpu = int('apiCpu', 256);
  const apiMemoryMiB = int('apiMemoryMiB', 512);
  const memoryFits = FARGATE_MEMORY[apiCpu];
  if (memoryFits === undefined) {
    throw new ConfigError('apiCpu', `must be one of ${Object.keys(FARGATE_MEMORY).join(', ')}, got ${apiCpu}`);
  }
  if (!memoryFits(apiMemoryMiB)) {
    throw new ConfigError('apiMemoryMiB', `${apiMemoryMiB} is not a Fargate memory size for ${apiCpu} CPU units`);
  }

  const apiMinTasks = int('apiMinTasks', 1);
  const apiMaxTasks = int('apiMaxTasks', 1);
  if (apiMaxTasks < apiMinTasks) throw new ConfigError('apiMaxTasks', `(${apiMaxTasks}) is below apiMinTasks (${apiMinTasks})`);
  const workerMinTasks = int('workerMinTasks', 0);
  const workerMaxTasks = int('workerMaxTasks', 1);
  if (workerMaxTasks < workerMinTasks) {
    throw new ConfigError('workerMaxTasks', `(${workerMaxTasks}) is below workerMinTasks (${workerMinTasks})`);
  }

  const alarmEmailRaw = optionalText('alarmEmail');
  const alarmEmail =
    alarmEmailRaw === undefined ? undefined : matching('alarmEmail', alarmEmailRaw, /^[^@\s]+@[^@\s]+\.[^@\s]+$/, 'an e-mail address');

  const webDistRaw = optionalText('webDist');
  let webDist: string | undefined;
  if (webDistRaw !== undefined) {
    webDist = path.resolve(webDistRaw);
    if (!fs.existsSync(path.join(webDist, 'index.html'))) {
      throw new ConfigError('webDist', `must be a directory holding index.html (the web export), got ${JSON.stringify(webDistRaw)}`);
    }
  }

  const pushEnabled = bool('pushEnabled');
  const otlpEnabled = bool('otlpEnabled');
  const otlpEndpointRaw = optionalText('otlpEndpoint');
  const otlpEndpoint =
    otlpEndpointRaw === undefined
      ? undefined
      : matching('otlpEndpoint', otlpEndpointRaw, /^https:\/\/\S+[^/]$/, 'an https URL with no trailing slash');
  if (otlpEnabled && otlpEndpoint === undefined) {
    throw new ConfigError('otlpEndpoint', 'is required when otlpEnabled is true (GRAFANA_OTLP_ENDPOINT)');
  }

  const mode = text('ingestionMode', required('ingestionMode'));
  if (!(INGESTION_MODES as readonly string[]).includes(mode)) {
    throw new ConfigError('ingestionMode', `must be one of ${INGESTION_MODES.join(', ')}, got ${JSON.stringify(mode)}`);
  }

  return {
    env,
    imageTag,
    cloudFrontPrefixListId,
    webOrigin,
    apiCpu,
    apiMemoryMiB,
    apiMinTasks,
    apiMaxTasks,
    workerMinTasks,
    workerMaxTasks,
    alarmEmail,
    webDist,
    pushEnabled,
    otlpEnabled,
    otlpEndpoint,
    ingestionMode: mode as IngestionMode,
    setlistBackfill: bool('setlistBackfill'),
    musicbrainzUserAgent: optionalText('musicbrainzUserAgent'),
  };
}

/** The image tag Compute runs. There is no sensible default: deploying "latest" would hide what runs. */
export function requireImageTag(config: SoundcheckConfig): string {
  if (config.imageTag === undefined) {
    throw new ConfigError('imageTag', 'is required by SoundcheckCompute: pass -c imageTag=<git short sha> (tests and CI pass "ci")');
  }
  return config.imageTag;
}
