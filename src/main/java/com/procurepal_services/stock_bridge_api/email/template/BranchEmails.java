package com.procurepal_services.stock_bridge_api.email.template;

import static com.procurepal_services.stock_bridge_api.email.template.EmailLayout.Detail;
import static com.procurepal_services.stock_bridge_api.email.template.EmailLayout.button;
import static com.procurepal_services.stock_bridge_api.email.template.EmailLayout.detailTable;
import static com.procurepal_services.stock_bridge_api.email.template.EmailLayout.page;
import static com.procurepal_services.stock_bridge_api.email.template.EmailLayout.paragraph;

import com.procurepal_services.stock_bridge_api.analytics.dto.BranchTableResponse;
import com.procurepal_services.stock_bridge_api.email.EmailKind;
import com.procurepal_services.stock_bridge_api.email.EmailMessage;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * The owner's evening digest of every branch (MULTI_BRANCH_UX_PLAN.md section 5.13): the branch table
 * as an email, sent only to people who turned it on. Transactional - about their own business, asked
 * for - so it needs a verified address and nothing else.
 */
public final class BranchEmails {

    private BranchEmails() {
    }

    public static EmailMessage dailyDigest(List<String> to, String companyName, BranchTableResponse table, String appBaseUrl) {
        List<Detail> rows = new ArrayList<>();
        StringBuilder text = new StringBuilder("Today at " + companyName + "\n\n");
        for (BranchTableResponse.Row row : table.rows()) {
            String line = naira(row.salesToday()) + " sales · " + row.low() + " low · " + row.out() + " out"
                    + (row.missing() > 0 ? " · " + row.missing() + " missing" : "")
                    + (row.waiting() + row.needsYou() > 0 ? " · " + (row.waiting() + row.needsYou()) + " waiting on a phone" : "");
            rows.add(new Detail(row.name(), line));
            text.append(row.name()).append(": ").append(line).append('\n');
        }
        BranchTableResponse.Totals totals = table.totals();
        String all = naira(totals.salesToday()) + " sales · stock worth " + naira(totals.stockWorth());
        rows.add(new Detail("All branches", all));
        text.append("\nAll branches: ").append(all).append("\n\n")
                .append("Open your branches: ").append(appBaseUrl).append("/app\n\n")
                .append("You get this because you turned on the daily summary. Turn it off from the dashboard.\n");

        String body = paragraph("Here is how each branch closed today.")
                + detailTable(rows)
                + button("Open your branches", appBaseUrl + "/app")
                + paragraph("You get this because you turned on the daily summary. Turn it off from the dashboard, "
                        + "under Your branches.");
        return new EmailMessage(to, "Today at " + companyName + ": every branch",
                page("Today at your branches", "Sales, low stock and anything missing, branch by branch.", body),
                text.toString(), EmailKind.TRANSACTIONAL);
    }

    private static String naira(BigDecimal amount) {
        BigDecimal value = amount == null ? BigDecimal.ZERO : amount.setScale(0, RoundingMode.HALF_UP);
        return "₦" + String.format("%,d", value.longValue());
    }
}
