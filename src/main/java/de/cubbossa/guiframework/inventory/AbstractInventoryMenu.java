package de.cubbossa.guiframework.inventory;

import de.cubbossa.guiframework.GUIHandler;
import de.cubbossa.guiframework.inventory.context.AnimationContext;
import de.cubbossa.guiframework.inventory.context.ClickContext;
import de.cubbossa.guiframework.inventory.context.CloseContext;
import de.cubbossa.guiframework.inventory.context.ContextConsumer;
import de.cubbossa.guiframework.inventory.pagination.DynamicMenuProcessor;
import lombok.Getter;
import lombok.Setter;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.stream.Collectors;

@Getter
public abstract class AbstractInventoryMenu<T, C extends ClickContext> {

    public enum ViewMode {
        MODIFY,
        VIEW
    }

    protected final SortedMap<Integer, ItemStack> itemStacks;
    protected final Map<Integer, Map<T, ContextConsumer<C>>> clickHandler;

    protected final List<DynamicMenuProcessor<T, C>> dynamicProcessors;
    protected final SortedMap<Integer, ItemStack> dynamicItemStacks;
    protected final Map<Integer, Map<T, ContextConsumer<C>>> dynamicClickHandler;

    protected final Map<T, ContextConsumer<C>> defaultClickHandler;
    protected final Map<T, Boolean> defaultCancelled;
    @Setter
    protected ContextConsumer<CloseContext> closeHandler;

    protected final Map<Integer, Collection<Animation>> animations;
    protected final Map<UUID, ViewMode> viewer;

    protected final int slotsPerPage;
    protected int currentPage = 0;

    protected Inventory inventory;

    public AbstractInventoryMenu(int slotsPerPage) {

        this.itemStacks = new TreeMap<>();
        this.clickHandler = new TreeMap<>();

        this.dynamicProcessors = new ArrayList<>();
        this.dynamicItemStacks = new TreeMap<>();
        this.dynamicClickHandler = new TreeMap<>();

        this.defaultClickHandler = new HashMap<>();
        this.animations = new TreeMap<>();
        this.viewer = new HashMap<>();
        this.defaultCancelled = new HashMap<>();

        this.slotsPerPage = slotsPerPage;
        this.inventory = createInventory(currentPage);
    }

    public abstract Inventory createInventory(int page);

    public void open(Player viewer) {
        open(viewer, ViewMode.MODIFY);
    }

    public void open(Player viewer, ViewMode viewMode) {
        GUIHandler.getInstance().callSynchronized(() -> openInventorySynchronized(viewer, viewMode, null));
    }

    public void open(Collection<Player> viewers, ViewMode viewMode) {
        viewers.forEach(player -> open(player, viewMode));
    }

    public void open(Player viewer, AbstractInventoryMenu<T, C> previous) {
        GUIHandler.getInstance().callSynchronized(() -> openInventorySynchronized(viewer, previous));
    }

    public void open(Collection<Player> viewers, AbstractInventoryMenu<T, C> previous) {
        viewers.forEach(player -> open(player, previous));
    }

    public AbstractInventoryMenu<T, C> openSubMenu(Player player, Supplier<AbstractInventoryMenu<T, C>> menuSupplier) {
        AbstractInventoryMenu<T, C> menu = menuSupplier.get();
        menu.open(player, this);
        return menu;
    }

    public void openNextPage(Player player) {
        openPage(player, currentPage + 1);
    }

    public void openPreviousPage(Player player) {
        openPage(player, currentPage - 1);
    }

    public void openPage(Player player, int page) {
        currentPage = page;
        open(player);
    }

    protected void openInventorySynchronized(Player viewer, @Nullable AbstractInventoryMenu<?, ?> previous) {
        openInventorySynchronized(viewer, ViewMode.MODIFY, previous);
    }

