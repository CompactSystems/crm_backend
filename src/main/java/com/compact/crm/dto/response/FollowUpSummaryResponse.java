package com.compact.crm.dto.response;

// Exact follow-up counts for the Follow-ups page (GET /api/followups/summary):
// the same visibility scope, search and filters as the list, but ignoring the
// status filter, so the Open / History / All tabs and the summary cards can show
// totals for the whole matching set rather than only the page on screen.
//  - pending / completed / missed / cancelled: matching follow-ups per status;
//  - total: all four added up;
//  - dueToday: PENDING follow-ups scheduled for today on the business clock.
public record FollowUpSummaryResponse(
        long total,
        long pending,
        long completed,
        long missed,
        long cancelled,
        long dueToday
) {
}
