import { fireEvent, render, screen } from '@testing-library/react-native';
import { useState } from 'react';

import { ThemeProvider } from '@/theme/ThemeProvider';

import { Button } from './Button';
import { ActionSheet, type SheetAction } from './Sheet';

jest.mock('@expo/vector-icons/Ionicons', () => 'Ionicons');

const share = jest.fn();
const remove = jest.fn();
const actions: SheetAction[] = [
  { key: 'share', label: 'Share', icon: 'share-outline', onPress: share },
  {
    key: 'delete',
    label: 'Delete post',
    icon: 'trash-outline',
    destructive: true,
    onPress: remove,
    confirm: {
      title: 'Delete this post?',
      message: 'This cannot be undone.',
      confirmLabel: 'Delete',
    },
  },
];

function Harness() {
  const [open, setOpen] = useState(false);
  return (
    <ThemeProvider>
      <Button title="More" onPress={() => setOpen(true)} />
      <ActionSheet
        visible={open}
        onClose={() => setOpen(false)}
        actions={actions}
        title="Post"
        testID="sheet"
      />
    </ThemeProvider>
  );
}

describe('ActionSheet', () => {
  beforeEach(() => jest.clearAllMocks());

  it('shows its rows only when open, and closes before it acts', async () => {
    await render(<Harness />);
    expect(screen.queryByTestId('sheet')).toBeNull();

    await fireEvent.press(screen.getByText('More'));
    expect(screen.getByTestId('sheet')).toBeOnTheScreen();
    expect(screen.getByText('POST')).toBeOnTheScreen();

    await fireEvent.press(screen.getByText('Share'));
    expect(share).toHaveBeenCalledTimes(1);
    expect(screen.queryByTestId('sheet')).toBeNull();
  });

  it('asks before a destructive action, and Cancel does nothing', async () => {
    await render(<Harness />);
    await fireEvent.press(screen.getByText('More'));

    await fireEvent.press(screen.getByText('Delete post'));
    expect(remove).not.toHaveBeenCalled();
    expect(screen.getByText('Delete this post?')).toBeOnTheScreen();
    expect(screen.getByText('This cannot be undone.')).toBeOnTheScreen();

    await fireEvent.press(screen.getByText('Cancel'));
    expect(remove).not.toHaveBeenCalled();
    expect(screen.queryByTestId('sheet')).toBeNull();

    // Opened again, it starts from its list, not from the question.
    await fireEvent.press(screen.getByText('More'));
    expect(screen.queryByTestId('sheet-confirm')).toBeNull();
    await fireEvent.press(screen.getByText('Delete post'));
    await fireEvent.press(screen.getByText('Delete'));
    expect(remove).toHaveBeenCalledTimes(1);
    expect(screen.queryByTestId('sheet')).toBeNull();
  });

  it('closes when the backdrop is tapped', async () => {
    await render(<Harness />);
    await fireEvent.press(screen.getByText('More'));

    await fireEvent.press(screen.getByTestId('sheet-backdrop', { includeHiddenElements: true }));

    expect(screen.queryByTestId('sheet')).toBeNull();
    expect(share).not.toHaveBeenCalled();
  });
});
