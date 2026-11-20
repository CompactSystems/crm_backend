package com.compact.crm.service;

import com.compact.crm.dto.request.FollowUpRequest;
import com.compact.crm.dto.request.OpportunityRequest;
import com.compact.crm.dto.response.FollowUpSummaryResponse;
import com.compact.crm.dto.search.FollowUpSearchCriteria;
import com.compact.crm.entity.ActivityLog;
import com.compact.crm.entity.ActivityType;
import com.compact.crm.entity.Employee;
import com.compact.crm.entity.FollowUp;
import com.compact.crm.entity.Industry;
import com.compact.crm.entity.Lead;
import com.compact.crm.entity.LeadSourceMaster;
import com.compact.crm.entity.Opportunity;
import com.compact.crm.entity.Permission;
import com.compact.crm.entity.Role;
import com.compact.crm.entity.RolePermission;
import com.compact.crm.entity.SalesStage;
import com.compact.crm.enums.ActivityModule;
import com.compact.crm.enums.FollowUpStatus;
import com.compact.crm.enums.LeadStatus;
import com.compact.crm.enums.LeadValidity;
import com.compact.crm.enums.SalesStageType;
import com.compact.crm.enums.Scope;
import com.compact.crm.repository.ActivityLogRepository;
import com.compact.crm.repository.ActivityTypeRepository;
import com.compact.crm.repository.EmployeeRepository;
import com.compact.crm.repository.FollowUpRepository;
import com.compact.crm.repository.IndustryRepository;
import com.compact.crm.repository.LeadRepository;
import com.compact.crm.repository.LeadSourceMasterRepository;
import com.compact.crm.repository.OpportunityRepository;
import com.compact.crm.repository.PermissionRepository;
import com.compact.crm.repository.RolePermissionRepository;
import com.compact.crm.repository.RoleRepository;
import com.compact.crm.security.AccessControlService;
import com.compact.crm.security.CurrentUserService;
import com.compact.crm.util.BusinessClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.compact.crm.security.AccessControlService.CUSTOMER_VIEW;
import static com.compact.crm.security.AccessControlService.FOLLOWUP_MANAGE;
import static com.compact.crm.security.AccessControlService.FOLLOWUP_VIEW;
import static com.compact.crm.security.AccessControlService.LEAD_MANAGE;
import static com.compact.crm.security.AccessControlService.LEAD_VIEW;
import static com.compact.crm.security.AccessControlService.OPPORTUNITY_MANAGE;
import static com.compact.crm.security.AccessControlService.OPPORTUNITY_VIEW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The follow-up lifecycle around an opportunity (real services + real H2):
 * pending follow-ups are CANCELLED exactly when the opportunity moves INTO
 * WON / LOST / DROPPED (never on POSTPONED, never on a later edit of an
 * already-closed opportunity), a reopened opportunity keeps them cancelled and
 * still needs a fresh next action, an identical resubmitted follow-up is not
 * duplicated while genuinely different ones are, and the Follow-ups summary
 * counts are exact.
 */
@DataJpaTest
@Import({LeadService.class, OpportunityService.class, FollowUpService.class,
        CustomerService.class, SalesStageService.class, ActivityLogService.class,
        AccessControlService.class, CurrentUserService.class})
class FollowUpLifecycleIntegrationTest {

    @Autowired private OpportunityService opportunityService;
    @Autowired private SalesStageService salesStageService;
    @Autowired private FollowUpService followUpService;

    @Autowired private LeadRepository leadRepository;
    @Autowired private OpportunityRepository opportunityRepository;
    @Autowired private FollowUpRepository followUpRepository;
    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private PermissionRepository permissionRepository;
    @Autowired private RolePermissionRepository rolePermissionRepository;
    @Autowired private IndustryRepository industryRepository;
    @Autowired private LeadSourceMasterRepository leadSourceMasterRepository;
    @Autowired private ActivityTypeRepository activityTypeRepository;
    @Autowired private ActivityLogRepository activityLogRepository;

    private Employee admin;
    private Industry industry;
    private LeadSourceMaster leadSource;
    private ActivityType call;
    private ActivityType visit;
    private int seq;

    private final Map<String, Permission> permissionsByCode = new HashMap<>();

