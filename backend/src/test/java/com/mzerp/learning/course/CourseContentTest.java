package com.mzerp.learning.course;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.mzerp.common.error.ApiException;
import com.mzerp.journal.Side;
import com.mzerp.learning.grading.Balances;
import com.mzerp.learning.grading.EntryGrader;
import com.mzerp.learning.grading.Grader;
import com.mzerp.learning.grading.MissionGrader;
import com.mzerp.learning.scenario.Scenario;
import com.mzerp.master.account.StandardChart;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 콘텐츠 검증 (ADR-0015, ADR-0017): 과정 파일이 엔진과 어긋난 채로 머지되지 않게 한다.
 * 정답 분개가 ERP에서 실제로 전기되는지는 골든 테스트(ScenarioGoldenTest)가 시나리오 전체로 확인한다.
 */
@DisplayName("과정 콘텐츠: 미션이 시나리오 거래와 연결되고 필요한 내용을 갖췄다")
class CourseContentTest {

    private final Course course = new CourseCatalog().get("accounting-basics");
    private final EntryGrader entryGrader = new EntryGrader();

    private static Map<String, Grader.AccountInfo> standardAccounts() {
        return StandardChart.load().stream()
                .map(r -> new Grader.AccountInfo(r.code(), r.name(), r.category().name(), r.subgroup()))
                .collect(Collectors.toMap(Grader.AccountInfo::code, Function.identity()));
    }

    @Test
    @DisplayName("1장 미션 6개가 시나리오 거래를 가리키고, 날짜 순서대로 놓여 있다")
    void missionsPointToScenarioTransactions() {
        assertThat(course.scenario().transactions()).hasSize(38);
        assertThat(course.missions()).extracting(m -> m.answer().no()).containsExactly(1, 2, 5, 6, 7, 8);
        assertThat(course.missions()).extracting(Course.Mission::kind).containsOnly(Course.MissionKind.ENTRY);
        assertThat(course.missions()).extracting(Course.Mission::date).isSorted();
        assertThat(course.mission("receivable-collection").answer().description()).contains("한빛전자");
    }

    @Test
    @DisplayName("모든 미션: 상황 설명과 해설이 있고, 힌트가 2개 이상이며, 정답 분개의 차변과 대변이 같다")
    void everyMissionIsComplete() {
        for (Course.Mission m : course.missions()) {
            assertThat(m.brief()).as(m.key() + " 상황").isNotBlank();
            assertThat(m.hints()).as(m.key() + " 힌트").hasSizeGreaterThanOrEqualTo(2);
            assertThat(m.explanation()).as(m.key() + " 해설").contains("흔한 실수").contains("실무에서는");
            long debit = m.answer().lines().stream().filter(l -> l.side() == Side.DEBIT).mapToLong(l -> l.amount()).sum();
            long credit = m.answer().lines().stream().filter(l -> l.side() == Side.CREDIT).mapToLong(l -> l.amount()).sum();
            assertThat(debit).as(m.key() + " 차대").isEqualTo(credit).isPositive();
        }
    }

    @Test
    @DisplayName("모든 미션에 근거(basis)와 기출 대조(exam_ref)가 빈칸 없이 적혀 있다 (training-site.md §9)")
    void basisAndExamRefAreNotBlank() {
        for (Course.Mission m : course.missions()) {
            assertThat(m.basis()).as(m.key() + " 근거(basis)").isNotEmpty()
                    .allSatisfy(entry -> assertThat(entry).as(m.key() + " 근거 항목").isNotBlank());
            assertThat(m.examRef()).as(m.key() + " 기출 대조(exam_ref)").isNotBlank();
        }
    }

