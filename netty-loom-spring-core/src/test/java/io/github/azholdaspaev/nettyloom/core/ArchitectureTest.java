package io.github.azholdaspaev.nettyloom.core;

import static com.tngtech.archunit.core.importer.ImportOption.Predefined.DO_NOT_INCLUDE_TESTS;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ArchitectureTest {

    private static JavaClasses coreClasses;

    @BeforeAll
    static void importClasses() {
        coreClasses = new ClassFileImporter()
                .withImportOption(DO_NOT_INCLUDE_TESTS)
                .importPackages("io.github.azholdaspaev.nettyloom.core");
    }

    @Test
    void coreShouldNotDependOnSpring() {
        noClasses()
                .that()
                .resideInAPackage("io.github.azholdaspaev.nettyloom.core..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage("org.springframework..")
                .check(coreClasses);
    }

    @Test
    void coreShouldNotDependOnJakartaServlet() {
        noClasses()
                .that()
                .resideInAPackage("io.github.azholdaspaev.nettyloom.core..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage("jakarta.servlet..")
                .check(coreClasses);
    }
}
