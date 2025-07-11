import { approximate, distanceParams } from './position';

describe('approximate', () => {
  it('rounds to 0.01°, about a kilometre, before anything leaves the phone', () => {
    expect(approximate(51.507351, -0.127758)).toEqual({ lat: 51.51, lng: -0.13 });
    expect(approximate(53.4794, -2.2453)).toEqual({ lat: 53.48, lng: -2.25 });
    expect(approximate(51.504999, 0.004999)).toEqual({ lat: 51.5, lng: 0 });
  });

  it('turns a position into the lists’ fromLat / fromLng, and nothing into nothing', () => {
    expect(distanceParams({ lat: 51.51, lng: -0.13 })).toEqual({ fromLat: 51.51, fromLng: -0.13 });
    expect(distanceParams(null)).toEqual({});
  });
});
