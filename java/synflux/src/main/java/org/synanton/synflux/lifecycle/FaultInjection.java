package org.synanton.synflux.lifecycle;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Deterministic failure injection for the vertical-slice acceptance run
 * (#253) — the documented public trigger for the recovery paths the unit
 * tests (#289, #293) prove at module level.
 *
 * <p>Set {@code SYNANTON_FAULT_INJECT} to a named point and the worker
 * crashes (exit 70) on reaching it, leaving the workflow non-terminal on
 * purpose. Points:
 *
 * <ul>
 *   <li>{@code after-discover} — discovery checkpoint written, before acquire</li>
 *   <li>{@code after-acquire} — content in the Content Cache, before the
 *       source version / manifest</li>
 *   <li>{@code after-manifest} — source version, manifest, and chunks
 *       committed, run not COMPLETED — the #293 visibility-gate window</li>
 * </ul>
 *
 * <p>The crash is one-shot per process: restart the worker without the
 * variable and recovery resumes from the checkpoint. Timing-based kills are
 * deliberately not used — they flake under load and cannot target the
 * manifest/COMPLETED window.
 */
public final class FaultInjection {

    private static final Logger log = LoggerFactory.getLogger(FaultInjection.class);
    /** Distinct exit code: an injected crash is never a spontaneous failure. */
    static final int INJECTED_EXIT_CODE = 70;
    public static final String ENV_VAR = "SYNANTON_FAULT_INJECT";

    private static final AtomicBoolean FIRED = new AtomicBoolean(false);
    private static volatile String configuredPointOverride;

    @FunctionalInterface
    interface ExitAction {
        void exit(int code);
    }

    private static volatile ExitAction exitAction = System::exit;

    private FaultInjection() {
    }

    /** Crashes the worker when the configured point matches; no-op otherwise. */
    public static void crashAt(String point) {
        String configured = configuredPoint();
        if (!point.equals(configured == null ? "" : configured.trim()) || !FIRED.compareAndSet(false, true)) {
            return;
        }
        log.error("FAULT INJECTED ({}): crashing worker (exit {}) — restart without {} to resume recovery",
                point, INJECTED_EXIT_CODE, ENV_VAR);
        exitAction.exit(INJECTED_EXIT_CODE);
    }

    private static String configuredPoint() {
        String override = configuredPointOverride;
        return override != null ? override : System.getenv(ENV_VAR);
    }

    /** Test seam: pins the configured point (tests cannot write real env vars). */
    static void configureForTest(String point) {
        configuredPointOverride = point;
    }

    /** Test seam: redirects the exit action (the real one terminates the JVM). */
    static void setExitAction(ExitAction action) {
        exitAction = action;
    }

    static void reset() {
        FIRED.set(false);
        configuredPointOverride = null;
        exitAction = System::exit;
    }
}
