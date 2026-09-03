# spring-hegel-example

Example Spring Boot application showcasing two things together:

1. **Hegel** ([hegel.dev](https://hegel.dev)) — property-based testing, used as the primary testing style.
2. **SPDD** (Structured Prompt-Driven Development) — every feature flows through the SPDD skills in `.claude/skills/`; prompts are the contract between design and code.

## Tech stack

- Java 25 (Gradle toolchain), Spring Boot 4.x, Gradle (Kotlin DSL) via the wrapper
- Package root: `com.antithesis.springhegel`
- Testing: JUnit 5 + Hegel (`dev.hegel:hegel`)

## Commands

```bash
./gradlew build          # compile + all tests
./gradlew test           # tests only
./gradlew bootRun        # run the app locally
```

Tests require `--enable-native-access=ALL-UNNAMED` (already configured in `build.gradle.kts` — don't remove it, Hegel's engine needs it).

## Development workflow: SPDD

Do NOT implement features ad hoc. Every non-trivial change goes through the SPDD pipeline:

1. `/spdd-analysis` — analyze the business requirement against the codebase → `spdd/analysis/*.md`
2. `/spdd-reasons-canvas` — turn the analysis into a REASONS Canvas structured prompt → `spdd/prompt/*.md`
3. `/spdd-generate` — generate code from the structured prompt, following its Operations sequence
4. `/spdd-sync` / `/spdd-prompt-update` — keep prompt and code in sync when either changes

Core principle: **when reality diverges, fix the prompt first — then update the code.** Commit prompt and code changes together.

- `spdd/analysis/` — enriched-context analysis documents
- `spdd/prompt/` — REASONS Canvas structured prompts (the source of truth for each feature)

## Testing conventions

- Prefer **property-based tests with Hegel** over example-based tests: annotate with `@HegelTest`, take a `TestCase tc` parameter, and draw inputs with `tc.draw(generator, "label")` (generators from `dev.hegel.Generators`).
- Express invariants/properties of the domain, not hand-picked examples. See `RegistrationValidatorPropertyTest` for the shape.
- Plain JUnit `@Test` is fine for wiring checks (e.g. context loads) and cases with no meaningful input space.

## Conventions

- Constructor injection, no field `@Autowired`
- Standard Spring layering: controller → service → repository
- Keep the code minimal — this is a showcase project; don't add dependencies or abstractions speculatively
