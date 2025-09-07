import { fireEvent, render, screen, waitFor } from '@testing-library/react-native';

import { ThemeProvider } from '@/theme/ThemeProvider';

import { REPORT_REASONS, ReportSheet, THANKS } from './ReportSheet';

async function renderSheet(onSubmit: jest.Mock, onClose = jest.fn(), onReported = jest.fn()) {
  await render(
    <ThemeProvider>
      <ReportSheet
        visible
        what="comment"
        onSubmit={onSubmit}
        onClose={onClose}
        onReported={onReported}
      />
    </ThemeProvider>,
  );
  return { onClose, onReported };
}

describe('ReportSheet', () => {
  it('offers the six reasons and sends nothing until one is chosen', async () => {
    const onSubmit = jest.fn(async () => undefined);
    await renderSheet(onSubmit);

    expect(screen.getByText('REPORT COMMENT')).toBeOnTheScreen();
    for (const reason of REPORT_REASONS) {
      expect(screen.getByText(reason.label)).toBeOnTheScreen();
    }
    expect(screen.getByTestId('report-send')).toBeDisabled();
    await fireEvent.press(screen.getByTestId('report-send'));
    expect(onSubmit).not.toHaveBeenCalled();
  });

  it('sends the reason with the trimmed details and thanks the reporter', async () => {
    const onSubmit = jest.fn(async () => undefined);
    const { onReported } = await renderSheet(onSubmit);

    await fireEvent.press(screen.getByText('Abuse'));
    await fireEvent.changeText(screen.getByTestId('report-details'), '  Insults the band  ');
    expect(screen.getByText('20/500')).toBeOnTheScreen();
    await fireEvent.press(screen.getByTestId('report-send'));

    await waitFor(() =>
      expect(onSubmit).toHaveBeenCalledWith({ reason: 'ABUSE', details: 'Insults the band' }),
    );
    expect(await screen.findByText(THANKS)).toBeOnTheScreen();
    expect(onReported).toHaveBeenCalledTimes(1);
  });

  it('leaves blank details out', async () => {
    const onSubmit = jest.fn(async () => undefined);
    await renderSheet(onSubmit);

    await fireEvent.press(screen.getByText('Spam'));
    await fireEvent.changeText(screen.getByTestId('report-details'), '   ');
    await fireEvent.press(screen.getByTestId('report-send'));

    await waitFor(() => expect(onSubmit).toHaveBeenCalledWith({ reason: 'SPAM' }));
  });

  it('keeps the form open with a message when the report could not be sent', async () => {
    const onSubmit = jest.fn(async () => {
      throw new Error('offline');
    });
    const { onReported } = await renderSheet(onSubmit);

    await fireEvent.press(screen.getByText('Copyright'));
    await fireEvent.press(screen.getByTestId('report-send'));

    expect(await screen.findByText('Your report could not be sent. Try again.')).toBeOnTheScreen();
    expect(screen.queryByTestId('report-done')).toBeNull();
    expect(screen.getByTestId('report-send')).not.toBeDisabled();
    expect(onReported).not.toHaveBeenCalled();
  });

  it('starts afresh after Cancel', async () => {
    const onSubmit = jest.fn(async () => undefined);
    const { onClose } = await renderSheet(onSubmit);

    await fireEvent.press(screen.getByText('Nudity'));
    await fireEvent.press(screen.getByText('Cancel'));

    expect(onClose).toHaveBeenCalledTimes(1);
    expect(screen.getByTestId('report-send')).toBeDisabled();
  });
});
