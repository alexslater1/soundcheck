import { compressionNote, megabytes, stateLabel } from './labels';

describe('upload labels', () => {
  it('formats sizes', () => {
    expect(megabytes(60 * 1024 * 1024)).toBe('60.0 MB');
    expect(megabytes(1_500_000)).toBe('1.4 MB');
  });

  it('describes each state, with a percentage while uploading', () => {
    expect(stateLabel({ state: 'UPLOADING', progress: 0.416 })).toBe('Uploading 42%');
    expect(stateLabel({ state: 'PROCESSING', progress: 1 })).toBe('Processing…');
    expect(stateLabel({ state: 'READY', progress: 1 })).toBe('Ready');
    expect(stateLabel({ state: 'REJECTED', progress: 1 })).toBe('Not a usable video');
  });

  it('reports what compression saved, from the two sizes the phone recorded', () => {
    const MB = 1024 * 1024;
    expect(
      compressionNote({
        clientMeta: { platform: 'android', originalBytes: 60 * MB, compressedBytes: 18 * MB },
      }),
    ).toBe('Compressed 60.0 MB → 18.0 MB (70% smaller)');
    expect(
      compressionNote({
        clientMeta: { platform: 'android', originalBytes: 10 * MB, compressedBytes: 11 * MB },
      }),
    ).toBe('Compression did not help (10.0 MB); the original was uploaded');
    expect(compressionNote({ clientMeta: { platform: 'web' } })).toBeNull();
  });
});