    protected void openInventorySynchronized(Player viewer, ViewMode viewMode, @Nullable AbstractInventoryMenu<?, ?> previous) {

        if (inventory == null) {
            GUIHandler.getInstance().getLogger().log(Level.SEVERE, "Could not open inventory for " + viewer.getName() + ", inventory is null.");
            return;
        }
        if (viewer.isSleeping()) {
            viewer.wakeup(true);
        }

        for (DynamicMenuProcessor<T, C> processor : dynamicProcessors) {
            processor.placeDynamicEntries(this, dynamicItemStacks::put, dynamicClickHandler::put);
        }

        for (int slot : getSlots()) {
            ItemStack item = dynamicItemStacks.getOrDefault(slot, itemStacks.get(slot));
            if (item == null) {
                continue;
            }
            inventory.setItem(slot, item.clone());
        }

        if (viewer.openInventory(inventory) == null) {
            return;
        }
        this.viewer.put(viewer.getUniqueId(), viewMode);
        InventoryHandler.getInstance().registerInventory(viewer, this, previous);
    }

    public boolean handleInteract(Player player, int clickedSlot, T action, C context) {

        if (Arrays.stream(getSlots()).noneMatch(value -> value == clickedSlot)) {
            return false;
        }
        if (viewer.get(player.getUniqueId()).equals(ViewMode.VIEW)) {
            return true;
        }

        ContextConsumer<C> clickHandler = getClickHandlerOrFallback(clickedSlot, action);
        clickHandler = dynamicClickHandler.getOrDefault(clickedSlot, new HashMap<>()).getOrDefault(action, clickHandler);

        if (clickHandler != null) {
            //execute and catch exceptions so users can't dupe itemstacks.
            try {
                clickHandler.accept(context);
            } catch (Exception exc) {
                context.setCancelled(true);
                GUIHandler.getInstance().getLogger().log(Level.SEVERE, "Error while handling GUI interaction of player " + player.getName(), exc);
            }
        }
        return context.isCancelled();
    }

    public void close(Player viewer) {
        if (viewer.getOpenInventory().getTopInventory().equals(this.inventory)) {
            viewer.closeInventory();
        }
        if (this.viewer.remove(viewer.getUniqueId()) == null) {
            return;
        }
        try {
            closeHandler.accept(new CloseContext(viewer, currentPage));
        } catch (Exception exc) {
            GUIHandler.getInstance().getLogger().log(Level.SEVERE, "Error occured while closing gui for " + viewer.getName(), exc);
        }
    }

    public void closeAll(Collection<Player> viewers) {
        viewers.forEach(this::close);
    }

    public void closeAll() {
        closeAll(viewer.keySet().stream().map(Bukkit::getPlayer).collect(Collectors.toSet()));
    }

    /**
     * loads a dynamic preset that only exists as long as the current page is opened. This might be useful to
     * implement pagination, as pagination may need to extend dynamically based on the page count.
     *
     * @param menuProcessor the instance of the processor. Use the BiConsumer parameters to add items and clickhandler
     *                      to a specific slot.
     */
    public DynamicMenuProcessor<T, C> loadPreset(DynamicMenuProcessor<T, C> menuProcessor) {
        dynamicProcessors.add(menuProcessor);
        return menuProcessor;
    }

    public void unloadPreset(DynamicMenuProcessor<T, C> menuProcessor) {
        dynamicProcessors.remove(menuProcessor);
    }


    public abstract int[] getSlots();

    public ItemStack getItemStack(int slot) {
        return itemStacks.get(slot);
    }

    public ContextConsumer<C> getClickHandlerOrFallback(int slot, T action) {
        return clickHandler.getOrDefault(slot, new HashMap<>()).getOrDefault(action, defaultClickHandler.get(action));
    }

    public void clearContent() {
        for (int slot : getSlots()) {
            inventory.setItem(slot, null);
        }
    }

    public void placeContent() {
        for (Map.Entry<Integer, ItemStack> entry : itemStacks.subMap(currentPage * slotsPerPage, currentPage * (slotsPerPage + 1)).entrySet()) {
            inventory.setItem(entry.getKey(), entry.getValue());
        }
    }

    public void refresh(int... slots) {
        for (int slot : slots) {
            int realIndex = currentPage * slotsPerPage + slot;
            inventory.setItem(slot, itemStacks.get(realIndex));
        }
    }

