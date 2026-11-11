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

    /** 기준정보는 전표를 모른다: 전표가 기준정보를 쓰는 한 방향만 허용 (ADR-0001 표) */
    @ArchTest
    static final ArchRule masterDataDoesNotKnowJournal =
            noClasses().that().resideInAnyPackage("com.mzerp.master..", "com.mzerp.identity..", "com.mzerp.common..")
                    .should().dependOnClassesThat().resideInAPackage("com.mzerp.journal..");

    /** ERP는 실습 층을 모른다: 실습 층이 ERP의 공개 서비스를 쓰는 한 방향만 허용 (ADR-0012) */
    @ArchTest
    static final ArchRule erpDoesNotKnowLearning =
            noClasses().that().resideInAnyPackage("com.mzerp.common..", "com.mzerp.identity..", "com.mzerp.master..", "com.mzerp.journal..")
                    .should().dependOnClassesThat().resideInAPackage("com.mzerp.learning..");

    /** 전표 라인은 전표(JournalEntry)를 통해서만 만든다 — 차대 검증을 우회하지 못하게 */
    @ArchTest
    static final ArchRule linesAreCreatedOnlyThroughEntry =
            noClasses().that().doNotHaveFullyQualifiedName("com.mzerp.journal.JournalEntry")
                    .should().callConstructor(com.mzerp.journal.JournalLine.class,
                            com.mzerp.journal.JournalEntry.class, int.class, com.mzerp.journal.JournalEntry.LineDraft.class);
}
