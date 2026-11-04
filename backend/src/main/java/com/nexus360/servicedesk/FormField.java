package com.nexus360.servicedesk;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.nexus360.user.Role;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * One input of a request form (dynamic form builder, docs/RELEASE3_SPEC.md §5 BE-2). Stored as JSON in form definition
 * versions (and mirrored on the catalogue item). The Release 2 shape {@code {key, label, type, required, options,
 * placeholder}} is still valid; Release 3 adds the types {@code email, multiselect, file, user} and optional validation.
 *
 * <ul>
 *   <li>{@code min}/{@code max}: number value bounds; text/textarea/email length; multiselect selection count; file count.</li>
 *   <li>{@code pattern}: Java regular expression the whole text value must match (text, textarea, email).</li>
 *   <li>{@code minDate}/{@code maxDate}: ISO dates ({@code YYYY-MM-DD}) or {@code today}, {@code today+N}, {@code today-N} (days).</li>
 *   <li>{@code showIf}: the field is shown (validated and kept) only when another, earlier field matches.</li>
 *   <li>{@code accept}: allowed file extensions for file fields (subset of the upload allow-list).</li>
 *   <li>{@code roles}: user picker restriction (empty = any active user).</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FormField(
        @NotBlank @Pattern(regexp = "^[a-zA-Z][a-zA-Z0-9_]{0,39}$") String key,
        @NotBlank @Size(max = 80) String label,
        @NotBlank @Pattern(regexp = "^(text|textarea|number|email|date|select|multiselect|checkbox|file|user)$") String type,
        boolean required,
        @Size(max = 50) List<@Size(max = 80) String> options,
        @Size(max = 120) String placeholder,
        @Size(max = 300) String helpText,
        Double min,
        Double max,
        @Size(max = 200) String pattern,
        @Size(max = 20) String minDate,
        @Size(max = 20) String maxDate,
        @Valid ShowIf showIf,
        @Size(max = 10) List<@Pattern(regexp = "^[a-z0-9]{2,5}$") String> accept,
        @Size(max = 9) List<Role> roles) {

    /** Release 2 constructor (the fields catalogue items had before the form builder). */
    public FormField(String key, String label, String type, boolean required, List<String> options, String placeholder) {
        this(key, label, type, required, options, placeholder, null, null, null, null, null, null, null, null, null);
    }

    public enum Operator { EQUALS, NOT_EQUALS, IN, CONTAINS, NOT_EMPTY, EMPTY }

    /** Conditional visibility: shown when {@code field} (an earlier field) {@code op} {@code value} (comma-separated for IN). */
    public record ShowIf(@NotBlank @Pattern(regexp = "^[a-zA-Z][a-zA-Z0-9_]{0,39}$") String field, @NotNull Operator op,
                         @Size(max = 500) String value) {}
}
