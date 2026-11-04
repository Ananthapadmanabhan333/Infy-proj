package com.nexus360.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import com.nexus360.support.IntegrationTest;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** Release 3 product name "SA" in user-facing backend strings (docs/RELEASE3_SPEC.md §1); technical identifiers unchanged. */
class BrandingIT extends IntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void healthOpenApiAndOrganisationUseTheNewName() throws Exception {
        mvc.perform(get("/api/health")).andExpect(status().isOk()).andExpect(jsonPath("$.service").value("SA API"));
        mvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andExpect(jsonPath("$.info.title").value("SA API"));
        assertThat(jdbc.queryForObject("select setting_value from system_settings where setting_key = 'org.name'", String.class)).isEqualTo("SA");
        mvc.perform(get("/api/public/info")).andExpect(jsonPath("$.organisationName").value("SA"));
    }

    @Test
    void exportsAndPublicMessagesNameSa() throws Exception {
        byte[] pdf = mvc.perform(as("priya.raman", get("/api/analytics/export?format=pdf&days=7"))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        PdfReader reader = new PdfReader(pdf);
        assertThat(reader.getInfo().get("Title")).startsWith("SA analytics");
        assertThat(reader.getInfo().get("Creator")).isEqualTo("SA");
        String text = new PdfTextExtractor(reader).getTextFromPage(1);
        assertThat(text).contains("Operations analytics").doesNotContain("SA 360").doesNotContain("NEXUS");
        reader.close();

        String message = body(mvc.perform(jsonBody(post("/api/auth/forgot-password"), Map.of("email", "nobody@example.org")))
                .andExpect(status().isAccepted()).andReturn()).get("message").asText();
        assertThat(message).contains("SA").doesNotContain("SA 360");
        // Technical identifiers stay: refresh cookie and CSRF header value.
        assertThat(com.nexus360.security.AuthController.REFRESH_COOKIE).isEqualTo("nexus_refresh");
        assertThat(com.nexus360.security.AuthController.CSRF_HEADER_VALUE).isEqualTo("nexus360");
    }
}
