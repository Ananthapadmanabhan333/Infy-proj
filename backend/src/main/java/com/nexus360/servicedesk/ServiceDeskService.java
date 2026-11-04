package com.nexus360.servicedesk;

import com.nexus360.approval.Approval;
import com.nexus360.approval.ApprovalHandler;
import com.nexus360.approval.ApprovalRepository;
import com.nexus360.approval.ApprovalService;
import com.nexus360.approval.ApprovalService.ApprovalDto;
import com.nexus360.audit.AuditService;
import com.nexus360.common.ApiException;
import com.nexus360.common.PageResponse;
import com.nexus360.events.DomainEvents.ServiceRequestEvent;
import com.nexus360.incident.AssignmentService;
import com.nexus360.incident.Priority;
import com.nexus360.notification.Notification.Severity;
import com.nexus360.notification.NotificationService;
import com.nexus360.security.Actor;
import com.nexus360.servicedesk.ServiceDeskDtos.CatalogItemDto;
import com.nexus360.servicedesk.ServiceDeskDtos.CatalogItemRequest;
import com.nexus360.servicedesk.ServiceDeskDtos.CategoryDto;
import com.nexus360.servicedesk.ServiceDeskDtos.CategoryRequest;
import com.nexus360.servicedesk.ServiceDeskDtos.CreateServiceRequest;
import com.nexus360.servicedesk.ServiceDeskDtos.RequestCommentDto;
import com.nexus360.servicedesk.ServiceDeskDtos.RequestCounts;
import com.nexus360.servicedesk.ServiceDeskDtos.RequestHistoryDto;
import com.nexus360.servicedesk.ServiceDeskDtos.ServiceRequestDto;
import com.nexus360.servicedesk.ServiceRequest.Status;
import com.nexus360.team.Team;
import com.nexus360.team.TeamRouting;
import com.nexus360.user.Department;
import com.nexus360.user.DepartmentRepository;
import com.nexus360.user.Role;
import com.nexus360.user.User;
import com.nexus360.user.UserRepository;
import jakarta.persistence.criteria.Predicate;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Service catalogue and request fulfilment. Also applies approval decisions for SERVICE_REQUEST approvals. */
@Service
@RequiredArgsConstructor
public class ServiceDeskService implements ApprovalHandler {

    public static final Set<Status> OPEN_STATUSES = EnumSet.of(Status.PENDING_APPROVAL, Status.APPROVED, Status.IN_FULFILLMENT);
    private static final Set<Priority> EMPLOYEE_PRIORITIES = EnumSet.of(Priority.LOW, Priority.MEDIUM, Priority.HIGH);

    /** Filters of the request list (also used by the tab counts). */
    public record RequestFilter(String scope, Status status, String search, String categoryCode, Priority priority) {}

    private final ServiceCategoryRepository categories;
    private final CatalogItemRepository items;
    private final ServiceRequestRepository requests;
    private final ServiceRequestCommentRepository comments;
    private final ServiceRequestHistoryRepository history;
    private final DepartmentRepository departments;
    private final UserRepository users;
    private final ApprovalService approvals;
    private final ApprovalRepository approvalRepository;
    private final AssignmentService assignment;
    private final TeamRouting teamRouting;
    private final NotificationService notifications;
    private final AuditService audit;
    private final ApplicationEventPublisher publisher;
    private final Clock clock;
    private final com.nexus360.form.FormService forms;
    private final ServiceRequestAttachmentRepository attachments;
    private final com.nexus360.config.AppProperties properties;

    // ------------------------------------------------------------------ catalogue

    @Transactional(readOnly = true)
    public List<CategoryDto> categories() {
        Map<Long, Long> counts = new HashMap<>();
        for (Object[] row : items.countActiveByCategory()) {
            counts.put((Long) row[0], (Long) row[1]);
        }
        return categories.findAllByOrderBySortOrderAscNameAsc().stream()
                .map(c -> new CategoryDto(c.getId(), c.getCode(), c.getName(), c.getDescription(), c.getIcon(), counts.getOrDefault(c.getId(), 0L)))
                .toList();
    }

    @Transactional
    public CategoryDto saveCategory(Long id, CategoryRequest r) {
        ServiceCategory c = id == null ? new ServiceCategory() : categories.findById(id).orElseThrow(() -> ApiException.notFound("Category", id));
        if (id == null && categories.existsByCodeIgnoreCase(r.code())) {
            throw ApiException.conflict("Category code already in use");
        }
        c.setCode(r.code());
        c.setName(r.name().trim());
        c.setDescription(r.description());
        c.setIcon(r.icon());
        c.setSortOrder(r.sortOrder());
        categories.save(c);
        audit.record(id == null ? "CATALOG_CATEGORY_CREATED" : "CATALOG_CATEGORY_UPDATED", "CATALOG_CATEGORY", c.getId(), Map.of("code", c.getCode()));
        return new CategoryDto(c.getId(), c.getCode(), c.getName(), c.getDescription(), c.getIcon(), 0);
    }

    @Transactional(readOnly = true)
    public List<CatalogItemDto> items(Long categoryId, String search, boolean includeInactive) {
        return items(categoryId, search, includeInactive, null);
    }

