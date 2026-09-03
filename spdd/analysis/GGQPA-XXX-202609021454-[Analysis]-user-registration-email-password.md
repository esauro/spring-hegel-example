# SPDD Analysis: User Registration (email + password)

## Original Business Requirement

> Source: `requirements/user_registration.md`

The first feature we are going to work on is the user registration.

We are going to create a very simple registration form that would enable users to register passing email and
password. We won't do any verification of the reachability of the email beyond the fact that the provided emails
MUST be valid emails. The passwords will be at least 8 characters long with uppercase, lowercase, numbers and
special characters.

We will use a DB, and for the time being we will use H2. Passwords will be stored encrypted.

It's really important to create proper structured code separating entity code, access code, and creating a well define service interface.

We want to have 100% code coverate using hegel-java.

### Stakeholder clarifications (2026-09-02)

The following decisions were provided by the stakeholder after the initial analysis and are binding:

- "Special characters" means ASCII special characters (e.g. `$`, `*`, `(`).
- "Encrypted" means **hashed** (one-way), confirming the analysis recommendation.
- Registering an already-registered email leads to a **conflict** error.
- The registration form is an **HTML webpage calling a RESTful API**.
- On successful registration the page shows a **success screen**; the API returns a **2xx** status code.
- "100% code coverage" applies to the **feature's domain logic**.
- Emails are **not case-sensitive**.
- **Leading and trailing spaces are trimmed** from inputs.
- Passwords have an **upper length limit of 32 characters**.
- All remaining ambiguities are delegated to the implementation team's judgment.

## Domain Concept Identification

### Existing Concepts (from codebase)

None. The codebase is a fresh Spring Boot 4.1.1 / Java 25 skeleton (`com.antithesis.springhegel`) containing only the application bootstrap class and two wiring tests. There is no domain code, no persistence layer, no validation, and no security dependency yet. This feature is fully greenfield and will **establish** the project's layering conventions (entity / repository / service interface / controller) rather than follow existing ones.

Relevant infrastructure already in place:

- **Web layer**: `spring-boot-starter-webmvc` is the only runtime dependency — HTTP is the delivery mechanism available, and it can also serve the static registration page.
- **Testing**: JUnit 5 + Hegel (`dev.hegel:hegel` 0.5.1) is wired and verified (`HegelSmokeTest`); property-based testing is the mandated primary style (CLAUDE.md).

### New Concepts Required

- **User**: the registered account holder, identified by email (case-insensitive), holding a stored password credential — the first persistent entity of the system.
- **Registration**: the act of creating a User from a submitted email + password pair, subject to validation rules — the single business operation of this feature, exposed through a well-defined service interface and a RESTful API.
- **Registration page**: a simple HTML webpage (form for email + password) that calls the RESTful API and shows a success screen on 2xx responses and errors otherwise. Presentation only — it contains no business rules.
- **Email validity**: a syntactic constraint on the email (format validity only; explicitly *no* reachability/deliverability verification). Emails are compared case-insensitively and trimmed of leading/trailing spaces before validation.
- **Password policy**: a composite constraint — 8 to 32 characters, and must contain uppercase, lowercase, numeric, and ASCII special characters (e.g. `$`, `*`, `(`).
- **Password credential (hashed)**: the stored form of the password — a one-way adaptive hash (BCrypt-family). Plaintext must never be persisted.
- **User store (H2)**: relational persistence for Users, using H2 "for the time being" — the persistence choice is explicitly provisional, so the design should not couple business logic to H2 specifics.

### Key Business Rules

- **Email must be syntactically valid**: governs Registration → rejects malformed input before any User is created.
- **No email reachability verification**: explicitly out of scope — no confirmation emails, no MX checks, no activation flow.
- **Email is case-insensitive**: governs Email validity and uniqueness — `Foo@Bar.com` and `foo@bar.com` are the same identity (normalize for storage/comparison).
- **Inputs are trimmed**: leading and trailing spaces are removed from submitted values before validation and storage.
- **Password policy (8–32 chars, upper, lower, digit, ASCII special)**: governs Registration → all four character classes are mandatory, not "3 of 4"; length is bounded at both ends.
- **Passwords stored hashed**: governs Password credential — the plaintext password must never be persisted or logged; only the one-way hashed form is stored.
- **Email uniqueness → conflict**: registering an email that is already registered (case-insensitively) is rejected with a conflict-style business error.
- **Successful registration returns 2xx**: the API signals success with a 2xx status; the page then shows a success screen.
- **Layer separation (structural rule)**: entity code, data-access code, and a well-defined service interface must be distinct — the service contract is the boundary the controller depends on.

## Strategic Approach

