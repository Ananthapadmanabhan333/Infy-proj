package com.nexus360.form;

import com.nexus360.common.ApiError;
import com.nexus360.common.ApiException;
import com.nexus360.servicedesk.FormField;
import com.nexus360.servicedesk.FormField.ShowIf;
import com.nexus360.user.Role;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.springframework.http.HttpStatus;

/**
 * Authoritative server-side validation of submitted form data against a form definition (types, required, min/max,
 * pattern, options, dates, conditional visibility, user pickers and file uploads). Hidden fields ({@code showIf} not met)
 * are neither validated nor kept. Every problem is reported as {@code fieldErrors[{field: "formData.<key>", message}]};
 * the response message is the first problem (Release 2 wording, e.g. {@code 'Needed from' is required}).
 */
public final class FormValidator {

    public static final Set<String> TYPES = Set.of("text", "textarea", "number", "email", "date", "select", "multiselect", "checkbox",
            "file", "user");
    /** The secure upload allow-list (see AttachmentService). */
    public static final Set<String> FILE_EXTENSIONS = Set.of("png", "jpg", "jpeg", "gif", "pdf", "docx", "xlsx", "txt", "log", "csv", "json");
    public static final int MAX_FIELDS = 40;
    private static final int TEXT_MAX = 2000;
    private static final int TEXTAREA_MAX = 5000;
    private static final int DEFAULT_MAX_FILES = 5;
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final Pattern RELATIVE_DATE = Pattern.compile("^today([+-]\\d{1,4})?$");
    private static final long REGEX_BUDGET_NANOS = 50_000_000L;

    /** Record lookups the validator needs (user pickers, staged uploads); implemented by the service desk. */
    public interface Lookups {

        /** {@code {id, name}} of an active user (restricted to {@code roles} when not empty), or empty. */
        Optional<Map<String, Object>> user(Long id, List<Role> roles);

        /** {@code {id, fileName, contentType, sizeBytes}} of a file the submitter uploaded and that is not used yet, or empty. */
        Optional<Map<String, Object>> stagedFile(Long id);
    }

    /** Lookups for definition previews without records (user and file fields are only checked for shape). */
    public static final Lookups NO_LOOKUPS = new Lookups() {
        @Override
        public Optional<Map<String, Object>> user(Long id, List<Role> roles) {
            return Optional.of(Map.of("id", id));
        }

        @Override
        public Optional<Map<String, Object>> stagedFile(Long id) {
            return Optional.of(Map.of("id", id));
        }
    };

    private FormValidator() {}

