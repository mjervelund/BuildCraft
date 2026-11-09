package buildcraft.gametest;

//? if >=26.3 {
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import buildcraft.api.v2.energy.MjAmount;
import buildcraft.lib.BCLib;
import buildcraft.lib.misc.ItemStackUtil;
import buildcraft.transport.BCTransportPipes;
import buildcraft.transport.internal.pipe.FluidAction;
import buildcraft.transport.pipe.behaviour.PipeBehaviourObsidian;
import buildcraft.transport.pipe.flow.PipeFlowItems;
import buildcraft.transport.tile.TilePipeHolder;

/**
 * Obsidian pipe item conservation (BCCE-team/BuildCraft#1: "items vanish when an obsidian pipe pulls from a minecart",
 * "dropped items are not removed and keep being sucked in"). An engine-like MJ feed powers an obsidian pipe that faces
 * WEST at a minecart or a dropped stack; the pipe leads through a cobblestone pipe into a chest. Every tick the marked
 * cargo is counted per item in the cart, both pipes, the chest and loose item entities: any change is a loss or a dupe.
 */
@GameTestHolder(BCLib.MODID)
@PrefixGameTestTemplate(false)
public final class ObsidianPipeConservationGameTests {
    private static final String MARKER_KEY = "buildcraft_obsidian_test";
    private static final BlockPos SOURCE = new BlockPos(1, 1, 3);
    private static final BlockPos OBSIDIAN = new BlockPos(2, 1, 3);
    private static final BlockPos COBBLE = new BlockPos(3, 1, 3);
    private static final BlockPos CHEST = new BlockPos(4, 1, 3);
    /** Engine-like feed per tick: enough for roughly ten items per tick at this distance. */
    private static final long POWER_PER_TICK = 10 * MjAmount.MICRO_MJ_PER_MJ;
    private static final int POWERED_TICKS = 240;
    private static final int CHECK_TICK = 420;

    private ObsidianPipeConservationGameTests() {
    }

    @GameTest(templateNamespace = BCLib.MODID, template = PipeGameTestSupport.LARGE_EMPTY_TEMPLATE,
        timeoutTicks = 460)
    public static void obsidianPipeEmptiesChestMinecartWithoutLossOrDupe(GameTestHelper helper) {
        Fixture fixture = new Fixture(helper, "chest_cart", false);
        Container cart = fixture.spawnCart(EntityTypes.CHEST_MINECART);
        fillMixed(cart, fixture.marker);
        fixture.run(() -> countContainer(cart, fixture.marker), true, () -> require(helper, isEmpty(cart, fixture.marker),
            "chest minecart still holds cargo after the obsidian pipe ran"));
    }

    @GameTest(templateNamespace = BCLib.MODID, template = PipeGameTestSupport.LARGE_EMPTY_TEMPLATE,
        timeoutTicks = 460)
    public static void obsidianPipeEmptiesHopperMinecartWithoutLossOrDupe(GameTestHelper helper) {
        Fixture fixture = new Fixture(helper, "hopper_cart", false);
        Container cart = fixture.spawnCart(EntityTypes.HOPPER_MINECART);
        cart.setItem(0, marked(new ItemStack(Items.APPLE, 64), fixture.marker));
        cart.setItem(1, marked(new ItemStack(Items.IRON_INGOT, 37), fixture.marker));
        cart.setItem(2, marked(new ItemStack(Items.DIAMOND_SWORD), fixture.marker));
        cart.setItem(4, marked(new ItemStack(Items.ENDER_PEARL, 16), fixture.marker));
        fixture.run(() -> countContainer(cart, fixture.marker), true, () -> require(helper, isEmpty(cart, fixture.marker),
            "hopper minecart still holds cargo after the obsidian pipe ran"));
    }

    @GameTest(templateNamespace = BCLib.MODID, template = PipeGameTestSupport.LARGE_EMPTY_TEMPLATE,
        timeoutTicks = 460)
    public static void obsidianPipeSucksDroppedStacksExactlyOnce(GameTestHelper helper) {
        Fixture fixture = new Fixture(helper, "dropped", false);
        Vec3 at = helper.absoluteVec(new Vec3(SOURCE.getX() + 0.5, SOURCE.getY() + 0.1, SOURCE.getZ() + 0.5));
        spawnDrop(helper, at, marked(new ItemStack(Items.APPLE, 64), fixture.marker));
        spawnDrop(helper, at.add(0.0, 0.0, 0.3), marked(new ItemStack(Items.IRON_INGOT, 45), fixture.marker));
        spawnDrop(helper, at.add(0.0, 0.0, -0.3), marked(new ItemStack(Items.DIAMOND_SWORD), fixture.marker));
        fixture.run(Map::of, true, () -> { });
    }

