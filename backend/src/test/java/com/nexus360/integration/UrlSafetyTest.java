package com.nexus360.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexus360.common.ApiException;
import java.net.InetAddress;
import org.junit.jupiter.api.Test;

class UrlSafetyTest {

    @Test
    void rejectsInsecureSchemesAndEmbeddedCredentials() {
        assertThatThrownBy(() -> UrlSafety.validate("http://hooks.example.com/x", false)).isInstanceOf(ApiException.class)
                .hasMessageContaining("https");
        assertThatThrownBy(() -> UrlSafety.validate("ftp://hooks.example.com/x", false)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> UrlSafety.validate("https://user:pass@hooks.example.com/x", false)).isInstanceOf(ApiException.class)
                .hasMessageContaining("credentials");
    }

    @Test
    void rejectsTargetsThatResolveToInternalAddresses() {
        for (String url : new String[] {"https://127.0.0.1/hook", "https://localhost/hook", "https://10.1.2.3/hook",
                "https://192.168.0.10/hook", "https://169.254.169.254/latest/meta-data", "https://[::1]/hook"}) {
            assertThatThrownBy(() -> UrlSafety.validate(url, false)).as(url).isInstanceOf(ApiException.class)
                    .hasMessageContaining("private or internal");
        }
    }

    @Test
    void classifiesAddressRanges() throws Exception {
        assertThat(UrlSafety.isInternal(InetAddress.getByName("172.16.5.4"))).isTrue();
        assertThat(UrlSafety.isInternal(InetAddress.getByName("100.64.0.1"))).isTrue();   // carrier-grade NAT
        assertThat(UrlSafety.isInternal(InetAddress.getByName("198.18.0.1"))).isTrue();   // benchmarking range
        assertThat(UrlSafety.isInternal(InetAddress.getByName("0.0.0.0"))).isTrue();
        assertThat(UrlSafety.isInternal(InetAddress.getByName("fd00::1"))).isTrue();      // unique local
        assertThat(UrlSafety.isInternal(InetAddress.getByName("8.8.8.8"))).isFalse();
        assertThat(UrlSafety.isInternal(InetAddress.getByName("2606:4700::1111"))).isFalse();
    }

    @Test
    void privateTargetsCanBeAllowedExplicitlyForLocalDevelopment() {
        assertThat(UrlSafety.validate("http://127.0.0.1:9000/hook", true).getPort()).isEqualTo(9000);
    }
}
