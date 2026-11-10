package com.mzerp.master.partner;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/companies/{companyId}/partners")
public class PartnerController {

    private final PartnerService service;

    public PartnerController(PartnerService service) {
        this.service = service;
    }

    public record PartnerResponse(String code, String name, String bizRegNo, PartnerType partnerType, boolean active) {
        static PartnerResponse of(Partner p) {
            return new PartnerResponse(p.getCode(), p.getName(), p.getBizRegNo(), p.getPartnerType(), p.isActive());
        }
    }

    public record CreatePartnerRequest(
            @NotBlank @Pattern(regexp = "\\d{5}", message = "숫자 5자리") String code,
            @NotBlank @Size(max = 100) String name,
            @Pattern(regexp = "\\d{3}-\\d{2}-\\d{5}", message = "000-00-00000 형식") String bizRegNo,
            @NotNull PartnerType partnerType) {
    }

    public record UpdatePartnerRequest(@Size(max = 100) String name,
                                       @Pattern(regexp = "\\d{3}-\\d{2}-\\d{5}", message = "000-00-00000 형식") String bizRegNo,
                                       PartnerType partnerType, Boolean active) {
    }

    @GetMapping
    @PreAuthorize("@companyAccess.has(#companyId, 'WRITER')")
    public List<PartnerResponse> search(@PathVariable Long companyId,
                                        @RequestParam(required = false) String q,
                                        @RequestParam(required = false) PartnerType type,
                                        @RequestParam(defaultValue = "false") boolean activeOnly) {
        return service.search(companyId, q, type, activeOnly).stream().map(PartnerResponse::of).toList();
    }

    @GetMapping("/{code}")
    @PreAuthorize("@companyAccess.has(#companyId, 'WRITER')")
    public PartnerResponse get(@PathVariable Long companyId, @PathVariable String code) {
        return PartnerResponse.of(service.get(companyId, code));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@companyAccess.has(#companyId, 'WRITER')")
    public PartnerResponse create(@PathVariable Long companyId, @Valid @RequestBody CreatePartnerRequest r) {
        return PartnerResponse.of(service.create(companyId,
                new PartnerService.CreateCommand(r.code(), r.name(), r.bizRegNo(), r.partnerType())));
    }

    @PatchMapping("/{code}")
    @PreAuthorize("@companyAccess.has(#companyId, 'WRITER')")
    public PartnerResponse update(@PathVariable Long companyId, @PathVariable String code,
                                  @Valid @RequestBody UpdatePartnerRequest r) {
        return PartnerResponse.of(service.update(companyId, code,
                new PartnerService.UpdateCommand(r.name(), r.bizRegNo(), r.partnerType(), r.active())));
    }
}
