package com.compact.crm.service.backup;

// The reviewed export policy of the CRM database (see ReadableExportPolicy for
// the rules). Every table and every column has an explicit decision; anything
// not listed here stops the readable export. Decisions:
//
//  - Business records and their full contact details (leads, opportunities,
//    customers, follow-ups, employees' names/e-mail/phone, products, ...) are
//    INCLUDED: the export is for authorised CRM administrators only.
//  - Credentials and authentication secrets are WITHHELD or EXCLUDED: password
//    hashes, the encrypted mailbox refresh token, the identifiers that bind a
//    mailbox to its Google/Microsoft sign-in, and the OAuth login state
//    (state hash, PKCE verifier, nonce). WAHA's API key is configuration, not
//    data - it is never in the database.
//  - Free-text consent/opt-out evidence (whatsapp_consent[_audit].evidence,
//    email_suppression[_audit].note) is WITHHELD by default after review: it
//    records how a person gave or withdrew consent and may hold personal notes.
//    The consent status, source, dates and who recorded it ARE exported. To
//    export the text, change withhold(...) to include(...) here (and update
//    docs/backup.md).
//  - Bookkeeping that is not CRM data is EXCLUDED: Flyway's migration history
//    (its latest version is in manifest.json) and the backup/export history.
//  - LEGACY columns: Hibernate ddl-auto=update adds columns but never drops
//    them, so the production database still has columns that older versions
//    of the entities created and that no current entity or migration mentions
//    (LEGACY_COLUMNS below). They hold the data of records made before the
//    current fields existed, so each has an explicit, reviewed decision like
//    any other column. None of them is a credential or personal note.
public final class ReadableExportPolicies {

    private ReadableExportPolicies() {
    }

    // Legacy columns known to exist in production (reported by the deployed
    // export on 2026-11-16), with what the older code stored in them:
    //   leads.website                 String: the company's website URL
    //   leads.interested_product      String: free-text product name(s), before lead_products
    //   leads.lead_source             enum LeadSource label (INDIA_MART, WEBSITE, ...), before lead_source_id
    //   leads.product_id              id of a products row (one product per lead), before lead_products
    //   opportunities.sales_stage     enum SalesStage label (NEW, QUOTATION_SENT, ...), before sales_stage_id
    //   followups.activity_type       enum ActivityType label (CALL, VISIT, ..., OTHER), before activity_type_id
    //   followups.custom_activity_type  short free-text activity label typed when OTHER was chosen
    // All are INCLUDED: they are ordinary business values of old records.
    // ReadableExportPostgresTest recreates them on a production-shaped schema.
    static final java.util.List<String> LEGACY_COLUMNS = java.util.List.of(
            "leads.website", "leads.interested_product", "leads.lead_source", "leads.product_id",
            "opportunities.sales_stage", "followups.activity_type", "followups.custom_activity_type");

