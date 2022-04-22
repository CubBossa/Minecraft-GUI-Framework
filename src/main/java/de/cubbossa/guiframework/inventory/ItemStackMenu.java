package de.cubbossa.guiframework.inventory;

import com.google.common.base.Preconditions;
import de.cubbossa.guiframework.GUIHandler;
import de.cubbossa.guiframework.inventory.context.AnimationContext;
import de.cubbossa.guiframework.inventory.context.CloseContext;
import de.cubbossa.guiframework.inventory.context.ContextConsumer;
import de.cubbossa.guiframework.inventory.context.TargetContext;
import lombok.Getter;
import lombok.Setter;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.stream.Collectors;

@Getter
public abstract class ItemStackMenu {

    public enum ViewMode {
        MODIFY,
        VIEW
    }

    protected final SortedMap<Integer, ItemStack> itemStacks;
    protected final SortedMap<Integer, Consumer<Player>> soundPlayer;

    protected final List<MenuPreset<? extends TargetContext<?>>> dynamicProcessors;
    protected final SortedMap<Integer, ItemStack> dynamicItemStacks;

    @Setter
    protected ContextConsumer<CloseContext> closeHandler;

    protected final Map<Integer, Collection<Animation>> animations;
    protected final Map<UUID, ViewMode> viewer;


    protected final int slotsPerPage;
    protected int currentPage = 0;

    protected Inventory inventory;

    public ItemStackMenu(int slotsPerPage) {

        this.itemStacks = new TreeMap<>();
        this.soundPlayer = new TreeMap<>();
        this.dynamicProcessors = new ArrayList<>();
        this.dynamicItemStacks = new TreeMap<>();
        this.animations = new TreeMap<>();
        this.viewer = new HashMap<>();
        this.slotsPerPage = slotsPerPage;
    }

    /**
     * @return all slots that are part of this inventory.
     */
    public abstract int[] getSlots();

    protected abstract Inventory createInventory(Player player, int page);

    protected abstract void openInventory(Player player, Inventory inventory);

    public void open(Player viewer) {
        open(viewer, ViewMode.MODIFY);
    }

    public void open(Player viewer, ViewMode viewMode) {
        GUIHandler.getInstance().callSynchronized(() -> openInventorySynchronized(viewer, viewMode, null));
    }

    public void open(Collection<Player> viewers, ViewMode viewMode) {
        viewers.forEach(player -> open(player, viewMode));
    }

    public void open(Player viewer, ItemStackMenu previous) {
        GUIHandler.getInstance().callSynchronized(() -> openInventorySynchronized(viewer, previous));
    }

    public void open(Collection<Player> viewers, ItemStackMenu previous) {
        viewers.forEach(player -> open(player, previous));
    }

    public ItemStackMenu openSubMenu(Player player, ItemStackMenu menu) {
        menu.open(player, this);
        return menu;
    }

    public ItemStackMenu openSubMenu(Player player, Supplier<ItemStackMenu> menuSupplier) {
        return openSubMenu(player, menuSupplier.get());
    }

    public ItemStackMenu openSubMenu(Player player, ItemStackMenu menu, MenuPreset<?> backPreset) {
        menu.addPreset(backPreset);
        menu.open(player, this);
        return menu;
    }

    public ItemStackMenu openSubMenu(Player player, Supplier<ItemStackMenu> menuSupplier, MenuPreset<?> backPreset) {
        return openSubMenu(player, menuSupplier.get(), backPreset);
    }

    public void openNextPage(Player player) {
        openPage(player, currentPage + 1);
    }

    public void openPreviousPage(Player player) {
        openPage(player, currentPage - 1);
    }

    public void openPage(Player player, int page) {
        currentPage = page;
        render(player);
    }

    protected void openInventorySynchronized(Player viewer, @Nullable ItemStackMenu previous) {
        openInventorySynchronized(viewer, ViewMode.MODIFY, previous);
    }

