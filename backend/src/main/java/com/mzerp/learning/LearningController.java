package com.mzerp.learning;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import com.mzerp.identity.AppUserPrincipal;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 실습 API (docs/design/training-site.md §8). 회사 경로 밖에 있다 — 실습 회사는 과정을 시작할 때 만들어진다.
 * 로그인한 사용자 자신의 수강·시도만 다룰 수 있다 (남의 것은 404).
 * 전표를 쓰고 고치는 일은 이 API가 아니라 일반전표 API로 한다: 실습자는 실제 ERP를 쓴다.
 */
@RestController
@RequestMapping("/api/v1/learning")
public class LearningController {

    private final LearningService service;

    public LearningController(LearningService service) {
        this.service = service;
    }

    public record EnrollRequest(@NotBlank String courseKey) {
    }

    /** 과정 시작. 이미 시작했으면 그 수강을 돌려준다. */
    @PostMapping("/enrollments")
    public LearningService.EnrollmentView enroll(@AuthenticationPrincipal AppUserPrincipal user,
                                                 @Valid @RequestBody EnrollRequest request) {
        return service.enroll(user.userId(), request.courseKey());
    }

    /** 내 수강 목록 (아직 시작하지 않았으면 빈 목록) */
    @GetMapping("/enrollments")
    public java.util.List<LearningService.EnrollmentView> mine(@AuthenticationPrincipal AppUserPrincipal user) {
        return service.mine(user.userId());
    }

    @GetMapping("/enrollments/{enrollmentId}")
    public LearningService.EnrollmentView enrollment(@AuthenticationPrincipal AppUserPrincipal user,
                                                     @PathVariable Long enrollmentId) {
        return service.get(user.userId(), enrollmentId);
    }

    @PostMapping("/enrollments/{enrollmentId}/missions/{missionKey}/start")
    public LearningService.AttemptView start(@AuthenticationPrincipal AppUserPrincipal user,
                                             @PathVariable Long enrollmentId, @PathVariable String missionKey) {
        return service.start(user.userId(), enrollmentId, missionKey);
    }

    /** 끝낸 미션 다시 보기: 정답 분개, 해설, 내 기록. 끝내기 전에는 409 (정답이 들어 있다) */
    @GetMapping("/enrollments/{enrollmentId}/missions/{missionKey}/review")
    public LearningService.ReviewView review(@AuthenticationPrincipal AppUserPrincipal user,
                                             @PathVariable Long enrollmentId, @PathVariable String missionKey) {
        return service.review(user.userId(), enrollmentId, missionKey);
    }

    /** 끝낸 미션 다시 풀기 (연습): 채점만 하고 장부에는 반영하지 않는다 */
    @PostMapping("/enrollments/{enrollmentId}/missions/{missionKey}/retry")
    public LearningService.AttemptView retry(@AuthenticationPrincipal AppUserPrincipal user,
                                             @PathVariable Long enrollmentId, @PathVariable String missionKey) {
        return service.retry(user.userId(), enrollmentId, missionKey);
    }

    @GetMapping("/attempts/{attemptId}")
    public LearningService.AttemptView attempt(@AuthenticationPrincipal AppUserPrincipal user, @PathVariable Long attemptId) {
        return service.attempt(user.userId(), attemptId);
    }

    /** 결재 요청: 채점해서 승인(전기)하거나 반려 사유를 돌려준다. */
    @PostMapping("/attempts/{attemptId}/submit")
    public LearningService.SubmitResult submit(@AuthenticationPrincipal AppUserPrincipal user, @PathVariable Long attemptId) {
        return service.submit(user.userId(), attemptId);
    }

    @PostMapping("/attempts/{attemptId}/hint")
    public LearningService.AttemptView hint(@AuthenticationPrincipal AppUserPrincipal user, @PathVariable Long attemptId) {
        return service.hint(user.userId(), attemptId);
    }

    /** 정답 보고 넘어가기 */
    @PostMapping("/attempts/{attemptId}/reveal")
    public LearningService.AttemptView reveal(@AuthenticationPrincipal AppUserPrincipal user, @PathVariable Long attemptId) {
        return service.reveal(user.userId(), attemptId);
    }
}
