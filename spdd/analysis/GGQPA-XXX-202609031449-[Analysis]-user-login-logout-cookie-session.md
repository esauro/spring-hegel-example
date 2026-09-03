# SPDD Analysis: User Login & Logout (email + password, cookie-based session)

## Original Business Requirement

> Source: `requirements/user_login.md` (the command argument `features/user_login.md` does not exist; this is the only login requirement file in the repository and matches the request).

I want to make it possible for existing users to login and logout using the email and password they used before while registering. We should have a separate page for doing this.

If they are logged out they can log in

If they are logged in they can log out

We will use cookies to check if a user is logged in or logged out.

As usual I want to use PBT with hegel to test this works as intended.

Please reuse the existing UserService and add the new actions in there.

## Domain Concept Identification

### Existing Concepts (from codebase)

- **User** (`user.User`, table `users`): the registered account holder — holds the normalized (trimmed, lowercased) email, a one-way BCrypt password hash and a creation timestamp. Login authenticates *against* this concept; nothing about it needs to change.
- **UserService** (`user.UserService` / `UserServiceImpl`): the well-defined business contract of the user domain, currently exposing a single action, `register`. The stakeholder explicitly asks for the login/logout actions to be added here rather than in a new service. Its Javadoc ("Business contract for registering users") will need to broaden to "users and their sessions".
- **UserRepository** (`user.UserRepository`): deliberately narrow data-access contract (`save`, `existsByEmail`, `findByEmail`). `findByEmail` already exists and is exactly the lookup login needs — no repository change is required for authentication itself.
- **RegistrationValidator** (`user.RegistrationValidator`): pure normalization + validation rules. Its `normalizeEmail` (trim + `Locale.ROOT` lowercase) is the normalization login **must reuse** so that the email typed at login resolves to the same identity as at registration. Its password *policy* rules are registration-only and should **not** gate login.
- **Password credential (hashed) / PasswordEncoder** (`config.PasswordConfig`, BCrypt): registration only uses `encode`; login introduces the complementary `matches` check. The registration service trims the password before hashing, so login must apply the identical trim before matching or legitimately padded passwords will fail.
- **Registration page** (`static/register.html`): a static HTML page with vanilla JavaScript calling the JSON API via `fetch`, showing errors from the unified error payload and a success screen. The new login page should follow the same pattern and style.
- **Unified error contract** (`user.web.ErrorResponse`, `GlobalExceptionHandler`): business exceptions are translated centrally into `{code, messages}` with a status per failure kind (400 validation, 409 conflict). Login introduces a third failure kind that must slot into this contract.
- **Business exceptions** (`InvalidRegistrationException`, `EmailAlreadyRegisteredException`): named after the business condition, carry only what the response needs, never echo user input.
- **Test infrastructure** (`InMemoryUserRepository`, `EmailPasswordGenerators`, `UserServicePropertyTest` fixture): fresh in-memory fake + service per Hegel draw, low-strength BCrypt(4) for speed, no Spring context in domain properties. The login properties must be shaped the same way.
- **Coverage gate** (`build.gradle.kts`): 100% line + branch coverage enforced on `com.antithesis.springhegel.user.*`, excluding `user.web.*`. Any new domain class placed in the `user` package falls under this gate automatically.

### New Concepts Required