    @GameTest(templateNamespace = BCLib.MODID, template = PipeGameTestSupport.LARGE_EMPTY_TEMPLATE,
        timeoutTicks = 460)
    public static void obsidianPipeIntoFullChestNeverDeletesMinecartCargo(GameTestHelper helper) {
        Fixture fixture = new Fixture(helper, "full_chest", true);
        Container cart = fixture.spawnCart(EntityTypes.CHEST_MINECART);
        fillMixed(cart, fixture.marker);
        // The destination accepts nothing; the cargo has to stay somewhere (cart, pipes or dropped and re-sucked).
        int before = countContainer(cart, fixture.marker).values().stream().mapToInt(Integer::intValue).sum();
        fixture.run(() -> countContainer(cart, fixture.marker), false, () -> {
            int after = countContainer(cart, fixture.marker).values().stream().mapToInt(Integer::intValue).sum();
            require(helper, after < before, "obsidian pipe never pulled from the cart (" + after + " of " + before
                + " left), so the blocked-destination path was not exercised");
            require(helper, countContainer(fixture.chest, fixture.marker).isEmpty(),
                "full chest accepted marked cargo");
        });
    }

    private static final class Fixture {
        final GameTestHelper helper;
        final String marker;
        final ChestBlockEntity chest;
        final TilePipeHolder obsidian;
        final TilePipeHolder cobble;
        Map<Item, Integer> expected;

        Fixture(GameTestHelper helper, String marker, boolean fullDestination) {
            this.helper = helper;
            this.marker = marker;
            for (int x = 0; x <= 5; x++) {
                for (int z = 1; z <= 5; z++) {
                    helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE.defaultBlockState());
                }
            }
            helper.setBlock(CHEST, Blocks.CHEST.defaultBlockState());
            BlockEntity be = GameTestCompat.getBlockEntity(helper, CHEST);
            if (!(be instanceof ChestBlockEntity c)) {
                helper.fail("chest block did not create ChestBlockEntity");
                throw new IllegalStateException("missing chest");
            }
            this.chest = c;
            if (fullDestination) {
                for (int slot = 0; slot < chest.getContainerSize(); slot++) {
                    chest.setItem(slot, new ItemStack(Items.COBBLESTONE, 64));
                }
            }
            this.obsidian = PipeGameTestSupport.placePipe(helper, OBSIDIAN, BCTransportPipes.obsidianItem);
            this.cobble = PipeGameTestSupport.placePipe(helper, COBBLE, BCTransportPipes.cobbleItem);
        }

        Container spawnCart(EntityType<? extends net.minecraft.world.entity.Entity> type) {
            helper.setBlock(SOURCE, Blocks.RAIL.defaultBlockState());
            net.minecraft.world.entity.Entity cart = helper.spawn(type,
                new Vec3(SOURCE.getX() + 0.5, SOURCE.getY() + 0.0625, SOURCE.getZ() + 0.5));
            if (!(cart instanceof Container container)) {
                helper.fail("minecart " + type + " is not a Container");
                throw new IllegalStateException("not a container");
            }
            return container;
        }

        void run(Supplier<Map<Item, Integer>> source, boolean expectDelivered, Runnable finalChecks) {
            helper.runAfterDelay(3, () -> {
                require(helper, obsidian.getPipe().isConnected(Direction.EAST), "obsidian pipe did not connect east");
                require(helper, !obsidian.getPipe().isConnected(Direction.WEST), "obsidian pipe face WEST is not open");
                require(helper, cobble.getPipe().isConnected(Direction.EAST), "cobblestone pipe did not connect to chest");
                expected = total(source.get());
                require(helper, !expected.isEmpty(), "fixture holds no marked cargo");
            });
            for (int tick = 4; tick <= CHECK_TICK; tick++) {
                final int now = tick;
                helper.runAfterDelay(tick, () -> {
                    if (now <= POWERED_TICKS) {
                        PipeBehaviourObsidian behaviour = (PipeBehaviourObsidian) obsidian.getPipe().getBehaviour();
                        long left = behaviour.receivePower(POWER_PER_TICK, FluidAction.EXECUTE);
                        require(helper, left >= 0 && left <= POWER_PER_TICK,
                            "obsidian pipe returned out-of-range leftover power " + left);
                    }
                    Map<Item, Integer> actual = total(source.get());
                    require(helper, actual.equals(expected), marker + ": cargo not conserved at tick " + now
                        + " (expected " + expected + ", found " + actual + " = source " + source.get()
                        + " + pipes " + countPipe(obsidian) + "/" + countPipe(cobble)
                        + " + chest " + countContainer(chest, marker) + " + loose " + countLoose() + ")");
                    if (now == CHECK_TICK) {
                        finalChecks.run();
                        if (expectDelivered) {
                            require(helper, countContainer(chest, marker).equals(expected),
                                marker + ": chest received " + countContainer(chest, marker) + " instead of " + expected);
                        }
                        helper.succeed();
                    }
                });
            }
        }

