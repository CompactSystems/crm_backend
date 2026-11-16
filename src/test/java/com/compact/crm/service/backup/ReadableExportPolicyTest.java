package com.compact.crm.service.backup;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReadableExportPolicyTest {

    private static final ReadableExportPolicy CRM = ReadableExportPolicies.crm();

    private static ReadableExportPolicy.ColumnRule column(String table, String column) {
        return CRM.table(table).orElseThrow().columns().get(column);
    }

    // ---- the reviewed CRM decisions ----------------------------------------

    @Test
    void credentialsAndAuthenticationSecrets_areWithheldOrExcluded() {
        assertThat(column("employees", "password").treatment()).isEqualTo(ReadableExportPolicy.Treatment.WITHHOLD);
        assertThat(column("email_mailbox_account", "refresh_token_encrypted").treatment())
                .isEqualTo(ReadableExportPolicy.Treatment.WITHHOLD);
        assertThat(column("email_mailbox_account", "provider_subject").treatment())
                .isEqualTo(ReadableExportPolicy.Treatment.WITHHOLD);
        assertThat(CRM.table("email_oauth_state")).isEmpty();
        assertThat(CRM.excluded("email_oauth_state")).isPresent();
    }

    @Test
    void consentEvidenceAndOptOutNotes_areWithheld_butConsentStatusIsExported() {
        for (String t : List.of("whatsapp_consent", "whatsapp_consent_audit")) {
            assertThat(column(t, "evidence").treatment()).isEqualTo(ReadableExportPolicy.Treatment.WITHHOLD);
            assertThat(column(t, "phone_e164").treatment()).isEqualTo(ReadableExportPolicy.Treatment.INCLUDE);
            assertThat(column(t, "source").treatment()).isEqualTo(ReadableExportPolicy.Treatment.INCLUDE);
        }
        assertThat(column("whatsapp_consent", "status").treatment()).isEqualTo(ReadableExportPolicy.Treatment.INCLUDE);
        assertThat(column("email_suppression", "note").treatment()).isEqualTo(ReadableExportPolicy.Treatment.WITHHOLD);
        assertThat(column("email_suppression_audit", "note").treatment()).isEqualTo(ReadableExportPolicy.Treatment.WITHHOLD);
    }

    @Test
    void businessContactDetails_areIncluded() {
        for (String c : List.of("phone", "alternate_phone", "email", "secondary_email", "contact_person", "pincode")) {
            assertThat(column("leads", c).treatment()).as("leads.%s", c).isEqualTo(ReadableExportPolicy.Treatment.INCLUDE);
        }
        for (String c : List.of("gst_number", "billing_address", "shipping_address", "phone", "email")) {
            assertThat(column("customers", c).treatment()).as("customers.%s", c).isEqualTo(ReadableExportPolicy.Treatment.INCLUDE);
        }
        assertThat(column("employees", "phone").treatment()).isEqualTo(ReadableExportPolicy.Treatment.INCLUDE);
        assertThat(column("opportunities", "contact_phone").treatment()).isEqualTo(ReadableExportPolicy.Treatment.INCLUDE);
    }

    @Test
    void everyWithheldColumnAndExcludedTable_hasAReason_andNoIncludedColumnLooksLikeASecret() {
        for (ReadableExportPolicy.TableRule t : CRM.tables()) {
            assertThat(t.description()).isNotBlank();
            for (ReadableExportPolicy.ColumnRule c : t.columns().values()) {
                if (c.treatment() == ReadableExportPolicy.Treatment.WITHHOLD) {
                    assertThat(c.reason()).as("%s.%s", t.name(), c.name()).isNotBlank();
                } else {
                    assertThat(ReadableExportPolicy.SECRET_NAME.matcher(c.name()).matches())
                            .as("%s.%s is included but looks like a credential", t.name(), c.name()).isFalse();
                }
            }
        }
        for (ReadableExportPolicy.ExcludedTable x : CRM.excludedTables()) {
            assertThat(x.reason()).as(x.name()).isNotBlank();
        }
        assertThat(CRM.excludedTables()).extracting(ReadableExportPolicy.ExcludedTable::name)
                .containsExactlyInAnyOrder("email_oauth_state", "flyway_schema_history", "db_backup");
    }

    // ---- fail-closed rules -------------------------------------------------

    @Test
    void anUnknownTableOrColumn_isReportedAsUnreviewed() {
        Map<String, List<String>> schema = Map.of(
                "employees", List.of("id", "name", "password", "shiny_new_column"),
                "brand_new_table", List.of("id"),
                "email_oauth_state", List.of("anything", "at_all"));

        assertThat(CRM.unreviewed(schema)).containsExactly("brand_new_table", "employees.shiny_new_column");
    }

    @Test
    void aCredentialLikeColumn_canNeverBeIncluded() {
        for (String name : List.of("password", "api_key", "apikey", "access_token", "client_secret", "code_verifier", "nonce")) {
            assertThatThrownBy(() -> new ReadableExportPolicy.Builder().table("t", "d").include(name))
                    .as(name).isInstanceOf(IllegalStateException.class).hasMessageContaining("credential");
        }
    }

    @Test
    void misconfiguredPolicies_areRejectedWhenBuilt() {
        // withheld without a reason
        assertThatThrownBy(() -> new ReadableExportPolicy.Builder().table("t", "d").withhold("x", " "))
                .isInstanceOf(IllegalStateException.class);
        // the name column must be exported
        assertThatThrownBy(() -> new ReadableExportPolicy.Builder().table("t", "d").include("id")
                .withhold("name", "r").label("name").done())
                .isInstanceOf(IllegalStateException.class);
        // a column decided twice
        assertThatThrownBy(() -> new ReadableExportPolicy.Builder().table("t", "d").include("id").withhold("id", "r"))
                .isInstanceOf(IllegalStateException.class);
        // a table both exported and excluded
        assertThatThrownBy(() -> new ReadableExportPolicy.Builder().exclude("t", "r").table("t", "d").include("id").done())
                .isInstanceOf(IllegalStateException.class);
        // a reference to a table with no policy
        assertThatThrownBy(() -> new ReadableExportPolicy.Builder().table("t", "d").include("x_id")
                .refersTo("x_id", "nowhere").done().build())
                .isInstanceOf(IllegalStateException.class);
        // unsafe names
        assertThatThrownBy(() -> new ReadableExportPolicy.Builder().table("T; DROP", "d"))
                .isInstanceOf(IllegalStateException.class);
    }
}
