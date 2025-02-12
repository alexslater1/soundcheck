package app.soundcheck.shared;

import com.fasterxml.uuid.Generators;
import com.fasterxml.uuid.impl.TimeBasedEpochRandomGenerator;
import java.util.UUID;

/**
 * Application-generated identifiers: UUIDv7 — time-ordered so B-tree inserts stay local,
 * unguessable in public URLs, and usable client-side for idempotency.
 */
public final class Ids {

    private static final TimeBasedEpochRandomGenerator GENERATOR = Generators.timeBasedEpochRandomGenerator();

    private Ids() {}

    public static UUID newId() {
        return GENERATOR.generate();
    }
}