    protected void openInventorySynchronized(Player viewer, ViewMode viewMode, @Nullable ItemStackMenu previous) {

        if (viewer.isSleeping()) {
            viewer.wakeup(true);
        }
        render(viewer);
        openInventory(viewer, inventory);

        if (this.viewer.isEmpty()) {
            animations.forEach((integer, animations1) -> {
                int i = integer - currentPage * slotsPerPage;
                if (i >= 0 && i < slotsPerPage) {
                    animations1.forEach(Animation::play);
                }
            });
        }
        this.viewer.put(viewer.getUniqueId(), viewMode);
    }

    public abstract void render(Player viewer);

    /**
     * Close this menu for a player.
     *
     * @param viewer the player to close this menu for
     */
    public void close(Player viewer) {

        if (this.viewer.remove(viewer.getUniqueId()) == null) {
            return;
        }
        if (this.viewer.size() == 0) {
            animations.forEach((integer, animations1) -> animations1.forEach(Animation::stop));
        }
        if (closeHandler != null) {
            try {
                closeHandler.accept(new CloseContext(viewer, currentPage));
            } catch (Exception exc) {
                GUIHandler.getInstance().getLogger().log(Level.SEVERE, "Error occured while closing gui for " + viewer.getName(), exc);
            }
        }
    }

    /**
     * Close this menu for a group of players.
     *
     * @param viewers the group of players to close this menu for.
     */
    public void closeAll(Collection<Player> viewers) {
        viewers.forEach(this::close);
    }

    /**
     * Close all player views of this menu.
     */
    public void closeAll() {
        closeAll(viewer.keySet().stream().map(Bukkit::getPlayer).collect(Collectors.toSet()));
    }

    public void refreshDynamicItemSuppliers() {
        dynamicItemStacks.clear();
        for (MenuPreset menuPreset : dynamicProcessors) {
            menuPreset.placeDynamicEntries(this, (integer, itemStack) -> dynamicItemStacks.put((Integer) integer, (ItemStack) itemStack), (key, value) -> {
            });
        }
    }

    /**
     * loads a dynamic preset that only exists as long as the current page is opened. This might be useful to
     * implement pagination, as pagination may need to extend dynamically based on the page count.
     *
     * @param menuPreset the instance of the processor. Use the BiConsumer parameters to add items and clickhandler
     *                      to a specific slot.
     */
    public MenuPreset<? extends TargetContext<?>> addPreset(MenuPreset<? extends TargetContext<?>> menuPreset) {
        dynamicProcessors.add(menuPreset);
        return menuPreset;
    }

    /**
     * Unloads a certain menu processor / preset. The preset items will stay until their slot is updated.
     *
     * @param menuPreset the preset to remove
     */
    public void removePreset(MenuPreset<? extends TargetContext<?>> menuPreset) {
        dynamicProcessors.remove(menuPreset);
    }

    /**
     * Removes all presets. The preset icons will stay in all open menus of this instance until the menu gets refreshed.
     * Reopen them or call {@link #refresh(int...)} on the according or just all slots with {@link #getSlots()}
     */
    public void removeAllPresets() {
        dynamicProcessors.clear();
    }

    /**
     * Clears all minecraft inventory slots. It does not clear the menu item map or any click handlers.
     * After reopening or refreshing the menu, all items will be back.
     */
    public void clearContent() {
        for (int slot : getSlots()) {
            inventory.setItem(slot, null);
        }
    }

    /**
     * @param slot the slot to get the itemstack from
     * @return the itemstack of the menu at the given slot. This does not return the actual item in the inventory but the stored item instance.
     */
    public ItemStack getItemStack(int slot) {
        ItemStack stack = itemStacks.get(slot);
        if (stack != null) {
            return stack;
        }
        int dynSlot = slot % slotsPerPage;
        return dynamicItemStacks.get(dynSlot < 0 ? dynSlot + slotsPerPage : dynSlot);
    }

