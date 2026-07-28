package com.procurepal_services.stock_bridge_api.superadmin;

/**
 * No client has {@code is_platform_owner = TRUE}, so there is no ProcurePal
 * tenant to manage users for.
 *
 * <h2>Why this is a real, expected state rather than a broken invariant</h2>
 * The platform owner is created either by {@code db/seed/V9001__seed_procurepal_
 * marketplace.sql} (local and docker profiles only) or by
 * {@code PlatformOwnerBootstrapRunner} from environment variables at startup. A
 * production database that has never had those variables set therefore has no
 * platform owner at all, and the first thing a super admin does on a fresh
 * deployment may well be to open this screen and find out. That is a
 * configuration step somebody has not done yet - not a bug, not a missing row
 * somebody deleted - so the answer has to be a sentence that says which step,
 * not a stack trace.
 *
 * <h2>Why 409 rather than 404</h2>
 * {@code /api/superadmin/platform-owner/users} is not a lookup by id: the URL
 * names a permanent, singular surface that always exists, and the caller did not
 * ask for a resource that might not be there. What is missing is a precondition
 * of the surface, which is exactly the shape of failure 409 describes and the
 * same shape LastActiveOwnerException uses ("the request is well-formed and you
 * are allowed to make it; the state of the system says no"). A 404 would send
 * the reader looking for a typo in a path that is spelled correctly.
 *
 * <p>Deliberately NOT reusing PlatformOwnerNotAllowedException: that one means
 * "you are not the platform owner" and answers 403 for a tenant probing a
 * marketplace-admin endpoint. Here the caller's authority is not in question -
 * they are a super admin on a super-admin surface - only the data's existence is.
 */
public class PlatformOwnerNotBootstrappedException extends RuntimeException {

    public PlatformOwnerNotBootstrappedException() {
        super("No platform owner tenant exists yet. Bootstrap ProcurePal first by setting the "
                + "app.platform-owner.* configuration (admin-email and admin-password at minimum) "
                + "and restarting the service, then retry this request.");
    }
}
