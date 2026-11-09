package buildcraft.transport.pipe.flow;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nonnull;

import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import buildcraft.api.v2.energy.MjAmount;
import buildcraft.gametest.PipeGameTestSupport;
import buildcraft.gametest.PipeGameTestSupport.TestPipe;
import buildcraft.lib.BCLib;
import buildcraft.lib.internal.mj.IMjConnector;
import buildcraft.lib.internal.mj.IMjReceiver;
import buildcraft.lib.internal.mj.MjCapabilities;
import buildcraft.transport.BCTransportPipes;
import buildcraft.transport.internal.pipe.IPipe.ConnectedType;
import buildcraft.transport.internal.pipe.PipeDefinition;

/** Kinesis pipes with nothing to do: they must stay cheap and still wake up for new demand or power. */
@GameTestHolder(BCLib.MODID)
@PrefixGameTestTemplate(false)
public final class KinesisIdleGameTests {
    private static final long MJ = MjAmount.MICRO_MJ_PER_MJ;

    private KinesisIdleGameTests() {
    }

    /** A row that sat idle for 100 ticks delivers to a consumer that only starts asking afterwards. */
    @GameTest(templateNamespace = BCLib.MODID, template = PipeGameTestSupport.LARGE_EMPTY_TEMPLATE, timeoutTicks = 260)
    public static void idleKinesisRowWakesForLateDemand(GameTestHelper helper) {
        Network network = new Network(helper.getLevel());
        TestPipe[] row = network.line(16, BCTransportPipes.diamondPower);
        Consumer consumer = network.consumer(row[1]);
        network.engine(row[15]);
        consumer.capacity = 0;
        for (int tick = 1; tick <= 240; tick++) {
            final int t = tick;
            helper.runAfterDelay(tick, () -> {
                if (t == 100) {
                    require(helper, network.supplied == 0 && consumer.received == 0, "idle row moved power");
                    consumer.capacity = 4 * MJ;
                }
                network.tick();
            });
        }
        helper.runAfterDelay(241, () -> {
            System.out.println("[kinesis-idle] late demand: consumer got " + consumer.received + " uMJ of " + 4 * MJ);
            require(helper, consumer.received >= 3 * MJ,
                "consumer that started asking after an idle period got " + consumer.received + " of " + 4 * MJ);
            helper.succeed();
        });
    }

    /** Power pushed into an idle row by an engine reaches a consumer that was already asking: nothing is lost. */
    @GameTest(templateNamespace = BCLib.MODID, template = PipeGameTestSupport.LARGE_EMPTY_TEMPLATE, timeoutTicks = 120)
    public static void idleKinesisRowCarriesPowerOnceAnEngineRuns(GameTestHelper helper) {
        Network network = new Network(helper.getLevel());
        TestPipe[] row = network.line(16, BCTransportPipes.diamondPower);
        Consumer consumer = network.consumer(row[1]);
        consumer.capacity = 4 * MJ;
        network.engine(row[15]);
        network.engineOutput = 0;
        for (int tick = 1; tick <= 100; tick++) {
            final int t = tick;
            helper.runAfterDelay(tick, () -> {
                if (t == 40) {
                    network.engineOutput = 64 * MJ;
                }
                network.tick();
            });
        }
        helper.runAfterDelay(101, () -> {
            System.out.println("[kinesis-idle] late engine: consumer got " + consumer.received + " uMJ of " + 4 * MJ);
            require(helper, consumer.received >= 3 * MJ, "consumer got " + consumer.received + " of " + 4 * MJ);
            helper.succeed();
        });
    }

