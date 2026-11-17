package com.compact.crm.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Browser CORS preflight (OPTIONS, no Authorization header) against the real
 * security chain. Spring Security's CorsFilter answers a preflight before
 * authentication runs; an origin not in crm.cors.allowed-origins gets 403
 * with no Access-Control-Allow-Origin, which is what the browser reports as
 * a CORS failure.
 *
 * The test classpath application.properties replaces the main one, so the
 * production default list is pinned here explicitly and separately checked
 * against src/main/resources/application.properties.
 */
@SpringBootTest(properties = "crm.cors.allowed-origins="
        + CorsPreflightSecurityTest.PRODUCTION_DEFAULT_ORIGINS)
@AutoConfigureMockMvc
class CorsPreflightSecurityTest {

    static final String PRODUCTION_DEFAULT_ORIGINS =
            "http://localhost:5173,http://localhost:5174,"
                    + "https://crm-frontend-q5nk.onrender.com,"
                    + "https://crm-frontend-handover.onrender.com";

    @Autowired
    private MockMvc mockMvc;

    @ParameterizedTest
    @ValueSource(strings = {
            "https://crm-frontend-handover.onrender.com",
            "https://crm-frontend-q5nk.onrender.com",
            "http://localhost:5173",
            "http://localhost:5174"
    })
    void loginPreflight_fromAllowedOrigin_isAccepted(String origin) throws Exception {
        mockMvc.perform(options("/api/auth/login")
                        .header("Origin", origin)
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "content-type"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", origin))
                .andExpect(header().string("Access-Control-Allow-Credentials", "true"));
    }

    @Test
    void preflight_toAuthenticatedEndpoint_isNotBlockedByAuthentication() throws Exception {
        String origin = "https://crm-frontend-handover.onrender.com";
        mockMvc.perform(options("/api/leads")
                        .header("Origin", origin)
                        .header("Access-Control-Request-Method", "GET")
                        .header("Access-Control-Request-Headers", "authorization"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", origin));
    }

    @Test
    void preflight_fromUnknownOrigin_isRejected() throws Exception {
        mockMvc.perform(options("/api/auth/login")
                        .header("Origin", "https://evil.example.com")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    @Test
    void mainApplicationProperties_defaultOrigins_matchPinnedList() throws Exception {
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(
                Path.of("src/main/resources/application.properties"))) {
            props.load(in);
        }
        assertThat(props.getProperty("crm.cors.allowed-origins"))
                .isEqualTo("${CORS_ALLOWED_ORIGINS:" + PRODUCTION_DEFAULT_ORIGINS + "}");
    }
}
