package com.nexus360.access;

import com.nexus360.access.AccessDtos.AccountRequestDto;
import com.nexus360.access.AccessDtos.ApproveBody;
import com.nexus360.access.AccessDtos.InvitationBody;
import com.nexus360.access.AccessDtos.LinkResponse;
import com.nexus360.access.AccessDtos.RejectBody;
import com.nexus360.access.AccessDtos.RequestCounts;
import com.nexus360.common.PageResponse;
import com.nexus360.security.CurrentActor;
import com.nexus360.security.Roles;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Users → Access requests: review, approve (single-use link), reject, and direct invitations. USER_ADMINS only. */
@Tag(name = "Account requests & invitations")
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AccountRequestAdminController {

    private final AccountRequestAdminService service;

    @PreAuthorize(Roles.USER_ADMINS)
    @GetMapping("/account-requests")
    public PageResponse<AccountRequestDto> list(@RequestParam(required = false) AccountRequestType type,
                                                @RequestParam(required = false) AccountRequestStatus status,
                                                @RequestParam(required = false) Integer page,
                                                @RequestParam(required = false) Integer size) {
        return service.list(type, status, page, size);
    }

    @PreAuthorize(Roles.USER_ADMINS)
    @GetMapping("/account-requests/counts")
    public RequestCounts counts() {
        return service.counts();
    }

    @Operation(summary = "Approve: ACCESS needs {role, departmentId, jobTitle?}; PASSWORD_RESET takes {}. Returns the link once.")
    @PreAuthorize(Roles.USER_ADMINS)
    @PostMapping("/account-requests/{id}/approve")
    public LinkResponse approve(@PathVariable Long id, @Valid @RequestBody(required = false) ApproveBody body) {
        return service.approve(id, body, CurrentActor.require());
    }

    @PreAuthorize(Roles.USER_ADMINS)
    @PostMapping("/account-requests/{id}/reject")
    public AccountRequestDto reject(@PathVariable Long id, @Valid @RequestBody RejectBody body) {
        return service.reject(id, body.note(), CurrentActor.require());
    }

    @Operation(summary = "Invite someone directly; returns the single-use registration link once")
    @PreAuthorize(Roles.USER_ADMINS)
    @PostMapping("/invitations")
    public LinkResponse invite(@Valid @RequestBody InvitationBody body) {
        return service.invite(body, CurrentActor.require());
    }
}
