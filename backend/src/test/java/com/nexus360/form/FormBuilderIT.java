package com.nexus360.form;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexus360.support.IntegrationTest;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;

/**
 * Dynamic form builder: versioned definitions, server-side validation (required, min/max, pattern, options, dates,
 * conditional visibility, user pickers, file uploads with the secure upload rules), request-type permissions and
 * the form editors' permissions.
 */
class FormBuilderIT extends IntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    private static final byte[] PDF = "%PDF-1.4\n%test\n".getBytes(StandardCharsets.UTF_8);

    private long dept(String code) {
        return jdbc.queryForObject("select id from departments where code = ?", Long.class, code);
    }

    private long userId(String user) {
        return jdbc.queryForObject("select id from users where email = ?", Long.class, email(user));
    }

    private static Map<String, Object> field(String key, String label, String type, boolean required, Object... extra) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("key", key);
        f.put("label", label);
        f.put("type", type);
        f.put("required", required);
        for (int i = 0; i < extra.length; i += 2) {
            f.put((String) extra[i], extra[i + 1]);
        }
        return f;
    }

    private List<Map<String, Object>> fields() {
        return List.of(
                field("kind", "Request kind", "select", true, "options", List.of("Standard", "Urgent")),
                field("reason", "Urgency reason", "textarea", true, "min", 10, "showIf", Map.of("field", "kind", "op", "EQUALS", "value", "Urgent")),
                field("costCentre", "Cost centre", "text", true, "pattern", "^CC-\\d{4}$"),
                field("quantity", "Quantity", "number", true, "min", 1, "max", 5),
                field("neededBy", "Needed by", "date", false, "minDate", "today"),
                field("contact", "Contact", "email", false),
                field("tags", "Tags", "multiselect", false, "options", List.of("A", "B", "C"), "max", 2),
                field("approver", "Approver", "user", true, "roles", List.of("IT_MANAGER")),
                field("quote", "Quote", "file", false, "accept", List.of("pdf"), "max", 1));
    }

    private JsonNode createForm(String code) throws Exception {
        return body(mvc.perform(jsonBody(as("priya.raman", post("/api/forms")), Map.of("code", code, "name", "Test form " + code,
                "fields", fields()))).andExpect(status().isCreated()).andReturn());
    }

    private long createItem(long formId, List<Long> departments) throws Exception {
        long category = jdbc.queryForObject("select id from service_categories where code = 'GENERAL'", Long.class);
        Map<String, Object> item = new HashMap<>(Map.of("categoryId", category, "name", unique("Builder item"), "requiresApproval", false,
                "fulfillmentHours", 8, "fulfillmentDepartmentId", dept("IT-SD"), "active", true, "formDefinitionId", formId,
                "allowedDepartmentIds", departments));
        return body(mvc.perform(jsonBody(as("admin", post("/api/catalog/items")), item)).andExpect(status().isCreated()).andReturn()).get("id").asLong();
    }

    private Map<String, Object> validData() {
        Map<String, Object> d = new HashMap<>();
        d.put("kind", "Standard");
        d.put("costCentre", "CC-1234");
        d.put("quantity", 2);
        d.put("approver", userId("priya.raman"));
        return d;
    }

    private org.springframework.test.web.servlet.ResultActions submit(String user, long item, Map<String, Object> data) throws Exception {
        return mvc.perform(jsonBody(as(user, post("/api/service-requests")), Map.of("catalogItemId", item, "title", unique("Form request"),
                "formData", data)));
    }

    private long upload(String user, String name, byte[] content) throws Exception {
        return body(mvc.perform(as(user, multipart("/api/service-requests/uploads").file(new MockMultipartFile("file", name, "application/pdf", content))))
                .andExpect(status().isCreated()).andReturn()).get("id").asLong();
    }

    @Test
    void submissionsAreValidatedOnTheServerIncludingConditionalFields() throws Exception {
        String code = "T-" + unique("V").replaceAll("[^A-Za-z0-9]", "").toUpperCase();
        long item = createItem(createForm(code).get("id").asLong(), List.of());

        submit("kavya.reddy", item, Map.of()).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("'Request kind' is required"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("formData.kind"));
        Map<String, Object> bad = validData();
        bad.put("costCentre", "1234");
        bad.put("quantity", 9);
        bad.put("neededBy", LocalDate.now().minusDays(2).toString());
        bad.put("contact", "not-an-email");
        bad.put("tags", List.of("A", "B", "C"));
        bad.put("approver", userId("riya.varma")); // not an IT manager
        bad.put("unknown", "x");
        JsonNode errors = body(submit("kavya.reddy", item, bad).andExpect(status().isBadRequest()).andReturn()).get("fieldErrors");
        assertThat(errors.findValuesAsText("field")).contains("formData.costCentre", "formData.quantity", "formData.neededBy", "formData.contact",
                "formData.tags", "formData.approver", "formData.unknown");

        // Hidden conditional field: not required and dropped; shown: required and validated.
        Map<String, Object> standard = validData();
        standard.put("reason", "ignored because hidden");
        JsonNode created = body(submit("kavya.reddy", item, standard).andExpect(status().isCreated()).andReturn());
        assertThat(created.get("formData").has("reason")).isFalse();
        assertThat(created.at("/formData/approver/name").asText()).isEqualTo("Priya Raman");
        assertThat(created.get("formVersion").asInt()).isEqualTo(1);
        Map<String, Object> urgent = validData();
        urgent.put("kind", "Urgent");
        submit("kavya.reddy", item, urgent).andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors[0].field").value("formData.reason"));
        urgent.put("reason", "short");
        submit("kavya.reddy", item, urgent).andExpect(status().isBadRequest());
        urgent.put("reason", "The payroll run starts tomorrow morning");
        submit("kavya.reddy", item, urgent).andExpect(status().isCreated());

        // Dry run with the same rules.
        mvc.perform(jsonBody(as("kavya.reddy", post("/api/service-requests/validate")), Map.of("catalogItemId", item, "formData", validData())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.valid").value(true));
    }

    @Test
    void fileFieldsUseTheSecureUploadRulesAndOwnUploadsOnly() throws Exception {
        String code = "T-" + unique("U").replaceAll("[^A-Za-z0-9]", "").toUpperCase();
        long item = createItem(createForm(code).get("id").asLong(), List.of());
        mvc.perform(as("kavya.reddy", multipart("/api/service-requests/uploads").file(new MockMultipartFile("file", "quote.pdf", "application/pdf",
                "not a pdf".getBytes(StandardCharsets.UTF_8))))).andExpect(status().isUnsupportedMediaType());
        mvc.perform(as("kavya.reddy", multipart("/api/service-requests/uploads").file(new MockMultipartFile("file", "run.exe", "application/octet-stream",
                new byte[]{1, 2, 3})))).andExpect(status().isUnsupportedMediaType());
        long txt = body(mvc.perform(as("kavya.reddy", multipart("/api/service-requests/uploads").file(new MockMultipartFile("file", "notes.txt",
                "text/plain", "hello".getBytes(StandardCharsets.UTF_8))))).andExpect(status().isCreated()).andReturn()).get("id").asLong();
        Map<String, Object> wrongType = validData();
        wrongType.put("quote", List.of(txt));
        submit("kavya.reddy", item, wrongType).andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors[0].field").value("formData.quote"));

        long other = upload("sneha.patil", "quote.pdf", PDF);
        Map<String, Object> foreign = validData();
        foreign.put("quote", List.of(other));
        submit("kavya.reddy", item, foreign).andExpect(status().isBadRequest()); // someone else's upload

        long mine = upload("kavya.reddy", "quote.pdf", PDF);
        Map<String, Object> ok = validData();
        ok.put("quote", List.of(mine));
        JsonNode created = body(submit("kavya.reddy", item, ok).andExpect(status().isCreated()).andReturn());
        assertThat(created.at("/formData/quote/0/fileName").asText()).isEqualTo("quote.pdf");
        long requestId = created.get("id").asLong();
        JsonNode files = getJson("kavya.reddy", "/api/service-requests/" + requestId + "/attachments");
        assertThat(files.findValuesAsText("fieldKey")).containsExactly("quote");
        mvc.perform(as("kavya.reddy", get("/api/service-requests/" + requestId + "/attachments/" + mine + "/download")))
                .andExpect(status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("X-Content-Type-Options", "nosniff"));
        mvc.perform(as("sneha.patil", get("/api/service-requests/" + requestId + "/attachments"))).andExpect(status().isNotFound());
        // A file is used once.
        submit("kavya.reddy", item, ok).andExpect(status().isBadRequest());
    }

    @Test
    void formsAreVersionedAndItemsFollowTheCurrentVersion() throws Exception {
        String code = "T-" + unique("R").replaceAll("[^A-Za-z0-9]", "").toUpperCase();
        JsonNode form = createForm(code);
        long formId = form.get("id").asLong();
        long item = createItem(formId, List.of());
        List<Map<String, Object>> v2 = new java.util.ArrayList<>(fields());
        v2.add(field("floor", "Floor", "number", true));
        JsonNode updated = body(mvc.perform(jsonBody(as("priya.raman", put("/api/forms/" + formId)), Map.of("code", code, "name", "Renamed",
                "fields", v2, "changeNote", "Added the floor"))).andExpect(status().isOk()).andReturn());
        assertThat(updated.get("currentVersion").asInt()).isEqualTo(2);
        mvc.perform(jsonBody(as("priya.raman", put("/api/forms/" + formId)), Map.of("code", code, "name", "Renamed only"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.currentVersion").value(2)); // no field change, no new version
        assertThat(getJson("priya.raman", "/api/forms/" + formId + "/versions").findValuesAsText("changeNote")).contains("Added the floor");
        assertThat(getJson("priya.raman", "/api/forms/" + formId + "/versions/1").get("fields")).hasSize(fields().size());
        JsonNode itemForm = getJson("kavya.reddy", "/api/catalog/items/" + item + "/form");
        assertThat(itemForm.get("formVersion").asInt()).isEqualTo(2);
        assertThat(itemForm.get("fields").findValuesAsText("key")).contains("floor");
        submit("kavya.reddy", item, validData()).andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors[0].field").value("formData.floor"));

        // Definition checks.
        mvc.perform(jsonBody(as("priya.raman", post("/api/forms")), Map.of("code", code + "X", "name", "Bad",
                "fields", List.of(field("a", "A", "text", false, "showIf", Map.of("field", "b", "op", "EQUALS", "value", "x")))))).andExpect(status().isBadRequest());
        mvc.perform(jsonBody(as("priya.raman", post("/api/forms")), Map.of("code", code + "Y", "name", "Bad",
                "fields", List.of(field("a", "A", "text", false, "pattern", "([a-z"))))).andExpect(status().isBadRequest());
        mvc.perform(jsonBody(as("priya.raman", post("/api/forms")), Map.of("code", code + "Z", "name", "Bad",
                "fields", List.of(field("a", "A", "file", false, "accept", List.of("exe")))))).andExpect(status().isBadRequest());
        // Templates are copied.
        JsonNode copy = body(mvc.perform(jsonBody(as("priya.raman", post("/api/forms")), Map.of("code", code + "C", "name", "Copy",
                "copyFromId", formId))).andExpect(status().isCreated()).andReturn());
        assertThat(copy.get("fields").findValuesAsText("key")).contains("floor");
        assertThat(getJson("riya.varma", "/api/forms?template=true&size=100").get("content").findValuesAsText("code")).contains("TPL-ACCESS", "TPL-HARDWARE");
    }

    @Test
    void requestTypesCanBeRestrictedToDepartmentsAndFormEditingIsGated() throws Exception {
        String code = "T-" + unique("P").replaceAll("[^A-Za-z0-9]", "").toUpperCase();
        long formId = createForm(code).get("id").asLong();
        long financeOnly = createItem(formId, List.of(dept("FIN")));
        assertThat(getJson("amit.shah", "/api/catalog/items").findValuesAsText("id")).contains(String.valueOf(financeOnly)); // FIN
        assertThat(getJson("kavya.reddy", "/api/catalog/items").findValuesAsText("id")).doesNotContain(String.valueOf(financeOnly)); // HR
        mvc.perform(as("kavya.reddy", get("/api/catalog/items/" + financeOnly + "/form"))).andExpect(status().isNotFound());
        submit("kavya.reddy", financeOnly, validData()).andExpect(status().isForbidden());
        submit("amit.shah", financeOnly, validData()).andExpect(status().isCreated());

        mvc.perform(jsonBody(as("riya.varma", post("/api/forms")), Map.of("code", code + "A", "name", "x"))).andExpect(status().isForbidden());
        mvc.perform(as("kavya.reddy", get("/api/forms"))).andExpect(status().isForbidden());
        mvc.perform(jsonBody(as("riya.varma", post("/api/forms/" + formId + "/validate")), Map.of("formData", Map.of())))
                .andExpect(status().isBadRequest()); // preview runs the same validation
        assertThat(getJson("kavya.reddy", "/api/service-requests/user-options?q=priya").findValuesAsText("fullName")).contains("Priya Raman");

        // Every Release 2 catalogue item has its own form definition with its original fields.
        JsonNode vpn = getJson("kavya.reddy", "/api/catalog/items?search=VPN").get(0);
        assertThat(vpn.get("formDefinitionId").isNull()).isFalse();
        assertThat(vpn.get("formFields").findValuesAsText("key")).contains("startDate", "justification");
    }
}
