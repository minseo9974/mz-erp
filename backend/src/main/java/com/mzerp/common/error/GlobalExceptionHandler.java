package com.mzerp.common.error;

import java.net.URI;
import java.util.List;
import java.util.Map;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * 모든 오류를 RFC 7807 Problem Details + 업무 오류 코드로 응답한다.
 * <pre>{ "type": "urn:mzerp:error:DUPLICATE_CODE", "title": "...", "status": 409, "code": "DUPLICATE_CODE", "detail": "..." }</pre>
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ProblemDetail> handleApi(ApiException e) {
        return respond(e.code(), e.getMessage(), null);
    }

    @ExceptionHandler(AuthenticationException.class)
    ResponseEntity<ProblemDetail> handleAuthentication(AuthenticationException e) {
        return respond(ErrorCode.LOGIN_FAILED, null, null);
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ProblemDetail> handleAccessDenied(AccessDeniedException e) {
        return respond(ErrorCode.FORBIDDEN_ROLE, null, null);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ProblemDetail> handleIntegrity(DataIntegrityViolationException e) {
        // 서비스에서 먼저 검사하지만, 동시에 같은 코드를 등록하는 경우 DB 제약이 마지막 방어선이 된다
        return respond(ErrorCode.DATA_CONFLICT, null, null);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<Map<String, String>> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(f -> Map.of("field", f.getField(), "message", String.valueOf(f.getDefaultMessage())))
                .toList();
        ProblemDetail body = problem(ErrorCode.VALIDATION_FAILED, null, errors);
        return ResponseEntity.status(body.getStatus()).body(body);
    }

    /** JSON 문법 오류, 타입 불일치(예: 금액에 문자열), 없는 enum 값 등. */
    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail body = problem(ErrorCode.VALIDATION_FAILED, "요청 본문을 읽을 수 없습니다 (JSON 형식·값 타입 확인)", null);
        return ResponseEntity.status(body.getStatus()).body(body);
    }

    private static ResponseEntity<ProblemDetail> respond(ErrorCode code, String detail, List<?> errors) {
        ProblemDetail body = problem(code, detail, errors);
        return ResponseEntity.status(code.status()).body(body);
    }

    private static ProblemDetail problem(ErrorCode code, String detail, List<?> errors) {
        ProblemDetail pd = ProblemDetail.forStatus(code.status());
        pd.setType(URI.create("urn:mzerp:error:" + code.name()));
        pd.setTitle(code.title());
        pd.setDetail(detail);
        pd.setProperty("code", code.name());
        if (errors != null && !errors.isEmpty()) {
            pd.setProperty("errors", errors);
        }
        return pd;
    }
}
