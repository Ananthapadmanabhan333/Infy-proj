package com.nexus360.asset;

import com.nexus360.asset.AssetDtos.AssetRequest;
import com.nexus360.asset.AssetDtos.ImportError;
import com.nexus360.asset.AssetDtos.ImportResult;
import com.nexus360.asset.AssetDtos.ImportRow;
import com.nexus360.audit.AuditService;
import com.nexus360.cmdb.ConfigurationItem;
import com.nexus360.cmdb.ConfigurationItemRepository;
import com.nexus360.common.ApiException;
import com.nexus360.security.Actor;
import com.nexus360.user.Department;
import com.nexus360.user.DepartmentRepository;
import com.nexus360.user.User;
import com.nexus360.user.UserRepository;
import java.math.BigDecimal;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * CSV export (formula-safe, via {@code Csv}) and validated CSV import of assets. The import checks every row first (types,
 * enums, dates, numbers, sizes, uniqueness of tags and serial numbers within the file and against the inventory, users,
 * departments, CIs, lifecycle transitions of existing assets) and reports row-level errors. With {@code dryRun} nothing is
 * stored; otherwise all rows are stored in one transaction, and only when no row has an error. Rows with an existing asset tag
 * update that asset; other rows create assets (with the given tag or a generated one).
 */
@Service
public class AssetCsvService {

    public static final int MAX_ROWS = 1000;
    public static final int MAX_BYTES = 2 * 1024 * 1024;

    public static final List<String> COLUMNS = List.of("assetTag", "name", "assetType", "status", "manufacturer", "model", "serialNumber",
            "assignedToEmail", "department", "location", "vendor", "purchaseOrder", "purchaseCost", "currency", "purchaseDate", "warrantyExpires",
            "licenseSeats", "seatsUsed", "licenseExpires", "ciName", "notes");

    private final AssetService service;
    private final AssetRepository assets;
    private final UserRepository users;
    private final DepartmentRepository departments;
    private final ConfigurationItemRepository cis;
    private final AuditService audit;
    private final TransactionTemplate tx;

    public AssetCsvService(AssetService service, AssetRepository assets, UserRepository users, DepartmentRepository departments,
                           ConfigurationItemRepository cis, AuditService audit, PlatformTransactionManager transactions) {
        this.service = service;
        this.assets = assets;
        this.users = users;
        this.departments = departments;
        this.cis = cis;
        this.audit = audit;
        this.tx = new TransactionTemplate(transactions);
    }

    // ------------------------------------------------------------------ export

    public List<List<Object>> exportRows(AssetService.Filter filter) {
        return tx.execute(s -> {
            List<Asset> list = service.all(filter);
            Map<Long, Integer> seats = new HashMap<>();
            service.summaries(list).forEach(x -> seats.put(x.id(), x.seatsUsed()));
            List<List<Object>> rows = new ArrayList<>();
            for (Asset a : list) {
                rows.add(Arrays.asList(a.getAssetTag(), a.getName(), a.getAssetType().name(), a.getStatus().name(), a.getManufacturer(), a.getModel(),
                        a.getSerialNumber(), a.getAssignedTo() == null ? null : a.getAssignedTo().getEmail(),
                        a.getDepartment() == null ? null : a.getDepartment().getCode(), a.getLocation(), a.getVendor(), a.getPurchaseOrder(),
                        a.getPurchaseCost() == null ? null : a.getPurchaseCost().toPlainString(), a.getCurrency(), str(a.getPurchaseDate()),
                        str(a.getWarrantyExpires()), a.getLicenseSeats(), a.getAssetType().isLicense() ? seats.getOrDefault(a.getId(), 0) : null,
                        str(a.getLicenseExpires()), a.getCi() == null ? null : a.getCi().getName(), a.getNotes()));
            }
            return rows;
        });
    }

    // ------------------------------------------------------------------ import

    /** A validated row ready to be applied. */
    private record Parsed(int row, Asset existing, AssetRequest request, Asset.Status status, User assignee) {}

