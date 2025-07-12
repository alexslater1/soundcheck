import { createPlayerPool, HANDOFF_WAIT_MS } from './playerPool';

type Fake = { id: number; released: boolean };

function setup() {
  let made = 0;
  const destroyed: Fake[] = [];
  const pool = createPlayerPool<Fake>({
    destroy: (player) => {
      player.released = true;
      destroyed.push(player);
    },
    schedule: (fn, ms) => {
      const timer = setTimeout(fn, ms);
      return () => clearTimeout(timer);
    },
  });
  const create = () => ({ id: ++made, released: false });
  return { pool, create, destroyed, made: () => made };
}

describe('player pool', () => {
  beforeEach(() => jest.useFakeTimers());
  afterEach(() => jest.useRealTimers());

  it('gives the post page the feed’s player, and gives it back when the post page closes', () => {
    const { pool, create, made } = setup();
    const feed = pool.newHolder();
    const post = pool.newHolder();

    const fromFeed = pool.acquire('post-1', feed, create);
    expect(pool.controls('post-1', feed)).toBe(true);

    // The post page opens on the same clip: the same player, now controlled and shown there.
    const onPost = pool.acquire('post-1', post, create);
    expect(onPost).toBe(fromFeed);
    expect(made()).toBe(1);
    expect(pool.controls('post-1', post)).toBe(true);
    expect(pool.shows('post-1', post)).toBe(true);
    expect(pool.controls('post-1', feed)).toBe(false);
    expect(pool.shows('post-1', feed)).toBe(false);

    // Back to the feed: the feed is on top again, with the player still alive.
    pool.release('post-1', post);
    expect(pool.controls('post-1', feed)).toBe(true);
    expect(pool.shows('post-1', feed)).toBe(true);
    expect(fromFeed.released).toBe(false);

    pool.release('post-1', feed);
    expect(fromFeed.released).toBe(true);
    expect(pool.size()).toBe(0);
  });

  it('a handoff stops the feed controlling the player at once but leaves its video on show', () => {
    const { pool, create } = setup();
    const feed = pool.newHolder();
    pool.acquire('post-1', feed, create);

    expect(pool.reserve('post-1')).toBe(true);

    // The feed screen is about to lose focus: it must not pause the clip on its way out.
    expect(pool.controls('post-1', feed)).toBe(false);
    expect(pool.shows('post-1', feed)).toBe(true);

    const post = pool.newHolder();
    pool.acquire('post-1', post, create);
    expect(pool.controls('post-1', post)).toBe(true);
    expect(pool.shows('post-1', feed)).toBe(false);

    // The reservation was taken over: closing the post page puts the feed back on top.
    pool.release('post-1', post);
    expect(pool.controls('post-1', feed)).toBe(true);
  });

  it('keeps a reserved player alive even if the feed lets go before the post page takes it', () => {
    const { pool, create, made } = setup();
    const feed = pool.newHolder();
    const player = pool.acquire('post-1', feed, create);
    pool.reserve('post-1');

    pool.release('post-1', feed);
    expect(player.released).toBe(false);

    const post = pool.newHolder();
    expect(pool.acquire('post-1', post, create)).toBe(player);
    expect(made()).toBe(1);
  });

  it('lets a handoff nobody takes lapse: the feed controls its player again', () => {
    const { pool, create } = setup();
    const feed = pool.newHolder();
    pool.acquire('post-1', feed, create);
    const changes = jest.fn();
    pool.subscribe(changes);

    pool.reserve('post-1');
    jest.advanceTimersByTime(HANDOFF_WAIT_MS);

    expect(pool.controls('post-1', feed)).toBe(true);
    expect(changes).toHaveBeenCalledTimes(2);
  });

  it('frees a reserved player nobody took once the reservation lapses', () => {
    const { pool, create } = setup();
    const feed = pool.newHolder();
    const player = pool.acquire('post-1', feed, create);
    pool.reserve('post-1');
    pool.release('post-1', feed);

    jest.advanceTimersByTime(HANDOFF_WAIT_MS);

    expect(player.released).toBe(true);
    expect(pool.size()).toBe(0);
  });

  it('has nothing to hand over for a clip without a player', () => {
    const { pool } = setup();
    expect(pool.reserve('post-9')).toBe(false);
  });

  it('replaces a broken player for everyone who holds it', () => {
    const { pool, create } = setup();
    const feed = pool.newHolder();
    const post = pool.newHolder();
    const first = pool.acquire('post-1', feed, create);
    pool.acquire('post-1', post, create);

    pool.renew('post-1');

    expect(first.released).toBe(true);
    expect(pool.player('post-1')).not.toBe(first);
    expect(pool.generation('post-1')).toBe(1);
    expect(pool.controls('post-1', post)).toBe(true);
  });

  it('counts a second acquire by the same holder once', () => {
    const { pool, create } = setup();
    const feed = pool.newHolder();
    const player = pool.acquire('post-1', feed, create);
    pool.acquire('post-1', feed, create);

    pool.release('post-1', feed);

    expect(player.released).toBe(true);
  });

  it('keeps clips apart', () => {
    const { pool, create } = setup();
    const holder = pool.newHolder();
    const a = pool.acquire('a', holder, create);
    const b = pool.acquire('b', holder, create);

    expect(a).not.toBe(b);
    pool.release('a', holder);
    expect(a.released).toBe(true);
    expect(b.released).toBe(false);
  });
});
