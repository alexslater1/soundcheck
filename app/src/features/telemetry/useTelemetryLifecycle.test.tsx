import { render } from '@testing-library/react-native';
import { AppState, type AppStateStatus } from 'react-native';

import { telemetry } from './telemetry';
import { useTelemetryLifecycle } from './useTelemetryLifecycle';

function Probe() {
  useTelemetryLifecycle();
  return null;
}

describe('useTelemetryLifecycle', () => {
  it('flushes when the app leaves the foreground, and stops listening when unmounted', async () => {
    let listener: (state: AppStateStatus) => void = () => undefined;
    const remove = jest.fn();
    jest.spyOn(AppState, 'addEventListener').mockImplementation((_type, handler) => {
      listener = handler as (state: AppStateStatus) => void;
      return { remove } as never;
    });
    const view = await render(<Probe />);
    (telemetry.flush as jest.Mock).mockClear();

    listener('active');
    expect(telemetry.flush).not.toHaveBeenCalled();
    listener('background');
    expect(telemetry.flush).toHaveBeenCalledTimes(1);
    listener('inactive');
    expect(telemetry.flush).toHaveBeenCalledTimes(2);

    await view.unmount();
    expect(remove).toHaveBeenCalled();
  });
});
