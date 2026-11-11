package com.procurepal_services.stock_bridge_api.branch;

/**
 * A person's stock-out, sale, count or adjustment named no area, at a branch with more than one that
 * keeps stock (STOCK_AREAS_BUILD_PLAN.md D-E10). Refused rather than recorded in the Store, for the
 * reason {@link BranchRequiredException} gives. Maps to 400: the request is missing something it needs.
 */
public class AreaRequiredException extends RuntimeException {

    public AreaRequiredException() {
        super("Choose the area this is for. If the app doesn't ask, reload it to get the latest version.");
    }
}
