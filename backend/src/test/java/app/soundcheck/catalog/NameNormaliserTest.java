package app.soundcheck.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** The normaliser's edge cases. */
class NameNormaliserTest {

    @ParameterizedTest(name = "\"{0}\" → \"{1}\"")
    @CsvSource(
            delimiter = '|',
            textBlock =
                    """
            The 1975                  | 1975
            1975                      | 1975
            Florence + The Machine    | florence and the machine
            Florence & the Machine    | florence and the machine
            Florence And The Machine  | florence and the machine
            Sigur Rós                 | sigur ros
            SIGUR ROS                 | sigur ros
            Björk                     | bjork
            MØ                        | mo
            Mötley Crüe               | motley crue
            Beyoncé                   | beyonce
            AC/DC                     | ac dc
            Guns N' Roses             | guns n roses
            Guns N’ Roses             | guns n roses
            D.J. Shadow               | dj shadow
            Jay-Z                     | jay z
            '  The   Smiths  '        | smiths
            The The                   | the
            Sigrid (Acoustic)         | sigrid acoustic
            Simon & Garfunkel         | simon and garfunkel
            Straße                    | strasse
            Thé Vines                 | vines
            Theatre of Hate           | theatre of hate
            !!!                       | !!!
            """)
    void normalises(String name, String expected) {
        assertThat(NameNormaliser.normalise(name)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            textBlock =
                    """
            The Smiths | The Smyths
            Oasis      | Oasish
            Queen      | Qween
            """)
    void tributeActsDoNotNormaliseToTheOriginal(String original, String tribute) {
        assertThat(NameNormaliser.normalise(tribute)).isNotEqualTo(NameNormaliser.normalise(original));
    }
}
