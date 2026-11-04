package com.nexus360.access;

import com.nexus360.access.AccessDtos.AccountRequestDto;
import com.nexus360.access.AccessDtos.ApproveBody;
import com.nexus360.access.AccessDtos.InvitationBody;
import com.nexus360.access.AccessDtos.LinkResponse;
import com.nexus360.access.AccessDtos.RequestCounts;
import com.nexus360.audit.AuditService;
import com.nexus360.common.ApiError;
import com.nexus360.common.ApiException;
import com.nexus360.common.PageResponse;
import com.nexus360.security.Actor;
import com.nexus360.user.Department;
import com.nexus360.user.DepartmentRepository;
import com.nexus360.user.Role;
import com.nexus360.user.User;
import com.nexus360.user.UserRepository;
import jakarta.persistence.criteria.Predicate;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * User administrators' side of account access: review requests, approve them into single-use links, reject them, or
 * invite someone directly. The raw token of a link is returned exactly once (in {@link LinkResponse}) and never audited.
 */
@Service
@RequiredArgsConstructor
public class AccountRequestAdminService {

    private final AccountRequestRepository requests;
    private final AccountTokenService tokens;
    private final UserRepository users;
    private final DepartmentRepository departments;
    private final AccountLinkMailer mailer;
    private final AuditService audit;
    private final Clock clock;

