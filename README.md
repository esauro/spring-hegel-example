# spring-hegel-example

An example Spring Boot application demonstrating a way of working that combines:

- **[Hegel](https://hegel.dev)** — property-based testing for Java (built by the Hypothesis folks at [Antithesis](https://antithesis.com)) as the primary testing style.
- **SPDD** (Structured Prompt-Driven Development) — features are designed as structured prompts (REASONS Canvas) that stay in sync with the code, driven by the Claude Code skills in [.claude/commands](.claude/commands).

## Requirements

- Java 22+ (the project toolchain targets Java 25). On Java 17–21, swap `dev.hegel:hegel` for `dev.hegel:hegel-jna` in `build.gradle.kts`.

## Getting started

```bash
./gradlew build     # compile and run all tests
./gradlew bootRun   # run the app
```

Once the app is running, open the registration page at:

**http://localhost:8080/register.html**

The page calls the REST API at `POST /api/users`. Once registered, log in at:

**http://localhost:8080/login.html**

The login page calls `POST`/`GET`/`DELETE /api/session`; the session travels in an HttpOnly `SESSION` cookie that references a server-side session record. The database is in-memory H2, so registered users and sessions are lost when the app restarts.

## Project layout

| Path | Purpose |
|------|---------|
| `src/main/java` | Application code (`com.antithesis.springhegel`) |
| `src/test/java` | JUnit 5 + Hegel property-based tests |
| `spdd/analysis` | SPDD analysis documents (`/spdd-analysis` output) |
| `spdd/prompt` | REASONS Canvas structured prompts — the design contract for each feature |
| `.claude/commands` | SPDD workflow skills for Claude Code |
| `CLAUDE.md` | Instructions for Claude Code sessions in this repo |

## How the tests are shaped

The domain package (`com.antithesis.springhegel.user`) is held to **100% line and branch coverage** by a JaCoCo rule that fails the build. That target is only realistic because the code is shaped for property-based testing:

- **Rules live in a pure component.** `RegistrationValidator` has no dependencies and no side effects, so its Hegel properties are plain method calls: generate an email/password, call `validate`, assert on the returned messages. Every rule (blank, length, format, character classes, printable ASCII) has its own property so each branch is exercised on every run, whatever Hegel happens to draw.
- **The repository contract is deliberately narrow.** `UserRepository` extends Spring Data's bare `Repository` marker and declares only `save`, `existsByEmail` and `findByEmail`. Spring Data implements it at runtime; in tests a 30-line `InMemoryUserRepository` implements it by hand.
- **A fresh fake per draw.** `@HegelTest` re-runs a test body many times. Sharing a Spring context and an H2 database across those runs would let one draw's user collide with the next (the uniqueness rule makes this especially likely). The service properties therefore build a new fake repository and a new service inside each test body, with no Spring context at all, and use a low-strength `BCryptPasswordEncoder(4)` to keep the many draws fast.
- **Sessions follow the same recipe.** Login sessions live behind a second narrow repository (`SessionRepository`, three methods) with its own hand-written in-memory fake. Time comes from an injected `java.time.Clock`, so session expiry is a deterministic property rather than a flaky sleep, and the login/logout/query state machine is checked with a model-based Hegel property that replays random action sequences against a tiny set of "live tokens".
- **Example tests only where there is no input space.** The concurrent-registration race (`save` throwing a constraint violation), null inputs, and the HTTP wiring (`RegistrationApiTest`, using MockMvc against the real H2) are plain JUnit tests, as allowed by the project conventions.

The recipe transfers to any Spring service: keep business rules in pure classes, keep data-access interfaces small enough to fake, and never let a property test depend on state that survives between draws.

## The SPDD workflow

```
requirement → /spdd-analysis → /spdd-reasons-canvas → /spdd-generate → /spdd-sync
                 (strategy)        (structured prompt)     (code)        (keep in sync)
```

The structured prompt is the contract: when code and prompt diverge, fix the prompt first, then regenerate the code, and commit both together.
