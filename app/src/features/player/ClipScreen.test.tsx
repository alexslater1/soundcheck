import { render, screen } from '@testing-library/react-native';

import { ThemeProvider } from '@/theme/ThemeProvider';

import { ClipScreen } from './ClipScreen';

const mockMedia = jest.fn();
jest.mock('./useMediaStatus', () => ({ useMediaStatus: (id: string) => mockMedia(id) }));
jest.mock('./VideoPlayer', () => {
  const React = jest.requireActual<typeof import('react')>('react');
  const { View } = jest.requireActual<typeof import('react-native')>('react-native');
  return { VideoPlayer: () => React.createElement(View, { testID: 'clip-player' }) };
});
jest.mock('@/components/ui/Screen', () => ({
  Screen: ({ children }: { children: import('react').ReactNode }) => children,
}));

const show = () =>
  render(
    <ThemeProvider>
      <ClipScreen id="m1" />
    </ThemeProvider>,
  );

describe('ClipScreen', () => {
  it('shows a ready photo as a photo, not a player', async () => {
    mockMedia.mockReturnValue({
      isPending: false,
      data: {
        status: 'READY',
        kind: 'IMAGE',
        posterUrl: 'http://localhost:8088/media/m1/v1/image.jpg',
        width: 1200,
        height: 1600,
      },
    });
    await show();

    expect(screen.getByTestId('clip-photo')).toBeTruthy();
    expect(screen.queryByTestId('clip-player')).toBeNull();
  });

  it('plays a ready clip', async () => {
    mockMedia.mockReturnValue({
      isPending: false,
      data: { status: 'READY', kind: 'VIDEO', hlsUrl: 'http://localhost:8088/master.m3u8' },
    });
    await show();

    expect(screen.getByTestId('clip-player')).toBeTruthy();
    expect(screen.queryByTestId('clip-photo')).toBeNull();
  });
});
