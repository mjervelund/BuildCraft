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

/**
 * Kinesis networks at the scale of the mega-base transport row: rows of diamond kinesis pipes with a small buffered
 * consumer every few pipes (an FE machine behind the MJ-to-FE converter: 20 FE buffer, 5 FE/t), fed by engines through
 * diamond-wood kinesis pipes. Every consumer must be kept running when the engines can supply far more than the demand
 * plus the pipes' resistance loss.
 */
@GameTestHolder(BCLib.MODID)
@PrefixGameTestTemplate(false)
public final class KinesisNetworkDeliveryGameTests {
    private static final long MJ = MjAmount.MICRO_MJ_PER_MJ;
    /** 20 FE at the default 0.1 MJ per FE. */
    private static final long CONSUMER_BUFFER = 2 * MJ;
    /** 5 FE/t. */
    private static final long CONSUMER_RATE = MJ / 2;
    /** Creative engine setting used on the transport row. */
    private static final long ENGINE_OUTPUT = 256 * MJ;
    private static final int ROW_PIPES = 65;
    private static final int ROW_CONSUMER_SPACING = 3;
    private static final int WARMUP_TICKS = 400;
    private static final int MEASURE_TICKS = 200;

    private KinesisNetworkDeliveryGameTests() {
    }

    /** One row of the transport plot: 65 diamond kinesis pipes, 20 consumers, one engine at the far end. */
    @GameTest(templateNamespace = BCLib.MODID, template = PipeGameTestSupport.LARGE_EMPTY_TEMPLATE,
        timeoutTicks = 640)
    public static void longKinesisRowKeepsEveryConsumerRunning(GameTestHelper helper) {
        Network network = new Network(helper.getLevel());
        TestPipe[] row = network.row(ROW_PIPES);
        network.engine(row[ROW_PIPES - 1], Direction.EAST);
        run(helper, network, "one 65-pipe row, 1 engine");
    }

    /** The flat transport-row case: 10 such rows joined by a 62-pipe header with 12 engine injection points. */
    @GameTest(templateNamespace = BCLib.MODID, template = PipeGameTestSupport.LARGE_EMPTY_TEMPLATE,
        timeoutTicks = 640)
    public static void headerJoinedKinesisRowsWithManyEnginesKeepEveryConsumerRunning(GameTestHelper helper) {
        Network network = new Network(helper.getLevel());
        TestPipe[] header = network.line(62, Direction.SOUTH, BCTransportPipes.diamondPower);
        for (int r = 0; r < 10; r++) {
            TestPipe[] row = network.row(ROW_PIPES);
            Network.join(header[3 + 6 * r], Direction.EAST, row[0]);
        }
        for (int e = 0; e < 12; e++) {
            network.engine(header[2 + 5 * e], Direction.WEST);
        }
        run(helper, network, "10 rows + header, 12 engines");
    }

    private static void run(GameTestHelper helper, Network network, String label) {
        for (int tick = 1; tick <= WARMUP_TICKS + MEASURE_TICKS; tick++) {
            final boolean measuring = tick > WARMUP_TICKS;
            helper.runAfterDelay(tick, () -> network.tick(measuring));
        }
        helper.runAfterDelay(WARMUP_TICKS + MEASURE_TICKS + 1, () -> {
            long demanded = CONSUMER_RATE * network.consumers.size() * (long) MEASURE_TICKS;
            double delivered = network.consumed / (double) demanded;
            int starved = 0;
            for (Consumer consumer : network.consumers) {
                if (consumer.measuredConsumed < CONSUMER_RATE * (long) MEASURE_TICKS * 95 / 100) {
                    starved++;
                }
            }
            String result = String.format("[kinesis-delivery] %s: %d consumers, %.1f %% of demand delivered, %d starved"
                + " (<95 %%), engines supplied %.1f MJ/t for %.1f MJ/t consumed", label, network.consumers.size(),
                delivered * 100, starved, network.supplied / (double) MJ / MEASURE_TICKS, network.consumed / (double) MJ / MEASURE_TICKS);
            System.out.println(result);
            long stored = 0;
            for (PipeFlowPower flow : network.flows) {
                for (Direction d : Direction.values()) {
                    stored += flow.getSection(d).internalPower + flow.getSection(d).internalNextPower;
                }
            }
            StringBuilder profile = new StringBuilder();
            for (int i = 0; i < Math.min(21, network.consumers.size()); i++) {
                profile.append(String.format(" %.0f", 100.0 * network.consumers.get(i).measuredConsumed / (CONSUMER_RATE * (double) MEASURE_TICKS)));
            }
            System.out.println("[kinesis-delivery-debug] " + label + ": stored in pipes " + stored / MJ + " MJ; row 0 consumers % (west->east):" + profile);
            if (starved > 0) {
                helper.fail(result);
            } else {
                helper.succeed();
            }
        });
    }