    @Test
    @DisplayName("오답 예시마다 통과하지 않고, 적어 둔 전용 사유가 첫 사유(MISTAKE)로 나온다 (ADR-0015, ADR-0017)")
    void everyMistakeFailsWithItsConfiguredMessage() {
        Map<String, Grader.AccountInfo> accounts = standardAccounts();
        Set<String> validPartners = course.scenario().partners().stream()
                .map(Scenario.PartnerSeed::code)
                .collect(Collectors.toSet());

        for (Course.Mission m : course.missions()) {
            // 정답 분개 자체는 통과해야 한다
            assertThat(entryGrader.grade(MissionGrader.Context.ofEntry(m, Balances.ofLines(m.answer().lines()), accounts)))
                    .as(m.key() + " 정답 분개 통과").isEmpty();

            assertThat(m.mistakes()).as(m.key() + " 오답 예시").isNotEmpty();
            for (Course.Mistake mistake : m.mistakes()) {
                for (Scenario.Line line : mistake.lines()) {
                    assertThat(accounts).as(m.key() + " 오답 예시 계정 " + line.accountCode()).containsKey(line.accountCode());
                    if (line.partnerCode() != null) {
                        assertThat(validPartners).as(m.key() + " 오답 예시 거래처 " + line.partnerCode())
                                .contains(line.partnerCode());
                    }
                }
                List<Grader.Finding> findings = entryGrader.grade(
                        MissionGrader.Context.ofEntry(m, Balances.ofLines(mistake.lines()), accounts));
                assertThat(findings).as(m.key() + " 오답 예시 통과 금지").isNotEmpty();
                assertThat(findings.getFirst().type()).as(m.key() + " 오답 예시 유형").isEqualTo(Grader.FindingType.MISTAKE);
                assertThat(findings.getFirst().message()).as(m.key() + " 오답 예시 사유").isEqualTo(mistake.says());
            }
        }
    }

    @Test
    @DisplayName("모든 미션에 통장 거래내역이 있고, 그 입출금과 잔액이 정답 분개를 차례로 전기한 보통예금 장부와 같다")
    void bankStatementsAgreeWithTheLedger() {
        long ledger = course.scenario().opening().lines().stream()
                .filter(l -> l.accountCode().equals("103"))
                .mapToLong(l -> l.side() == Side.DEBIT ? l.amount() : -l.amount()).sum();
        for (Course.Mission m : course.missions()) {
            List<Course.Evidence.BankRow> rows = m.evidence().stream().flatMap(e -> e.bankRows().stream()).toList();
            assertThat(rows).as(m.key() + " 통장 거래내역").isNotEmpty();
            long opening = ((Number) m.evidence().stream().filter(e -> e.type().equals("bank")).findFirst().orElseThrow()
                    .body().get("opening")).longValue();
            assertThat(opening).as(m.key() + " 이전 잔액").isEqualTo(ledger);

            long running = opening;
            for (Course.Evidence.BankRow row : rows) {
                assertThat(row.date()).as(m.key() + " 거래일").isEqualTo(m.date().toString());
                running += row.in() - row.out();
                assertThat(row.balance()).as(m.key() + " " + row.memo() + " 잔액").isEqualTo(running);
            }
            // 통장에서 움직인 돈 = 정답 분개가 보통예금에 만드는 변화
            ledger += m.answer().lines().stream().filter(l -> l.accountCode().equals("103"))
                    .mapToLong(l -> l.side() == Side.DEBIT ? l.amount() : -l.amount()).sum();
            assertThat(running).as(m.key() + " 통장 잔액과 장부 잔액").isEqualTo(ledger);
        }
    }

    @Test
    @DisplayName("증빙의 금액은 거래 금액과 같다: 영수증·어음의 금액 = 정답 분개의 차변 합계")
    void evidenceAmountsMatchTheTransaction() {
        for (Course.Mission m : course.missions()) {
            long amount = m.answer().lines().stream().filter(l -> l.side() == Side.DEBIT).mapToLong(l -> l.amount()).sum();
            assertThat(m.evidence()).as(m.key() + " 증빙").hasSizeGreaterThanOrEqualTo(2);
            m.evidence().stream().filter(e -> !e.type().equals("bank")).forEach(e ->
                    assertThat(((Number) e.body().get("amount")).longValue()).as(m.key() + " " + e.title()).isEqualTo(amount));
        }
    }

