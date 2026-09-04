# Current-User Self-Deletion — delete the logged-in account via button + RESTful API, with a full-stack Hegel lifecycle property

## Requirements

Implement account self-deletion for logged-in users: the person who owns the live session presented in the `SESSION` cookie can delete their own account — and only their own — through a dedicated "Delete my account" button on the logged-in view of `login.html` and a dedicated `DELETE /api/users/me` endpoint. Deleting the account destroys **every** session of that user (the presented one and any other device's), so a deleted user is logged out everywhere, and releases the email so a new, distinct user can register with it. Without a live session (no cookie, unknown, ended or expired token) the deletion is **refused** and nothing changes. Add the behavior to the existing `UserService` behind the existing narrow repositories, keep every rule Hegel-testable without Spring, and add the requested **full integration property test**: a Hegel property that drives the real Spring application through HTTP (register → log in → log out → log in → delete self → register the same email again) and finishes by deleting the re-created user so the shared in-memory database is left as it was found. Keep the 100% line + branch coverage gate on the `user` domain package green.

Decisions adopted from the analysis (unconfirmed by the stakeholder, therefore stated explicitly and easy to change):

- **Hard delete** of the `users` row after all of its `sessions` rows; no soft-delete flag, no retention.
- "If the user is not active … it shouldn't be possible to delete them" is read as: **deletion requires a live (non-expired) session**; anything else is refused with `401 NOT_LOGGED_IN`. This refusal is deliberately **not idempotent** (unlike logout): a second delete with the same cookie is a `401`.
- The subject is addressed only by the cookie (`/api/users/me`); there is no id in the path and no admin path.
- **No password re-confirmation** before deletion — a stolen or forgotten-open session can delete the account. Accepted trade-off for a showcase; documented in the code.
- The page asks for confirmation with a native `confirm()` dialog before calling the endpoint, then returns to the logged-out view.
- A refused deletion also clears the (dead) cookie, so the browser cannot keep presenting it.
- The integration property keeps the six steps in the stated order and draws the credentials (and the case variant of the email used for the second login); random action orders stay in the Spring-free model-based property.
- API tests run with the `test` Spring profile, which lowers BCrypt strength to 4 through a new, defaulted configuration property, so the many draws of the integration property stay fast and all MockMvc test classes share one Spring context.

Boundaries: no Spring Security, no JPA cascade mapping between `User` and `Session`, no admin deletion, no deletion by id, no password confirmation, no soft delete, no changes to registration or login rules, no new Gradle dependencies.

## Entities

```mermaid
classDiagram
direction TB

class User {
    +Long id
    +String email
    +String passwordHash
    +Instant createdAt
}

class Session {
    +Long id
    +String token
    +User user
    +Instant createdAt
    +Instant expiresAt
    +isExpiredAt(Instant now) boolean
}

class RegisteredUser {
    <<record>>
    +Long id
    +String email
}

class NotLoggedInException {
    <<RuntimeException>>
    +NotLoggedInException()
}

class UserService {
    <<interface>>
    +register(String email, String password) RegisteredUser
    +login(String email, String password, String replacedToken) ActiveSession
    +logout(String token) void
    +currentUser(String token) Optional~RegisteredUser~
    +deleteCurrentUser(String token) void
}

class UserRepository {
    <<interface>>
    +save(User) User
    +existsByEmail(String) boolean
    +findByEmail(String) Optional~User~
    +delete(User) void
}

class SessionRepository {
    <<interface>>
    +save(Session) Session
    +findByToken(String) Optional~Session~
    +delete(Session) void
    +deleteAllByUser(User) void
}

class CurrentUserController {
    +deleteCurrentUser(String token) ResponseEntity~Void~
}

class ErrorResponse {
    <<record>>
    +String code
    +List~String~ messages
}

User "1" -- "0..*" Session : owns (sessions.user_id FK, not null)
Session --> User : resolves to (live session ⇒ logged-in user)
UserService ..> NotLoggedInException : throws when no live session
UserService --> SessionRepository : deleteAllByUser, then
UserService --> UserRepository : delete
CurrentUserController --> UserService : deleteCurrentUser(cookie token)
NotLoggedInException --> ErrorResponse : 401 NOT_LOGGED_IN (GlobalExceptionHandler)
User --> RegisteredUser : maps to (unchanged; not returned by deletion)
```

Conservative constraints: `User`, `Session`, `RegisteredUser`, `ActiveSession` and the web DTOs are **unchanged**. The two repositories each gain exactly one method. No new entity, no bidirectional mapping, no cascade.

## Approach

1. API design and identity:
   - The subject of the deletion is always "the user who owns the live session in the `SESSION` cookie". The endpoint is `DELETE /api/users/me` on a new, tiny `CurrentUserController` (`/api/users/me`), leaving `RegistrationController` (`POST /api/users`) and its prompt untouched. Success is `204 No Content` with the cookie cleared (`Max-Age=0`), the same header logout sends.
   - "Live session" means exactly what `currentUser` means today: token present, known, and not expired (exclusive boundary). The service centralizes that rule in one private resolver used by both `currentUser` and `deleteCurrentUser`, so the two can never disagree.
   - Refusal is a business exception, `NotLoggedInException`, translated by the existing `GlobalExceptionHandler` into the existing `ErrorResponse` shape with the new code `NOT_LOGGED_IN` and `401`. The handler also clears the cookie, since a token that fails this check can never become valid again.

2. Technical implementation:
   - Deletion order inside one `@Transactional` service method: resolve the live session → take its owner → `sessionRepository.deleteAllByUser(owner)` → `userRepository.delete(owner)`. Hibernate executes queued deletions in order, so the `sessions.user_id` foreign key is satisfied; a failure anywhere rolls back everything, never leaving a user without sessions or vice versa.
   - Repositories stay narrow: `SessionRepository` gains the derived delete `deleteAllByUser(User)`; `UserRepository` re-declares CRUD's `delete(User)`. Both are trivially implemented in the in-memory fakes.
   - The cookie-clearing header is built in one place: `SessionController` exposes a package-private `clearedSessionCookie()` used by logout, by `CurrentUserController` and by the new exception handler; the "no `Secure` on localhost" comment stays where it is.
   - `PasswordConfig` reads `app.password.bcrypt-strength` (default `10`, BCrypt's default) so a test profile can lower the cost without touching production behavior. `src/test/resources/application-test.properties` sets `4`; every `@SpringBootTest @AutoConfigureMockMvc` class activates the `test` profile so they share one cached context and one H2 instance.
   - No new dependencies. `@HegelTest` is a JUnit `@TestTemplate` driven by a `TestTemplateInvocationContextProvider`, the same mechanism as `@ParameterizedTest`, so it composes with `SpringExtension` (`@Autowired` fields are injected once per test instance; Hegel supplies the `TestCase` parameter per invocation). Operation 13 verifies this first and stops with a report if it does not hold — no silent fallback to a looped `@Test`.

3. Business logic and testing:
   - Rules: self only; live session required; all sessions destroyed; email released (a re-registration yields a new id); old credentials no longer log in; refusal changes nothing (except the lazy removal of an expired row, exactly as `currentUser` does today).
   - Spring-free Hegel properties on the service (fresh fakes, `MutableClock`, BCrypt strength 4) cover every new branch: deletion removes user + all sessions and is then refused; refusal for `null` / forged / logged-out / expired tokens; re-registration as a new identity; other users untouched; and a **model-based lifecycle property** over random sequences of register / login / logout / delete / query / time-advance checked against a three-field model (`registered`, `issued`, `live`).
   - The **full-stack lifecycle property** (`@SpringBootTest` + `MockMvcTester` + `@HegelTest`) runs the stakeholder's six steps over HTTP with drawn credentials, asserts the observable contract at each step (status codes, cookies, `401` codes, distinct ids), and finishes by logging in as the re-created user and deleting it, verifying the email is unknown afterwards. Emails are drawn into a test-owned domain and suffixed with a per-JVM sequence number so a draw can never collide with the fixed emails of the example-based API tests or with leftovers from a failed earlier draw.
   - Example-based MockMvc checks cover the wiring that has no input space: `401 NOT_LOGGED_IN` without / with a forged cookie (body and cleared cookie), and the page containing the delete control.

## Structure

### Inheritance Relationships
1. `UserService` interface (existing) gains `deleteCurrentUser(String token)`; `UserServiceImpl` (existing) implements all five methods.
2. `NotLoggedInException extends RuntimeException` (new, no attributes).
3. `UserRepository` and `SessionRepository` keep extending Spring Data's bare `Repository` marker; each gains one method; `InMemoryUserRepository` / `InMemorySessionRepository` (tests) implement the new methods.
4. `CurrentUserController` is a plain `@RestController` (new); `SessionController` and `RegistrationController` unchanged in shape.
5. Test classes: `UserServiceDeletionPropertyTest` (Spring-free), `UserLifecycleIntegrationPropertyTest` and `CurrentUserApiTest` (Spring + MockMvc) — plain classes, no base class.

### Dependencies
1. `CurrentUserController` injects `UserService` only; uses `SessionController.SESSION_COOKIE` and `SessionController.clearedSessionCookie()`.
2. `UserServiceImpl` dependencies unchanged (`UserRepository`, `SessionRepository`, `RegistrationValidator`, `PasswordEncoder`, `SessionTokenGenerator`, `Clock`); `deleteCurrentUser` uses `SessionRepository` then `UserRepository`.
3. `GlobalExceptionHandler` additionally handles `NotLoggedInException` and uses `SessionController.clearedSessionCookie()`.
4. `PasswordConfig` reads `app.password.bcrypt-strength` from the environment.
5. `login.html` additionally calls `DELETE /api/users/me`; it still never reads the cookie.
6. `UserLifecycleIntegrationPropertyTest` uses `EmailPasswordGenerators.validPasswords()` / `randomizeCase()` (made public) and `MockMvcTester`.

### Layered Architecture
1. Static page layer: `static/login.html` — adds the delete control, confirmation and a post-deletion notice; no business rules.
2. Controller layer: `user.web.CurrentUserController` — maps cookie → token → `deleteCurrentUser`; `204` + cleared cookie. `SessionController` exposes the shared cookie-clearing header.
3. Service layer: `user.UserService` / `UserServiceImpl` — live-session resolution (shared private helper), deletion order, transactional boundary.
4. Repository layer: `user.UserRepository` (+ `delete`), `user.SessionRepository` (+ `deleteAllByUser`).
5. Data access / persistence: unchanged tables `users`, `sessions`; the FK dictates deletion order.
6. Infrastructure config: `config.PasswordConfig` (configurable strength), `src/test/resources/application-test.properties`.
7. Exception handling layer: `user.web.GlobalExceptionHandler` — adds `NotLoggedInException` → `401 NOT_LOGGED_IN` + cleared cookie.

## Operations

Execute in this order; each operation lists its completion criterion.

### Operation 1 — Create Business Exception - `user.NotLoggedInException`
1. Inheritance: `extends RuntimeException`.
2. Attributes: none — carries neither the token nor why it failed to resolve.
3. Constructors: no-arg, calling `super("Not logged in")`.
4. Usage: thrown by `UserServiceImpl.deleteCurrentUser` when the presented token does not resolve to a live session (`null`, unknown, already ended, or expired). Javadoc must say that, unlike `logout`, the action is refused rather than ignored, and that the token is intentionally not included.

### Operation 2 — Update Repository - `user.UserRepository`
1. Add exactly one method: `void delete(User user);` (re-declares the CRUD method so Spring Data implements it; keep the interface extending the bare `Repository<User, Long>` marker).
2. Javadoc on the method: "Removes the user. Callers must delete the user's sessions first — `sessions.user_id` is a not-null foreign key."
3. Constraints: no other change; `save`, `existsByEmail`, `findByEmail` untouched. Done when the Spring Data proxy starts and `InMemoryUserRepository` compiles against the four-method contract.

### Operation 3 — Update Repository - `user.SessionRepository`
1. Add exactly one method: `void deleteAllByUser(User user);` (Spring Data derived delete query — `delete…By` prefix; the `All` subject word is ignored by the parser).
2. Javadoc: "Removes every session owned by `user`, whether live or expired. Used when the user is deleted."
3. Constraints: no other change; keep `save`, `findByToken`, `delete`. Done when the proxy starts (the derived query is validated at startup) and `InMemorySessionRepository` compiles.

### Operation 4 — Update Service Interface - `user.UserService`
1. Update the class Javadoc to "Business contract for registering users, managing their login sessions and deleting accounts."
2. Add:
   - `void deleteCurrentUser(String token)`
     - Javadoc: deletes the user who owns the live session identified by `token`, together with **all** of that user's sessions, in one transaction. The email becomes available for registration again. `token` may be `null`.
     - `@throws NotLoggedInException` if `token` is `null`, unknown, already ended or expired (an expired session encountered here is deleted, as in `currentUser`). Nothing else is changed in that case.
3. Constraints: no other methods; existing Javadoc unchanged; no `Session` or `User` entity in the signature.

### Operation 5 — Update Service Implementation - `user.UserServiceImpl`
1. Add a private helper `Optional<Session> liveSession(String token)`:
   - if `token == null` return `Optional.empty()`;
   - `Optional<Session> session = sessionRepository.findByToken(token)`; if empty return empty;
   - if `session.get().isExpiredAt(clock.instant())` → `sessionRepository.delete(session.get())` and return empty;
   - else return `session`.
2. Refactor `currentUser(token)` to `return liveSession(token).map(s -> new RegisteredUser(s.getUser().getId(), s.getUser().getEmail()));` — behavior-preserving (all existing login properties must still pass unchanged).
3. Add `deleteCurrentUser(token)` — `@Transactional`:
   - `Session session = liveSession(token).orElseThrow(NotLoggedInException::new);`
   - `User owner = session.getUser();`
   - `sessionRepository.deleteAllByUser(owner);` (every session of the owner, including the presented one)
   - `userRepository.delete(owner);`
   - Order comment: sessions must go first because of the FK; the transaction guarantees all-or-nothing.
4. Constraints: no logging; the token appears in no exception message; nothing is returned (no detached entity leaves the service). Every new branch (`null` token, unknown token, expired token, live token) maps to a property in Operation 12.

### Operation 6 — Update Exception Handler - `user.web.GlobalExceptionHandler`; update `ErrorResponse` Javadoc
1. Add constant `static final String NOT_LOGGED_IN = "NOT_LOGGED_IN"`.
2. Add handler `handleNotLoggedIn(NotLoggedInException ex): ResponseEntity<ErrorResponse>` → `401 UNAUTHORIZED`, header `Set-Cookie: SessionController.clearedSessionCookie()`, body `ErrorResponse(NOT_LOGGED_IN, List.of("Not logged in"))`.
3. Existing handlers unchanged; no `WWW-Authenticate` header.
4. Update `ErrorResponse`'s Javadoc so `code` is documented as one of `VALIDATION_ERROR`, `EMAIL_ALREADY_REGISTERED`, `INVALID_CREDENTIALS`, `NOT_LOGGED_IN`. No structural change.

### Operation 7 — Update Controller - `user.web.SessionController`
1. Add a package-private `static String clearedSessionCookie()` returning `sessionCookie("").maxAge(0).build().toString()` (i.e. `SESSION=; Path=/; Max-Age=0; HttpOnly; SameSite=Strict`), with Javadoc "Header value that makes the browser drop the session cookie; shared by logout, account deletion and the not-logged-in error."
2. `logout` uses `clearedSessionCookie()` instead of building the header inline. Behavior and all existing `SessionApiTest` assertions unchanged.
3. Constraints: `sessionCookie(...)` stays private; `SESSION_COOKIE` stays package-private; the `Secure` comment stays.

### Operation 8 — Create Controller - `user.web.CurrentUserController`
1. Responsibility: HTTP + cookie adapter for deleting the logged-in user; no business logic.
2. Attributes: `private final UserService userService` (constructor injection).
3. Methods (class annotated `@RestController`, `@RequestMapping("/api/users/me")`):
   - `deleteCurrentUser(@CookieValue(name = SessionController.SESSION_COOKIE, required = false) String token): ResponseEntity<Void>` — `@DeleteMapping`. Calls `userService.deleteCurrentUser(token)`. Responds `204 NO_CONTENT` with header `Set-Cookie: SessionController.clearedSessionCookie()`. Failures propagate to `GlobalExceptionHandler`.
4. Javadoc on the class: the subject is always the owner of the presented live session; there is no id in the path by design (no enumeration, no authorization check to get wrong); deletion needs no password re-confirmation — a deliberate showcase trade-off.
5. Constraints: no other mappings; `RegistrationController` untouched.

### Operation 9 — Update Static Page - `src/main/resources/static/login.html`
1. Styles: add `.danger { background: #b00020; color: #fff; border: none; margin-left: .5rem; }` and `#notice { color: #1b5e20; }`.
2. Markup:
   - Add `<p id="notice" hidden></p>` directly after `<p id="loading">`.
   - In `<section id="logged-in">`, after the logout button add `<button id="delete-button" type="button" class="danger">Delete my account</button>`.
3. Script:
   - Delete click: `if (!window.confirm('Delete your account? This cannot be undone.')) return;` then `fetch('/api/users/me', { method: 'DELETE' })` inside `try/catch`; on `response.status === 204` set `notice.textContent = 'Your account has been deleted.'` and unhide it; in every case (including `401` and network errors) call `showLoggedOut()`.
   - `showLoggedIn(email)` hides `#notice`; the login form's submit handler hides `#notice` when it starts.
4. Constraints: never touch `document.cookie`; no libraries; the confirm text and notice text are exactly as above. Done when the page is served and, against the running app, the button deletes the account and shows the logged-out form with the notice.

### Operation 10 — Update Configuration - `config.PasswordConfig`; create `src/test/resources/application-test.properties`
1. `passwordEncoder(@Value("${app.password.bcrypt-strength:10}") int strength): PasswordEncoder` returns `new BCryptPasswordEncoder(strength)`. Javadoc: "Strength defaults to BCrypt's 10; the `test` profile lowers it so property tests that drive the real application can run many iterations quickly."
2. Create `src/test/resources/application-test.properties` containing exactly `app.password.bcrypt-strength=4` (with a comment line explaining why). Do **not** create `src/test/resources/application.properties` (it would shadow the main one).
3. Constraints: production behavior unchanged (strength 10); `config` stays outside the coverage gate.

### Operation 11 — Update Test Support (in `src/test/java`)
1. `user.InMemoryUserRepository`: add `public void delete(User user)` → `byEmail.remove(user.getEmail())`.
2. `user.InMemorySessionRepository`: add `public void deleteAllByUser(User user)` → `byToken.values().removeIf(s -> s.getUser().getId().equals(user.getId()))` (owner identity by id, mirroring the FK, not object identity). Add helper `long countByUser(User user)` if needed by properties (optional; keep `size()` and `containsToken()`).
3. `user.EmailPasswordGenerators`: make the class `public final` and make `validEmails()`, `validPasswords()`, `randomizeCase(TestCase, String)` `public static`; other members unchanged.

4. `user.UserServicePropertyTest`: the anonymous racing `UserRepository` in `uniqueConstraintViolationOnSaveIsReportedAsConflict` must implement the new `delete(User)` as a no-op (it is never reached in that test). No other change to existing tests.
5. Constraints: no Spring; fakes stay free of business rules.

### Operation 12 — Create Hegel Property Tests - `user.UserServiceDeletionPropertyTest`
Uses `ServiceFixture.fresh()` inside every test body (no state shared between draws). Reuse `validEmails`, `validPasswords`, `randomizeCase`, `tokensLike` and a local `pad`/`pick` helper as in `UserServiceLoginPropertyTest`.

Properties (`@HegelTest`, `TestCase tc`):
1. `deletingTheCurrentUserRemovesTheUserAndEveryOneOfItsSessions` — register `(e, p)`; login `n` times (`n` in 1..4, `null` replacedToken) collecting tokens; `deleteCurrentUser(pick(tokens))`; assert `repository.existsByEmail(e)` is false, `repository.size() == 0`, `sessions.size() == 0`, every token's `currentUser` is empty, `login(e, p, null)` throws `InvalidCredentialsException`, and a second `deleteCurrentUser(sameToken)` throws `NotLoggedInException` (not idempotent).
2. `deletionWithoutALiveSessionIsRefusedAndChangesNothing` — register + login → live token `t`; draw a dead-token kind from `sampledFrom(NULL, FORGED, LOGGED_OUT)`: `NULL` → `null`; `FORGED` → `tokensLike()` filtered `!= t`; `LOGGED_OUT` → login again, `logout` that token, use it. `deleteCurrentUser(dead)` throws `NotLoggedInException` with message exactly `"Not logged in"`; afterwards `repository.existsByEmail(e)` is true, `currentUser(t)` is present, `sessions.size() == 1`.
3. `deletionWithAnExpiredSessionIsRefusedAndTheExpiredRowIsRemoved` — register + login → `t`; `clock.advance(SESSION_LIFETIME.plus(extra))` with `extra` drawn in `[0, 24 h]`; `deleteCurrentUser(t)` throws `NotLoggedInException`; `sessions.size() == 0` (lazy removal); user still exists; a fresh `login(e, p, null)` still succeeds.
4. `aDeletedEmailCanBeRegisteredAgainAsANewUser` — register `(e, p)` → `id1`; login → `t`; `deleteCurrentUser(t)`; register `(pad(randomizeCase(e)), p2)` with `p2` drawn from `validPasswords()` → `RegisteredUser` with `email == e` and `id != id1`; `login(e, p2, null)` succeeds; `currentUser(t)` is empty; `repository.size() == 1`.
5. `deletingOneUserLeavesOtherUsersUntouched` — draw two distinct emails `a`, `b` (filter `!a.equals(b)`) and passwords; register both; login `na`, `nb` times (each in 1..3); `deleteCurrentUser(pick(tokensA))`; assert `existsByEmail(b)` true, every `b` token present, every `a` token empty, `sessions.size() == nb`, `repository.size() == 1`.
6. `randomLifecycleSequencesAgreeWithTheModel` — draw `(e, p)` and a list of 1–20 actions from `sampledFrom(REGISTER, LOGIN, LOGOUT_LIVE, DELETE_LIVE, DELETE_STALE, QUERY, ADVANCE_PAST_EXPIRY)`; model: `boolean registered = false`, `List<String> issued`, `Set<String> live`.
   - `REGISTER`: if `!registered` → `register(e, p)` succeeds, `registered = true`; else → `assertThrows(EmailAlreadyRegisteredException)`.
   - `LOGIN`: if `registered` → token added to `issued` and `live`; else → `assertThrows(InvalidCredentialsException)`.
   - `LOGOUT_LIVE`: if `live` non-empty → `logout(pick(live))`, remove from `live`.
   - `DELETE_LIVE`: if `live` non-empty → `deleteCurrentUser(pick(live))`, then `registered = false`, `live.clear()`.
   - `DELETE_STALE`: `stale = issued − live`; token = `pick(stale)` if non-empty else `null`; `assertThrows(NotLoggedInException)`; model unchanged.
   - `QUERY`: for every issued token `currentUser(t).isPresent() == live.contains(t)`; `repository.existsByEmail(e) == registered`.
   - `ADVANCE_PAST_EXPIRY`: `clock.advance(SESSION_LIFETIME)`; `live.clear()`.
   Run the `QUERY` check once more after the sequence.

No additional example tests are needed: `null` is covered by property 2; every branch of `liveSession` and `deleteCurrentUser` is hit by properties 1–3.

### Operation 13 — Create Full-Stack Hegel Property - `user.web.UserLifecycleIntegrationPropertyTest`
1. Class: `@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test")`; `@Autowired MockMvcTester mvc`; `private static final AtomicLong SEQUENCE = new AtomicLong()`; helpers `register(email, password)`, `login(email, password)`, `deleteMe(Cookie)`, `session(Cookie)` returning `MvcTestResult`; `sessionCookie(result)` as in `SessionApiTest`; `idOf(result)` extracting `$.id` as a `long`. The request-body helper must **JSON-escape** `\` and `"` (valid passwords contain both); fixed-password tests get away with concatenation, a property does not.
2. **First action of the generate phase for this operation**: write the class with a trivial `@HegelTest` body that only draws a value and touches `mvc`, and run it. If `TestCase` is not resolved alongside `SpringExtension` (or `mvc` is `null`), stop and report the exact failure to the user — do **not** replace the Hegel property with a looped `@Test`.
3. Property `@HegelTest void aUserCanRegisterLogInLogOutLogInAgainDeleteItselfAndBeRegisteredAgain(TestCase tc)`:
   - Draws: `local` from `fromRegex("[a-z0-9]{1,12}").fullmatch(true)`; `loginLocal = randomizeCase(tc, local)` (drawn **before** the sequence number is involved: the randomizer draws one choice per character, so anything whose length varies between runs must never be fed to it — otherwise Hegel cannot replay a failure and reports the property as flaky); `email = local + "." + SEQUENCE.incrementAndGet() + "@lifecycle.test"` and `loginEmail = loginLocal + "." + <same number> + "@lifecycle.test"` (test-owned domain, never `example.com`; the sequence keeps every draw's email unique even across failed/shrunk runs); `password`, `password2` from `EmailPasswordGenerators.validPasswords()`.
   - Step 1 — register `(email, password)` → `201`, `$.email == email`, keep `id1`.
   - Step 2 — login `(email, password)` → `201`, cookie `c1` matching `[A-Za-z0-9_-]{43}`; `GET /api/session` with `c1` → `200`, `$.email == email`.
   - Step 3 — `DELETE /api/session` with `c1` → `204`; `GET /api/session` with `c1` → `204`.
   - Step 4 — login `(loginEmail, password)` with no cookie → `201`, cookie `c2` with `c2.value != c1.value`; `GET /api/session` with `c2` → `200`.
   - Step 5 — `DELETE /api/users/me` with `c2` → `204`, empty body, `Set-Cookie` starts with `SESSION=;` and contains `Max-Age=0`; then `GET /api/session` with `c2` → `204`; `DELETE /api/users/me` with `c2` again → `401`, `$.code == "NOT_LOGGED_IN"`; login `(email, password)` → `401`, `$.code == "INVALID_CREDENTIALS"`.
   - Step 6 — register `(email, password2)` → `201`, `$.email == email`, `id2 != id1`.
   - Cleanup (part of the property) — login `(email, password2)` → `201`, cookie `c3`; `DELETE /api/users/me` with `c3` → `204`; login `(email, password2)` → `401 INVALID_CREDENTIALS` (proves the database holds nothing for this email).
4. Constraints: no `testCases` override by default (strength-4 BCrypt keeps the run in seconds); if the run exceeds ~10 s locally, set `@HegelTest(testCases = 25)` and say so in the README bullet. Assertions never assume an empty database. No `@DirtiesContext`, no `@Transactional` on the test.

### Operation 14 — Create API Wiring Tests - `user.web.CurrentUserApiTest`; activate the `test` profile on existing API tests
1. `CurrentUserApiTest` — `@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test")`, `MockMvcTester mvc`:
   - `deleteWithoutACookieReturns401NotLoggedInAndClearsTheCookie` — `DELETE /api/users/me` → `401`, `$.code == "NOT_LOGGED_IN"`, `$.messages == ["Not logged in"]`, `Set-Cookie` contains `Max-Age=0` and `HttpOnly`.
   - `deleteWithAForgedCookieReturns401NotLoggedIn` — cookie `SESSION=forged` → same status/body/cookie header.
   - `loginPageOffersAccountDeletion` — `GET /login.html` → `200`, body contains `id="delete-button"` and `/api/users/me`.
2. Add `@ActiveProfiles("test")` to `RegistrationApiTest` and `SessionApiTest` (one annotation each; no other change) so all four MockMvc classes share one context. `SpringHegelExampleApplicationTests` stays profile-less (it proves the production configuration loads).
3. Constraints: fixed emails in these classes keep their `example.com` domain; the lifecycle property owns `lifecycle.test`.

### Operation 15 — Update Documentation - `README.md`
1. Under "Getting started", after the login paragraph add: "From the logged-in view you can delete your account (`DELETE /api/users/me`); that removes the user and all of its sessions, and the email can be registered again."
2. In "How the tests are shaped" add one bullet: "**One property runs the whole stack.** `UserLifecycleIntegrationPropertyTest` is a `@HegelTest` inside a `@SpringBootTest`: it drives register → login → logout → login → delete → re-register through MockMvc with drawn credentials and cleans up after itself, so it also proves the deletion order against the real foreign key. API tests run with the `test` profile, which lowers BCrypt to strength 4 via `app.password.bcrypt-strength`."
3. In the "Sessions follow the same recipe" bullet, change "three methods" to "four methods".

### Operation 16 — Verify
1. `./gradlew build` passes: compilation, all Hegel and JUnit tests, `jacocoTestCoverageVerification` at 100% line and branch for `com.antithesis.springhegel.user.*` excluding `user.web.*` (now including `NotLoggedInException`, the enlarged repositories' interfaces and `UserServiceImpl.liveSession/deleteCurrentUser`).
2. Report the wall-clock time of `UserLifecycleIntegrationPropertyTest`; apply the `testCases` bound from Operation 13.4 only if needed, and say so.
3. `./gradlew bootRun`: register, log in, click "Delete my account", confirm → the logged-out form appears with "Your account has been deleted."; reload → still logged out; registering the same email succeeds.
4. All existing tests (`RegistrationApiTest`, `SessionApiTest`, `UserServicePropertyTest`, `UserServiceLoginPropertyTest`, `RegistrationValidatorPropertyTest`) pass unchanged apart from the added `@ActiveProfiles`.

## Norms

1. Annotation Standards: `@RestController` + `@RequestMapping` on the new controller; `@Transactional` on `deleteCurrentUser`; `@Value` only on the `@Bean` method parameter in `PasswordConfig`; `@ActiveProfiles("test")` on every `@SpringBootTest @AutoConfigureMockMvc` class; `@HegelTest` + `TestCase tc` for properties, both Spring-free and full-stack.
2. Dependency Injection: constructor injection only, `final` fields, no field `@Autowired` in production code (test classes use `@Autowired MockMvcTester` as the existing API tests do). Controllers depend on `UserService`, never on `UserServiceImpl`. Time only through the injected `Clock`.
3. Exception Handling: `NotLoggedInException extends RuntimeException`, named after the business condition, carries nothing; translated exclusively by `GlobalExceptionHandler` to `ErrorResponse("NOT_LOGGED_IN", ["Not logged in"])` with `401`. Unknown/expired tokens are the empty case for `currentUser`/`logout` and an exception only for `deleteCurrentUser` — the shared `liveSession` helper is the single definition of "live".
4. Data Validation: none new — the only input is the cookie token; no normalization, no bean validation.
5. Logging: no logger introduced; if added later it must never log tokens, cookies or passwords.
6. Documentation Standards: Javadoc on `UserService.deleteCurrentUser` (contract, `null` handling, exception, deletion scope), on both new repository methods (FK ordering), on `NotLoggedInException`, on `CurrentUserController` (no id in path, no re-authentication — deliberate), on `clearedSessionCookie()`, and on the strength property in `PasswordConfig`. Comments only for constraints the code cannot express (deletion order, sequence-suffixed emails).
7. Testing Norms: Spring-free properties build a fresh `ServiceFixture` per body; the full-stack property uses one shared context and cleans up its own data; emails in the full-stack property are sequence-suffixed and live in `lifecycle.test`; example-based `@Test` only for `401` wiring and the page content check. No `@DirtiesContext`, no `@Transactional` on tests, no `@Sql` cleanup.
8. Naming: endpoint `/api/users/me`; exception `NotLoggedInException`; error code `NOT_LOGGED_IN`; message `"Not logged in"`; button id `delete-button`; notice id `notice`; property `app.password.bcrypt-strength`; profile `test`.

## Safeguards

1. Functional Constraints: the new API surface is exactly `DELETE /api/users/me` plus the new button on `login.html`. No `GET /api/users/me`, no deletion by id, no admin deletion, no soft delete, no auto-logout of other users, no changes to `POST /api/users` or `/api/session` semantics (the shared cookie-clearing helper is a refactor with identical headers).
2. Performance Constraints: with the `test` profile (BCrypt strength 4) the full `./gradlew test` must stay in the same order of magnitude as before this feature; `UserLifecycleIntegrationPropertyTest` should finish in under ~10 s on a developer machine, otherwise bound it with `@HegelTest(testCases = 25)` and document it. Production strength stays 10.
3. Security Constraints:
   - The subject of deletion is derived only from the `SESSION` cookie resolved server-side; nothing in the request body or path can name another user.
   - No password re-confirmation — documented in `CurrentUserController` as a deliberate showcase trade-off; a production deployment should add re-authentication.
   - The cookie is cleared on `204` **and** on `401 NOT_LOGGED_IN` (`SESSION=; Path=/; Max-Age=0; HttpOnly; SameSite=Strict`).
   - CSRF: protected like the other state-changing endpoints by `SameSite=Strict`; no token machinery added.
   - Tokens never appear in bodies, exception messages, logs or `toString()`; the deletion returns no body.
   - Do NOT add `spring-boot-starter-security`; do NOT read `document.cookie` in the page.
4. Integration Constraints: all `@SpringBootTest @AutoConfigureMockMvc` classes activate the `test` profile so they share one context and one H2; the lifecycle property leaves no rows for its emails behind (verified by the final `401 INVALID_CREDENTIALS`); fixed-email tests keep `example.com`, the property owns `lifecycle.test`.
5. Business Rule Constraints (exact, testable):
   - `deleteCurrentUser(token)` succeeds iff `liveSession(token)` is present, where live = non-`null`, found by token, and `!now.isBefore(expiresAt)` is false.
   - On success: all rows in `sessions` with `user_id = owner.id` are deleted, then the `users` row; afterwards `existsByEmail(owner.email)` is false and every token ever issued to the owner resolves to "not logged in".
   - On refusal: `NotLoggedInException`; the only permitted side effect is the lazy deletion of the expired row that was presented.
   - A second deletion with the same token is refused (`401`); this is intentional and differs from the idempotent logout.
   - After deletion, registering the same normalized email succeeds and yields a new id; logging in with the deleted user's credentials yields `401 INVALID_CREDENTIALS`.
6. Exception Handling Constraints: exactly one new business exception (`NotLoggedInException`), handled only by `GlobalExceptionHandler`; existing handlers, codes and messages unchanged; no `WWW-Authenticate` header.
7. Technical Constraints: no new Gradle dependencies; no JPA cascade or bidirectional `User`↔`Session` mapping; `UserRepository` and `SessionRepository` each gain exactly one method and stay on the bare `Repository` marker; `RegistrationController` unchanged; `SessionController` changes limited to the `clearedSessionCookie()` extraction; Hegel JVM flags in `build.gradle.kts` untouched; no `src/test/resources/application.properties`.
8. Data Constraints: deletion order sessions → user inside one transaction (FK `sessions.user_id` not null); no schema change; the `users.email` unique constraint keeps holding among existing rows only.
9. API Constraints:
   - `DELETE /api/users/me` with a live cookie = `204 No Content`, no body, `Set-Cookie: SESSION=; Path=/; Max-Age=0; HttpOnly; SameSite=Strict`.
   - `DELETE /api/users/me` without / with a dead cookie = `401` `{"code": "NOT_LOGGED_IN", "messages": ["Not logged in"]}` plus the same cookie-clearing header.
   - After a successful deletion: `GET /api/session` with any of the user's former cookies = `204`; `POST /api/session` with the former credentials = `401 INVALID_CREDENTIALS`; `POST /api/users` with the same email = `201` with a new `id`.
10. Exact Messages and Texts (do not modify): `"Not logged in"` (exception + response); confirm dialog `"Delete your account? This cannot be undone."`; notice `"Your account has been deleted."`; button label `"Delete my account"`.
11. Coverage Constraints: `jacocoTestCoverageVerification` keeps enforcing 100% line AND branch coverage for `com.antithesis.springhegel.user.*` excluding `user.web.*`. Branch map: `liveSession` null / unknown / expired / live → Operation 12 properties 2, 2, 3, 1; `deleteCurrentUser` refuse / succeed → properties 2–3 / 1; `NotLoggedInException` constructor → property 2. The full-stack property is additional evidence, never the sole cover for a domain branch.

## Acceptance Criteria Traceability

| AC# | Description | Covered By |
|-----|-------------|-----------|
| 1 | A logged-in user can delete their own account through a new, dedicated API endpoint | Operations 4, 5 (`deleteCurrentUser`), 8 (`DELETE /api/users/me`); verified by Operation 12 #1 and Operation 13 step 5 |
| 2 | A new button on the page triggers the deletion | Operation 9 (`#delete-button`, confirm, notice); served/content check in Operation 14 |
| 3 | Existing services are reused | Operations 4, 5 (new method on `UserService`; shared `liveSession` rule), 2, 3 (one method per existing repository) |
| 4 | A deleted user is logged out — their sessions are destroyed | Operation 5 (`deleteAllByUser` before `delete`), 8 (cookie cleared); verified by Operation 12 #1, #5, #6 and Operation 13 step 5 |
| 5 | Deletion is not possible without an active (live) session | Operations 1, 5, 6 (`NotLoggedInException` → `401 NOT_LOGGED_IN`); verified by Operation 12 #2, #3, #6 and Operation 14 |
| 6 | Full integration property test: register → login → logout → login → delete → re-register same email | Operation 13 (Hegel `@TestTemplate` inside `@SpringBootTest`, steps 1–6); Operation 10 keeps it fast; Safeguards 2, 4 |
| 7 | The test finishes by deleting the (re-created) user to avoid interference | Operation 13 cleanup step (login, delete, verify `401`); sequence-suffixed `lifecycle.test` emails |
| 8 | 100% line/branch coverage of `user.*` (excluding `user.web`), Hegel as primary style | Operations 1–5 under the gate, Operation 12 (6 properties incl. model-based lifecycle), Operation 16; Safeguards 11 |
