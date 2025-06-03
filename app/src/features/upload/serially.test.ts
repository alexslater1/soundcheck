import { serially } from './serially';

describe('serially (one compression at a time)', () => {
  it('runs calls one after another, in order, each with its own result or error', async () => {
    const log: string[] = [];
    const resolvers: (() => void)[] = [];
    const work = serially(async (name: string) => {
      log.push(`start ${name}`);
      await new Promise<void>((resolve) => resolvers.push(resolve));
      log.push(`end ${name}`);
      if (name === 'b') throw new Error('b failed');
      return name.toUpperCase();
    });

    const a = work('a');
    const b = work('b');
    const c = work('c');
    await Promise.resolve();
    await Promise.resolve();
    expect(log).toEqual(['start a']); // b and c wait

    resolvers.shift()!();
    await expect(a).resolves.toBe('A');
    await new Promise((r) => setTimeout(r, 0));
    expect(log).toEqual(['start a', 'end a', 'start b']);

    resolvers.shift()!();
    await expect(b).rejects.toThrow('b failed'); // a failure does not stop the queue
    await new Promise((r) => setTimeout(r, 0));
    resolvers.shift()!();
    await expect(c).resolves.toBe('C');
    expect(log).toEqual(['start a', 'end a', 'start b', 'end b', 'start c', 'end c']);
  });
});
