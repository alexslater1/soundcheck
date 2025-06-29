package app.soundcheck;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import app.soundcheck.shared.config.ConditionalOnRole;
import app.soundcheck.shared.config.Roles;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RestController;

/**
 * Architecture rules beyond what Spring Modulith checks: no cross-module {@code internal}
 * access, controllers never touch jOOQ directly and exist only in the {@code api} role, no
 * {@code java.util.Date}, and each module only uses the jOOQ classes generated for its own schema (plus
 * {@code shared}).
 */
class ArchitectureTests {

    static final String BASE = "app.soundcheck";
    static final List<String> MODULES = List.of(
            "shared",
            "identity",
            "social",
            "catalog",
            "ingestion",
            "media",
            "content",
            "signals",
            "feed",
            "notifications",
            "analytics");

    static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(BASE);
    }

    @Test
    void noModuleReachesIntoAnotherModulesInternals() {
        for (String module : MODULES) {
            ArchRule rule = noClasses()
                    .that()
                    .resideOutsideOfPackage(BASE + "." + module + "..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAPackage(BASE + "." + module + ".internal..")
                    .as("no class outside module '" + module + "' may use its internal package");
            rule.check(classes);
        }
    }

    @Test
    void controllersDoNotUseJooqDirectly() {
        noClasses()
                .that()
                .haveSimpleNameEndingWith("Controller")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("org.jooq..", BASE + ".shared.jooq..")
                .as("controllers are thin: validate, call a service, map the response")
                .check(classes);
    }

    @Test
    void controllersExistOnlyInTheApiRole() {
        // A worker or cli process serves health checks only, never the API.
        DescribedPredicate<JavaAnnotation<?>> apiRole = DescribedPredicate.describe(
                "@ConditionalOnRole(Roles.API)",
                a -> a.getRawType().isEquivalentTo(ConditionalOnRole.class)
                        && Roles.API.equals(a.get("value").orElse(null)));
        classes()
                .that()
                .areAnnotatedWith(RestController.class)
                .should()
                .beAnnotatedWith(apiRole)
                .as("every @RestController is registered only in the api role")
                .check(classes);
    }

    @Test
    void noJavaUtilDate() {
        noClasses()
                .should()
                .dependOnClassesThat()
                .belongToAnyOf(
                        java.util.Date.class, java.sql.Date.class, java.sql.Timestamp.class, java.util.Calendar.class)
                .as("use java.time (Instant in UTC) everywhere")
                .check(classes);
    }

    @Test
    void modulesOnlyUseTheirOwnSchemasGeneratedClasses() {
        // Module name → jOOQ schema package(s) it may use; `notifications` owns schema `notify`.
        for (String module : MODULES) {
            if (module.equals("shared") || module.equals("analytics")) {
                continue; // shared owns the generated code; analytics reads views across schemas
            }
            String schema = module.equals("notifications") ? "notify" : module;
            Set<String> allowed = Set.of(BASE + ".shared.jooq." + schema, BASE + ".shared.jooq.shared");
            noClasses()
                    .that()
                    .resideInAPackage(BASE + "." + module + "..")
                    .should(useJooqClassesOfAnotherSchema(allowed))
                    .as("module '" + module + "' may only query schema '" + schema + "' (and shared)")
                    .check(classes);
        }
    }

    private static ArchCondition<JavaClass> useJooqClassesOfAnotherSchema(Set<String> allowed) {
        String jooqRoot = BASE + ".shared.jooq.";
        // Used under noClasses().should(...): an event marked "satisfied" is a violation of the rule.
        return new ArchCondition<>("use jOOQ classes of another module's schema") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                item.getDirectDependenciesFromSelf().forEach(dep -> {
                    String pkg = dep.getTargetClass().getPackageName();
                    if (!pkg.startsWith(jooqRoot)) {
                        return;
                    }
                    String schemaPkg =
                            jooqRoot + pkg.substring(jooqRoot.length()).split("\\.")[0];
                    if (!allowed.contains(schemaPkg)) {
                        events.add(SimpleConditionEvent.satisfied(
                                item,
                                item.getName() + " uses " + dep.getTargetClass().getName()));
                    }
                });
            }
        };
    }
}
