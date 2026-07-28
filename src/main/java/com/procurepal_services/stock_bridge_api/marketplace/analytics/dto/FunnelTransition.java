package com.procurepal_services.stock_bridge_api.marketplace.analytics.dto;

import java.math.BigDecimal;

/**
 * How long one hop of the fulfilment chain took, over the orders dated in the window that
 * actually completed that hop.
 *
 * <h2>Where the timestamps come from</h2>
 * {@code MIN(order_status_events.created_at)} for the target status, falling back to the
 * matching milestone column on orders when no event row exists (an order seeded or
 * migrated straight into a status). MIN, not MAX: a status reached twice - which the state
 * machine forbids today but the audit trail could still contain - should be measured from
 * the first time it happened.
 *
 * <h2>Why both mean and median</h2>
 * One order confirmed after a fortnight's public holiday moves the mean and not the
 * median. Shipping only the mean would have ops chasing a number that describes an
 * outlier; shipping only the median would hide that the outlier exists.
 *
 * @param sampleSize orders contributing. Both figures are null (and therefore OMITTED from
 *     the JSON, since default-property-inclusion is non_null) when this is zero - an
 *     average of nothing is not zero hours, and rendering it as "0h" would read as
 *     instant fulfilment. Clients must treat the fields as undefined, not null.
 * @param averageHours mean hop duration in hours, 2dp.
 * @param medianHours p50 hop duration in hours, 2dp.
 */
public record FunnelTransition(
        String transition, String fromStage, String toStage, long sampleSize, BigDecimal averageHours, BigDecimal medianHours) {
}
