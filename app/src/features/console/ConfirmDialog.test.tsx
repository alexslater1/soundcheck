import { fireEvent, render, screen } from '@testing-library/react-native';

import { ThemeProvider } from '@/theme/ThemeProvider';

import { ConfirmDialog } from './ConfirmDialog';

describe('ConfirmDialog', () => {
  it('lists what will change and confirms or cancels', async () => {
    const onConfirm = jest.fn();
    const onCancel = jest.fn();
    await render(
      <ThemeProvider>
        <ConfirmDialog
          visible
          title="Activate config v4?"
          changes={['v4 becomes the active config', 'v3 is archived']}
          confirmLabel="Activate"
          onConfirm={onConfirm}
          onCancel={onCancel}
        />
      </ThemeProvider>,
    );
    expect(screen.getByText('• v4 becomes the active config')).toBeOnTheScreen();
    expect(screen.getByText('• v3 is archived')).toBeOnTheScreen();
    await fireEvent.press(screen.getByTestId('confirm-ok'));
    await fireEvent.press(screen.getByTestId('confirm-cancel'));
    expect(onConfirm).toHaveBeenCalledTimes(1);
    expect(onCancel).toHaveBeenCalledTimes(1);
  });

  it('cannot be pressed twice while busy, and shows an error', async () => {
    const onConfirm = jest.fn();
    await render(
      <ThemeProvider>
        <ConfirmDialog
          visible
          busy
          error="The server refused: config changed meanwhile"
          title="Hide post?"
          changes={['The post disappears from every feed']}
          confirmLabel="Hide"
          destructive
          onConfirm={onConfirm}
          onCancel={jest.fn()}
        />
      </ThemeProvider>,
    );
    await fireEvent.press(screen.getByTestId('confirm-ok'));
    expect(onConfirm).not.toHaveBeenCalled();
    expect(screen.getByText('Working…')).toBeOnTheScreen();
    expect(screen.getByText('The server refused: config changed meanwhile')).toBeOnTheScreen();
  });
});