    @BeforeEach
    void setUp() {

        Role adminRole = roleRepository.save(Role.builder().name("ADMIN").rank(100).build());

        for (String code : List.of(LEAD_VIEW, LEAD_MANAGE, CUSTOMER_VIEW, OPPORTUNITY_VIEW, OPPORTUNITY_MANAGE,
                FOLLOWUP_VIEW, FOLLOWUP_MANAGE)) {

            Permission p = permissionsByCode.computeIfAbsent(
                    code, c -> permissionRepository.save(Permission.builder().code(c).build()));

            rolePermissionRepository.save(RolePermission.builder()
                    .role(adminRole).permission(p).scope(Scope.ALL).build());
        }

        admin = employeeRepository.save(Employee.builder()
                .name("Admin").email("admin@test.com").phone("9000000001").password("x").role(adminRole).build());

        industry = industryRepository.save(Industry.builder().name("Manufacturing").build());
        leadSource = leadSourceMasterRepository.save(LeadSourceMaster.builder().name("Referral").build());
        call = activityTypeRepository.save(ActivityType.builder().name("Call").build());
        visit = activityTypeRepository.save(ActivityType.builder().name("Visit").build());

        salesStageService.ensureCoreStages();

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(admin.getEmail(), null, List.of()));
    }

    // ------------------------------------------------------------------ fixtures

    private Lead lead(String company) {

        seq++;

        return leadRepository.save(Lead.builder()
                .companyName(company).contactPerson("Contact").phone("98000000" + (10 + seq))
                .email(company.toLowerCase().replace(' ', '.') + seq + "@example.com")
                .city("Pune").state("MH").industry(industry).leadSource(leadSource)
                .leadStatus(LeadStatus.VALID).leadValidity(LeadValidity.VALID)
                .assignedEmployee(admin).build());
    }

    private FollowUp saved(Opportunity opportunity, Lead lead, FollowUpStatus status, LocalDateTime when, String remarks) {

        return followUpRepository.save(FollowUp.builder()
                .opportunity(opportunity).lead(lead).employee(admin).activityType(call)
                .status(status).scheduledDate(when).remarks(remarks).build());
    }

    private LocalDateTime inDays(long days) {
        return BusinessClock.now().plusDays(days);
    }

    private OpportunityRequest request(String stage) {

        OpportunityRequest r = new OpportunityRequest();
        r.setTitle("Deal");
        r.setProductValue(1000.0);
        r.setExpectedClosingDate(BusinessClock.today().plusDays(30));
        r.setSalesStage(stage);
        r.setLeadValidity(LeadValidity.VALID);

        if ("LOST".equals(stage)) {
            r.setLostReason("Chose a competitor");
        }

        return r;
    }

    // An IN PROGRESS opportunity converted from a lead that already had a
    // pending next action (carried over on the LEAD).
    private Opportunity inProgress(String company) {

        Lead lead = lead(company);
        saved(null, lead, FollowUpStatus.PENDING, inDays(5), "Lead stage call");

        return opportunityService.convertLeadToOpportunity(lead.getId(), request("IN PROGRESS"));
    }

    private FollowUpStatus statusOf(FollowUp f) {
        return followUpRepository.findById(f.getId()).orElseThrow().getStatus();
    }

    private List<ActivityLog> autoCancelLog() {

        return activityLogRepository.findAll().stream()
                .filter(a -> a.getModule() == ActivityModule.FOLLOWUP
                        && a.getDescription() != null && a.getDescription().contains("cancelled automatically"))
                .toList();
    }

    // ------------------------------------------------------- 1-4: transitions

    @Test
    void movingToWon_cancelsEveryPendingFollowUp_ofTheOpportunityAndItsConvertedLead() {

        Opportunity opp = inProgress("Won Co");
        FollowUp a = saved(opp, null, FollowUpStatus.PENDING, inDays(2), "Send quote");
        FollowUp b = saved(opp, null, FollowUpStatus.PENDING, inDays(9), "Negotiate price");
        FollowUp overdue = saved(opp, null, FollowUpStatus.PENDING, inDays(-3), "Chase signature");
        FollowUp onLead = followUpRepository.findByLeadId(opp.getLead().getId()).get(0);

        opportunityService.updateOpportunity(opp.getId(), request("WON"));

        for (FollowUp f : List.of(a, b, overdue, onLead)) {
            FollowUp reloaded = followUpRepository.findById(f.getId()).orElseThrow();
            assertThat(reloaded.getStatus()).isEqualTo(FollowUpStatus.CANCELLED);
            assertThat(reloaded.getCompletedDate()).isNull();
        }

        // nothing deleted, one audit entry per cancelled follow-up
        assertThat(followUpRepository.count()).isEqualTo(4);
        assertThat(autoCancelLog()).hasSize(4);
        assertThat(autoCancelLog().get(0).getDescription()).contains("WON");
    }

    @Test
    void movingToLostOrDropped_behavesTheSame() {

        for (String stage : List.of("LOST", "DROPPED")) {

            Opportunity opp = inProgress(stage + " Co");
            FollowUp f = saved(opp, null, FollowUpStatus.PENDING, inDays(4), "Follow up the buyer");

            opportunityService.updateOpportunity(opp.getId(), request(stage));

            assertThat(statusOf(f)).as(stage).isEqualTo(FollowUpStatus.CANCELLED);
            assertThat(followUpRepository.findByLeadId(opp.getLead().getId()))
                    .allMatch(x -> x.getStatus() == FollowUpStatus.CANCELLED);
        }
    }

    @Test
    void completedMissedAndAlreadyCancelled_areLeftExactlyAsTheyWere() {

        Opportunity opp = inProgress("History Co");

        FollowUp done = saved(opp, null, FollowUpStatus.COMPLETED, inDays(-5), "Initial call");
        done.setCompletedDate(inDays(-5));
        followUpRepository.save(done);
        FollowUp missed = saved(opp, null, FollowUpStatus.MISSED, inDays(-4), "Missed call");
        FollowUp cancelled = saved(opp, null, FollowUpStatus.CANCELLED, inDays(-3), "Cancelled by user");
        LocalDateTime completedAt = done.getCompletedDate();

        opportunityService.updateOpportunity(opp.getId(), request("WON"));

        assertThat(statusOf(done)).isEqualTo(FollowUpStatus.COMPLETED);
        assertThat(followUpRepository.findById(done.getId()).orElseThrow().getCompletedDate()).isEqualTo(completedAt);
        assertThat(statusOf(missed)).isEqualTo(FollowUpStatus.MISSED);
        assertThat(statusOf(cancelled)).isEqualTo(FollowUpStatus.CANCELLED);

        // only the lead's carried-over pending follow-up was auto-cancelled
        assertThat(autoCancelLog()).hasSize(1);
    }

    @Test
    void otherOpportunitiesAndUnrelatedLeads_areUnaffected() {

        Opportunity closing = inProgress("Closing Co");
        Opportunity other = inProgress("Other Co");
        FollowUp otherOpp = saved(other, null, FollowUpStatus.PENDING, inDays(3), "Other deal call");
        Lead stranger = lead("Unconverted Co");
        FollowUp strangerLead = saved(null, stranger, FollowUpStatus.PENDING, inDays(3), "Cold call");

        opportunityService.updateOpportunity(closing.getId(), request("WON"));

        assertThat(statusOf(otherOpp)).isEqualTo(FollowUpStatus.PENDING);
        assertThat(statusOf(strangerLead)).isEqualTo(FollowUpStatus.PENDING);
        assertThat(followUpRepository.findByLeadId(other.getLead().getId()))
                .allMatch(x -> x.getStatus() == FollowUpStatus.PENDING);
    }

    @Test
    void convertingStraightIntoWon_cancelsTheLeadsPendingFollowUps() {

        Lead lead = lead("Direct Won Co");
        FollowUp onLead = saved(null, lead, FollowUpStatus.PENDING, inDays(5), "Lead call");

        opportunityService.convertLeadToOpportunity(lead.getId(), request("WON"));

        assertThat(statusOf(onLead)).isEqualTo(FollowUpStatus.CANCELLED);
    }

    // ------------------------------------------------------ 5: postponed

    @Test
    void postponing_cancelsNothing() {

        Opportunity opp = inProgress("Postponed Co");
        FollowUp f = saved(opp, null, FollowUpStatus.PENDING, inDays(6), "Check back after budget");

        opportunityService.updateOpportunity(opp.getId(), request("POSTPONED"));

        assertThat(statusOf(f)).isEqualTo(FollowUpStatus.PENDING);
        assertThat(followUpRepository.findByLeadId(opp.getLead().getId()))
                .allMatch(x -> x.getStatus() == FollowUpStatus.PENDING);
        assertThat(autoCancelLog()).isEmpty();
    }

    // ------------------------------------------- 6: edits of a closed opportunity

    @Test
    void savingAnAlreadyWonOpportunity_doesNotCancelNewAfterSalesFollowUps() {

        Opportunity opp = inProgress("After Sales Co");
        opportunityService.updateOpportunity(opp.getId(), request("WON"));

        FollowUpRequest afterSales = new FollowUpRequest();
        afterSales.setOpportunityId(opp.getId());
        afterSales.setEmployeeId(admin.getId());
        afterSales.setActivityTypeId(call.getId());
        afterSales.setStatus(FollowUpStatus.PENDING);
        afterSales.setScheduledDate(inDays(20));
        afterSales.setRemarks("Annual maintenance check-in");
        FollowUp created = followUpService.createFollowUp(afterSales);

        // unrelated edits of the Won opportunity, twice
        OpportunityRequest edit = request("WON");
        edit.setTitle("Deal (renamed)");
        opportunityService.updateOpportunity(opp.getId(), edit);
        opportunityService.updateOpportunity(opp.getId(), edit);

        assertThat(statusOf(created)).isEqualTo(FollowUpStatus.PENDING);
    }

    @Test
    void movingBetweenClosedStages_isNotATransitionIntoTerminal() {

        Opportunity opp = inProgress("Won Then Lost Co");
        opportunityService.updateOpportunity(opp.getId(), request("WON"));

        FollowUp fresh = saved(opp, null, FollowUpStatus.PENDING, inDays(15), "Re-engagement call");

        opportunityService.updateOpportunity(opp.getId(), request("LOST"));

        assertThat(statusOf(fresh)).isEqualTo(FollowUpStatus.PENDING);
    }

    // ------------------------------------------------------------ 7: reopen

    @Test
    void reopening_keepsCancelledFollowUpsCancelled_andStillNeedsAFreshNextAction() {

        Opportunity opp = inProgress("Reopen Co");
        FollowUp old = saved(opp, null, FollowUpStatus.PENDING, inDays(7), "Old stale task");

        opportunityService.updateOpportunity(opp.getId(), request("LOST"));
        assertThat(statusOf(old)).isEqualTo(FollowUpStatus.CANCELLED);

        // no qualifying follow-up any more -> reopening is rejected
        assertThatThrownBy(() -> opportunityService.updateOpportunity(opp.getId(), request("IN PROGRESS")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("needs a pending follow-up");

        assertThat(statusOf(old)).isEqualTo(FollowUpStatus.CANCELLED);

        // with a fresh next action it succeeds; the old ones stay cancelled
        OpportunityRequest reopen = request("IN PROGRESS");
        reopen.setNextActionDescription("Call the buyer again");
        reopen.setNextFollowUpDate(inDays(3));
        reopen.setNextActionActivityTypeId(call.getId());
        opportunityService.updateOpportunity(opp.getId(), reopen);

        assertThat(statusOf(old)).isEqualTo(FollowUpStatus.CANCELLED);
        assertThat(followUpRepository.findByOpportunityIdAndStatus(opp.getId(), FollowUpStatus.PENDING))
                .hasSize(1)
                .allMatch(f -> "Call the buyer again".equals(f.getRemarks()));
    }

    // -------------------------------------------- 8, 10: failed / consistent

    @Test
    void aRejectedTransition_leavesEveryFollowUpUntouched() {

        Opportunity opp = inProgress("Rejected Co");
        FollowUp f = saved(opp, null, FollowUpStatus.PENDING, inDays(4), "Quote follow-up");

        OpportunityRequest noReason = request("LOST");
        noReason.setLostReason(null);

        assertThatThrownBy(() -> opportunityService.updateOpportunity(opp.getId(), noReason))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(statusOf(f)).isEqualTo(FollowUpStatus.PENDING);
        assertThat(opportunityRepository.findById(opp.getId()).orElseThrow().getSalesStage().getName())
                .isEqualTo("IN PROGRESS");
        assertThat(followUpRepository.findByLeadId(opp.getLead().getId()))
                .allMatch(x -> x.getStatus() == FollowUpStatus.PENDING);
        assertThat(autoCancelLog()).isEmpty();
    }

    @Test
    void stageAndFollowUps_changeTogether() {

        Opportunity opp = inProgress("Together Co");
        saved(opp, null, FollowUpStatus.PENDING, inDays(4), "Quote follow-up");

        opportunityService.updateOpportunity(opp.getId(), request("WON"));

        assertThat(opportunityRepository.findById(opp.getId()).orElseThrow().getSalesStage().getStageType())
                .isEqualTo(SalesStageType.WON);
        assertThat(followUpRepository.findByOpportunityIdAndStatus(opp.getId(), FollowUpStatus.PENDING)).isEmpty();
        assertThat(followUpRepository.findByLeadIdAndStatus(opp.getLead().getId(), FollowUpStatus.PENDING)).isEmpty();
    }

    @Test
    void cancelOutstanding_isIdempotent() {

        Opportunity opp = inProgress("Idem Co");
        saved(opp, null, FollowUpStatus.PENDING, inDays(4), "Quote follow-up");

        assertThat(followUpService.cancelOutstandingForClosedOpportunity(opp, "WON")).isEqualTo(2);
        assertThat(followUpService.cancelOutstandingForClosedOpportunity(opp, "WON")).isZero();
        assertThat(autoCancelLog()).hasSize(2);
    }

    // --------------------------------------------------------- 11-13: duplicates

    private FollowUpRequest pendingRequest(Opportunity opp, ActivityType type, LocalDateTime when, String remarks) {

        FollowUpRequest r = new FollowUpRequest();
        r.setOpportunityId(opp.getId());
        r.setEmployeeId(admin.getId());
        r.setActivityTypeId(type.getId());
        r.setStatus(FollowUpStatus.PENDING);
        r.setScheduledDate(when);
        r.setRemarks(remarks);
        return r;
    }

    @Test
    void repeatedIdenticalCreate_returnsTheExistingFollowUp_notADuplicate() {

        Opportunity opp = inProgress("Double Click Co");
        LocalDateTime when = inDays(8).withNano(0);
        long before = followUpRepository.findByOpportunityId(opp.getId()).size();

        FollowUp first = followUpService.createFollowUp(pendingRequest(opp, call, when, "Send brochure"));
        FollowUp second = followUpService.createFollowUp(pendingRequest(opp, call, when, "  Send brochure "));

        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(followUpRepository.findByOpportunityId(opp.getId())).hasSize((int) before + 1);
    }

    @Test
    void genuinelyDifferentFollowUps_forTheSameOpportunity_areAllKept() {

        Opportunity opp = inProgress("Many Tasks Co");
        LocalDateTime when = inDays(8).withNano(0);

        FollowUp base = followUpService.createFollowUp(pendingRequest(opp, call, when, "Send brochure"));
        FollowUp otherTime = followUpService.createFollowUp(pendingRequest(opp, call, when.plusHours(2), "Send brochure"));
        FollowUp otherText = followUpService.createFollowUp(pendingRequest(opp, call, when, "Book site survey"));
        FollowUp otherType = followUpService.createFollowUp(pendingRequest(opp, visit, when, "Send brochure"));

        assertThat(List.of(base.getId(), otherTime.getId(), otherText.getId(), otherType.getId())).doesNotHaveDuplicates();
        assertThat(followUpRepository.findByOpportunityIdAndStatus(opp.getId(), FollowUpStatus.PENDING)).hasSize(4);
    }

    @Test
    void anIdenticalCompletedFollowUp_doesNotBlockANewPendingOne() {

        Opportunity opp = inProgress("History Dup Co");
        LocalDateTime when = inDays(8).withNano(0);

        FollowUp done = saved(opp, null, FollowUpStatus.COMPLETED, when, "Send brochure");

        FollowUp again = followUpService.createFollowUp(pendingRequest(opp, call, when, "Send brochure"));

        assertThat(again.getId()).isNotEqualTo(done.getId());
        assertThat(again.getStatus()).isEqualTo(FollowUpStatus.PENDING);
    }

    @Test
    void reschedulingUpdatesTheSameRecord() {

        Opportunity opp = inProgress("Reschedule Co");
        FollowUp f = followUpService.createFollowUp(pendingRequest(opp, call, inDays(8).withNano(0), "Send brochure"));
        long count = followUpRepository.count();

        FollowUpRequest move = pendingRequest(opp, call, inDays(12).withNano(0), "Send brochure");
        FollowUp moved = followUpService.updateFollowUp(f.getId(), move);

        assertThat(moved.getId()).isEqualTo(f.getId());
        assertThat(followUpRepository.count()).isEqualTo(count);
    }

    @Test
    void nextActionSavedWithAnOpportunityEdit_isNotDuplicatedByASecondIdenticalSave() {

        Lead lead = lead("Next Action Co");
        LocalDateTime when = inDays(6).withNano(0);
        Opportunity opp = opportunityService.convertLeadToOpportunity(lead.getId(), withNext(request("IN PROGRESS"), when));

        // same next action submitted again with an edit
        opportunityService.updateOpportunity(opp.getId(), withNext(request("IN PROGRESS"), when));

        assertThat(followUpRepository.findByOpportunityIdAndStatus(opp.getId(), FollowUpStatus.PENDING)).hasSize(1);
    }

    private OpportunityRequest withNext(OpportunityRequest r, LocalDateTime when) {

        r.setNextActionDescription("Confirm the specification");
        r.setNextFollowUpDate(when);
        r.setNextActionActivityTypeId(call.getId());
        return r;
    }

    // ------------------------------------------------ 13: completion + next action

    private FollowUpRequest completion(Opportunity opp, LocalDateTime scheduled) {

        FollowUpRequest r = pendingRequest(opp, call, scheduled, "Initial call");
        r.setStatus(FollowUpStatus.COMPLETED);
        r.setCompletedDate(BusinessClock.now());
        return r;
    }

    @Test
    void completing_createsTheRequiredNextAction_once() {

        Opportunity opp = inProgress("Complete Co");
        // no other qualifying follow-up may remain, so the next action is required
        for (FollowUp f : followUpRepository.findByLeadId(opp.getLead().getId())) {
            f.setStatus(FollowUpStatus.COMPLETED);
            followUpRepository.save(f);
        }
        FollowUp current = saved(opp, null, FollowUpStatus.PENDING, inDays(1).withNano(0), "Initial call");

        FollowUpRequest done = completion(opp, current.getScheduledDate());
        done.setNextActionDescription("Send the revised quotation");
        done.setNextFollowUpDate(inDays(5).withNano(0));
        done.setNextActionActivityTypeId(call.getId());

        followUpService.updateFollowUp(current.getId(), done);

        List<FollowUp> pending = followUpRepository.findByOpportunityIdAndStatus(opp.getId(), FollowUpStatus.PENDING);
        assertThat(pending).hasSize(1);
        assertThat(pending.get(0).getRemarks()).isEqualTo("Send the revised quotation");
    }

    @Test
    void completing_whenTheSameNextActionAlreadyExists_createsNoSecondOne() {

        Opportunity opp = inProgress("Complete Dup Co");
        for (FollowUp f : followUpRepository.findByLeadId(opp.getLead().getId())) {
            f.setStatus(FollowUpStatus.COMPLETED);
            followUpRepository.save(f);
        }
        FollowUp current = saved(opp, null, FollowUpStatus.PENDING, inDays(1).withNano(0), "Initial call");
        LocalDateTime nextAt = inDays(5).withNano(0);
        saved(opp, null, FollowUpStatus.PENDING, nextAt, "Send the revised quotation");

        FollowUpRequest done = completion(opp, current.getScheduledDate());
        done.setNextActionDescription("Send the revised quotation");
        done.setNextFollowUpDate(nextAt);
        done.setNextActionActivityTypeId(call.getId());

        followUpService.updateFollowUp(current.getId(), done);

        assertThat(followUpRepository.findByOpportunityIdAndStatus(opp.getId(), FollowUpStatus.PENDING)).hasSize(1);
    }

    // ------------------------------------------------------------ summary counts

    @Test
    void summary_countsEveryStatusExactly_notJustAPage() {

        Opportunity opp = inProgress("Summary Co");
        LocalDateTime today = BusinessClock.startOfToday();

        for (int i = 0; i < 60; i++) {
            saved(opp, null, FollowUpStatus.COMPLETED, today.minusDays(i + 1), "Past call " + i);
        }
        saved(opp, null, FollowUpStatus.MISSED, today.minusDays(2), "Missed");
        saved(opp, null, FollowUpStatus.CANCELLED, today.minusDays(2), "Cancelled");
        saved(opp, null, FollowUpStatus.PENDING, today.plusHours(10), "Due today");
        saved(opp, null, FollowUpStatus.PENDING, today.plusDays(3), "Later");

        FollowUpSummaryResponse s = followUpService.getSummary(new FollowUpSearchCriteria());

        // plus the lead-level pending follow-up the fixture carries over
        assertThat(s.completed()).isEqualTo(60);
        assertThat(s.missed()).isEqualTo(1);
        assertThat(s.cancelled()).isEqualTo(1);
        assertThat(s.pending()).isEqualTo(3);
        assertThat(s.dueToday()).isEqualTo(1);
        assertThat(s.total()).isEqualTo(65);
    }

    @Test
    void summary_ignoresTheStatusFilter_butHonoursTheOthers() {

        Opportunity opp = inProgress("Summary Filter Co");
        saved(opp, null, FollowUpStatus.COMPLETED, inDays(-1), "Done");

        FollowUpSearchCriteria onlyCompleted = new FollowUpSearchCriteria();
        onlyCompleted.setStatus(List.of(FollowUpStatus.COMPLETED));
        assertThat(followUpService.getSummary(onlyCompleted).pending()).isEqualTo(1);

        FollowUpSearchCriteria byOpportunity = new FollowUpSearchCriteria();
        byOpportunity.setOpportunityId(opp.getId());
        FollowUpSummaryResponse s = followUpService.getSummary(byOpportunity);
        assertThat(s.total()).isEqualTo(1);
        assertThat(s.completed()).isEqualTo(1);
        assertThat(s.pending()).isZero();
    }

    @Test
    void summary_dueToday_isAHalfOpenDay_onTheBusinessClock() {

        Opportunity opp = inProgress("Boundary Co");
        LocalDateTime today = BusinessClock.startOfToday();

        long before = followUpService.getSummary(new FollowUpSearchCriteria()).dueToday();

        saved(opp, null, FollowUpStatus.PENDING, today, "Exactly at midnight, today");
        saved(opp, null, FollowUpStatus.PENDING, today.plusHours(23).plusMinutes(59), "Last minute of today");
        saved(opp, null, FollowUpStatus.PENDING, today.plusDays(1), "Exactly at midnight, tomorrow");
        saved(opp, null, FollowUpStatus.PENDING, today.minusSeconds(1), "Last second of yesterday");
        saved(opp, null, FollowUpStatus.COMPLETED, today.plusHours(5), "Done today");

        assertThat(followUpService.getSummary(new FollowUpSearchCriteria()).dueToday()).isEqualTo(before + 2);
    }

    @Test
    void summary_usesTheSameVisibilityScopeAsTheList() {

        Opportunity adminsOpp = inProgress("Admins Co");
        saved(adminsOpp, null, FollowUpStatus.COMPLETED, inDays(-2), "Admin completed");

        Role employeeRole = roleRepository.save(Role.builder().name("EMPLOYEE").rank(10).build());
        rolePermissionRepository.save(RolePermission.builder()
                .role(employeeRole).permission(permissionsByCode.get(FOLLOWUP_VIEW)).scope(Scope.OWN).build());

        Employee asha = employeeRepository.save(Employee.builder()
                .name("Asha").email("asha@test.com").phone("9000000002").password("x").role(employeeRole).build());

        Lead ashaLead = leadRepository.save(Lead.builder()
                .companyName("Ashas Co").contactPerson("C").phone("9811111111").email("ashas@example.com")
                .city("Pune").state("MH").industry(industry).leadSource(leadSource)
                .leadStatus(LeadStatus.VALID).leadValidity(LeadValidity.VALID).assignedEmployee(asha).build());
        saved(null, ashaLead, FollowUpStatus.PENDING, inDays(2), "Asha pending");
        saved(null, ashaLead, FollowUpStatus.COMPLETED, inDays(-2), "Asha completed");

        FollowUpSearchCriteria all = new FollowUpSearchCriteria();

        // admin: everything, and the same total the list reports
        FollowUpSummaryResponse forAdmin = followUpService.getSummary(all);
        assertThat(forAdmin.total()).isEqualTo(followUpService.searchFollowUps(all, 0, 50, null, null).getTotalElements());
        assertThat(forAdmin.total()).isGreaterThan(2);

        // OWN scope: only the follow-ups on the employee's own leads, in the summary and in the list
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(asha.getEmail(), null, List.of()));

        FollowUpSummaryResponse forAsha = followUpService.getSummary(all);
        assertThat(forAsha.pending()).isEqualTo(1);
        assertThat(forAsha.completed()).isEqualTo(1);
        assertThat(forAsha.total()).isEqualTo(2);
        assertThat(followUpService.searchFollowUps(all, 0, 50, null, null).getTotalElements()).isEqualTo(2);
    }

    @Test
    void summary_total_matchesTheListForTheSameSearch_andNeverDoubleCounts() {

        // a follow-up naming BOTH the lead and its opportunity, plus one of each kind alone
        Opportunity opp = inProgress("Needle Co");
        saved(opp, opp.getLead(), FollowUpStatus.PENDING, inDays(3), "Both parents");
        saved(opp, null, FollowUpStatus.COMPLETED, inDays(-3), "Opportunity only");
        saved(null, lead("Needle Lead Co"), FollowUpStatus.MISSED, inDays(-4), "Lead only");
        saved(null, lead("Haystack Co"), FollowUpStatus.PENDING, inDays(5), "Unrelated");

        FollowUpSearchCriteria search = new FollowUpSearchCriteria();
        search.setSearch("needle");

        FollowUpSummaryResponse summary = followUpService.getSummary(search);

        // the list for the same search, every status
        long listTotal = followUpService.searchFollowUps(search, 0, 50, null, null).getTotalElements();
        assertThat(summary.total()).isEqualTo(listTotal);
        assertThat(summary.total()).isEqualTo(summary.pending() + summary.completed() + summary.missed() + summary.cancelled());

        // the status filter changes the list but not the breakdown
        FollowUpSearchCriteria searchAndStatus = new FollowUpSearchCriteria();
        searchAndStatus.setSearch("needle");
        searchAndStatus.setStatus(List.of(FollowUpStatus.COMPLETED));

        assertThat(followUpService.getSummary(searchAndStatus)).isEqualTo(summary);
        assertThat(followUpService.searchFollowUps(searchAndStatus, 0, 50, null, null).getTotalElements())
                .isEqualTo(summary.completed());

        // paging and sorting do not enter into it
        assertThat(followUpService.searchFollowUps(search, 0, 1, "status", "desc").getTotalElements()).isEqualTo(listTotal);
    }

    // ------------------------------------------------------- the stage rule itself

    @Test
    void closesOutstandingFollowUps_ruleTable() {

        SalesStage inProg = stage(SalesStageType.IN_PROGRESS);
        SalesStage postponed = stage(SalesStageType.POSTPONED);
        SalesStage won = stage(SalesStageType.WON);
        SalesStage lost = stage(SalesStageType.LOST);
        SalesStage dropped = stage(SalesStageType.LEGACY_DROPPED);
        SalesStage unresponsive = stage(SalesStageType.LEGACY_UNRESPONSIVE);
        SalesStage custom = stage(null);

        assertThat(OpportunityWorkflow.closesOutstandingFollowUps(inProg, won)).isTrue();
        assertThat(OpportunityWorkflow.closesOutstandingFollowUps(inProg, lost)).isTrue();
        assertThat(OpportunityWorkflow.closesOutstandingFollowUps(postponed, dropped)).isTrue();
        assertThat(OpportunityWorkflow.closesOutstandingFollowUps(null, won)).isTrue();
        assertThat(OpportunityWorkflow.closesOutstandingFollowUps(custom, lost)).isTrue();

        assertThat(OpportunityWorkflow.closesOutstandingFollowUps(inProg, postponed)).isFalse();
        assertThat(OpportunityWorkflow.closesOutstandingFollowUps(inProg, inProg)).isFalse();
        assertThat(OpportunityWorkflow.closesOutstandingFollowUps(won, won)).isFalse();
        assertThat(OpportunityWorkflow.closesOutstandingFollowUps(won, lost)).isFalse();
        assertThat(OpportunityWorkflow.closesOutstandingFollowUps(unresponsive, dropped)).isFalse();
        assertThat(OpportunityWorkflow.closesOutstandingFollowUps(lost, inProg)).isFalse();
        assertThat(OpportunityWorkflow.closesOutstandingFollowUps(inProg, unresponsive)).isFalse();
    }

    private static SalesStage stage(SalesStageType type) {
        return SalesStage.builder().name(String.valueOf(type)).stageType(type).build();
    }
}
