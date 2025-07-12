import { fireEvent, render, screen } from '@testing-library/react-native';

import { SeekBar } from './SeekBar';

async function renderBar(onSeek = jest.fn(), positionMs = 4_000) {
  const view = await render(
    <SeekBar positionMs={positionMs} durationMs={40_000} onSeek={onSeek} testID="seek" />,
  );
  return { view, onSeek };
}

/** The bar is 200 points wide and starts 20 points from the left of the screen. */
async function layOut() {
  await fireEvent(screen.getByTestId('seek'), 'layout', {
    nativeEvent: { layout: { x: 0, y: 0, width: 200, height: 24 } },
  });
}

describe('SeekBar', () => {
  it('shows the time under the finger while dragging and seeks once, when it lets go', async () => {
    const { onSeek } = await renderBar();
    await layOut();
    const bar = screen.getByTestId('seek');
    expect(screen.queryByTestId('seek-time')).toBeNull();

    await fireEvent(bar, 'responderGrant', { nativeEvent: { pageX: 70, locationX: 50 } });
    expect(screen.getByTestId('seek-time').props.children).toEqual(['0:10', ' / ', '0:40']);

    // The finger slides on (and off the bar's end): the time follows it, clamped to the clip.
    await fireEvent(bar, 'responderMove', { nativeEvent: { pageX: 120, locationX: 3 } });
    expect(screen.getByTestId('seek-time').props.children).toEqual(['0:20', ' / ', '0:40']);
    await fireEvent(bar, 'responderMove', { nativeEvent: { pageX: 400, locationX: 3 } });
    expect(screen.getByTestId('seek-time').props.children).toEqual(['0:40', ' / ', '0:40']);
    expect(onSeek).not.toHaveBeenCalled();

    await fireEvent(bar, 'responderRelease', { nativeEvent: { pageX: 170, locationX: 3 } });
    expect(onSeek).toHaveBeenCalledTimes(1);
    expect(onSeek).toHaveBeenCalledWith(30_000);
    expect(screen.queryByTestId('seek-time')).toBeNull();
  });

  it('does not seek when the drag is taken away', async () => {
    const { onSeek } = await renderBar();
    await layOut();
    const bar = screen.getByTestId('seek');

    await fireEvent(bar, 'responderGrant', { nativeEvent: { pageX: 70, locationX: 50 } });
    await fireEvent(bar, 'responderTerminate', { nativeEvent: {} });

    expect(onSeek).not.toHaveBeenCalled();
    expect(screen.queryByTestId('seek-time')).toBeNull();
  });

  it('keeps the drag once it has it, so the list cannot turn it into a scroll', async () => {
    await renderBar();
    const bar = screen.getByTestId('seek');
    expect(bar.props.onResponderTerminationRequest()).toBe(false);
    expect(bar.props.onStartShouldSetResponder()).toBe(true);
  });

  it('steps five seconds with the accessibility actions and reads the time out', async () => {
    const { onSeek } = await renderBar();
    const bar = screen.getByTestId('seek');
    expect(bar.props.accessibilityValue.text).toBe('0:04 of 0:40');

    await fireEvent(bar, 'accessibilityAction', { nativeEvent: { actionName: 'increment' } });
    await fireEvent(bar, 'accessibilityAction', { nativeEvent: { actionName: 'decrement' } });

    expect(onSeek).toHaveBeenNthCalledWith(1, 9_000);
    expect(onSeek).toHaveBeenNthCalledWith(2, 0);
  });
});
