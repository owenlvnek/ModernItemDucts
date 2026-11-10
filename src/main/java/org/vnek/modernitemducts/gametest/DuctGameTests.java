package org.vnek.modernitemducts.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.vnek.modernitemducts.ModernItemDucts;
import org.vnek.modernitemducts.Registration;
import org.vnek.modernitemducts.attachment.Attachment;
import org.vnek.modernitemducts.attachment.AttachmentKind;
import org.vnek.modernitemducts.attachment.FilterSettings;
import org.vnek.modernitemducts.attachment.RedstoneMode;
import org.vnek.modernitemducts.attachment.Tier;
import org.vnek.modernitemducts.block.DuctBlockEntity;
import org.vnek.modernitemducts.block.DuctType;

/**
 * Server-side GameTests. Run with {@code ./gradlew runGameTestServer}.
 * All tests use the "platform" template: a 9x4x9 area with a stone floor at y=0.
 */
@GameTestHolder(ModernItemDucts.MODID)
@PrefixGameTestTemplate(false)
public class DuctGameTests {
    private static final String TEMPLATE = "platform";

    // ------------------------------------------------------------------ helpers

    private static DuctBlockEntity duct(GameTestHelper h, int x, int y, int z, DuctType type) {
        BlockPos p = new BlockPos(x, y, z);
        h.setBlock(p, Registration.DUCTS.get(type).get());
        if (!(h.getBlockEntity(p) instanceof DuctBlockEntity be)) throw new IllegalStateException("no duct BE at " + p);
        be.ensureRegistered();
        return be;
    }

    private static DuctBlockEntity ductAt(GameTestHelper h, int x, int y, int z) {
        return (DuctBlockEntity) h.getBlockEntity(new BlockPos(x, y, z));
    }

    private static Container barrel(GameTestHelper h, int x, int y, int z) {
        BlockPos p = new BlockPos(x, y, z);
        h.setBlock(p, Blocks.BARREL);
        return (Container) h.getBlockEntity(p);
    }

    private static Container container(GameTestHelper h, int x, int y, int z) {
        return (Container) h.getBlockEntity(new BlockPos(x, y, z));
    }

    private static int count(Container c, Item item) {
        int n = 0;
        for (int i = 0; i < c.getContainerSize(); i++) if (c.getItem(i).is(item)) n += c.getItem(i).getCount();
        return n;
    }

    private static Attachment servo(DuctBlockEntity duct, Direction face, Tier tier, RedstoneMode mode) {
        Attachment a = new Attachment(AttachmentKind.SERVO, tier);
        a.setRedstoneMode(mode);
        duct.setAttachment(face, a);
        return a;
    }

    private static void check(GameTestHelper h, boolean ok, String msg) {
        if (!ok) h.fail(msg);
    }

    // ------------------------------------------------------------------ tests

    /** A servo pulls from a barrel and delivers through a 4-duct line into a second barrel. */
    @GameTest(template = TEMPLATE, timeoutTicks = 300)
    public static void servoDeliversThroughLine(GameTestHelper h) {
        Container a = barrel(h, 1, 1, 1);
        a.setItem(0, new ItemStack(Items.DIAMOND, 20));
        a.setItem(5, new ItemStack(Items.IRON_INGOT, 10));
        DuctBlockEntity first = duct(h, 2, 1, 1, DuctType.FAST);
        for (int x = 3; x <= 5; x++) duct(h, x, 1, 1, DuctType.FAST);
        Container b = barrel(h, 6, 1, 1);
        servo(first, Direction.WEST, Tier.T4, RedstoneMode.IGNORE);
        h.succeedWhen(() -> {
            check(h, count(b, Items.DIAMOND) == 20, "diamonds in target: " + count(b, Items.DIAMOND));
            check(h, count(b, Items.IRON_INGOT) == 10, "iron in target: " + count(b, Items.IRON_INGOT));
            check(h, a.isEmpty(), "source not empty");
        });
    }

    /** Default redstone mode requires a signal: nothing moves until the duct is powered. */
    @GameTest(template = TEMPLATE, timeoutTicks = 300)
    public static void servoWaitsForRedstoneByDefault(GameTestHelper h) {
        Container a = barrel(h, 1, 1, 1);
        a.setItem(0, new ItemStack(Items.DIAMOND, 8));
        DuctBlockEntity first = duct(h, 2, 1, 1, DuctType.FAST);
        duct(h, 3, 1, 1, DuctType.FAST);
        Container b = barrel(h, 4, 1, 1);
        Attachment s = new Attachment(AttachmentKind.SERVO, Tier.T4); // default mode from config
        first.setAttachment(Direction.WEST, s);
        h.startSequence()
                .thenExecuteAfter(80, () -> {
                    check(h, s.redstoneMode() == RedstoneMode.HIGH, "default mode was " + s.redstoneMode());
                    check(h, count(a, Items.DIAMOND) == 8, "servo moved items without a signal");
                    h.setBlock(new BlockPos(2, 2, 1), Blocks.REDSTONE_BLOCK);
                })
                .thenWaitUntil(() -> check(h, count(b, Items.DIAMOND) == 8, "powered servo did not deliver"))
                .thenSucceed();
    }

