package indi.ohtoai.tool.client_tools.client.craft;

import net.minecraft.core.HolderLookup;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.*;

import java.util.*;

/**
 * Builds an optimal multi-source crafting plan from available chest items
 * to a target product, using only single-material recipes.
 *
 * <p>Unlike {@link RecipeChainAnalyzer} which requires a fixed source item,
 * the planner scans all items in the input chest and constructs a dependency
 * tree, preferring items that are "closer" to the product (fewer crafting
 * steps) over more basic materials.</p>
 *
 * <p>Example: to craft gold_blocks when the chest contains both gold_ingots
 * and gold_nuggets, the planner uses gold_ingots first (1 step away) and
 * only falls back to crafting ingots from nuggets (2 steps away) for any
 * remaining deficit.</p>
 *
 * <p>The recipe graph is cached after first build so repeated planning
 * cycles (infinite mode) only re-traverse the tree, not rebuild the graph.</p>
 */
public class MaterialPlanner {

    /**
     * A single crafting operation in the material plan.
     */
    public record CraftOp(
        Item fromItem,
        Item toItem,
        int fromCount,
        int toCount,
        int executions,
        ResourceLocation recipeId
    ) {
        @Override
        public String toString() {
            ResourceLocation fromKey = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(fromItem);
            ResourceLocation toKey = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(toItem);
            return executions + "× (" + fromCount + " " + (fromKey != null ? fromKey : fromItem) +
                " → " + toCount + " " + (toKey != null ? toKey : toItem) + ")";
        }
    }

    /**
     * The complete material plan: ordered operations (dependencies first)
     * and what to extract from the input chest.
     */
    public static class MaterialPlan {
        public final List<CraftOp> operations;
        public final Map<Item, Integer> takeItems;

        public MaterialPlan(List<CraftOp> operations, Map<Item, Integer> takeItems) {
            this.operations = Collections.unmodifiableList(operations);
            this.takeItems = Collections.unmodifiableMap(takeItems);
        }

        public boolean isEmpty() {
            return operations.isEmpty() && takeItems.isEmpty();
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder("MaterialPlan:\n");
            if (!takeItems.isEmpty()) {
                sb.append("  Take from chest:\n");
                for (var e : takeItems.entrySet()) {
                    ResourceLocation key = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(e.getKey());
                    sb.append("    ").append(e.getValue()).append("× ").append(key != null ? key : e.getKey()).append("\n");
                }
            }
            if (!operations.isEmpty()) {
                sb.append("  Craft:\n");
                for (CraftOp op : operations) {
                    sb.append("    ").append(op).append("\n");
                }
            }
            return sb.toString();
        }
    }

    /**
     * Internal node in the dependency tree.
     */
    private static class DependencyNode {
        final Item item;
        int fromChest;       // how many come directly from the chest
        int fromCrafting;    // how many come from crafting (child)
        RecipeChainAnalyzer.RecipeEdge recipe; // the recipe used (null if leaf / chest-only)
        DependencyNode child; // ingredient subtree (null if leaf)
        int surplus;         // extra produced beyond what's needed

        DependencyNode(Item item) {
            this.item = item;
        }

        int totalAvailable() {
            return fromChest + fromCrafting;
        }
    }

    // ---- State ----

    private final RecipeManager recipeManager;
    private final HolderLookup.Provider registryAccess;
    private Map<Item, List<RecipeChainAnalyzer.RecipeEdge>> reverseMap;
    private boolean graphBuilt = false;

    public MaterialPlanner(RecipeManager recipeManager, HolderLookup.Provider registryAccess) {
        this.recipeManager = recipeManager;
        this.registryAccess = registryAccess;
    }

    // ---- Public API ----

    /**
     * Build a material plan: given a target product and the contents of the
     * input chest, determine (a) which items to extract from the chest and
     * (b) which crafting operations to execute, in order.
     *
     * @param productItem the desired final item
     * @param targetCount how many final items are wanted (> 0 for finite, -1 / 0 treated as "as many as possible")
     * @param chestItems  snapshot of the input chest: item → total count
     * @return the plan, or null if nothing can be crafted
     */
    public MaterialPlan buildPlan(Item productItem, int targetCount, Map<Item, Integer> chestItems) {
        ensureGraphBuilt();

        if (chestItems.isEmpty()) return null;

        // Clone chest items so we can mutate during planning
        Map<Item, Integer> available = new HashMap<>(chestItems);

        // --- Determine effective target ---
        int effectiveTarget;
        if (targetCount <= 0) {
            // "As many as possible": estimate from available materials
            effectiveTarget = estimateMaxPossible(productItem, available);
            if (effectiveTarget <= 0) return null;
        } else {
            // Deduct existing final products in the chest
            int alreadyHave = available.getOrDefault(productItem, 0);
            effectiveTarget = Math.max(0, targetCount - alreadyHave);
            if (alreadyHave > 0) {
                available.put(productItem, 0); // we'll take these separately
            }
            if (effectiveTarget <= 0) return null;
        }

        // --- Build dependency tree ---
        Set<Item> visited = new HashSet<>();
        DependencyNode root = buildTree(productItem, effectiveTarget, available, visited);

        if (root == null || root.totalAvailable() <= 0) return null;

        // --- Clamp: recalculate based on actual availability (bottom-up pass) ---
        int actualProducible = clampBottomUp(root);
        if (actualProducible <= 0) return null;

        // --- Linearize into ordered operations ---
        List<CraftOp> ops = new ArrayList<>();
        Map<Item, Integer> takeItems = new LinkedHashMap<>();
        linearize(root, ops, takeItems);

        return new MaterialPlan(ops, takeItems);
    }

    // ---- Graph construction ----

