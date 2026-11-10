package org.vnek.modernitemducts;

import net.neoforged.neoforge.common.ModConfigSpec;
import org.vnek.modernitemducts.attachment.RedstoneMode;

/** Common (startup) configuration. */
public final class Config {
    public enum HomelessBehavior { DROP, VOID }

    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.EnumValue<RedstoneMode> DEFAULT_REDSTONE_MODE;
    public static final ModConfigSpec.EnumValue<HomelessBehavior> HOMELESS_BEHAVIOR;
    public static final ModConfigSpec.IntValue ROUTE_BUDGET_MICROS;
    public static final ModConfigSpec.BooleanValue PREVENT_SOURCE_LOOP;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        DEFAULT_REDSTONE_MODE = b.comment(
                        "Redstone mode given to newly placed servos and retrievers.",
                        "HIGH (default, same as the classic behaviour) = only work while powered,",
                        "LOW = only work while unpowered, IGNORE = always work.")
                .defineEnum("defaultRedstoneMode", RedstoneMode.HIGH);
        HOMELESS_BEHAVIOR = b.comment(
                        "What happens to items that can find no destination and no servo backlog:",
                        "DROP = spill them as item entities at the duct, VOID = delete them.")
                .defineEnum("homelessItems", HomelessBehavior.DROP);
        ROUTE_BUDGET_MICROS = b.comment(
                        "Time budget (microseconds per server tick, across all networks) for background route",
                        "table computation. At least one table is always computed per tick.")
                .defineInRange("routeBudgetMicros", 100, 0, 50_000);
        PREVENT_SOURCE_LOOP = b.comment(
                        "If true, a servo never sends items back into the inventory it is pulling from,",
                        "even if that inventory also touches the network through another face.")
                .define("preventSourceLoop", true);
        SPEC = b.build();
    }

    private Config() {}

    /** Safe accessor usable before the config file has loaded (e.g. very early tests). */
    public static RedstoneMode defaultRedstone() {
        try { return DEFAULT_REDSTONE_MODE.get(); } catch (IllegalStateException e) { return RedstoneMode.HIGH; }
    }

    public static HomelessBehavior homeless() {
        try { return HOMELESS_BEHAVIOR.get(); } catch (IllegalStateException e) { return HomelessBehavior.DROP; }
    }

    public static long routeBudgetNanos() {
        try { return ROUTE_BUDGET_MICROS.get() * 1000L; } catch (IllegalStateException e) { return 100_000L; }
    }

    public static boolean preventSourceLoop() {
        try { return PREVENT_SOURCE_LOOP.get(); } catch (IllegalStateException e) { return true; }
    }
}
