import { testDraft } from './testDrafts';
import {
  canPostAgain,
  compressionNote,
  megabytes,
  postNote,
  postOnItsWay,
  stateLabel,
} from './labels';

describe('upload labels', () => {
  it('formats sizes', () => {
    expect(megabytes(60 * 1024 * 1024)).toBe('60.0 MB');
    expect(megabytes(1_500_000)).toBe('1.4 MB');
  });

  it('describes each state, with a percentage while uploading', () => {
    expect(stateLabel({ state: 'QUEUED', progress: 0 })).toBe('Waiting for its turn…');
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

  describe('the post that goes with an upload', () => {
    const draft = testDraft({ caption: 'x' });
    const base = { draft, postId: null, postError: null, attached: false };

    it('says nothing until something was written', () => {
      expect(postNote({ ...base, draft: null, state: 'UPLOADING' })).toBeNull();
      expect(postOnItsWay({ ...base, draft: null, state: 'UPLOADING' })).toBe(false);
    });

    it('is on its way until the post exists; then the row says the clip joins it', () => {
      expect(postNote({ ...base, state: 'UPLOADING' })?.text).toBe('Posting…');
      expect(postNote({ ...base, postId: 'p1', state: 'UPLOADING' })?.text).toBe(
        'In the post: it shows there once it has uploaded',
      );
      expect(postOnItsWay({ ...base, state: 'UPLOADING' })).toBe(true);
      // Once the post exists it is on the profile (its clip shows as on its way there).
      expect(postOnItsWay({ ...base, postId: 'p1', state: 'PROCESSING' })).toBe(false);
    });

    it('does not promise a post for a clip that was rejected or an upload that failed', () => {
      expect(postNote({ ...base, state: 'REJECTED' })).toEqual({
        text: 'Not posted: this clip could not be used.',
        problem: true,
      });
      expect(postNote({ ...base, postId: 'p1', state: 'REJECTED' })?.text).toBe(
        'Left out of the post: this clip could not be used.',
      );
      expect(postOnItsWay({ ...base, state: 'REJECTED' })).toBe(false);
      expect(postOnItsWay({ ...base, state: 'FAILED' })).toBe(false);
      expect(canPostAgain({ ...base, state: 'REJECTED', postError: 'x' })).toBe(false);
    });

    it('offers to post again when a request failed: the post at any time, its slot once uploaded', () => {
      const failed = { ...base, postError: 'Network request failed' };
      expect(postNote({ ...failed, state: 'READY' })).toEqual({
        text: 'Not posted: Network request failed',
        problem: true,
      });
      expect(canPostAgain({ ...failed, state: 'UPLOADING' })).toBe(true);
      expect(canPostAgain({ ...failed, postId: 'p1', state: 'READY' })).toBe(true);
      expect(canPostAgain({ ...failed, postId: 'p1', state: 'UPLOADING' })).toBe(false);
      expect(canPostAgain({ ...base, state: 'READY' })).toBe(false);
      expect(postOnItsWay({ ...failed, state: 'READY' })).toBe(false);
    });

    it('is done once the clip is in its post', () => {
      expect(postNote({ ...base, postId: 'p1', attached: true, state: 'READY' })?.text).toBe(
        'Posted',
      );
      expect(postOnItsWay({ ...base, postId: 'p1', attached: true, state: 'READY' })).toBe(false);
    });
  });
});
