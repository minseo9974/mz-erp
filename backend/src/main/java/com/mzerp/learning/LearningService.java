package com.mzerp.learning;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import com.mzerp.learning.grading.MissionGrader;
import com.mzerp.learning.scenario.Scenario;
import com.mzerp.learning.scenario.ScenarioReplayer;
import com.mzerp.ledger.BalanceMapper;
import com.mzerp.master.account.AccountRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 실습의 유스케이스: 과정 시작 · 미션 시작 · 결재 요청(채점) · 힌트 · 정답 대신 처리 · 다시 보기 · 다시 풀기.
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
    private static final TypeReference<List<Grader.Finding>> FINDINGS_TYPE = new TypeReference<>() {
    };

    private final EnrollmentRepository enrollments;
    private final SandboxRunRepository runs;
    private final MissionAttemptRepository attempts;
    private final AttemptSubmissionRepository submissions;
    private final CourseCatalog catalog;
    private final SandboxFactory sandboxes;
    private final ScenarioReplayer replayer;
    private final JournalService journal;
    private final BalanceMapper balances;
    private final AccountRepository accounts;
    private final Map<Course.MissionKind, MissionGrader> graders;

    public LearningService(EnrollmentRepository enrollments, SandboxRunRepository runs,
                           MissionAttemptRepository attempts, AttemptSubmissionRepository submissions,
                           CourseCatalog catalog, SandboxFactory sandboxes, ScenarioReplayer replayer,
                           JournalService journal, BalanceMapper balances, AccountRepository accounts,
                           List<MissionGrader> graders) {
        this.enrollments = enrollments;
        this.runs = runs;
        this.attempts = attempts;
        this.submissions = submissions;
        this.catalog = catalog;
        this.sandboxes = sandboxes;
        this.replayer = replayer;
        this.journal = journal;
        this.balances = balances;
        this.accounts = accounts;
        this.graders = graders.stream().collect(Collectors.toMap(MissionGrader::kind, Function.identity()));
    }

    // ------------------------------------------------------------------ 응답 모델

    /**
     * @param kind              미션 종류 (ENTRY · QUIZ · LOOKUP · CORRECTION …)
     * @param status            NOT_STARTED · IN_PROGRESS · PASSED · REVEALED (본 시도의 상태)
     * @param attemptId         시작한 적이 있으면 본 시도의 id
     * @param practiceAttemptId 지금 다시 풀고 있으면 그 연습 시도의 id
     */
    public record MissionView(String key, String kind, String title, String brief, LocalDate date, String status, Long attemptId,
                              Long practiceAttemptId) {
    }

    /**
     * @param canRestart 그 장의 첫 미션을 시작한 적이 있어 "이 장부터 다시"가 가능한가 (ADR-0019 §1)
     */
    public record ChapterView(String key, String title, boolean canRestart, List<MissionView> missions) {
    }

    public record EnrollmentView(Long id, Long companyId, String courseKey, String courseTitle, List<ChapterView> chapters) {
    }

    /**
     * @param kind     미션 종류 (ENTRY · QUIZ · LOOKUP · CORRECTION …)
     * @param practice 끝낸 미션을 다시 푸는 연습인가
     * @param evidence 증빙 (통장 거래내역, 영수증, 어음)
     * @param hints    지금까지 연 힌트
     */
    public record AttemptView(Long id, String missionKey, String kind, String title, String brief, LocalDate date, String status,
                              boolean practice, List<Course.Evidence> evidence, List<String> hints, int hintsRemaining,
                              int submissions) {
    }

    /** 정답 분개의 한 줄. 코드만으로는 읽기 어려우니 이름을 함께 준다 */
    public record AnswerLine(String side, String accountCode, String accountName, String partnerName, long amount) {
    }

    /**
     * 끝낸 미션 다시 보기.
     *
     * @param kind        미션 종류 (ENTRY · QUIZ · LOOKUP · CORRECTION …)
     * @param status      PASSED · REVEALED
     * @param submissions 본 시도에서 결재를 요청한 횟수
     * @param rejections  본 시도에서 반려될 때마다 받은 사유 (요청 순서대로)
     * @param practices   다시 풀기를 끝낸 횟수
     * @param practiceAttemptId 지금 다시 풀고 있으면 그 연습 시도의 id
     */
    public record ReviewView(String key, String kind, String title, String chapterTitle, String brief, LocalDate date, String status,
                             Instant finishedAt, List<Course.Evidence> evidence, List<AnswerLine> answer,
                             String explanation, List<String> hints,
                             int hintsUsed, int submissions, List<List<String>> rejections, int practices,
                             Long practiceAttemptId) {
    }

    public record SubmitResult(boolean passed, List<Grader.Finding> findings, AttemptView attempt) {
    }

    // ------------------------------------------------------------------ 과정

    /** 과정을 시작한다: 수강과 첫 회차(실습 회사)를 만든다. 이미 시작했으면 그 수강을 돌려준다. */
    @Transactional
    public EnrollmentView enroll(Long userId, String courseKey) {
        Course course = catalog.get(courseKey);
        Enrollment enrollment = enrollments.findByUserIdAndCourseKey(userId, courseKey).orElseGet(() -> {
            Enrollment saved = enrollments.save(new Enrollment(userId, course.key(), course.version()));
            Long companyId = sandboxes.create(userId, course.scenario());
            runs.save(new SandboxRun(saved.getId(), companyId, null, course.version()));
            return saved;
        });
        return view(enrollment, course);
    }

    /** 내 수강 목록. 화면이 "과정을 시작했는가"를 알기 위해 쓴다 (과정 시작은 회사를 만들므로 조회와 분리). */
    @Transactional(readOnly = true)
    public List<EnrollmentView> mine(Long userId) {
        return enrollments.findByUserIdOrderById(userId).stream()
                .map(e -> view(e, catalog.get(e.getCourseKey())))
                .toList();
    }

    @Transactional(readOnly = true)
    public EnrollmentView get(Long userId, Long enrollmentId) {
        Enrollment enrollment = load(userId, enrollmentId);
        return view(enrollment, catalog.get(enrollment.getCourseKey()));
    }

    /**
     * 이 장부터 다시 시작한다 (ADR-0019 §1).
     * 그 장의 첫 미션을 시작한 적이 있는 장까지만 허용한다(건너뛰기 금지).
     * 진행 중이던 시도와 현재 회차는 ABANDONED로 남기고, 그 장보다 앞 장 미션의 정답 거래를 날짜순으로 재생한 새 회사를 만든다.
     */
    @Transactional
    public EnrollmentView restart(Long userId, Long enrollmentId, String chapterKey) {
        Enrollment enrollment = load(userId, enrollmentId);
        Course course = catalog.get(enrollment.getCourseKey());
        Course.Chapter target = chapter(course, chapterKey);
        if (target.missions().isEmpty()
                || !attempts.existsByEnrollmentIdAndMissionKeyAndPracticeFalse(enrollmentId, target.missions().getFirst().key())) {
            throw new ApiException(ErrorCode.CHAPTER_NOT_STARTED, "'" + target.title() + "' 장은 아직 시작하지 않았어요");
        }

        // 진행 중이던 시도는 ABANDONED로 끝낸다 (옛 회사의 임시 전표는 지우지 않고 그대로 둔다)
        attempts.findByEnrollmentIdAndStatus(enrollmentId, MissionAttempt.Status.IN_PROGRESS)
                .ifPresent(MissionAttempt::abandon);

        SandboxRun current = activeRun(enrollmentId);
        current.abandon();
        // 수강당 ACTIVE 회차 하나(ux_sandbox_run_one_active)를 지키기 위해 새 회차 삽입 전에 먼저 반영한다
        runs.flush();

        List<Integer> checkpointTransactions = course.chapters().stream()
                .takeWhile(c -> !c.key().equals(target.key()))
                .flatMap(c -> c.missions().stream())
                .filter(m -> m.answer() != null)
                .map(m -> m.answer().no())
                .toList();
        Long newCompanyId = sandboxes.createCheckpoint(userId, course.scenario(), checkpointTransactions);
        runs.save(new SandboxRun(enrollmentId, newCompanyId, target.key(), course.version()));
        return view(enrollment, course);
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
        Course.Mission mission = mission(course, missionKey);
        SandboxRun run = activeRun(enrollmentId);

        if (isBeforeStartChapter(course, run, mission.chapterKey())) {
            throw new ApiException(ErrorCode.MISSION_ALREADY_FINISHED, "'" + mission.title() + "' 미션은 이미 끝냈어요");
        }
        var existing = attempts.findByRunIdAndMissionKeyAndPracticeFalse(run.getId(), mission.key());
        if (existing.isPresent()) {
            existing.get().requireInProgress(); // 끝난 미션은 다시 시작하지 않는다 — 다시 풀기는 retry (연습)
            return view(existing.get(), mission);
        }
        requireNothingInProgress(enrollment, course);
        Set<String> done = attempts.findByRunId(run.getId()).stream()
                .filter(a -> !a.isPractice() && a.isCompleted())
                .map(MissionAttempt::getMissionKey)
                .collect(Collectors.toSet());
        course.missions().stream()
                .takeWhile(m -> !m.key().equals(mission.key()))
                .filter(m -> !isBeforeStartChapter(course, run, m.chapterKey()) && !done.contains(m.key()))
                .findFirst()
                .ifPresent(previous -> {
                    throw new ApiException(ErrorCode.MISSION_LOCKED, "'" + previous.title() + "' 미션을 먼저 끝내세요");
                });
        requireNoDrafts(run, course);
        return view(attempts.save(newAttempt(enrollment, run, mission, false)), mission);
    }

    /**
     * 끝낸 미션을 다시 푼다 (연습).
     * <p>
     * 그 거래는 이미 장부에 전기되어 있다. 다시 푼 전표까지 전기하면 같은 거래가 두 번 기록되므로, 연습은 채점만 하고
     * 통과하든 그만두든 연습으로 쓴 임시 전표는 지운다 — 장부는 연습 전과 같다.
     * 채점 방식은 본 시도와 같다: 지금 잔액을 저장해 두고, 임시 전표가 만들 변화를 정답과 비교한다.
     */
    @Transactional
    public AttemptView retry(Long userId, Long enrollmentId, String missionKey) {
        Enrollment enrollment = load(userId, enrollmentId);
        Course course = catalog.get(enrollment.getCourseKey());
        Course.Mission mission = mission(course, missionKey);
        SandboxRun run = activeRun(enrollmentId);
        finishedOriginal(enrollment, run, course, mission);

        var practicing = attempts.findByRunIdAndMissionKeyOrderById(run.getId(), mission.key()).stream()
                .filter(a -> a.isPractice() && a.isInProgress())
                .findFirst();
        if (practicing.isPresent()) {
            return view(practicing.get(), mission);
        }
        requireNothingInProgress(enrollment, course);
        requireNoDrafts(run, course);
        return view(attempts.save(newAttempt(enrollment, run, mission, true)), mission);
    }

    /**
     * 끝낸 미션 다시 보기: 정답 분개, 해설, 내가 반려됐던 사유.
     * 정답이 들어 있으므로 본 시도를 끝낸(통과했거나 정답을 보고 넘어간) 뒤에만 볼 수 있다.
     */
    @Transactional(readOnly = true)
    public ReviewView review(Long userId, Long enrollmentId, String missionKey) {
        Enrollment enrollment = load(userId, enrollmentId);
        Course course = catalog.get(enrollment.getCourseKey());
        Course.Mission mission = mission(course, missionKey);
        SandboxRun run = activeRun(enrollmentId);
        MissionAttempt original = finishedOriginal(enrollment, run, course, mission);

        Map<String, String> accountNames = accounts.findByCompanyIdOrderByCode(run.getCompanyId()).stream()
                .collect(Collectors.toMap(a -> a.getCode(), a -> a.getName()));
        Map<String, String> partnerNames = course.scenario().partners().stream()
                .collect(Collectors.toMap(Scenario.PartnerSeed::code, Scenario.PartnerSeed::name));
        List<AnswerLine> answer = mission.answer().lines().stream()
                .map(l -> new AnswerLine(l.side().name(), l.accountCode(), accountNames.getOrDefault(l.accountCode(), l.accountCode()),
                        l.partnerCode() == null ? null : partnerNames.getOrDefault(l.partnerCode(), l.partnerCode()), l.amount()))
                .toList();
        List<List<String>> rejections = submissions.findByAttemptIdOrderById(original.getId()).stream()
                .filter(s -> !s.isPassed())
                .map(s -> JSON.readValue(s.getFindings(), FINDINGS_TYPE).stream().map(Grader.Finding::message).toList())
                .toList();
        List<MissionAttempt> practices = attempts.findByEnrollmentIdAndMissionKeyOrderById(enrollmentId, mission.key()).stream()
                .filter(MissionAttempt::isPractice)
                .toList();
        String chapterTitle = course.chapters().stream().filter(c -> c.key().equals(mission.chapterKey()))
                .map(Course.Chapter::title).findFirst().orElse("");

        return new ReviewView(mission.key(), mission.kind().name(), mission.title(), chapterTitle, mission.brief(), mission.date(),
                original.getStatus().name(), original.getFinishedAt(), mission.evidence(), answer,
                mission.explanation(), mission.hints(),
                Math.min(original.getHintsUsed(), mission.hints().size()), original.getSubmissions(), rejections,
                (int) practices.stream().filter(MissionAttempt::isCompleted).count(),
                practices.stream().filter(MissionAttempt::isInProgress).map(MissionAttempt::getId).findFirst().orElse(null));
    }

    @Transactional(readOnly = true)
    public AttemptView attempt(Long userId, Long attemptId) {
        MissionAttempt attempt = loadAttempt(userId, attemptId);
        return view(attempt, mission(attempt));
    }

    /**
     * 결재 요청 = 채점 (ADR-0017). 미션 종류에 맞는 {@link MissionGrader} 전략으로 판정한다.
     * 통과하면 팀장이 임시 전표를 전기하고, 연습이면 전기하지 않고 그 전표를 지운다.
     *
     * @param submittedAnswer quiz·lookup의 제출 값 (전표 미션은 null)
     */
    @Transactional
    public SubmitResult submit(Long userId, Long attemptId, JsonNode submittedAnswer) {
        MissionAttempt attempt = loadAttempt(userId, attemptId);
        Course.Mission mission = mission(attempt);
        Long companyId = attempt.getCompanyId();
        attempt.countSubmission();

        MissionGrader grader = graders.get(mission.kind());
        if (grader == null) {
            throw new IllegalStateException("미션 종류 " + mission.kind() + "의 채점기가 없습니다");
        }

        List<JournalService.EntryView> drafts = drafts(companyId, course(attempt));
        // 전기된 잔액 + 임시 전표 = "결재가 나면 이렇게 된다"
        Map<Balances.Key, Long> pending = postedBalances(companyId);
        for (JournalService.EntryView draft : drafts) {
            draft.lines().forEach(l -> Balances.add(pending, l.accountCode(), l.partnerCode(), l.side(), l.amount()));
        }
        Map<Balances.Key, Long> actual = Balances.minus(pending, Balances.decode(JSON.readValue(attempt.getBaseline(), BASELINE_TYPE)));
        Map<String, Grader.AccountInfo> accountInfo = accounts.findByCompanyIdOrderByCode(companyId).stream()
                .map(a -> new Grader.AccountInfo(a.getCode(), a.getName(), a.getCategory().name(), a.getSubgroup()))
                .collect(Collectors.toMap(Grader.AccountInfo::code, Function.identity()));

        List<Grader.Finding> findings = grader.grade(
                new MissionGrader.Context(mission, actual, drafts, accountInfo, submittedAnswer));

        boolean passed = findings.isEmpty();
        String answerJson = submittedAnswer == null || submittedAnswer.isNull()
                ? null : JSON.writeValueAsString(submittedAnswer);
        submissions.save(new AttemptSubmission(attempt.getId(), companyId, passed, JSON.writeValueAsString(findings), answerJson));
        if (passed) {
            if (attempt.isPractice()) {
                // 이 거래는 이미 장부에 있다. 또 전기하면 두 번 기록된다
                drafts.forEach(d -> journal.delete(companyId, d.id()));
            } else {
                Long teamLead = sandboxes.teamLeadId();
                drafts.forEach(d -> journal.post(companyId, d.id(), teamLead));
            }
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
     * 연습에서는 "그만두기"다: 임시 전표만 지운다 (정답 전표는 이미 장부에 있다).
     */
    @Transactional
    public AttemptView reveal(Long userId, Long attemptId) {
        MissionAttempt attempt = loadAttempt(userId, attemptId);
        attempt.requireInProgress();
        Course.Mission mission = mission(attempt);
        Course course = course(attempt);
        Long companyId = attempt.getCompanyId();

        drafts(companyId, course).forEach(d -> journal.delete(companyId, d.id()));
        if (!attempt.isPractice()) {
            replayer.replayTransaction(companyId, sandboxes.teamLeadId(), course.scenario(), mission.answer().no());
        }
        attempt.finish(MissionAttempt.Status.REVEALED);
        return view(attempt, mission);
    }

    // ------------------------------------------------------------------ 내부

    /** 한 번에 하나만 — 본 시도든 연습이든, 두 미션의 장부 변화가 섞이면 어느 것인지 가를 수 없다 */
    private void requireNothingInProgress(Enrollment enrollment, Course course) {
        attempts.findByEnrollmentIdAndStatus(enrollment.getId(), MissionAttempt.Status.IN_PROGRESS).ifPresent(other -> {
            throw new ApiException(ErrorCode.MISSION_IN_PROGRESS,
                    "'" + mission(course, other.getMissionKey()).title() + "' 미션이 진행 중입니다");
        });
    }

    private void requireNoDrafts(SandboxRun run, Course course) {
        List<JournalService.EntryView> leftover = drafts(run.getCompanyId(), course);
        if (!leftover.isEmpty()) {
            throw new ApiException(ErrorCode.MISSION_DRAFTS_LEFT, "임시 전표 " + leftover.size() + "장("
                    + leftover.stream().map(d -> d.entryDate() + " " + d.entryNo()).collect(Collectors.joining(", "))
                    + ")이 남아 있어요. 일반전표 입력 화면에서 지운 뒤 미션을 시작하세요");
        }
    }

    private MissionAttempt newAttempt(Enrollment enrollment, SandboxRun run, Course.Mission mission, boolean practice) {
        String baseline = JSON.writeValueAsString(Balances.encode(postedBalances(run.getCompanyId())));
        return new MissionAttempt(enrollment.getId(), run.getId(), run.getCompanyId(), mission.key(), baseline, practice);
    }

    /** 끝낸 본 시도. 시작 장보다 앞 장은 이전 회차에서 마지막으로 끝낸 본 시도를, 그 밖에는 현재 회차의 본 시도를 찾는다. */
    private MissionAttempt finishedOriginal(Enrollment enrollment, SandboxRun run, Course course, Course.Mission mission) {
        var found = isBeforeStartChapter(course, run, mission.chapterKey())
                ? attempts.findByEnrollmentIdAndMissionKeyAndPracticeFalseOrderByIdDesc(enrollment.getId(), mission.key())
                        .stream().filter(MissionAttempt::isCompleted).findFirst()
                : attempts.findByRunIdAndMissionKeyAndPracticeFalse(run.getId(), mission.key())
                        .filter(MissionAttempt::isCompleted);
        return found.orElseThrow(() -> new ApiException(ErrorCode.MISSION_NOT_FINISHED,
                "'" + mission.title() + "' 미션을 끝낸 뒤에 볼 수 있어요"));
    }

    private SandboxRun activeRun(Long enrollmentId) {
        return runs.findByEnrollmentIdAndStatus(enrollmentId, SandboxRun.Status.ACTIVE)
                .orElseThrow(() -> new IllegalStateException("수강 " + enrollmentId + "에 진행 중인 회차가 없습니다"));
    }

    private static Course.Chapter chapter(Course course, String chapterKey) {
        return course.chapters().stream().filter(c -> c.key().equals(chapterKey)).findFirst()
                .orElseThrow(() -> ApiException.notFound("장 " + chapterKey));
    }

    /** 회차의 시작 장보다 앞의 장인가. startChapter가 null이면 과정 처음부터 시작이므로 앞 장이 없다. */
    private static boolean isBeforeStartChapter(Course course, SandboxRun run, String chapterKey) {
        if (run.getStartChapter() == null) {
            return false;
        }
        int startIndex = chapterIndex(course, run.getStartChapter());
        int targetIndex = chapterIndex(course, chapterKey);
        return startIndex >= 0 && targetIndex >= 0 && targetIndex < startIndex;
    }

    private static int chapterIndex(Course course, String chapterKey) {
        for (int i = 0; i < course.chapters().size(); i++) {
            if (course.chapters().get(i).key().equals(chapterKey)) {
                return i;
            }
        }
        return -1;
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

    private static Course.Mission mission(Course course, String missionKey) {
        return course.missions().stream().filter(m -> m.key().equals(missionKey)).findFirst()
                .orElseThrow(() -> ApiException.notFound("미션 " + missionKey));
    }

    private Course.Mission mission(MissionAttempt attempt) {
        return mission(course(attempt), attempt.getMissionKey());
    }

    private Course course(MissionAttempt attempt) {
        return catalog.get(enrollments.findById(attempt.getEnrollmentId()).orElseThrow().getCourseKey());
    }

    private EnrollmentView view(Enrollment enrollment, Course course) {
        SandboxRun run = activeRun(enrollment.getId());
        List<MissionAttempt> all = attempts.findByEnrollmentIdOrderById(enrollment.getId());
        // 현재 회차의 본 시도
        Map<String, MissionAttempt> inActiveRun = all.stream()
                .filter(a -> a.getRunId().equals(run.getId()) && !a.isPractice())
                .collect(Collectors.toMap(MissionAttempt::getMissionKey, Function.identity()));
        // 시작 장보다 앞 장의 마지막 완료 본 시도 (id 오름차순이므로 나중 것이 덮어쓴다)
        Map<String, MissionAttempt> lastCompleted = all.stream()
                .filter(a -> !a.isPractice() && a.isCompleted())
                .collect(Collectors.toMap(MissionAttempt::getMissionKey, Function.identity(), (older, newer) -> newer));
        // 현재 진행 중인 연습 시도 (수강 단위 하나뿐, 현재 회차의 것)
        Map<String, Long> practicing = all.stream()
                .filter(a -> a.getRunId().equals(run.getId()) && a.isPractice() && a.isInProgress())
                .collect(Collectors.toMap(MissionAttempt::getMissionKey, MissionAttempt::getId));
        // 어느 회차에서든 본 시도를 시작한 적이 있는 미션
        Set<String> everStarted = all.stream()
                .filter(a -> !a.isPractice())
                .map(MissionAttempt::getMissionKey)
                .collect(Collectors.toSet());

        List<ChapterView> chapters = course.chapters().stream()
                .map(c -> {
                    boolean beforeStart = isBeforeStartChapter(course, run, c.key());
                    boolean canRestart = !c.missions().isEmpty() && everStarted.contains(c.missions().getFirst().key());
                    List<MissionView> missionViews = c.missions().stream()
                            .map(m -> {
                                MissionAttempt attempt = beforeStart ? lastCompleted.get(m.key()) : inActiveRun.get(m.key());
                                return new MissionView(m.key(), m.kind().name(), m.title(), m.brief(), m.date(),
                                        attempt == null ? "NOT_STARTED" : attempt.getStatus().name(),
                                        attempt == null ? null : attempt.getId(), practicing.get(m.key()));
                            })
                            .toList();
                    return new ChapterView(c.key(), c.title(), canRestart, missionViews);
                })
                .toList();
        return new EnrollmentView(enrollment.getId(), run.getCompanyId(), course.key(), course.title(), chapters);
    }

    private static AttemptView view(MissionAttempt attempt, Course.Mission mission) {
        int shown = Math.min(attempt.getHintsUsed(), mission.hints().size());
        return new AttemptView(attempt.getId(), mission.key(), mission.kind().name(), mission.title(), mission.brief(), mission.date(),
                attempt.getStatus().name(), attempt.isPractice(), mission.evidence(), mission.hints().subList(0, shown),
                mission.hints().size() - shown,
                attempt.getSubmissions());
    }
}
