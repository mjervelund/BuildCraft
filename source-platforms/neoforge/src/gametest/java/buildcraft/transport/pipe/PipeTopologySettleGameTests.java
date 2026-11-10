package buildcraft.transport.pipe;

import java.lang.reflect.Field;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.DyeColor;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import buildcraft.gametest.PipeGameTestSupport;
import buildcraft.lib.BCLib;
import buildcraft.transport.BCTransportPipes;
import buildcraft.transport.internal.pipe.IPipeHolder.PipeMessageReceiver;
import buildcraft.transport.tile.TilePipeHolder;

/**
 * Pipe topology is rescanned only when something around the pipe really changed. A neighbouring pipe saving its state
 * (BlockEntity.setChanged, which NeoForge reports to the horizontal neighbours through onNeighborChange) is not such a
 * change; placing a pipe or painting one still is.
 */
@GameTestHolder(BCLib.MODID)
@PrefixGameTestTemplate(false)
public final class PipeTopologySettleGameTests {
    private static final Field UPDATE_MARKED;

    static {
        try {
            UPDATE_MARKED = Pipe.class.getDeclaredField("updateMarked");
            UPDATE_MARKED.setAccessible(true);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private PipeTopologySettleGameTests() {
    }

    /**
     * A settled row of kinesis pipes stays settled, even while one of them saves every tick (what a pipe carrying
     * power does): no pipe is left marked for a topology rescan.
     */
    @GameTest(templateNamespace = BCLib.MODID, template = PipeGameTestSupport.LARGE_EMPTY_TEMPLATE, timeoutTicks = 60)
    public static void settledPipeRowIsNotRescannedWhenANeighbourSaves(GameTestHelper helper) {
        TilePipeHolder[] row = new TilePipeHolder[7];
        for (int x = 0; x < row.length; x++) {
            row[x] = PipeGameTestSupport.placePipe(helper, new BlockPos(x, 1, 3), BCTransportPipes.diamondPower);
        }
        int[] marked = { 0, 0 };
        for (int tick = 10; tick < 40; tick++) {
            helper.runAfterDelay(tick, () -> {
                for (TilePipeHolder holder : row) {
                    if (marked(holder)) {
                        marked[0]++;
                    }
                }
                row[3].setChanged();
                if (marked(row[2]) || marked(row[4])) {
                    marked[1]++;
                }
            });
        }
        helper.runAfterDelay(41, () -> {
            for (int x = 0; x + 1 < row.length; x++) {
                require(helper, row[x].getPipe().isConnected(Direction.EAST) && row[x + 1].getPipe().isConnected(Direction.WEST),
                    "pipes " + x + " and " + (x + 1) + " are not connected");
            }
            System.out.println("[pipe-topology] settled row: " + marked[0] + " pipe-ticks left marked for a rescan, "
                + marked[1] + " of 30 neighbour saves marked a neighbour");
            require(helper, marked[0] == 0, marked[0] + " pipe-ticks of a settled row were marked for a topology rescan");
            require(helper, marked[1] == 0, marked[1] + " of 30 saves of a pipe marked its neighbours for a rescan");
            helper.succeed();
        });
    }

    /** A pipe placed next to a settled pipe still connects on both sides. */
    @GameTest(templateNamespace = BCLib.MODID, template = PipeGameTestSupport.LARGE_EMPTY_TEMPLATE, timeoutTicks = 40)
    public static void pipePlacedNextToSettledPipeConnects(GameTestHelper helper) {
        TilePipeHolder first = PipeGameTestSupport.placePipe(helper, new BlockPos(2, 1, 3), BCTransportPipes.diamondPower);
        TilePipeHolder[] second = new TilePipeHolder[1];
        TilePipeHolder[] above = new TilePipeHolder[1];
        helper.runAfterDelay(10, () -> {
            second[0] = PipeGameTestSupport.placePipe(helper, new BlockPos(3, 1, 3), BCTransportPipes.diamondPower);
            above[0] = PipeGameTestSupport.placePipe(helper, new BlockPos(2, 2, 3), BCTransportPipes.diamondPower);
        });
        helper.runAfterDelay(15, () -> {
            require(helper, first.getPipe().isConnected(Direction.EAST) && second[0].getPipe().isConnected(Direction.WEST),
                "pipe placed east of a settled pipe did not connect");
            require(helper, first.getPipe().isConnected(Direction.UP) && above[0].getPipe().isConnected(Direction.DOWN),
                "pipe placed above a settled pipe did not connect");
            helper.succeed();
        });
    }

    /** Painting a settled pipe disconnects it from differently coloured neighbours on both sides, also vertically. */
    @GameTest(templateNamespace = BCLib.MODID, template = PipeGameTestSupport.LARGE_EMPTY_TEMPLATE, timeoutTicks = 40)
    public static void paintingSettledPipeDisconnectsDifferentlyColouredNeighbours(GameTestHelper helper) {
        TilePipeHolder centre = PipeGameTestSupport.placePipe(helper, new BlockPos(3, 1, 3), BCTransportPipes.stoneItem);
        TilePipeHolder east = PipeGameTestSupport.placePipe(helper, new BlockPos(4, 1, 3), BCTransportPipes.stoneItem);
        TilePipeHolder above = PipeGameTestSupport.placePipe(helper, new BlockPos(3, 2, 3), BCTransportPipes.stoneItem);
        east.getPipe().setColour(DyeColor.LIME);
        above.getPipe().setColour(DyeColor.LIME);
        helper.runAfterDelay(10, () -> {
            require(helper, centre.getPipe().isConnected(Direction.EAST) && centre.getPipe().isConnected(Direction.UP),
                "uncoloured pipe did not connect to its coloured neighbours");
            // What painting with a paintbrush does (BlockPipeHolder).
            centre.getPipe().setColour(DyeColor.PINK);
            centre.scheduleNetworkUpdate(PipeMessageReceiver.BEHAVIOUR);
        });
        helper.runAfterDelay(15, () -> {
            require(helper, !centre.getPipe().isConnected(Direction.EAST) && !east.getPipe().isConnected(Direction.WEST),
                "painted pipe stayed connected to its differently coloured east neighbour");
            require(helper, !centre.getPipe().isConnected(Direction.UP) && !above.getPipe().isConnected(Direction.DOWN),
                "painted pipe stayed connected to its differently coloured upper neighbour");
            helper.succeed();
        });
    }

    private static boolean marked(TilePipeHolder holder) {
        try {
            return UPDATE_MARKED.getBoolean(holder.getPipe());
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void require(GameTestHelper helper, boolean condition, String message) {
        if (!condition) {
            helper.fail(message);
        }
    }
}
