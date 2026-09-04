# SPDD Analysis: Current-User Self-Deletion (logged-in users delete their own account)

## Original Business Requirement

> Source: `requirements/user_deletion.md` (verbatim)

I want to add a new feature that would consist in deleting of the current user. Only logged in users can delete themselves, through the new button and api endpoint specific for that. As before I'd like to reuse existing services if possible. A user that is deleted is also logged out meaning the sessions will be destroyed.

If the user is not active, meaning there's an active session for them, it shouldn't be possible to delete them

As part of this work I'd like to do an full integration property test:
1. A new user is created
2. The user can log in
3. The user can log out
4. The user can log in
5. The user can delete its own user
6. A new user with the same email should be able to be created

To avoid test interference the test will finish with deleting the user.

## Domain Concept Identification

### Existing Concepts (from codebase)

- **User**: a registered identity — normalized unique email, one-way password hash, creation time. Persisted in `users`; the email's uniqueness is enforced by a database constraint and pre-checked at registration. Owns zero or more Sessions.
- **Session**: the server-side record that one User is logged in through one opaque token, with a fixed 12-hour lifetime and an exclusive expiry boundary. Persisted in `sessions` with a mandatory foreign key to the owning User. It is the *only* representation of "logged in": the browser holds the token in an HttpOnly `SESSION` cookie and the server always resolves it.
- **Current user resolution**: the existing service action that turns a presented token into the logged-in user, treating `null`, unknown, ended and expired tokens uniformly as "not logged in" (deleting expired rows lazily). This is the natural gate for "only logged-in users can …".
- **Logout**: deletes the presented session only; idempotent and never fails. Deletion needs a stricter variant of this (see rules below), so the two must not be confused.
- **Session rotation on login**: a login that presents an existing session replaces it. Relevant to the integration scenario only in that the second login (after logout) presents no cookie.
- **UserService contract**: the single business interface (register / login / logout / currentUser) that the stakeholder wants extended rather than duplicated. All domain rules live here, behind narrow repository interfaces with hand-written in-memory fakes, so Hegel properties run without Spring.
- **Narrow repositories**: `UserRepository` (save, existsByEmail, findByEmail) and `SessionRepository` (save, findByToken, delete). Neither can currently delete a user or remove all sessions of a user.
- **Web adapter + unified error payload**: two controllers (`/api/users` for registration, `/api/session` for login/logout/query), `GlobalExceptionHandler` translating business exceptions to `ErrorResponse(code, messages)` with the codes `VALIDATION_ERROR`, `EMAIL_ALREADY_REGISTERED`, `INVALID_CREDENTIALS`.
- **Login page with two states**: `login.html` shows the login form when logged out and, when logged in, the email plus a logout button — the only place where a "delete my account" control belongs today.
- **Test doubles and fixture**: `InMemoryUserRepository`, `InMemorySessionRepository`, `MutableClock`, `ServiceFixture` (BCrypt strength 4) and shared Hegel generators for valid emails/passwords; API tests use `MockMvcTester` on a shared Spring context and shared in-memory H2 with unique emails per test.
- **Coverage gate**: JaCoCo demands 100% line and branch coverage of `com.antithesis.springhegel.user.*` excluding `user.web.*`; the build fails otherwise.

### New Concepts Required

- **Account self-deletion**: a new business action on `UserService` — "the user who owns the presented live session ceases to exist". It is the first destructive operation on User and the first action that removes rows across two tables. Relates to Session (the credential that authorizes it, and collateral that is destroyed) and to User (the subject).
- **Whole-user session revocation**: destroying *all* sessions belonging to a User, not just the presented one. Today only single-session deletion exists. This is both a business rule ("a deleted user is logged out everywhere") and a technical prerequisite (the foreign key forbids deleting a User that still has sessions).
- **"Not logged in" as a failure**: until now an unknown/expired token was silently the empty case. Deletion must *refuse* in that case, which introduces a new business failure kind (authentication required) with its own error code in the unified payload, distinct from wrong credentials at login.
- **Email release**: after deletion the normalized email is free again and a fresh registration yields a *new* identity (new id). Falls out of a hard delete but must be stated and tested, because it is the observable contract of acceptance step 6.
- **Full-stack property test**: a new *testing* concept for this project — a Hegel property that drives the real Spring application through HTTP (register → login → logout → login → delete → re-register), with drawn credentials and self-cleanup against the shared in-memory database. Existing Hegel tests are Spring-free; this one deliberately is not.

### Key Business Rules

