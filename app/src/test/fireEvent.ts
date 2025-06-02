import { act, fireEvent as syncFireEvent } from '@testing-library/react-native';

type Element = Parameters<typeof syncFireEvent>[0];
type EventName = Parameters<typeof syncFireEvent>[1];
type EventProps = Record<string, unknown>;

/**
 * `fireEvent` for tests that await what a handler starts: the library's own calls the handler inside a
 * synchronous act() and passes only the arguments given. Here the handler runs inside an async act(), so
 * what it starts on promises (a mutation's request after its onMutate, a state update after a picked
 * file comes back) has settled and rendered when the returned promise resolves; and `press` and `scroll`
 * pass a default synthetic event, with the props given deep-merged into it.
 */
export async function fireEvent(
  element: Element,
  eventName: EventName,
  ...data: unknown[]
): Promise<void> {
  await act(async () => {
    syncFireEvent(element, eventName, ...data);
  });
}

fireEvent.press = (element: Element, eventProps?: EventProps) =>
  fireEvent(element, 'press', merge(touchEvent(), eventProps));
fireEvent.changeText = (element: Element, text: string) => fireEvent(element, 'changeText', text);
fireEvent.scroll = (element: Element, eventProps?: EventProps) =>
  fireEvent(element, 'scroll', merge(scrollEvent(), eventProps));

/** What every synthetic event has. */
function syntheticEvent() {
  return {
    currentTarget: {},
    target: {},
    preventDefault: () => {},
    isDefaultPrevented: () => false,
    stopPropagation: () => {},
    isPropagationStopped: () => false,
    persist: () => {},
    isPersistent: () => false,
    timeStamp: 0,
  };
}

/** A touch at the origin. */
function touchEvent(): EventProps {
  return {
    ...syntheticEvent(),
    nativeEvent: {
      changedTouches: [],
      identifier: 0,
      locationX: 0,
      locationY: 0,
      pageX: 0,
      pageY: 0,
      target: 0,
      timestamp: Date.now(),
      touches: [],
    },
    currentTarget: { measure: () => {} },
  };
}

/** A scroll at offset 0. */
function scrollEvent(): EventProps {
  return {
    ...syntheticEvent(),
    nativeEvent: {
      contentInset: { bottom: 0, left: 0, right: 0, top: 0 },
      contentOffset: { y: 0, x: 0 },
      contentSize: { height: 0, width: 0 },
      layoutMeasurement: { height: 0, width: 0 },
      responderIgnoreScroll: true,
      target: 0,
      velocity: { y: 0, x: 0 },
    },
  };
}

/** Plain objects merge key by key, anything else replaces. */
function merge(target: EventProps, source?: EventProps): EventProps {
  for (const [key, value] of Object.entries(source ?? {})) {
    const current = target[key];
    if (isPlainObject(value) && isPlainObject(current)) {
      merge(current, value);
    } else {
      target[key] = value;
    }
  }
  return target;
}

function isPlainObject(value: unknown): value is EventProps {
  return value != null && typeof value === 'object' && !Array.isArray(value);
}
