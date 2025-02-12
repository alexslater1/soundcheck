package app.soundcheck;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

/**
 * Enforces the module boundaries: no module touches another's {@code internal}
 * package, declared {@code allowedDependencies} hold, and there are no cycles. Also writes the module
 * documentation (C4/UML diagrams and canvases) to {@code build/spring-modulith-docs}.
 */
class ModularityTests {

    static final ApplicationModules modules = ApplicationModules.of(SoundcheckApplication.class);

    @Test
    void modulesRespectTheirDeclaredBoundaries() {
        modules.verify();
    }

    @Test
    void writesModuleDocumentation() {
        new Documenter(modules).writeDocumentation();
    }
}
