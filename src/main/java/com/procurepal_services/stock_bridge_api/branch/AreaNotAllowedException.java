package com.procurepal_services.stock_bridge_api.branch;

/**
 * A write in an area the person doesn't work in (STOCK_AREAS_PART_3.md, D-E9) - typically saved on a phone
 * before their areas changed. Maps to 403, like {@link BranchNotAllowedException}, so the phone keeps it as
 * "needs you" with this sentence and records nothing.
 */
public class AreaNotAllowedException extends RuntimeException {

    public AreaNotAllowedException(String areaName) {
        super("You don't work in the " + areaName + ", so this wasn't recorded.");
    }
}
