import { fireEvent, render, screen } from '@testing-library/react-native';

import { ThemeProvider } from '@/theme/ThemeProvider';

import { PlayerFrame } from './PlayerFrame';
import { usePlayerPrefs } from './playerPrefs';

jest.mock('@expo/vector-icons/Ionicons', () => 'Ionicons');

const seek = { positionMs: 1000, durationMs: 10_000, onSeek: jest.fn() };

async function show(props: Partial<Parameters<typeof PlayerFrame>[0]> = {}) {
  await render(
    <ThemeProvider>
      <PlayerFrame
        aspectRatio={9 / 16}
        showPoster={false}
        showSound
        seek={seek}
        testID="f"
        {...props}
      />
    </ThemeProvider>,
  );
}

/** A node of the rendered tree (react-test-renderer 19.0 ships no types: RNTL 13 gives `any`). */
type TreeNode = { type: unknown; props: { testID?: string }; children: (TreeNode | string)[] };

/** The view a child draws: RNTL 13 lists the frame's children as elements (e.g. `SoundButton`). */
function hostOf(node: TreeNode): TreeNode {
  const first = node.children[0];
  return typeof node.type === 'string' || first === undefined || typeof first === 'string'
    ? node
    : hostOf(first);
}

/** The testIDs of the frame's children, in drawing order (later ones are on top). */
function layers(): string[] {
  const frame = screen.getByTestId('f');
  return frame.children.flatMap((child: TreeNode | string) =>
    typeof child === 'string' ? [] : [String(hostOf(child).props.testID ?? '')],
  );
}

describe('PlayerFrame', () => {
  it('draws the shade under the controls, so the seek bar shows on top of it', async () => {
    await show({ shade: true });
    const order = layers();
    expect(order.indexOf('f-shade')).toBeGreaterThanOrEqual(0);
    expect(order.indexOf('f-shade')).toBeLessThan(order.indexOf('f-seek'));
    expect(order.indexOf('f-shade')).toBeLessThan(order.indexOf('f-sound'));
  });

  it('has no shade unless asked for one', async () => {
    await show();
    expect(screen.queryByTestId('f-shade')).toBeNull();
  });

  it('leaves the sound button out when the screen draws its own, and keeps tap and seek', async () => {
    await show({ soundButton: false });
    expect(screen.queryByTestId('f-sound')).toBeNull();
    expect(screen.getByTestId('f-tap')).toBeOnTheScreen();
    expect(screen.getByTestId('f-seek')).toBeOnTheScreen();
  });

  it('toggles the app-wide sound setting from its button', async () => {
    usePlayerPrefs.setState({ muted: false });
    await show();
    await fireEvent.press(screen.getByTestId('f-sound'));
    expect(usePlayerPrefs.getState().muted).toBe(true);
  });
});
