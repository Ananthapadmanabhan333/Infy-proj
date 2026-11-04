package com.nexus360.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CoreConfig {

    /** Single time source so SLA and workflow logic can be tested with a controllable clock. */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    OpenAPI nexusOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("SA API")
                        .version("1.0.0")
                        .description("SA — Enterprise Operations Platform. Intelligent Service Management. Unified Operations. "
                                + "Authenticate with POST /api/auth/login and use the returned access token as a Bearer token.")
                        .license(new License().name("Proprietary")))
                .components(new Components().addSecuritySchemes("bearer",
                        new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList("bearer"));
    }
}