- **Self only**: a user can delete exactly one user — themself — identified solely by the live session they present. No id in the request, no admin path, no deleting others. Governs Session → User.
- **Live session required**: deletion is possible only when the presented token resolves to a non-expired session. No cookie, unknown token, ended session and expired session all refuse the deletion, and nothing is changed. Governs Session, the new failure kind. (Reading of the ambiguous sentence in the requirement — see Ambiguities.)
- **Deletion destroys every session of the user**: after deletion no token that ever belonged to that user resolves to a logged-in state — the presented one and any other device's. Governs Session.
- **Deletion is not idempotent at the API level**: repeating the deletion with the same token is refused (the session no longer exists). This is deliberately different from logout. Governs Session.
- **Email becomes re-registrable**: registering the same (normalized) email after deletion succeeds and produces a different user id; the old credentials no longer log in. Governs User.
- **Deletion clears the browser's cookie**: on success the web layer clears the `SESSION` cookie so the page returns to the logged-out state. Governs the web adapter.
- **Existing invariants remain**: email uniqueness among *existing* users; session token uniqueness; no token or password ever in bodies or logs; expired sessions are still lazily removed when encountered.

## Strategic Approach

### Solution Direction

- Extend the existing vertical slice one more step: **"Delete my account" button on the logged-in view of `login.html` → a new delete endpoint for the current user → a new `UserService` action → both repositories → H2**. The controller only maps cookie ↔ token and success ↔ cleared cookie; every rule (live-session check, cascade to sessions, hard delete) lives in the service where Hegel reaches it without Spring, exactly as login/logout do.
- Resolve identity the way `currentUser` already does (token → live session → user), then, inside one transaction, remove all sessions of that user and then the user. Reuse the existing token-resolution rules so "logged in" means the same thing for deletion as for the session query.
- Signal "not logged in" as a new business exception translated by the existing `GlobalExceptionHandler` into the existing `ErrorResponse` shape with a new code; do not reuse `INVALID_CREDENTIALS` (its message "Invalid email or password" would be wrong).
- Grow the two narrow repositories by exactly what the action needs (removing all sessions of a user; removing a user), keeping them fakeable with a few lines in the in-memory doubles.
- Testing in three layers: (1) Spring-free Hegel properties on the service for the deletion rules and for an extended model-based action sequence (add "delete" to login/logout/query/advance-time); (2) the requested **full-stack Hegel property** through `MockMvcTester` for the six-step scenario with drawn credentials, finishing by deleting the re-created user; (3) example-based MockMvc checks for HTTP wiring (status codes, cookie cleared, new error code, page still served and containing the new control).

### Key Design Decisions

- **Hard delete vs soft delete / anonymization** — Trade-offs: a soft delete keeps history but forces the uniqueness rule to become "unique among active users", complicates re-registration (step 6) and adds state to every existing query; a hard delete is the smallest change and makes email release automatic. Nothing in the requirement asks for retention. → **Recommendation: hard delete** of the user row after its sessions; if retention is ever needed it is a separate feature.

- **Scope of session destruction: presented session only vs all sessions of the user** — Trade-offs: "presented only" would leave other devices logged in as a user that no longer exists and is anyway impossible because of the foreign key; "all" matches the requirement's plural "sessions will be destroyed" and gives a clean invariant for Hegel (no token of a deleted user is ever live). → **Recommendation: destroy all sessions of the user**, then the user, in one transaction.

- **Behavior without a live session: idempotent no-op (like logout) vs refusal** — Trade-offs: idempotency is simpler for the page, but the requirement explicitly says deletion "shouldn't be possible" without an active session, and silently returning success for a non-existent subject would hide misuse. → **Recommendation: refuse with an authentication-required failure (401)** carrying a new error code; the refusal changes nothing server-side. Whether the refusal also clears the (dead) cookie is a canvas detail; recommended yes, since the cookie can never become valid again.

