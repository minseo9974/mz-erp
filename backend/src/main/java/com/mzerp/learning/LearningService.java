package com.mzerp.learning;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.mzerp.common.error.ApiException;
import com.mzerp.common.error.ErrorCode;
import com.mzerp.journal.EntryStatus;
import com.mzerp.journal.JournalService;
import com.mzerp.learning.course.Course;
import com.mzerp.learning.course.CourseCatalog;
import com.mzerp.learning.grading.Balances;
import com.mzerp.learning.grading.Grader;
import com.mzerp.learning.scenario.Scenario;
import com.mzerp.learning.scenario.ScenarioReplayer;
import com.mzerp.ledger.BalanceMapper;
import com.mzerp.master.account.AccountRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * 실습의 유스케이스: 과정 시작 · 미션 시작 · 결재 요청(채점) · 힌트 · 정답 대신 처리.
 * <p>
 * 미션 하나의 흐름 (ADR-0013):
 * <ol>
 *   <li>시작 — 지금 장부 잔액을 저장해 둔다 (baseline)</li>
 *   <li>실습자가 ERP 화면에서 전표를 작성해 임시 저장한다 (이 클래스는 관여하지 않는다)</li>
 *   <li>결재 요청 — "임시 전표를 전기했다고 치면" 장부가 얼마나 달라지는지 계산해 기대한 변화와 비교한다</li>
 *   <li>맞으면 팀장(시스템 계정)이 그 전표를 전기하고 미션이 끝난다. 틀리면 반려 사유를 돌려주고 전표는 임시로 남는다</li>
 * </ol>
 * ERP에는 화면과 같은 공개 서비스로만 접근한다 (ADR-0012).
 */
@Service
public class LearningService {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<Map<String, Long>> BASELINE_TYPE = new TypeReference<>() {
    };

    private final EnrollmentRepository enrollments;
    private final MissionAttemptRepository attempts;
    private final AttemptSubmissionRepository submissions;
    private final CourseCatalog catalog;
    private final SandboxFactory sandboxes;
    private final ScenarioReplayer replayer;
    private final JournalService journal;
    private final BalanceMapper balances;
    private final AccountRepository accounts;

    public LearningService(EnrollmentRepository enrollments, MissionAttemptRepository attempts,
                           AttemptSubmissionRepository submissions, CourseCatalog catalog, SandboxFactory sandboxes,
                           ScenarioReplayer replayer, JournalService journal, BalanceMapper balances,
                           AccountRepository accounts) {
        this.enrollments = enrollments;
        this.attempts = attempts;
        this.submissions = submissions;
        this.catalog = catalog;
        this.sandboxes = sandboxes;
        this.replayer = replayer;
        this.journal = journal;
        this.balances = balances;
        this.accounts = accounts;
    }

    // ------------------------------------------------------------------ 응답 모델

    /** @param status NOT_STARTED · IN_PROGRESS · PASSED · REVEALED */
    public record MissionView(String key, String title, String status) {
    }

    public record ChapterView(String key, String title, List<MissionView> missions) {
    }

    public record EnrollmentView(Long id, Long companyId, String courseKey, String courseTitle, List<ChapterView> chapters) {
    }

    /** @param hints 지금까지 연 힌트 */
    public record AttemptView(Long id, String missionKey, String title, String brief, LocalDate date, String status,
                              List<String> hints, int hintsRemaining, int submissions) {
    }

    public record SubmitResult(boolean passed, List<Grader.Finding> findings, AttemptView attempt) {
    }

    // ------------------------------------------------------------------ 과정

    /** 과정을 시작한다: 실습 회사를 만들고 수강을 기록한다. 이미 시작했으면 그 수강을 돌려준다. */
    @Transactional
    public EnrollmentView enroll(Long userId, String courseKey) {
        Course course = catalog.get(courseKey);
        Enrollment enrollment = enrollments.findByUserIdAndCourseKey(userId, courseKey).orElseGet(() -> {
            Long companyId = sandboxes.create(userId, course.scenario());
            return enrollments.save(new Enrollment(userId, companyId, course.key(), course.version()));
        });
        return view(enrollment, course);
    }

