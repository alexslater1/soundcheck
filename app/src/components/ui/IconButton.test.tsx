import { fireEvent, render, screen } from '@testing-library/react-native';

import { ThemeProvider } from '@/theme/ThemeProvider';

import { IconButton } from './IconButton';

// Icon fonts are loaded through expo-asset, which is not available under Jest.
jest.mock('@expo/vector-icons/Ionicons', () => 'Ionicons');

describe('IconButton', () => {
  it('is a labelled button that shows only its icon', async () => {
    const onPress = jest.fn();
    await render(
      <ThemeProvider>
        <IconButton
          icon="ellipsis-horizontal"
          accessibilityLabel="More actions"
          onPress={onPress}
        />
      </ThemeProvider>,
    );

    const button = screen.getByRole('button', { name: 'More actions' });
    expect(screen.queryByText('More')).toBeNull();
    await fireEvent.press(button);
    expect(onPress).toHaveBeenCalledTimes(1);
  });
});
