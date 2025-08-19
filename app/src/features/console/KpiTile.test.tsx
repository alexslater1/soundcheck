import { render, screen } from '@testing-library/react-native';

import { ThemeProvider } from '@/theme/ThemeProvider';

import { deltaTone, formatDelta, KpiTile } from './KpiTile';

jest.mock('./charts/ChartCanvas', () => ({ ChartCanvas: () => null }));

describe('formatDelta and deltaTone', () => {
  it.each([
    [0.12, '▲ +12 %'],
    [-0.035, '▼ −3.5 %'],
    [0, '± 0 %'],
    [0.0004, '± 0 %'],
  ])('%p → %s', (delta, text) => {
    expect(formatDelta(delta)).toBe(text);
  });

  it('reads a rise as good or bad depending on the measure', async () => {
    expect(deltaTone(0.1, 'up')).toBe('good');
    expect(deltaTone(0.1, 'down')).toBe('bad');
    expect(deltaTone(-0.1, 'down')).toBe('good');
    expect(deltaTone(0, 'up')).toBe('flat');
  });
});

describe('KpiTile', () => {
  it('shows the value, the delta in words and a trend', async () => {
    await render(
      <ThemeProvider>
        <KpiTile label="DAU" value="1,204" delta={0.12} trend={[1, 2, 3]} />
      </ThemeProvider>,
    );
    expect(screen.getByText('DAU')).toBeOnTheScreen();
    expect(screen.getByText('1,204')).toBeOnTheScreen();
    expect(screen.getByText('▲ +12 % vs previous period')).toBeOnTheScreen();
    expect(screen.getByLabelText('DAU: trend from 1 to 3 over 3 points.')).toBeOnTheScreen();
  });

  it('shows a skeleton while loading', async () => {
    await render(
      <ThemeProvider>
        <KpiTile label="DAU" value="1,204" loading />
      </ThemeProvider>,
    );
    expect(screen.queryByText('1,204')).toBeNull();
    expect(screen.getByLabelText('Loading')).toBeOnTheScreen();
  });
});
