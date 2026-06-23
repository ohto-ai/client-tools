package indi.ohtoai.tool.client_tools.client.shop;

import indi.ohtoai.tool.client_tools.client.util.ContainerUtils;
import it.unimi.dsi.fastutil.ints.Int2ObjectArrayMap;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tick-based state machine that navigates a container-based shop GUI to buy
 * or sell items. The shop is opened via {@code /shop} chat command.
 *
 * <h3>Shop GUI layout (double chest, 54 slots = 6 rows × 9 columns):</h3>
 *
 * <b>Browse page:</b>
 * <pre>
 *   Slots  0-44: shop items (5 rows)
 *   Slots 45-53: navigation row — paper "上一页" / "下一页"
 * </pre>
 *
 * <b>Item detail view (after clicking an item):</b>
 * <pre>
 *   Left col:  lime_stained_glass_pane ×3 (购买 1 / 8 / 64) + chest (购买全部(背包))
 *   Mid cols:  display items
 *   Right col: red_stained_glass_pane  ×3 (出售 1 / 8 / 64) + chest (出售全部(背包))
 *   Bottom:    "返回" / "退出交易"
 * </pre>
 *
 * <h3>Buy/sell flow:</h3>
 * <ol>
 *   <li>Send {@code /shop} chat</li>
 *   <li>Wait for container → scan current page for target item</li>
 *   <li>If found → click item → detail view → buy/sell → exit</li>
 *   <li>If not found → navigate to page 1 → scan forward page by page</li>
 *   <li>If exhausted all pages → error (shop doesn't sell this item)</li>
 * </ol>
 */
public class ShopExecutor {

    private enum State {
        IDLE,
        SEND_SHOP,
        WAIT_OPEN,
        SCAN_PAGE,          // scan current page for target item
        GO_TO_FIRST,        // navigate backward to page 1
        WAIT_PREV,          // wait after clicking "上一页"
        NAVIGATE_NEXT,      // click "下一页"
        WAIT_NAVIGATE,      // wait after page navigation
        CLICK_ITEM,         // click the target item
        WAIT_DETAIL,        // wait for detail view to appear
        SCAN_ACTION,        // find buy/sell button by item type + count
        CLICK_ACTION,       // click the action button
        WAIT_ACTION,        // wait for transaction
        EXIT_MENU,          // click "退出交易" or close
        WAIT_EXIT,
        DONE,
        ERROR
    }

    // --- Singleton ---

    private static ShopExecutor instance;

    public static ShopExecutor getInstance() {
        if (instance == null) instance = new ShopExecutor();
        return instance;
    }

    private ShopExecutor() {}

    // --- Logger ---

    private static final Logger LOGGER = LoggerFactory.getLogger("client-tools");

    // --- State ---

    private State state = State.IDLE;
    private boolean isBuyMode;
    private Item targetItem;
    private int targetCount = -1;   // -1 = all
    private String errorMessage = "";
    private int tickCounter;
    private int foundSlot = -1;     // slot index for current action
    private int lastContainerId = -1;
    private int pageCount;          // pages scanned so far
    private boolean scannedCurrentPage; // true after first scan of the shop's opening page

    // --- Constants ---

    private static final int NAV_WAIT_TICKS = 4;           // wait after page navigation
    private static final int CONTAINER_OPEN_TIMEOUT = 100; // 5s
    private static final int MAX_PAGES = 50;
    private static final int DETAIL_WAIT_TICKS = 8;
    private static final int ACTION_WAIT_TICKS = 5;

    // --- Item name mapping (Chinese → Minecraft item ID) ---

    private static final Map<String, String> ITEM_NAME_MAP = new HashMap<>();
    static {
        ITEM_NAME_MAP.put("沙子", "minecraft:sand");
        ITEM_NAME_MAP.put("沙砾", "minecraft:gravel");
        ITEM_NAME_MAP.put("石头", "minecraft:stone");
        ITEM_NAME_MAP.put("圆石", "minecraft:cobblestone");
        ITEM_NAME_MAP.put("泥土", "minecraft:dirt");
        ITEM_NAME_MAP.put("玻璃", "minecraft:glass");
        ITEM_NAME_MAP.put("玻璃板", "minecraft:glass_pane");
        ITEM_NAME_MAP.put("海绵", "minecraft:sponge");
        ITEM_NAME_MAP.put("湿海绵", "minecraft:wet_sponge");
        ITEM_NAME_MAP.put("煤炭", "minecraft:coal");
        ITEM_NAME_MAP.put("木炭", "minecraft:charcoal");
        ITEM_NAME_MAP.put("铁锭", "minecraft:iron_ingot");
        ITEM_NAME_MAP.put("金锭", "minecraft:gold_ingot");
        ITEM_NAME_MAP.put("钻石", "minecraft:diamond");
        ITEM_NAME_MAP.put("绿宝石", "minecraft:emerald");
        ITEM_NAME_MAP.put("红石", "minecraft:redstone");
        ITEM_NAME_MAP.put("青金石", "minecraft:lapis_lazuli");
        ITEM_NAME_MAP.put("下界石英", "minecraft:quartz");
        ITEM_NAME_MAP.put("燧石", "minecraft:flint");
        ITEM_NAME_MAP.put("粘土", "minecraft:clay");
        ITEM_NAME_MAP.put("粘土块", "minecraft:clay");
        ITEM_NAME_MAP.put("雪球", "minecraft:snowball");
        ITEM_NAME_MAP.put("火药", "minecraft:gunpowder");
        ITEM_NAME_MAP.put("荧石粉", "minecraft:glowstone_dust");
        ITEM_NAME_MAP.put("荧石", "minecraft:glowstone");
        ITEM_NAME_MAP.put("黑曜石", "minecraft:obsidian");
        ITEM_NAME_MAP.put("基岩", "minecraft:bedrock");
        ITEM_NAME_MAP.put("下界岩", "minecraft:netherrack");
        ITEM_NAME_MAP.put("末地石", "minecraft:end_stone");
        ITEM_NAME_MAP.put("橡木", "minecraft:oak_log");
        ITEM_NAME_MAP.put("云杉木", "minecraft:spruce_log");
        ITEM_NAME_MAP.put("白桦木", "minecraft:birch_log");
        ITEM_NAME_MAP.put("丛林木", "minecraft:jungle_log");
        ITEM_NAME_MAP.put("金合欢木", "minecraft:acacia_log");
        ITEM_NAME_MAP.put("深色橡木", "minecraft:dark_oak_log");
        ITEM_NAME_MAP.put("红树木", "minecraft:mangrove_log");
        ITEM_NAME_MAP.put("樱花木", "minecraft:cherry_log");
        ITEM_NAME_MAP.put("绯红菌柄", "minecraft:crimson_stem");
        ITEM_NAME_MAP.put("诡异菌柄", "minecraft:warped_stem");
        ITEM_NAME_MAP.put("骨头", "minecraft:bone");
        ITEM_NAME_MAP.put("骨粉", "minecraft:bone_meal");
        ITEM_NAME_MAP.put("线", "minecraft:string");
        ITEM_NAME_MAP.put("羽毛", "minecraft:feather");
        ITEM_NAME_MAP.put("皮革", "minecraft:leather");
        ITEM_NAME_MAP.put("箭", "minecraft:arrow");
        ITEM_NAME_MAP.put("末影珍珠", "minecraft:ender_pearl");
        ITEM_NAME_MAP.put("末影之眼", "minecraft:ender_eye");
        ITEM_NAME_MAP.put("烈焰棒", "minecraft:blaze_rod");
        ITEM_NAME_MAP.put("烈焰粉", "minecraft:blaze_powder");
        ITEM_NAME_MAP.put("恶魂之泪", "minecraft:ghast_tear");
        ITEM_NAME_MAP.put("岩浆膏", "minecraft:magma_cream");
        ITEM_NAME_MAP.put("蜘蛛眼", "minecraft:spider_eye");
        ITEM_NAME_MAP.put("腐肉", "minecraft:rotten_flesh");
        ITEM_NAME_MAP.put("小麦", "minecraft:wheat");
        ITEM_NAME_MAP.put("种子", "minecraft:wheat_seeds");
        ITEM_NAME_MAP.put("胡萝卜", "minecraft:carrot");
        ITEM_NAME_MAP.put("马铃薯", "minecraft:potato");
        ITEM_NAME_MAP.put("苹果", "minecraft:apple");
        ITEM_NAME_MAP.put("金苹果", "minecraft:golden_apple");
        ITEM_NAME_MAP.put("西瓜", "minecraft:melon");
        ITEM_NAME_MAP.put("西瓜片", "minecraft:melon_slice");
        ITEM_NAME_MAP.put("南瓜", "minecraft:pumpkin");
        ITEM_NAME_MAP.put("甘蔗", "minecraft:sugar_cane");
        ITEM_NAME_MAP.put("糖", "minecraft:sugar");
        ITEM_NAME_MAP.put("鸡蛋", "minecraft:egg");
        ITEM_NAME_MAP.put("海晶碎片", "minecraft:prismarine_shard");
        ITEM_NAME_MAP.put("海晶砂砾", "minecraft:prismarine_crystals");
        ITEM_NAME_MAP.put("海带", "minecraft:kelp");
        ITEM_NAME_MAP.put("干海带", "minecraft:dried_kelp");
        ITEM_NAME_MAP.put("三叉戟", "minecraft:trident");
        ITEM_NAME_MAP.put("鹦鹉螺壳", "minecraft:nautilus_shell");
        ITEM_NAME_MAP.put("海洋之心", "minecraft:heart_of_the_sea");
        ITEM_NAME_MAP.put("鳞甲", "minecraft:scute");
        ITEM_NAME_MAP.put("幻翼膜", "minecraft:phantom_membrane");
        ITEM_NAME_MAP.put("兔子皮", "minecraft:rabbit_hide");
        ITEM_NAME_MAP.put("兔子脚", "minecraft:rabbit_foot");
        ITEM_NAME_MAP.put("粘液球", "minecraft:slime_ball");
        ITEM_NAME_MAP.put("蜂蜜瓶", "minecraft:honey_bottle");
        ITEM_NAME_MAP.put("蜜脾", "minecraft:honeycomb");
        ITEM_NAME_MAP.put("铜锭", "minecraft:copper_ingot");
        ITEM_NAME_MAP.put("紫水晶碎片", "minecraft:amethyst_shard");
        ITEM_NAME_MAP.put("回响碎片", "minecraft:echo_shard");
        ITEM_NAME_MAP.put("下界合金锭", "minecraft:netherite_ingot");
        ITEM_NAME_MAP.put("下界合金碎片", "minecraft:netherite_scrap");
        ITEM_NAME_MAP.put("远古残骸", "minecraft:ancient_debris");
    }

    // --- Public API ---

    public void startBuy(Item item, int count) {
        LOGGER.info("[shop] startBuy: {} count={}", item.getDescription().getString(), count);
        resetCommon(item, count, true);
        state = State.SEND_SHOP;
    }

    public void startSell(Item item, int count) {
        LOGGER.info("[shop] startSell: {} count={}", item.getDescription().getString(), count);
        resetCommon(item, count, false);
        state = State.SEND_SHOP;
    }

    private void resetCommon(Item item, int count, boolean buyMode) {
        this.targetItem = item;
        this.targetCount = count;
        this.isBuyMode = buyMode;
        this.tickCounter = 0;
        this.pageCount = 0;
        this.foundSlot = -1;
        this.lastContainerId = -1;
        this.errorMessage = "";
        this.scannedCurrentPage = false;
    }

    public void stop() {
        if (!isRunning()) return;
        LOGGER.info("[shop] stop() in state={}", state);
        closeContainer();
        state = State.IDLE;
    }

    public void tick(Minecraft client) {
        if (state == State.IDLE || state == State.DONE || state == State.ERROR) return;

        tickCounter++;

        if (tickCounter == 1) {
            LOGGER.info("[shop] → {}", state);
        }

        switch (state) {
            case SEND_SHOP -> doSendShop(client);
            case WAIT_OPEN -> doWaitOpen(client);
            case SCAN_PAGE -> doScanPage(client);
            case GO_TO_FIRST -> doGoToFirst(client);
            case WAIT_PREV -> doWaitPrev(client);
            case NAVIGATE_NEXT -> doNavigateNext(client);
            case WAIT_NAVIGATE -> doWaitNavigate(client);
            case CLICK_ITEM -> doClickItem(client);
            case WAIT_DETAIL -> doWaitDetail(client);
            case SCAN_ACTION -> doScanAction(client);
            case CLICK_ACTION -> doClickAction(client);
            case WAIT_ACTION -> doWaitAction(client);
            case EXIT_MENU -> doExitMenu(client);
            case WAIT_EXIT -> doWaitExit(client);
            default -> {}
        }
    }

    public boolean isRunning() {
        return state != State.IDLE && state != State.DONE && state != State.ERROR;
    }

    public boolean isDone() { return state == State.DONE; }
    public boolean isError() { return state == State.ERROR; }
    public String getErrorMessage() { return errorMessage; }
    public Item getTargetItem() { return targetItem; }
    public boolean isBuyMode() { return isBuyMode; }

    // --- Item name resolution ---

    public static Item resolveItem(String name) {
        if (name == null || name.isEmpty()) return null;

        String lower = name.toLowerCase(Locale.ROOT);

        // 1. Try Chinese name mapping
        if (ITEM_NAME_MAP.containsKey(name)) {
            return resolveById(ITEM_NAME_MAP.get(name));
        }

        // 2. Try as full item ID (e.g. "minecraft:sand", "create:crushed_iron")
        if (lower.contains(":")) {
            ResourceLocation rl = ResourceLocation.tryParse(lower);
            if (rl != null) {
                Item item = BuiltInRegistries.ITEM.get(rl);
                if (item != Items.AIR) return item;
            }
        }

        // 3. Try as bare item name — search all namespaces for an exact path match
        if (!lower.contains(":")) {
            // 3a. Try "minecraft:" first (most common)
            ResourceLocation mcRl = ResourceLocation.tryParse("minecraft:" + lower);
            if (mcRl != null) {
                Item item = BuiltInRegistries.ITEM.get(mcRl);
                if (item != Items.AIR) return item;
            }
            // 3b. Search all registered items for exact path match
            for (var entry : BuiltInRegistries.ITEM.entrySet()) {
                if (entry.getKey().location().getPath().equals(lower)) {
                    Item item = entry.getValue();
                    if (item != Items.AIR) return item;
                }
            }
        }

        // 4. Fuzzy match against Chinese name map values
        for (var entry : ITEM_NAME_MAP.entrySet()) {
            if (entry.getValue().contains(lower)) {
                return resolveById(entry.getValue());
            }
        }

        // 5. Fuzzy match against registry keys
        for (var entry : BuiltInRegistries.ITEM.entrySet()) {
            if (entry.getKey().location().getPath().contains(lower)) {
                return entry.getValue();
            }
        }

        return null;
    }

    private static Item resolveById(String id) {
        ResourceLocation rl = ResourceLocation.tryParse(id);
        if (rl == null) return null;
        Item item = BuiltInRegistries.ITEM.get(rl);
        return item != Items.AIR ? item : null;
    }

    public static java.util.Set<String> getChineseItemNames() {
        return ITEM_NAME_MAP.keySet();
    }

    // ==================== State handlers ====================

    private void doSendShop(Minecraft client) {
        if (!checkPlayer(client)) return;
        client.player.connection.sendCommand("shop");
        state = State.WAIT_OPEN;
        tickCounter = 0;
    }

    private void doWaitOpen(Minecraft client) {
        if (!checkPlayer(client)) return;
        int size = ContainerUtils.getContainerSize(client.player.containerMenu);
        if (size > 0) {
            lastContainerId = client.player.containerMenu.containerId;
            LOGGER.info("[shop] Container opened: size={}, containerId={}", size, lastContainerId);
            state = State.SCAN_PAGE;
            tickCounter = 0;
            return;
        }
        if (tickCounter > CONTAINER_OPEN_TIMEOUT) {
            error("Timed out waiting for shop to open");
        }
    }

    /**
     * Scans the current browse page (slots 0-44) for the target item.
     * On the first call after opening the shop, if the item is not found,
     * we go to page 1 before scanning forward systematically.
     */
    private void doScanPage(Minecraft client) {
        if (!checkPlayer(client)) return;
        AbstractContainerMenu menu = client.player.containerMenu;
        int size = ContainerUtils.getContainerSize(menu);
        if (size < 0) {
            error("Shop container closed");
            return;
        }
        lastContainerId = menu.containerId;

        // Handle accidental detail view
        if (hasDetailViewItems(menu, size)) {
            clickButtonByText(menu, size, "返回");
            state = State.WAIT_NAVIGATE;
            tickCounter = 0;
            return;
        }

        // Scan for target item
        foundSlot = findItemSlot(menu, size, targetItem);
        if (foundSlot >= 0) {
            LOGGER.info("[shop] Found {} in slot {} (page {} of scan)",
                targetItem.getDescription().getString(), foundSlot, pageCount + 1);
            state = State.CLICK_ITEM;
            tickCounter = 0;
            return;
        }

        // Item not on this page
        if (!scannedCurrentPage) {
            // First scan of the shop's opening page — go to page 1
            scannedCurrentPage = true;
            LOGGER.info("[shop] Item not on opening page, going to page 1");
            state = State.GO_TO_FIRST;
            tickCounter = 0;
            return;
        }

        // Already scanning from page 1 — try next page
        pageCount++;
        if (hasNextButton(menu, size) && pageCount < MAX_PAGES) {
            foundSlot = findNextButton(menu, size);
            if (foundSlot >= 0) {
                state = State.NAVIGATE_NEXT;
                tickCounter = 0;
            } else {
                error("Item not found after " + pageCount + " pages: " + targetItem.getDescription().getString());
            }
        } else {
            error("Item not found in shop after " + pageCount + " pages: " + targetItem.getDescription().getString());
        }
    }

    /**
     * Navigates backward until we reach page 1 (no "上一页" button).
     */
    private void doGoToFirst(Minecraft client) {
        if (!checkPlayer(client)) return;
        AbstractContainerMenu menu = client.player.containerMenu;
        int size = ContainerUtils.getContainerSize(menu);
        if (size < 0) {
            error("Shop container closed");
            return;
        }
        lastContainerId = menu.containerId;

        if (hasDetailViewItems(menu, size)) {
            clickButtonByText(menu, size, "返回");
            state = State.WAIT_PREV;
            tickCounter = 0;
            return;
        }

        if (!hasPrevButton(menu, size)) {
            // We're at page 1 — start scanning
            LOGGER.info("[shop] At page 1, starting scan");
            state = State.SCAN_PAGE;
            tickCounter = 0;
        } else {
            int prevSlot = findPrevButton(menu, size);
            if (prevSlot >= 0) {
                foundSlot = prevSlot;
                clickSlot(client, prevSlot);
                state = State.WAIT_PREV;
                tickCounter = 0;
            } else {
                // No prev button by text but hasPrevButton was true — should not happen
                state = State.SCAN_PAGE;
                tickCounter = 0;
            }
        }
    }

    private void doWaitPrev(Minecraft client) {
        if (tickCounter >= NAV_WAIT_TICKS) {
            state = State.GO_TO_FIRST;
            tickCounter = 0;
        }
    }

    private void doNavigateNext(Minecraft client) {
        if (!checkPlayer(client)) return;
        LOGGER.info("[shop] Navigating to next page (page {})", pageCount + 1);
        clickSlot(client, foundSlot);
        state = State.WAIT_NAVIGATE;
        tickCounter = 0;
    }

    private void doWaitNavigate(Minecraft client) {
        if (tickCounter >= NAV_WAIT_TICKS) {
            state = State.SCAN_PAGE;
            tickCounter = 0;
        }
    }

    // --- Detail view handling ---

    private void doClickItem(Minecraft client) {
        if (!checkPlayer(client)) return;
        LOGGER.info("[shop] Clicking item at slot {}", foundSlot);
        clickSlot(client, foundSlot);
        state = State.WAIT_DETAIL;
        tickCounter = 0;
    }

    private void doWaitDetail(Minecraft client) {
        if (!checkPlayer(client)) return;
        AbstractContainerMenu menu = client.player.containerMenu;
        int size = ContainerUtils.getContainerSize(menu);
        if (size < 0) {
            error("Container closed after clicking item");
            return;
        }
        lastContainerId = menu.containerId;

        if (hasDetailViewItems(menu, size)) {
            state = State.SCAN_ACTION;
            tickCounter = 0;
        } else if (tickCounter > DETAIL_WAIT_TICKS * 3) {
            error("Detail view did not appear after clicking item");
        }
    }

    private void doScanAction(Minecraft client) {
        if (!checkPlayer(client)) return;
        AbstractContainerMenu menu = client.player.containerMenu;
        int size = ContainerUtils.getContainerSize(menu);
        if (size < 0) {
            error("Container closed in detail view");
            return;
        }
        lastContainerId = menu.containerId;

        foundSlot = -1;

        if (isBuyMode) {
            foundSlot = findBuyButton(menu, size, targetCount);
        } else {
            foundSlot = findSellButton(menu, size, targetCount);
        }

        if (foundSlot >= 0) {
            state = State.CLICK_ACTION;
            tickCounter = 0;
        } else {
            String fallback = isBuyMode ? "购买" : "出售";
            foundSlot = findButtonByText(menu, size, fallback);
            if (foundSlot >= 0) {
                state = State.CLICK_ACTION;
                tickCounter = 0;
            } else {
                state = State.EXIT_MENU;
                tickCounter = 0;
            }
        }
    }

    private void doClickAction(Minecraft client) {
        if (!checkPlayer(client)) return;
        clickSlot(client, foundSlot);
        state = State.WAIT_ACTION;
        tickCounter = 0;
    }

    private void doWaitAction(Minecraft client) {
        if (tickCounter >= ACTION_WAIT_TICKS) {
            state = State.EXIT_MENU;
            tickCounter = 0;
        }
    }

    private void doExitMenu(Minecraft client) {
        if (!checkPlayer(client)) return;
        AbstractContainerMenu menu = client.player.containerMenu;
        int size = ContainerUtils.getContainerSize(menu);

        if (size > 0) {
            int exitSlot = findButtonByText(menu, size, "退出交易");
            if (exitSlot >= 0) {
                clickSlot(client, exitSlot);
                state = State.WAIT_EXIT;
                tickCounter = 0;
                return;
            }
            int backSlot = findButtonByText(menu, size, "返回");
            if (backSlot >= 0) {
                clickSlot(client, backSlot);
                state = State.WAIT_EXIT;
                tickCounter = 0;
                return;
            }
        }

        closeContainer();
        state = State.WAIT_EXIT;
        tickCounter = 0;
    }

    private void doWaitExit(Minecraft client) {
        if (tickCounter >= NAV_WAIT_TICKS) {
            if (client.player.containerMenu != null
                && ContainerUtils.getContainerSize(client.player.containerMenu) > 0) {
                closeContainer();
            }
            state = State.DONE;
            String mode = isBuyMode ? "buy" : "sell";
            String countStr = targetCount < 0 ? "all" : String.valueOf(targetCount);
            if (client.player != null) {
                client.player.displayClientMessage(
                    Component.translatable("client-tools.shop." + mode + "_done",
                        targetItem.getDescription().getString(), countStr), false);
            }
        }
    }

    // ==================== Button finding ====================

    private static int findNextButton(AbstractContainerMenu menu, int size) {
        return findButtonByText(menu, size, "下一页");
    }

    private static int findPrevButton(AbstractContainerMenu menu, int size) {
        return findButtonByText(menu, size, "上一页");
    }

    private static boolean hasNextButton(AbstractContainerMenu menu, int size) {
        return findButtonByText(menu, size, "下一页") >= 0;
    }

    private static boolean hasPrevButton(AbstractContainerMenu menu, int size) {
        return findButtonByText(menu, size, "上一页") >= 0;
    }

    private int findBuyButton(AbstractContainerMenu menu, int size, int count) {
        if (count < 0 || count > 64) {
            return findButtonByText(menu, size, "购买全部");
        }
        List<Integer> limeSlots = findSlotsByItem(menu, size, Items.LIME_STAINED_GLASS_PANE);
        if (limeSlots.isEmpty()) {
            return findButtonByText(menu, size, "购买");
        }
        int idx = count <= 1 ? 0 : count <= 8 ? 1 : 2;
        return idx < limeSlots.size() ? limeSlots.get(idx) : limeSlots.get(limeSlots.size() - 1);
    }

    private int findSellButton(AbstractContainerMenu menu, int size, int count) {
        if (count < 0 || count > 64) {
            return findButtonByText(menu, size, "出售全部");
        }
        List<Integer> redSlots = findSlotsByItem(menu, size, Items.RED_STAINED_GLASS_PANE);
        if (redSlots.isEmpty()) {
            return findButtonByText(menu, size, "出售");
        }
        int idx = count <= 1 ? 0 : count <= 8 ? 1 : 2;
        return idx < redSlots.size() ? redSlots.get(idx) : redSlots.get(redSlots.size() - 1);
    }

    private static boolean hasDetailViewItems(AbstractContainerMenu menu, int size) {
        for (int i = 0; i < size; i++) {
            ItemStack stack = menu.getSlot(i).getItem();
            if (!stack.isEmpty()) {
                Item item = stack.getItem();
                if (item == Items.LIME_STAINED_GLASS_PANE || item == Items.RED_STAINED_GLASS_PANE) {
                    return true;
                }
            }
        }
        return false;
    }

    private static List<Integer> findSlotsByItem(AbstractContainerMenu menu, int size, Item item) {
        List<Integer> slots = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            ItemStack stack = menu.getSlot(i).getItem();
            if (!stack.isEmpty() && stack.getItem() == item) {
                slots.add(i);
            }
        }
        slots.sort(Comparator.naturalOrder());
        return slots;
    }

    private static int findButtonByText(AbstractContainerMenu menu, int size, String text) {
        for (int i = 0; i < size; i++) {
            ItemStack stack = menu.getSlot(i).getItem();
            if (!stack.isEmpty()) {
                if (stack.getHoverName().getString().contains(text)) {
                    return i;
                }
            }
        }
        return -1;
    }

    private void clickButtonByText(AbstractContainerMenu menu, int size, String text) {
        int slot = findButtonByText(menu, size, text);
        if (slot >= 0) {
            foundSlot = slot;
            Minecraft client = Minecraft.getInstance();
            if (client.player != null) {
                clickSlot(client, slot);
            }
        }
    }

    private static int findItemSlot(AbstractContainerMenu menu, int size, Item target) {
        int end = Math.min(size - 1, 44);
        for (int i = 0; i <= end; i++) {
            ItemStack stack = menu.getSlot(i).getItem();
            if (!stack.isEmpty() && stack.getItem() == target) {
                return i;
            }
        }
        return -1;
    }

    // ==================== Helpers ====================

    private boolean checkPlayer(Minecraft client) {
        if (client.player == null || client.player.connection == null) {
            error("Player not available");
            return false;
        }
        return true;
    }

    private void error(String msg) {
        this.errorMessage = msg;
        this.state = State.ERROR;
        Minecraft client = Minecraft.getInstance();
        if (client.player != null) {
            client.player.displayClientMessage(
                Component.translatable("client-tools.shop.error", msg), false);
        }
        LOGGER.warn("[shop] ERROR: {}", msg);
    }

    private void closeContainer() {
        Minecraft client = Minecraft.getInstance();
        if (client.player != null && client.player.connection != null
            && client.player.containerMenu != null) {
            client.player.connection.send(
                new ServerboundContainerClosePacket(client.player.containerMenu.containerId));
        }
    }

    private void clickSlot(Minecraft client, int slotIndex) {
        if (client.player == null || client.player.connection == null) return;
        int stateId = ContainerUtils.getContainerStateId(client.player.containerMenu);
        client.player.connection.send(
            new ServerboundContainerClickPacket(
                lastContainerId, stateId, slotIndex, 0,
                ClickType.PICKUP, ItemStack.EMPTY, new Int2ObjectArrayMap<>()
            )
        );
    }
}