- **Login (authentication)**: the act of proving identity by presenting an email + password that match a registered User. Outcome: either a new Session is established, or a single "invalid credentials" business failure. Depends on User, RegistrationValidator (normalization), PasswordEncoder (`matches`).
- **Session (logged-in state)**: a server-side record that a specific User is currently logged in, identified by an unguessable opaque token, created by Login and destroyed by Logout. Owned by exactly one User; a User may hold zero or more Sessions. This is the *truth* about "logged in or logged out" — the cookie only carries a reference to it.
- **Session cookie**: the browser-side carrier of the session token. Presence of the cookie is *not* the same as being logged in — the server must resolve the token to a live Session. Cookie attributes (HttpOnly, SameSite, path, lifetime) are a delivery-layer concern.
- **Logout**: the act of ending the Session referenced by the presented cookie and instructing the browser to drop the cookie. After logout the same token must never authenticate again.
- **Current-session query ("who am I / am I logged in?")**: the read-only action that resolves a presented token to the logged-in User (or to "not logged in"). The requirement's "if logged out they can log in / if logged in they can log out" implies the page must be able to *ask* the server which state it is in; this action is implicit in the requirement but essential to it.
- **Login page**: a separate static HTML page that (a) asks the server for the current state, (b) shows a login form when logged out and a logout control (with the logged-in email) when logged in, and (c) transitions between the two after each action. Presentation only, no business rules.
- **Authentication failure**: a new business error kind — "the email/password pair does not identify a user" — distinct from validation errors and from conflicts. It must be deliberately uninformative (same response whether the email is unknown or the password is wrong).

### Key Business Rules

- **Credentials are the registration credentials**: login succeeds iff the normalized email identifies a registered User *and* the trimmed password matches that User's stored hash. Governs Login → User, PasswordEncoder.
- **Same normalization as registration (round-trip invariant)**: for any user registered with `(e, p)`, logging in with any case variant of `e`, with surrounding whitespace on `e` or `p`, must succeed. This is the central property the Hegel tests should express. Governs Login → RegistrationValidator.
- **Wrong credentials never log in**: for a registered user, any password other than the one registered (after trim) must fail; any unregistered email must fail. Governs Login.
- **Uniform failure**: unknown email and wrong password produce the *same* observable failure (same status, code, message) so the login endpoint cannot be used to enumerate registered emails. Implicit rule surfaced here.
- **Logged out ⇒ can log in; logged in ⇒ can log out**: the state machine has two states per browser; the page exposes exactly the action valid in the current state. Governs Session, Login page.
- **Cookie is the state signal, server holds the truth**: "logged in" means the presented cookie's token resolves to a live Session. A missing, unknown, expired or logged-out token means "logged out". Governs Session, Session cookie.
- **Logout invalidates**: after logout, a subsequent request presenting the old cookie is treated as logged out; the token cannot be replayed. Governs Session, Logout.
- **Login and logout are UserService actions**: the stakeholder mandates that the new operations extend the existing service contract rather than introduce a separate service. Governs UserService.
- **Password material never leaks**: the existing safeguard (no plaintext in logs, responses, `toString()`) extends to the login request DTO and to the session token (which must not appear in logs or response bodies beyond the cookie). Governs Login, Session.
- **Property-based tests are primary**: the domain state transitions (login, logout, query) must be expressed as Hegel properties; only HTTP/cookie wiring may be example-based. Governs the whole feature.

## Strategic Approach

### Solution Direction

- Extend the existing vertical slice with a second one for authentication: **login page → JSON endpoints (login / logout / current session) → `UserService` actions → `UserRepository` + a new session store → H2**. The web layer's only job is to translate between the cookie and the opaque session token and to map the new business exception; every rule about *who may log in* and *what "logged in" means* lives behind the service interface, where Hegel can reach it without a Spring context.
- Model the logged-in state as an explicit **server-side Session** owned by a User and referenced by an opaque, random token carried in an **HttpOnly cookie**. The service issues the token on login, resolves it on query, and deletes it on logout. Because the service owns the state transitions, the "logged out → login → logged in → logout → logged out" state machine is a plain in-memory object graph in tests, exactly like registration today.
- Reuse, don't duplicate: `RegistrationValidator.normalizeEmail` for the email, the same trim for the password, `PasswordEncoder.matches` for verification, `UserRepository.findByEmail` for the lookup, `GlobalExceptionHandler` / `ErrorResponse` for the new failure kind, and the `register.html` pattern for the new static page.
- Testing strategy: Hegel properties on the service for (1) the register→login round-trip under normalization variants, (2) rejection of wrong passwords / unknown emails with a uniform failure, (3) logout invalidating the token, (4) session token uniqueness and unguessability, and (5) **generated action sequences** (login / logout / query drawn in random order) checked against a tiny reference model of "which tokens are live" — the natural showcase for property-based testing of stateful behavior. HTTP + cookie wiring (Set-Cookie on login, cookie clearing on logout, 401 mapping, page served) stays example-based with MockMvc, following `RegistrationApiTest`.

