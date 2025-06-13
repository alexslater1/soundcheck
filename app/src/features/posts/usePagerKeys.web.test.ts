/**
 * @jest-environment jsdom
 */
import { renderHook } from '@testing-library/react-native';

import { usePagerKeys } from './usePagerKeys.web';

const key = (k: string, target: EventTarget = window) => {
  const event = new KeyboardEvent('keydown', { key: k, bubbles: true, cancelable: true });
  target.dispatchEvent(event);
  return event;
};

describe('usePagerKeys on the web', () => {
  it('steps on ↓ and ↑, cancels the browser scroll, and ignores typing in a text field', async () => {
    const onStep = jest.fn();
    const hook = await renderHook(() => usePagerKeys(true, onStep));

    const down = key('ArrowDown');
    expect(onStep).toHaveBeenLastCalledWith(1);
    expect(down.defaultPrevented).toBe(true);
    key('k');
    expect(onStep).toHaveBeenLastCalledWith(-1);

    const field = document.createElement('textarea');
    document.body.appendChild(field);
    const typed = key('j', field);
    expect(onStep).toHaveBeenCalledTimes(2);
    expect(typed.defaultPrevented).toBe(false);

    await hook.unmount();
    key('ArrowDown');
    expect(onStep).toHaveBeenCalledTimes(2);
  });

  it('listens only while enabled (the feed is the screen in view)', async () => {
    const onStep = jest.fn();
    await renderHook(() => usePagerKeys(false, onStep));
    expect(key('ArrowDown').defaultPrevented).toBe(false);
    expect(onStep).not.toHaveBeenCalled();
  });
});
