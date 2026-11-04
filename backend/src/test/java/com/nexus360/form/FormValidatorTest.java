package com.nexus360.form;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexus360.common.ApiException;
import com.nexus360.servicedesk.FormField;
import com.nexus360.servicedesk.FormField.Operator;
import com.nexus360.servicedesk.FormField.ShowIf;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Pure form validation rules (no records involved). */
class FormValidatorTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 4);

    private static FormField text(String key, boolean required, Double min, Double max, String pattern, ShowIf showIf) {
        return new FormField(key, key, "text", required, null, null, null, min, max, pattern, null, null, showIf, null, null);
    }

    @Test
    void release2FieldsKeepTheirBehaviourAndMessages() {
        List<FormField> fields = List.of(new FormField("startDate", "Needed from", "date", true, null, null),
                new FormField("ok", "Confirmed", "checkbox", false, null, null));
        assertThatThrownBy(() -> FormValidator.validate(fields, Map.of(), FormValidator.NO_LOOKUPS, TODAY))
                .isInstanceOf(ApiException.class).hasMessage("'Needed from' is required");
        assertThat(FormValidator.validate(fields, Map.of("startDate", "2026-12-01"), FormValidator.NO_LOOKUPS, TODAY))
                .containsEntry("startDate", "2026-12-01").containsEntry("ok", false);
    }

    @Test
    void conditionalFieldsAreOnlyValidatedWhenShownAndNestedConditionsFollowTheirParent() {
        FormField kind = new FormField("kind", "Kind", "select", true, List.of("A", "B"), null);
        FormField detail = text("detail", true, 3.0, null, null, new ShowIf("kind", Operator.EQUALS, "B"));
        FormField more = text("more", true, null, null, null, new ShowIf("detail", Operator.NOT_EMPTY, null));
        List<FormField> fields = List.of(kind, detail, more);
        assertThat(FormValidator.validate(fields, Map.of("kind", "A", "detail", "x", "more", "y"), FormValidator.NO_LOOKUPS, TODAY))
                .containsOnlyKeys("kind");
        assertThatThrownBy(() -> FormValidator.validate(fields, Map.of("kind", "B"), FormValidator.NO_LOOKUPS, TODAY))
                .hasMessage("'detail' is required");
        assertThatThrownBy(() -> FormValidator.validate(fields, Map.of("kind", "B", "detail", "xy"), FormValidator.NO_LOOKUPS, TODAY))
                .hasMessage("'detail' must be at least 3 characters");
        assertThat(FormValidator.validate(fields, Map.of("kind", "B", "detail", "xyz", "more", "1"), FormValidator.NO_LOOKUPS, TODAY))
                .containsKeys("kind", "detail", "more");
    }

    @Test
    void patternsDatesAndMultiselectAreChecked() {
        FormField cc = text("cc", true, null, null, "^CC-\\d{4}$", null);
        FormField date = new FormField("d", "Date", "date", false, null, null, null, null, null, null, "today", "today+30", null, null, null);
        FormField tags = new FormField("t", "Tags", "multiselect", false, List.of("x", "y", "z"), null, null, null, 2.0, null, null, null, null, null, null);
        List<FormField> fields = List.of(cc, date, tags);
        assertThatThrownBy(() -> FormValidator.validate(fields, Map.of("cc", "CC-12"), FormValidator.NO_LOOKUPS, TODAY))
                .hasMessage("'cc' has an invalid format");
        assertThatThrownBy(() -> FormValidator.validate(fields, Map.of("cc", "CC-1234", "d", "2026-11-03"), FormValidator.NO_LOOKUPS, TODAY))
                .hasMessage("'Date' must be on or after 2026-11-04");
        assertThatThrownBy(() -> FormValidator.validate(fields, Map.of("cc", "CC-1234", "t", List.of("x", "y", "z")), FormValidator.NO_LOOKUPS, TODAY))
                .hasMessage("'Tags' allows at most 2 selections");
        assertThat(FormValidator.validate(fields, Map.of("cc", "CC-1234", "d", "2026-12-04", "t", List.of("x", "x")), FormValidator.NO_LOOKUPS, TODAY))
                .containsEntry("t", List.of("x"));
    }

    @Test
    void catastrophicPatternsAreCutOff() {
        assertThat(FormValidator.safeMatches("^(a+)+$", "a".repeat(5000) + "!")).isFalse();
    }

    @Test
    void definitionsAreChecked() {
        assertThatThrownBy(() -> FormValidator.validateDefinition(List.of(text("a", false, null, null, null, new ShowIf("a", Operator.EQUALS, "x")))))
                .hasMessageContaining("earlier field");
        assertThatThrownBy(() -> FormValidator.validateDefinition(List.of(text("a", false, 5.0, 2.0, null, null))))
                .hasMessageContaining("min");
        assertThatThrownBy(() -> FormValidator.validateDefinition(List.of(text("a", false, null, null, null, null), text("a", false, null, null, null, null))))
                .hasMessageContaining("unique");
    }
}
