package com.procurepal_services.stock_bridge_api.marketplace.analytics;

/**
 * Bucket size for the revenue series.
 *
 * An enum rather than the free string {@code AnalyticsService} takes, because both values
 * below are interpolated into SQL as {@code date_trunc(...)} and {@code generate_series}
 * arguments. They are bound as parameters, not concatenated - but making the type itself
 * closed means there is no path by which caller text could ever reach either function,
 * rather than a validation call somebody has to remember to make first.
 */
public enum AnalyticsGranularity {
    DAY("day", "1 day"),
    WEEK("week", "1 week"),
    MONTH("month", "1 month");

    private final String datePart;
    private final String step;

    AnalyticsGranularity(String datePart, String step) {
        this.datePart = datePart;
        this.step = step;
    }

    /** The date_trunc field name. */
    public String datePart() {
        return datePart;
    }

    /** The generate_series stride, as a Postgres interval literal. */
    public String step() {
        return step;
    }
}