### Solution Direction

- Implement registration as a thin vertical slice through the standard Spring layering already mandated by CLAUDE.md: **HTML page → RESTful endpoint → service interface (+ implementation) → repository → H2**. The controller stays trivial; all business rules (trimming/normalization outcomes, validation, uniqueness conflict, hashing) live behind the service interface, which is the "well defined service interface" the requirement demands.
- The **registration form is a static HTML page** (form + minimal JavaScript) served by the same Spring Boot app, submitting to the RESTful registration endpoint and rendering a success screen on 2xx or the error otherwise. No template engine is introduced; the page carries zero business logic, so all rules remain in the property-testable service layer.
- Persistence via **Spring Data JPA with H2**, keeping the entity and repository free of H2-specific constructs so the "for the time being" database can be swapped later.
- Password handling via **spring-security-crypto only** (a `PasswordEncoder` such as BCrypt) — bringing in hashing without pulling the full Spring Security auto-configured filter chain, which would add authentication behavior nobody asked for.
- Testing strategy: Hegel property-based tests as the primary style — generators produce valid/invalid emails and policy-satisfying/violating passwords, and properties assert the business rules as invariants (e.g. "every policy-violating password is rejected", "no stored credential ever equals the submitted plaintext", "registering then re-registering any case variant of the same email conflicts", "registering then looking up round-trips"). Coverage measured with JaCoCo, targeting **100% of the feature's domain logic** (service + validation + entity behavior); bootstrap/config classes and the static page are outside the coverage target.

### Key Design Decisions

Decisions confirmed by the stakeholder (2026-09-02):

- **Meaning of "encrypted"** → **one-way adaptive hashing (BCrypt-family)**. Reversible encryption of passwords is an anti-pattern; recorded as a safeguard for the prompt.
- **Duplicate email behavior** → **reject with a conflict business error**, backed by a uniqueness guarantee in the store (not just a pre-check, to stay correct under concurrent registrations) and applied to the case-normalized email.
- **Delivery** → **HTML webpage + RESTful API**: the page is presentation-only; success = page shows a success screen, API returns 2xx.
- **Coverage scope** → **100% of the feature's domain logic**, measured with JaCoCo.
- **Email case handling** → case-insensitive: normalize (lowercase) for uniqueness and storage.
- **Input trimming** → leading/trailing spaces trimmed from inputs before validation.
- **Password bounds** → minimum 8, maximum 32 characters; "special characters" = ASCII special characters.

Decisions delegated to the implementation team (rationale recorded here, details pinned in the REASONS Canvas):

- **Scope of the security dependency**: full `spring-boot-starter-security` vs `spring-security-crypto` alone → **crypto module only**. The full starter auto-secures every endpoint and adds login machinery, which is out of scope and would break the showcase's minimalism; the crypto module gives exactly the `PasswordEncoder` needed.
- **Where validation lives**: web-layer bean validation vs domain/service-level enforcement → **service-level enforcement as the source of truth** (optionally mirrored at the web boundary). The service interface is the contract, and Hegel properties must exercise the rules without going through HTTP.
- **Email validity definition**: full RFC 5321/5322 compliance vs pragmatic format check → **pragmatic, explicitly documented format rule** (exact grammar fixed in the REASONS Canvas). Full RFC compliance is notoriously complex and contradicts "very simple"; the chosen rule must be precise because Hegel generators will probe its boundaries.
- **H2 runtime mode**: in-memory vs file-backed → **in-memory**. It's a showcase; no durability requirement exists, tests stay hermetic, and the swap-later intent is explicit.
- **API error contract**: exact response shape and status codes per failure kind (validation vs conflict) → defined in the REASONS Canvas; conflict is distinguishable from validation failure, success is 2xx per stakeholder decision.

### Alternatives Considered

- **Server-rendered registration form (Thymeleaf/MVC views)**: rejected — the stakeholder specified an HTML page calling a RESTful API; a static page + JSON endpoint achieves this without adding a template engine, and keeps all business rules in the property-testable service layer.
- **Full Spring Security integration (login, sessions, filter chain)**: rejected — the requirement is registration only; authentication is a separate future feature.
- **Plain JDBC/JdbcTemplate instead of Spring Data JPA**: rejected — JPA repositories are the conventional Spring layering this project wants to showcase ("entity code, access code" separation maps directly to entity + repository), with less boilerplate.
- **Example-based tests for the validation matrix**: rejected as primary style — CLAUDE.md mandates Hegel property-based tests; hand-picked examples would undercut the whole point of the showcase.

## Risk & Gap Analysis

### Requirement Ambiguities