        Map<Item, Integer> total(Map<Item, Integer> sourceCount) {
            Map<Item, Integer> sum = new HashMap<>(sourceCount);
            merge(sum, countPipe(obsidian));
            merge(sum, countPipe(cobble));
            merge(sum, countContainer(chest, marker));
            merge(sum, countLoose());
            return sum;
        }

        Map<Item, Integer> countPipe(TilePipeHolder holder) {
            NonNullList<ItemStack> contents = NonNullList.create();
            ((PipeFlowItems) holder.getPipe().getFlow()).addDrops(contents, 0);
            Map<Item, Integer> counts = new HashMap<>();
            for (ItemStack stack : contents) {
                add(counts, stack, marker);
            }
            return counts;
        }

        Map<Item, Integer> countLoose() {
            BlockPos min = helper.absolutePos(BlockPos.ZERO);
            BlockPos max = helper.absolutePos(new BlockPos(6, 2, 6));
            AABB bounds = new AABB(
                Math.min(min.getX(), max.getX()) - 2, Math.min(min.getY(), max.getY()) - 2,
                Math.min(min.getZ(), max.getZ()) - 2, Math.max(min.getX(), max.getX()) + 3,
                Math.max(min.getY(), max.getY()) + 4, Math.max(min.getZ(), max.getZ()) + 3);
            Map<Item, Integer> counts = new HashMap<>();
            for (ItemEntity entity : helper.getLevel().getEntitiesOfClass(ItemEntity.class, bounds)) {
                if (!entity.isRemoved()) {
                    add(counts, entity.getItem(), marker);
                }
            }
            return counts;
        }
    }

    private static void fillMixed(Container cart, String marker) {
        cart.setItem(0, marked(new ItemStack(Items.APPLE, 64), marker));
        cart.setItem(1, marked(new ItemStack(Items.APPLE, 13), marker));
        cart.setItem(3, marked(new ItemStack(Items.IRON_INGOT, 64), marker));
        cart.setItem(5, marked(new ItemStack(Items.DIAMOND_SWORD), marker));
        cart.setItem(6, marked(new ItemStack(Items.DIAMOND_SWORD), marker));
        cart.setItem(9, marked(new ItemStack(Items.ENDER_PEARL, 16), marker));
        cart.setItem(13, marked(new ItemStack(Items.COBBLESTONE, 64), marker));
        ItemStack named = marked(new ItemStack(Items.GOLD_INGOT, 21), marker);
        named.set(DataComponents.CUSTOM_NAME, Component.literal("obsidian cargo"));
        cart.setItem(17, named);
        cart.setItem(26, marked(new ItemStack(Items.REDSTONE, 50), marker));
    }

    private static void spawnDrop(GameTestHelper helper, Vec3 at, ItemStack stack) {
        ItemEntity entity = new ItemEntity(helper.getLevel(), at.x, at.y, at.z, stack, 0.0, 0.0, 0.0);
        helper.getLevel().addFreshEntity(entity);
    }

    private static ItemStack marked(ItemStack stack, String marker) {
        net.minecraft.nbt.CompoundTag tag = ItemStackUtil.getCustomData(stack);
        tag.putString(MARKER_KEY, marker);
        ItemStackUtil.setCustomData(stack, tag);
        return stack;
    }

    private static boolean isMarked(ItemStack stack, String marker) {
        return !stack.isEmpty()
            && marker.equals(GameTestCompat.readString(ItemStackUtil.getCustomData(stack), MARKER_KEY));
    }

    private static void add(Map<Item, Integer> counts, ItemStack stack, String marker) {
        if (isMarked(stack, marker)) {
            counts.merge(stack.getItem(), stack.getCount(), Integer::sum);
        }
    }

    private static void merge(Map<Item, Integer> into, Map<Item, Integer> from) {
        from.forEach((item, count) -> into.merge(item, count, Integer::sum));
    }

    private static Map<Item, Integer> countContainer(Container container, String marker) {
        Map<Item, Integer> counts = new HashMap<>();
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            add(counts, container.getItem(slot), marker);
        }
        return counts;
    }

    private static boolean isEmpty(Container container, String marker) {
        return countContainer(container, marker).isEmpty();
    }

    private static void require(GameTestHelper helper, boolean condition, String message) {
        if (!condition) {
            helper.fail(message);
        }
    }
}
//?}