    private static final class Network {
        private final Level level;
        private final List<PipeFlowPower> flows = new ArrayList<>();
        private final List<PipeFlowPower> engines = new ArrayList<>();
        private final List<Direction> engineSides = new ArrayList<>();
        private final List<Consumer> consumers = new ArrayList<>();
        private long consumed;
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

        private TestPipe[] line(int length, Direction direction, PipeDefinition definition) {
            TestPipe[] pipes = new TestPipe[length];
            for (int i = 0; i < length; i++) {
                pipes[i] = pipe(definition);
                if (i > 0) {
                    join(pipes[i - 1], direction, pipes[i]);
                }
            }
            return pipes;
        }

        /** A row running east with a consumer above every third pipe (20 on a 65-pipe row, like the plot). */
        private TestPipe[] row(int length) {
            TestPipe[] pipes = line(length, Direction.EAST, BCTransportPipes.diamondPower);
            for (int i = 2; i < length - 1; i += ROW_CONSUMER_SPACING) {
                Consumer consumer = new Consumer();
                consumers.add(consumer);
                pipes[i].connect(Direction.UP, ConnectedType.TILE)
                    .exposeCapability(Direction.UP, MjCapabilities.CAP_RECEIVER, consumer);
            }
            return pipes;
        }

        /**
         * A diamond-wood kinesis pipe (256 MJ/t, so the injection point is never the limit) on {@code side} of
         * {@code target}, with an engine pushing into its far side.
         */
        private void engine(TestPipe target, Direction side) {
            TestPipe wood = pipe(BCTransportPipes.diaWoodPower);
            join(target, side, wood);
            wood.connect(side, ConnectedType.TILE);
            engines.add((PipeFlowPower) wood.getFlow());
            engineSides.add(side);
        }

        private void tick(boolean measuring) {
            for (int i = 0; i < engines.size(); i++) {
                PipeFlowPower.Section input = engines.get(i).getSection(engineSides.get(i));
                long leftover = input.receivePower(ENGINE_OUTPUT, FluidAction.EXECUTE);
                if (measuring) {
                    supplied += ENGINE_OUTPUT - leftover;
                }
            }
            for (PipeFlowPower flow : flows) {
                flow.onTick();
            }
            for (Consumer consumer : consumers) {
                long used = consumer.consume();
                if (measuring) {
                    consumed += used;
                    consumer.measuredConsumed += used;
                }
            }
        }
    }

    /** A buffered machine: asks for its free buffer space, uses a fixed amount per tick while it has it. */
    private static final class Consumer implements IMjReceiver {
        private long stored;
        private long measuredConsumed;

        private long consume() {
            long used = Math.min(stored, CONSUMER_RATE);
            stored -= used;
            return used;
        }

        @Override
        public long getPowerRequested() {
            return Math.max(0, CONSUMER_BUFFER - stored);
        }

        @Override
        public long receivePower(long microJoules, FluidAction action) {
            long accepted = Math.max(0, Math.min(microJoules, getPowerRequested()));
            if (action.execute()) {
                stored += accepted;
            }
            return microJoules - accepted;
        }

        @Override
        public boolean canConnect(@Nonnull IMjConnector other) {
            return true;
        }
    }
}