    /** Whitelist on the servo: only diamonds leave, dirt stays in the source. */
    @GameTest(template = TEMPLATE, timeoutTicks = 400)
    public static void servoWhitelistFilter(GameTestHelper h) {
        Container a = barrel(h, 1, 1, 1);
        a.setItem(0, new ItemStack(Items.DIRT, 10));
        a.setItem(1, new ItemStack(Items.DIAMOND, 10));
        DuctBlockEntity first = duct(h, 2, 1, 1, DuctType.FAST);
        duct(h, 3, 1, 1, DuctType.FAST);
        Container b = barrel(h, 4, 1, 1);
        Attachment s = servo(first, Direction.WEST, Tier.T3, RedstoneMode.IGNORE);
        s.filter.set(FilterSettings.Flag.EXCLUDE, false);
        s.filter.items().set(0, new ItemStack(Items.DIAMOND));
        h.startSequence()
                .thenWaitUntil(() -> check(h, count(b, Items.DIAMOND) == 10, "diamonds not delivered"))
                .thenIdle(60)
                .thenExecute(() -> {
                    check(h, count(b, Items.DIRT) == 0, "dirt passed a diamond whitelist");
                    check(h, count(a, Items.DIRT) == 10, "dirt left the source");
                })
                .thenSucceed();
    }

    /** Blacklist filter attachment on the nearer target: dirt skips it and goes to the further one. */
    @GameTest(template = TEMPLATE, timeoutTicks = 400)
    public static void filterBlacklistRedirects(GameTestHelper h) {
        Container a = barrel(h, 1, 1, 1);
        a.setItem(0, new ItemStack(Items.DIRT, 12));
        a.setItem(1, new ItemStack(Items.DIAMOND, 12));
        DuctBlockEntity first = duct(h, 2, 1, 1, DuctType.FAST);
        DuctBlockEntity mid = duct(h, 3, 1, 1, DuctType.FAST);
        duct(h, 4, 1, 1, DuctType.FAST);
        Container near = barrel(h, 3, 1, 2);
        Container far = barrel(h, 5, 1, 1);
        servo(first, Direction.WEST, Tier.T4, RedstoneMode.IGNORE);
        Attachment f = new Attachment(AttachmentKind.FILTER, Tier.T1);
        f.filter.items().set(0, new ItemStack(Items.DIRT)); // exclude-list is the default
        mid.setAttachment(Direction.SOUTH, f);
        h.succeedWhen(() -> {
            check(h, count(near, Items.DIAMOND) == 12, "diamonds in near: " + count(near, Items.DIAMOND));
            check(h, count(near, Items.DIRT) == 0, "dirt got through the blacklist");
            check(h, count(far, Items.DIRT) == 12, "dirt in far: " + count(far, Items.DIRT));
            check(h, count(far, Items.DIAMOND) == 0, "diamonds went to the far target");
        });
    }

    /** A full destination: nothing is extracted, nothing is dropped. */
    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    public static void fullDestinationLeavesSource(GameTestHelper h) {
        Container a = barrel(h, 1, 1, 1);
        a.setItem(0, new ItemStack(Items.DIAMOND, 16));
        DuctBlockEntity first = duct(h, 2, 1, 1, DuctType.FAST);
        DuctBlockEntity second = duct(h, 3, 1, 1, DuctType.FAST);
        Container b = barrel(h, 4, 1, 1);
        for (int i = 0; i < b.getContainerSize(); i++) b.setItem(i, new ItemStack(Items.COBBLESTONE, 64));
        servo(first, Direction.WEST, Tier.T4, RedstoneMode.IGNORE);
        h.runAtTickTime(120, () -> {
            check(h, count(a, Items.DIAMOND) == 16, "items left the source: " + count(a, Items.DIAMOND));
            check(h, count(b, Items.COBBLESTONE) == 27 * 64, "target changed");
            check(h, first.packetsView().isEmpty() && second.packetsView().isEmpty(), "packets in flight");
            h.assertItemEntityNotPresent(Items.DIAMOND, new BlockPos(2, 1, 1), 6);
            h.succeed();
        });
    }