### Key Design Decisions

- **How "logged in" is represented** — options: (a) servlet `HttpSession` and the container's `JSESSIONID` cookie; (b) a stateless signed/encrypted cookie (JWT-style); (c) an opaque random token referencing a server-side Session record; (d) full Spring Security form login.
  Trade-offs: (a) is zero-code but puts the state inside the servlet container, so `UserService` would have nothing to own and the state machine could not be property-tested without a Spring context — it contradicts "add the new actions to UserService" and the project's testing philosophy. (b) makes logout unenforceable server-side (the cookie stays valid until it expires) and adds key management; the requirement's "if logged in they can log out" reads as a real state change, not a client-side cookie deletion. (d) was already rejected for registration because the starter auto-secures every endpoint and adds machinery the showcase does not want. (c) keeps the truth on the server, makes logout a genuine invalidation, gives the service a pure, fakeable contract, and follows the existing entity/repository layering.
  → **Recommendation: (c)** — opaque token + server-side Session, issued and revoked by `UserService`, carried in an HttpOnly cookie.

- **Where the session record lives** — a new persisted entity through the same narrow-repository pattern, versus an in-memory map inside a component.
  Trade-offs: persistence is consistent with the existing "entity code / access code / service interface" separation, survives nothing extra (H2 is already in-memory, so both options lose sessions on restart), and gives tests a second 20-line in-memory fake following `InMemoryUserRepository`. An in-memory component is marginally less code but breaks the layering story and would have to be reset between tests.
  → **Recommendation: persisted Session with its own narrow repository**, kept free of H2-specific constructs like `User`.

- **Login when already logged in** — reject (conflict), ignore (keep old session), or accept and rotate (new session replaces the presented one).
  Trade-offs: rejecting forces the page to log out first and adds an error path; ignoring makes the password check pointless; rotating is the conventional defense against session fixation and keeps the API idempotent from the page's point of view.
  → **Recommendation: accept and rotate** — a successful login always yields a fresh token, and any session presented with the request is invalidated. Flagged as an ambiguity below for stakeholder confirmation.

- **Logout without a live session** — error (401) or idempotent success.
  Trade-offs: an error gives the page a state it cannot act on (it is already logged out); idempotent success (clear the cookie regardless) is simpler for the page and safe.
  → **Recommendation: idempotent success**, always clearing the cookie. Flagged below.

- **Input validation at login** — apply the registration password policy, or only require non-blank inputs.
  Trade-offs: applying the policy leaks policy details and would lock out users if the policy is later tightened; login should simply check the credential.
  → **Recommendation: normalize + require non-blank email and password (validation error), then authenticate; no policy checks.** Blank/missing input is a validation failure with the existing `VALIDATION_ERROR` code; wrong credentials are a distinct authentication failure.

- **Failure disclosure** — distinct "unknown email" vs "wrong password" responses, or one uniform response.
  → **Recommendation: uniform "invalid email or password" failure mapped to 401** through the existing exception-handler pattern. Emails must not be enumerable.

- **Session lifetime** — sessions that never expire versus a fixed lifetime with server-side expiry.
  Trade-offs: no expiry is the least code; a lifetime is a cheap, conventional safeguard and yields a useful Hegel boundary property, but requires an injectable time source (the service currently calls `Instant.now()` directly).
  → **Recommendation: a fixed lifetime with expiry enforced by the service**, with the exact duration and the time-source approach fixed in the REASONS Canvas. Flagged below.