    @Transactional(readOnly = true)
    public EnrollmentView get(Long userId, Long enrollmentId) {
        Enrollment enrollment = load(userId, enrollmentId);
        return view(enrollment, catalog.get(enrollment.getCourseKey()));
    }

    // ------------------------------------------------------------------ 미션

    /**
     * 미션을 시작한다. 채점은 "시작 이후 장부의 변화"로 하므로, 그 변화에 다른 것이 섞이지 않게 세 가지를 확인한다.
     * <ol>
     *   <li>한 번에 하나만 — 두 미션의 변화가 섞이면 어느 미션의 것인지 가를 수 없다</li>
     *   <li>과정 순서대로 — 뒤 미션은 앞 미션이 장부에 남긴 결과(예: 외상 매출 → 그 대금 회수) 위에서 풀게 된다</li>
     *   <li>임시 전표가 남아 있지 않을 것 — 미션 밖에서 써 둔 전표가 이번 결재에 함께 올라가면 안 된다</li>
     * </ol>
     */
    @Transactional
    public AttemptView start(Long userId, Long enrollmentId, String missionKey) {
        Enrollment enrollment = load(userId, enrollmentId);
        Course course = catalog.get(enrollment.getCourseKey());
        Course.Mission mission = mission(enrollment, missionKey);

        var existing = attempts.findByEnrollmentIdAndMissionKey(enrollmentId, mission.key());
        if (existing.isPresent()) {
            existing.get().requireInProgress(); // 끝난 미션은 다시 시작하지 않는다 (다시 하기는 장 단위로)
            return view(existing.get(), mission);
        }
        attempts.findByEnrollmentIdAndStatus(enrollmentId, MissionAttempt.Status.IN_PROGRESS).ifPresent(other -> {
            throw new ApiException(ErrorCode.MISSION_IN_PROGRESS,
                    "'" + mission(enrollment, other.getMissionKey()).title() + "' 미션이 진행 중입니다");
        });
        Map<String, MissionAttempt.Status> done = attempts.findByEnrollmentId(enrollmentId).stream()
                .collect(Collectors.toMap(MissionAttempt::getMissionKey, MissionAttempt::getStatus));
        course.missions().stream()
                .takeWhile(m -> !m.key().equals(mission.key()))
                .filter(m -> !done.containsKey(m.key()))
                .findFirst()
                .ifPresent(previous -> {
                    throw new ApiException(ErrorCode.MISSION_LOCKED, "'" + previous.title() + "' 미션을 먼저 끝내세요");
                });
        List<JournalService.EntryView> leftover = drafts(enrollment.getCompanyId(), course);
        if (!leftover.isEmpty()) {
            throw new ApiException(ErrorCode.MISSION_DRAFTS_LEFT, "임시 전표 " + leftover.size() + "장("
                    + leftover.stream().map(d -> d.entryDate() + " " + d.entryNo()).collect(Collectors.joining(", "))
                    + ")이 남아 있어요. 일반전표 입력 화면에서 지운 뒤 미션을 시작하세요");
        }
        String baseline = JSON.writeValueAsString(Balances.encode(postedBalances(enrollment.getCompanyId())));
        return view(attempts.save(new MissionAttempt(enrollmentId, enrollment.getCompanyId(), mission.key(), baseline)), mission);
    }

    @Transactional(readOnly = true)
    public AttemptView attempt(Long userId, Long attemptId) {
        MissionAttempt attempt = loadAttempt(userId, attemptId);
        return view(attempt, mission(attempt));
    }

