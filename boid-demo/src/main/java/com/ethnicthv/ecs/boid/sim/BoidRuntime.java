package com.ethnicthv.ecs.boid.sim;

import com.ethnicthv.ecs.boid.debug.WorldStatsCollector;
import com.ethnicthv.ecs.core.execution.SystemThreadMode;

import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

/**
 * Boid demo runtime wrapper that can either run simulation steps on the main
 * thread or on a dedicated simulation thread with mailbox + published snapshots.
 */
public final class BoidRuntime implements AutoCloseable {
    private static final long WORLD_STATS_PUBLISH_INTERVAL_NANOS = 250_000_000L;
    private static final long IDLE_PARK_NANOS = 1_000_000L;

    private final Object lifecycleLock = new Object();
    private final BoidSimulation simulation;
    private final SystemThreadMode threadMode;
    private final ConcurrentLinkedQueue<SimulationCommand> pendingCommands = new ConcurrentLinkedQueue<>();
    private final RenderSnapshot[] renderSnapshots = new RenderSnapshot[] {
        new RenderSnapshot(new float[0], 0),
        new RenderSnapshot(new float[0], 0)
    };

    private volatile PublishedState publishedState;
    private volatile boolean bootstrapped;
    private volatile boolean closed;
    private volatile boolean running;
    private volatile boolean paused;
    private volatile Thread simulationThread;
    private volatile Throwable runtimeFailure;
    private long lastWorldStatsPublishNanos;
    private int publishedRenderIndex;

    public BoidRuntime(SimulationConfig config, SystemThreadMode threadMode) {
        this.simulation = new BoidSimulation(config);
        this.threadMode = Objects.requireNonNull(threadMode, "threadMode");
    }

    public void bootstrap() {
        ensureAlive();
        if (bootstrapped) {
            return;
        }

        if (threadMode == SystemThreadMode.MAIN_THREAD) {
            simulation.bootstrap();
            publishState(true);
            bootstrapped = true;
            return;
        }

        startDedicatedThread();
        runOnSimulationThreadAndWait(simulation -> {
            simulation.bootstrap();
            bootstrapped = true;
            publishState(true);
        });
    }

    public SystemThreadMode threadMode() {
        return threadMode;
    }

    public void stepFixed() {
        ensureBootstrapped();
        checkRuntimeFailure();
        ensureMainThreadMode("stepFixed");
        if (paused) {
            return;
        }
        simulation.stepFixed();
        publishState(false);
    }

    public float fixedDeltaTime() {
        checkRuntimeFailure();
        return simulation.fixedDeltaTime();
    }

    public int boidCount() {
        checkRuntimeFailure();
        return publishedState().stats().boidCount();
    }

    public float worldHalfExtent() {
        checkRuntimeFailure();
        return simulation.worldHalfExtent();
    }

    public BoidSimulation.SimulationStats stats() {
        checkRuntimeFailure();
        return publishedState().stats();
    }

    public WorldStatsCollector.WorldStatsSnapshot worldStats() {
        checkRuntimeFailure();
        return publishedState().worldStats();
    }

    public boolean isPaused() {
        checkRuntimeFailure();
        return paused;
    }

    public void setPaused(boolean paused) {
        ensureBootstrapped();
        checkRuntimeFailure();
        this.paused = paused;
        if (threadMode == SystemThreadMode.MAIN_THREAD) {
            publishState(true);
            return;
        }
        pendingCommands.add(simulation -> { });
        unparkSimulationThread();
    }

    public int copyPositions(float[] target) {
        return copyPositions(target, 1);
    }

    public int copyPositions(float[] target, int stride) {
        checkRuntimeFailure();
        PublishedState state = publishedState();
        RenderSnapshot snapshot = state.renderSnapshot();
        if (stride <= 0) {
            throw new IllegalArgumentException("stride must be > 0");
        }

        int visibleBoids = (snapshot.boidCount() + stride - 1) / stride;
        int requiredLength = visibleBoids * 3;
        if (target.length < requiredLength) {
            throw new IllegalArgumentException("target length must be >= " + requiredLength);
        }

        int visibleIndex = 0;
        float[] positions = snapshot.positions();
        for (int boidIndex = 0; boidIndex < snapshot.boidCount(); boidIndex += stride) {
            int sourceBase = boidIndex * 3;
            int targetBase = visibleIndex * 3;
            target[targetBase] = positions[sourceBase];
            target[targetBase + 1] = positions[sourceBase + 1];
            target[targetBase + 2] = positions[sourceBase + 2];
            visibleIndex++;
        }
        return visibleIndex;
    }