    /**
     * Sets an inventory icon
     *
     * @param item the item instance to insert into the inventory
     * @param slot the slot to add the item at. Use slots larger than the slots on one page to place them on a different page.
     *             {@code slot = (current_page * slots_per_page) + inventory_slot}
     */
    public void setItem(int slot, ItemStack item) {
        itemStacks.put(slot, item);
    }

    public void removeItem(int... slots) {
        for (int slot : slots) {
            inventory.setItem(slot, null);
            itemStacks.remove(slot);
        }
    }

    /**
     * Populates the inventory with itemstacks that are rendered on each page, if no real item was found.
     *
     * @param slot the slots to render the item at (not paginated, only 0 to rows*cols)
     * @param item the item to render
     */
    public void setDynamicItem(int slot, ItemStack item) {
        Preconditions.checkArgument(slotsPerPage <= slot ||slot < 0, "Slot must be on first page.");
        dynamicItemStacks.put(slot, item);
    }

    /**
     * Refreshes the itemstack at certain slots of this menu.
     * This method needs to be called after all methods that insert items. {@link #setItem(int, ItemStack)}
     *
     * @param slots the slots to refresh
     */
    public void refresh(int... slots) {
        for (int slot : slots) {
            int realIndex = currentPage * slotsPerPage + slot;
            inventory.setItem(slot, getItemStack(realIndex));
        }
    }

    public abstract int getMinPage();

    public abstract int getMaxPage();


    public Animation playAnimation(int slot, int ticks, Function<AnimationContext, ItemStack> itemUpdater) {
        return playAnimation(slot, -1, ticks, itemUpdater);
    }

    public Animation playAnimation(int slot, int intervals, int ticks, Function<AnimationContext, ItemStack> itemUpdater) {
        Animation animation = new Animation(slot, intervals, ticks, itemUpdater);

        Collection<Animation> animations = this.animations.getOrDefault(slot, new HashSet<>());
        animations.add(animation);
        this.animations.put(slot, animations);
        if (inventory != null && viewer.size() > 0) {
            animation.play();
        }
        return animation;
    }

    public void stopAnimation(int... slots) {
        for (int slot : slots) {
            Collection<Animation> animations = this.animations.get(slot);
            if (animations != null) {
                animations.forEach(AbstractInventoryMenu.Animation::stop);
            }
        }
    }

    public class Animation {

        private final int slot;
        private int intervals = -1;
        private final int ticks;
        private final Function<AnimationContext, ItemStack> itemUpdater;

        private BukkitTask task;

        public Animation(int slot, int ticks, Function<AnimationContext, ItemStack> itemUpdater) {
            this.slot = slot;
            this.ticks = ticks;
            this.itemUpdater = itemUpdater;
        }

        public Animation(int slot, int intervals, int ticks, Function<AnimationContext, ItemStack> itemUpdater) {
            this.slot = slot;
            this.intervals = intervals;
            this.ticks = ticks;
            this.itemUpdater = itemUpdater;
        }

        public void play() {
            final ItemStack item = itemStacks.getOrDefault(slot, new ItemStack(Material.AIR));
            AtomicInteger interval = new AtomicInteger(0);
            task = Bukkit.getScheduler().runTaskTimer(GUIHandler.getInstance().getPlugin(), () -> {
                if (intervals == -1 || interval.get() < intervals) {
                    if (item != null) {
                        try {
                            setItem(slot, itemUpdater.apply(new AnimationContext(slot, intervals, item, Bukkit.getCurrentTick(), Bukkit.getCurrentTick() % 20)));
                            refresh(slot);
                        } catch (Throwable t) {
                            GUIHandler.getInstance().getLogger().log(Level.SEVERE, "Error occured while playing animation in inventory menu", t);
                        }
                        interval.addAndGet(1);
                    }
                } else {
                    stop();
                }
            }, 0, ticks);
        }

        public void stop() {
            if (task != null && !task.isCancelled()) {
                task.cancel();
            }
        }

        public boolean isRunning() {
            return !task.isCancelled();
        }
    }
}