    /** 결재 요청 = 채점. 통과하면 팀장이 임시 전표를 전기한다. */
    @Transactional
    public SubmitResult submit(Long userId, Long attemptId) {
        MissionAttempt attempt = loadAttempt(userId, attemptId);
        Course.Mission mission = mission(attempt);
        Long companyId = attempt.getCompanyId();
        attempt.countSubmission();

        List<JournalService.EntryView> drafts = drafts(companyId, course(attempt));
        // 전기된 잔액 + 임시 전표 = "결재가 나면 이렇게 된다"
        Map<Balances.Key, Long> pending = postedBalances(companyId);
        for (JournalService.EntryView draft : drafts) {
            draft.lines().forEach(l -> Balances.add(pending, l.accountCode(), l.partnerCode(), l.side(), l.amount()));
        }
        Map<Balances.Key, Long> actual = Balances.minus(pending, Balances.decode(JSON.readValue(attempt.getBaseline(), BASELINE_TYPE)));

        List<Grader.Finding> findings = grade(mission, actual, companyId);
        if (findings.isEmpty()) {
            // 금액과 계정이 맞아도 전표 일자가 거래일과 다르면 장부의 기간이 틀어진다
            drafts.stream().filter(d -> !d.entryDate().equals(mission.date())).findFirst().ifPresent(d ->
                    findings.add(new Grader.Finding(Grader.FindingType.WRONG_DATE,
                            "전표 일자가 " + d.entryDate() + "로 되어 있어요. 전표 일자는 작성한 날이 아니라 거래가 일어난 날이에요.")));
        }

        boolean passed = findings.isEmpty();
        submissions.save(new AttemptSubmission(attempt.getId(), companyId, passed, JSON.writeValueAsString(findings)));
        if (passed) {
            Long teamLead = sandboxes.teamLeadId();
            drafts.forEach(d -> journal.post(companyId, d.id(), teamLead));
            attempt.finish(MissionAttempt.Status.PASSED);
        }
        return new SubmitResult(passed, findings, view(attempt, mission));
    }

    /** 다음 힌트를 연다. */
    @Transactional
    public AttemptView hint(Long userId, Long attemptId) {
        MissionAttempt attempt = loadAttempt(userId, attemptId);
        Course.Mission mission = mission(attempt);
        attempt.useHint(mission.hints().size());
        return view(attempt, mission);
    }

    /**
     * 정답 보고 넘어가기: 실습자가 쓰던 임시 전표를 지우고, 시스템이 정답 전표를 작성·전기한다.
     * 막혀서 그만두는 것보다 넘어가서 해설을 읽는 편이 낫다.
     */
    @Transactional
    public AttemptView reveal(Long userId, Long attemptId) {
        MissionAttempt attempt = loadAttempt(userId, attemptId);
        attempt.requireInProgress();
        Course.Mission mission = mission(attempt);
        Course course = course(attempt);
        Long companyId = attempt.getCompanyId();

        drafts(companyId, course).forEach(d -> journal.delete(companyId, d.id()));
        replayer.replayTransaction(companyId, sandboxes.teamLeadId(), course.scenario(), mission.answer().no());
        attempt.finish(MissionAttempt.Status.REVEALED);
        return view(attempt, mission);
    }

    // ------------------------------------------------------------------ 내부

    /** 정답 분개와 대안 분개 중 하나라도 맞으면 통과. 전부 틀리면 정답 분개 기준의 반려 사유를 돌려준다. */
    private List<Grader.Finding> grade(Course.Mission mission, Map<Balances.Key, Long> actual, Long companyId) {
        Map<String, Grader.AccountInfo> info = accounts.findByCompanyIdOrderByCode(companyId).stream()
                .map(a -> new Grader.AccountInfo(a.getCode(), a.getName(), a.getCategory().name(), a.getSubgroup()))
                .collect(Collectors.toMap(Grader.AccountInfo::code, Function.identity()));
        for (List<Scenario.Line> alternative : mission.accept()) {
            if (Grader.grade(expected(alternative), actual, info).isEmpty()) {
                return new ArrayList<>();
            }
        }
        return new ArrayList<>(Grader.grade(expected(mission.answer().lines()), actual, info));
    }