    private void ensureGraphBuilt() {
        if (graphBuilt) return;
        reverseMap = RecipeChainAnalyzer.buildReverseRecipeMap(recipeManager, registryAccess);
        graphBuilt = true;
    }

    // ---- Tree building ----

    /**
     * Recursively build a dependency tree for obtaining {@code needed} of {@code item}.
     * Mutates {@code available} as chest resources are consumed.
     */
    private DependencyNode buildTree(Item item, int needed, Map<Item, Integer> available, Set<Item> visited) {
        if (needed <= 0) return null;

        // Cycle detection: if we've already visited this item up the tree, stop
        if (visited.contains(item)) return null;

        DependencyNode node = new DependencyNode(item);

        // 1) Take what's available directly from the chest
        int chestCount = available.getOrDefault(item, 0);
        node.fromChest = Math.min(chestCount, needed);
        if (node.fromChest > 0) {
            available.put(item, chestCount - node.fromChest);
        }

        int remaining = needed - node.fromChest;
        if (remaining <= 0) {
            node.surplus = node.fromChest - needed;
            return node;
        }

        // 2) Find the best recipe to craft the remaining amount
        List<RecipeChainAnalyzer.RecipeEdge> recipes = reverseMap.get(item);
        if (recipes == null || recipes.isEmpty()) {
            return node; // can't craft, return what we have
        }

        // Sort recipes: prefer ones whose input is directly available in chest
        List<RecipeChainAnalyzer.RecipeEdge> sorted = new ArrayList<>(recipes);
        sorted.sort((a, b) -> {
            int aAvail = available.getOrDefault(a.fromItem(), 0);
            int bAvail = available.getOrDefault(b.fromItem(), 0);
            // More available = higher priority (lower sort value)
            return Integer.compare(bAvail, aAvail);
        });

        // Try recipes in priority order until we satisfy the need
        int totalCrafted = 0;
        for (RecipeChainAnalyzer.RecipeEdge recipe : sorted) {
            if (remaining <= 0) break;

            int execs = ceilDiv(remaining, recipe.toCount());
            int inputNeeded = execs * recipe.fromCount();

            visited.add(item);
            DependencyNode child = buildTree(recipe.fromItem(), inputNeeded, available, visited);
            visited.remove(item);

            if (child == null) continue;

            int inputAvailable = child.totalAvailable();
            if (inputAvailable <= 0) continue;

            int actualExecs = inputAvailable / recipe.fromCount();
            if (actualExecs <= 0) continue;

            int crafted = actualExecs * recipe.toCount();
            remaining -= crafted;
            totalCrafted += crafted;

            // Record the recipe and child (only for the first/primary recipe)
            if (node.recipe == null) {
                node.recipe = recipe;
                node.child = child;
                node.fromCrafting = crafted;
            }
            // TODO: support multiple alternative recipes feeding the same node
            // For now we only use the best recipe; remaining alternatives
            // could supplement but that's a future enhancement.
            break;
        }

        // If we over-produced, add surplus back
        node.surplus = (node.fromChest + totalCrafted) - needed;
        if (node.surplus > 0) {
            available.merge(item, node.surplus, Integer::sum);
        }

        return node;
    }

    // ---- Bottom-up clamping ----

    /**
     * Bottom-up pass: recalculate fromCrafting based on what the child
     * can actually provide. Returns the number of {@code root.item} that
     * can actually be produced.
     */
    private int clampBottomUp(DependencyNode node) {
        if (node == null) return 0;

        if (node.child != null) {
            int childOutput = clampBottomUp(node.child);
            // childOutput is how many of the child's item are available
            int actualExecs = childOutput / node.recipe.fromCount();
            int actualCrafted = actualExecs * node.recipe.toCount();
            node.fromCrafting = actualCrafted;

            // Put back unused child output
            int unused = childOutput - (actualExecs * node.recipe.fromCount());
            node.child.surplus += unused;
        }

        return node.totalAvailable();
    }

    // ---- Linearization ----

    /**
     * Topological sort: collect operations (child first, then parent)
     * and chest items to extract.
     */
    private void linearize(DependencyNode node, List<CraftOp> ops, Map<Item, Integer> takeItems) {
        if (node == null) return;

        // Depth-first: child first (dependencies before dependents)
        if (node.child != null) {
            linearize(node.child, ops, takeItems);
        }

        // Record chest extraction
        if (node.fromChest > 0) {
            takeItems.merge(node.item, node.fromChest, Integer::sum);
        }

        // Record crafting operation
        if (node.recipe != null && node.fromCrafting > 0) {
            int execs = node.fromCrafting / node.recipe.toCount();
            if (execs > 0) {
                ops.add(new CraftOp(
                    node.recipe.fromItem(),
                    node.item,
                    node.recipe.fromCount(),
                    node.recipe.toCount(),
                    execs,
                    node.recipe.recipeId()
                ));
            }
        }
    }

    // ---- Helpers ----

    private static int ceilDiv(int a, int b) {
        return (a + b - 1) / b;
    }

    /**
     * Quick estimate of the maximum possible products from available chest items.
     * Used in "infinite" mode to set a per-cycle target. This is just a heuristic;
     * the actual buildTree pass determines the exact number.
     */
    private int estimateMaxPossible(Item productItem, Map<Item, Integer> available) {
        // Try to build the tree with a large target and see how many we get
        // Use a cloned copy so we don't mutate the original
        Map<Item, Integer> clone = new HashMap<>(available);
        Set<Item> visited = new HashSet<>();
        // Start with a very large target; the tree will clamp to what's actually available
        DependencyNode root = buildTree(productItem, Integer.MAX_VALUE / 2, clone, visited);
        if (root == null) return 0;
        clampBottomUp(root);
        return root.totalAvailable();
    }
}
