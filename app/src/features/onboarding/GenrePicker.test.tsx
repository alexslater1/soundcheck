import { fireEvent, render, screen } from '@testing-library/react-native';
import { useState } from 'react';

import type { GenreResponse } from '@/api/generated/model';
import { ThemeProvider } from '@/theme/ThemeProvider';

import { GenrePicker } from './GenrePicker';
import type { GenreSelection } from './genreSelection';

const GENRES: GenreResponse[] = [
  { id: 1, slug: 'rock', name: 'Rock', parentId: null, aliases: ['hard rock'] },
  { id: 2, slug: 'indie', name: 'Indie', parentId: 1, aliases: ['britpop', 'shoegaze'] },
  { id: 14, slug: 'techno', name: 'Techno', parentId: 12, aliases: ['minimal'] },
];

const changes = jest.fn<void, [GenreSelection]>();
/** The selection after the last change. */
const latest = (): GenreSelection => changes.mock.calls.at(-1)?.[0] ?? new Map();

function Harness({ showWeights }: { showWeights?: boolean }) {
  const [selection, setSelection] = useState<GenreSelection>(new Map());
  return (
    <ThemeProvider>
      <GenrePicker
        genres={GENRES}
        selection={selection}
        onChange={(next) => {
          changes(next);
          setSelection(next);
        }}
        showWeights={showWeights}
      />
    </ThemeProvider>
  );
}

describe('GenrePicker', () => {
  beforeEach(() => changes.mockReset());

  it('saves a pick as "love" and asks nothing more at sign-up', async () => {
    await render(<Harness />);

    await fireEvent.press(screen.getByRole('button', { name: 'Indie' }));

    expect(latest().get(2)).toBe(1);
    expect(screen.queryByText('love')).toBeNull();
    expect(screen.queryByText('like')).toBeNull();
  });

  it('narrows the chips by search, finding a style by its other name', async () => {
    await render(<Harness />);
    await fireEvent.press(screen.getByRole('button', { name: 'Techno' }));

    await fireEvent.changeText(screen.getByLabelText('Search genres'), 'shoe');

    expect(screen.getByRole('button', { name: 'Indie' })).toBeTruthy();
    expect(screen.getByText('shoegaze')).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Rock' })).toBeNull();
    // The pick stays in view (and removable) while searching.
    expect(screen.getByTestId('genres-picked')).toBeTruthy();
    await fireEvent.press(screen.getByRole('button', { name: 'Techno' }));
    expect(latest().has(14)).toBe(false);

    await fireEvent.changeText(screen.getByLabelText('Search genres'), 'polka');
    expect(screen.getByTestId('genres-no-match')).toBeTruthy();
  });

  it('offers love / like for each pick in Settings', async () => {
    await render(<Harness showWeights />);
    await fireEvent.press(screen.getByRole('button', { name: 'Rock' }));

    await fireEvent.press(screen.getByLabelText('Rock: like it'));

    expect(latest().get(1)).toBe(0.5);
  });
});
