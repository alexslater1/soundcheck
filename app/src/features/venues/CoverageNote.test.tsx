import { render, screen } from '@testing-library/react-native';

import { ThemeProvider } from '@/theme/ThemeProvider';

import { CoverageNote } from './CoverageNote';

jest.mock('@expo/vector-icons/Ionicons', () => 'Ionicons');

const NOTE =
  "We don't read this venue's own listings: its list is copied in from Facebook and Eventbrite, so it isn't the venue's own. Its gigs here come from Ticketmaster and fans, so some may be missing.";

describe('CoverageNote', () => {
  it('shows the note as a card, or as a caption under a gig', async () => {
    await render(
      <ThemeProvider>
        <CoverageNote note={NOTE} />
      </ThemeProvider>,
    );
    expect(screen.getByTestId('coverage-note')).toHaveTextContent(NOTE);
  });

  it('renders nothing for a venue without a note', async () => {
    await render(
      <ThemeProvider>
        <CoverageNote note={null} />
        <CoverageNote compact />
      </ThemeProvider>,
    );
    expect(screen.queryByTestId('coverage-note')).toBeNull();
  });
});
