package com.mzerp.learning.grading;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.mzerp.learning.course.Course;
import com.mzerp.learning.scenario.Scenario;

import org.springframework.stereotype.Component;

/**
 * {@link Course.MissionKind#ENTRY} 미션 채점기 (ADR-0013, ADR-0017).
 * <ol>
 *   <li>정답 분개 또는 대안 분개(accept)와 잔액 변화가 같으면 일자를 확인해 통과/반려한다</li>
 *   <li>틀렸을 때는 미션의 오답 예시(mistakes)를 먼저 비교해 일치하면 {@link Grader.FindingType#MISTAKE}로 전용 사유를 돌려준다</li>
 *   <li>오답 예시에 해당하지 않으면 범용 잔액 변화 진단({@link Grader#grade})을 쓴다</li>
 * </ol>
 */
@Component
public class EntryGrader implements MissionGrader {

    @Override
    public Course.MissionKind kind() {
        return Course.MissionKind.ENTRY;
    }

    @Override
    public List<Grader.Finding> grade(Context context) {
        Course.Mission mission = context.mission();
        Map<Balances.Key, Long> actual = context.actual();
        Map<Balances.Key, Long> expected = Balances.ofLines(mission.answer().lines());

        boolean matched = Grader.matches(expected, actual);
        if (!matched) {
            for (List<Scenario.Line> alternative : mission.accept()) {
                if (Grader.matches(Balances.ofLines(alternative), actual)) {
                    matched = true;
                    break;
                }
            }
        }
        if (matched) {
            List<Grader.Finding> dateFindings = new ArrayList<>();
            // 금액과 계정이 맞아도 전표 일자가 거래일과 다르면 장부의 기간이 틀어진다
            context.drafts().stream()
                    .filter(d -> !d.entryDate().equals(mission.date()))
                    .findFirst()
                    .ifPresent(d -> dateFindings.add(new Grader.Finding(Grader.FindingType.WRONG_DATE,
                            "전표 일자가 " + d.entryDate() + "로 되어 있어요. 전표 일자는 작성한 날이 아니라 거래가 일어난 날이에요.")));
            return dateFindings;
        }

        for (Course.Mistake mistake : mission.mistakes()) {
            if (Grader.matchesMistake(expected, Balances.ofLines(mistake.lines()), actual)) {
                return List.of(new Grader.Finding(Grader.FindingType.MISTAKE, mistake.says()));
            }
        }
        return new ArrayList<>(Grader.grade(expected, actual, context.accounts()));
    }
}
