package app.soundcheck.ingestion.internal.resolve;

import static org.assertj.core.api.Assertions.assertThat;

import app.soundcheck.ingestion.internal.resolve.TitleParser.Flag;
import app.soundcheck.ingestion.internal.resolve.TitleParser.Parsed;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The title parser, one row per title shape seen on London venue sites (made-up acts, real shapes):
 * the acts in billing order, the flags and the tour name. {@code |} separates expected acts.
 */
class TitleParserTest {

    static Stream<Arguments> titles() {
        return Stream.of(
                // plain and simple bills
                row("Pale Kites", "Pale Kites"),
                row("Pale Kites + Night Bus", "Pale Kites|Night Bus"),
                row("Pale Kites + Night Bus + Glass Harbour", "Pale Kites|Night Bus|Glass Harbour"),
                row("Pale Kites w/ Night Bus", "Pale Kites|Night Bus"),
                row("Pale Kites w/Night Bus", "Pale Kites|Night Bus"),
                row("Pale Kites with Night Bus", "Pale Kites|Night Bus"),
                row("Pale Kites, Night Bus, Glass Harbour", "Pale Kites|Night Bus|Glass Harbour"),
                row("Pale Kites / Night Bus", "Pale Kites|Night Bus"),
                row("Pale Kites supported by Night Bus", "Pale Kites|Night Bus"),
                row("Pale Kites support from Night Bus & The Lanterns", "Pale Kites|Night Bus & The Lanterns"),
                row("Pale Kites. Support: Night Bus", "Pale Kites|Night Bus"),
                row("DJ Meredith feat. Sol Rivers", "DJ Meredith|Sol Rivers"),
                row("DJ Meredith ft. Sol Rivers", "DJ Meredith|Sol Rivers"),
                // names that contain separators must survive
                row("Simon & Garfunkel Tribute", "Simon & Garfunkel Tribute"),
                row("Echo & The Bunnymen", "Echo & The Bunnymen"),
                row("Florence and the Machine Tribute Night", "Florence and the Machine Tribute Night"),
                row("AC/DC UK", "AC/DC UK"),
                row("Hot Chip DJs + Night Bus", "Hot Chip DJs|Night Bus"),
                row("Crosby, Stills & Nash Revue", "Crosby|Stills & Nash Revue"), // known limit: commas split
                // placeholders are not acts
                row("Pale Kites + support", "Pale Kites"),
                row("Pale Kites + Special Guests", "Pale Kites"),
                row("Pale Kites + very special guests", "Pale Kites"),
                row("Pale Kites + guests", "Pale Kites"),
                row("Pale Kites + more TBA", "Pale Kites"),
                row("Pale Kites + Night Bus + more", "Pale Kites|Night Bus"),
                row("Pale Kites with friends", "Pale Kites"),
                row("Pale Kites + support TBC", "Pale Kites"),
                // status words, anywhere
                row("SOLD OUT: Pale Kites", "Pale Kites", Set.of(Flag.SOLD_OUT)),
                row("SOLD OUT - Pale Kites + Night Bus", "Pale Kites|Night Bus", Set.of(Flag.SOLD_OUT)),
                row("Pale Kites (SOLD OUT)", "Pale Kites", Set.of(Flag.SOLD_OUT)),
                row("**SOLD OUT** Pale Kites", "Pale Kites", Set.of(Flag.SOLD_OUT)),
                row("Pale Kites - SOLD OUT", "Pale Kites", Set.of(Flag.SOLD_OUT)),
                row("CANCELLED Pale Kites", "Pale Kites", Set.of(Flag.CANCELLED)),
                row("Pale Kites [CANCELED]", "Pale Kites", Set.of(Flag.CANCELLED)),
                row("POSTPONED: Pale Kites", "Pale Kites", Set.of(Flag.POSTPONED)),
                row("NEW DATE Pale Kites", "Pale Kites", Set.of(Flag.RESCHEDULED)),
                row("Pale Kites - RESCHEDULED", "Pale Kites", Set.of(Flag.RESCHEDULED)),
                row("Last Few Tickets! Pale Kites", "Pale Kites"),
                row("Pale Kites (Low Tickets)", "Pale Kites"),
                row("Pale Kites - Selling Fast", "Pale Kites"),
                // show flags
                row("Pale Kites (Early Show)", "Pale Kites", Set.of(Flag.EARLY_SHOW)),
                row("Pale Kites - Late Show", "Pale Kites", Set.of(Flag.LATE_SHOW)),
                row("Pale Kites (18+)", "Pale Kites", Set.of(Flag.AGE_RESTRICTED)),
                row("Pale Kites 14+", "Pale Kites", Set.of(Flag.AGE_RESTRICTED)),
                row("Pale Kites (Over 18s)", "Pale Kites", Set.of(Flag.AGE_RESTRICTED)),
                row("FREE ENTRY: Open Mic Night", "Open Mic Night", Set.of(Flag.FREE)),
                row("Pale Kites (Free)", "Pale Kites", Set.of(Flag.FREE)),
                // promoters
                row("Club Fandango presents: Pale Kites", "Pale Kites"),
                row("Club Fandango Presents Pale Kites + Night Bus", "Pale Kites|Night Bus"),
                row("Eat Your Own Ears present Pale Kites", "Pale Kites"),
                row("Communion Presents - Pale Kites", "Pale Kites"),
                // tours and event names
                row("Pale Kites: The Long Way Home Tour", "Pale Kites", Set.of(), "The Long Way Home Tour"),
                row("Pale Kites - Album Launch", "Pale Kites", Set.of(), "Album Launch"),
                row("Pale Kites – UK Tour 2026", "Pale Kites", Set.of(), "UK Tour 2026"),
                row("Pale Kites | Headline Show", "Pale Kites", Set.of(), "Headline Show"),
                row("Pale Kites (Album Launch)", "Pale Kites", Set.of(), "Album Launch"),
                row("Pale Kites (Acoustic)", "Pale Kites", Set.of(), "Acoustic"),
                row("Pale Kites plays 'Dusk' in full", "Pale Kites plays 'Dusk' in full"),
                row("Night Bus - Glass Harbour", "Night Bus - Glass Harbour"), // no tour word: not cut
                // everything at once
                row(
                        "SOLD OUT: Club Fandango presents Pale Kites + Night Bus + support (18+)",
                        "Pale Kites|Night Bus",
                        Set.of(Flag.SOLD_OUT, Flag.AGE_RESTRICTED)),
                row(
                        "NEW DATE: Pale Kites w/ Night Bus, Glass Harbour - Autumn Tour (Early Show)",
                        "Pale Kites|Night Bus|Glass Harbour",
                        Set.of(Flag.RESCHEDULED, Flag.EARLY_SHOW),
                        "Autumn Tour"),
                // shapes found on the first live run of the venue sites (2025-06-05), invented names
                row("Lantern Ultimate Showcase: Steeplehound", "Steeplehound", Set.of(), "Lantern Ultimate Showcase"),
                row(
                        "LOUD LANTERNS 11th Birthday Party: Alexa Leaving + Gem Rogue + Lola Pony",
                        "Alexa Leaving|Gem Rogue|Lola Pony",
                        Set.of(),
                        "LOUD LANTERNS 11th Birthday Party"),
                row(
                        "SHAKE SOME LANTERN #178.THE SPACEHOUNDS,HAUNTED MEN,THE BAD BREEDS",
                        "THE SPACEHOUNDS|HAUNTED MEN|THE BAD BREEDS"),
                row(
                        "SHAKE SOME LANTERN #183. JELLY DIVERS, GLITTER KICKS, X- RAY LOVERS",
                        "JELLY DIVERS|GLITTER KICKS|X- RAY LOVERS"),
                row("Rockaway Lantern #112 Andi Sax Gang", "Andi Sax Gang"),
                row("Subject B supporting Skaville Lantern", "Skaville Lantern|Subject B"),
                row(
                        "Bones and the Raft plus Edmund Fjord and Warren Marten",
                        "Bones and the Raft|Edmund Fjord and Warren Marten"),
                row(
                        "Micko & The Mellowtones/Rachel Mayfair Group/European Moon",
                        "Micko & The Mellowtones|Rachel Mayfair Group|European Moon"),
                row("Less Than Hollow and guests TBA", "Less Than Hollow"),
                row("Less Than Hollow + guests TBC", "Less Than Hollow"),
                row("Odd Lust DJ set", "Odd Lust"),
                row(
                        "DESPERATE MEASURES 45TH ANNIVERSARY SHOW",
                        "DESPERATE MEASURES",
                        Set.of(),
                        "45TH ANNIVERSARY SHOW"),
                row("Emilie Cushion IGYC Tour London", "Emilie Cushion", Set.of(), "IGYC Tour London"),
                row("Civil War Presents... Sleeping Mountains", "Sleeping Mountains"),
                row("K2B Presents... An Evening with Groom of the Stool", "Groom of the Stool"),
                row("Back With a Bang - The Satellights", "Back With a Bang - The Satellights"),
                row("NIGHT BUS WITH PALE KITES", "NIGHT BUS|PALE KITES"),
                // unicode, whitespace, punctuation
                row("  Sigur Rós  +  Björk  ", "Sigur Rós|Björk"),
                row("Pale Kites + Night Bus", "Pale Kites|Night Bus"),
                row("!!! (Chk Chk Chk)", "!!! (Chk Chk Chk)"),
                row("Pale Kites!!", "Pale Kites"),
                row("Pale Kites + Pale Kites", "Pale Kites"));
    }

