import type { SoundcheckConfig } from './config';

/**
 * The three kinds of task, and the process roles each runs (`SOUNDCHECK_ROLES`). One image (the API's)
 * runs as `api,scheduler` for the service and as `cli` for one-off jobs; the worker image runs `worker`.
 */
export const TASK_ROLES = {
  api: 'api,scheduler',
  worker: 'worker',
  job: 'cli',
} as const;
export type TaskKind = keyof typeof TASK_ROLES;

/** What the environment is built from: the config and the deployed resources' addresses (tokens). */
export interface TaskEnvInputs {
  readonly config: SoundcheckConfig;
  readonly region: string;
  readonly dbEndpoint: string;
  readonly mediaBucketName: string;
  readonly queueUrl: string;
  readonly dlqUrl: string;
  readonly publicBaseUrl: string;
}

/** Database credentials come from RDS's generated secret: environment variable → JSON key. */
export const DB_SECRET_FIELDS = { DB_USER: 'username', DB_PASSWORD: 'password' } as const;

/**
 * Plain (non-secret) environment per task. A variable a task doesn't
 * get keeps the backend's default (the API runs Flyway and republishes outstanding events; the worker and
 * jobs don't, so a worker starting beside the API never races it on migrations).
 */
export function taskEnvironment(kind: TaskKind, inputs: TaskEnvInputs): Record<string, string> {
  const { config } = inputs;
  const env: Record<string, string> = {
    SPRING_PROFILES_ACTIVE: 'aws',
    SOUNDCHECK_ROLES: TASK_ROLES[kind],
    SOUNDCHECK_ENV: config.env,
    DB_URL: `jdbc:postgresql://${inputs.dbEndpoint}:5432/soundcheck`,
    DB_POOL_SIZE: kind === 'api' ? '10' : '5',
    S3_BUCKET: inputs.mediaBucketName,
    AWS_REGION: inputs.region,
    SQS_QUEUE_URL: inputs.queueUrl,
    PUBLIC_BASE_URL: inputs.publicBaseUrl,
    // Push and telemetry are off in one-off jobs whatever the flags say.
    PUSH_ENABLED: String(kind !== 'job' && config.pushEnabled),
    OTLP_ENABLED: String(kind !== 'job' && config.otlpEnabled),
  };
  if (kind !== 'job' && config.otlpEnabled && config.otlpEndpoint !== undefined) {
    env.OTEL_EXPORTER_OTLP_ENDPOINT = config.otlpEndpoint;
  }
  if (kind === 'api') {
    // Read by the console's pipeline page; harmless before it exists.
    env.SQS_DLQ_URL = inputs.dlqUrl;
  }
  if (kind === 'api' || kind === 'job') {
    env.INGESTION_MODE = config.ingestionMode;
    env.INGESTION_SETLISTFM_BACKFILL = String(config.setlistBackfill);
    if (config.musicbrainzUserAgent !== undefined) env.MUSICBRAINZ_USER_AGENT = config.musicbrainzUserAgent;
  }
  if (kind !== 'api') {
    env.SPRING_FLYWAY_ENABLED = 'false';
    env.SPRING_MODULITH_EVENTS_REPUBLISH_OUTSTANDING_EVENTS_ON_RESTART = 'false';
  }
  return env;
}

/** Keys of the app secret `soundcheck/<env>/app` that become environment variables of the same name. */
export function appSecretKeys(kind: TaskKind, config: SoundcheckConfig): string[] {
  const ingestion = ['TICKETMASTER_API_KEY', 'LASTFM_API_KEY', 'SETLISTFM_API_KEY'];
  // Optional keys are mapped only when their flag is on: ECS fails a task whose secret key is missing.
  const optional = [
    ...(config.pushEnabled ? ['PUSH_CREDENTIALS_JSON'] : []),
    ...(config.otlpEnabled ? ['OTLP_AUTHORIZATION'] : []),
  ];
  switch (kind) {
    case 'api':
      return [
        'JWT_PRIVATE_KEY_PEM',
        ...ingestion,
        'SOUNDCHECK_ADMIN_EMAIL',
        'SOUNDCHECK_ADMIN_PASSWORD',
        ...optional,
      ];
    case 'worker':
      // The worker issues and checks no user tokens, so it needs no JWT key.
      return optional;
    case 'job':
      return ingestion;
  }
}

/** The app secret's name for an environment. Created and updated only by `make aws-secrets`. */
export function appSecretName(env: string): string {
  return `soundcheck/${env}/app`;
}
