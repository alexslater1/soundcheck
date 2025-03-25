import { act, render, screen } from '@testing-library/react-native';
import { Text } from 'react-native';

import { MAX_LIVE_PLAYERS, usePlayerManager, usePlayerSlot } from './playerManager';

const ids = Array.from({ length: 10 }, (_, i) => `clip-${i}`);

/** Stands in for a feed item: shows what its slot allows. */
function Clip({ id }: { id: string }) {
  const { live, active } = usePlayerSlot(id);
  return <Text testID={id}>{active ? 'playing' : live ? 'live' : 'poster'}</Text>;
}

function states(): string[] {
  return ids.map((id) => String(screen.getByTestId(id).props.children));
}

describe('player manager', () => {
  beforeEach(() => usePlayerManager.getState().reset());

  it('mounts at most three live players and plays exactly one while scrolling through a list', async () => {
    await render(
      <>
        {ids.map((id) => (
          <Clip key={id} id={id} />
        ))}
      </>,
    );
    // Nothing is in view yet: every clip is a poster.
    expect(states().every((s) => s === 'poster')).toBe(true);

    await act(async () => usePlayerManager.getState().setOrder(ids));
    for (const current of ids) {
      await act(async () => usePlayerManager.getState().setCurrent(current));
      const now = states();
      expect(now.filter((s) => s !== 'poster').length).toBeLessThanOrEqual(MAX_LIVE_PLAYERS);
      expect(now.filter((s) => s === 'playing')).toHaveLength(1);
      expect(String(screen.getByTestId(current).props.children)).toBe('playing');
    }

    // In the middle of the list the neighbours are live and ready, the rest are posters.
    await act(async () => usePlayerManager.getState().setCurrent('clip-4'));
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

  it('stops everything when nothing is in view', async () => {
    usePlayerManager.getState().setOrder(ids);
    usePlayerManager.getState().setCurrent('clip-2');
    expect(usePlayerManager.getState().liveIds).toEqual(['clip-1', 'clip-2', 'clip-3']);

    usePlayerManager.getState().setCurrent(null);
    expect(usePlayerManager.getState().liveIds).toEqual([]);
  });

  it('forgets a current clip that is no longer in the list', () => {
    usePlayerManager.getState().setOrder(ids);
    usePlayerManager.getState().setCurrent('clip-9');

    usePlayerManager.getState().setOrder(ids.slice(0, 5));

    expect(usePlayerManager.getState().currentId).toBeNull();
    expect(usePlayerManager.getState().liveIds).toEqual([]);
  });
});