All ambiguities from the initial analysis were resolved by stakeholder clarification (2026-09-02) or explicitly delegated:

- ~~"Valid email" undefined~~ → delegated: pragmatic format rule, exact grammar to be pinned in the REASONS Canvas (case-insensitive, trimmed).
- ~~"Special characters" undefined~~ → resolved: ASCII special characters; the precise character set enumeration is fixed in the REASONS Canvas.
- ~~"Encrypted" vs hashed~~ → resolved: hashed.
- ~~Duplicate registration behavior~~ → resolved: conflict error.
- ~~"Registration form" delivery~~ → resolved: HTML page calling a RESTful API; success screen on 2xx.
- ~~Response contract~~ → success is 2xx (resolved); detailed error body/status mapping delegated to the REASONS Canvas.
- ~~"100% code coverage" scope~~ → resolved: 100% of the feature's domain logic.
- ~~Email case sensitivity~~ → resolved: case-insensitive.
- ~~Whitespace handling~~ → resolved: leading/trailing spaces trimmed.
- ~~Password upper bound~~ → resolved: 32 characters.

No open ambiguities remain that block REASONS Canvas generation.

### Edge Cases

- **Trimming vs password content**: spaces are trimmed only at the ends of inputs; interior spaces in passwords remain legal characters. A password that becomes shorter than 8 characters after trimming is invalid. (Trimming passwords is unusual but is an explicit stakeholder decision.)
- **Case-normalization boundaries**: uniqueness and lookup use the normalized (lowercased) email; the exact normalization (ASCII vs locale-aware lowercase) must be fixed in the canvas — Hegel will probe non-ASCII letters.
- **Exactly-8 and exactly-32 character passwords** satisfying all four classes — both bounds are inclusive and must be exercised at the boundary.
- **Non-ASCII characters in passwords**: the policy counts ASCII special characters; whether non-ASCII characters are allowed as "filler" characters (while never satisfying the special-character class) must be pinned in the canvas.
- **Concurrent duplicate registrations**: two simultaneous requests with the same (normalized) email — uniqueness must hold at the storage level, not only via check-then-insert; both must not succeed, the loser gets the conflict error.
- **Null/absent fields**: missing email or password in the request must produce a validation error, not a server error.

### Technical Risks

- **Hegel + Spring lifecycle interplay**: `@HegelTest` runs a test body many times per execution; combining it with Spring-managed state (a shared H2 instance) risks cross-run contamination (e.g. leftover users colliding with the uniqueness conflict rule). Mitigation direction: test domain rules on the service with isolated state per draw, and keep stateful DB properties carefully reset — this is also the showcase's most instructive lesson.
- **Coverage tooling**: JaCoCo must be added and scoped to the feature's domain logic; a few plain JUnit wiring tests alongside Hegel properties are acceptable (allowed by CLAUDE.md conventions).
- **New dependencies required**: data-jpa, H2 driver, validation, and security-crypto are all absent today; each addition must stay within the "keep the code minimal" convention.
- **Password material leakage**: plaintext must not appear in logs, error messages, or `toString()` output of request/entity objects — needs an explicit safeguard in the prompt.
- **BCrypt input limits**: BCrypt truncates around 72 bytes; the 32-character password cap keeps all inputs safely below this, but the cap must be enforced *before* hashing so the guarantee holds.

### Acceptance Criteria Coverage

The requirement contains no numbered ACs; the following are derived from its normative statements and the stakeholder clarifications.

| AC# | Description | Addressable? | Gaps/Notes |
|-----|-------------|--------------|------------|
| 1 | Users can register by providing email and password | Yes | HTML page calling a RESTful API |
| 2 | Provided emails MUST be valid emails (format only, no reachability check) | Yes | Pragmatic rule; case-insensitive, trimmed; exact grammar pinned in REASONS Canvas |
| 3 | Passwords: 8–32 chars with uppercase, lowercase, numbers and ASCII special characters | Yes | Special-character set enumerated in REASONS Canvas |
| 4 | Users persisted in a DB, H2 for the time being | Yes | In-memory H2; entity/repository kept portable |
| 5 | Passwords stored hashed | Yes | One-way adaptive hashing (BCrypt-family); never plaintext |
| 6 | Duplicate email registration returns a conflict error | Yes | Enforced at storage level on normalized email |
| 7 | Successful registration: API returns 2xx, page shows success screen | Yes | Error body/status detail defined in REASONS Canvas |
| 8 | Structured code: entity code / access code / well-defined service interface separated | Yes | Establishes project layering conventions |
| 9 | 100% coverage of the feature's domain logic using hegel-java | Yes | JaCoCo added and scoped; wiring tests may be plain JUnit |
