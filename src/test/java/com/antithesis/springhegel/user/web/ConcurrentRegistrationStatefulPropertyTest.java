package com.antithesis.springhegel.user.web;

import static com.antithesis.springhegel.user.EmailPasswordGenerators.randomizeCase;
import static com.antithesis.springhegel.user.EmailPasswordGenerators.validPasswords;
import static com.antithesis.springhegel.user.web.UserApi.idOf;
import static com.antithesis.springhegel.user.web.UserApi.sessionCookie;
import static dev.hegel.Generators.fromRegex;
import static dev.hegel.Generators.integers;
import static org.assertj.core.api.Assertions.assertThat;

import dev.hegel.ConcurrentPool;
import dev.hegel.HegelTest;
import dev.hegel.Invariant;
import dev.hegel.Rule;
import dev.hegel.Stateful;
import dev.hegel.TestCase;
import jakarta.servlet.http.Cookie;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * Registration uniqueness under contention, as a <em>concurrent</em> Hegel stateful property.
 * {@link UserLifecycleStatefulPropertyTest} drives one user through one sequence of actions; here
 * {@link Stateful#run} with {@code maxConcurrency} above 1 runs the machine's rules on two to four
 * worker threads at once, so several {@code POST /api/users} for the same email are in flight
 * together. The service's {@code existsByEmail} pre-check cannot see a registration that has not
 * committed yet, which leaves the database's unique constraint (translated from a {@code
 * DataIntegrityViolationException} into a {@code 409}) as the real guarantee. Until now only a
 * stubbed repository exercised that path; this property exercises it against H2.
 *
 * <p>The engine chooses, round by round, which {@link Rule#group() group} of rules runs: while
 * the {@code register} group runs, every worker registers; while the {@code reset} group runs,
 * workers delete previous winners so the same emails are contended again. Rules of different
 * groups never overlap in time, and {@link Invariant invariants} run between rounds, so the only
 * race in play is registration against registration. All model state is thread-safe because rules
 * mutate it concurrently; a {@link ConcurrentPool} (not a {@code Pool}) carries winners from the
 * register rule to the delete rule so the engine chooses, and shrinks over, which one dies.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ConcurrentRegistrationStatefulPropertyTest {

    /** Keeps every test case's emails unique, even across failed or shrunk runs that left rows behind. */
    private static final AtomicLong SEQUENCE = new AtomicLong();

    @Autowired
    private MockMvcTester mvc;

    @HegelTest
    void parallelRegistrationsOfTheSameEmailHaveExactlyOneWinner(TestCase tc) {
        String local = tc.draw(fromRegex("[a-z0-9]{1,12}").fullmatch(true), "local");
        int candidates = tc.draw(integers().min(1).max(3), "candidates");
        // Test-owned domain plus a per-JVM sequence: never collides with other test classes nor
        // with leftovers of an earlier failed draw.
        String suffix = "." + SEQUENCE.incrementAndGet() + "@race.test";
        List<String> tails = new ArrayList<>();
        for (int i = 0; i < candidates; i++) {
            tails.add("-" + i + suffix);
        }

        ConcurrentRegistrationMachine machine = new ConcurrentRegistrationMachine(new UserApi(mvc), tc, local, tails);
        // Few emails, several workers: collisions are the point. Never sequential (min 2); the
        // engine draws the level up to 4 per test case.
        Stateful.run(machine, tc, Stateful.options().minConcurrency(2).maxConcurrency(4));
        machine.cleanUp();
    }

    /** A registration the model saw succeed: the credentials that must log in and the id they must yield. */
    record Account(String email, String password, long id) {
    }

    /**
     * The state machine. Rules run concurrently on this one object, each with its own worker's
     * {@link TestCase}; invariants run alone on the driving thread with the driving test case.
     */
    static final class ConcurrentRegistrationMachine {

        private final UserApi api;
        private final String local;
        private final List<String> tails;
        private final List<String> emails;

        // --- the model (shared between workers, so every field is thread-safe) ---
        /** Email → the one registration the model considers current. */
        private final Map<String, Account> registered = new ConcurrentHashMap<>();
        /** Every id the application ever handed out in this test case. */
        private final Set<Long> ids = ConcurrentHashMap.newKeySet();
        /** Emails that answered 409 since they were last deleted; each needs a winner to explain it. */
        private final Set<String> conflicts = ConcurrentHashMap.newKeySet();
        /** The winners as the engine sees them: which one a reset deletes is a drawn, shrinkable choice. */
        private final ConcurrentPool<Account> accounts;

        ConcurrentRegistrationMachine(UserApi api, TestCase tc, String local, List<String> tails) {
            this.api = api;
            this.local = local;
            this.tails = List.copyOf(tails);
            this.emails = tails.stream().map(tail -> local + tail).toList();
            this.accounts = new ConcurrentPool<>(tc);
        }

        // --- rules ---

        /** Runs on every worker at once: the same few emails, in random letter case, registered in parallel. */
        @Rule(group = "register", weight = 4)
        void register(TestCase tc) {
            int slot = tc.draw(integers().min(0).max(tails.size() - 1), "slot");
            String password = tc.draw(validPasswords(), "password");
            // Only the fixed-length local part is case-randomized (one draw per character).
            String attempted = randomizeCase(tc, local) + tails.get(slot);
            String email = emails.get(slot);

            MvcTestResult result = api.register(attempted, password);
            int status = result.getResponse().getStatus();
            assertThat(status).as("register %s", attempted).isIn(HttpStatus.CREATED.value(), HttpStatus.CONFLICT.value());
            if (status == HttpStatus.CONFLICT.value()) {
                assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("EMAIL_ALREADY_REGISTERED");
                conflicts.add(email);
                return;
            }
            assertThat(result).bodyJson().extractingPath("$.email").isEqualTo(email);
            long id = idOf(result);
            assertThat(ids.add(id)).as("id %s was handed out before", id).isTrue();
            Account account = new Account(email, password, id);
            // Atomic per email: if two workers both got a 201 for it, the second compute sees the
            // first one's account — the lost-uniqueness bug this property hunts.
            registered.compute(email, (key, previous) -> {
                assertThat(previous).as("two registrations of %s both succeeded: %s and %s", key, previous, account).isNull();
                return account;
            });
            accounts.add(tc, account);
        }

        /** Never overlaps a registration (different group): re-arms the race for that email. */
        @Rule(group = "reset")
        void deleteAccount(TestCase tc) {
            Account account = tc.draw(accounts.consuming(), "account");
            MvcTestResult login = api.login(account.email(), account.password());
            assertThat(login).hasStatus(HttpStatus.CREATED);
            assertThat(idOf(login)).isEqualTo(account.id());
            assertThat(api.deleteMe(sessionCookie(login))).hasStatus(HttpStatus.NO_CONTENT);
            assertThat(registered.remove(account.email())).isEqualTo(account);
            conflicts.remove(account.email());
        }

        // --- invariants ---

        @Invariant
        void winnersAgreeWithTheDatabase(TestCase tc) {
            assertThat(accounts.size()).isEqualTo(registered.size());
            for (String email : emails) {
                Account winner = registered.get(email);
                if (winner == null) {
                    assertInvalidCredentials(api.login(email, tc.draw(validPasswords(), "password")));
                    continue;
                }
                MvcTestResult login = api.login(email, winner.password());
                assertThat(login).hasStatus(HttpStatus.CREATED);
                assertThat(idOf(login)).isEqualTo(winner.id());
                // Leave no session behind: the check must not change what later checks see.
                Cookie cookie = sessionCookie(login);
                assertThat(api.logout(cookie)).hasStatus(HttpStatus.NO_CONTENT);
                MvcTestResult again = api.register(email, winner.password());
                assertThat(again).hasStatus(HttpStatus.CONFLICT);
                assertThat(again).bodyJson().extractingPath("$.code").isEqualTo("EMAIL_ALREADY_REGISTERED");
            }
        }

        /** A 409 is explained only by a 201 the model knows about. */
        @Invariant
        void everyConflictHasAWinner(TestCase tc) {
            assertThat(registered.keySet()).containsAll(conflicts);
        }

        // --- cleanup ---

        /** Leaves the shared database as it was found and proves it: nothing is left for these emails. */
        void cleanUp() {
            for (Account account : registered.values()) {
                MvcTestResult login = api.login(account.email(), account.password());
                assertThat(login).hasStatus(HttpStatus.CREATED);
                assertThat(api.deleteMe(sessionCookie(login))).hasStatus(HttpStatus.NO_CONTENT);
            }
            for (String email : emails) {
                assertInvalidCredentials(api.login(email, "Wrong-password-1"));
            }
        }

        private static void assertInvalidCredentials(MvcTestResult result) {
            assertThat(result).hasStatus(HttpStatus.UNAUTHORIZED);
            assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("INVALID_CREDENTIALS");
        }
    }
}