    /**
     * Catalogue items. Release 3: request types may be restricted to roles and departments; people who may not submit an
     * item do not see it (administrators see every item, with {@code canSubmit} telling whether they may).
     */
    @Transactional(readOnly = true)
    public List<CatalogItemDto> items(Long categoryId, String search, boolean includeInactive, Actor actor) {
        String q = search == null ? null : search.trim().toLowerCase(Locale.ROOT);
        Long departmentId = departmentOf(actor);
        boolean admin = actor == null || actor.role().isAdmin();
        return items.findAllByOrderByNameAsc().stream()
                .filter(i -> includeInactive || i.isActive())
                .filter(i -> categoryId == null || i.getCategory().getId().equals(categoryId))
                .filter(i -> q == null || q.isEmpty() || i.getName().toLowerCase(Locale.ROOT).contains(q)
                        || (i.getDescription() != null && i.getDescription().toLowerCase(Locale.ROOT).contains(q)))
                .filter(i -> admin || canSubmit(i, actor, departmentId))
                .map(i -> dto(i, actor == null || canSubmit(i, actor, departmentId)))
                .toList();
    }

    @Transactional
    public CatalogItemDto saveItem(Long id, CatalogItemRequest r) {
        return saveItem(id, r, com.nexus360.security.CurrentActor.orSystem());
    }

    @Transactional
    public CatalogItemDto saveItem(Long id, CatalogItemRequest r, Actor actor) {
        CatalogItem item = id == null ? new CatalogItem() : items.findWithDetailsById(id).orElseThrow(() -> ApiException.notFound("Catalog item", id));
        if (r.requiresApproval() && (r.approverRole() == null || !Role.APPROVERS.contains(r.approverRole()))) {
            throw ApiException.badRequest("Approval-gated items need an approver role of IT_MANAGER, INCIDENT_MANAGER or SYSTEM_ADMIN");
        }
        List<Long> allowedDepartments = r.allowedDepartmentIds() == null ? List.of() : r.allowedDepartmentIds().stream().distinct().toList();
        for (Long d : allowedDepartments) {
            if (d == null || !departments.existsById(d)) {
                throw ApiException.invalidField("allowedDepartmentIds", "Department " + d + " does not exist");
            }
        }
        item.setCategory(categories.findById(r.categoryId()).orElseThrow(() -> ApiException.badRequest("Category does not exist")));
        item.setName(r.name().trim());
        item.setDescription(r.description());
        item.setRequiresApproval(r.requiresApproval());
        item.setApproverRole(r.requiresApproval() ? r.approverRole() : null);
        item.setFulfillmentHours(r.fulfillmentHours());
        item.setFulfillmentDepartment(departments.findById(r.fulfillmentDepartmentId())
                .orElseThrow(() -> ApiException.badRequest("Department does not exist")));
        item.setActive(r.active());
        item.setAllowedRoles(new ArrayList<>(r.allowedRoles() == null ? List.of() : r.allowedRoles().stream().distinct().toList()));
        item.setAllowedDepartmentIds(new ArrayList<>(allowedDepartments));
        if (item.getFormFields() == null) {
            item.setFormFields(new ArrayList<>());
        }
        items.save(item);
        forms.attach(item, r.formDefinitionId(), r.formFields(), actor);
        audit.record(id == null ? "CATALOG_ITEM_CREATED" : "CATALOG_ITEM_UPDATED", "CATALOG_ITEM", item.getId(), Map.of("name", item.getName(),
                "form", item.getFormDefinition() == null ? "" : item.getFormDefinition().getCode(),
                "restricted", !item.getAllowedRoles().isEmpty() || !item.getAllowedDepartmentIds().isEmpty()));
        return dto(item, true);
    }

    /** The current form of a catalogue item for the submitter (404 for items they may not see). */
    @Transactional(readOnly = true)
    public ServiceDeskDtos.CatalogFormDto catalogForm(Long itemId, Actor actor) {
        CatalogItem item = items.findWithDetailsById(itemId).filter(i -> i.isActive() || actor.role().isAdmin())
                .orElseThrow(() -> ApiException.notFound("Catalog item", itemId));
        boolean can = canSubmit(item, actor, departmentOf(actor));
        if (!can && !actor.role().isAdmin()) {
            throw ApiException.notFound("Catalog item", itemId);
        }
        com.nexus360.form.FormService.ResolvedForm form = forms.resolve(item);
        return new ServiceDeskDtos.CatalogFormDto(item.getId(), item.getName(), form.formId(), form.version(), form.fields(), can);
    }

    /** Dry-run of a submission's form data (the same server-side rules as {@link #create}). */
    @Transactional(readOnly = true)
    public ServiceDeskDtos.FormValidationResult validateSubmission(ServiceDeskDtos.ValidateRequestBody body, Actor actor) {
        CatalogItem item = items.findWithDetailsById(body.catalogItemId()).filter(CatalogItem::isActive)
                .orElseThrow(() -> ApiException.badRequest("Catalogue item is not available"));
        requireCanSubmit(item, actor);
        return new ServiceDeskDtos.FormValidationResult(true, com.nexus360.form.FormValidator.validate(forms.resolve(item).fields(),
                body.formData() == null ? Map.of() : body.formData(), lookups(actor), forms.today()));
    }

