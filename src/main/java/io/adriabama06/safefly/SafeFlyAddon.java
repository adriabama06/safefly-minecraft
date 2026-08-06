package io.adriabama06.safefly;

import io.adriabama06.safefly.commands.CommandExample;
import io.adriabama06.safefly.hud.HudExample;
import io.adriabama06.safefly.modules.ModuleExample;
import io.adriabama06.safefly.modules.SafeFly;
import com.mojang.logging.LogUtils;
import meteordevelopment.meteorclient.addons.GithubRepo;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.commands.Commands;
import meteordevelopment.meteorclient.systems.hud.Hud;
import meteordevelopment.meteorclient.systems.hud.HudGroup;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Modules;
import org.slf4j.Logger;

public class SafeFlyAddon extends MeteorAddon {
    public static final Logger LOG = LogUtils.getLogger();
    public static final Category CATEGORY = new Category("SafeFly");
    public static final HudGroup HUD_GROUP = new HudGroup("SafeFly");

    @Override
    public void onInitialize() {
        LOG.info("Initializing SafeFly Addon");

        // Modules
        Modules.get().add(new ModuleExample());
        Modules.get().add(new SafeFly(CATEGORY));

        // Commands
        Commands.add(new CommandExample());

        // HUD
        Hud.get().register(HudExample.INFO);
    }

    @Override
    public void onRegisterCategories() {
        Modules.registerCategory(CATEGORY);
    }

    @Override
    public String getPackage() {
        return "io.adriabama06.safefly";
    }

    @Override
    public GithubRepo getRepo() {
        return new GithubRepo("adriabama06", "safefly-minecraft");
    }
}