    public void setItem(ItemStack item, int... slots) {
        for (int slot : slots) {
            itemStacks.put(slot, item);
        }
    }

    public void setClickHandler(T action, ContextConsumer<C> clickHandler, int... slots) {
        for (int slot : slots) {
            Map<T, ContextConsumer<C>> map = this.clickHandler.getOrDefault(slot, new HashMap<>());
            map.put(action, clickHandler);
            this.clickHandler.put(slot, map);
        }
    }

    public void setItemAndClickHandler(ItemStack item, T action, ContextConsumer<C> clickHandler, int... slots) {
        setItem(item, slots);
        setClickHandler(action, clickHandler, slots);
    }

    public void setDefaultClickHandler(T action, ContextConsumer<C> clickHandler) {
        defaultClickHandler.put(action, clickHandler);
    }

    public void removeItem(int... slots) {
        for (int slot : slots) {
            inventory.setItem(slot, null);
            itemStacks.remove(slot);
        }
    }

    public void removeClickHandler(int... slots) {
        for (int slot : slots) {
            clickHandler.remove(slot);
        }
    }

    public void removeClickHandler(T action, int... slots) {
        for (int slot : slots) {
            Map<T, ContextConsumer<C>> map = clickHandler.get(slot);
            if (map != null) {
                map.remove(action);
            }
        }
    }

    public void removeItemAndClickHandler(int... slots) {
        for (int slot : slots) {
            inventory.setItem(slot, null);
            itemStacks.remove(slot);
            clickHandler.remove(slot);
        }
    }

    public void removeItemAndClickHandler(T action, int... slots) {
        for (int slot : slots) {
            inventory.setItem(slot, null);
            itemStacks.remove(slot);
            Map<T, ContextConsumer<C>> map = clickHandler.get(slot);
            if (map != null) {
                map.remove(action);
            }
        }
    }

    public void removeDefaultClickHandler(T action) {
        defaultClickHandler.remove(action);
    }

    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    public boolean isThisInventory(Inventory inventory) {
        return this.inventory != null && this.inventory.equals(inventory);
    }

    public void playAnimation(int slot, int milliseconds, ContextConsumer<AnimationContext> itemUpdater) {
        playAnimation(slot, -1, milliseconds, itemUpdater);
    }

    public void playAnimation(int slot, int intervals, int milliseconds, ContextConsumer<AnimationContext> itemUpdater) {
        Animation animation = new Animation(slot, intervals, milliseconds, itemUpdater);

        Collection<Animation> animations = this.animations.get(null);
        if (animations == null) {
            animations = new HashSet<>();
        }
        animations.add(animation);
    }

    public void stopAnimation(int... slots) {
        for (int slot : slots) {
            Collection<Animation> animations = this.animations.get(slot);
            if (animations != null) {
                animations.forEach(Animation::stop);
            }
        }
    }

    public class Animation {

        private final int slot;
        private int intervals = -1;
        private final int milliseconds;
        private final ContextConsumer<AnimationContext> itemUpdater;

        private BukkitTask task;

        public Animation(int slot, int milliseconds, ContextConsumer<AnimationContext> itemUpdater) {
            this.slot = slot;
            this.milliseconds = milliseconds;
            this.itemUpdater = itemUpdater;
        }

        public Animation(int slot, int intervals, int milliseconds, ContextConsumer<AnimationContext> itemUpdater) {
            this.slot = slot;
            this.intervals = intervals;
            this.milliseconds = milliseconds;
            this.itemUpdater = itemUpdater;
        }

        public void play() {
            final ItemStack item = itemStacks.get(slot);
            AtomicInteger interval = new AtomicInteger(0);
            task = Bukkit.getScheduler().runTaskTimer(GUIHandler.getInstance().getPlugin(), () -> {
                if ((intervals == -1 || interval.get() < intervals) && item != null) {
                    try {
                        itemUpdater.accept(new AnimationContext(slot, intervals, item));
                    } catch (Throwable t) {
                        GUIHandler.getInstance().getLogger().log(Level.SEVERE, "Error occured while playing animation in inventory menu", t);
                    }
                    interval.addAndGet(1);
                }
            }, 0, milliseconds);
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
