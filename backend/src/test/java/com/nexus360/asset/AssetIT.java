package com.nexus360.asset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexus360.support.IntegrationTest;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Assets: CRUD with validation and permissions, the lifecycle state machine, hardware assignment with history, licence seats,
 * maintenance records, expiring warranties and licences, formula-safe CSV export, validated CSV import (dry run, row-level errors,
 * all-or-nothing apply, updates by asset tag), CI links, the employee portal's "my assets" and the ASSETS report.
 */
class AssetIT extends IntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    private long userId(String user) {
        return jdbc.queryForObject("select id from users where email = ?", Long.class, email(user));
    }

    private long dept(String code) {
        return jdbc.queryForObject("select id from departments where code = ?", Long.class, code);
    }

    private Map<String, Object> laptop(String run) {
        Map<String, Object> m = new HashMap<>();
        m.put("name", "Test laptop " + run);
        m.put("assetType", "LAPTOP");
        m.put("manufacturer", "Dell");
        m.put("model", "Latitude 7450");
        m.put("serialNumber", "SN-" + run);
        m.put("departmentId", dept("IT-EUC"));
        m.put("location", "Bengaluru HQ");
        m.put("purchaseCost", 99000.50);
        m.put("purchaseDate", LocalDate.now(java.time.ZoneOffset.UTC).minusDays(30).toString());
        m.put("warrantyExpires", LocalDate.now(java.time.ZoneOffset.UTC).plusDays(10).toString());
        return m;
    }

    private JsonNode create(Map<String, Object> body) throws Exception {
        return body(mvc.perform(jsonBody(as("deepa.nair", post("/api/assets")), body)).andExpect(status().isCreated()).andReturn());
    }

    private ResultActions transition(long id, Map<String, Object> body) throws Exception {
        return mvc.perform(jsonBody(as("deepa.nair", post("/api/assets/" + id + "/transition")), body));
    }

    @Test
    void crudValidationAndPermissions() throws Exception {
        String run = UUID.randomUUID().toString().substring(0, 8);
        Map<String, Object> body = laptop(run);
        mvc.perform(jsonBody(as("riya.varma", post("/api/assets")), body)).andExpect(status().isForbidden());
        mvc.perform(as("kavya.reddy", get("/api/assets"))).andExpect(status().isForbidden());
        JsonNode created = create(body);
        long id = created.at("/asset/id").asLong();
        assertThat(created.at("/asset/assetTag").asText()).startsWith("AST-");
        assertThat(created.at("/asset/status").asText()).isEqualTo("IN_STOCK");
        assertThat(created.at("/asset/category").asText()).isEqualTo("HARDWARE");
        assertThat(created.at("/asset/warrantyStatus").asText()).isEqualTo("EXPIRING");
        assertThat(created.get("purchaseCost").decimalValue()).isEqualByComparingTo("99000.50");
        assertThat(created.get("currency").asText()).isEqualTo("INR");
        assertThat(created.get("allowedTransitions").toString()).contains("IN_USE", "IN_REPAIR", "RETIRED").doesNotContain("DISPOSED");
        assertThat(getJson("ananya.bose", "/api/assets/" + id).at("/asset/name").asText()).isEqualTo("Test laptop " + run); // auditors read

        Map<String, Object> dupSerial = laptop(run);
        dupSerial.put("serialNumber", "sn-" + run); // case-insensitive
        mvc.perform(jsonBody(as("deepa.nair", post("/api/assets")), dupSerial)).andExpect(status().isConflict());
        Map<String, Object> dupTag = laptop(run + "b");
        dupTag.put("assetTag", created.at("/asset/assetTag").asText());
        mvc.perform(jsonBody(as("deepa.nair", post("/api/assets")), dupTag)).andExpect(status().isConflict());
        mvc.perform(jsonBody(as("deepa.nair", post("/api/assets")), Map.of("name", "Office 365", "assetType", "SOFTWARE_LICENSE")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors[0].field").value("licenseSeats"));
        Map<String, Object> seats = laptop(run + "c");
        seats.put("licenseSeats", 5);
        mvc.perform(jsonBody(as("deepa.nair", post("/api/assets")), seats)).andExpect(status().isBadRequest());
        Map<String, Object> currency = laptop(run + "d");
        currency.put("currency", "RUPEES");
        mvc.perform(jsonBody(as("deepa.nair", post("/api/assets")), currency)).andExpect(status().isBadRequest());
        Map<String, Object> warranty = laptop(run + "e");
        warranty.put("warrantyExpires", LocalDate.now(java.time.ZoneOffset.UTC).minusDays(60).toString());
        mvc.perform(jsonBody(as("deepa.nair", post("/api/assets")), warranty)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("warrantyExpires"));
        Map<String, Object> future = laptop(run + "f");
        future.put("purchaseDate", LocalDate.now(java.time.ZoneOffset.UTC).plusDays(30).toString());
        future.remove("warrantyExpires");
        mvc.perform(jsonBody(as("deepa.nair", post("/api/assets")), future)).andExpect(status().isBadRequest());
        Map<String, Object> unknownDept = laptop(run + "g");
        unknownDept.put("departmentId", 999_999);
        mvc.perform(jsonBody(as("deepa.nair", post("/api/assets")), unknownDept)).andExpect(status().isBadRequest());
        Map<String, Object> negative = laptop(run + "h");
        negative.put("purchaseCost", -1);
        mvc.perform(jsonBody(as("deepa.nair", post("/api/assets")), negative)).andExpect(status().isBadRequest());

        // Update and CI link.
        long ci = jdbc.queryForObject("select id from configuration_items where name = 'payroll-app-staging'", Long.class);
        body.put("location", "Pune office");
        body.put("ciId", ci);
        JsonNode updated = body(mvc.perform(jsonBody(as("deepa.nair", put("/api/assets/" + id)), body)).andExpect(status().isOk()).andReturn());
        assertThat(updated.at("/asset/location").asText()).isEqualTo("Pune office");
        assertThat(updated.at("/asset/ciName").asText()).isEqualTo("payroll-app-staging");
        assertThat(getJson("riya.varma", "/api/cmdb/cis/" + ci).get("assets").findValuesAsText("id")).contains(String.valueOf(id));
        assertThat(getJson("riya.varma", "/api/cmdb/cis/" + ci + "/history").findValuesAsText("action")).contains("ASSET_LINKED");
        Map<String, Object> toLicence = new HashMap<>(body);
        toLicence.put("assetType", "SOFTWARE_LICENSE");
        toLicence.put("licenseSeats", 3);
        mvc.perform(jsonBody(as("deepa.nair", put("/api/assets/" + id)), toLicence)).andExpect(status().isBadRequest());
        JsonNode history = getJson("riya.varma", "/api/assets/" + id + "/history");
        assertThat(history.findValuesAsText("type")).contains("CREATED", "UPDATED", "CI_LINKED");

        JsonNode list = getJson("riya.varma", "/api/assets?search=" + run + "&type=LAPTOP&status=IN_STOCK&warranty=EXPIRING");
        assertThat(list.get("totalElements").asInt()).isEqualTo(1);
        assertThat(getJson("riya.varma", "/api/assets?search=" + run + "&category=SOFTWARE").get("totalElements").asInt()).isZero();
        mvc.perform(as("riya.varma", get("/api/assets?sort=price"))).andExpect(status().isBadRequest());
        mvc.perform(as("riya.varma", get("/api/assets?warranty=SOON"))).andExpect(status().isBadRequest());
        mvc.perform(as("riya.varma", get("/api/assets?status=LOST"))).andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("select count(*) from audit_logs where action in ('ASSET_CREATED', 'ASSET_UPDATED') and entity_id = ?", Long.class,
                created.at("/asset/assetTag").asText())).isEqualTo(2);
    }

    @Test
    void lifecycleAssignmentAndMaintenance() throws Exception {
        String run = UUID.randomUUID().toString().substring(0, 8);
        long id = create(laptop(run)).at("/asset/id").asLong();
        transition(id, Map.of("status", "IN_USE")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors[0].field").value("assigneeId"));
        transition(id, Map.of("status", "DISPOSED", "note", "Too early")).andExpect(status().isConflict());
        transition(id, Map.of("status", "IN_STOCK")).andExpect(status().isConflict());
        JsonNode inUse = body(transition(id, Map.of("status", "IN_USE", "assigneeId", userId("kavya.reddy"))).andExpect(status().isOk()).andReturn());
        assertThat(inUse.at("/asset/status").asText()).isEqualTo("IN_USE");
        assertThat(inUse.at("/asset/assignedToName").asText()).isEqualTo("Kavya Reddy");
        assertThat(jdbc.queryForObject("select count(*) from notifications n join users u on u.id = n.user_id where u.email = ? and n.type = 'ASSET_ASSIGNED' "
                + "and n.message like ?", Long.class, email("kavya.reddy"), "%" + inUse.at("/asset/assetTag").asText() + "%")).isEqualTo(1);
        // Reassign; the history keeps both holders.
        mvc.perform(jsonBody(as("deepa.nair", post("/api/assets/" + id + "/assign")), Map.of("userId", userId("kavya.reddy"))))
                .andExpect(status().isConflict());
        mvc.perform(jsonBody(as("deepa.nair", post("/api/assets/" + id + "/assign")), Map.of("userId", 999_999))).andExpect(status().isBadRequest());
        JsonNode reassigned = body(mvc.perform(jsonBody(as("deepa.nair", post("/api/assets/" + id + "/assign")), Map.of("userId", userId("fatima.khan"),
                "note", "Kavya moved to Pune"))).andExpect(status().isOk()).andReturn());
        assertThat(reassigned.at("/asset/assignedToName").asText()).isEqualTo("Fatima Khan");
        JsonNode assignments = getJson("riya.varma", "/api/assets/" + id + "/assignments");
        assertThat(assignments).hasSize(2);
        assertThat(assignments.at("/0/userName").asText()).isEqualTo("Fatima Khan");
        assertThat(assignments.at("/0/returnedAt").isNull()).isTrue();
        assertThat(assignments.at("/1/userName").asText()).isEqualTo("Kavya Reddy");
        assertThat(assignments.at("/1/returnedAt").isNull()).isFalse();
        // To repair (keeps the holder), back to stock (returns it), retire, dispose with a note.
        JsonNode repair = body(transition(id, Map.of("status", "IN_REPAIR", "note", "Screen flicker")).andExpect(status().isOk()).andReturn());
        assertThat(repair.at("/asset/assignedToName").asText()).isEqualTo("Fatima Khan");
        mvc.perform(jsonBody(as("deepa.nair", post("/api/assets/" + id + "/maintenance")), Map.of("maintenanceType", "REPAIR", "description",
                "Display cable replaced", "performedOn", LocalDate.now(java.time.ZoneOffset.UTC).plusDays(3).toString()))).andExpect(status().isBadRequest());
        mvc.perform(jsonBody(as("deepa.nair", post("/api/assets/" + id + "/maintenance")), Map.of("maintenanceType", "CLEANING", "description",
                "Dust removal", "performedOn", LocalDate.now(java.time.ZoneOffset.UTC).toString()))).andExpect(status().isBadRequest());
        mvc.perform(jsonBody(as("deepa.nair", post("/api/assets/" + id + "/maintenance")), Map.of("maintenanceType", "REPAIR", "description",
                "Display cable replaced", "performedOn", LocalDate.now(java.time.ZoneOffset.UTC).toString(), "nextDue", LocalDate.now(java.time.ZoneOffset.UTC).minusDays(1).toString())))
                .andExpect(status().isBadRequest());
        JsonNode m = body(mvc.perform(jsonBody(as("deepa.nair", post("/api/assets/" + id + "/maintenance")), Map.of("maintenanceType", "REPAIR",
                "description", "Display cable replaced", "vendor", "Dell Service", "cost", 0, "performedOn", LocalDate.now(java.time.ZoneOffset.UTC).toString())))
                .andExpect(status().isCreated()).andReturn());
        assertThat(m.get("createdByName").asText()).isEqualTo("Deepa Nair");
        mvc.perform(jsonBody(as("riya.varma", post("/api/assets/" + id + "/maintenance")), Map.of("maintenanceType", "REPAIR", "description",
                "Not allowed", "performedOn", LocalDate.now(java.time.ZoneOffset.UTC).toString()))).andExpect(status().isForbidden());
        assertThat(getJson("riya.varma", "/api/assets/" + id + "/maintenance")).hasSize(1);
        JsonNode stock = body(transition(id, Map.of("status", "IN_STOCK", "note", "Repaired")).andExpect(status().isOk()).andReturn());
        assertThat(stock.at("/asset/assignedToId").isNull()).isTrue();
        mvc.perform(jsonBody(as("deepa.nair", post("/api/assets/" + id + "/return")), Map.of())).andExpect(status().isConflict());
        transition(id, Map.of("status", "RETIRED", "note", "End of life")).andExpect(status().isOk());
        mvc.perform(jsonBody(as("deepa.nair", post("/api/assets/" + id + "/assign")), Map.of("userId", userId("kavya.reddy"))))
                .andExpect(status().isConflict());
        transition(id, Map.of("status", "DISPOSED")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors[0].field").value("note"));
        JsonNode disposed = body(transition(id, Map.of("status", "DISPOSED", "note", "Recycled with certificate EW-1")).andExpect(status().isOk()).andReturn());
        assertThat(disposed.get("disposedAt").isNull()).isFalse();
        assertThat(disposed.get("allowedTransitions")).isEmpty();
        mvc.perform(jsonBody(as("deepa.nair", put("/api/assets/" + id)), laptop(run))).andExpect(status().isConflict());
        assertThat(getJson("riya.varma", "/api/assets/" + id + "/history").findValuesAsText("type")).contains("ASSIGNED", "RETURNED", "STATUS_CHANGED",
                "MAINTENANCE");
    }

    @Test
    void licenceSeats() throws Exception {
        String run = UUID.randomUUID().toString().substring(0, 8);
        JsonNode lic = create(Map.of("name", "Diagram tool " + run, "assetType", "SOFTWARE_LICENSE", "licenseSeats", 2, "vendor", "Lucid",
                "licenseExpires", LocalDate.now(java.time.ZoneOffset.UTC).plusDays(20).toString()));
        long id = lic.at("/asset/id").asLong();
        assertThat(lic.at("/asset/category").asText()).isEqualTo("SOFTWARE");
        assertThat(lic.at("/asset/warrantyStatus").asText()).isEqualTo("EXPIRING"); // licence end within 30 days
        mvc.perform(jsonBody(as("deepa.nair", post("/api/assets")), Map.of("name", "Seats to someone", "assetType", "SOFTWARE_LICENSE", "licenseSeats", 1,
                "assignedToId", userId("kavya.reddy")))).andExpect(status().isBadRequest());
        JsonNode one = body(mvc.perform(jsonBody(as("deepa.nair", post("/api/assets/" + id + "/assign")), Map.of("userId", userId("kavya.reddy"))))
                .andExpect(status().isOk()).andReturn());
        assertThat(one.at("/asset/status").asText()).isEqualTo("IN_USE");
        assertThat(one.at("/asset/assignedToId").isNull()).isTrue(); // licences have seats, not a holder
        mvc.perform(jsonBody(as("deepa.nair", post("/api/assets/" + id + "/assign")), Map.of("userId", userId("kavya.reddy"))))
                .andExpect(status().isConflict());
        mvc.perform(jsonBody(as("deepa.nair", post("/api/assets/" + id + "/assign")), Map.of("userId", userId("fatima.khan")))).andExpect(status().isOk());
        mvc.perform(jsonBody(as("deepa.nair", post("/api/assets/" + id + "/assign")), Map.of("userId", userId("sneha.patil"))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.message").value("All 2 seats of Diagram tool " + run + " are in use"));
        JsonNode detail = getJson("riya.varma", "/api/assets/" + id);
        assertThat(detail.at("/asset/seatsUsed").asInt()).isEqualTo(2);
        assertThat(detail.get("seats").findValuesAsText("userName")).containsExactly("Fatima Khan", "Kavya Reddy");
        Map<String, Object> shrink = new HashMap<>(Map.of("name", "Diagram tool " + run, "assetType", "SOFTWARE_LICENSE", "licenseSeats", 1));
        mvc.perform(jsonBody(as("deepa.nair", put("/api/assets/" + id)), shrink)).andExpect(status().isConflict());
        mvc.perform(jsonBody(as("deepa.nair", post("/api/assets/" + id + "/return")), Map.of())).andExpect(status().isBadRequest());
        mvc.perform(jsonBody(as("deepa.nair", post("/api/assets/" + id + "/return")), Map.of("userId", userId("sneha.patil")))).andExpect(status().isConflict());
        mvc.perform(jsonBody(as("deepa.nair", post("/api/assets/" + id + "/return")), Map.of("userId", userId("kavya.reddy")))).andExpect(status().isOk());
        JsonNode last = body(mvc.perform(jsonBody(as("deepa.nair", post("/api/assets/" + id + "/return")), Map.of("userId", userId("fatima.khan"))))
                .andExpect(status().isOk()).andReturn());
        assertThat(last.at("/asset/status").asText()).isEqualTo("IN_STOCK");
        assertThat(last.at("/asset/seatsUsed").asInt()).isZero();
        assertThat(getJson("riya.varma", "/api/assets/" + id + "/assignments")).hasSize(2);
        JsonNode expiring = getJson("riya.varma", "/api/assets/expiring?days=30");
        boolean found = false;
        for (JsonNode item : expiring) {
            if (item.at("/asset/id").asLong() == id) {
                found = true;
                assertThat(item.get("kind").asText()).isEqualTo("LICENSE");
                assertThat(item.get("daysLeft").asLong()).isEqualTo(20);
            }
        }
        assertThat(found).isTrue();
    }

    @Test
    void expiringWarrantiesSummaryAndPortal() throws Exception {
        String run = UUID.randomUUID().toString().substring(0, 8);
        Map<String, Object> expired = laptop(run + "x");
        expired.put("purchaseDate", LocalDate.now(java.time.ZoneOffset.UTC).minusDays(800).toString());
        expired.put("warrantyExpires", LocalDate.now(java.time.ZoneOffset.UTC).minusDays(5).toString());
        expired.put("assignedToId", userId("kavya.reddy"));
        JsonNode e = create(expired);
        assertThat(e.at("/asset/status").asText()).isEqualTo("IN_USE");
        assertThat(e.at("/asset/warrantyStatus").asText()).isEqualTo("EXPIRED");
        JsonNode soon = create(laptop(run + "y"));
        Map<String, Object> retired = laptop(run + "z");
        long retiredId = create(retired).at("/asset/id").asLong();
        transition(retiredId, Map.of("status", "RETIRED")).andExpect(status().isOk());

        JsonNode list = getJson("riya.varma", "/api/assets/expiring?days=30");
        List<String> ids = list.findValuesAsText("id");
        assertThat(ids).contains(e.at("/asset/id").asText(), soon.at("/asset/id").asText()).doesNotContain(String.valueOf(retiredId));
        for (JsonNode item : list) {
            if (item.at("/asset/id").asLong() == e.at("/asset/id").asLong()) {
                assertThat(item.get("expired").asBoolean()).isTrue();
                assertThat(item.get("daysLeft").asLong()).isEqualTo(-5);
            }
        }
        assertThat(list.toString()).contains("Cisco ASA 5516-X (vpn-gw-01)"); // demo warranty ending in 12 days
        mvc.perform(as("riya.varma", get("/api/assets/expiring?days=0"))).andExpect(status().isBadRequest());
        JsonNode stats = getJson("ananya.bose", "/api/assets/summary");
        assertThat(stats.get("total").asLong()).isGreaterThanOrEqualTo(30);
        assertThat(stats.at("/byStatus/IN_USE").asLong()).isPositive();
        assertThat(stats.get("seatsTotal").asLong()).isGreaterThanOrEqualTo(65);
        assertThat(stats.get("expired").asLong()).isPositive();

        // Employee portal: only my own assets (the demo laptop and Microsoft 365 seat, plus this test's laptop).
        JsonNode portal = getJson("kavya.reddy", "/api/portal/overview");
        JsonNode mine = portal.get("myAssets");
        assertThat(mine.get("total").asLong()).isGreaterThanOrEqualTo(3);
        assertThat(mine.get("items").findValuesAsText("assetTag")).contains(e.at("/asset/assetTag").asText());
        assertThat(mine.get("items").findValuesAsText("name")).contains("Microsoft 365 E3").doesNotContain("Test laptop " + run + "y");
        assertThat(getJson("sneha.patil", "/api/portal/overview").get("myAssets").get("items").findValuesAsText("assetTag"))
                .doesNotContain(e.at("/asset/assetTag").asText());
    }

    @Test
    void csvExportIsFormulaSafeAndImportIsValidatedWithDryRun() throws Exception {
        String run = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        Map<String, Object> evil = laptop(run);
        evil.put("name", "=HYPERLINK(\"http://evil.example\",\"x\") " + run);
        JsonNode existing = create(evil);
        String tag = existing.at("/asset/assetTag").asText();
        String csv = new String(mvc.perform(as("riya.varma", get("/api/assets/export?search=" + run))).andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"assets.csv\"")).andReturn().getResponse().getContentAsByteArray(),
                StandardCharsets.UTF_8);
        assertThat(csv).startsWith("assetTag,name,assetType,status");
        assertThat(csv).contains(tag).contains("\"'=HYPERLINK(\"\"http://evil.example\"\",\"\"x\"\") " + run + "\"");
        assertThat(jdbc.queryForObject("select count(*) from audit_logs where action = 'ASSETS_EXPORTED'", Long.class)).isPositive();
        String template = mvc.perform(as("deepa.nair", get("/api/assets/import-template"))).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(template).contains("assetTag,name,assetType").contains("Latitude 7440");

        String bad = String.join("\r\n",
                "assetTag,name,assetType,status,serialNumber,department,assignedToEmail,purchaseCost,purchaseDate,warrantyExpires,licenseSeats",
                "IMP-" + run + "-1,Imported laptop one,LAPTOP,IN_STOCK,IMPSN-" + run + "-1,IT-EUC,,1200.50,2026-01-10,2029-01-09,",
                "IMP-" + run + "-2,Imported toaster,TOASTER,,IMPSN-" + run + "-2,IT-EUC,,,,,",
                "IMP-" + run + "-3,Duplicate serial,LAPTOP,,IMPSN-" + run + "-1,IT-EUC,,,,,",
                "IMP-" + run + "-4,Unknown department,LAPTOP,,IMPSN-" + run + "-4,NOWHERE,,,,,",
                "IMP-" + run + "-5,In use without holder,LAPTOP,IN_USE,IMPSN-" + run + "-5,IT-EUC,,,,,",
                "IMP-" + run + "-6,Bad date,LAPTOP,,IMPSN-" + run + "-6,IT-EUC,,,2026-13-45,,",
                tag + ",Existing laptop,LAPTOP,DISPOSED,SN-" + run + ",IT-EUC,,,,,",
                "IMP-" + run + "-8,Licence without seats,SOFTWARE_LICENSE,,,IT-EUC,,,,,",
                "IMP-" + run + "-1,Duplicate tag,LAPTOP,,IMPSN-" + run + "-9,IT-EUC,,,,,");
        JsonNode dry = importCsv(bad, true, "deepa.nair");
        assertThat(dry.get("dryRun").asBoolean()).isTrue();
        assertThat(dry.get("applied").asBoolean()).isFalse();
        assertThat(dry.get("totalRows").asInt()).isEqualTo(9);
        assertThat(dry.get("validRows").asInt()).isEqualTo(1);
        Map<Integer, String> errors = new HashMap<>();
        dry.get("errors").forEach(err -> errors.put(err.get("row").asInt(), err.get("field").asText()));
        assertThat(errors).containsEntry(3, "assetType").containsEntry(4, "serialNumber").containsEntry(5, "department")
                .containsEntry(6, "assignedToEmail").containsEntry(7, "purchaseDate").containsEntry(8, "status").containsEntry(9, "licenseSeats")
                .containsEntry(10, "assetTag");
        assertThat(dry.get("rows").findValuesAsText("action")).contains("CREATE");
        JsonNode applyBad = importCsv(bad, false, "deepa.nair");
        assertThat(applyBad.get("applied").asBoolean()).isFalse(); // all or nothing
        assertThat(jdbc.queryForObject("select count(*) from assets where asset_tag like ?", Long.class, "IMP-" + run + "%")).isZero();

        String good = String.join("\n",
                "assetTag,name,assetType,status,manufacturer,serialNumber,department,location,assignedToEmail,purchaseCost,currency,purchaseDate,"
                        + "warrantyExpires,licenseSeats,ciName,notes",
                "IMP-" + run + "-1,Imported laptop one,laptop,,Lenovo,IMPSN-" + run + "-1,IT-EUC,\"Pune office, IT cupboard\",,1200.50,INR,2026-01-10,"
                        + "2029-01-09,,,\"Multi-line\nnote\"",
                ",Imported phone,MOBILE,IN_STOCK,Apple,IMPSN-" + run + "-2,IT-SD,,,,,,,,,",
                "IMP-" + run + "-3,Imported laptop for Kavya,LAPTOP,IN_USE,Dell,IMPSN-" + run + "-3,FIN,,kavya.reddy@demo.nexus360.io,,,,,,,",
                "IMP-" + run + "-4,Imported licence,SOFTWARE_LICENSE,,Atlassian,,IT-APPS,,,5000,USD,,,25,,",
                tag + ",Existing laptop renamed,LAPTOP,IN_USE,Dell,SN-" + run + ",IT-EUC,Mumbai office,sanjay.menon@demo.nexus360.io,,,,,,"
                        + "payroll-app-staging,");
        mvc.perform(multipart("/api/assets/import").file(new MockMultipartFile("file", "assets.csv", "text/csv", good.getBytes(StandardCharsets.UTF_8)))
                .param("dryRun", "false").header("Authorization", "Bearer " + token("riya.varma"))).andExpect(status().isForbidden());
        JsonNode preview = importCsv(good, true, "deepa.nair");
        assertThat(preview.get("errors")).isEmpty();
        assertThat(preview.get("created").asInt()).isEqualTo(4);
        assertThat(preview.get("updated").asInt()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from assets where asset_tag like ?", Long.class, "IMP-" + run + "%")).isZero();
        JsonNode applied = importCsv(good, false, "deepa.nair");
        assertThat(applied.get("applied").asBoolean()).isTrue();
        assertThat(applied.get("created").asInt()).isEqualTo(4);
        assertThat(jdbc.queryForObject("select count(*) from assets where asset_tag like ?", Long.class, "IMP-" + run + "%")).isEqualTo(3);
        Map<String, Object> one = jdbc.queryForMap("select name, location, notes, purchase_cost, status from assets where asset_tag = ?", "IMP-" + run + "-1");
        assertThat(one.get("location")).isEqualTo("Pune office, IT cupboard");
        assertThat(one.get("notes")).isEqualTo("Multi-line\nnote");
        assertThat(one.get("status")).isEqualTo("IN_STOCK");
        assertThat(jdbc.queryForObject("select u.email from assets a join users u on u.id = a.assigned_to_id where a.asset_tag = ?", String.class,
                "IMP-" + run + "-3")).isEqualTo(email("kavya.reddy"));
        JsonNode updatedExisting = getJson("riya.varma", "/api/assets/" + existing.at("/asset/id").asLong());
        assertThat(updatedExisting.at("/asset/name").asText()).isEqualTo("Existing laptop renamed");
        assertThat(updatedExisting.at("/asset/assignedToName").asText()).isEqualTo("Sanjay Menon");
        assertThat(updatedExisting.at("/asset/status").asText()).isEqualTo("IN_USE");
        assertThat(updatedExisting.at("/asset/ciName").asText()).isEqualTo("payroll-app-staging");
        assertThat(getJson("riya.varma", "/api/assets/" + existing.at("/asset/id").asLong() + "/history").findValuesAsText("type")).contains("IMPORTED");
        assertThat(jdbc.queryForObject("select count(*) from assets where name = 'Imported phone' and serial_number = ?", Long.class,
                "IMPSN-" + run + "-2")).isEqualTo(1);

        JsonNode header = importCsv("assetTag,name,assetType,colour\nX,Y,LAPTOP,red", true, "deepa.nair");
        assertThat(header.at("/errors/0/row").asInt()).isEqualTo(1);
        assertThat(header.at("/errors/0/field").asText()).isEqualTo("colour");
        mvc.perform(multipart("/api/assets/import").file(new MockMultipartFile("file", "x.csv", "text/csv", new byte[0]))
                .header("Authorization", "Bearer " + token("deepa.nair"))).andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/assets/import").file(new MockMultipartFile("file", "x.csv", "text/csv", "name,\"unterminated".getBytes()))
                .header("Authorization", "Bearer " + token("deepa.nair"))).andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("select count(*) from audit_logs where action = 'ASSETS_IMPORTED'", Long.class)).isGreaterThanOrEqualTo(4);
    }

    private JsonNode importCsv(String csv, boolean dryRun, String user) throws Exception {
        return body(mvc.perform(multipart("/api/assets/import").file(new MockMultipartFile("file", "assets.csv", "text/csv",
                csv.getBytes(StandardCharsets.UTF_8))).param("dryRun", String.valueOf(dryRun)).header("Authorization", "Bearer " + token(user)))
                .andExpect(status().isOk()).andReturn());
    }

    @Test
    void assetsReportAndDemoInventory() throws Exception {
        JsonNode demo = getJson("riya.varma", "/api/assets?search=Latitude&size=100");
        assertThat(demo.get("totalElements").asInt()).isGreaterThanOrEqualTo(3);
        JsonNode licences = getJson("riya.varma", "/api/assets?type=SOFTWARE_LICENSE&search=Microsoft");
        assertThat(licences.at("/content/0/seatsUsed").asInt()).isGreaterThanOrEqualTo(12);
        assertThat(getJson("riya.varma", "/api/assets?status=IN_REPAIR").get("totalElements").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(getJson("riya.varma", "/api/assets?status=DISPOSED").get("totalElements").asInt()).isGreaterThanOrEqualTo(1);

        long schedule = body(mvc.perform(jsonBody(as("priya.raman", post("/api/reports/schedules")), Map.of("name", unique("Asset inventory"), "reportType",
                "ASSETS", "format", "CSV", "frequency", "WEEKLY"))).andExpect(status().isCreated()).andReturn()).get("id").asLong();
        JsonNode runResult = body(mvc.perform(as("priya.raman", post("/api/reports/schedules/" + schedule + "/run"))).andExpect(status().isOk()).andReturn());
        assertThat(runResult.get("status").asText()).isEqualTo("SUCCEEDED");
        String file = mvc.perform(as("priya.raman", get("/api/reports/runs/" + runResult.get("id").asLong() + "/download"))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(file).startsWith("asset_tag,name,asset_type,status").contains("Microsoft 365 E3");
        long pdf = body(mvc.perform(jsonBody(as("priya.raman", post("/api/reports/schedules")), Map.of("name", unique("Asset PDF"), "reportType",
                "ASSETS", "format", "PDF", "frequency", "MONTHLY"))).andExpect(status().isCreated()).andReturn()).get("id").asLong();
        mvc.perform(as("priya.raman", post("/api/reports/schedules/" + pdf + "/run"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED")).andExpect(jsonPath("$.contentType").value("application/pdf"));
    }
}