    /**
     * Items in flight towards the nearer target re-route to the other branch when a duct on
     * their path is removed.
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 900)
    public static void reroutesAfterDuctRemoved(GameTestHelper h) {
        Container a = barrel(h, 1, 1, 1);
        a.setItem(0, new ItemStack(Items.DIAMOND, 64));
        DuctBlockEntity first = duct(h, 2, 1, 1, DuctType.STANDARD);
        for (int x = 3; x <= 5; x++) duct(h, x, 1, 1, DuctType.STANDARD);
        Container nearTarget = barrel(h, 6, 1, 1);
        for (int z = 2; z <= 4; z++) duct(h, 3, 1, z, DuctType.STANDARD);
        Container farTarget = barrel(h, 3, 1, 5);
        servo(first, Direction.WEST, Tier.T3, RedstoneMode.IGNORE);
        h.startSequence()
                .thenWaitUntil(() -> check(h, !ductAt(h, 4, 1, 1).packetsView().isEmpty(), "no packet reached the cut point yet"))
                .thenExecute(() -> h.setBlock(new BlockPos(5, 1, 1), Blocks.AIR))
                .thenWaitUntil(() -> {
                    check(h, count(farTarget, Items.DIAMOND) == 64, "far target has " + count(farTarget, Items.DIAMOND));
                    check(h, a.isEmpty(), "source not empty");
                })
                .thenExecute(() -> {
                    check(h, count(nearTarget, Items.DIAMOND) == 0, "near target got items after the cut");
                    h.assertItemEntityNotPresent(Items.DIAMOND, new BlockPos(4, 1, 2), 8);
                })
                .thenSucceed();
    }

    /** With every destination gone, homeless items travel back into the servo's backlog. */
    @GameTest(template = TEMPLATE, timeoutTicks = 400)
    public static void homelessItemsReturnToBacklog(GameTestHelper h) {
        Container a = barrel(h, 1, 1, 1);
        a.setItem(0, new ItemStack(Items.DIAMOND, 8));
        DuctBlockEntity first = duct(h, 2, 1, 1, DuctType.FAST);
        duct(h, 3, 1, 1, DuctType.FAST);
        duct(h, 4, 1, 1, DuctType.FAST);
        barrel(h, 5, 1, 1);
        Attachment s = servo(first, Direction.WEST, Tier.T1, RedstoneMode.IGNORE);
        h.startSequence()
                .thenWaitUntil(() -> check(h, !ductAt(h, 4, 1, 1).packetsView().isEmpty(), "no packet near the target yet"))
                .thenExecute(() -> h.setBlock(new BlockPos(5, 1, 1), Blocks.AIR))
                .thenWaitUntil(() -> check(h, s.hasBacklog(), "servo has no backlog"))
                .thenExecute(() -> {
                    check(h, s.backlog().get(0).count == 8, "backlog holds " + s.backlog().get(0).count);
                    h.assertItemEntityNotPresent(Items.DIAMOND, new BlockPos(3, 1, 1), 6);
                })
                .thenSucceed();
    }

    /** A retriever pulls matching items from another inventory on the network into its own. */
    @GameTest(template = TEMPLATE, timeoutTicks = 300)
    public static void retrieverPullsItems(GameTestHelper h) {
        Container store = barrel(h, 1, 1, 1);
        store.setItem(0, new ItemStack(Items.DIAMOND, 10));
        store.setItem(1, new ItemStack(Items.DIRT, 10));
        duct(h, 2, 1, 1, DuctType.FAST);
        duct(h, 3, 1, 1, DuctType.FAST);
        DuctBlockEntity last = duct(h, 4, 1, 1, DuctType.FAST);
        Container mine = barrel(h, 5, 1, 1);
        Attachment r = new Attachment(AttachmentKind.RETRIEVER, Tier.T3);
        r.setRedstoneMode(RedstoneMode.IGNORE);
        r.filter.set(FilterSettings.Flag.EXCLUDE, false);
        r.filter.items().set(0, new ItemStack(Items.DIAMOND));
        last.setAttachment(Direction.EAST, r);
        h.startSequence()
                .thenWaitUntil(() -> check(h, count(mine, Items.DIAMOND) == 10, "retrieved " + count(mine, Items.DIAMOND)))
                .thenIdle(40)
                .thenExecute(() -> check(h, count(store, Items.DIRT) == 10, "retriever took unfiltered items"))
                .thenSucceed();
    }
}
