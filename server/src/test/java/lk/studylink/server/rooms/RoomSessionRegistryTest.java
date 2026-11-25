package lk.studylink.server.rooms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import lk.studylink.shared.config.StudyLinkConfig;
import lk.studylink.shared.model.BindingInvalidationListener;
import lk.studylink.shared.model.RoomBinding;
import lk.studylink.shared.model.RoomDefinition;
import lk.studylink.shared.model.SessionInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Deterministic unit and concurrency tests for {@link RoomSessionRegistry}, {@link DisplayNamePolicy},
 * {@link RoomBinding}, {@link SessionInfo}, and {@link lk.studylink.shared.model.MembershipView}
 * (Issue M1-02).
 */
class RoomSessionRegistryTest {

    @Test
    void acceptsValidDisplayNamesTrimsAndPreservesDisplaySpelling() throws RoomRegistryException {
        RoomSessionRegistry registry = new RoomSessionRegistry(StudyLinkConfig.srsDefaults());

        SessionInfo trimmedSession = registry.registerSession("  Amal Viduranga 01  ");
        assertNotNull(trimmedSession.sessionId());
        assertTrue(trimmedSession.sessionId().startsWith("s-"));
        assertEquals("Amal Viduranga 01", trimmedSession.displayName());
        assertEquals(Optional.empty(), registry.currentBinding(trimmedSession.sessionId()),
                "A newly registered session must start outside all rooms");

        // Exact lower limit: 2 code points
        SessionInfo minLen = registry.registerSession("Ab");
        assertEquals("Ab", minLen.displayName());

        // Exact upper code-point limit: 24 ASCII letters (24 code points, 24 UTF-8 bytes)
        String twentyFourChars = "A".repeat(24);
        SessionInfo maxChars = registry.registerSession(twentyFourChars);
        assertEquals(twentyFourChars, maxChars.displayName());

        // Exact upper UTF-8 byte limit: 16 Sinhala letters (16 code points <= 24, 48 UTF-8 bytes == 48)
        String sixteenSinhalaLetters = "අ".repeat(16);
        assertEquals(16, sixteenSinhalaLetters.codePointCount(0, sixteenSinhalaLetters.length()));
        assertEquals(48, sixteenSinhalaLetters.getBytes(StandardCharsets.UTF_8).length);
        SessionInfo maxUtf8 = registry.registerSession(sixteenSinhalaLetters);
        assertEquals(sixteenSinhalaLetters, maxUtf8.displayName());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "   ",
            "A",
            "   Z   ",
            "ABCDEFGHIJKLMNOPQRSTUVWXYZ",
            "අඅඅඅඅඅඅඅඅඅඅඅඅඅඅඅඅ",
            "Amal_01",
            "Amal-01",
            "Amal@uom",
            "Amal.Perera",
            "Amal\tPerera",
            "Amal\nPerera"
    })
    void rejectsInvalidDisplayNamesAndBothLengthLimits(String invalidName) {
        RoomSessionRegistry registry = new RoomSessionRegistry(StudyLinkConfig.srsDefaults());

        RoomRegistryException ex = assertThrows(
                RoomRegistryException.class,
                () -> registry.registerSession(invalidName)
        );
        assertEquals(RoomRegistryErrorCode.INVALID_NAME, ex.errorCode());

        RoomRegistryException nullEx = assertThrows(
                RoomRegistryException.class,
                () -> registry.registerSession(null)
        );
        assertEquals(RoomRegistryErrorCode.INVALID_NAME, nullEx.errorCode());
    }

    @Test
    void rejectsMixedCaseDuplicateLiveNamesLocaleIndependently() throws RoomRegistryException {
        RoomSessionRegistry registry = new RoomSessionRegistry(StudyLinkConfig.srsDefaults());

        SessionInfo original = registry.registerSession("Ishan Perera");
        assertEquals("Ishan Perera", original.displayName());

        Locale previousLocale = Locale.getDefault();
        try {
            // Verify Locale.ROOT folding even when default locale is Turkish (dotted/dotless I)
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));

            for (String duplicate : List.of("ishan perera", "ISHAN PERERA", "  iShAn PeReRa  ")) {
                RoomRegistryException ex = assertThrows(
                        RoomRegistryException.class,
                        () -> registry.registerSession(duplicate)
                );
                assertEquals(RoomRegistryErrorCode.NAME_IN_USE, ex.errorCode());
            }
        } finally {
            Locale.setDefault(previousLocale);
        }
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void simultaneousNameReservationAllowsExactlyOneWinner() throws Exception {
        RoomSessionRegistry registry = new RoomSessionRegistry(StudyLinkConfig.srsDefaults());
        int contenders = 16;
        CyclicBarrier startBarrier = new CyclicBarrier(contenders);
        String[] candidateSpellings = {"Amal", "amal", "AMAL", "  Amal  "};

        ExecutorService pool = Executors.newFixedThreadPool(contenders);
        try {
            List<Callable<Optional<SessionInfo>>> tasks = new ArrayList<>();
            for (int i = 0; i < contenders; i++) {
                String candidate = candidateSpellings[i % candidateSpellings.length];
                tasks.add(() -> {
                    startBarrier.await(5, TimeUnit.SECONDS);
                    try {
                        return Optional.of(registry.registerSession(candidate));
                    } catch (RoomRegistryException e) {
                        assertEquals(RoomRegistryErrorCode.NAME_IN_USE, e.errorCode());
                        return Optional.empty();
                    }
                });
            }

            List<Future<Optional<SessionInfo>>> futures = new ArrayList<>();
            for (Callable<Optional<SessionInfo>> task : tasks) {
                futures.add(pool.submit(task));
            }

            List<SessionInfo> winners = new ArrayList<>();
            int duplicateRejections = 0;
            for (Future<Optional<SessionInfo>> future : futures) {
                Optional<SessionInfo> outcome = future.get(5, TimeUnit.SECONDS);
                if (outcome.isPresent()) {
                    winners.add(outcome.get());
                } else {
                    duplicateRejections++;
                }
            }

            assertEquals(1, winners.size(), "Exactly one concurrent registration must win the name");
            assertEquals(contenders - 1, duplicateRejections);
            assertTrue(registry.findSession(winners.get(0).sessionId()).isPresent());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void reusesConfiguredRoomCatalogueAndPreservesBindingOnUnknownRoomJoin() throws RoomRegistryException {
        List<RoomDefinition> customCatalogue = List.of(
                new RoomDefinition("netprog", "Network Programming"),
                new RoomDefinition("revision", "Exam Revision"),
                new RoomDefinition("workshop", "Project Workshop")
        );
        RoomSessionRegistry registry = new RoomSessionRegistry(customCatalogue);
        assertEquals(customCatalogue, registry.listRooms());

        List<RoomBinding> invalidations = new CopyOnWriteArrayList<>();
        registry.addInvalidationListener(invalidations::add);

        SessionInfo session = registry.registerSession("Amal");
        RoomBinding activeBinding = registry.joinRoom(session.sessionId(), "netprog");
        assertEquals(new RoomBinding(session.sessionId(), "netprog", 1L), activeBinding);
        assertTrue(registry.isCurrent(activeBinding));

        // Attempt to join an unknown room and a blank room
        RoomRegistryException unknownEx = assertThrows(
                RoomRegistryException.class,
                () -> registry.joinRoom(session.sessionId(), "nonexistent-room")
        );
        assertEquals(RoomRegistryErrorCode.UNKNOWN_ROOM, unknownEx.errorCode());

        RoomRegistryException blankEx = assertThrows(
                RoomRegistryException.class,
                () -> registry.joinRoom(session.sessionId(), "   ")
        );
        assertEquals(RoomRegistryErrorCode.UNKNOWN_ROOM, blankEx.errorCode());

        // Current valid binding must remain completely intact and no invalidation fired
        assertTrue(registry.isCurrent(activeBinding), "Failed join must preserve current valid binding");
        assertEquals(Optional.of(activeBinding), registry.currentBinding(session.sessionId()));
        assertTrue(invalidations.isEmpty(), "Failed join must not trigger invalidation listeners");
    }

    @Test
    void supportsWorkedSessionLifecycleJoinSwitchLeaveCloseAndReconnect() throws RoomRegistryException {
        RoomSessionRegistry registry = new RoomSessionRegistry(StudyLinkConfig.srsDefaults());

        // 1. Register Amal -> starts outside all rooms
        SessionInfo firstSession = registry.registerSession("Amal");
        assertEquals("Amal", firstSession.displayName());
        assertEquals(Optional.empty(), registry.currentBinding(firstSession.sessionId()));

        // 2. Join netprog -> revision 1
        RoomBinding netprogBinding = registry.joinRoom(firstSession.sessionId(), "netprog");
        assertEquals(new RoomBinding(firstSession.sessionId(), "netprog", 1L), netprogBinding);
        assertTrue(registry.isCurrent(netprogBinding));
        assertEquals(List.of(firstSession), registry.listRoomMembers("netprog"));

        // 3. Switch to revision -> revision 2; old netprog binding is immediately rejected
        RoomBinding revisionBinding = registry.joinRoom(firstSession.sessionId(), "revision");
        assertEquals(new RoomBinding(firstSession.sessionId(), "revision", 2L), revisionBinding);
        assertFalse(registry.isCurrent(netprogBinding), "Old netprog binding must be rejected after switch");
        assertTrue(registry.isCurrent(revisionBinding));
        assertTrue(registry.listRoomMembers("netprog").isEmpty());
        assertEquals(List.of(firstSession), registry.listRoomMembers("revision"));

        // 4. Leave room -> revisionBinding invalidated, but session and name remain reserved
        Optional<RoomBinding> leftBinding = registry.leaveRoom(firstSession.sessionId());
        assertEquals(Optional.of(revisionBinding), leftBinding);
        assertFalse(registry.isCurrent(revisionBinding));
        assertEquals(Optional.empty(), registry.currentBinding(firstSession.sessionId()));
        assertTrue(registry.findSession(firstSession.sessionId()).isPresent());
        RoomRegistryException duplicateWhileLoggedIn = assertThrows(
                RoomRegistryException.class,
                () -> registry.registerSession("amal")
        );
        assertEquals(RoomRegistryErrorCode.NAME_IN_USE, duplicateWhileLoggedIn.errorCode());

        // 5. Close session -> releases name and invalidates session
        assertTrue(registry.closeSession(firstSession.sessionId()));
        assertEquals(Optional.empty(), registry.findSession(firstSession.sessionId()));

        // 6. Reconnect with the same name -> issues a fresh session ID
        SessionInfo reconnectedSession = registry.registerSession("Amal");
        assertNotEquals(firstSession.sessionId(), reconnectedSession.sessionId(),
                "Reconnecting must create a fresh session ID");
        RoomBinding rejoinedBinding = registry.joinRoom(reconnectedSession.sessionId(), "netprog");
        assertEquals(new RoomBinding(reconnectedSession.sessionId(), "netprog", 1L), rejoinedBinding);
        assertTrue(registry.isCurrent(rejoinedBinding));
        assertFalse(registry.isCurrent(netprogBinding));
    }

    @Test
    void switchingAtoBtoARejectsOriginalABindingAndLateCloseCannotDisturbReconnectedSession()
            throws RoomRegistryException {
        RoomSessionRegistry registry = new RoomSessionRegistry(StudyLinkConfig.srsDefaults());

        SessionInfo oldSession = registry.registerSession("Amal");
        RoomBinding bindingA1 = registry.joinRoom(oldSession.sessionId(), "netprog");
        RoomBinding bindingB2 = registry.joinRoom(oldSession.sessionId(), "revision");
        RoomBinding bindingA3 = registry.joinRoom(oldSession.sessionId(), "netprog");

        assertEquals(1L, bindingA1.revision());
        assertEquals(2L, bindingB2.revision());
        assertEquals(3L, bindingA3.revision());

        // Original A binding (revision 1) must NEVER become current again when returning to room A
        assertFalse(registry.isCurrent(bindingA1), "Original A binding (rev 1) must remain invalid after A -> B -> A");
        assertFalse(registry.isCurrent(bindingB2), "Intermediate B binding (rev 2) must be invalid");
        assertTrue(registry.isCurrent(bindingA3), "Only latest A binding (rev 3) must be current");

        // Close oldSession and reconnect as Amal
        assertTrue(registry.closeSession(oldSession.sessionId()));
        SessionInfo newSession = registry.registerSession("Amal");
        RoomBinding newBinding = registry.joinRoom(newSession.sessionId(), "netprog");
        assertTrue(registry.isCurrent(newBinding));

        // Late/repeated closeSession calls for oldSession must return false and never release newSession's name
        assertFalse(registry.closeSession(oldSession.sessionId()));
        assertFalse(registry.closeSession(oldSession.sessionId()));

        assertTrue(registry.findSession(newSession.sessionId()).isPresent());
        assertTrue(registry.isCurrent(newBinding), "Late cleanup of old session must not disturb reconnected session");
        RoomRegistryException stillReservedEx = assertThrows(
                RoomRegistryException.class,
                () -> registry.registerSession("AMAL")
        );
        assertEquals(RoomRegistryErrorCode.NAME_IN_USE, stillReservedEx.errorCode());
    }

    @Test
    void staleGuardedActionsAreNeverExecuted() throws Exception {
        RoomSessionRegistry registry = new RoomSessionRegistry(StudyLinkConfig.srsDefaults());
        SessionInfo session = registry.registerSession("Kasun");
        RoomBinding binding1 = registry.joinRoom(session.sessionId(), "netprog");

        // Admitted action while binding1 is current
        AtomicInteger executionCount = new AtomicInteger();
        Optional<String> committed = registry.executeIfCurrent(binding1, () -> {
            executionCount.incrementAndGet();
            return "file-101";
        });
        assertEquals(Optional.of("file-101"), committed);
        assertEquals(1, executionCount.get());

        // Switch room -> binding1 becomes stale
        RoomBinding binding2 = registry.joinRoom(session.sessionId(), "revision");
        Optional<String> afterSwitch = registry.executeIfCurrent(binding1, () -> {
            executionCount.incrementAndGet();
            return "should-not-commit";
        });
        assertEquals(Optional.empty(), afterSwitch);
        assertFalse(registry.runIfCurrent(binding1, executionCount::incrementAndGet));
        assertEquals(1, executionCount.get(), "Stale binding after switch must never execute guarded action");

        // Leave room -> binding2 becomes stale
        registry.leaveRoom(session.sessionId());
        assertFalse(registry.runIfCurrent(binding2, executionCount::incrementAndGet));
        assertEquals(1, executionCount.get(), "Stale binding after leave must never execute guarded action");

        // Rejoin then close session -> binding3 becomes stale
        RoomBinding binding3 = registry.joinRoom(session.sessionId(), "workshop");
        registry.closeSession(session.sessionId());
        assertFalse(registry.runIfCurrent(binding3, executionCount::incrementAndGet));
        assertFalse(registry.runIfCurrent(null, executionCount::incrementAndGet));
        assertEquals(1, executionCount.get(), "Stale binding after session close must never execute guarded action");
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void admittedGuardedActionAndConcurrentRoomChangeHaveConsistentSerialOrdering() throws Exception {
        RoomSessionRegistry registry = new RoomSessionRegistry(StudyLinkConfig.srsDefaults());
        SessionInfo session = registry.registerSession("Nimal");
        RoomBinding initialBinding = registry.joinRoom(session.sessionId(), "netprog");

        CountDownLatch actionAdmittedLatch = new CountDownLatch(1);
        CountDownLatch releaseActionLatch = new CountDownLatch(1);
        CountDownLatch switcherReadyLatch = new CountDownLatch(1);
        List<String> eventTimeline = new CopyOnWriteArrayList<>();

        registry.addInvalidationListener(invalidated ->
                eventTimeline.add("INVALIDATED:" + invalidated.roomId() + "@r" + invalidated.revision())
        );

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Optional<String>> guardedCommitFuture = executor.submit(() ->
                    registry.executeIfCurrent(initialBinding, () -> {
                        eventTimeline.add("COMMIT_START:" + initialBinding.roomId() + "@r" + initialBinding.revision());
                        actionAdmittedLatch.countDown();
                        assertTrue(switcherReadyLatch.await(5, TimeUnit.SECONDS));
                        assertTrue(releaseActionLatch.await(5, TimeUnit.SECONDS));
                        // While inside the admitted guarded action, initialBinding is still current
                        assertTrue(registry.isCurrent(initialBinding));
                        eventTimeline.add("COMMIT_END:" + initialBinding.roomId() + "@r" + initialBinding.revision());
                        return "bundle-committed";
                    })
            );

            assertTrue(actionAdmittedLatch.await(5, TimeUnit.SECONDS));

            Future<RoomBinding> switchRoomFuture = executor.submit(() -> {
                switcherReadyLatch.countDown();
                RoomBinding switched = registry.joinRoom(session.sessionId(), "revision");
                eventTimeline.add("SWITCH_DONE:" + switched.roomId() + "@r" + switched.revision());
                return switched;
            });

            assertTrue(switcherReadyLatch.await(5, TimeUnit.SECONDS));
            releaseActionLatch.countDown();

            Optional<String> commitOutcome = guardedCommitFuture.get(5, TimeUnit.SECONDS);
            RoomBinding newBinding = switchRoomFuture.get(5, TimeUnit.SECONDS);

            assertEquals(Optional.of("bundle-committed"), commitOutcome);
            assertEquals(new RoomBinding(session.sessionId(), "revision", 2L), newBinding);

            // Admitted action must finish completely before the concurrent room switch invalidates initialBinding
            assertEquals(
                    List.of(
                            "COMMIT_START:netprog@r1",
                            "COMMIT_END:netprog@r1",
                            "INVALIDATED:netprog@r1",
                            "SWITCH_DONE:revision@r2"
                    ),
                    eventTimeline
            );

            // Any subsequent guarded action with initialBinding after the switch is rejected
            AtomicBoolean postSwitchExecuted = new AtomicBoolean(false);
            assertFalse(registry.runIfCurrent(initialBinding, () -> postSwitchExecuted.set(true)));
            assertFalse(postSwitchExecuted.get());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void invalidationListenerReportsExactOldBindingOutsideLockAfterNewStateIsCommitted()
            throws RoomRegistryException {
        RoomSessionRegistry registry = new RoomSessionRegistry(StudyLinkConfig.srsDefaults());
        SessionInfo session = registry.registerSession("Maya");

        List<RoomBinding> observedInvalidations = new ArrayList<>();
        AtomicBoolean heldLockDuringCallback = new AtomicBoolean(false);
        AtomicBoolean oldBindingWasStillCurrentDuringCallback = new AtomicBoolean(false);
        AtomicBoolean newStateWasAlreadyVisibleDuringCallback = new AtomicBoolean(false);

        BindingInvalidationListener listener = invalidated -> {
            observedInvalidations.add(invalidated);
            if (registry.isLockHeldByCurrentThread()) {
                heldLockDuringCallback.set(true);
            }
            if (registry.isCurrent(invalidated)) {
                oldBindingWasStillCurrentDuringCallback.set(true);
            }
            Optional<RoomBinding> latest = registry.currentBinding(session.sessionId());
            if (invalidated.revision() == 1L) {
                // During switch rev 1 -> rev 2, rev 2 must already be active and current
                RoomBinding expectedRev2 = new RoomBinding(session.sessionId(), "revision", 2L);
                if (latest.equals(Optional.of(expectedRev2)) && registry.isCurrent(expectedRev2)) {
                    newStateWasAlreadyVisibleDuringCallback.set(true);
                }
            }
        };

        registry.addInvalidationListener(listener);

        // First join has no prior binding to invalidate
        RoomBinding rev1 = registry.joinRoom(session.sessionId(), "netprog");
        assertTrue(observedInvalidations.isEmpty());

        // Switch netprog (rev 1) -> revision (rev 2): notifies exact rev1 while rev2 is already active
        RoomBinding rev2 = registry.joinRoom(session.sessionId(), "revision");
        assertEquals(List.of(rev1), observedInvalidations);
        assertFalse(heldLockDuringCallback.get(), "Listener must never run under the registry lock");
        assertFalse(oldBindingWasStillCurrentDuringCallback.get(), "Old binding must already be invalid when notified");
        assertTrue(newStateWasAlreadyVisibleDuringCallback.get(), "New binding must already be current when notified");
        assertTrue(registry.isCurrent(rev2));

        // Leave room: notifies exact rev2
        registry.leaveRoom(session.sessionId());
        assertEquals(List.of(rev1, rev2), observedInvalidations);
        assertFalse(heldLockDuringCallback.get());
        assertFalse(oldBindingWasStillCurrentDuringCallback.get());

        // Join workshop (rev 3) and close session: notifies exact rev3
        RoomBinding rev3 = registry.joinRoom(session.sessionId(), "workshop");
        assertTrue(registry.closeSession(session.sessionId()));
        assertEquals(List.of(rev1, rev2, rev3), observedInvalidations);
        assertFalse(heldLockDuringCallback.get());
        assertFalse(oldBindingWasStillCurrentDuringCallback.get());

        // Removing listener stops further notifications
        registry.removeInvalidationListener(listener);
        SessionInfo other = registry.registerSession("Ravi");
        registry.joinRoom(other.sessionId(), "netprog");
        registry.closeSession(other.sessionId());
        assertEquals(3, observedInvalidations.size());
    }

    @Test
    void throwingInvalidationListenerDoesNotAbortSwitchLeaveOrCloseAndHealthyListenerReceivesOldBindings()
            throws RoomRegistryException {
        RoomSessionRegistry registry = new RoomSessionRegistry(StudyLinkConfig.srsDefaults());
        AtomicInteger throwingCalls = new AtomicInteger();
        List<RoomBinding> healthyReceived = new ArrayList<>();
        AtomicBoolean heldLockInListener = new AtomicBoolean(false);

        registry.addInvalidationListener(invalidated -> {
            if (registry.isLockHeldByCurrentThread()) {
                heldLockInListener.set(true);
            }
            throwingCalls.incrementAndGet();
            throw new IllegalStateException("Simulated listener failure for " + invalidated);
        });

        registry.addInvalidationListener(invalidated -> {
            if (registry.isLockHeldByCurrentThread()) {
                heldLockInListener.set(true);
            }
            assertFalse(registry.isCurrent(invalidated),
                    "Invalidated binding must already be non-current when healthy listener runs");
            healthyReceived.add(invalidated);
        });

        SessionInfo session = registry.registerSession("Amal");
        RoomBinding rev1 = registry.joinRoom(session.sessionId(), "netprog");
        assertTrue(healthyReceived.isEmpty());

        // 1. Switch netprog (rev 1) -> revision (rev 2)
        RoomBinding rev2 = registry.joinRoom(session.sessionId(), "revision");
        assertEquals(new RoomBinding(session.sessionId(), "revision", 2L), rev2);
        assertFalse(registry.isCurrent(rev1));
        assertTrue(registry.isCurrent(rev2));
        assertEquals(Optional.of(rev2), registry.currentBinding(session.sessionId()));
        assertEquals(1, throwingCalls.get());
        assertEquals(List.of(rev1), healthyReceived);

        // 2. Leave revision (rev 2)
        Optional<RoomBinding> left = registry.leaveRoom(session.sessionId());
        assertEquals(Optional.of(rev2), left);
        assertFalse(registry.isCurrent(rev2));
        assertEquals(Optional.empty(), registry.currentBinding(session.sessionId()));
        assertTrue(registry.findSession(session.sessionId()).isPresent());
        assertEquals(2, throwingCalls.get());
        assertEquals(List.of(rev1, rev2), healthyReceived);

        // 3. Join workshop (rev 3) and close session
        RoomBinding rev3 = registry.joinRoom(session.sessionId(), "workshop");
        assertTrue(registry.closeSession(session.sessionId()));
        assertFalse(registry.isCurrent(rev3));
        assertEquals(Optional.empty(), registry.findSession(session.sessionId()));
        assertEquals(3, throwingCalls.get());
        assertEquals(List.of(rev1, rev2, rev3), healthyReceived);
        assertFalse(heldLockInListener.get(), "Listeners must never be called under the registry lock");

        // Closing the session must still have released the display name "Amal"
        SessionInfo reconnected = registry.registerSession("Amal");
        assertNotEquals(session.sessionId(), reconnected.sessionId());
    }

    @Test
    void doesNotSwallowJvmErrorsFromInvalidationListener() throws RoomRegistryException {
        final class SimulatedJvmError extends Error {
            private SimulatedJvmError() {
                super("simulated JVM error");
            }
        }

        RoomSessionRegistry registry = new RoomSessionRegistry(StudyLinkConfig.srsDefaults());
        registry.addInvalidationListener(invalidated -> {
            throw new SimulatedJvmError();
        });

        SessionInfo session = registry.registerSession("Amal");
        RoomBinding rev1 = registry.joinRoom(session.sessionId(), "netprog");

        assertThrows(SimulatedJvmError.class, () -> registry.joinRoom(session.sessionId(), "revision"));
        assertFalse(registry.isCurrent(rev1));
        assertTrue(registry.isCurrent(new RoomBinding(session.sessionId(), "revision", 2L)));
    }

    @Test
    void rejectsUnicodeCaseFoldEquivalentDisplayNamesInBothOrdersAndSupportsReleaseAndLateCleanup()
            throws RoomRegistryException {
        assertTrue("Straße".equalsIgnoreCase("STRAẞE"));
        RoomSessionRegistry registry = new RoomSessionRegistry(StudyLinkConfig.srsDefaults());

        // 1. Register "Straße", then reject "STRAẞE"
        SessionInfo first = registry.registerSession("Straße");
        assertEquals("Straße", first.displayName());

        RoomRegistryException firstDup = assertThrows(
                RoomRegistryException.class,
                () -> registry.registerSession("STRAẞE")
        );
        assertEquals(RoomRegistryErrorCode.NAME_IN_USE, firstDup.errorCode());

        // 2. Close "Straße", then register "STRAẞE" and reject "Straße"
        assertTrue(registry.closeSession(first.sessionId()));

        SessionInfo second = registry.registerSession("STRAẞE");
        assertEquals("STRAẞE", second.displayName());
        assertNotEquals(first.sessionId(), second.sessionId());

        RoomRegistryException secondDup = assertThrows(
                RoomRegistryException.class,
                () -> registry.registerSession("Straße")
        );
        assertEquals(RoomRegistryErrorCode.NAME_IN_USE, secondDup.errorCode());

        // 3. Late cleanup of old session 'first' must not release 'second'
        assertFalse(registry.closeSession(first.sessionId()));
        assertTrue(registry.findSession(second.sessionId()).isPresent());
        RoomRegistryException stillReserved = assertThrows(
                RoomRegistryException.class,
                () -> registry.registerSession("Straße")
        );
        assertEquals(RoomRegistryErrorCode.NAME_IN_USE, stillReserved.errorCode());

        // 4. Closing 'second' releases the name for future re-registration
        assertTrue(registry.closeSession(second.sessionId()));
        SessionInfo third = registry.registerSession("Straße");
        assertEquals("Straße", third.displayName());
    }
}
