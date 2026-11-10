package com.mzerp;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * 모듈 의존 방향 강제 (ADR-0001).
 * common ← identity ← master ← (2단계부터) journal ← ledger …
 */
@AnalyzeClasses(packages = "com.mzerp", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule modulesAreFreeOfCycles =
            slices().matching("com.mzerp.(*)..").should().beFreeOfCycles();

    @ArchTest
    static final ArchRule masterSubmodulesAreFreeOfCycles =
            slices().matching("com.mzerp.master.(*)..").should().beFreeOfCycles();

    @ArchTest
    static final ArchRule commonDependsOnNothing =
            noClasses().that().resideInAPackage("com.mzerp.common..")
                    .should().dependOnClassesThat().resideInAnyPackage("com.mzerp.identity..", "com.mzerp.master..");

    @ArchTest
    static final ArchRule identityDoesNotKnowMasterData =
            noClasses().that().resideInAPackage("com.mzerp.identity..")
                    .should().dependOnClassesThat().resideInAPackage("com.mzerp.master..");
}
