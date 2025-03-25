import { newUuid } from './uuid';

const UUID_V4 = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;

describe('newUuid', () => {
  const original = globalThis.crypto;
  afterEach(() => {
    Object.defineProperty(globalThis, 'crypto', { value: original, configurable: true });
  });

  it('uses the platform generator when there is one', () => {
    Object.defineProperty(globalThis, 'crypto', {
      value: { randomUUID: () => '11111111-2222-4333-8444-555555555555' },
      configurable: true,
    });
    expect(newUuid()).toBe('11111111-2222-4333-8444-555555555555');
  });

  it('builds a valid version-4 UUID without one, different every time', () => {
    Object.defineProperty(globalThis, 'crypto', { value: undefined, configurable: true });
    const ids = Array.from({ length: 200 }, newUuid);
    for (const id of ids) {
      expect(id).toMatch(UUID_V4); // the server parses the Idempotency-Key as a UUID
    }
    expect(new Set(ids).size).toBe(200);
  });
});
