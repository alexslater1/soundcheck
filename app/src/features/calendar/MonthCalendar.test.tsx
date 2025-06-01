import { fireEvent, render, screen } from '@testing-library/react-native';

import { ThemeProvider } from '@/theme/ThemeProvider';

import { MonthCalendar } from './MonthCalendar';

jest.mock('@expo/vector-icons/Ionicons', () => 'Ionicons');

async function renderCalendar(props: Partial<React.ComponentProps<typeof MonthCalendar>> = {}) {
  const onSelect = jest.fn();
  const onMonthChange = jest.fn();
  await render(
    <ThemeProvider>
      <MonthCalendar
        month={{ year: 2026, month: 10 }}
        onMonthChange={onMonthChange}
        counts={{ '2026-10-02': 3, '2026-10-17': 1 }}
        selected={null}
        onSelect={onSelect}
        today="2026-10-02"
        {...props}
      />
    </ThemeProvider>,
  );
  return { onSelect, onMonthChange };
}

describe('MonthCalendar', () => {
  it('labels the days that have gigs with their count', async () => {
    await renderCalendar();
    expect(screen.getByText('October 2026')).toBeTruthy();
    expect(screen.getByLabelText('2026-10-02, 3 gigs')).toBeTruthy();
    expect(screen.getByLabelText('2026-10-17, 1 gig')).toBeTruthy();
    expect(screen.getByLabelText('2026-10-18')).toBeTruthy();
  });

  it('selects a day', async () => {
    const { onSelect } = await renderCalendar();
    await fireEvent.press(screen.getByTestId('day-2026-10-17'));
    expect(onSelect).toHaveBeenLastCalledWith('2026-10-17');
  });

  it('clears the selection when the selected day is tapped again', async () => {
    const { onSelect } = await renderCalendar({ selected: '2026-10-17' });
    expect(screen.getByTestId('day-2026-10-17')).toBeSelected();
    await fireEvent.press(screen.getByTestId('day-2026-10-17'));
    expect(onSelect).toHaveBeenLastCalledWith(null);
  });

  it('moves between months', async () => {
    const { onMonthChange } = await renderCalendar();
    await fireEvent.press(screen.getByLabelText('Next month'));
    expect(onMonthChange).toHaveBeenLastCalledWith({ year: 2026, month: 11 });
    await fireEvent.press(screen.getByLabelText('Previous month'));
    expect(onMonthChange).toHaveBeenLastCalledWith({ year: 2026, month: 9 });
  });
});