    /** Prints the cost of PipeFlowPower.onTick per pipe for an idle network and for a working one (no assertion). */
    @GameTest(templateNamespace = BCLib.MODID, template = PipeGameTestSupport.LARGE_EMPTY_TEMPLATE, timeoutTicks = 260)
    public static void kinesisTickCost(GameTestHelper helper) {
        Network idle = new Network(helper.getLevel());
        Network busy = new Network(helper.getLevel());
        for (int r = 0; r < 32; r++) {
            idle.line(65, BCTransportPipes.diamondPower);
        }
        for (int r = 0; r < 10; r++) {
            TestPipe[] row = busy.line(65, BCTransportPipes.diamondPower);
            for (int i = 2; i < 64; i += 3) {
                Consumer consumer = busy.consumer(row[i]);
                consumer.capacity = 2 * MJ;
                consumer.rate = MJ / 2;
            }
            busy.engine(row[64]);
        }
        long[] idleNanos = new long[1];
        long[] busyNanos = new long[1];
        for (int tick = 1; tick <= 240; tick++) {
            final boolean measuring = tick > 40;
            helper.runAfterDelay(tick, () -> {
                long a = System.nanoTime();
                idle.tick();
                long b = System.nanoTime();
                busy.tick();
                long c = System.nanoTime();
                if (measuring) {
                    idleNanos[0] += b - a;
                    busyNanos[0] += c - b;
                }
            });
        }
        helper.runAfterDelay(241, () -> {
            System.out.printf("[kinesis-tick-cost] idle: %d pipes, %.1f ns per pipe-tick; working: %d pipes, %.1f ns per"
                    + " pipe-tick (200 ticks)%n", idle.flows.size(), idleNanos[0] / 200.0 / idle.flows.size(),
                busy.flows.size(), busyNanos[0] / 200.0 / busy.flows.size());
            helper.succeed();
        });
    }

    private static void require(GameTestHelper helper, boolean condition, String message) {
        if (!condition) {
            helper.fail(message);
        }
    }

    private static final class Network {
        private final Level level;
        private final List<PipeFlowPower> flows = new ArrayList<>();
        private final List<PipeFlowPower> engines = new ArrayList<>();
        private final List<Consumer> consumers = new ArrayList<>();
        private long engineOutput = 64 * MJ;
        private long supplied;

        private Network(Level level) {
            this.level = level;
        }

        private TestPipe pipe(PipeDefinition definition) {
            TestPipe pipe = new TestPipe(level, definition);
            PipeFlowPower flow = new PipeFlowPower(pipe);
            pipe.setFlow(flow);
            flow.reconfigure();
            flows.add(flow);
            return pipe;
        }

        private static void join(TestPipe a, Direction side, TestPipe b) {
            a.connectPipe(side, b);
            b.connectPipe(side.getOpposite(), a);
        }

        private TestPipe[] line(int length, PipeDefinition definition) {
            TestPipe[] pipes = new TestPipe[length];
            for (int i = 0; i < length; i++) {
                pipes[i] = pipe(definition);
                if (i > 0) {
                    join(pipes[i - 1], Direction.EAST, pipes[i]);
                }
            }
            return pipes;
        }

        private Consumer consumer(TestPipe pipe) {
            Consumer consumer = new Consumer();
            consumers.add(consumer);
            pipe.connect(Direction.UP, ConnectedType.TILE).exposeCapability(Direction.UP, MjCapabilities.CAP_RECEIVER, consumer);
            return consumer;
        }

        /** A wooden kinesis pipe east of {@code target}, with an engine pushing into its east side. */
        private void engine(TestPipe target) {
            TestPipe wood = pipe(BCTransportPipes.woodPower);
            join(target, Direction.EAST, wood);
            wood.connect(Direction.EAST, ConnectedType.TILE);
            engines.add((PipeFlowPower) wood.getFlow());
        }

        private void tick() {
            for (PipeFlowPower engine : engines) {
                if (engineOutput > 0) {
                    supplied += engineOutput - engine.getSection(Direction.EAST).receivePower(engineOutput, FluidAction.EXECUTE);
                }
            }
            for (PipeFlowPower flow : flows) {
                flow.onTick();
            }
            for (Consumer consumer : consumers) {
                consumer.stored = Math.max(0, consumer.stored - consumer.rate);
            }
        }
    }

    /** Asks for its free space; with rate 0 it keeps what it got (so {@code received} is exact). */
    private static final class Consumer implements IMjReceiver {
        private long capacity;
        private long rate;
        private long stored;
        private long received;

        @Override
        public long getPowerRequested() {
            return Math.max(0, capacity - stored);
        }

        @Override
        public long receivePower(long microJoules, FluidAction action) {
            long accepted = Math.max(0, Math.min(microJoules, getPowerRequested()));
            if (action.execute()) {
                stored += accepted;
                received += accepted;
            }
            return microJoules - accepted;
        }

        @Override
        public boolean canConnect(@Nonnull IMjConnector other) {
            return true;
        }
    }
}