    /** 분개 라인 → 그 분개가 장부에 만드는 변화 */
    private static Map<Balances.Key, Long> expected(List<Scenario.Line> lines) {
        Map<Balances.Key, Long> net = new TreeMap<>();
        lines.forEach(l -> Balances.add(net, l.accountCode(), l.partnerCode(), l.side(), l.amount()));
        return net;
    }

    private Map<Balances.Key, Long> postedBalances(Long companyId) {
        Map<Balances.Key, Long> net = new TreeMap<>();
        for (BalanceMapper.Row row : balances.postedBalances(companyId)) {
            long value = row.debit() - row.credit();
            if (value != 0) {
                net.put(new Balances.Key(row.accountCode(), row.partnerCode()), value);
            }
        }
        return net;
    }

    /** 실습 회사의 임시 전표 전부. 실습 회사에는 실습자 한 명만 전표를 쓰고, 미션은 한 번에 하나라서 모두 이 미션의 것이다. */
    private List<JournalService.EntryView> drafts(Long companyId, Course course) {
        Scenario scenario = course.scenario();
        return journal.list(companyId, scenario.startDate(), scenario.endDate(), EntryStatus.DRAFT, null);
    }

    private Enrollment load(Long userId, Long enrollmentId) {
        // 남의 수강은 "없는 것"으로 답한다 (존재 여부를 알려 주지 않는다)
        return enrollments.findByIdAndUserId(enrollmentId, userId).orElseThrow(() -> ApiException.notFound("수강 " + enrollmentId));
    }

    private MissionAttempt loadAttempt(Long userId, Long attemptId) {
        MissionAttempt attempt = attempts.findById(attemptId).orElseThrow(() -> ApiException.notFound("미션 시도 " + attemptId));
        load(userId, attempt.getEnrollmentId());
        return attempt;
    }

    private Course.Mission mission(Enrollment enrollment, String missionKey) {
        Course course = catalog.get(enrollment.getCourseKey());
        return course.missions().stream().filter(m -> m.key().equals(missionKey)).findFirst()
                .orElseThrow(() -> ApiException.notFound("미션 " + missionKey));
    }

    private Course.Mission mission(MissionAttempt attempt) {
        return mission(enrollments.findById(attempt.getEnrollmentId()).orElseThrow(), attempt.getMissionKey());
    }

    private Course course(MissionAttempt attempt) {
        return catalog.get(enrollments.findById(attempt.getEnrollmentId()).orElseThrow().getCourseKey());
    }

    private EnrollmentView view(Enrollment enrollment, Course course) {
        Map<String, MissionAttempt.Status> status = attempts.findByEnrollmentId(enrollment.getId()).stream()
                .collect(Collectors.toMap(MissionAttempt::getMissionKey, MissionAttempt::getStatus));
        List<ChapterView> chapters = course.chapters().stream()
                .map(c -> new ChapterView(c.key(), c.title(), c.missions().stream()
                        .map(m -> new MissionView(m.key(), m.title(),
                                status.containsKey(m.key()) ? status.get(m.key()).name() : "NOT_STARTED"))
                        .toList()))
                .toList();
        return new EnrollmentView(enrollment.getId(), enrollment.getCompanyId(), course.key(), course.title(), chapters);
    }

    private static AttemptView view(MissionAttempt attempt, Course.Mission mission) {
        int shown = Math.min(attempt.getHintsUsed(), mission.hints().size());
        return new AttemptView(attempt.getId(), mission.key(), mission.title(), mission.brief(), mission.date(),
                attempt.getStatus().name(), mission.hints().subList(0, shown), mission.hints().size() - shown,
                attempt.getSubmissions());
    }
}
