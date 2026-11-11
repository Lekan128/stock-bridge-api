package com.procurepal_services.stock_bridge_api.branch;

/**
 * A write named no branch, at a company with more than one (D-B10). Refused rather than recorded at
 * the main branch, because the likely sender is an old copy of the app that doesn't know about
 * branches, and recording at a guess would put stock at the wrong branch with nobody seeing it. Maps
 * to 400: the request is missing something it needs.
 */
public class BranchRequiredException extends RuntimeException {

    public BranchRequiredException() {
        super("Choose the branch this is for. If the app doesn't ask, reload it to get the latest version.");
    }
}
