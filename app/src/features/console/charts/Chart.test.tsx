import { fireEvent, render, screen } from '@testing-library/react-native';

import { ThemeProvider } from '@/theme/ThemeProvider';

import { Chart } from './Chart';
import type { ChartSpec } from './chartTypes';

// The drawing is behind the platform boundary; under Jest (native) it is the empty native file anyway.
describe('Chart', () => {
  const spec: ChartSpec = {
    kind: 'line',
    x: ['1 Oct', '2 Oct'],
    series: [{ key: 'dau', label: 'DAU', values: [10, 12] }],
  };

  it('carries its summary as the accessibility label', async () => {
    await render(
      <ThemeProvider>
        <Chart title="Daily activity" spec={spec} testID="c" />
      </ThemeProvider>,
    );
    expect(
      screen.getByLabelText('Daily activity: line chart, 1 Oct to 2 Oct. DAU from 10 to 12.'),
    ).toBeOnTheScreen();
  });

  it('switches to a table of the same numbers and back', async () => {
    await render(
      <ThemeProvider>
        <Chart title="Daily activity" spec={spec} testID="c" />
      </ThemeProvider>,
    );
    await fireEvent.press(screen.getByTestId('c-toggle'));
    expect(screen.getByText('DAU')).toBeOnTheScreen();
    expect(screen.getByText('1 Oct')).toBeOnTheScreen();
    expect(screen.getByText('12')).toBeOnTheScreen();
    expect(screen.getByText('Show chart')).toBeOnTheScreen();
    await fireEvent.press(screen.getByTestId('c-toggle'));
    expect(screen.queryByText('1 Oct')).toBeNull();
  });
});