    @Test
    @DisplayName("CourseCatalog는 kind 기본값과 형식, mistakes 차대·사유를 읽을 때 검증한다")
    void courseCatalogValidatesKindAndMistakes() {
        // kind를 생략한 테스트 과정은 ENTRY로 읽힌다
        Course defaultKind = new CourseCatalog().get("two-chapters-test");
        assertThat(defaultKind.missions()).extracting(Course.Mission::kind).containsOnly(Course.MissionKind.ENTRY);

        // quiz처럼 전표가 아닌 kind는 scenario 없이도 파싱된다
        Course quizCourse = CourseCatalog.parseToml("""
                key = "sample"
                version = 1
                title = "샘플"
                scenario = "mz-electronics-2026"
                [[chapters]]
                key = "ch0"
                title = "입사 첫날"
                [[chapters.missions]]
                key = "q1"
                kind = "quiz"
                title = "개념 확인"
                brief = "보기 중 하나를 고르세요."
                explanation = "해설"
                """);
        assertThat(quizCourse.mission("q1").kind()).isEqualTo(Course.MissionKind.QUIZ);
        assertThat(quizCourse.mission("q1").answer()).isNull();

        // 모르는 kind 거부
        assertThatThrownBy(() -> CourseCatalog.parseToml("""
                key = "bad"
                version = 1
                title = "잘못된 종류"
                scenario = "mz-electronics-2026"
                [[chapters]]
                key = "ch1"
                title = "1장"
                [[chapters.missions]]
                key = "m1"
                kind = "essay"
                scenario = 1
                title = "t"
                brief = "b"
                explanation = "e"
                """)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("모르는 미션 종류");

        // entry인데 scenario 누락 거부
        assertThatThrownBy(() -> CourseCatalog.parseToml("""
                key = "bad"
                version = 1
                title = "시나리오 누락"
                scenario = "mz-electronics-2026"
                [[chapters]]
                key = "ch1"
                title = "1장"
                [[chapters.missions]]
                key = "m1"
                kind = "entry"
                title = "t"
                brief = "b"
                explanation = "e"
                """)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("scenario");

        // mistakes 차대 불일치 거부
        assertThatThrownBy(() -> CourseCatalog.parseToml("""
                key = "bad"
                version = 1
                title = "오답 차대 불일치"
                scenario = "mz-electronics-2026"
                [[chapters]]
                key = "ch1"
                title = "1장"
                [[chapters.missions]]
                key = "m1"
                scenario = 1
                title = "t"
                brief = "b"
                explanation = "e"
                [[chapters.missions.mistakes]]
                lines = [["D", "817", 250000], ["C", "103", 200000]]
                says = "차대가 다릅니다"
                """)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("차변 합계");

        // mistakes says 빈칸 거부
        assertThatThrownBy(() -> CourseCatalog.parseToml("""
                key = "bad"
                version = 1
                title = "사유 빈칸"
                scenario = "mz-electronics-2026"
                [[chapters]]
                key = "ch1"
                title = "1장"
                [[chapters.missions]]
                key = "m1"
                scenario = 1
                title = "t"
                brief = "b"
                explanation = "e"
                [[chapters.missions.mistakes]]
                lines = [["D", "817", 250000], ["C", "103", 250000]]
                says = "   "
                """)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("says");
    }

    @Test
    @DisplayName("없는 과정이나 경로처럼 생긴 키는 404")
    void unknownCourse() {
        assertThatThrownBy(() -> new CourseCatalog().get("no-such-course")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> new CourseCatalog().get("../scenario/mz-electronics-2026")).isInstanceOf(ApiException.class);
    }
}