- **How the subject is addressed: "current user" derived from the cookie vs user id in the path** — Trade-offs: an id in the path needs an extra authorization check (id must match the session's user), invites enumeration and contradicts "delete the *current* user"; a cookie-addressed "me"-style resource needs no id and no extra check. → **Recommendation: a current-user resource under the existing `/api/users` controller, identified only by the `SESSION` cookie**. Exact path and whether the registration controller is renamed to a general user controller are canvas decisions.

- **Re-authentication (password confirmation) before deletion** — Trade-offs: asking for the password again protects against a hijacked or forgotten-open session performing an irreversible action, but the requirement asks only for "logged in" and a button, and the integration scenario has no password step at deletion. → **Recommendation: no re-authentication**; keep the minimal showcase behavior, and flag the security trade-off explicitly (below) for stakeholder confirmation.

- **User-interface confirmation** — Trade-offs: a one-click irreversible button is hostile; a native browser `confirm()` is a one-line safeguard with no new dependency. → **Recommendation: confirm before calling the endpoint**; on success (or on a 401) switch to the logged-out view.

- **Shape of the "full integration property test": Hegel through HTTP with a Spring context vs a service-level property** — Trade-offs: the stakeholder says "full integration", so the property must exercise the real wiring (controllers, cookie handling, JPA, the foreign key). That means a `@SpringBootTest` + `MockMvcTester` class whose test method is a Hegel property drawing email and password; it will be slower and it shares one H2 with every other API test. A service-level property cannot catch ordering bugs against the foreign key, which is exactly the class of bug this feature can introduce. → **Recommendation: do both** — a Spring-free service property suite (fast, gives the coverage gate its branches) *and* one HTTP-level Hegel property for the six-step scenario. Mitigate cost and interference as described under Technical Risks.

- **Cleanup discipline of the integration property** — The scenario ends with a freshly re-registered user (step 6). The stakeholder wants the test to finish by deleting it, which means the cleanup itself logs in and deletes again — exercising the feature twice per draw. → **Recommendation: make the cleanup part of the property** (log in as the re-created user, delete, assert the email is free again), and draw emails from a dedicated, clearly test-owned shape so a draw can never collide with the fixed emails used by the example-based API tests.

### Alternatives Considered

- **Soft delete / "deleted" flag on User**: rejected — complicates uniqueness and re-registration, spreads state into every query, not requested.
- **Reusing `INVALID_CREDENTIALS` for "not logged in"**: rejected — wrong message, conflates login failure with missing authentication; a new code is one constant and one handler.
- **Making deletion idempotent like logout**: rejected — contradicts the requirement's explicit "shouldn't be possible" and would report success for an absent subject.
- **A separate `AccountService` / `UserDeletionService`**: rejected — the stakeholder again asks to reuse existing services; the action belongs on `UserService` next to login/logout.
- **JPA cascade (orphan removal / `cascade = REMOVE` from User to Session)**: rejected as the mechanism — User has no collection of sessions today; adding a bidirectional mapping just to cascade a delete adds entity complexity and moves a business rule into mapping metadata where Hegel cannot see it. Deleting sessions explicitly in the service keeps the rule visible and testable with the in-memory fakes.
- **Deleting by user id in the URL with an ownership check**: rejected — see the "current user" decision.
- **Running the integration property against the service only (no Spring)**: rejected as *the* integration test — it would not exercise the foreign key, the cookie round-trip or the real encoder.

## Risk & Gap Analysis

### Requirement Ambiguities

- **The "not active" sentence is self-contradictory**: "If the user is not active, meaning there's an active session for them, it shouldn't be possible to delete them." Read literally it forbids deleting users who *have* a session, which contradicts "only logged in users can delete themselves". Interpretation adopted: "not active" = **no live session** presented — deletion requires a live, non-expired session and is refused otherwise. Needs confirmation.
- **"The sessions will be destroyed"**: all sessions of the user (adopted) or only the presented one? Adopted "all", also forced by the foreign key.
- **Response when deletion is refused**: status/code text is unspecified; recommended 401 with a new error code (name to be fixed in the canvas), no user data in the body.
- **Password confirmation before deletion**: not mentioned; assumed **not required**. Security trade-off worth a yes/no.
- **UI confirmation dialog**: not mentioned; assumed a browser `confirm()` before the request.
- **"Full integration property test"**: assumed to mean Hegel-driven, through HTTP, with the real Spring context and H2, drawing the credentials while keeping the six steps in the stated order. Confirm whether normalization variants (case/whitespace on the second login) should also be drawn, and whether other action orders are wanted (recommended: keep the fixed order; random orders are already covered at service level by the model-based property).
- **"Finish with deleting the user"**: assumed to mean the *re-created* user from step 6 is deleted at the end (log in again, delete, verify), leaving the shared database as it was found.
- **Cookie on refusal**: whether a refused deletion also clears the presented (dead) cookie. Recommended yes; harmless either way.

### Edge Cases

- **Expired session presented for deletion**: must be refused, and the expired row should be lazily removed as `currentUser` does today — consistency of the "logged in" definition across actions.
- **Replay after deletion**: the same token presented again (delete, query, logout) must behave as "not logged in"; the delete is refused, logout stays a silent no-op, query says logged out.
- **Two devices**: user logged in twice; deletion from one must kill the other token as well — a good Hegel property (no token ever issued to the deleted user is live afterwards).
- **Other users untouched**: deleting user A must not affect user B's account or sessions — a natural multi-user property for the fakes.
- **Re-registration yields a new identity**: the assertion for step 6 should be "registration succeeds *and* the id differs from the deleted user's", and the old password should still work for the new user only because it was re-registered, not because anything survived.
- **Login of a deleted user with the old credentials**: must fail with the uniform invalid-credentials response (the user is unknown now), never a server error.
- **Delete while a rotated session exists**: login-while-logged-in rotated the token earlier; only the current token is live, and deletion must still remove any leftover rows for the user.
- **Concurrent delete and login for the same user**: two requests racing; the loser must end in a business response (401 on delete, 401 on login), not a 500. Full protection is out of scope for a showcase; note it as a known, unhandled race similar to the registration race that is already documented.
- **Page behavior on failure**: a refused deletion means the browser's session is dead; the page should fall back to the logged-out view rather than displaying an error the user cannot act on.
- **Blank/garbage cookie values**: as for every session endpoint — not an error path, just "not logged in".

### Technical Risks

- **Foreign key ordering**: `sessions.user_id` is a not-null FK; deleting the user before its sessions fails only against H2, not against the in-memory fakes (which enforce no FK). The HTTP-level property is the test that catches this class of bug; the service must delete sessions first, in the same transaction, so a failure leaves no half-deleted user.
- **Hegel inside a Spring test**: `@HegelTest` (Hegel's JUnit extension resolving the `TestCase` parameter) has not been combined with `@SpringBootTest` + `@Autowired MockMvcTester` in this project. Parameter resolution and test-instance lifecycle should cooperate, but this needs an early spike in the generate phase; fallback is a plain `@Test` looping over a Hegel-independent generator, which would be a visible downgrade and must be reported if taken.
- **BCrypt cost in the full-stack property**: the Spring context uses the production encoder (default strength); each draw performs several BCrypt operations (register, two logins, plus the cleanup login and re-registration), roughly half a second or more per draw, so a default-sized Hegel run can take tens of seconds. Mitigation directions: a test-profile `PasswordEncoder` bean with low strength for API tests, or bounding this property's example count. The canvas must pick one; the default must not silently make `./gradlew test` slow.
- **Shared database and Hegel replays/shrinking**: all API test classes share one context and one in-memory H2. A draw that fails midway can leave a registered user behind; Hegel then re-runs and shrinks, possibly reproducing an email that now conflicts. Mitigations: emails drawn from a test-owned shape that cannot collide with the fixed emails of `SessionApiTest`/`RegistrationApiTest`; cleanup that tolerates leftovers (or a best-effort pre-cleanup); assertions that do not assume an empty database.
- **Coverage gate**: every new domain artifact under `user.*` (exception, service branches, repository interface) must be fully covered — mostly by the Spring-free properties, since branch coverage should not depend on the slower integration property alone.
- **Repository growth and fakes**: `SessionRepository` gains a "remove all of a user" operation and `UserRepository` a "remove" operation; the in-memory fakes (keyed by token / by email) need matching implementations that mirror the database's semantics (removing the user's sessions by owner identity, not by token).
- **Security posture of a destructive cookie-authenticated endpoint**: covered against CSRF by the existing `SameSite=Strict` cookie and JSON-only bodies, but a stolen or shared session can now delete the account without a password. Document this as an explicit, accepted trade-off unless the stakeholder wants re-authentication.
- **Controller placement and naming**: the natural home is the `/api/users` controller, currently named after registration; renaming it (or adding a second controller on the same path) is a small structural change that must stay consistent with existing API tests.
- **Static page has no automated behavior test**: the new button's JavaScript (confirm, call, state switch) is verified only by "page served" plus a content check; acceptable for the showcase but should be stated.
- **Transactional visibility**: the deletion must not return the user object after removal; response is empty, and the service should not hand out detached entities.

### Acceptance Criteria Coverage

The requirement has no numbered ACs; the following are derived from its normative statements and the six-step scenario.

| AC# | Description | Addressable? | Gaps/Notes |
|-----|-------------|--------------|------------|
| 1 | A logged-in user can delete their own account through a new, dedicated API endpoint | Yes | Cookie-addressed current-user resource; identity from the live session only; hard delete |
| 2 | A new button on the page triggers the deletion | Yes | Added to the logged-in view of `login.html`, with a confirmation; only "served + present" is automatically checked |
| 3 | Existing services are reused | Yes | New action on `UserService`; token resolution reuses the `currentUser` rules; repositories grow minimally |
| 4 | A deleted user is logged out — their sessions are destroyed | Yes | All sessions of the user removed before the user, in one transaction; cookie cleared on success |
| 5 | Deletion is not possible without an active (live) session | Yes | Refused with a new authentication-required failure; nothing changes; **depends on the adopted reading of the contradictory sentence** |
| 6 | Full integration property test: register → login → logout → login → delete → re-register same email | Partial | Addressable with Hegel + Spring + MockMvc; risks: Hegel/Spring extension interplay (needs spike), BCrypt cost, shared-database interference — mitigations proposed, decisions left to the canvas |
| 7 | The test finishes by deleting the (re-created) user to avoid interference | Yes | Cleanup is part of the property (log in again, delete, verify email free); tolerate leftovers from failed draws |
| 8 | (Implicit, project rule) 100% line/branch coverage of `user.*` excluding `user.web`, Hegel as primary test style | Yes | Spring-free properties for all new branches, extended model-based sequence with a delete action; integration property is additional, not the coverage source |
