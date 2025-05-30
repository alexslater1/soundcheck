import { render } from '@testing-library/react-native';

import { useTabReselect } from './useTabReselect';

const listeners: (() => void)[] = [];
let focused = false;

jest.mock('expo-router', () => ({
  useNavigation: () => mockNavigation,
}));
const mockNavigation = {
  isFocused: () => focused,
  addListener: (_event: string, listener: () => void) => {
    listeners.push(listener);
    return () => listeners.splice(listeners.indexOf(listener), 1);
  },
};

function Probe({ onReselect }: { onReselect: () => void }) {
  useTabReselect(onReselect);
  return null;
}

const pressTab = () => listeners.forEach((listener) => listener());

describe('useTabReselect', () => {
  beforeEach(() => {
    listeners.length = 0;
    focused = false;
  });

  it('fires when the tab of the screen already showing is pressed again', async () => {
    const onReselect = jest.fn();
    await render(<Probe onReselect={onReselect} />);
    focused = true;
    pressTab();
    expect(onReselect).toHaveBeenCalledTimes(1);
  });

  it('does not fire when the press switches to the tab', async () => {
    const onReselect = jest.fn();
    await render(<Probe onReselect={onReselect} />);
    pressTab();
    expect(onReselect).not.toHaveBeenCalled();
  });

  it('stops listening when the screen goes away', async () => {
    const { unmount } = await render(<Probe onReselect={jest.fn()} />);
    await unmount();
    expect(listeners).toHaveLength(0);
  });
});