    public static ReadableExportPolicy crm() {

        ReadableExportPolicy.Builder b = new ReadableExportPolicy.Builder();

        // ---- people and access ---------------------------------------------

        b.table("employees", "CRM users: name, role, manager, e-mail, phone, active flag.")
                .label("name")
                .include("id", "name", "email", "phone", "role_id", "manager_id", "is_active", "created_at", "updated_at")
                .withhold("password", "password hash (login credential)")
                .done();

        b.table("roles", "Roles (ADMIN, MANAGER, EMPLOYEE) and their rank.")
                .label("name")
                .include("id", "name", "role_rank")
                .done();

        b.table("permissions", "Permission codes.")
                .label("code")
                .include("id", "code")
                .done();

        b.table("role_permissions", "Which role has which permission, and its scope (ALL, TEAM, OWN).")
                .include("id", "role_id", "permission_id", "scope")
                .done();

        // ---- lookup lists --------------------------------------------------

        lookup(b, "industries", "Industry list.");
        lookup(b, "lead_sources", "Lead source list.");
        lookup(b, "products", "Product list.");
        lookup(b, "batteries", "Battery list.");
        lookup(b, "activity_types", "Follow-up activity types (call, visit, ...).");
        lookup(b, "lost_reasons", "Reasons an opportunity was lost.");

        b.table("sales_stages", "Opportunity stages.")
                .label("name")
                .include("id", "name", "stage_type", "is_active", "created_at", "updated_at")
                .done();

        // ---- the sales pipeline --------------------------------------------

        b.table("leads", "Leads: company, contact details, status, source, industry, assigned employee.")
                .label("company_name")
                .include("id", "company_name", "contact_person", "designation", "phone", "alternate_phone", "email",
                        "secondary_email", "city", "state", "pincode", "campaign_code", "industry_id",
                        "other_industry_detail", "description", "lead_status", "lead_validity", "lead_source_id",
                        "assigned_employee_id", "final_remarks", "invalid_reason", "contact_deadline", "closed_at",
                        "created_at", "updated_at")
                // legacy (see LEGACY_COLUMNS): business values of older leads
                .include("website", "interested_product", "lead_source", "product_id")
                .refersTo("product_id", "products")
                .done();

        b.table("lead_products", "Products a lead is interested in, with quantity.")
                .include("id", "lead_id", "product_id", "quantity")
                .done();

        b.table("lead_batteries", "Batteries a lead is interested in, with quantity.")
                .include("id", "lead_id", "battery_id", "quantity")
                .done();

        b.table("opportunities", "Opportunities: title, value, stage, expected closing, contact, lead.")
                .label("title")
                .include("id", "title", "product_value", "expected_closing_date", "sales_stage_id", "lead_validity",
                        "sub_status", "lost_reason", "postponed_review_date", "postponed_reason",
                        "postponed_reminder_acknowledged_for", "migrated_from_new_at", "retired_stage_type",
                        "retired_stage_name", "retired_stage_moved_at", "lead_id", "contact_person", "contact_phone",
                        "contact_email", "created_at", "updated_at")
                // legacy (see LEGACY_COLUMNS): the stage label of older opportunities
                .include("sales_stage")
                .done();

        b.table("opportunity_products", "Products on an opportunity, with quantity.")
                .include("id", "opportunity_id", "product_id", "quantity")
                .done();

        b.table("opportunity_batteries", "Batteries on an opportunity, with quantity.")
                .include("id", "opportunity_id", "battery_id", "quantity")
                .done();

        b.table("customers", "Customers: company, contact details, addresses, GST number, assigned employee.")
                .label("company_name")
                .include("id", "customer_code", "company_name", "contact_person", "designation", "phone",
                        "alternate_phone", "email", "secondary_email", "website", "city", "state", "pincode",
                        "billing_address", "shipping_address", "gst_number", "assigned_employee_id", "opportunity_id",
                        "created_at", "updated_at")
                .done();

        b.table("followups", "Follow-ups: lead/opportunity, employee, activity type, status, dates, remarks.")
                .include("id", "lead_id", "opportunity_id", "employee_id", "activity_type_id", "status",
                        "scheduled_date", "completed_date", "remarks", "created_at", "updated_at")
                // legacy (see LEGACY_COLUMNS): the activity label of older follow-ups
                .include("activity_type", "custom_activity_type")
                .done();

        // ---- history -------------------------------------------------------

        b.table("activity_log", "Activity history: who did what, when, and which fields changed.")
                .include("id", "employee_id", "employee_name", "action", "module", "entity_id", "entity_name",
                        "description", "field_name", "old_value", "new_value", "created_at")
                .done();

        b.table("documents", "Uploaded documents (names and details only - the files are not in this export).")
                .label("file_name")
                .include("id", "file_name", "stored_file_name", "content_type", "file_size_bytes", "category",
                        "product_id", "uploaded_by_employee_id", "uploaded_by_name", "is_active", "created_at")
                .done();

        // ---- e-mail ----------------------------------------------------------

        b.table("email_templates", "E-mail templates.")
                .label("name")
                .include("id", "name", "subject", "body", "type", "created_by_employee_id", "created_by_name",
                        "is_active", "is_default", "created_at", "updated_at")
                .refersTo("created_by_employee_id", "employees")
                .done();

        b.table("email_bulk_batch", "Bulk e-mail batches: sender, subject, body, recipients.")
                .include("id", "batch_id", "owner_employee_id", "target", "template_id", "subject", "body",
                        "content_hash", "recipient_ids", "recipient_count", "sender_provider", "sender_email", "created_at")
                .refersTo("owner_employee_id", "employees")
                .refersTo("template_id", "email_templates")
                .done();

        b.table("email_send_log", "One row per e-mail sent from the CRM: recipient, status, sender.")
                .include("id", "batch_id", "target", "target_id", "record_label", "recipient_email", "status", "detail",
                        "template_id", "sent_by_employee_id", "sender_provider", "sender_email", "created_at", "updated_at")
                .refersTo("template_id", "email_templates")
                .refersTo("sent_by_employee_id", "employees")
                .done();

        b.table("email_suppression", "E-mail addresses that must not be e-mailed (unsubscribed, bounced, ...).")
                .include("id", "email_normalized", "reason", "source", "created_by_employee_id", "created_at")
                .withhold("note", "free-text note about an opt-out (personal data; withheld after review)")
                .refersTo("created_by_employee_id", "employees")
                .done();

        b.table("email_suppression_audit", "History of e-mail suppression changes.")
                .include("id", "email_normalized", "action", "reason", "performed_by_employee_id", "performed_at")
                .withhold("note", "free-text note about an opt-out (personal data; withheld after review)")
                .refersTo("performed_by_employee_id", "employees")
                .done();

        b.table("email_mailbox_account", "Employees' connected sending mailboxes (Gmail / Microsoft 365).")
                .include("id", "employee_id", "provider", "email_address", "granted_scopes", "status", "status_detail",
                        "connected_at", "last_used_at", "updated_at")
                .withhold("refresh_token_encrypted", "encrypted OAuth refresh token (authentication secret)")
                .withhold("provider_subject", "Google/Microsoft account identifier that binds the mailbox's sign-in")
                .withhold("provider_tenant_id", "Microsoft tenant identifier of the mailbox's sign-in")
                .refersTo("employee_id", "employees")
                .done();

        b.exclude("email_oauth_state",
                "short-lived OAuth sign-in state (state hash, PKCE verifier, nonce) - authentication secrets, no business data");

        // ---- WhatsApp --------------------------------------------------------

        b.table("whatsapp_templates", "WhatsApp message templates.")
                .label("name")
                .include("id", "name", "description", "body", "version", "is_active", "created_by_employee_id",
                        "created_by_name", "updated_by_employee_id", "meta_template_name", "meta_language_code",
                        "meta_body_params", "created_at", "updated_at")
                .refersTo("created_by_employee_id", "employees")
                .refersTo("updated_by_employee_id", "employees")
                .done();

        b.table("whatsapp_bulk_batch", "Bulk WhatsApp batches: template, body, recipients.")
                .include("id", "batch_id", "owner_employee_id", "target", "template_id", "template_version",
                        "template_name", "body", "content_hash", "recipient_ids", "recipient_count",
                        "meta_template_name", "meta_language_code", "meta_body_params", "created_at")
                .refersTo("owner_employee_id", "employees")
                .refersTo("template_id", "whatsapp_templates")
                .done();

        b.table("whatsapp_send_log", "One row per WhatsApp message sent: number, status, delivery.")
                .include("id", "batch_id", "target", "target_id", "record_label", "phone_e164", "status", "detail",
                        "provider_message_id", "delivery_status", "delivery_updated_at", "template_id",
                        "template_version", "sent_by_employee_id", "created_at", "updated_at")
                .refersTo("template_id", "whatsapp_templates")
                .refersTo("sent_by_employee_id", "employees")
                .done();

        b.table("whatsapp_consent", "WhatsApp consent per phone number: opted in / out, source, date.")
                .include("id", "phone_e164", "status", "source", "consented_at", "lead_id", "opportunity_id",
                        "recorded_by_employee_id", "created_at", "updated_at")
                .withhold("evidence", "free-text consent evidence (personal data; withheld after review)")
                .refersTo("lead_id", "leads")
                .refersTo("opportunity_id", "opportunities")
                .refersTo("recorded_by_employee_id", "employees")
                .done();

        b.table("whatsapp_consent_audit", "History of every WhatsApp opt-in / opt-out.")
                .include("id", "phone_e164", "previous_status", "new_status", "source", "consented_at", "lead_id",
                        "opportunity_id", "import_reference", "performed_by_employee_id", "performed_by_name",
                        "performed_at")
                .withhold("evidence", "free-text consent evidence (personal data; withheld after review)")
                .refersTo("lead_id", "leads")
                .refersTo("opportunity_id", "opportunities")
                .refersTo("performed_by_employee_id", "employees")
                .done();

        b.table("whatsapp_webhook_event", "WhatsApp delivery receipts received (technical; no message text).")
                .include("id", "event_key", "event_type", "provider_message_id", "ack", "delivery_status",
                        "session_name", "applied", "received_at")
                .done();

        // ---- bookkeeping -----------------------------------------------------

        b.exclude("flyway_schema_history",
                "database migration bookkeeping, not CRM data (the CRM schema version is in manifest.json)");
        b.exclude("db_backup",
                "history of backups and readable exports (shown on the Backups page), not CRM data");

        return b.build();
    }

    private static void lookup(ReadableExportPolicy.Builder b, String table, String description) {
        b.table(table, description)
                .label("name")
                .include("id", "name", "is_active", "created_at", "updated_at")
                .done();
    }
}
