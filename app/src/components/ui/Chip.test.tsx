import { render, screen } from '@testing-library/react-native';

import { ThemeProvider } from '@/theme/ThemeProvider';

import { Chip } from './Chip';

describe('Chip', () => {
  const show = (icon?: 'sparkles') =>
    render(
      <ThemeProvider>
        <Chip label="For You" selected={false} onPress={() => undefined} icon={icon} />
      </ThemeProvider>,
    );

  it('draws an icon before the label when given one, and none otherwise', async () => {
    await show('sparkles');
    expect(screen.queryByTestId('chip-icon', { includeHiddenElements: true })).toBeTruthy();
    expect(screen.getByRole('radio', { name: 'For You' })).toBeTruthy();
  });

  it('has no icon by default', async () => {
    await show();
    expect(screen.queryByTestId('chip-icon', { includeHiddenElements: true })).toBeNull();
  });
});