    public ImportResult importCsv(byte[] content, boolean dryRun, Actor actor) {
        if (content == null || content.length == 0) {
            throw ApiException.invalidField("file", "The file is empty");
        }
        if (content.length > MAX_BYTES) {
            throw ApiException.invalidField("file", "The file is larger than 2 MB");
        }
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(content)).toString();
        } catch (CharacterCodingException e) {
            throw ApiException.invalidField("file", "The file must be UTF-8 encoded CSV");
        }
        List<List<String>> table;
        try {
            table = parse(text);
        } catch (IllegalArgumentException e) {
            throw ApiException.invalidField("file", e.getMessage());
        }
        if (table.isEmpty()) {
            throw ApiException.invalidField("file", "The file has no header row");
        }
        ImportResult result = tx.execute(status -> {
            List<ImportError> errors = new ArrayList<>();
            Map<String, Integer> columns = header(table.get(0), errors);
            List<Parsed> parsed = new ArrayList<>();
            List<ImportRow> rows = new ArrayList<>();
            int dataRows = 0;
            if (errors.isEmpty()) {
                Set<String> tags = new HashSet<>();
                Set<String> serials = new HashSet<>();
                for (int i = 1; i < table.size(); i++) {
                    List<String> cells = table.get(i);
                    if (cells.stream().allMatch(c -> c == null || c.isBlank())) {
                        continue;
                    }
                    dataRows++;
                    if (dataRows > MAX_ROWS) {
                        errors.add(new ImportError(i + 1, null, "At most " + MAX_ROWS + " rows per import"));
                        break;
                    }
                    int before = errors.size();
                    Parsed p = validate(i + 1, cells, columns, tags, serials, errors);
                    if (p != null && errors.size() == before) {
                        parsed.add(p);
                    }
                    rows.add(new ImportRow(i + 1, p == null ? cell(cells, columns, "assettag") : p.existing() != null ? p.existing().getAssetTag()
                            : p.request().assetTag(), cell(cells, columns, "name"), cell(cells, columns, "assettype"),
                            p == null || p.status() == null ? cell(cells, columns, "status") : p.status().name(),
                            p == null ? null : p.existing() == null ? "CREATE" : "UPDATE"));
                }
            }
            int creates = (int) parsed.stream().filter(p -> p.existing() == null).count();
            int updates = parsed.size() - creates;
            if (dryRun || !errors.isEmpty()) {
                status.setRollbackOnly();
                return new ImportResult(dryRun, false, dataRows, parsed.size(), dryRun ? creates : 0, dryRun ? updates : 0, errors, rows);
            }
            for (Parsed p : parsed) {
                try {
                    apply(p, actor);
                } catch (ApiException e) {
                    errors.add(new ImportError(p.row(), e.getFieldErrors() == null || e.getFieldErrors().isEmpty() ? null
                            : e.getFieldErrors().get(0).field(), e.getMessage()));
                }
            }
            if (!errors.isEmpty()) {
                status.setRollbackOnly();
                return new ImportResult(false, false, dataRows, parsed.size() - errors.size(), 0, 0, errors, rows);
            }
            return new ImportResult(false, true, dataRows, parsed.size(), creates, updates, errors, rows);
        });
        audit.record("ASSETS_IMPORTED", "ASSET", null, Map.of("dryRun", dryRun, "applied", result.applied(), "rows", result.totalRows(),
                "created", result.created(), "updated", result.updated(), "errors", result.errors().size()));
        return result;
    }

    private void apply(Parsed p, Actor actor) {
        if (p.existing() == null) {
            service.createInternal(p.request(), p.status(), actor, "IMPORTED", "Imported from CSV");
            return;
        }
        Asset a = service.load(p.existing().getId());
        service.updateInternal(a, p.request(), actor);
        service.event(a.getId(), "IMPORTED", null, null, "Updated from CSV", actor);
        if (p.assignee() != null && !a.getAssetType().isLicense() && (a.getAssignedTo() == null || !a.getAssignedTo().getId().equals(p.assignee().getId()))
                && a.getStatus().assignable()) {
            service.assignInternal(a, p.assignee().getId(), "Imported from CSV", actor);
        }
        if (p.status() != null && p.status() != a.getStatus()) {
            service.transitionInternal(a, p.status(), p.status() == Asset.Status.DISPOSED ? "Disposed (CSV import)" : "CSV import",
                    p.assignee() == null ? null : p.assignee().getId(), actor);
        }
    }

    private Map<String, Integer> header(List<String> cells, List<ImportError> errors) {
        Map<String, String> known = new HashMap<>();
        COLUMNS.forEach(c -> known.put(c.toLowerCase(Locale.ROOT), c));
        Map<String, Integer> out = new LinkedHashMap<>();
        for (int i = 0; i < cells.size(); i++) {
            String raw = cells.get(i) == null ? "" : cells.get(i).trim();
            String key = raw.toLowerCase(Locale.ROOT);
            if (key.isEmpty()) {
                continue;
            }
            if (!known.containsKey(key)) {
                errors.add(new ImportError(1, raw, "Unknown column '" + raw + "'. Columns: " + String.join(", ", COLUMNS)));
            } else if (out.containsKey(key)) {
                errors.add(new ImportError(1, raw, "Column '" + raw + "' appears twice"));
            } else {
                out.put(key, i);
            }
        }
        for (String required : List.of("name", "assettype")) {
            if (errors.isEmpty() && !out.containsKey(required)) {
                errors.add(new ImportError(1, known.get(required), "Required column '" + known.get(required) + "' is missing"));
            }
        }
        return out;
    }

    private Parsed validate(int row, List<String> cells, Map<String, Integer> columns, Set<String> tags, Set<String> serials, List<ImportError> errors) {
        Row r = new Row(row, cells, columns, errors);
        String tag = r.text("assetTag", 40);
        Asset existing = null;
        if (tag != null) {
            if (!tag.matches("^[A-Za-z0-9][A-Za-z0-9-]{1,39}$")) {
                r.error("assetTag", "Asset tags use letters, digits and '-' (2-40 characters)");
            } else if (!tags.add(tag.toUpperCase(Locale.ROOT))) {
                r.error("assetTag", "Asset tag " + tag + " appears more than once in the file");
            } else {
                existing = assets.findByAssetTagIgnoreCase(tag).orElse(null);
            }
        }
        String name = r.text("name", 150);
        if (name == null || name.length() < 2) {
            r.error("name", "Name is required (2-150 characters)");
        }
        Asset.Type type = r.enumValue("assetType", Asset.Type.class);
        if (type == null && r.raw("assetType") == null) {
            r.error("assetType", "Asset type is required");
        }
        Asset.Status status = r.enumValue("status", Asset.Status.class);
        String manufacturer = r.text("manufacturer", 80);
        String model = r.text("model", 120);
        String serial = r.text("serialNumber", 120);
        if (serial != null) {
            if (!serials.add(serial.toLowerCase(Locale.ROOT))) {
                r.error("serialNumber", "Serial number " + serial + " appears more than once in the file");
            } else {
                Optional<Asset> owner = assets.findBySerialIgnoreCase(serial);
                Long existingId = existing == null ? null : existing.getId();
                if (owner.isPresent() && !owner.get().getId().equals(existingId)) {
                    r.error("serialNumber", "Serial number " + serial + " already belongs to " + owner.get().getAssetTag());
                }
            }
        }
        User assignee = null;
        String email = r.text("assignedToEmail", 160);
        if (email != null) {
            assignee = users.findByEmailIgnoreCase(email).filter(User::isActive).orElse(null);
            if (assignee == null) {
                r.error("assignedToEmail", "No active user with e-mail " + email);
            } else if (type != null && type.isLicense()) {
                r.error("assignedToEmail", "Licence seats are assigned in the application, not by import");
            }
        }
        Long departmentId = null;
        String dept = r.text("department", 120);
        if (dept != null) {
            Department d = departments.findByCodeIgnoreCase(dept).orElseGet(() -> departments.findAll().stream()
                    .filter(x -> x.getName().equalsIgnoreCase(dept)).findFirst().orElse(null));
            if (d == null) {
                r.error("department", "Unknown department " + dept);
            } else {
                departmentId = d.getId();
            }
        }
        String location = r.text("location", 160);
        String vendor = r.text("vendor", 120);
        String po = r.text("purchaseOrder", 60);
        BigDecimal cost = r.decimal("purchaseCost");
        String currency = r.text("currency", 3);
        if (currency != null && !currency.matches("^[A-Za-z]{3}$")) {
            r.error("currency", "Currency must be a three-letter code such as INR or USD");
        }
        LocalDate purchased = r.date("purchaseDate");
        LocalDate warranty = r.date("warrantyExpires");
        Integer seats = r.integer("licenseSeats", 1, 100_000);
        LocalDate licenceEnds = r.date("licenseExpires");
        if (type != null) {
            if (type.isLicense() && seats == null && r.raw("licenseSeats") == null && (existing == null || r.has("licenseSeats"))) {
                r.error("licenseSeats", "A software licence needs the number of seats");
            }
            if (!type.isLicense() && seats != null) {
                r.error("licenseSeats", "Only software licences have seats");
            }
            if (!type.isLicense() && licenceEnds != null) {
                r.error("licenseExpires", "Only software licences have a licence end date");
            }
        }
        if (warranty != null && purchased != null && warranty.isBefore(purchased)) {
            r.error("warrantyExpires", "The warranty cannot end before the purchase date");
        }
        Long ciId = null;
        String ciName = r.text("ciName", 120);
        if (ciName != null) {
            ConfigurationItem ci = cis.findByNameIgnoreCase(ciName).orElse(null);
            if (ci == null) {
                r.error("ciName", "No configuration item named " + ciName);
            } else {
                ciId = ci.getId();
            }
        }
        String notes = r.text("notes", 2000);
        if (existing != null) {
            if (existing.getStatus() == Asset.Status.DISPOSED) {
                r.error("assetTag", existing.getAssetTag() + " is disposed and cannot be updated");
            }
            if (type != null && type.isLicense() != existing.getAssetType().isLicense()) {
                r.error("assetType", "A licence cannot become hardware (or the other way round)");
            }
            if (status != null && status != existing.getStatus() && !existing.getStatus().next().contains(status)) {
                r.error("status", existing.getAssetTag() + " cannot move from " + existing.getStatus() + " to " + status);
            }
            if (existing.getAssetType().isLicense() && seats != null && seats < service.seatsUsed(existing.getId())) {
                r.error("licenseSeats", service.seatsUsed(existing.getId()) + " seats are assigned; cannot reduce to " + seats);
            }
        } else if (status == Asset.Status.DISPOSED) {
            r.error("status", "A new asset cannot be DISPOSED");
        }
        boolean hardware = type != null && !type.isLicense();
        if (hardware && type.personal() && status == Asset.Status.IN_USE && assignee == null && (existing == null || existing.getAssignedTo() == null)) {
            r.error("assignedToEmail", "Hardware IN_USE needs assignedToEmail");
        }
        if (hardware && assignee != null && status != null && status != Asset.Status.IN_USE) {
            r.error("status", "An assigned hardware asset is IN_USE (leave status empty or use IN_USE)");
        }
        if (r.failed()) {
            return null;
        }
        AssetRequest request;
        if (existing == null) {
            request = new AssetRequest(tag, name, type, manufacturer, model, serial, departmentId, location, vendor, po, cost, currency, purchased,
                    warranty, seats, licenceEnds, ciId, notes, hardware && assignee != null ? assignee.getId() : null);
        } else {
            // Columns missing from the file keep the asset's values; present but empty cells clear them.
            Asset e = existing;
            request = new AssetRequest(null, name, type, r.has("manufacturer") ? manufacturer : e.getManufacturer(),
                    r.has("model") ? model : e.getModel(), r.has("serialNumber") ? serial : e.getSerialNumber(),
                    r.has("department") ? (departmentId == null ? 0L : departmentId) : null, r.has("location") ? location : e.getLocation(),
                    r.has("vendor") ? vendor : e.getVendor(), r.has("purchaseOrder") ? po : e.getPurchaseOrder(),
                    r.has("purchaseCost") ? cost : e.getPurchaseCost(), r.has("currency") && currency != null ? currency : e.getCurrency(),
                    r.has("purchaseDate") ? purchased : e.getPurchaseDate(), r.has("warrantyExpires") ? warranty : e.getWarrantyExpires(),
                    r.has("licenseSeats") ? seats : e.getLicenseSeats(), r.has("licenseExpires") ? licenceEnds : e.getLicenseExpires(),
                    r.has("ciName") ? (ciId == null ? 0L : ciId) : null, r.has("notes") ? notes : e.getNotes(), null);
        }
        Asset.Status initial = existing == null && status == Asset.Status.IN_USE && assignee != null ? null : status;
        return new Parsed(row, existing, request, initial, assignee);
    }

    /** Cell access and parsing for one CSV row; errors are appended to the shared list. */
    private static final class Row {
        private final int row;
        private final List<String> cells;
        private final Map<String, Integer> columns;
        private final List<ImportError> errors;
        private boolean failed;

        Row(int row, List<String> cells, Map<String, Integer> columns, List<ImportError> errors) {
            this.row = row;
            this.cells = cells;
            this.columns = columns;
            this.errors = errors;
        }

        boolean has(String column) {
            return columns.containsKey(column.toLowerCase(Locale.ROOT));
        }

        String raw(String column) {
            return cell(cells, columns, column.toLowerCase(Locale.ROOT));
        }

        String text(String column, int max) {
            String v = raw(column);
            if (v != null && v.length() > max) {
                error(column, column + " is longer than " + max + " characters");
                return null;
            }
            return v;
        }

        <E extends Enum<E>> E enumValue(String column, Class<E> type) {
            String v = raw(column);
            if (v == null) {
                return null;
            }
            try {
                return Enum.valueOf(type, v.trim().toUpperCase(Locale.ROOT).replace(' ', '_').replace('-', '_'));
            } catch (IllegalArgumentException e) {
                error(column, "Unknown " + column + " '" + v + "'. Allowed: " + Arrays.toString(type.getEnumConstants()));
                return null;
            }
        }

        LocalDate date(String column) {
            String v = raw(column);
            if (v == null) {
                return null;
            }
            try {
                return LocalDate.parse(v.trim());
            } catch (DateTimeParseException e) {
                error(column, column + " must be a date (YYYY-MM-DD)");
                return null;
            }
        }

        BigDecimal decimal(String column) {
            String v = raw(column);
            if (v == null) {
                return null;
            }
            try {
                BigDecimal d = new BigDecimal(v.trim().replace(",", ""));
                if (d.signum() < 0 || d.scale() > 2 || d.precision() - d.scale() > 10) {
                    error(column, column + " must be a non-negative amount with at most 2 decimals");
                    return null;
                }
                return d;
            } catch (NumberFormatException e) {
                error(column, column + " must be a number");
                return null;
            }
        }

        Integer integer(String column, int min, int max) {
            String v = raw(column);
            if (v == null) {
                return null;
            }
            try {
                int n = Integer.parseInt(v.trim());
                if (n < min || n > max) {
                    error(column, column + " must be between " + min + " and " + max);
                    return null;
                }
                return n;
            } catch (NumberFormatException e) {
                error(column, column + " must be a whole number");
                return null;
            }
        }

        void error(String column, String message) {
            failed = true;
            errors.add(new ImportError(row, column, message));
        }

        boolean failed() {
            return failed;
        }
    }

    private static String cell(List<String> cells, Map<String, Integer> columns, String key) {
        Integer i = columns.get(key);
        if (i == null || i >= cells.size()) {
            return null;
        }
        String v = cells.get(i);
        if (v == null) {
            return null;
        }
        v = v.trim();
        // Values exported by Csv are prefixed with ' when they start with a formula character; undo that on import.
        if (v.length() > 1 && v.charAt(0) == '\'' && "=+-@".indexOf(v.charAt(1)) >= 0) {
            v = v.substring(1);
        }
        return v.isEmpty() ? null : v;
    }

    /** RFC 4180 parser (quoted fields, doubled quotes, CRLF/LF/CR line ends, optional UTF-8 BOM). */
    static List<List<String>> parse(String text) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        boolean any = false;
        int i = text.startsWith("﻿") ? 1 : 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        cell.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    cell.append(c);
                }
            } else if (c == '"' && cell.length() == 0) {
                quoted = true;
                any = true;
            } else if (c == ',') {
                row.add(cell.toString());
                cell.setLength(0);
                any = true;
            } else if (c == '\r' || c == '\n') {
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    i++;
                }
                row.add(cell.toString());
                cell.setLength(0);
                rows.add(row);
                row = new ArrayList<>();
                any = false;
            } else {
                cell.append(c);
                any = true;
            }
            i++;
        }
        if (quoted) {
            throw new IllegalArgumentException("Unterminated quoted field in the CSV");
        }
        if (any || cell.length() > 0 || !row.isEmpty()) {
            row.add(cell.toString());
            rows.add(row);
        }
        return rows;
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }
}