    /** Who may submit an item: no restriction, or the actor's role and department are allowed (admins always). */
    static boolean canSubmit(CatalogItem item, Actor actor, Long departmentId) {
        if (actor == null || actor.role().isAdmin()) {
            return true;
        }
        boolean roleOk = item.getAllowedRoles() == null || item.getAllowedRoles().isEmpty() || item.getAllowedRoles().contains(actor.role());
        boolean deptOk = item.getAllowedDepartmentIds() == null || item.getAllowedDepartmentIds().isEmpty()
                || departmentId != null && item.getAllowedDepartmentIds().contains(departmentId);
        return roleOk && deptOk;
    }

    private void requireCanSubmit(CatalogItem item, Actor actor) {
        if (!canSubmit(item, actor, departmentOf(actor))) {
            throw ApiException.forbidden("You are not allowed to submit the request type “" + item.getName() + "”");
        }
    }

    private Long departmentOf(Actor actor) {
        return actor == null || actor.id() == null ? null
                : users.findWithDepartmentById(actor.id()).map(User::getDepartment).map(Department::getId).orElse(null);
    }

    /** Record lookups for user-picker and file fields: active users (optionally by role) and the submitter's staged uploads. */
    private com.nexus360.form.FormValidator.Lookups lookups(Actor actor) {
        return new com.nexus360.form.FormValidator.Lookups() {
            @Override
            public java.util.Optional<Map<String, Object>> user(Long id, List<Role> roles) {
                return users.findById(id).filter(User::isActive).filter(u -> roles.isEmpty() || roles.contains(u.getRole()))
                        .map(u -> Map.<String, Object>of("id", u.getId(), "name", u.getFullName()));
            }

            @Override
            public java.util.Optional<Map<String, Object>> stagedFile(Long id) {
                return actor.id() == null ? java.util.Optional.empty() : attachments.findStaged(id, actor.id())
                        .map(m -> Map.<String, Object>of("id", m.id(), "fileName", m.fileName(), "contentType", m.contentType(),
                                "sizeBytes", m.sizeBytes()));
            }
        };
    }

    // ------------------------------------------------------------------ request attachments (file form fields)

    /** Uploads a file for a file field (validated like incident attachments); submit the returned id as the field value. */
    @Transactional
    public ServiceDeskDtos.StagedUpload upload(org.springframework.web.multipart.MultipartFile file, Actor actor) {
        if (actor.role() == Role.AUDITOR) {
            throw ApiException.forbidden("Auditors have read-only access");
        }
        if (file == null || file.isEmpty()) {
            throw ApiException.badRequest("The file is empty");
        }
        long max = properties.attachments().maxBytes();
        if (file.getSize() > max) {
            throw new ApiException(org.springframework.http.HttpStatus.PAYLOAD_TOO_LARGE, "File exceeds the " + max / (1024 * 1024) + " MB limit");
        }
        Instant now = clock.instant();
        attachments.purgeStaged(actor.id(), now.minus(Duration.ofHours(24)));
        if (attachments.countByUploadedByIdAndRequestIdIsNull(actor.id()) >= 20) {
            throw ApiException.conflict("You have 20 uploaded files that are not used in a request yet; submit or wait for them to expire");
        }
        String name = com.nexus360.incident.AttachmentService.sanitizeFileName(file.getOriginalFilename());
        byte[] data;
        try {
            data = file.getBytes();
        } catch (java.io.IOException e) {
            throw ApiException.badRequest("Could not read the uploaded file");
        }
        String contentType = com.nexus360.incident.AttachmentService.detectContentType(name, data);
        ServiceRequestAttachment a = new ServiceRequestAttachment();
        a.setUploadedBy(users.getReferenceById(actor.id()));
        a.setFileName(name);
        a.setContentType(contentType);
        a.setSizeBytes(data.length);
        a.setSha256(com.nexus360.security.Hashing.sha256Hex(data));
        a.setData(data);
        a.setCreatedAt(now);
        attachments.save(a);
        audit.record("REQUEST_FILE_UPLOADED", "SERVICE_REQUEST_ATTACHMENT", a.getId(), Map.of("file", name, "bytes", data.length, "type", contentType));
        return new ServiceDeskDtos.StagedUpload(a.getId(), name, contentType, data.length, now);
    }

    @Transactional(readOnly = true)
    public List<ServiceDeskDtos.RequestAttachmentDto> attachments(Long requestId, Actor actor) {
        load(requestId, actor);
        return attachments.findMetaByRequest(requestId).stream().map(m -> new ServiceDeskDtos.RequestAttachmentDto(m.id(), m.fileName(),
                m.contentType(), m.sizeBytes(), m.fieldKey(), m.uploadedByName(), m.createdAt())).toList();
    }

    @Transactional(readOnly = true)
    public com.nexus360.incident.AttachmentService.Download download(Long requestId, Long attachmentId, Actor actor) {
        load(requestId, actor);
        ServiceRequestAttachment a = attachments.findByIdAndRequestId(attachmentId, requestId)
                .orElseThrow(() -> ApiException.notFound("Attachment", attachmentId));
        audit.record("REQUEST_FILE_DOWNLOADED", "SERVICE_REQUEST_ATTACHMENT", a.getId(), Map.of("request", requestId));
        return new com.nexus360.incident.AttachmentService.Download(a.getFileName(), a.getContentType(), a.getData());
    }

