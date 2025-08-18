import { clipInBox, fitClip, pageFit } from './pageFit';

describe('pageFit', () => {
  // A Pixel's feed area: a 20:9 screen less the header and the tab bar.
  const width = 412;
  const height = 780;

  it('fills the page with a portrait 9:16 clip, cropping a sliver instead of black bands', () => {
    expect(pageFit(9 / 16, width, height)).toEqual({ fill: true, width, height });
  });

  it('fits a landscape clip whole (over its blurred poster)', () => {
    const fit = pageFit(16 / 9, width, height);
    expect(fit.fill).toBe(false);
    expect(fit.width).toBe(width);
    expect(fit.height).toBeCloseTo(width / (16 / 9));
  });

  it('fits a clip whose shape is more than 25 % off the page, either way', () => {
    expect(pageFit(0.75, width, height).fill).toBe(false); // 4:3 portrait on a tall page: 1.42×
    expect(pageFit(0.4, width, height).fill).toBe(false); // a very tall clip: 0.76×
    expect(pageFit(0.66, width, height).fill).toBe(true); // 1.25× exactly still fills
  });

  it('fitClip keeps the shape inside the box', () => {
    expect(fitClip(9 / 16, 412, 600)).toEqual({ width: 337.5, height: 600 });
  });
});

describe('clipInBox', () => {
  const portraitBox = 9 / 16;

  it('lets a clip of the box’s shape (or close) fill it, and an unknown shape too', () => {
    expect(clipInBox(9 / 16, portraitBox)).toBeNull();
    expect(clipInBox(0.6, portraitBox)).toBeNull();
    expect(clipInBox(null, portraitBox)).toBeNull();
  });

  it('fits a landscape clip into a portrait box by width, centred', () => {
    const fit = clipInBox(16 / 9, portraitBox);
    expect(fit?.width).toBe(1);
    expect(fit?.height).toBeCloseTo(portraitBox / (16 / 9));
  });

  it('fits a portrait clip into a landscape box by height', () => {
    const fit = clipInBox(9 / 16, 16 / 9);
    expect(fit?.height).toBe(1);
    expect(fit?.width).toBeCloseTo(9 / 16 / (16 / 9));
  });
});

describe('a feed page of several clips', () => {
  it('fits a landscape clip whole in the page’s box, and lets a portrait one fill it', () => {
    const page = 412 / 780;
    const landscape = clipInBox(16 / 9, page);
    expect(landscape?.width).toBe(1);
    expect(landscape?.height).toBeCloseTo(page / (16 / 9));
    expect(clipInBox(9 / 16, page)).toBeNull();
  });
});
