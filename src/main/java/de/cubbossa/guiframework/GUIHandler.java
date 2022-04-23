package de.cubbossa.guiframework;

import de.cubbossa.guiframework.inventory.listener.HotbarListener;
import de.cubbossa.guiframework.inventory.listener.InventoryListener;
import lombok.Getter;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.logging.Logger;

public abstract class GUIHandler {

	@Getter
	JavaPlugin plugin;

	@Getter
	private static GUIHandler instance;

	public GUIHandler(JavaPlugin plugin) {
		instance = this;
		this.plugin = plugin;
	}

	public void registerDefaultListeners() {
		new InventoryListener();
		new HotbarListener();
	}

	public Logger getLogger() {
		return plugin.getLogger();
	}

	public void callSynchronized(Runnable runnable) {
		Bukkit.getScheduler().runTask(plugin, runnable);
	}

	public abstract MiniMessage getMiniMessage();
}