    /** User picker search for form fields: active users by name (at least 2 characters), optionally limited to roles. */
    @Transactional(readOnly = true)
    public List<ServiceDeskDtos.UserOption> userOptions(String q, List<Role> roles) {
        String term = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        if (term.length() < 2) {
            return List.of();
        }
        return users.findAll().stream().filter(User::isActive)
                .filter(u -> roles == null || roles.isEmpty() || roles.contains(u.getRole()))
                .filter(u -> u.getFullName().toLowerCase(Locale.ROOT).contains(term))
                .sorted(java.util.Comparator.comparing(User::getFullName)).limit(20)
                .map(u -> new ServiceDeskDtos.UserOption(u.getId(), u.getFullName(), u.getDepartment() == null ? null : u.getDepartment().getName(),
                        u.getRole()))
                .toList();
    }

    // ------------------------------------------------------------------ requests

    @Transactional(readOnly = true)
    public PageResponse<ServiceRequestDto> list(RequestFilter filter, Integer page, Integer size, Actor actor) {
        Page<ServiceRequest> result = requests.findAll(specification(filter, actor, true),
                PageResponse.pageable(page, size, Sort.by(Sort.Direction.DESC, "createdAt")));
        return PageResponse.of(result, toDtos(result.getContent(), actor));
    }

    /** DTOs for a batch of loaded requests (latest approval, comment count visible to the actor); used by My Work and the portal. */
    @Transactional(readOnly = true)
    public List<ServiceRequestDto> toDtos(List<ServiceRequest> list, Actor actor) {
        List<Long> ids = list.stream().map(ServiceRequest::getId).toList();
        Map<Long, ApprovalDto> approvalsById = ids.isEmpty() ? Map.of()
                : approvalRepository.findByTypeAndReferenceIdIn(Approval.Type.SERVICE_REQUEST, ids).stream()
                .collect(Collectors.toMap(Approval::getReferenceId, ApprovalDto::of, (a, b) -> a.createdAt().isAfter(b.createdAt()) ? a : b));
        Map<Long, Long> commentCounts = commentCounts(ids, actor);
        return list.stream().map(r -> dto(r, approvalsById.get(r.getId()), commentCounts.getOrDefault(r.getId(), 0L))).toList();
    }

    /**
     * Tab counts within the scope: all, mine (raised by the caller), pendingApproval, inFulfillment (approved or being
     * fulfilled) and fulfilled. Category, priority and search filters apply; the status filter does not.
     */
    @Transactional(readOnly = true)
    public RequestCounts counts(RequestFilter filter, Actor actor) {
        RequestFilter base = new RequestFilter(filter.scope(), null, filter.search(), filter.categoryCode(), filter.priority());
        Specification<ServiceRequest> scoped = specification(base, actor, false);
        long all = requests.count(scoped);
        long mine = requests.count(specification(new RequestFilter("mine", null, filter.search(), filter.categoryCode(), filter.priority()),
                actor, false));
        long pending = requests.count(scoped.and(statusIn(EnumSet.of(Status.PENDING_APPROVAL))));
        long fulfilling = requests.count(scoped.and(statusIn(EnumSet.of(Status.APPROVED, Status.IN_FULFILLMENT))));
        long fulfilled = requests.count(scoped.and(statusIn(EnumSet.of(Status.FULFILLED))));
        return new RequestCounts(all, mine, pending, fulfilling, fulfilled);
    }

    @Transactional(readOnly = true)
    public ServiceRequestDto get(Long id, Actor actor) {
        ServiceRequest r = load(id, actor);
        return dto(r, approvals.latestFor(Approval.Type.SERVICE_REQUEST, r.getId()), commentCounts(List.of(id), actor).getOrDefault(id, 0L));
    }

    @Transactional
    public ServiceRequestDto create(CreateServiceRequest body, Actor actor) {
        CatalogItem item = items.findWithDetailsById(body.catalogItemId()).filter(CatalogItem::isActive)
                .orElseThrow(() -> ApiException.badRequest("Catalogue item is not available"));
        Priority priority = body.priority() == null ? Priority.MEDIUM : body.priority();
        if (!actor.isStaff() && !EMPLOYEE_PRIORITIES.contains(priority)) {
            throw ApiException.badRequest("Priority must be LOW, MEDIUM or HIGH");
        }
        requireCanSubmit(item, actor);
        com.nexus360.form.FormService.ResolvedForm form = forms.resolve(item);
        Map<String, Object> formData = com.nexus360.form.FormValidator.validate(form.fields(), body.formData() == null ? Map.of() : body.formData(),
                lookups(actor), forms.today());
        User requester = users.findById(actor.id()).orElseThrow(() -> ApiException.badRequest("Unknown user"));
        Instant now = clock.instant();
        ServiceRequest r = new ServiceRequest();
        r.setNumber("REQ-" + requests.nextNumber());
        r.setCatalogItem(item);
        r.setRequester(requester);
        r.setTitle(body.title().trim());
        r.setDetails(body.details());
        r.setFormData(formData);
        r.setPriority(priority);
        r.setDepartment(item.getFulfillmentDepartment());
        r.setTeam(teamRouting.defaultTeam(item.getFulfillmentDepartment()).orElse(null));
        r.setDemo(requester.isDemo());
        r.setStatus(item.isRequiresApproval() ? Status.PENDING_APPROVAL : Status.APPROVED);
        r.setFormDefinitionId(form.formId());
        r.setFormVersion(form.version());
        requests.save(r);
        linkFiles(form.fields(), formData, r, actor);
        record(r, actor, "CREATED", null, r.getStatus().name(), "Priority " + priority.name().toLowerCase(Locale.ROOT));
        if (!item.isRequiresApproval()) {
            startClock(r, now);
        }
        ApprovalDto approval = null;
        if (item.isRequiresApproval()) {
            Approval a = approvals.request(Approval.Type.SERVICE_REQUEST, r.getId(), r.getNumber(), item.getName() + " for " + requester.getFullName(),
                    r.getTitle(), Map.of("catalogItem", item.getName(), "formData", formData), item.getApproverRole(), actor, r.isDemo(),
                    "/service-desk?tab=approvals");
            approval = ApprovalDto.of(a);
            record(r, actor, "APPROVAL_REQUESTED", null, a.getApproverRole().name(), "Awaiting approval by " + a.getApproverRole());
        }
        audit.record("SERVICE_REQUEST_CREATED", "SERVICE_REQUEST", r.getNumber(), Map.of("item", item.getName(), "approval", item.isRequiresApproval(),
                "priority", priority));
        publisher.publishEvent(new ServiceRequestEvent(r.getId(), "CREATED", actor.depth()));
        return dto(r, approval, 0);
    }