    public SteeringExecutionMode steeringExecutionMode() {
        checkRuntimeFailure();
        return publishedState().controls().steeringExecutionMode();
    }

    public void setSteeringExecutionMode(SteeringExecutionMode executionMode) {
        submitCommand(simulation -> simulation.setSteeringExecutionMode(executionMode));
    }

    public SteeringExecutionMode toggleSteeringExecutionMode() {
        SteeringExecutionMode toggledMode = steeringExecutionMode().toggle();
        setSteeringExecutionMode(toggledMode);
        return toggledMode;
    }

    public float separationWeight() {
        checkRuntimeFailure();
        return publishedState().controls().separationWeight();
    }

    public void setSeparationWeight(float separationWeight) {
        submitCommand(simulation -> simulation.setSeparationWeight(separationWeight));
    }

    public float alignmentWeight() {
        checkRuntimeFailure();
        return publishedState().controls().alignmentWeight();
    }

    public void setAlignmentWeight(float alignmentWeight) {
        submitCommand(simulation -> simulation.setAlignmentWeight(alignmentWeight));
    }

    public float cohesionWeight() {
        checkRuntimeFailure();
        return publishedState().controls().cohesionWeight();
    }

    public void setCohesionWeight(float cohesionWeight) {
        submitCommand(simulation -> simulation.setCohesionWeight(cohesionWeight));
    }

    public float maxForce() {
        checkRuntimeFailure();
        return publishedState().controls().maxForce();
    }

    public void setMaxForce(float maxForce) {
        submitCommand(simulation -> simulation.setMaxForce(maxForce));
    }

    public void resetSteeringParameters() {
        submitCommand(BoidSimulation::resetSteeringParameters);
    }

    public void resetBoids(int boidCount, long randomSeed) {
        submitCommand(simulation -> simulation.resetBoids(boidCount, randomSeed));
    }

    public void shutdown() {
        close();
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        stopDedicatedThread();
        simulation.close();
    }

    private void submitCommand(SimulationCommand command) {
        ensureBootstrapped();
        checkRuntimeFailure();
        Objects.requireNonNull(command, "command");
        if (threadMode == SystemThreadMode.MAIN_THREAD) {
            command.apply(simulation);
            publishState(true);
            return;
        }

        pendingCommands.add(command);
        unparkSimulationThread();
    }

    private void startDedicatedThread() {
        synchronized (lifecycleLock) {
            if (simulationThread != null) {
                return;
            }
            running = true;
            Thread worker = Thread.ofPlatform()
                .name("boid-sim-runtime")
                .daemon(true)
                .unstarted(this::runDedicatedLoop);
            simulationThread = worker;
            worker.start();
        }
    }

