package com.procurepal_services.stock_bridge_api.superadmin;

import com.procurepal_services.stock_bridge_api.superadmin.dto.CatalogResetPreview;
import java.util.List;
import lombok.Getter;

/**
 * The two ways a catalog reset says no, in one file for the reason {@code ImportExceptions}
 * gives: each is a handful of lines and the set is easier to see together than apart.
 */
public final class CatalogResetExceptions {

    private CatalogResetExceptions() {
    }

    /**
     * 409 - the tenant's products have been ordered, so deleting them would orphan order
     * history. Carries the blocker list rather than only a message, so the caller can render
     * which products are in the way instead of a dead-end toast.
     */
    @Getter
    public static class Blocked extends RuntimeException {
        private final transient CatalogResetPreview body;

        public Blocked(CatalogResetPreview body) {
            super(body.message());
            this.body = body;
        }
    }

    /** 400 - confirmPhrase did not match the target tenant. See CatalogResetRequest. */
    public static class NotConfirmed extends RuntimeException {
        public NotConfirmed(String requiredPhrase) {
            super("This reset was not confirmed. Type \"" + requiredPhrase + "\" exactly to proceed.");
        }
    }
}
