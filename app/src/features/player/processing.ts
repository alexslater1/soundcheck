/**
 * What the app says while the worker prepares a clip. Measured on 2025-07-08 with the
 * worker's own FFmpeg commands on 2 vCPUs (the size of the AWS worker task): a 60 s 1080p phone clip
 * took 40 s to transcode, a 3-minute one 118 s; the other stages (download, probe, poster, upload)
 * take seconds. A 4K original takes longer.
 */
export const PROCESSING_HINT = 'Usually under a minute; a long clip can take a few minutes.';
