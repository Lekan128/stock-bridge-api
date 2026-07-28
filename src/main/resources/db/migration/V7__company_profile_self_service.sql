-- One permission: a tenant may edit its OWN company record.
--
-- Until now every clients row was written by somebody else - ClientSignupService
-- at signup, or a super admin through /api/superadmin/clients. That left a
-- company with a typo in its name, a dead phone number or a departed admin's
-- email address with no way to fix it except a support ticket, which is a
-- ridiculous amount of friction for three descriptive columns. MANAGE_COMPANY_
-- PROFILE is what /api/company (PUT) gates on.
--
-- WHAT THE PERMISSION DELIBERATELY DOES NOT GRANT
-- The endpoint behind it binds a purpose-built request DTO carrying exactly
-- name, phone and admin contact email. It cannot reach:
--   is_platform_owner - a tenant declaring itself the marketplace operator would
--                       hand it the public catalog. ClientSignupService states
--                       the same rule for signup; this honours it.
--   payment_terms     - pay-on-delivery is a commercial decision ProcurePal ops
--                       make about a customer. Self-granted credit is theft with
--                       extra steps.
--   is_active         - suspension is a super-admin action. A suspended tenant
--                       un-suspending itself defeats the entire mechanism.
--   slug              - the login identifier (unique, findBySlug). Changing it
--                       locks out every user of the company at once and breaks
--                       every stored reference to them.
-- None of that is expressible as a grant, which is exactly why the allow-list
-- lives in the DTO rather than here - this row only says "may edit the company
-- profile", and the API defines what "profile" means.
--
-- IDEMPOTENCY AND OUT-OF-ORDER APPLICATION
-- Same property V5 and V6 were written for: on a fresh database Flyway runs
-- V1..V7 and then db/seed/V9000+, but on a local database seeded before this
-- file existed the seed has already run and this arrives out of order behind it
-- (spring.flyway.out-of-order in application-local.yml). Both orderings must
-- produce the same rows, so the permission insert is ON CONFLICT (code) DO
-- NOTHING and the grant is joined from a VALUES list with ON CONFLICT
-- (role_id, permission_id) DO NOTHING.
--
-- WHY A NARROW ADDITIVE INSERT, NOT V5/V6's DELETE-AND-RESTATE
-- V5 and V6 each rebuilt role_permissions for the five roles so the whole matrix
-- read in one place. That was worth it when they were re-cutting the matrix
-- wholesale (V5 renamed three roles, V6 added nine permissions across all five).
-- Here there is exactly one new grant, and restating V6's forty-odd rows to add
-- it would mean the correctness of every EXISTING grant depends on transcribing
-- that list perfectly - one dropped line silently revokes a permission, with no
-- error and no failing migration, on every database that runs this. The blast
-- radius of the additive form is one row; the blast radius of the restating form
-- is the whole matrix. Additive wins.
--
-- The cost is that the full matrix is no longer readable in a single file. That
-- is a real loss and it is accepted knowingly: RolePermissionMatrixIntegration
-- Test asserts the complete per-role set against the live API, which is a
-- better place for the canonical answer than a migration nobody re-reads.

INSERT INTO permissions (code, description) VALUES
    ('MANAGE_COMPANY_PROFILE', 'Edit the company''s own name, phone and admin contact email')
ON CONFLICT (code) DO NOTHING;

-- ============================================================================
-- Granted to OWNER only.
--
-- V5 describes OWNER as "The account holder" and that is precisely the job this
-- permission belongs to. The three editable fields are not operational data -
-- they are who the account IS. admin_contact_email in particular is where every
-- piece of account correspondence ProcurePal sends ends up, so being able to
-- change it is adjacent to being able to take the account over; the account
-- holder is the only person who should hold that.
--
-- Considered and rejected, for the record:
--   PROCUREMENT_MANAGER - the closest call. It already holds MANAGE_DELIVERY_
--     ADDRESSES, so the argument "it maintains the company's details" is
--     available. But its remit (V5: "Owns the product catalog, moves stock, and
--     views analytics") is operational, and the address book is operational data
--     it genuinely needs to do that job. The company's legal name and contact of
--     record are not; letting a buyer redirect account correspondence is a
--     bigger grant than anything else in its set.
--   INVENTORY_OFFICER / STOREKEEPER - floor and stock jobs, no account concern
--     whatsoever.
--   FINANCE_OFFICER - explicitly read-only by design (V5: "changes nothing").
--     Making the one exception to that be the company's contact details would be
--     a strange first crack in a deliberately sealed role.
--
-- If PROCUREMENT_MANAGER should get it after all, that is a two-line additive
-- migration and no revocation risk - which is the other reason this file does
-- not restate the matrix.
--
-- Note the READ side (GET /api/company) carries no permission requirement at
-- all: every authenticated tenant user may see their own company, the same way
-- /api/me already hands them the company name, identifier and platform-owner
-- flag. See CompanyController for that reasoning.
-- ============================================================================
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM (VALUES
    ('OWNER', 'MANAGE_COMPANY_PROFILE')
) AS v(role_name, permission_code)
JOIN roles r ON r.name = v.role_name
JOIN permissions p ON p.code = v.permission_code
ON CONFLICT (role_id, permission_id) DO NOTHING;
