package com.ethnicthv.ecs.core.execution;

import com.ethnicthv.ecs.core.system.GameLoop;
import com.ethnicthv.ecs.core.system.SystemManager;

import java.util.Objects;

/**
 * Small runtime wrapper that can either stay inert in main-thread mode or own
 * a dedicated simulation thread in dedicated-thread mode.
 */
public final class EcsRuntimeController {
    private static final String DEFAULT_THREAD_NAME = "ecs-simulation";
    private static final float DEFAULT_FIXED_TICK_RATE = 60.0f;

    private final Object lifecycleLock = new Object();
    private final SystemManager systemManager;
    private final SystemThreadMode threadMode;
    private final String simulationThreadName;
    private final float fixedTickRate;

    private volatile EcsRuntimeState state = EcsRuntimeState.NEW;
    private volatile Thread runtimeThread;
    private volatile GameLoop runtimeLoop;
    private volatile Throwable failure;

    public EcsRuntimeController(
        SystemManager systemManager,
        SystemThreadMode threadMode,
        String simulationThreadName,
        float fixedTickRate
    ) {
        this.systemManager = Objects.requireNonNull(systemManager, "systemManager");
        this.threadMode = Objects.requireNonNull(threadMode, "threadMode");
        this.simulationThreadName = simulationThreadName == null || simulationThreadName.isBlank()
            ? DEFAULT_THREAD_NAME
            : simulationThreadName;
        if (fixedTickRate <= 0f) {
            throw new IllegalArgumentException("fixedTickRate must be > 0");
        }
        this.fixedTickRate = fixedTickRate;
    }

    public static String defaultThreadName() {
        return DEFAULT_THREAD_NAME;
    }

    public static float defaultFixedTickRate() {
        return DEFAULT_FIXED_TICK_RATE;
    }

    public SystemThreadMode threadMode() {
        return threadMode;
    }

    public float fixedTickRate() {
        return fixedTickRate;
    }

    public EcsRuntimeState state() {
        return state;
    }

    public boolean isRunning() {
        return state == EcsRuntimeState.RUNNING;
    }

    public void start() {
        if (threadMode == SystemThreadMode.MAIN_THREAD) {
            return;
        }

        Thread threadToStart;
        synchronized (lifecycleLock) {
            if (state == EcsRuntimeState.RUNNING) {
                return;
            }
            if (state == EcsRuntimeState.STOPPING) {
                throw new IllegalStateException("Cannot start ECS runtime while it is stopping");
            }

            failure = null;
            GameLoop loop = new GameLoop(systemManager, fixedTickRate);
            Thread runtime = Thread.ofPlatform()
                .name(simulationThreadName)
                .daemon(true)
                .unstarted(() -> runLoop(loop));

            runtimeLoop = loop;
            runtimeThread = runtime;
            state = EcsRuntimeState.RUNNING;
            threadToStart = runtime;
        }

        threadToStart.start();
    }

    public void stop() {
        if (threadMode == SystemThreadMode.MAIN_THREAD) {
            return;
        }

        GameLoop loopToStop;
        Thread threadToInterrupt;
        synchronized (lifecycleLock) {
            if (state == EcsRuntimeState.NEW || state == EcsRuntimeState.STOPPED) {
                return;
            }
            state = EcsRuntimeState.STOPPING;
            loopToStop = runtimeLoop;
            threadToInterrupt = runtimeThread;
        }

        if (loopToStop != null) {
            loopToStop.stop();
        }
        if (threadToInterrupt != null) {
            threadToInterrupt.interrupt();
        }
    }

    public void awaitStop() {
        if (threadMode == SystemThreadMode.MAIN_THREAD) {
            return;
        }

        Thread threadToJoin = runtimeThread;
        if (threadToJoin != null) {
            boolean interrupted = false;
            while (threadToJoin.isAlive()) {
                try {
                    threadToJoin.join();
                } catch (InterruptedException ex) {
                    interrupted = true;
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }

        Throwable thrown = failure;
        if (thrown != null) {
            if (thrown instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("Dedicated ECS runtime failed", thrown);
        }
    }

    public void assertUpdateThread(String operation) {
        if (threadMode == SystemThreadMode.MAIN_THREAD) {
            return;
        }

        Thread owner = runtimeThread;
        if (owner == null || Thread.currentThread() != owner) {
            throw new IllegalStateException(
                operation + " is not allowed from thread '" + Thread.currentThread().getName() +
                    "' when systemThreadMode=DEDICATED_THREAD. Use startRuntime()/stopRuntime() " +
                    "and let the dedicated runtime own ECS updates."
            );
        }
    }

    private void runLoop(GameLoop loop) {
        try {
            loop.run();
        } catch (Throwable thrown) {
            failure = thrown;
        } finally {
            synchronized (lifecycleLock) {
                runtimeLoop = null;
                runtimeThread = null;
                state = EcsRuntimeState.STOPPED;
                lifecycleLock.notifyAll();
            }
        }
    }
}