    /** Titles written as sentences about the venue (Amersham Arms style), made-up acts. */
    static Stream<Arguments> sentences() {
        return Stream.of(
                Arguments.of(
                        "Saturday 10th October &#8211; Kholla take the helm".replace("&#8211;", "–"),
                        "Kholla take the helm"),
                Arguments.of(
                        "Headlining Rough Edges at The Lantern Arms on Thursday 22nd October – S0FTD0LLY.",
                        "S0FTD0LLY"),
                Arguments.of(
                        "The Ondines are headlining at The Lantern Arms on Saturday 24th October 2026!", "The Ondines"),
                Arguments.of("Keez headlines at The Lantern Arms on Thursday 29th October!", "Keez"),
                Arguments.of("Pale Kites live at the Lantern Arms", "Pale Kites"),
                Arguments.of("Pale Kites @ Lantern Arms Fri 27 Nov 2026", "Pale Kites"),
                Arguments.of(
                        "Joe Clark, Ricardo Tarma, Father Levi, Lydia", "Joe Clark|Ricardo Tarma|Father Levi|Lydia"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("sentences")
    void readsActsOutOfSentencesAboutTheVenue(String title, String acts) {
        assertThat(TitleParser.parse(title, "The Lantern Arms").acts())
                .containsExactlyElementsOf(Arrays.asList(acts.split("\\|")));
    }

    /** A venue whose name has an "&", written with "and" too, and after a bar with the city. */
    static Stream<Arguments> ampersandVenue() {
        return Stream.of(
                Arguments.of(
                        "Back With a Bang - The Satellights at The Hope and Lantern",
                        "Back With a Bang - The Satellights"),
                Arguments.of("Connor McBryde | London, Hope & Lantern", "Connor McBryde"),
                Arguments.of("Silent Confidants @Hope and Lantern", "Silent Confidants"),
                Arguments.of("IDestroyer live @ Hope & Lantern: SOLD OUT", "IDestroyer"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("ampersandVenue")
    void recognisesTheVenueWrittenWithAndOrAmpersand(String title, String acts) {
        assertThat(TitleParser.parse(title, "Hope & Lantern").acts())
                .containsExactlyElementsOf(Arrays.asList(acts.split("\\|")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("titles")
    void readsTheActsFlagsAndTour(String title, List<String> acts, Set<Flag> flags, String tour) {
        Parsed parsed = TitleParser.parse(title);
        assertThat(parsed.acts()).as("acts").containsExactlyElementsOf(acts);
        assertThat(parsed.flags()).as("flags").containsExactlyInAnyOrderElementsOf(flags);
        assertThat(parsed.tour()).as("tour").isEqualTo(tour);
    }

    private static Arguments row(String title, String acts) {
        return row(title, acts, Set.of(), null);
    }

    private static Arguments row(String title, String acts, Set<Flag> flags) {
        return row(title, acts, flags, null);
    }

    private static Arguments row(String title, String acts, Set<Flag> flags, String tour) {
        return Arguments.of(title, Arrays.asList(acts.split("\\|")), flags, tour);
    }
}