    @Transactional
    public ServiceRequestDto assign(Long id, Long assigneeId, Actor actor) {
        return assign(id, assigneeId, null, actor);
    }

    /**
     * Assigns to a staff member and/or a team (Release 3). With a team the assignee must be a member and the request moves
     * to the team's department; a team without an assignee puts the request in the team queue. Without a team, the team
     * follows from the assignee (see {@link TeamRouting#resolve}).
     */
    @Transactional
    public ServiceRequestDto assign(Long id, Long assigneeId, Long teamId, Actor actor) {
        requireStaff(actor);
        if (assigneeId == null && teamId == null) {
            throw ApiException.badRequest("Give an assignee, a team or both");
        }
        ServiceRequest r = load(id, actor);
        requireOpen(r);
        User assignee = assigneeId == null ? null : users.findWithDepartmentById(assigneeId).filter(u -> u.isActive() && u.getRole().isStaff())
                .orElseThrow(() -> ApiException.badRequest("Assignee must be active support staff"));
        Team team = null;
        if (teamId != null) {
            team = teamRouting.activeById(teamId).orElseThrow(() -> ApiException.badRequest("Team does not exist or is inactive"));
            if (assignee != null && !teamRouting.isMember(team, assignee)) {
                throw ApiException.badRequest(assignee.getFullName() + " is not a member of " + team.getName());
            }
            r.setDepartment(team.getDepartment());
        } else {
            team = teamRouting.resolve(r.getTeam(), r.getDepartment(), assignee);
        }
        String previousTeam = r.getTeam() == null ? null : r.getTeam().getName();
        Long previousTeamId = r.getTeam() == null ? null : r.getTeam().getId();
        r.setTeam(team);
        if (!java.util.Objects.equals(previousTeam, team == null ? null : team.getName())) {
            record(r, actor, "TEAM_CHANGED", previousTeam, team == null ? null : team.getName(), null);
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("team", team == null ? null : team.getCode());
        if (assignee != null) {
            String previous = r.getAssignee() == null ? null : r.getAssignee().getFullName();
            r.setAssignee(assignee);
            record(r, actor, "ASSIGNED", previous, assignee.getFullName(), null);
            notifications.notifyUser(assignee.getId(), "REQUEST_ASSIGNED", "Request assigned: " + r.getNumber(), r.getTitle(),
                    "/service-desk?request=" + r.getId(), Severity.INFO);
            details.put("assignee", assignee.getEmail());
        } else if (team != null && !team.getId().equals(previousTeamId)) {
            if (r.getAssignee() != null) {
                record(r, actor, "ASSIGNED", r.getAssignee().getFullName(), null, "Moved to the " + team.getName() + " queue");
                r.setAssignee(null);
            }
            notifyTeamQueue(r, team);
        }
        audit.record("SERVICE_REQUEST_ASSIGNED", "SERVICE_REQUEST", r.getNumber(), details);
        return get(id, actor);
    }

    /** Workflow helper (ASSIGN_TEAM): team and optional member (null = team queue). */
    @Transactional
    public void assignToTeamInternal(ServiceRequest r, Team team, User member, Actor actor) {
        String previousTeam = r.getTeam() == null ? null : r.getTeam().getName();
        r.setTeam(team);
        r.setDepartment(team.getDepartment());
        if (!java.util.Objects.equals(previousTeam, team.getName())) {
            record(r, actor, "TEAM_CHANGED", previousTeam, team.getName(), null);
        }
        if (member != null) {
            String previous = r.getAssignee() == null ? null : r.getAssignee().getFullName();
            r.setAssignee(member);
            record(r, actor, "ASSIGNED", previous, member.getFullName(), "Routed to " + team.getName());
            notifications.notifyUser(member.getId(), "REQUEST_ASSIGNED", "Request assigned: " + r.getNumber(), r.getTitle(),
                    "/service-desk?request=" + r.getId(), Severity.INFO);
        } else {
            r.setAssignee(null);
            notifyTeamQueue(r, team);
        }
    }

    private void notifyTeamQueue(ServiceRequest r, Team team) {
        notifications.notifyUsers(teamRouting.activeMembers(team).stream().map(User::getId).toList(), "TEAM_QUEUE",
                "New in " + team.getName() + ": " + r.getNumber(), r.getTitle() + " is waiting in the team queue.",
                "/service-desk?request=" + r.getId(), Severity.INFO);
    }

    @Transactional
    public ServiceRequestDto start(Long id, Actor actor) {
        requireStaff(actor);
        ServiceRequest r = load(id, actor);
        if (r.getStatus() != Status.APPROVED) {
            throw ApiException.conflict("Only approved requests can move into fulfilment");
        }
        if (r.getAssignee() == null) {
            r.setAssignee(users.getReferenceById(actor.id()));
            record(r, actor, "ASSIGNED", null, actor.name(), "Picked up when fulfilment started");
        }
        r.setStatus(Status.IN_FULFILLMENT);
        record(r, actor, "STATUS_CHANGED", Status.APPROVED.name(), Status.IN_FULFILLMENT.name(), null);
        notifications.notifyUser(r.getRequester().getId(), "REQUEST_UPDATED", r.getNumber() + " in fulfilment",
                actor.name() + " started working on " + r.getTitle() + ".", "/service-desk?request=" + r.getId(), Severity.INFO);
        audit.record("SERVICE_REQUEST_STARTED", "SERVICE_REQUEST", r.getNumber(), null);
        return get(id, actor);
    }

    @Transactional
    public ServiceRequestDto fulfill(Long id, String notes, Actor actor) {
        requireStaff(actor);
        ServiceRequest r = load(id, actor);
        if (r.getStatus() != Status.IN_FULFILLMENT && r.getStatus() != Status.APPROVED) {
            throw ApiException.conflict("Request " + r.getNumber() + " is " + r.getStatus());
        }
        Status previous = r.getStatus();
        r.setStatus(Status.FULFILLED);
        r.setFulfilledAt(clock.instant());
        r.setFulfillmentNotes(notes);
        record(r, actor, "STATUS_CHANGED", previous.name(), Status.FULFILLED.name(), notes);
        notifications.notifyUser(r.getRequester().getId(), "REQUEST_FULFILLED", r.getNumber() + " fulfilled",
                notes == null || notes.isBlank() ? r.getTitle() + " is complete." : notes, "/service-desk?request=" + r.getId(), Severity.SUCCESS);
        audit.record("SERVICE_REQUEST_FULFILLED", "SERVICE_REQUEST", r.getNumber(), null);
        return get(id, actor);
    }

    @Transactional
    public ServiceRequestDto cancel(Long id, String reason, Actor actor) {
        ServiceRequest r = load(id, actor);
        boolean requester = r.getRequester().getId().equals(actor.id());
        if (!requester && !actor.isStaff()) {
            throw ApiException.forbidden("Only the requester or support staff can cancel this request");
        }
        requireOpen(r);
        Status previous = r.getStatus();
        r.setStatus(Status.CANCELLED);
        r.setFulfillmentNotes("Cancelled: " + reason);
        approvalRepository.findFirstByTypeAndReferenceIdOrderByCreatedAtDesc(Approval.Type.SERVICE_REQUEST, r.getId())
                .filter(a -> a.getStatus() == Approval.Status.PENDING)
                .ifPresent(a -> approvals.cancel(a, "Request cancelled: " + reason, actor));
        record(r, actor, "STATUS_CHANGED", previous.name(), Status.CANCELLED.name(), reason);
        if (!requester) {
            notifications.notifyUser(r.getRequester().getId(), "REQUEST_UPDATED", r.getNumber() + " cancelled", reason,
                    "/service-desk?request=" + r.getId(), Severity.WARNING);
        }
        audit.record("SERVICE_REQUEST_CANCELLED", "SERVICE_REQUEST", r.getNumber(), Map.of("reason", reason));
        return get(id, actor);
    }

    // ------------------------------------------------------------------ comments & history

    @Transactional(readOnly = true)
    public List<RequestCommentDto> comments(Long id, Actor actor) {
        load(id, actor);
        List<ServiceRequestComment> list = actor.isStaff() || actor.role() == Role.AUDITOR
                ? comments.findByRequestIdOrderByCreatedAtAscIdAsc(id) : comments.findByRequestIdAndInternalFalseOrderByCreatedAtAscIdAsc(id);
        return list.stream().map(ServiceDeskService::commentDto).toList();
    }

    @Transactional
    public RequestCommentDto addComment(Long id, String body, boolean internal, Actor actor) {
        ServiceRequest r = load(id, actor);
        if (actor.role() == Role.AUDITOR) {
            throw ApiException.forbidden("Auditors have read-only access");
        }
        if (internal && !actor.isStaff()) {
            throw ApiException.forbidden("Only support staff can add internal notes");
        }
        ServiceRequestComment c = new ServiceRequestComment();
        c.setRequest(r);
        c.setAuthor(actor.isSystem() ? null : users.getReferenceById(actor.id()));
        c.setBody(body.trim());
        c.setInternal(internal);
        c.setSystemGenerated(actor.isSystem());
        comments.save(c);
        record(r, actor, "COMMENTED", null, null, internal ? "Added an internal note" : "Added a comment");
        String link = "/service-desk?request=" + r.getId();
        boolean fromRequester = r.getRequester().getId().equals(actor.id());
        if (!internal && !fromRequester) {
            notifications.notifyUser(r.getRequester().getId(), "REQUEST_COMMENT", "Update on " + r.getNumber(), body, link, Severity.INFO);
        } else if (fromRequester && r.getAssignee() != null) {
            notifications.notifyUser(r.getAssignee().getId(), "REQUEST_COMMENT", "Requester replied on " + r.getNumber(), body, link, Severity.INFO);
        }
        return new RequestCommentDto(c.getId(), c.getBody(), c.isInternal(), c.isSystemGenerated(), actor.id(), actor.name(), c.getCreatedAt());
    }

    @Transactional(readOnly = true)
    public List<RequestHistoryDto> history(Long id, Actor actor) {
        load(id, actor);
        return history.findByRequestIdOrderByCreatedAtAscIdAsc(id).stream()
                .map(h -> new RequestHistoryDto(h.getCreatedAt(), h.getActorName(), h.getAction(), h.getFromValue(), h.getToValue(), h.getNote()))
                .toList();
    }

    // ------------------------------------------------------------------ approval handler

    @Override
    public Approval.Type type() {
        return Approval.Type.SERVICE_REQUEST;
    }

    @Override
    public void onDecision(Approval approval, boolean approved, Actor approver) {
        ServiceRequest r = requests.findWithDetailsById(approval.getReferenceId())
                .orElseThrow(() -> ApiException.notFound("Service request", approval.getReferenceId()));
        if (r.getStatus() != Status.PENDING_APPROVAL) {
            throw ApiException.conflict("Request " + r.getNumber() + " is no longer awaiting approval");
        }
        if (approved) {
            r.setStatus(Status.APPROVED);
            record(r, approver, "APPROVED", Status.PENDING_APPROVAL.name(), Status.APPROVED.name(), approval.getDecisionComment());
            startClock(r, clock.instant());
        } else {
            r.setStatus(Status.REJECTED);
            r.setFulfillmentNotes("Rejected: " + approval.getDecisionComment());
            record(r, approver, "REJECTED", Status.PENDING_APPROVAL.name(), Status.REJECTED.name(), approval.getDecisionComment());
        }
    }

    // ------------------------------------------------------------------ helpers

    private void startClock(ServiceRequest r, Instant now) {
        r.setDueAt(now.plus(Duration.ofHours(r.getCatalogItem().getFulfillmentHours())));
        if (r.getAssignee() == null && r.getDepartment() != null) {
            // Release 3: the request's team (the fulfilment department's default team) first, then the department's agents.
            Team team = r.getTeam() != null && r.getTeam().isActive() ? r.getTeam() : teamRouting.defaultTeam(r.getDepartment()).orElse(null);
            User agent = team == null ? null : teamRouting.leastLoadedMember(team).orElse(null);
            if (agent == null) {
                agent = assignment.leastLoadedAgent(r.getDepartment().getId()).orElse(null);
                if (agent != null && team != null && !teamRouting.isMember(team, agent)) {
                    team = teamRouting.resolve(null, r.getDepartment(), agent);
                }
            }
            r.setTeam(team);
            if (agent != null) {
                r.setAssignee(agent);
                record(r, Actor.SYSTEM, "ASSIGNED", null, agent.getFullName(), "Routed to " + (team == null ? r.getDepartment().getName()
                        : r.getDepartment().getName() + " / " + team.getName()));
                notifications.notifyUser(agent.getId(), "REQUEST_ASSIGNED", "Request assigned: " + r.getNumber(), r.getTitle(),
                        "/service-desk?request=" + r.getId(), Severity.INFO);
            }
        }
    }

    private void record(ServiceRequest r, Actor actor, String action, String from, String to, String note) {
        ServiceRequestHistory h = new ServiceRequestHistory();
        h.setRequestId(r.getId());
        h.setActorId(actor.id());
        h.setActorName(actor.name());
        h.setAction(action);
        h.setFromValue(truncate(from, 200));
        h.setToValue(truncate(to, 200));
        h.setNote(truncate(note, 1000));
        history.save(h);
    }

    private Specification<ServiceRequest> specification(RequestFilter f, Actor actor, boolean withStatus) {
        String effectiveScope = actor.isStaff() || actor.role() == Role.AUDITOR ? (f.scope() == null ? "all" : f.scope()) : "mine";
        Long departmentId = "department".equals(effectiveScope)
                ? users.findWithDepartmentById(actor.id()).map(User::getDepartment).map(Department::getId).orElse(-1L) : null;
        String category = f.categoryCode() == null || f.categoryCode().isBlank() ? null : f.categoryCode().trim().toUpperCase(Locale.ROOT);
        return (root, query, cb) -> {
            List<Predicate> p = new ArrayList<>();
            switch (effectiveScope) {
                case "mine" -> p.add(cb.equal(root.get("requester").get("id"), actor.id()));
                case "assigned" -> p.add(cb.equal(root.get("assignee").get("id"), actor.id()));
                case "department" -> p.add(cb.equal(root.get("department").get("id"), departmentId));
                default -> { }
            }
            if (withStatus && f.status() != null) {
                p.add(cb.equal(root.get("status"), f.status()));
            }
            if (category != null) {
                p.add(cb.equal(root.get("catalogItem").get("category").get("code"), category));
            }
            if (f.priority() != null) {
                p.add(cb.equal(root.get("priority"), f.priority()));
            }
            if (f.search() != null && !f.search().isBlank()) {
                String like = "%" + f.search().trim().toLowerCase(Locale.ROOT).replace("%", "\\%").replace("_", "\\_") + "%";
                p.add(cb.or(cb.like(cb.lower(root.get("title")), like, '\\'), cb.like(cb.lower(root.get("number")), like, '\\')));
            }
            return cb.and(p.toArray(new Predicate[0]));
        };
    }

    private static Specification<ServiceRequest> statusIn(Set<Status> statuses) {
        return (root, query, cb) -> root.get("status").in(statuses);
    }

    private Map<Long, Long> commentCounts(List<Long> ids, Actor actor) {
        Map<Long, Long> counts = new HashMap<>();
        if (ids.isEmpty()) {
            return counts;
        }
        boolean all = actor.isStaff() || actor.role() == Role.AUDITOR;
        for (Object[] row : all ? comments.countAll(ids) : comments.countPublic(ids)) {
            counts.put((Long) row[0], (Long) row[1]);
        }
        return counts;
    }

    /** Links the staged uploads named by file fields to the new request (they were validated as the submitter's own). */
    private void linkFiles(List<FormField> fields, Map<String, Object> formData, ServiceRequest r, Actor actor) {
        for (FormField f : fields) {
            if (!"file".equals(f.type()) || !(formData.get(f.key()) instanceof List<?> files) || files.isEmpty()) {
                continue;
            }
            List<Long> ids = files.stream().map(x -> x instanceof Map<?, ?> m ? ((Number) m.get("id")).longValue() : null)
                    .filter(java.util.Objects::nonNull).toList();
            if (attachments.link(ids, r.getId(), f.key(), actor.id()) != ids.size()) {
                throw ApiException.conflict("A file of '" + f.label() + "' was used by another request meanwhile; upload it again");
            }
        }
    }

    private ServiceRequest load(Long id, Actor actor) {
        ServiceRequest r = requests.findWithDetailsById(id).orElseThrow(() -> ApiException.notFound("Service request", id));
        if (!actor.isStaff() && actor.role() != Role.AUDITOR && !r.getRequester().getId().equals(actor.id())) {
            throw ApiException.notFound("Service request", id);
        }
        return r;
    }

    private static void requireOpen(ServiceRequest r) {
        if (!OPEN_STATUSES.contains(r.getStatus())) {
            throw ApiException.conflict("Request " + r.getNumber() + " is " + r.getStatus());
        }
    }

    private static void requireStaff(Actor actor) {
        if (!actor.isStaff()) {
            throw ApiException.forbidden("Only support staff can perform this action");
        }
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max - 1) + "…";
    }

