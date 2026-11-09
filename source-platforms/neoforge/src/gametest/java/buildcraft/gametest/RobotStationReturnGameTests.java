package buildcraft.gametest;

import java.util.Collections;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import buildcraft.lib.BCLib;
import buildcraft.lib.internal.core.BlockIndex;
import buildcraft.lib.internal.statement.StatementSlot;
import buildcraft.robotics.BCRoboticsBoards;
import buildcraft.robotics.ai.AIRobotGoAndLinkToDock;
import buildcraft.robotics.ai.AIRobotGotoStation;
import buildcraft.robotics.ai.AIRobotStraightMoveTo;
import buildcraft.robotics.entity.EntityRobot;
import buildcraft.robotics.internal.legacy.robots.AIRobot;
import buildcraft.robotics.internal.legacy.robots.DockingStation;
import buildcraft.robotics.internal.legacy.robots.IRobotRegistry;

/**
 * A robot must find its way back to a docking station along any passable route, not only along routes that stay
 * inside a sphere around its start or fit in one tick's node budget (BCCE-team/BuildCraft 57562363). The AIs are
 * updated by hand and the robot's velocity is applied manually, so the result does not depend on server TPS.
 */
@GameTestHolder(BCLib.MODID)
@PrefixGameTestTemplate(false)
public final class RobotStationReturnGameTests {
    private static final String TEMPLATE = "empty7x3x7";

    private RobotStationReturnGameTests() {
    }

    /**
     * The robot and its station's approach cell are two blocks apart, but a 39-block-high wall separates them inside
     * solid stone: the only route goes up one shaft, over the wall and down the other (77 cells).
     */
    @GameTest(templateNamespace = BCLib.MODID, template = TEMPLATE, timeoutTicks = 40)
    public static void robotReturnsToStationAlongLongDetour(GameTestHelper helper) {
        int bottom = 3, top = 44, bridge = 42;
        for (int y = bottom; y <= top; y++) {
            for (int x = 1; x <= 5; x++) {
                for (int z = 1; z <= 3; z++) {
                    boolean shaft = z == 2 && y > bottom && y <= bridge && (x == 2 || x == 4);
                    boolean over = z == 2 && y == bridge && x == 3;
                    helper.setBlock(new BlockPos(x, y, z),
                        shaft || over ? Blocks.AIR.defaultBlockState() : Blocks.STONE.defaultBlockState());
                }
            }
        }
        BlockPos start = helper.absolutePos(new BlockPos(2, bottom + 1, 2));
        BlockPos dock = helper.absolutePos(new BlockPos(4, bottom, 2));
        BlockPos approach = dock.above();

        EntityRobot robot = new EntityRobot(helper.getLevel(), BCRoboticsBoards.EMPTY);
        robot.setPos(start.getX() + 0.5D, start.getY() + 0.5D, start.getZ() + 0.5D);
        IRobotRegistry registry = robot.getRegistry();
        require(helper, registry != null, "robot registry unavailable in GameTest world");
        ProbeStation station = new ProbeStation(new BlockIndex(dock), Direction.UP);
        station.setLevel(helper.getLevel());
        Root root = new Root(robot);
        try {
            registry.registerRobot(robot);
            registry.registerStation(station);
            AIRobotGotoStation goTo = new AIRobotGotoStation(robot, station);
            root.startDelegateAI(goTo);
            drive(robot, root, goTo, 4_000);
            require(helper, !root.ended, "robot gave up on the only route back to its station (a 77-cell detour)");
            require(helper, goTo.getDelegateAI() instanceof AIRobotStraightMoveTo,
                "robot never reached the station's approach cell");
            require(helper, robot.blockPosition().equals(approach),
                "robot stopped at " + robot.blockPosition() + " instead of the approach cell " + approach);
        } finally {
            root.abort();
            station.forceRelease();
            registry.removeStation(station);
            registry.killRobot(robot);
            for (int y = bottom; y <= top; y++) {
                for (int x = 1; x <= 5; x++) {
                    for (int z = 1; z <= 3; z++) {
                        helper.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState());
                    }
                }
            }
        }
        helper.succeed();
    }

    /**
     * The "go to station" gate action approaches from one block outside the station face, like the charging/unload
     * return. A solid block two blocks out (here: a wall) must not make the route fail.
     */
    @GameTest(templateNamespace = BCLib.MODID, template = TEMPLATE, timeoutTicks = 40)
    public static void linkToDockApproachesFromTheAdjacentCell(GameTestHelper helper) {
        BlockState stone = Blocks.STONE.defaultBlockState();
        BlockPos dockRel = new BlockPos(3, 1, 3);
        helper.setBlock(dockRel, stone);
        helper.setBlock(new BlockPos(3, 1, 1), stone);
        BlockPos dock = helper.absolutePos(dockRel);
        BlockPos approach = dock.relative(Direction.NORTH);
        BlockPos start = helper.absolutePos(new BlockPos(5, 1, 2));

        EntityRobot robot = new EntityRobot(helper.getLevel(), BCRoboticsBoards.EMPTY);
        robot.setPos(start.getX() + 0.5D, start.getY() + 0.5D, start.getZ() + 0.5D);
        IRobotRegistry registry = robot.getRegistry();
        require(helper, registry != null, "robot registry unavailable in GameTest world");
        ProbeStation station = new ProbeStation(new BlockIndex(dock), Direction.NORTH);
        station.setLevel(helper.getLevel());
        Root root = new Root(robot);
        try {
            registry.registerRobot(robot);
            registry.registerStation(station);
            AIRobotGoAndLinkToDock link = new AIRobotGoAndLinkToDock(robot, station);
            root.startDelegateAI(link);
            drive(robot, root, link, 400);
            require(helper, !root.ended, "go-to-station gate action gave up although the station face is reachable");
            require(helper, link.getDelegateAI() instanceof AIRobotStraightMoveTo,
                "robot never reached the station's approach cell");
            require(helper, robot.blockPosition().equals(approach),
                "robot stopped at " + robot.blockPosition() + " instead of the approach cell " + approach);
            require(helper, robot.getLinkedStation() == station, "the requested station did not become the robot's home");
        } finally {
            root.abort();
            station.forceRelease();
            registry.removeStation(station);
            registry.killRobot(robot);
        }
        helper.succeed();
    }

    /** Updates the active AI and moves the robot by its velocity until the final straight docking move starts. */
    private static void drive(EntityRobot robot, Root root, AIRobot stationAi, int maxSteps) {
        for (int step = 0; step < maxSteps && !root.ended
            && !(stationAi.getDelegateAI() instanceof AIRobotStraightMoveTo); step++) {
            root.getActiveAI().update();
            Vec3 motion = robot.getDeltaMovement();
            robot.setPos(robot.getX() + motion.x, robot.getY() + motion.y, robot.getZ() + motion.z);
        }
    }

    private static void require(GameTestHelper helper, boolean ok, String message) {
        if (!ok) helper.fail(message);
    }

    /** Parent AI that records when the station AI under test ends. */
    private static final class Root extends AIRobot {
        private boolean ended;

        private Root(EntityRobot robot) {
            super(robot);
        }

        @Override
        public void update() {
        }

        @Override
        public void delegateAIEnded(AIRobot ai) {
            ended = true;
        }
    }

    private static final class ProbeStation extends DockingStation {
        private ProbeStation(BlockIndex pos, Direction side) {
            super(pos, side);
        }

        @Override
        public Iterable<StatementSlot> getActiveActions() {
            return Collections.emptyList();
        }
    }
}