- **How the page learns its state** — query the server for the current session, or inspect the cookie from JavaScript.
  Trade-offs: reading the cookie requires it to be non-HttpOnly (exposing the token to scripts) and confuses "cookie present" with "session live".
  → **Recommendation: a dedicated current-session read action/endpoint**; the cookie stays HttpOnly.

- **Concurrent sessions per user** — one session per user (login elsewhere logs the first out) or independent sessions per login.
  → **Recommendation: independent sessions**; logout ends only the presented session. Simplest, and nothing in the requirement asks for single-device semantics. Flagged below.

### Alternatives Considered

- **Servlet `HttpSession` / `JSESSIONID`**: rejected — state would live in the container, not in `UserService`; untestable with Hegel without a Spring context; conflicts with the stakeholder's instruction to add the actions to the service.
- **Stateless signed cookie (JWT/HMAC)**: rejected — logout would be cosmetic (token valid until expiry), key management is out of proportion for a showcase, and it removes the property-testable server-side state machine.
- **Full `spring-boot-starter-security` with form login**: rejected — already excluded in the registration feature; it auto-secures all endpoints, introduces CSRF/filter-chain configuration, and hides the domain logic the showcase wants to expose.
- **Server-rendered login page (Thymeleaf)**: rejected — the registration page established static HTML + JSON API; adding a template engine breaks the established pattern and the "minimal" convention.
- **Separate `AuthenticationService`**: rejected — the stakeholder explicitly asks to reuse `UserService`. The risk of the interface growing is noted as a technical risk below.
- **Reusing the registration password policy at login**: rejected — see the validation decision above.

## Risk & Gap Analysis

### Requirement Ambiguities

- **"Separate page"**: does the single page host both login and logout (recommended reading: one `login.html` that switches between a login form and a logout control depending on state), or are two pages wanted? The analysis assumes one page with two states.
- **Login while already logged in**: the requirement only states the two happy paths. Recommendation: accept and rotate the session. Needs confirmation.
- **Logout when not logged in**: unspecified. Recommendation: idempotent success, cookie cleared.
- **Session lifetime**: not mentioned. Recommendation: a fixed lifetime with server-side expiry; the duration is a stakeholder call (a browser-session cookie with a server-side lifetime of some hours is a sensible default).
- **Multiple simultaneous sessions**: unspecified. Recommendation: allow; logout affects only the presented session.
- **"Using cookies"**: the cookie is assumed to carry an opaque session reference (HttpOnly), not the login state itself and not user data. Needs confirmation that a server-side session is acceptable ("we will use cookies to check" is satisfied because the check is driven by the cookie).
- **Should registration auto-login?** Not requested; assumed **no** — the register page keeps its current success screen. Worth a yes/no.
- **What the logged-in page shows**: assumed to display the logged-in email (already public via the registration response) and a logout button; no other profile data.
- **Failure response for wrong credentials**: assumed uniform 401 with a single generic message; status/code text pinned in the REASONS Canvas.

### Edge Cases

- **Email variants at login**: uppercase, mixed case, surrounding whitespace — must resolve to the same User (normalization reuse). Central Hegel property.
- **Password whitespace at login**: registration strips the password before hashing, so a password registered as `"  Str0ng!x "` is stored as `"Str0ng!x"`; login must strip identically or such users can never log in. Interior spaces are significant and must be preserved.
- **Blank / null / missing fields**: must yield a validation error (400), not an authentication failure and not a server error — consistent with registration's handling of malformed bodies.
- **Unknown email vs wrong password**: identical observable outcome (status, code, message). Also consider response-time differences: a missing user skips the BCrypt comparison and returns faster, which is a timing side-channel; the mitigation direction (constant-work comparison) is a REASONS Canvas safeguard decision.
- **Replayed token after logout**: must be treated as logged out; the query endpoint must not resurrect it.
- **Garbage / forged cookie values**: unknown tokens are simply "logged out" — never an error, never a stack trace.
- **Expired session**: presented after its lifetime → logged out; the boundary (exactly at expiry) must be defined inclusive/exclusive in the canvas so Hegel can probe it.
- **Two logins for the same user**: both sessions live (or first rotated away, per decision); logout of one must not affect the other.
- **Cookie on non-HTTPS localhost**: the `Secure` attribute cannot be used for local `http://localhost:8080` runs; `HttpOnly` + `SameSite` are still applicable. The canvas must decide how to handle `Secure` in a showcase.

