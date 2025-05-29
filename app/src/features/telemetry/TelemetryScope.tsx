import { createContext, useCallback, useContext, type ReactNode } from 'react';

import type { EventIn, EventInSurface, EventInType } from '@/api/generated/model';

import { telemetry } from './telemetry';

/** Where on the screen an event happens. */
export type TelemetryPlace = {
  surface: EventInSurface;
  /** The feed session the post was served in, when it came from the For You feed. */
  sessionId?: string;
  /** The post's position in that session or list. */
  position?: number;
};

const Place = createContext<TelemetryPlace>({ surface: 'POST' });

/** Says where the components inside it are, so that their events carry it. */
export function TelemetryScope({
  place,
  children,
}: {
  place: TelemetryPlace;
  children: ReactNode;
}) {
  return <Place.Provider value={place}>{children}</Place.Provider>;
}

export type Track = (type: EventInType, postId: string, value?: number) => void;

/** Builds the event a component reports from the place it is in. */
export function eventFor(
  place: TelemetryPlace,
  type: EventInType,
  postId: string,
  value: number | undefined,
  now: Date,
): EventIn {
  return {
    type,
    postId,
    surface: place.surface,
    sessionId: place.sessionId ?? null,
    position: place.position ?? null,
    value: value ?? null,
    clientTs: now.toISOString(),
  };
}

/**
 * Returns the function components report interactions with: `track('LIKE', post.id)`. It never
 * throws and never waits; the event is queued and sent with the next batch.
 */
export function useTrack(): Track {
  const place = useContext(Place);
  return useCallback(
    (type, postId, value) => telemetry.track(eventFor(place, type, postId, value, new Date())),
    [place],
  );
}
