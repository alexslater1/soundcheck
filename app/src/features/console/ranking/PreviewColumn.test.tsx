import { fireEvent, render, screen } from '@testing-library/react-native';

import { ThemeProvider } from '@/theme/ThemeProvider';

import { PreviewColumn } from './PreviewColumn';
import { item } from './rankingTestData';
import { waterfallOf } from './WaterfallDrawer';

describe('PreviewColumn', () => {
  it('shows each item with its move against the other list', async () => {
    const onSelect = jest.fn();
    await render(
      <ThemeProvider>
        <PreviewColumn
          title="Draft"
          items={[item(1, 4), item(2, 2), item(3, 1), item(4, null)]}
          showChange
          selectedPostId={null}
          onSelect={onSelect}
          testID="col"
        />
      </ThemeProvider>,
    );
    expect(screen.getByTestId('col-change-1')).toHaveTextContent('▲3');
    expect(screen.getByTestId('col-change-2')).toHaveTextContent('=');
    expect(screen.getByTestId('col-change-3')).toHaveTextContent('▼2');
    expect(screen.getByTestId('col-change-4')).toHaveTextContent('NEW');
    expect(screen.getByText('@user1')).toBeOnTheScreen();

    await fireEvent.press(screen.getByTestId('col-item-3'));
    expect(onSelect).toHaveBeenCalledWith(expect.objectContaining({ rank: 3 }));
  });

  it('says so when the user has no candidates', async () => {
    await render(
      <ThemeProvider>
        <PreviewColumn
          title="Active"
          items={[]}
          showChange={false}
          selectedPostId={null}
          onSelect={jest.fn()}
          testID="col"
        />
      </ThemeProvider>,
    );
    expect(screen.getByTestId('col-empty')).toBeOnTheScreen();
  });
});

describe('waterfallOf', () => {
  it('starts at the bias and steps through every feature that adds something', () => {
    expect(waterfallOf(item(1, 1))).toEqual({
      kind: 'waterfall',
      start: { label: 'bias', value: -2 },
      steps: [
        { label: 'recency', value: 0.75 },
        { label: 'seen', value: -0.75 },
      ],
      totalLabel: 'logit',
    });
  });
});
