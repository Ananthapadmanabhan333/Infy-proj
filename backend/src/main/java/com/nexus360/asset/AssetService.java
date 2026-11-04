package com.nexus360.asset;

import com.nexus360.admin.SettingsService;
import com.nexus360.asset.AssetDtos.AssetDetail;
import com.nexus360.asset.AssetDtos.AssetRequest;
import com.nexus360.asset.AssetDtos.AssetSummary;
import com.nexus360.asset.AssetDtos.AssetSummaryStats;
import com.nexus360.asset.AssetDtos.AssignRequest;
import com.nexus360.asset.AssetDtos.AssignmentDto;
import com.nexus360.asset.AssetDtos.EventDto;
import com.nexus360.asset.AssetDtos.ExpiringItem;
import com.nexus360.asset.AssetDtos.MaintenanceDto;
import com.nexus360.asset.AssetDtos.MaintenanceRequest;
import com.nexus360.asset.AssetDtos.ReturnRequest;
import com.nexus360.asset.AssetDtos.SeatDto;
import com.nexus360.asset.AssetDtos.TransitionRequest;
import com.nexus360.audit.AuditService;
import com.nexus360.cmdb.CmdbService;
import com.nexus360.cmdb.ConfigurationItem;
import com.nexus360.cmdb.ConfigurationItemRepository;
import com.nexus360.common.ApiException;
import com.nexus360.common.PageResponse;
import com.nexus360.notification.Notification.Severity;
import com.nexus360.notification.NotificationService;
import com.nexus360.security.Actor;
import com.nexus360.user.Department;
import com.nexus360.user.DepartmentRepository;
import com.nexus360.user.User;
import com.nexus360.user.UserRepository;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Asset inventory: CRUD, the validated lifecycle ({@link Asset.Status#next()}), hardware assignment and licence seats with full
 * assignment history, maintenance records, lifecycle events, expiring warranties/licences, summary figures and the
 * "my assets" list of the employee portal. CSV import/export lives in {@link AssetCsvService}.
 */
@Service
@RequiredArgsConstructor
public class AssetService {

    static final Set<String> SORTS = Set.of("assetTag", "name", "assetType", "status", "createdAt", "updatedAt", "warrantyExpires", "purchaseDate");

    public record Filter(String search, List<Asset.Type> types, List<Asset.Status> statuses, String category, Long assignedToId, Long departmentId,
                         Long ciId, String warranty, String location) {}

    private final AssetRepository assets;
    private final UserRepository users;
    private final DepartmentRepository departments;
    private final ConfigurationItemRepository cis;
    private final CmdbService cmdb;
    private final NotificationService notifications;
    private final SettingsService settings;
    private final AuditService audit;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    // ------------------------------------------------------------------ reading

    @Transactional(readOnly = true)
    public PageResponse<AssetSummary> list(Filter f, Integer page, Integer size, String sort) {
        Page<Asset> result = assets.findAll(spec(f), PageResponse.pageable(page, size, sortOf(sort)));
        return PageResponse.of(result, summaries(result.getContent()));
    }

    /** All matching assets (export), ordered by tag; at most 10 000. */
    @Transactional(readOnly = true)
    public List<Asset> all(Filter f) {
        return assets.findAll(spec(f)).stream().sorted(Comparator.comparing(Asset::getAssetTag)).limit(10_000).toList();
    }

    @Transactional(readOnly = true)
    public AssetDetail get(Long id) {
        return detail(load(id));
    }

    @Transactional(readOnly = true)
    public List<AssignmentDto> assignments(Long id) {
        load(id);
        return jdbc.query("select x.id, x.user_id, u.full_name, x.assigned_at, b.full_name, x.returned_at, r.full_name, x.note from asset_assignments x "
                + "join users u on u.id = x.user_id left join users b on b.id = x.assigned_by_id left join users r on r.id = x.returned_by_id "
                + "where x.asset_id = ? order by x.assigned_at desc, x.id desc", (rs, n) -> new AssignmentDto(rs.getLong(1), rs.getLong(2), rs.getString(3),
                rs.getTimestamp(4).toInstant(), rs.getString(5), rs.getTimestamp(6) == null ? null : rs.getTimestamp(6).toInstant(), rs.getString(7),
                rs.getString(8)), id);
    }

    @Transactional(readOnly = true)
    public List<EventDto> events(Long id) {
        load(id);
        return jdbc.query("select id, event_type, from_value, to_value, note, actor_name, created_at from asset_events where asset_id = ? "
                + "order by created_at desc, id desc limit 500", (rs, n) -> new EventDto(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getString(5), rs.getString(6), rs.getTimestamp(7).toInstant()), id);
    }

    @Transactional(readOnly = true)
    public List<MaintenanceDto> maintenance(Long id) {
        load(id);
        return maintenanceOf(id);
    }

    /** Warranties and licences ending within {@code days} (default setting asset.warranty.warning_days) or already ended, active assets only. */
    @Transactional(readOnly = true)
    public List<ExpiringItem> expiring(Integer days) {
        int window = days == null ? warningDays() : days;
        if (window < 1 || window > 365) {
            throw ApiException.badRequest("days must be between 1 and 365");
        }
        LocalDate today = LocalDate.now(clock);
        LocalDate until = today.plusDays(window);
        List<Asset> list = assets.findAll((root, q, cb) -> cb.and(root.get("status").in(Asset.Status.IN_STOCK, Asset.Status.IN_USE, Asset.Status.IN_REPAIR),
                cb.or(cb.lessThanOrEqualTo(root.get("warrantyExpires"), until), cb.lessThanOrEqualTo(root.get("licenseExpires"), until))));
        Map<Long, AssetSummary> byId = new HashMap<>();
        summaries(list).forEach(s -> byId.put(s.id(), s));
        List<ExpiringItem> out = new ArrayList<>();
        for (Asset a : list) {
            if (a.getWarrantyExpires() != null && !a.getWarrantyExpires().isAfter(until)) {
                long left = ChronoUnit.DAYS.between(today, a.getWarrantyExpires());
                out.add(new ExpiringItem(byId.get(a.getId()), "WARRANTY", a.getWarrantyExpires(), left, left < 0));
            }
            if (a.getLicenseExpires() != null && !a.getLicenseExpires().isAfter(until)) {
                long left = ChronoUnit.DAYS.between(today, a.getLicenseExpires());
                out.add(new ExpiringItem(byId.get(a.getId()), "LICENSE", a.getLicenseExpires(), left, left < 0));
            }
        }
        out.sort(Comparator.comparing(ExpiringItem::expiresOn).thenComparing(i -> i.asset().assetTag()));
        return out;
    }

    @Transactional(readOnly = true)
    public AssetSummaryStats stats() {
        Map<String, Long> byStatus = new LinkedHashMap<>();
        for (Asset.Status s : Asset.Status.values()) {
            byStatus.put(s.name(), 0L);
        }
        Map<String, Long> byType = new LinkedHashMap<>();
        jdbc.query("select status, count(*) from assets group by status", rs -> {
            byStatus.put(rs.getString(1), rs.getLong(2));
        });
        jdbc.query("select asset_type, count(*) from assets group by asset_type order by asset_type", rs -> {
            byType.put(rs.getString(1), rs.getLong(2));
        });
        long total = byStatus.values().stream().mapToLong(Long::longValue).sum();
        long licences = byType.getOrDefault("SOFTWARE_LICENSE", 0L);
        Long seats = jdbc.queryForObject("select coalesce(sum(license_seats), 0) from assets where asset_type = 'SOFTWARE_LICENSE' and status not in "
                + "('RETIRED', 'DISPOSED')", Long.class);
        Long used = jdbc.queryForObject("select count(*) from asset_assignments x join assets a on a.id = x.asset_id where x.returned_at is null "
                + "and a.asset_type = 'SOFTWARE_LICENSE'", Long.class);
        List<ExpiringItem> expiring = expiring(null);
        BigDecimal cost = jdbc.queryForObject("select coalesce(sum(purchase_cost), 0) from assets where status <> 'DISPOSED'", BigDecimal.class);
        Long currencies = jdbc.queryForObject("select count(distinct currency) from assets where purchase_cost is not null and status <> 'DISPOSED'", Long.class);
        return new AssetSummaryStats(total, byStatus, byType, total - licences, licences, seats == null ? 0 : seats, used == null ? 0 : used,
                expiring.stream().filter(i -> !i.expired()).count(), expiring.stream().filter(ExpiringItem::expired).count(), warningDays(), cost,
                currencies != null && currencies > 1);
    }

    /** Assets the user holds (hardware) or has a licence seat of, for the employee portal. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> mine(Long userId, int limit) {
        return jdbc.queryForList("select a.id, a.asset_tag as \"assetTag\", a.name, a.asset_type as \"assetType\", a.status, a.manufacturer, a.model, "
                + "a.serial_number as \"serialNumber\", coalesce(a.warranty_expires, a.license_expires) as \"expiresOn\", x.assigned_at as \"assignedAt\" "
                + "from asset_assignments x join assets a on a.id = x.asset_id where x.user_id = ? and x.returned_at is null and a.status not in "
                + "('RETIRED', 'DISPOSED') order by x.assigned_at desc limit ?", userId, limit);
    }

    @Transactional(readOnly = true)
    public long mineCount(Long userId) {
        Long n = jdbc.queryForObject("select count(*) from asset_assignments x join assets a on a.id = x.asset_id where x.user_id = ? and x.returned_at is null "
                + "and a.status not in ('RETIRED', 'DISPOSED')", Long.class, userId);
        return n == null ? 0 : n;
    }

    // ------------------------------------------------------------------ writing

    @Transactional
    public AssetDetail create(AssetRequest r, Actor actor) {
        Asset a = createInternal(r, null, actor, "CREATED", null);
        audit.record("ASSET_CREATED", "ASSET", a.getAssetTag(), Map.of("name", a.getName(), "type", a.getAssetType()));
        return detail(a);
    }

    /** Creates an asset (also used by the CSV import with {@code event} IMPORTED and an initial {@code status}). */
    Asset createInternal(AssetRequest r, Asset.Status initialStatus, Actor actor, String event, String note) {
        Asset a = new Asset();
        String tag = r.assetTag() == null || r.assetTag().isBlank() ? null : r.assetTag().trim().toUpperCase(Locale.ROOT);
        if (tag != null && assets.findByAssetTagIgnoreCase(tag).isPresent()) {
            throw ApiException.conflict("Asset tag " + tag + " is already in use");
        }
        a.setAssetTag(tag == null ? "AST-" + assets.nextTag() : tag);
        a.setAssetType(r.assetType());
        a.setCreatedBy(actor.isSystem() ? null : users.getReferenceById(actor.id()));
        a.setCreatedAt(clock.instant());
        apply(a, r, true, actor);
        assets.saveAndFlush(a);
        event(a.getId(), event, null, a.getStatus().name(), note == null ? a.getAssetType() + " " + a.getName() : note, actor);
        if (a.getCi() != null) {
            cmdb.record(a.getCi().getId(), actor, "ASSET_LINKED", null, a.getAssetTag() + " " + a.getName(), null);
        }
        if (r.assignedToId() != null) {
            if (a.getAssetType().isLicense()) {
                throw ApiException.invalidField("assignedToId", "Assign licence seats with POST /assets/{id}/assign");
            }
            assignInternal(a, r.assignedToId(), null, actor);
        }
        if (initialStatus != null && initialStatus != a.getStatus()) {
            if (initialStatus == Asset.Status.DISPOSED) {
                throw ApiException.invalidField("status", "A new asset cannot be DISPOSED");
            }
            if (initialStatus == Asset.Status.IN_USE && a.getAssetType().personal() && a.getAssignedTo() == null) {
                throw ApiException.invalidField("status", "Hardware IN_USE needs a holder");
            }
            Asset.Status from = a.getStatus();
            a.setStatus(initialStatus);
            if (initialStatus == Asset.Status.RETIRED) {
                a.setRetiredAt(clock.instant());
            }
            event(a.getId(), "STATUS_CHANGED", from.name(), initialStatus.name(), "Initial status", actor);
        }
        return a;
    }

    @Transactional
    public AssetDetail update(Long id, AssetRequest r, Actor actor) {
        Asset a = load(id);
        updateInternal(a, r, actor);
        audit.record("ASSET_UPDATED", "ASSET", a.getAssetTag(), Map.of("name", a.getName()));
        return detail(a);
    }

    void updateInternal(Asset a, AssetRequest r, Actor actor) {
        if (a.getStatus() == Asset.Status.DISPOSED) {
            throw ApiException.conflict(a.getAssetTag() + " is disposed and can no longer be edited");
        }
        if (r.assetType() != a.getAssetType() && (r.assetType().isLicense() || a.getAssetType().isLicense())) {
            throw ApiException.invalidField("assetType", "A licence cannot become hardware (or the other way round); create a new asset");
        }
        if (r.assetTag() != null && !r.assetTag().isBlank() && !r.assetTag().trim().equalsIgnoreCase(a.getAssetTag())) {
            throw ApiException.invalidField("assetTag", "The asset tag cannot change");
        }
        ConfigurationItem ciBefore = a.getCi();
        List<String> changed = changedFields(a, r);
        a.setAssetType(r.assetType());
        apply(a, r, false, actor);
        a.setUpdatedAt(clock.instant());
        assets.saveAndFlush(a);
        if (!changed.isEmpty()) {
            event(a.getId(), "UPDATED", null, null, "Changed: " + String.join(", ", changed), actor);
        }
        if (!Objects.equals(ciBefore == null ? null : ciBefore.getId(), a.getCi() == null ? null : a.getCi().getId())) {
            if (ciBefore != null) {
                cmdb.record(ciBefore.getId(), actor, "ASSET_UNLINKED", a.getAssetTag() + " " + a.getName(), null, null);
            }
            if (a.getCi() != null) {
                cmdb.record(a.getCi().getId(), actor, "ASSET_LINKED", null, a.getAssetTag() + " " + a.getName(), null);
            }
            event(a.getId(), "CI_LINKED", ciBefore == null ? null : ciBefore.getName(), a.getCi() == null ? null : a.getCi().getName(), null, actor);
        }
    }

    @Transactional
    public AssetDetail transition(Long id, TransitionRequest r, Actor actor) {
        Asset a = load(id);
        transitionInternal(a, r.status(), r.note(), r.assigneeId(), actor);
        return detail(a);
    }

    void transitionInternal(Asset a, Asset.Status to, String note, Long assigneeId, Actor actor) {
        Asset.Status from = a.getStatus();
        if (from == to) {
            throw ApiException.conflict(a.getAssetTag() + " is already " + to);
        }
        if (!from.next().contains(to)) {
            throw ApiException.conflict("An asset cannot move from " + from + " to " + to + " (allowed: " + from.next() + ")");
        }
        boolean licence = a.getAssetType().isLicense();
        if (to == Asset.Status.DISPOSED && (note == null || note.isBlank())) {
            throw ApiException.invalidField("note", "Say how the asset was disposed of");
        }
        if (to == Asset.Status.IN_USE && !licence) {
            if (assigneeId != null) {
                if (a.getAssignedTo() == null || !a.getAssignedTo().getId().equals(assigneeId)) {
                    a.setStatus(to);
                    assignInternal(a, assigneeId, note, actor);
                    event(a.getId(), "STATUS_CHANGED", from.name(), to.name(), note, actor);
                    audit.record("ASSET_STATUS_CHANGED", "ASSET", a.getAssetTag(), Map.of("from", from, "to", to));
                    return;
                }
            } else if (a.getAssignedTo() == null && a.getAssetType().personal()) {
                throw ApiException.invalidField("assigneeId", "Assign the asset to someone to put it in use");
            }
        }
        if (to == Asset.Status.IN_STOCK || to == Asset.Status.RETIRED || to == Asset.Status.DISPOSED) {
            releaseAll(a, actor, "Released: asset moved to " + to);
        }
        a.setStatus(to);
        Instant now = clock.instant();
        if (to == Asset.Status.RETIRED) {
            a.setRetiredAt(now);
        } else if (to == Asset.Status.DISPOSED) {
            a.setDisposedAt(now);
        } else if (from == Asset.Status.RETIRED) {
            a.setRetiredAt(null);
        }
        a.setUpdatedAt(now);
        event(a.getId(), "STATUS_CHANGED", from.name(), to.name(), note, actor);
        audit.record("ASSET_STATUS_CHANGED", "ASSET", a.getAssetTag(), Map.of("from", from, "to", to));
    }

    @Transactional
    public AssetDetail assign(Long id, AssignRequest r, Actor actor) {
        Asset a = load(id);
        assignInternal(a, r.userId(), r.note(), actor);
        audit.record("ASSET_ASSIGNED", "ASSET", a.getAssetTag(), Map.of("userId", r.userId()));
        return detail(a);
    }

    void assignInternal(Asset a, Long userId, String note, Actor actor) {
        if (!a.getStatus().assignable()) {
            throw ApiException.conflict(a.getAssetTag() + " is " + a.getStatus() + " and cannot be assigned");
        }
        User user = users.findById(userId).filter(User::isActive).orElseThrow(() -> ApiException.invalidField("userId", "The user must be an active user"));
        Instant now = clock.instant();
        if (a.getAssetType().isLicense()) {
            int used = seatsUsed(a.getId());
            if (openAssignment(a.getId(), user.getId()) != null) {
                throw ApiException.conflict(user.getFullName() + " already has a seat of " + a.getName());
            }
            if (used >= a.getLicenseSeats()) {
                throw ApiException.conflict("All " + a.getLicenseSeats() + " seats of " + a.getName() + " are in use");
            }
        } else {
            if (a.getAssignedTo() != null && a.getAssignedTo().getId().equals(user.getId())) {
                throw ApiException.conflict(a.getAssetTag() + " is already assigned to " + user.getFullName());
            }
            if (a.getAssignedTo() != null) {
                closeAssignment(a, a.getAssignedTo(), actor, "Reassigned to " + user.getFullName());
            }
            a.setAssignedTo(user);
            a.setAssignedAt(now);
        }
        jdbc.update("insert into asset_assignments (asset_id, user_id, assigned_by_id, assigned_at, note) values (?, ?, ?, ?, ?)", a.getId(), user.getId(),
                actor.isSystem() ? null : actor.id(), Timestamp.from(now), truncate(note, 500));
        Asset.Status from = a.getStatus();
        if (from == Asset.Status.IN_STOCK) {
            a.setStatus(Asset.Status.IN_USE);
        }
        a.setUpdatedAt(now);
        event(a.getId(), "ASSIGNED", null, user.getFullName(), note, actor);
        if (from != a.getStatus()) {
            event(a.getId(), "STATUS_CHANGED", from.name(), a.getStatus().name(), "Assigned", actor);
        }
        if (!Objects.equals(actor.id(), user.getId())) {
            notifications.notifyUser(user.getId(), "ASSET_ASSIGNED", (a.getAssetType().isLicense() ? "Licence seat assigned: " : "Asset assigned: ")
                    + a.getName(), a.getAssetTag() + " (" + a.getAssetType() + ") is now assigned to you" + (note == null || note.isBlank() ? "." : ": " + note),
                    "/portal", Severity.INFO);
        }
    }

    @Transactional
    public AssetDetail returnAsset(Long id, ReturnRequest r, Actor actor) {
        Asset a = load(id);
        User holder;
        if (a.getAssetType().isLicense()) {
            if (r.userId() == null) {
                throw ApiException.invalidField("userId", "Say whose licence seat to release");
            }
            holder = users.findById(r.userId()).orElseThrow(() -> ApiException.invalidField("userId", "User does not exist"));
            if (openAssignment(a.getId(), holder.getId()) == null) {
                throw ApiException.conflict(holder.getFullName() + " has no seat of " + a.getName());
            }
        } else {
            holder = a.getAssignedTo();
            if (holder == null) {
                throw ApiException.conflict(a.getAssetTag() + " is not assigned to anyone");
            }
            if (r.userId() != null && !r.userId().equals(holder.getId())) {
                throw ApiException.invalidField("userId", a.getAssetTag() + " is assigned to " + holder.getFullName());
            }
        }
        closeAssignment(a, holder, actor, r.note());
        if (!a.getAssetType().isLicense()) {
            a.setAssignedTo(null);
            a.setAssignedAt(null);
        }
        if (a.getStatus() == Asset.Status.IN_USE && (!a.getAssetType().isLicense() || seatsUsed(a.getId()) == 0)) {
            a.setStatus(Asset.Status.IN_STOCK);
            event(a.getId(), "STATUS_CHANGED", Asset.Status.IN_USE.name(), Asset.Status.IN_STOCK.name(), "Returned", actor);
        }
        a.setUpdatedAt(clock.instant());
        audit.record("ASSET_RETURNED", "ASSET", a.getAssetTag(), Map.of("userId", holder.getId()));
        return detail(a);
    }

    @Transactional
    public MaintenanceDto addMaintenance(Long id, MaintenanceRequest r, Actor actor) {
        Asset a = load(id);
        if (a.getStatus() == Asset.Status.DISPOSED) {
            throw ApiException.conflict(a.getAssetTag() + " is disposed");
        }
        if (r.nextDue() != null && r.nextDue().isBefore(r.performedOn())) {
            throw ApiException.invalidField("nextDue", "The next due date must be on or after the maintenance date");
        }
        String status = r.status() == null ? "COMPLETED" : r.status();
        if ("COMPLETED".equals(status) && r.performedOn().isAfter(LocalDate.now(clock))) {
            throw ApiException.invalidField("performedOn", "Completed maintenance cannot be in the future (use status SCHEDULED)");
        }
        Long mid = jdbc.queryForObject("insert into asset_maintenance (asset_id, maintenance_type, status, description, vendor, cost, performed_on, next_due, "
                + "created_by_id, created_by_name, is_demo, created_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, false, ?) returning id", Long.class, id,
                r.maintenanceType(), status, r.description().trim(), blankToNull(r.vendor()), r.cost(), java.sql.Date.valueOf(r.performedOn()),
                r.nextDue() == null ? null : java.sql.Date.valueOf(r.nextDue()), actor.isSystem() ? null : actor.id(), actor.name(),
                Timestamp.from(clock.instant()));
        event(id, "MAINTENANCE", null, r.maintenanceType() + " " + status, r.description(), actor);
        audit.record("ASSET_MAINTENANCE_RECORDED", "ASSET", a.getAssetTag(), Map.of("type", r.maintenanceType(), "status", status));
        return maintenanceOf(id).stream().filter(m -> m.id().equals(mid)).findFirst().orElseThrow();
    }

    // ------------------------------------------------------------------ helpers

    private void apply(Asset a, AssetRequest r, boolean creating, Actor actor) {
        a.setName(r.name().trim());
        a.setManufacturer(blankToNull(r.manufacturer()));
        a.setModel(blankToNull(r.model()));
        String serial = blankToNull(r.serialNumber());
        if (serial != null) {
            assets.findBySerialIgnoreCase(serial).filter(other -> !other.getId().equals(a.getId())).ifPresent(other -> {
                throw ApiException.conflict("Serial number " + serial + " already belongs to " + other.getAssetTag());
            });
        }
        a.setSerialNumber(serial);
        if (r.departmentId() != null) {
            a.setDepartment(r.departmentId() == 0L ? null : departments.findById(r.departmentId())
                    .orElseThrow(() -> ApiException.invalidField("departmentId", "Department does not exist")));
        }
        a.setLocation(blankToNull(r.location()));
        a.setVendor(blankToNull(r.vendor()));
        a.setPurchaseOrder(blankToNull(r.purchaseOrder()));
        a.setPurchaseCost(r.purchaseCost());
        if (r.currency() != null || creating) {
            a.setCurrency(r.currency() == null ? "INR" : r.currency().toUpperCase(Locale.ROOT));
        }
        a.setPurchaseDate(r.purchaseDate());
        if (r.purchaseDate() != null && r.purchaseDate().isAfter(LocalDate.now(clock).plusDays(1))) {
            throw ApiException.invalidField("purchaseDate", "The purchase date cannot be in the future");
        }
        a.setWarrantyExpires(r.warrantyExpires());
        if (r.warrantyExpires() != null && r.purchaseDate() != null && r.warrantyExpires().isBefore(r.purchaseDate())) {
            throw ApiException.invalidField("warrantyExpires", "The warranty cannot end before the purchase date");
        }
        if (a.getAssetType().isLicense()) {
            if (r.licenseSeats() == null) {
                throw ApiException.invalidField("licenseSeats", "A software licence needs the number of seats");
            }
            if (!creating && r.licenseSeats() < seatsUsed(a.getId())) {
                throw ApiException.conflict(seatsUsed(a.getId()) + " seats are assigned; release seats before reducing the licence to " + r.licenseSeats());
            }
            a.setLicenseSeats(r.licenseSeats());
            a.setLicenseExpires(r.licenseExpires());
        } else {
            if (r.licenseSeats() != null) {
                throw ApiException.invalidField("licenseSeats", "Only software licences have seats");
            }
            a.setLicenseSeats(null);
            a.setLicenseExpires(null);
        }
        if (r.ciId() != null) {
            a.setCi(r.ciId() == 0L ? null : cis.findById(r.ciId()).orElseThrow(() -> ApiException.invalidField("ciId", "Configuration item does not exist")));
        }
        a.setNotes(blankToNull(r.notes()));
    }

    private static List<String> changedFields(Asset a, AssetRequest r) {
        List<String> out = new ArrayList<>();
        diff(out, "name", a.getName(), r.name() == null ? null : r.name().trim());
        diff(out, "assetType", a.getAssetType(), r.assetType());
        diff(out, "manufacturer", a.getManufacturer(), blankToNull(r.manufacturer()));
        diff(out, "model", a.getModel(), blankToNull(r.model()));
        diff(out, "serialNumber", a.getSerialNumber(), blankToNull(r.serialNumber()));
        diff(out, "location", a.getLocation(), blankToNull(r.location()));
        diff(out, "vendor", a.getVendor(), blankToNull(r.vendor()));
        diff(out, "purchaseOrder", a.getPurchaseOrder(), blankToNull(r.purchaseOrder()));
        if (a.getPurchaseCost() == null ? r.purchaseCost() != null : r.purchaseCost() == null || a.getPurchaseCost().compareTo(r.purchaseCost()) != 0) {
            out.add("purchaseCost");
        }
        diff(out, "purchaseDate", a.getPurchaseDate(), r.purchaseDate());
        diff(out, "warrantyExpires", a.getWarrantyExpires(), r.warrantyExpires());
        diff(out, "licenseSeats", a.getLicenseSeats(), r.licenseSeats());
        diff(out, "licenseExpires", a.getLicenseExpires(), r.licenseExpires());
        diff(out, "notes", a.getNotes(), blankToNull(r.notes()));
        if (r.departmentId() != null) {
            diff(out, "department", a.getDepartment() == null ? 0L : a.getDepartment().getId(), r.departmentId());
        }
        return out;
    }

    private static void diff(List<String> out, String field, Object a, Object b) {
        if (!Objects.equals(a, b)) {
            out.add(field);
        }
    }

    private void releaseAll(Asset a, Actor actor, String note) {
        if (a.getAssetType().isLicense()) {
            List<Long> holders = jdbc.queryForList("select user_id from asset_assignments where asset_id = ? and returned_at is null", Long.class, a.getId());
            for (Long u : holders) {
                closeAssignment(a, users.getReferenceById(u), actor, note);
            }
        } else if (a.getAssignedTo() != null) {
            closeAssignment(a, a.getAssignedTo(), actor, note);
            a.setAssignedTo(null);
            a.setAssignedAt(null);
        }
    }

    private void closeAssignment(Asset a, User holder, Actor actor, String note) {
        int n = jdbc.update("update asset_assignments set returned_at = ?, returned_by_id = ?, note = coalesce(?, note) where asset_id = ? and user_id = ? "
                + "and returned_at is null", Timestamp.from(clock.instant()), actor.isSystem() ? null : actor.id(), truncate(note, 500), a.getId(), holder.getId());
        if (n > 0) {
            String name = jdbc.queryForObject("select full_name from users where id = ?", String.class, holder.getId());
            event(a.getId(), "RETURNED", name, null, note, actor);
        }
    }

    private Long openAssignment(Long assetId, Long userId) {
        List<Long> ids = jdbc.queryForList("select id from asset_assignments where asset_id = ? and user_id = ? and returned_at is null", Long.class, assetId, userId);
        return ids.isEmpty() ? null : ids.get(0);
    }

    int seatsUsed(Long assetId) {
        Integer n = jdbc.queryForObject("select count(*) from asset_assignments where asset_id = ? and returned_at is null", Integer.class, assetId);
        return n == null ? 0 : n;
    }

    void event(Long assetId, String type, String from, String to, String note, Actor actor) {
        jdbc.update("insert into asset_events (asset_id, event_type, from_value, to_value, note, actor_id, actor_name, created_at) values (?, ?, ?, ?, ?, ?, ?, ?)",
                assetId, type, truncate(from, 200), truncate(to, 200), truncate(note, 1000), actor.isSystem() ? null : actor.id(), actor.name(),
                Timestamp.from(clock.instant()));
    }

    private List<MaintenanceDto> maintenanceOf(Long id) {
        return jdbc.query("select id, maintenance_type, status, description, vendor, cost, performed_on, next_due, created_by_name, created_at "
                + "from asset_maintenance where asset_id = ? order by performed_on desc, id desc", (rs, n) -> new MaintenanceDto(rs.getLong(1), rs.getString(2),
                rs.getString(3), rs.getString(4), rs.getString(5), rs.getBigDecimal(6), rs.getDate(7).toLocalDate(),
                rs.getDate(8) == null ? null : rs.getDate(8).toLocalDate(), rs.getString(9), rs.getTimestamp(10).toInstant()), id);
    }

    private AssetDetail detail(Asset a) {
        AssetSummary s = summaries(List.of(a)).get(0);
        List<SeatDto> seats = !a.getAssetType().isLicense() ? List.of() : jdbc.query("select x.id, x.user_id, u.full_name, u.email, x.assigned_at, "
                + "b.full_name from asset_assignments x join users u on u.id = x.user_id left join users b on b.id = x.assigned_by_id where x.asset_id = ? "
                + "and x.returned_at is null order by u.full_name", (rs, n) -> new SeatDto(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4),
                rs.getTimestamp(5).toInstant(), rs.getString(6)), a.getId());
        return new AssetDetail(s, a.getVendor(), a.getPurchaseOrder(), a.getPurchaseCost(), a.getCurrency(), a.getPurchaseDate(), a.getNotes(),
                a.getRetiredAt(), a.getDisposedAt(), seats, maintenanceOf(a.getId()), List.copyOf(a.getStatus().next()),
                a.getCreatedBy() == null ? null : a.getCreatedBy().getFullName());
    }

    List<AssetSummary> summaries(Collection<Asset> list) {
        Map<Long, Integer> seats = new HashMap<>();
        List<Long> licences = list.stream().filter(a -> a.getAssetType().isLicense()).map(Asset::getId).toList();
        if (!licences.isEmpty()) {
            jdbc.query("select asset_id, count(*) from asset_assignments where returned_at is null and asset_id in ("
                    + String.join(",", licences.stream().map(String::valueOf).toList()) + ") group by asset_id", rs -> {
                        seats.put(rs.getLong(1), rs.getInt(2));
                    });
        }
        LocalDate today = LocalDate.now(clock);
        int window = warningDays();
        return list.stream().map(a -> {
            LocalDate expiry = a.getAssetType().isLicense() ? a.getLicenseExpires() : a.getWarrantyExpires();
            String warranty = expiry == null ? "NONE" : expiry.isBefore(today) ? "EXPIRED" : !expiry.isAfter(today.plusDays(window)) ? "EXPIRING" : "ACTIVE";
            Department d = a.getDepartment();
            return new AssetSummary(a.getId(), a.getAssetTag(), a.getName(), a.getAssetType(), a.getAssetType().category(), a.getStatus(), a.getManufacturer(),
                    a.getModel(), a.getSerialNumber(), a.getAssignedTo() == null ? null : a.getAssignedTo().getId(),
                    a.getAssignedTo() == null ? null : a.getAssignedTo().getFullName(), a.getAssignedAt(), d == null ? null : d.getId(),
                    d == null ? null : d.getName(), a.getLocation(), a.getWarrantyExpires(), warranty, a.getLicenseSeats(), seats.getOrDefault(a.getId(), 0),
                    a.getLicenseExpires(), a.getCi() == null ? null : a.getCi().getId(), a.getCi() == null ? null : a.getCi().getName(), a.isDemo(),
                    a.getCreatedAt(), a.getUpdatedAt());
        }).toList();
    }

    private Specification<Asset> spec(Filter f) {
        return (root, query, cb) -> {
            List<Predicate> p = new ArrayList<>();
            if (f.types() != null && !f.types().isEmpty()) {
                p.add(root.get("assetType").in(f.types()));
            }
            if (f.statuses() != null && !f.statuses().isEmpty()) {
                p.add(root.get("status").in(f.statuses()));
            }
            if (f.category() != null && !f.category().isBlank()) {
                String c = f.category().trim().toUpperCase(Locale.ROOT);
                if (!c.equals("HARDWARE") && !c.equals("SOFTWARE")) {
                    throw ApiException.badRequest("category must be HARDWARE or SOFTWARE");
                }
                p.add(c.equals("SOFTWARE") ? cb.equal(root.get("assetType"), Asset.Type.SOFTWARE_LICENSE)
                        : cb.notEqual(root.get("assetType"), Asset.Type.SOFTWARE_LICENSE));
            }
            if (f.assignedToId() != null) {
                p.add(cb.equal(root.get("assignedTo").get("id"), f.assignedToId()));
            }
            if (f.departmentId() != null) {
                p.add(cb.equal(root.get("department").get("id"), f.departmentId()));
            }
            if (f.ciId() != null) {
                p.add(cb.equal(root.get("ci").get("id"), f.ciId()));
            }
            if (f.location() != null && !f.location().isBlank()) {
                p.add(cb.like(cb.lower(root.get("location")), like(f.location()), '\\'));
            }
            if (f.warranty() != null && !f.warranty().isBlank()) {
                LocalDate today = LocalDate.now(clock);
                LocalDate until = today.plusDays(warningDays());
                switch (f.warranty().trim().toUpperCase(Locale.ROOT)) {
                    case "EXPIRED" -> p.add(cb.lessThan(root.get("warrantyExpires"), today));
                    case "EXPIRING" -> p.add(cb.between(root.get("warrantyExpires"), today, until));
                    case "ACTIVE" -> p.add(cb.greaterThan(root.get("warrantyExpires"), until));
                    case "NONE" -> p.add(cb.isNull(root.get("warrantyExpires")));
                    default -> throw ApiException.badRequest("warranty must be ACTIVE, EXPIRING, EXPIRED or NONE");
                }
            }
            if (f.search() != null && !f.search().isBlank()) {
                String like = like(f.search());
                var holder = root.join("assignedTo", JoinType.LEFT);
                p.add(cb.or(cb.like(cb.lower(root.get("assetTag")), like, '\\'), cb.like(cb.lower(root.get("name")), like, '\\'),
                        cb.like(cb.lower(root.get("serialNumber")), like, '\\'), cb.like(cb.lower(root.get("model")), like, '\\'),
                        cb.like(cb.lower(holder.get("fullName")), like, '\\')));
            }
            return cb.and(p.toArray(new Predicate[0]));
        };
    }

    private static String like(String text) {
        return "%" + text.trim().toLowerCase(Locale.ROOT).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }

    static Sort sortOf(String sort) {
        if (sort == null || sort.isBlank()) {
            return Sort.by(Sort.Direction.ASC, "assetTag");
        }
        String[] parts = sort.split(",");
        String field = parts[0].trim();
        if (!SORTS.contains(field)) {
            throw ApiException.badRequest("Cannot sort by '" + field + "'");
        }
        return Sort.by(parts.length > 1 && "desc".equalsIgnoreCase(parts[1].trim()) ? Sort.Direction.DESC : Sort.Direction.ASC, field);
    }

    int warningDays() {
        return Math.max(1, settings.getInt("asset.warranty.warning_days", 30));
    }

    Asset load(Long id) {
        return assets.findWithDetailsById(id).orElseThrow(() -> ApiException.notFound("Asset", id));
    }

    static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }

    private static String truncate(String v, int max) {
        return v == null || v.length() <= max ? v : v.substring(0, max);
    }
}