    private static RequestCommentDto commentDto(ServiceRequestComment c) {
        return new RequestCommentDto(c.getId(), c.getBody(), c.isInternal(), c.isSystemGenerated(),
                c.getAuthor() == null ? null : c.getAuthor().getId(),
                c.getAuthor() == null ? "SA Automation" : c.getAuthor().getFullName(), c.getCreatedAt());
    }

    static CatalogItemDto dto(CatalogItem i, boolean canSubmit) {
        com.nexus360.form.FormDefinition f = i.getFormDefinition();
        return new CatalogItemDto(i.getId(), i.getCategory().getId(), i.getCategory().getName(), i.getName(), i.getDescription(),
                i.isRequiresApproval(), i.getApproverRole(), i.getFulfillmentHours(), i.getFulfillmentDepartment().getId(),
                i.getFulfillmentDepartment().getName(), i.getFormFields(), i.isActive(), f == null ? null : f.getId(), f == null ? null : f.getCode(),
                f == null ? null : f.getCurrentVersion(), i.getAllowedRoles() == null ? List.of() : i.getAllowedRoles(),
                i.getAllowedDepartmentIds() == null ? List.of() : i.getAllowedDepartmentIds(), canSubmit);
    }

    static ServiceRequestDto dto(ServiceRequest r, ApprovalDto approval, long commentCount) {
        return new ServiceRequestDto(r.getId(), r.getNumber(), r.getCatalogItem().getId(), r.getCatalogItem().getName(),
                r.getCatalogItem().getCategory().getName(), r.getTitle(), r.getDetails(), r.getFormData(), r.getStatus(),
                r.getRequester().getId(), r.getRequester().getFullName(),
                r.getDepartment() == null ? null : r.getDepartment().getId(), r.getDepartment() == null ? null : r.getDepartment().getName(),
                r.getAssignee() == null ? null : r.getAssignee().getId(), r.getAssignee() == null ? null : r.getAssignee().getFullName(),
                r.getDueAt(), r.getFulfilledAt(), r.getFulfillmentNotes(), r.getCreatedAt(), r.getUpdatedAt(), approval, r.isDemo(),
                r.getPriority(), r.getCatalogItem().getCategory().getCode(), commentCount,
                r.getTeam() == null ? null : r.getTeam().getId(), r.getTeam() == null ? null : r.getTeam().getName(), r.getFormDefinitionId(),
                r.getFormVersion());
    }
}