    @Transactional(readOnly = true)
    public PageResponse<AccountRequestDto> list(AccountRequestType type, AccountRequestStatus status, Integer page, Integer size) {
        Specification<AccountRequest> spec = (root, query, cb) -> {
            List<Predicate> p = new ArrayList<>();
            if (type != null) {
                p.add(cb.equal(root.get("type"), type));
            }
            if (status != null) {
                p.add(cb.equal(root.get("status"), status));
            }
            return cb.and(p.toArray(new Predicate[0]));
        };
        Sort newestFirst = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));
        return PageResponse.of(requests.findAll(spec, PageResponse.pageable(page, size, newestFirst)), AccountRequestDto::of);
    }

    @Transactional(readOnly = true)
    public RequestCounts counts() {
        return new RequestCounts(requests.countByTypeAndStatus(AccountRequestType.ACCESS, AccountRequestStatus.PENDING),
                requests.countByTypeAndStatus(AccountRequestType.PASSWORD_RESET, AccountRequestStatus.PENDING));
    }

    @Transactional
    public LinkResponse approve(Long id, ApproveBody body, Actor actor) {
        AccountRequest request = requests.findForUpdate(id).orElseThrow(() -> ApiException.notFound("Account request", id));
        requirePending(request);
        ApproveBody choice = body == null ? new ApproveBody(null, null, null) : body;
        AccountTokenService.Issued issued = request.getType() == AccountRequestType.ACCESS
                ? approveAccess(request, choice, actor)
                : approveReset(request, actor);
        request.setStatus(AccountRequestStatus.APPROVED);
        request.setDecidedBy(actor.id() == null ? null : users.getReferenceById(actor.id()));
        request.setDecidedAt(clock.instant());

        AccountToken token = issued.token();
        Map<String, Object> decision = new LinkedHashMap<>();
        decision.put("type", request.getType());
        decision.put("email", request.getEmail());
        decision.put("linkId", token.getId());
        audit.record("ACCESS_REQUEST_APPROVED", "ACCOUNT_REQUEST", request.getId(), decision);
        String link = AccountTokenService.link(token.getPurpose(), issued.rawToken());
        boolean emailed = mailer.send(token, link);
        Map<String, Object> linkDetails = linkDetails(token, issued.revokedPrevious());
        linkDetails.put("emailQueued", emailed);
        audit.record(token.getPurpose() == AccountTokenPurpose.INVITE ? "INVITATION_CREATED" : "PASSWORD_RESET_LINK_CREATED",
                "ACCOUNT_TOKEN", token.getId(), linkDetails);
        return new LinkResponse(link, token.getExpiresAt(), emailed);
    }

    @Transactional
    public AccountRequestDto reject(Long id, String note, Actor actor) {
        AccountRequest request = requests.findForUpdate(id).orElseThrow(() -> ApiException.notFound("Account request", id));
        requirePending(request);
        request.setStatus(AccountRequestStatus.REJECTED);
        request.setDecidedBy(actor.id() == null ? null : users.findById(actor.id()).orElse(null));
        request.setDecidedAt(clock.instant());
        request.setDecisionNote(note.trim());
        audit.record("ACCESS_REQUEST_REJECTED", "ACCOUNT_REQUEST", request.getId(),
                Map.of("type", request.getType(), "email", request.getEmail(), "note", request.getDecisionNote()));
        return AccountRequestDto.of(request);
    }

    @Transactional
    public LinkResponse invite(InvitationBody body, Actor actor) {
        String email = PublicAccessService.normalizeEmail(body.email());
        requireMayGrant(actor, body.role());
        if (users.existsByEmailIgnoreCase(email)) {
            throw ApiException.conflict("An account with this e-mail address already exists");
        }
        Department department = department(body.departmentId());
        AccountTokenService.Issued issued = tokens.issue(AccountTokenPurpose.INVITE, email, PublicAccessService.blankToNull(body.fullName()),
                body.role(), department, PublicAccessService.blankToNull(body.jobTitle()), null, null, actor.id());
        AccountToken token = issued.token();
        String link = AccountTokenService.link(AccountTokenPurpose.INVITE, issued.rawToken());
        boolean emailed = mailer.send(token, link);
        Map<String, Object> linkDetails = linkDetails(token, issued.revokedPrevious());
        linkDetails.put("emailQueued", emailed);
        audit.record("INVITATION_CREATED", "ACCOUNT_TOKEN", token.getId(), linkDetails);
        return new LinkResponse(link, token.getExpiresAt(), emailed);
    }

    // ------------------------------------------------------------------ helpers

    private AccountTokenService.Issued approveAccess(AccountRequest request, ApproveBody choice, Actor actor) {
        List<ApiError.FieldError> missing = new ArrayList<>();
        if (choice.role() == null) {
            missing.add(new ApiError.FieldError("role", "Choose the role to grant"));
        }
        if (choice.departmentId() == null) {
            missing.add(new ApiError.FieldError("departmentId", "Choose a department"));
        }
        if (!missing.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Validation failed", missing);
        }
        requireMayGrant(actor, choice.role());
        if (users.existsByEmailIgnoreCase(request.getEmail())) {
            throw ApiException.conflict("An account with this e-mail address already exists");
        }
        Department department = department(choice.departmentId());
        String jobTitle = PublicAccessService.blankToNull(choice.jobTitle());
        return tokens.issue(AccountTokenPurpose.INVITE, request.getEmail(), request.getFullName(), choice.role(), department,
                jobTitle != null ? jobTitle : request.getJobTitle(), null, request.getId(), actor.id());
    }

    private AccountTokenService.Issued approveReset(AccountRequest request, Actor actor) {
        User user = request.getUserId() == null ? null : users.findById(request.getUserId()).orElse(null);
        if (user == null || !user.isActive()) {
            throw ApiException.conflict("The account for this request no longer exists or is inactive");
        }
        // Mirrors UserService: an IT administrator cannot take over a system administrator account.
        if (user.getRole() == Role.SYSTEM_ADMIN && !actor.isSystem() && actor.role() != Role.SYSTEM_ADMIN) {
            throw ApiException.forbidden("Only a system administrator can issue a reset link for a system administrator account");
        }
        return tokens.issue(AccountTokenPurpose.PASSWORD_RESET, user.getEmail().toLowerCase(Locale.ROOT), user.getFullName(),
                null, null, null, user.getId(), request.getId(), actor.id());
    }

    private static void requirePending(AccountRequest request) {
        if (request.getStatus() != AccountRequestStatus.PENDING) {
            throw ApiException.conflict("This request is no longer pending (" + request.getStatus() + ")");
        }
    }

    /** IT administrators manage users but cannot grant the SYSTEM_ADMIN role (same rule as UserService). */
    private static void requireMayGrant(Actor actor, Role role) {
        if (role == Role.SYSTEM_ADMIN && !actor.isSystem() && actor.role() != Role.SYSTEM_ADMIN) {
            throw ApiException.forbidden("Only a system administrator can grant the SYSTEM_ADMIN role");
        }
    }

    private Department department(Long id) {
        return departments.findById(id).orElseThrow(() -> ApiException.invalidField("departmentId", "Department does not exist"));
    }

    /** Audit details of an issued link: who it is for and what it grants — never the token or the link itself. */
    private static Map<String, Object> linkDetails(AccountToken token, int revokedPrevious) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("email", token.getEmail());
        if (token.getRole() != null) {
            details.put("role", token.getRole());
        }
        if (token.getDepartment() != null) {
            details.put("departmentId", token.getDepartment().getId());
        }
        if (token.getUserId() != null) {
            details.put("userId", token.getUserId());
        }
        details.put("accountRequestId", token.getAccountRequestId());
        details.put("expiresAt", token.getExpiresAt());
        details.put("revokedPrevious", revokedPrevious);
        return details;
    }
}
