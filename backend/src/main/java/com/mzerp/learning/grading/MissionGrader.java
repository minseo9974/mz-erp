package com.mzerp.learning.grading;

import java.util.List;
import java.util.Map;

import com.mzerp.journal.JournalService;
import com.mzerp.learning.course.Course;

import tools.jackson.databind.JsonNode;

/**
 * 미션 종류별 채점 전략 (ADR-0017).
 * 각 구현체는 자신이 담당하는 {@link Course.MissionKind}의 제출을 채점해 반려 사유 목록을 돌려준다.
 * 비어 있으면 통과.
 */
public interface MissionGrader {

    /** 이 채점기가 담당하는 미션 종류 */
    Course.MissionKind kind();

    /** @return 비어 있으면 통과 */
    List<Grader.Finding> grade(Context context);

    /**
     * 채점에 필요한 재료 묶음.
     * 전표 기반 미션(entry·correction)은 actual·drafts·accounts를 쓰고,
     * 선택형·값 찾기(quiz·lookup)는 submittedAnswer를 쓴다.
     */
    record Context(Course.Mission mission,
                   Map<Balances.Key, Long> actual,
                   List<JournalService.EntryView> drafts,
                   Map<String, Grader.AccountInfo> accounts,
                   JsonNode submittedAnswer) {

        /** 잔액 변화만으로 전표 미션을 채점할 때 쓰는 도우미 (단위 테스트·콘텐츠 테스트용) */
        public static Context ofEntry(Course.Mission mission, Map<Balances.Key, Long> actual,
                                      Map<String, Grader.AccountInfo> accounts) {
            return new Context(mission, actual, List.of(), accounts, null);
        }
    }
}
