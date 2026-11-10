package com.mzerp.master.company;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import com.mzerp.identity.AppUserPrincipal;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class CompanyController {

    private final CompanyService service;

    public CompanyController(CompanyService service) {
        this.service = service;
    }

    public record CreateCompanyRequest(@NotBlank @Size(max = 100) String name,
                                       @Size(max = 20) String bizRegNo,
                                       @Size(max = 100) String industry) {
    }

    public record CompanyResponse(Long companyId, String name, String bizRegNo, String industry) {
        static CompanyResponse of(Company c) {
            return new CompanyResponse(c.getId(), c.getName(), c.getBizRegNo(), c.getIndustry());
        }
    }

    /** 로그인한 사용자라면 누구나 회사를 만들 수 있고, 만든 회사의 ADMIN이 된다. */
    @PostMapping("/companies")
    @ResponseStatus(HttpStatus.CREATED)
    public CompanyResponse create(@AuthenticationPrincipal AppUserPrincipal user,
                                  @Valid @RequestBody CreateCompanyRequest request) {
        Company company = service.create(user.userId(),
                new CompanyService.CreateCommand(request.name(), request.bizRegNo(), request.industry()));
        return CompanyResponse.of(company);
    }

    /** 내 정보와 접근할 수 있는 회사·역할 목록. */
    @GetMapping("/me")
    public CompanyService.Me me(@AuthenticationPrincipal AppUserPrincipal user) {
        return service.me(user.userId());
    }
}
