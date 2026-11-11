package com.mzerp.learning.course;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mzerp.common.error.ApiException;
import com.mzerp.journal.Side;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 콘텐츠 검증 (ADR-0015): 과정 파일이 엔진과 어긋난 채로 머지되지 않게 한다.
 * 정답 분개가 ERP에서 실제로 전기되는지는 골든 테스트(ScenarioGoldenTest)가 시나리오 전체로 확인한다.
 */
@DisplayName("과정 콘텐츠: 미션이 시나리오 거래와 연결되고 필요한 내용을 갖췄다")
class CourseContentTest {

    private final Course course = new CourseCatalog().get("accounting-basics");

    @Test
    @DisplayName("1장 미션 6개가 시나리오 거래를 가리키고, 날짜 순서대로 놓여 있다")
    void missionsPointToScenarioTransactions() {
        assertThat(course.scenario().transactions()).hasSize(38);
        assertThat(course.missions()).extracting(m -> m.answer().no()).containsExactly(1, 2, 5, 6, 7, 8);
        assertThat(course.missions()).extracting(Course.Mission::date).isSorted();
        assertThat(course.mission("receivable-collection").answer().description()).contains("한빛전자");
    }

    @Test
    @DisplayName("모든 미션: 상황 설명이 있고, 힌트가 2개 이상이며, 정답 분개의 차변과 대변이 같다")
    void everyMissionIsComplete() {
        for (Course.Mission m : course.missions()) {
            assertThat(m.brief()).as(m.key() + " 상황").isNotBlank();
            assertThat(m.hints()).as(m.key() + " 힌트").hasSizeGreaterThanOrEqualTo(2);
            long debit = m.answer().lines().stream().filter(l -> l.side() == Side.DEBIT).mapToLong(l -> l.amount()).sum();
            long credit = m.answer().lines().stream().filter(l -> l.side() == Side.CREDIT).mapToLong(l -> l.amount()).sum();
            assertThat(debit).as(m.key() + " 차대").isEqualTo(credit).isPositive();
        }
    }

    @Test
    @DisplayName("없는 과정이나 경로처럼 생긴 키는 404")
    void unknownCourse() {
        assertThatThrownBy(() -> new CourseCatalog().get("no-such-course")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> new CourseCatalog().get("../scenario/mz-electronics-2026")).isInstanceOf(ApiException.class);
    }
}
