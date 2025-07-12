import { createTapClassifier, DOUBLE_TAP_MS } from './tapGesture';

function setup() {
  const onSingle = jest.fn();
  const onDouble = jest.fn();
  const classifier = createTapClassifier({
    schedule: (fn, ms) => {
      const timer = setTimeout(fn, ms);
      return () => clearTimeout(timer);
    },
    onSingle,
    onDouble,
  });
  return { classifier, onSingle, onDouble };
}

describe('tap classifier', () => {
  beforeEach(() => jest.useFakeTimers());
  afterEach(() => jest.useRealTimers());

  it('acts on a single tap only after the double-tap window has passed', () => {
    const { classifier, onSingle, onDouble } = setup();

    classifier.tap({ x: 10, y: 20 });
    jest.advanceTimersByTime(DOUBLE_TAP_MS - 1);
    expect(onSingle).not.toHaveBeenCalled();

    jest.advanceTimersByTime(1);
    expect(onSingle).toHaveBeenCalledWith({ x: 10, y: 20 });
    expect(onDouble).not.toHaveBeenCalled();
  });

  it('turns two taps inside the window into one double tap and never a single one', () => {
    const { classifier, onSingle, onDouble } = setup();

    classifier.tap({ x: 1, y: 1 });
    jest.advanceTimersByTime(DOUBLE_TAP_MS - 50);
    classifier.tap({ x: 5, y: 6 });
    jest.advanceTimersByTime(DOUBLE_TAP_MS * 4);

    expect(onDouble).toHaveBeenCalledTimes(1);
    expect(onDouble).toHaveBeenCalledWith({ x: 5, y: 6 });
    expect(onSingle).not.toHaveBeenCalled();
  });

  it('counts two taps further apart than the window as two single taps', () => {
    const { classifier, onSingle, onDouble } = setup();

    classifier.tap({ x: 1, y: 1 });
    jest.advanceTimersByTime(DOUBLE_TAP_MS + 10);
    classifier.tap({ x: 2, y: 2 });
    jest.advanceTimersByTime(DOUBLE_TAP_MS);

    expect(onSingle).toHaveBeenCalledTimes(2);
    expect(onDouble).not.toHaveBeenCalled();
  });

  it('starts a new pair after a double tap', () => {
    const { classifier, onSingle, onDouble } = setup();

    classifier.tap({ x: 0, y: 0 });
    classifier.tap({ x: 0, y: 0 });
    classifier.tap({ x: 0, y: 0 });
    classifier.tap({ x: 0, y: 0 });

    expect(onDouble).toHaveBeenCalledTimes(2);
    jest.advanceTimersByTime(DOUBLE_TAP_MS);
    expect(onSingle).not.toHaveBeenCalled();
  });

  it('drops a waiting tap when cancelled', () => {
    const { classifier, onSingle } = setup();

    classifier.tap({ x: 0, y: 0 });
    classifier.cancel();
    jest.advanceTimersByTime(DOUBLE_TAP_MS);

    expect(onSingle).not.toHaveBeenCalled();
  });
});
