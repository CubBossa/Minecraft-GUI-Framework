package de.bossascrew.guiframework.examples.lobby;

import de.bossascrew.guiframework.examples.system.ServerHandler;
import de.cubbossa.guiframework.inventory.ButtonBuilder;
import de.cubbossa.guiframework.inventory.MenuPresets;
import de.cubbossa.guiframework.inventory.implementations.InventoryMenu;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

public class GameSelectorModule implements CommandExecutor {

    public static final String COMMAND = "teleport";

    InventoryMenu gameSelector;

    public GameSelectorModule(JavaPlugin plugin) {

        gameSelector = new InventoryMenu(3, Component.text("Choose A Game"));
        gameSelector.loadPreset(MenuPresets.fill(MenuPresets.FILLER_LIGHT));
        gameSelector.setButton(4, ButtonBuilder.buttonBuilder()
                        .withItemStack(Material.NETHERITE_SWORD, Component.text("Hierarchy", NamedTextColor.DARK_RED))
                        .withClickHandler(clickContext -> ServerHandler.getInstance().connect(clickContext.getPlayer(), "hierachy_01")));
        gameSelector.setButton(10, ButtonBuilder.buttonBuilder()
                        .withItemStack(Material.GOLD_BLOCK, Component.text("Creative", NamedTextColor.GOLD))
                        .withClickHandler(clickContext -> ServerHandler.getInstance().connect(clickContext.getPlayer(), "creative_01")));
        gameSelector.setButton(16, ButtonBuilder.buttonBuilder()
                        .withItemStack(Material.COBBLESTONE, Component.text("Skyblock", NamedTextColor.AQUA))
                        .withClickHandler(clickContext -> ServerHandler.getInstance().connect(clickContext.getPlayer(), "skyblock_01")));
        gameSelector.setButton(25, ButtonBuilder.buttonBuilder()
                        .withItemStack(Material.EMERALD, Component.text("Lobby", NamedTextColor.GREEN))
                        .withClickHandler(clickContext -> ServerHandler.getInstance().connect(clickContext.getPlayer(), "lobby_01")));

        Objects.requireNonNull(plugin.getCommand(COMMAND)).setExecutor(this);
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command cmd, @NotNull String label, @NotNull String[] args) {
        if (sender instanceof Player player) {
            gameSelector.open(player);
        }
        return true;
    }
}