    /** Validates and returns the clean data (declared, visible fields only; values normalised). */
    public static Map<String, Object> validate(List<FormField> fields, Map<String, Object> submitted, Lookups lookups, LocalDate today) {
        Map<String, Object> data = submitted == null ? Map.of() : submitted;
        List<ApiError.FieldError> errors = new ArrayList<>();
        Map<String, Object> clean = new LinkedHashMap<>();
        Set<String> keys = new LinkedHashSet<>();
        Map<String, Boolean> visible = new LinkedHashMap<>();
        for (FormField f : fields) {
            keys.add(f.key());
            boolean shown = f.showIf() == null || Boolean.TRUE.equals(visible.get(f.showIf().field())) && matches(f.showIf(), data.get(f.showIf().field()));
            visible.put(f.key(), shown);
            if (!shown) {
                continue;
            }
            try {
                Object value = value(f, data.get(f.key()), lookups, today);
                if (value != null) {
                    clean.put(f.key(), value);
                }
            } catch (FieldProblem p) {
                errors.add(new ApiError.FieldError("formData." + f.key(), p.getMessage()));
            }
        }
        for (String k : data.keySet()) {
            if (!keys.contains(k)) {
                errors.add(new ApiError.FieldError("formData." + k, "Unknown form field '" + k + "'"));
            }
        }
        if (!errors.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, errors.get(0).message(), errors);
        }
        return clean;
    }

    private static Object value(FormField f, Object raw, Lookups lookups, LocalDate today) {
        String label = "'" + f.label() + "'";
        if (isBlank(raw)) {
            if ("checkbox".equals(f.type())) {
                if (f.required()) {
                    throw new FieldProblem(label + " must be confirmed");
                }
                return false;
            }
            if (f.required()) {
                throw new FieldProblem(label + " is required");
            }
            return null;
        }
        return switch (f.type()) {
            case "number" -> {
                double d;
                try {
                    d = raw instanceof Number n ? n.doubleValue() : Double.parseDouble(raw.toString().trim());
                } catch (NumberFormatException e) {
                    throw new FieldProblem(label + " must be a number");
                }
                if (Double.isNaN(d) || Double.isInfinite(d)) {
                    throw new FieldProblem(label + " must be a number");
                }
                if (f.min() != null && d < f.min()) {
                    throw new FieldProblem(label + " must be at least " + format(f.min()));
                }
                if (f.max() != null && d > f.max()) {
                    throw new FieldProblem(label + " must be at most " + format(f.max()));
                }
                yield raw instanceof Number n ? n : d;
            }
            case "checkbox" -> {
                boolean b = raw instanceof Boolean bool ? bool : Boolean.parseBoolean(raw.toString());
                if (f.required() && !b) {
                    throw new FieldProblem(label + " must be confirmed");
                }
                yield b;
            }
            case "date" -> {
                LocalDate date;
                try {
                    date = LocalDate.parse(raw.toString().trim());
                } catch (DateTimeParseException e) {
                    throw new FieldProblem(label + " must be a date (YYYY-MM-DD)");
                }
                LocalDate min = resolveDate(f.minDate(), today);
                LocalDate max = resolveDate(f.maxDate(), today);
                if (min != null && date.isBefore(min)) {
                    throw new FieldProblem(label + " must be on or after " + min);
                }
                if (max != null && date.isAfter(max)) {
                    throw new FieldProblem(label + " must be on or before " + max);
                }
                yield date.toString();
            }
            case "select" -> {
                String s = raw.toString();
                if (f.options() == null || !f.options().contains(s)) {
                    throw new FieldProblem(label + " must be one of " + f.options());
                }
                yield s;
            }
            case "multiselect" -> {
                List<String> chosen = new ArrayList<>(new LinkedHashSet<>(list(raw).stream().map(String::valueOf).toList()));
                for (String c : chosen) {
                    if (f.options() == null || !f.options().contains(c)) {
                        throw new FieldProblem(label + " must only contain " + f.options());
                    }
                }
                if (f.required() && chosen.isEmpty()) {
                    throw new FieldProblem(label + " is required");
                }
                if (f.min() != null && chosen.size() < f.min()) {
                    throw new FieldProblem(label + " needs at least " + format(f.min()) + " selections");
                }
                if (f.max() != null && chosen.size() > f.max()) {
                    throw new FieldProblem(label + " allows at most " + format(f.max()) + " selections");
                }
                yield chosen;
            }
            case "email" -> {
                String s = raw.toString().trim();
                if (s.length() > 254 || !EMAIL.matcher(s).matches()) {
                    throw new FieldProblem(label + " must be a valid e-mail address");
                }
                checkText(f, label, s, 254);
                yield s.toLowerCase(Locale.ROOT);
            }
            case "user" -> {
                Long id = id(raw instanceof Map<?, ?> m ? m.get("id") : raw);
                if (id == null) {
                    throw new FieldProblem(label + " must be a user");
                }
                yield lookups.user(id, f.roles() == null ? List.of() : f.roles())
                        .orElseThrow(() -> new FieldProblem(label + " must be an active user" + (f.roles() == null || f.roles().isEmpty() ? ""
                                : " with role " + f.roles().stream().map(Role::label).toList())));
            }
            case "file" -> {
                List<Object> ids = list(raw);
                int maxFiles = f.max() == null ? DEFAULT_MAX_FILES : (int) Math.round(f.max());
                if (ids.size() > maxFiles) {
                    throw new FieldProblem(label + " allows at most " + maxFiles + " file" + (maxFiles == 1 ? "" : "s"));
                }
                if (f.required() && ids.isEmpty()) {
                    throw new FieldProblem(label + " is required");
                }
                if (f.min() != null && ids.size() < f.min()) {
                    throw new FieldProblem(label + " needs at least " + format(f.min()) + " files");
                }
                List<Map<String, Object>> files = new ArrayList<>();
                Set<Long> seen = new LinkedHashSet<>();
                for (Object o : ids) {
                    Long id = id(o instanceof Map<?, ?> m ? m.get("id") : o);
                    if (id == null || !seen.add(id)) {
                        throw new FieldProblem(label + " must list uploaded file ids");
                    }
                    Map<String, Object> file = lookups.stagedFile(id)
                            .orElseThrow(() -> new FieldProblem(label + ": file " + id + " was not uploaded by you or is already used"));
                    Object name = file.get("fileName");
                    if (name != null && f.accept() != null && !f.accept().isEmpty() && !f.accept().contains(extension(name.toString()))) {
                        throw new FieldProblem(label + " only accepts " + f.accept() + " files");
                    }
                    files.add(file);
                }
                yield files;
            }
            default -> {
                String s = raw.toString().trim();
                checkText(f, label, s, "textarea".equals(f.type()) ? TEXTAREA_MAX : TEXT_MAX);
                yield s;
            }
        };
    }

    private static void checkText(FormField f, String label, String s, int hardMax) {
        if (s.length() > hardMax || f.max() != null && s.length() > f.max()) {
            throw new FieldProblem(label + " is too long" + (f.max() != null ? " (at most " + format(f.max()) + " characters)" : ""));
        }
        if (f.min() != null && s.length() < f.min()) {
            throw new FieldProblem(label + " must be at least " + format(f.min()) + " characters");
        }
        if (f.pattern() != null && !f.pattern().isBlank() && !safeMatches(f.pattern(), s)) {
            throw new FieldProblem(label + " has an invalid format");
        }
    }

    // ------------------------------------------------------------------ conditional visibility

    static boolean matches(ShowIf rule, Object actual) {
        List<String> values = isBlank(actual) ? List.of() : list(actual).stream().map(v -> String.valueOf(v).trim()).toList();
        String expected = rule.value() == null ? "" : rule.value().trim();
        return switch (rule.op()) {
            case EMPTY -> values.isEmpty();
            case NOT_EMPTY -> !values.isEmpty() && !(values.size() == 1 && "false".equalsIgnoreCase(values.get(0)));
            case EQUALS -> values.stream().anyMatch(expected::equalsIgnoreCase);
            case NOT_EQUALS -> values.stream().noneMatch(expected::equalsIgnoreCase);
            case IN -> Arrays.stream(expected.split(",")).map(String::trim).anyMatch(e -> values.stream().anyMatch(e::equalsIgnoreCase));
            case CONTAINS -> !expected.isEmpty() && values.stream().anyMatch(v -> v.toLowerCase(Locale.ROOT).contains(expected.toLowerCase(Locale.ROOT)));
        };
    }

    // ------------------------------------------------------------------ definition checks

    /** Checks a form definition: unique keys, options, earlier-field references, compilable patterns, ranges, dates, files. */
    public static void validateDefinition(List<FormField> fields) {
        if (fields.size() > MAX_FIELDS) {
            throw ApiException.invalidField("fields", "A form can have at most " + MAX_FIELDS + " fields");
        }
        Set<String> keys = new LinkedHashSet<>();
        for (int i = 0; i < fields.size(); i++) {
            FormField f = fields.get(i);
            String at = "fields[" + i + "]";
            if (f == null || f.key() == null || f.type() == null) {
                throw ApiException.invalidField(at, "Every field needs a key, a label and a type");
            }
            if (!TYPES.contains(f.type())) {
                throw ApiException.invalidField(at + ".type", "Unknown field type '" + f.type() + "'");
            }
            if (!keys.add(f.key())) {
                throw ApiException.invalidField(at + ".key", "Form field keys must be unique ('" + f.key() + "')");
            }
            if (("select".equals(f.type()) || "multiselect".equals(f.type())) && (f.options() == null || f.options().isEmpty())) {
                throw ApiException.invalidField(at + ".options", "Select field '" + f.key() + "' needs at least one option");
            }
            if (f.options() != null && new LinkedHashSet<>(f.options()).size() != f.options().size()) {
                throw ApiException.invalidField(at + ".options", "Options of '" + f.key() + "' must be unique");
            }
            if (f.min() != null && f.max() != null && f.min() > f.max()) {
                throw ApiException.invalidField(at + ".min", "min cannot be greater than max");
            }
            if (f.pattern() != null && !f.pattern().isBlank()) {
                try {
                    Pattern.compile(f.pattern());
                } catch (PatternSyntaxException e) {
                    throw ApiException.invalidField(at + ".pattern", "Invalid pattern: " + e.getDescription());
                }
            }
            for (String d : new String[]{f.minDate(), f.maxDate()}) {
                if (d != null && !d.isBlank()) {
                    try {
                        resolveDate(d, LocalDate.now());
                    } catch (FieldProblem e) {
                        throw ApiException.invalidField(at + ".minDate", e.getMessage());
                    }
                }
            }
            if ("file".equals(f.type())) {
                if (f.accept() != null && !FILE_EXTENSIONS.containsAll(f.accept())) {
                    throw ApiException.invalidField(at + ".accept", "File fields may only accept " + FILE_EXTENSIONS);
                }
                if (f.max() != null && (f.max() < 1 || f.max() > 10)) {
                    throw ApiException.invalidField(at + ".max", "A file field accepts 1 to 10 files");
                }
            }
            if (f.showIf() != null) {
                if (!keys.contains(f.showIf().field()) || f.showIf().field().equals(f.key())) {
                    throw ApiException.invalidField(at + ".showIf", "showIf of '" + f.key() + "' must reference an earlier field");
                }
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    static LocalDate resolveDate(String spec, LocalDate today) {
        if (spec == null || spec.isBlank()) {
            return null;
        }
        String s = spec.trim().toLowerCase(Locale.ROOT);
        if (RELATIVE_DATE.matcher(s).matches()) {
            return s.length() == 5 ? today : today.plusDays(Long.parseLong(s.substring(5).replace("+", "")));
        }
        try {
            return LocalDate.parse(s);
        } catch (DateTimeParseException e) {
            throw new FieldProblem("Dates must be YYYY-MM-DD, today or today+N");
        }
    }

    /** Regular-expression match with a time budget, so a pathological pattern cannot stall the request thread. */
    static boolean safeMatches(String pattern, String input) {
        long deadline = System.nanoTime() + REGEX_BUDGET_NANOS;
        CharSequence guarded = new CharSequence() {
            @Override
            public int length() {
                return input.length();
            }

            @Override
            public char charAt(int index) {
                if (System.nanoTime() > deadline) {
                    throw new IllegalStateException("pattern evaluation timed out");
                }
                return input.charAt(index);
            }

            @Override
            public CharSequence subSequence(int start, int end) {
                return input.subSequence(start, end);
            }

            @Override
            public String toString() {
                return input;
            }
        };
        try {
            return Pattern.compile(pattern).matcher(guarded).matches();
        } catch (IllegalStateException | PatternSyntaxException e) {
            return false;
        }
    }

    private static boolean isBlank(Object v) {
        return v == null || v instanceof String s && s.isBlank() || v instanceof Collection<?> c && c.isEmpty();
    }

    private static List<Object> list(Object raw) {
        if (raw == null) {
            return List.of();
        }
        if (raw instanceof Collection<?> c) {
            return new ArrayList<>(c);
        }
        return List.of(raw);
    }

    private static Long id(Object o) {
        if (o instanceof Number n) {
            return n.longValue();
        }
        if (o instanceof String s && s.trim().matches("^\\d{1,18}$")) {
            return Long.valueOf(s.trim());
        }
        return null;
    }

    private static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String format(double d) {
        return d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d);
    }

    private static final class FieldProblem extends RuntimeException {
        FieldProblem(String message) {
            super(message, null, false, false);
        }
    }
}
