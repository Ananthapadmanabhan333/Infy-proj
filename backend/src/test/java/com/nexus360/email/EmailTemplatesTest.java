package com.nexus360.email;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The branded e-mail layout escapes every piece of caller content and only links absolute http(s) URLs. */
class EmailTemplatesTest {

    @Test
    void userContentIsEscapedInHtmlAndKeptReadableInText() {
        EmailTemplates.Content content = new EmailTemplates.Content("<script>alert('x')</script> Payroll down",
                List.of("Reported by Tom & \"Jerry\" <b>bold</b>\nsecond line"),
                List.of(new EmailTemplates.Fact("Title <i>", "<img src=x onerror=alert(1)>")),
                "Open <incident>", "https://sa.example.test/incidents/7?tab=a&x=\"y\"", "Footer <b>note</b>");
        EmailTemplates.Rendered r = EmailTemplates.render("Subject <x>", content, "Acme <Ops>");

        assertThat(r.html()).doesNotContain("<script>", "<b>bold</b>", "<img src=x", "<incident>", "<i>", "Acme <Ops>", "<x>");
        assertThat(r.html()).contains("&lt;script&gt;alert(&#39;x&#39;)&lt;/script&gt; Payroll down");
        assertThat(r.html()).contains("Tom &amp; &quot;Jerry&quot; &lt;b&gt;bold&lt;/b&gt;<br>second line");
        assertThat(r.html()).contains("&lt;img src=x onerror=alert(1)&gt;");
        assertThat(r.html()).contains("href=\"https://sa.example.test/incidents/7?tab=a&amp;x=&quot;y&quot;\"");
        assertThat(r.html()).contains("Acme &lt;Ops&gt;");
        assertThat(r.html()).contains(">SA</div>").contains("Enterprise Operations Platform");
        assertThat(r.text()).contains("<script>alert('x')</script> Payroll down", "Open <incident>: https://sa.example.test/incidents/7");
    }

    @Test
    void nonHttpLinksAreDropped() {
        for (String url : new String[]{"javascript:alert(1)", "data:text/html,hi", "/relative/path", "https://a.test/x y", null}) {
            EmailTemplates.Rendered r = EmailTemplates.render("S", EmailTemplates.Content.of("Heading", "Body", "Click", url, null), "SA");
            assertThat(r.html()).as(String.valueOf(url)).doesNotContain("href=");
            assertThat(r.text()).as(String.valueOf(url)).doesNotContain("Click:");
        }
    }

    @Test
    void retryBackoffGrowsAndIsCapped() {
        assertThat(EmailService.backoff(1).toSeconds()).isEqualTo(30);
        assertThat(EmailService.backoff(2).toSeconds()).isEqualTo(60);
        assertThat(EmailService.backoff(3).toSeconds()).isEqualTo(120);
        assertThat(EmailService.backoff(20).toSeconds()).isEqualTo(3600);
        assertThat(EmailOutboxProcessor.mask("riya.varma@demo.test")).isEqualTo("r***@demo.test");
    }
}
