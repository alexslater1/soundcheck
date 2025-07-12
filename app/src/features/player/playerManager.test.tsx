import { act, render, screen } from '@testing-library/react-native';
import { Text } from 'react-native';

import {
  createPlayerManager,
  MAX_LIVE_PLAYERS,
  PlayerManagerProvider,
  usePlayerSlot,
} from './playerManager';

const ids = Array.from({ length: 10 }, (_, i) => `clip-${i}`);

/** Stands in for a feed item: shows what its slot allows. */
function Clip({ id, prefix = '' }: { id: string; prefix?: string }) {
  const { live, active } = usePlayerSlot(id);
  return <Text testID={`${prefix}${id}`}>{active ? 'playing' : live ? 'live' : 'poster'}</Text>;
}

function states(prefix = ''): string[] {
  return ids.map((id) => String(screen.getByTestId(`${prefix}${id}`).props.children));
}

describe('player manager', () => {
  it('mounts at most three live players and plays exactly one while scrolling through a list', async () => {
    const manager = createPlayerManager();
    await render(
      <PlayerManagerProvider value={manager}>
        {ids.map((id) => (
          <Clip key={id} id={id} />
        ))}
      </PlayerManagerProvider>,
    );
    // Nothing is in view yet: every clip is a poster.
    expect(states().every((s) => s === 'poster')).toBe(true);

    await act(async () => manager.getState().setOrder(ids));
    for (const current of ids) {
      await act(async () => manager.getState().setCurrent(current));
      const now = states();
      expect(now.filter((s) => s !== 'poster').length).toBeLessThanOrEqual(MAX_LIVE_PLAYERS);
      expect(now.filter((s) => s === 'playing')).toHaveLength(1);
      expect(String(screen.getByTestId(current).props.children)).toBe('playing');
    }

    // In the middle of the list the neighbours are live and ready, the rest are posters.
    await act(async () => manager.getState().setCurrent('clip-4'));
    expect(states()).toEqual([
      'poster',
      'poster',
      'poster',
      'live',
      'playing',
      'live',
      'poster',
      'poster',
      'poster',
      'poster',
    ]);
  });

  it('stops everything when nothing is in view', () => {
    const manager = createPlayerManager();
    manager.getState().setOrder(ids);
    manager.getState().setCurrent('clip-2');
    expect(manager.getState().liveIds).toEqual(['clip-1', 'clip-2', 'clip-3']);

    manager.getState().setCurrent(null);
    expect(manager.getState().liveIds).toEqual([]);
  });

  it('forgets a current clip that is no longer in the list', () => {
    const manager = createPlayerManager();
    manager.getState().setOrder(ids);
    manager.getState().setCurrent('clip-9');

    manager.getState().setOrder(ids.slice(0, 5));

    expect(manager.getState().currentId).toBeNull();
    expect(manager.getState().liveIds).toEqual([]);
  });

  it('keeps two lists apart: the hidden one holds no players and comes back where it was', async () => {
    const forYou = createPlayerManager();
    const following = createPlayerManager();
    await render(
      <>
        <PlayerManagerProvider value={forYou}>
          {ids.map((id) => (
            <Clip key={id} id={id} />
          ))}
        </PlayerManagerProvider>
        <PlayerManagerProvider value={following}>
          {ids.map((id) => (
            <Clip key={id} id={id} prefix="f-" />
          ))}
        </PlayerManagerProvider>
      </>,
    );
    await act(async () => {
      forYou.getState().setOrder(ids);
      forYou.getState().setCurrent('clip-6');
      following.getState().setOrder(ids);
      following.getState().setCurrent('clip-1');
      following.getState().setEnabled(false);
    });

    // For You is showing: one clip plays there; Following is all posters.
    expect(states().filter((s) => s === 'playing')).toEqual(['playing']);
    expect(states('f-').every((s) => s === 'poster')).toBe(true);

    // Switch: For You goes quiet, Following plays the clip it was on.
    await act(async () => {
      forYou.getState().setEnabled(false);
      following.getState().setEnabled(true);
    });
    expect(states().every((s) => s === 'poster')).toBe(true);
    expect(states('f-')[1]).toBe('playing');
    expect(forYou.getState().currentId).toBe('clip-6');
  });
});
