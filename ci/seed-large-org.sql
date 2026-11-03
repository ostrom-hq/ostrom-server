-- SPDX-License-Identifier: AGPL-3.0-only
--
-- Two organizations for the console's members search against the real server (ostrom-web
-- e2e-real/membersSearch.spec.ts), written by console-journey.yml once the image has migrated, as the migration role.
-- The console shows its search box past one page of members (100); the free plan's 5 seats (`FreePlanSeats`) keep the
-- API from making such a team, so this file does, and nothing in the server changes.
--
-- * "Search crowd" (`search-crowd`): 121 people. Its owner signs in with the usual e-mailed link, at
--   search-owner@search-crowd.e2e.ostrom.test (a verified address: the sign-in finds this account). Then 117 people
--   `crowd-001` to `crowd-117`, and, joined last, so beyond the first page of 100 in the members' order
--   (`joined_at`, `id`): `zoe-late` (found by login) and `crowd-119`, named "Zoey Byname" (found by name).
-- * "Search elsewhere" (`search-elsewhere`): its owner and `zoe-elsewhere`, whom a search of the crowd never shows.
--
-- Fixed ids and `ON CONFLICT DO NOTHING`: played twice, it writes nothing more.
-- The orgs tables force row-level security on `ostrom.org_id` (OrgsMigrations), so each organization's rows are
-- written with the setting on that organization, as the server's `inOrg` does.

BEGIN;

INSERT INTO identity_users (id, login, display_name, primary_email, avatar_url, created_at)
SELECT md5('search-seed ' || p.login)::uuid, p.login, p.name, p.email, NULL, TIMESTAMPTZ '2026-11-01 09:00:00+00'
FROM (
    VALUES ('search-owner', 'Search Owner', 'search-owner@search-crowd.e2e.ostrom.test'),
           ('zoe-late', 'Late Joiner', NULL),
           ('crowd-119', 'Zoey Byname', NULL),
           ('elsewhere-owner', 'Elsewhere Owner', NULL),
           ('zoe-elsewhere', 'Zoe Elsewhere', NULL)
) AS p (login, name, email)
UNION ALL
SELECT md5('search-seed crowd-' || lpad(n::text, 3, '0'))::uuid, 'crowd-' || lpad(n::text, 3, '0'),
       'Crowd ' || lpad(n::text, 3, '0'), NULL, NULL, TIMESTAMPTZ '2026-11-01 09:00:00+00'
FROM generate_series(1, 117) AS n
ON CONFLICT DO NOTHING;

INSERT INTO identity_user_emails (user_id, email, verified, created_at)
VALUES (md5('search-seed search-owner')::uuid, 'search-owner@search-crowd.e2e.ostrom.test', TRUE,
        TIMESTAMPTZ '2026-11-01 09:00:00+00')
ON CONFLICT DO NOTHING;

-- Search crowd: the owner first, the crowd one second apart, then the two people of the second page.
SELECT set_config('ostrom.org_id', 'a5ea7c00-0000-4000-8000-000000000001', TRUE);
INSERT INTO orgs_organizations (id, slug, name, created_at)
VALUES ('a5ea7c00-0000-4000-8000-000000000001', 'search-crowd', 'Search crowd', TIMESTAMPTZ '2026-11-01 09:00:00+00')
ON CONFLICT DO NOTHING;
INSERT INTO orgs_members (id, org_id, user_id, role, joined_at)
SELECT md5('search-seed member ' || m.login)::uuid, 'a5ea7c00-0000-4000-8000-000000000001',
       md5('search-seed ' || m.login)::uuid, m.role, TIMESTAMPTZ '2026-11-01 10:00:00+00' + m.n * INTERVAL '1 second'
FROM (
    SELECT 'search-owner' AS login, 'owner' AS role, 0 AS n
    UNION ALL
    SELECT 'crowd-' || lpad(n::text, 3, '0'), 'member', n FROM generate_series(1, 117) AS n
    UNION ALL
    SELECT 'zoe-late', 'member', 118
    UNION ALL
    SELECT 'crowd-119', 'member', 119
) AS m
ON CONFLICT DO NOTHING;

-- Search elsewhere: another team, whose Zoe a search of the crowd must not show.
SELECT set_config('ostrom.org_id', 'a5ea7c00-0000-4000-8000-000000000002', TRUE);
INSERT INTO orgs_organizations (id, slug, name, created_at)
VALUES ('a5ea7c00-0000-4000-8000-000000000002', 'search-elsewhere', 'Search elsewhere',
        TIMESTAMPTZ '2026-11-01 09:00:00+00')
ON CONFLICT DO NOTHING;
INSERT INTO orgs_members (id, org_id, user_id, role, joined_at)
SELECT md5('search-seed member ' || m.login)::uuid, 'a5ea7c00-0000-4000-8000-000000000002',
       md5('search-seed ' || m.login)::uuid, m.role, TIMESTAMPTZ '2026-11-01 10:00:00+00' + m.n * INTERVAL '1 second'
FROM (VALUES ('elsewhere-owner', 'owner', 0), ('zoe-elsewhere', 'member', 1)) AS m (login, role, n)
ON CONFLICT DO NOTHING;

COMMIT;