    private void stopDedicatedThread() {
        Thread worker;
        synchronized (lifecycleLock) {
            running = false;
            worker = simulationThread;
            if (worker != null) {
                worker.interrupt();
            }
        }
        if (worker == null) {
            return;
        }

        boolean interrupted = false;
        while (worker.isAlive()) {
            try {
                worker.join();
            } catch (InterruptedException ex) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private void runDedicatedLoop() {
        double accumulator = 0.0;
        long previousTime = System.nanoTime();
        try {
            while (running && !Thread.currentThread().isInterrupted()) {
                long now = System.nanoTime();
                double frameTime = (now - previousTime) / 1_000_000_000.0;
                previousTime = now;
                if (frameTime > 0.25) {
                    frameTime = 0.25;
                }

                accumulator += frameTime;
                boolean changed = drainCommands();
                boolean stepped = false;

                if (bootstrapped && !paused) {
                    while (accumulator >= simulation.fixedDeltaTime()) {
                        simulation.stepFixed();
                        accumulator -= simulation.fixedDeltaTime();
                        stepped = true;
                        changed = true;
                    }
                }

                if (changed) {
                    publishState(false);
                }

                if (!stepped) {
                    long nanosUntilTick = (long) ((simulation.fixedDeltaTime() - Math.min(accumulator, simulation.fixedDeltaTime())) * 1_000_000_000.0);
                    LockSupport.parkNanos(Math.max(250_000L, Math.min(IDLE_PARK_NANOS, nanosUntilTick)));
                }
            }
        } catch (Throwable thrown) {
            runtimeFailure = thrown;
        } finally {
            synchronized (lifecycleLock) {
                simulationThread = null;
                running = false;
            }
        }
    }

    private boolean drainCommands() {
        boolean changed = false;
        SimulationCommand command;
        while ((command = pendingCommands.poll()) != null) {
            command.apply(simulation);
            changed = true;
        }
        return changed;
    }

    private void runOnSimulationThreadAndWait(SimulationCommand command) {
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        pendingCommands.add(simulation -> {
            try {
                command.apply(simulation);
            } catch (Throwable thrown) {
                failure.set(thrown);
                throw thrown;
            } finally {
                completed.countDown();
            }
        });
        unparkSimulationThread();

        boolean interrupted = false;
        while (completed.getCount() > 0) {
            checkRuntimeFailure();
            try {
                completed.await(10L, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (InterruptedException ex) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }

        Throwable thrown = failure.get();
        if (thrown != null) {
            if (thrown instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("Dedicated simulation command failed", thrown);
        }
        checkRuntimeFailure();
    }

    private void publishState(boolean forceWorldStats) {
        BoidSimulation.SimulationStats stats = simulation.getStats();
        WorldStatsCollector.WorldStatsSnapshot currentWorldStats = publishedState == null
            ? null
            : publishedState.worldStats();

        long now = System.nanoTime();
        if (forceWorldStats || currentWorldStats == null || now - lastWorldStatsPublishNanos >= WORLD_STATS_PUBLISH_INTERVAL_NANOS) {
            currentWorldStats = simulation.collectWorldStats();
            lastWorldStatsPublishNanos = now;
        }

        RenderSnapshot renderSnapshot = publishRenderSnapshot(stats.boidCount());
        ControlState controlState = new ControlState(
            simulation.steeringExecutionMode(),
            simulation.separationWeight(),
            simulation.alignmentWeight(),
            simulation.cohesionWeight(),
            simulation.maxForce()
        );
        publishedState = new PublishedState(renderSnapshot, stats, currentWorldStats, controlState, paused, threadMode);
    }

    private RenderSnapshot publishRenderSnapshot(int boidCount) {
        int writeIndex = 1 - publishedRenderIndex;
        RenderSnapshot snapshot = renderSnapshots[writeIndex];
        int requiredLength = boidCount * 3;
        if (snapshot.positions().length < requiredLength) {
            snapshot = new RenderSnapshot(new float[requiredLength], boidCount);
            renderSnapshots[writeIndex] = snapshot;
        }

        int copiedBoids = simulation.copyPositions(snapshot.positions());
        RenderSnapshot publishedSnapshot = new RenderSnapshot(snapshot.positions(), copiedBoids);
        renderSnapshots[writeIndex] = publishedSnapshot;
        publishedRenderIndex = writeIndex;
        return publishedSnapshot;
    }

    private PublishedState publishedState() {
        PublishedState state = publishedState;
        if (state == null) {
            throw new IllegalStateException("Runtime state has not been published yet");
        }
        return state;
    }

    private void unparkSimulationThread() {
        Thread worker = simulationThread;
        if (worker != null) {
            LockSupport.unpark(worker);
        }
    }

    private void ensureAlive() {
        if (closed) {
            throw new IllegalStateException("Boid runtime has been closed");
        }
    }

    private void checkRuntimeFailure() {
        Throwable thrown = runtimeFailure;
        if (thrown == null) {
            return;
        }
        if (thrown instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        throw new IllegalStateException("Boid runtime thread failed", thrown);
    }

    private void ensureBootstrapped() {
        ensureAlive();
        if (!bootstrapped) {
            throw new IllegalStateException("Boid runtime must be bootstrapped before use");
        }
    }

    private void ensureMainThreadMode(String operation) {
        if (threadMode != SystemThreadMode.MAIN_THREAD) {
            throw new IllegalStateException(operation + " is only valid in MAIN_THREAD mode");
        }
    }

    @FunctionalInterface
    private interface SimulationCommand {
        void apply(BoidSimulation simulation);
    }

    private record RenderSnapshot(float[] positions, int boidCount) {
    }

    private record PublishedState(
        RenderSnapshot renderSnapshot,
        BoidSimulation.SimulationStats stats,
        WorldStatsCollector.WorldStatsSnapshot worldStats,
        ControlState controls,
        boolean paused,
        SystemThreadMode threadMode
    ) {
    }

    private record ControlState(
        SteeringExecutionMode steeringExecutionMode,
        float separationWeight,
        float alignmentWeight,
        float cohesionWeight,
        float maxForce
    ) {
    }
}
