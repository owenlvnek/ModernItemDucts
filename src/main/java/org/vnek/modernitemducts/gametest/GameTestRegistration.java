package org.vnek.modernitemducts.gametest;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.resources.ResourceKey;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.vnek.modernitemducts.ModernItemDucts;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Since 1.21.5 GameTests are registry entries: a test function (code) plus a test instance
 * (structure, time limit, environment). This registers both for every test in {@link DuctGameTests}.
 */
public final class GameTestRegistration {
    private record Test(Consumer<GameTestHelper> function, int maxTicks) {}

    private static final Map<String, Test> TESTS = new LinkedHashMap<>();

    static {
        TESTS.put("servo_delivers_through_line", new Test(DuctGameTests::servoDeliversThroughLine, 300));
        TESTS.put("servo_waits_for_redstone_by_default", new Test(DuctGameTests::servoWaitsForRedstoneByDefault, 300));
        TESTS.put("servo_whitelist_filter", new Test(DuctGameTests::servoWhitelistFilter, 400));
        TESTS.put("filter_blacklist_redirects", new Test(DuctGameTests::filterBlacklistRedirects, 400));
        TESTS.put("full_destination_leaves_source", new Test(DuctGameTests::fullDestinationLeavesSource, 200));
        TESTS.put("reroutes_after_duct_removed", new Test(DuctGameTests::reroutesAfterDuctRemoved, 900));
        TESTS.put("homeless_items_return_to_backlog", new Test(DuctGameTests::homelessItemsReturnToBacklog, 400));
        TESTS.put("retriever_pulls_items", new Test(DuctGameTests::retrieverPullsItems, 300));
    }

    private static final DeferredRegister<Consumer<GameTestHelper>> FUNCTIONS =
            DeferredRegister.create(Registries.TEST_FUNCTION, ModernItemDucts.MODID);

    static {
        TESTS.forEach((name, test) -> FUNCTIONS.register(name, () -> test.function()));
    }

    private GameTestRegistration() {}

    public static void register(IEventBus modBus) {
        FUNCTIONS.register(modBus);
        modBus.addListener(GameTestRegistration::onRegisterTests);
    }

    private static void onRegisterTests(RegisterGameTestsEvent event) {
        // An empty environment: the tests set up everything they need themselves.
        Holder<TestEnvironmentDefinition<?>> env = event.registerEnvironment(ModernItemDucts.id("default"));
        TESTS.forEach((name, test) -> event.registerTest(ModernItemDucts.id(name), new FunctionGameTestInstance(
                ResourceKey.create(Registries.TEST_FUNCTION, ModernItemDucts.id(name)),
                new TestData<>(env, ModernItemDucts.id("platform"), test.maxTicks(), 0, true))));
    }
}