### Technical Risks

- **Service interface growth**: `UserService` goes from one action to four (register, login, logout, current session). Acceptable and stakeholder-mandated, but the canvas should keep the session-store dependency behind its own narrow repository so the service stays fakeable and `UserServicePropertyTest`'s fixture stays small.
- **Coverage gate applies to all new domain code**: any Session entity, session repository interface, exception, or helper placed under `user.*` (outside `user.web`) must reach 100% line and branch coverage from tests, mostly Hegel properties. Entity boilerplate (JPA no-arg constructors, `toString`) needs to be exercised as `User`'s is today.
- **Time in the domain**: expiry needs a controllable clock in tests; the service currently calls `Instant.now()` directly. Introducing an injectable time source is a small structural change to the existing service and its test fixture.
- **Token generation**: must use a cryptographically secure random source with enough entropy; property tests can assert uniqueness across draws and length/alphabet invariants, but unpredictability itself is a design safeguard, not a testable property.
- **Cookie handling in MockMvc**: `MockMvcTester` can send cookies and inspect `Set-Cookie`; the API tests share one Spring context and one H2 database across test methods, so login tests must use distinct emails (as the registration tests already do) and must not assume an empty session table.
- **Hegel and Spring lifecycle**: same lesson as registration — domain properties must build a fresh in-memory user repository *and* a fresh in-memory session repository per draw; no Spring context, no shared state.
- **CSRF exposure**: cookie-authenticated, state-changing JSON endpoints are in principle CSRF-relevant. With a `SameSite` cookie and JSON-only request bodies the exposure is minimal for a showcase, but this must be an explicit, documented safeguard rather than an accident.
- **Password/token leakage**: the login request DTO needs the same `toString` redaction as `RegistrationRequest`; the session token must never appear in JSON bodies or logs (cookie only); the Session entity's `toString` must not print the token.
- **Static page state check on load**: the page must call the current-session endpoint before rendering either form; a failed call should default to the logged-out view without exposing errors.

### Acceptance Criteria Coverage

The requirement contains no numbered ACs; the following are derived from its normative statements.

| AC# | Description | Addressable? | Gaps/Notes |
|-----|-------------|--------------|------------|
| 1 | Existing users can log in with the email and password used at registration | Yes | Requires identical normalization (email case/trim, password trim) to registration; central Hegel round-trip property |
| 2 | Logged-in users can log out | Yes | Logout must invalidate the server-side session so the token cannot be replayed; behavior when not logged in needs confirmation (recommended: idempotent) |
| 3 | A separate page for login/logout | Yes | Assumed one static page with two states following `register.html`; "separate" interpreted as separate from the registration page |
| 4 | If logged out → can log in; if logged in → can log out | Yes | Implies a current-session query the page can call on load; behavior for login-while-logged-in needs confirmation (recommended: rotate) |
| 5 | Cookies are used to check logged in / logged out | Yes | Cookie carries an opaque HttpOnly session token; the server resolves it to a live session — confirm this reading of "use cookies" |
| 6 | Property-based tests with Hegel verify the behavior | Yes | Service-level state-machine and round-trip properties; HTTP/cookie wiring example-based, as permitted by CLAUDE.md |
| 7 | Reuse the existing `UserService`; add the new actions there | Yes | Interface grows to register/login/logout/current-session; session storage behind its own narrow repository |
| 8 | (Implicit, project rule) 100% line/branch coverage of `user.*` domain code, excluding `user.web` | Yes | New domain classes fall under the existing JaCoCo gate automatically; time source must be injectable to cover expiry branches |
