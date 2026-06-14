package indi.ohtoai.tool.client_tools.client.craft;

import net.minecraft.core.HolderLookup;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.*;

import java.util.*;

/**
 * Analyzes Minecraft crafting recipes to find the shortest crafting chain
 * from a raw material to a final product, using only recipes where all
 * non-empty ingredients are the same item type (single-material recipes).
 */
public class RecipeChainAnalyzer {

    private RecipeChainAnalyzer() {
        // static utility class
    }

    /**
     * A single step in a crafting chain.
     */
    public record RecipeStep(
        Item fromItem,
        Item toItem,
        int fromCount,
        int toCount,
        ResourceLocation recipeId
    ) {
        @Override
        public String toString() {
            return fromCount + "x " + itemName(fromItem) + " → " + toCount + "x " + itemName(toItem);
        }

        private static String itemName(Item item) {
            ResourceLocation key = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item);
            return key != null ? key.toString() : item.toString();
        }
    }

    /**
     * A complete crafting chain from raw material to final product.
     */
    public record RecipeChain(List<RecipeStep> steps) {
        /**
         * @return the total number of raw items needed per final product output
         */
        public int totalRawPerProduct() {
            int raw = 1;
            for (RecipeStep step : steps) {
                raw *= step.fromCount();
            }
            int product = 1;
            for (RecipeStep step : steps) {
                product *= step.toCount();
            }
            // raw / product simplified: raw items needed per product item
            return raw / gcd(raw, product);
        }

        public int totalProductPerRaw() {
            // How many final products from one batch of each step's output
            int product = 1;
            for (RecipeStep step : steps) {
                product *= step.toCount();
            }
            int raw = 1;
            for (RecipeStep step : steps) {
                raw *= step.fromCount();
            }
            return product / gcd(raw, product);
        }

        private static int gcd(int a, int b) {
            return b == 0 ? a : gcd(b, a % b);
        }
    }

    /**
     * Edge representation for recipe graph traversal. Package-private so
     * {@link MaterialPlanner} can reuse the same edge type.
     */
    record RecipeEdge(Item fromItem, Item toItem, int fromCount, int toCount, ResourceLocation recipeId) {
    }

    /**
     * Analyze the crafting recipe graph to find the shortest chain
     * from {@code sourceItem} to {@code productItem}.
     *
     * @param sourceItem      the raw material item
     * @param productItem     the desired final product
     * @param recipeManager   the client's recipe manager (from {@code Minecraft.getInstance().level.getRecipeManager()})
     * @param registryAccess  registry access for result item lookups (from {@code level.registryAccess()})
     * @return the shortest RecipeChain, or null if no chain exists
     */
    public static RecipeChain analyze(
        Item sourceItem,
        Item productItem,
        RecipeManager recipeManager,
        HolderLookup.Provider registryAccess
    ) {
        if (sourceItem == productItem) {
            return new RecipeChain(Collections.emptyList());
        }

        // Step 1: Build adjacency map — for each item, list what it can be crafted into
        Map<Item, List<RecipeEdge>> adjacency = new HashMap<>();

        List<RecipeHolder<CraftingRecipe>> allRecipes = recipeManager.getAllRecipesFor(RecipeType.CRAFTING);

        for (RecipeHolder<CraftingRecipe> holder : allRecipes) {
            CraftingRecipe recipe = holder.value();

            // Only consider recipes with a single item type across all non-empty ingredients
            Item inputItem = getSingleIngredientItem(recipe);
            if (inputItem == null) continue;

            // Count input items and determine output
            int inputCount = countNonEmptyIngredients(recipe);

            ItemStack resultStack;
            try {
                resultStack = recipe.getResultItem(registryAccess);
            } catch (Exception e) {
                // Some custom recipes may throw on getResultItem; skip them
                continue;
            }

            if (resultStack.isEmpty()) continue;

            Item outputItem = resultStack.getItem();
            int outputCount = resultStack.getCount();

            // Don't include identity recipes (same item in → same item out)
            if (inputItem == outputItem) continue;

            adjacency
                .computeIfAbsent(inputItem, k -> new ArrayList<>())
                .add(new RecipeEdge(inputItem, outputItem, inputCount, outputCount, holder.id()));
        }

        if (adjacency.isEmpty()) return null;

        // Step 2: BFS from sourceItem to productItem
        Queue<Item> queue = new ArrayDeque<>();
        Set<Item> visited = new HashSet<>();
        // For each "to" item, stores the "from" item and the edge used
        Map<Item, RecipeEdge> prevEdge = new HashMap<>();
        Map<Item, Item> prevItem = new HashMap<>();

        queue.add(sourceItem);
        visited.add(sourceItem);

        while (!queue.isEmpty()) {
            Item current = queue.poll();

            if (current == productItem) {
                // Reconstruct chain
                return reconstructChain(sourceItem, productItem, prevItem, prevEdge);
            }

            List<RecipeEdge> edges = adjacency.get(current);
            if (edges == null) continue;

            for (RecipeEdge edge : edges) {
                Item next = edge.toItem();
                if (!visited.contains(next)) {
                    visited.add(next);
                    prevItem.put(next, current);
                    prevEdge.put(next, edge);
                    queue.add(next);
                }
            }
        }

        return null; // No chain found
    }

    /**
     * Reconstruct the crafting chain from BFS traversal data.
     */
    private static RecipeChain reconstructChain(
        Item sourceItem,
        Item productItem,
        Map<Item, Item> prevItem,
        Map<Item, RecipeEdge> prevEdge
    ) {
        List<RecipeStep> steps = new ArrayList<>();
        Item current = productItem;

        while (prevItem.containsKey(current)) {
            Item from = prevItem.get(current);
            RecipeEdge edge = prevEdge.get(current);
            steps.add(new RecipeStep(from, current, edge.fromCount(), edge.toCount(), edge.recipeId()));
            current = from;
        }

        Collections.reverse(steps);
        return new RecipeChain(steps);
    }

    /**
     * Check if a recipe uses only a single item type across all non-empty ingredients,
     * and return that item. Returns null if the recipe uses multiple item types.
     */
    private static Item getSingleIngredientItem(CraftingRecipe recipe) {
        Item commonItem = null;

        for (Ingredient ing : recipe.getIngredients()) {
            if (ing.isEmpty()) continue;

            ItemStack[] items = ing.getItems();
            if (items.length == 0) continue;

            // Check that all items within this ingredient are the same type
            Item firstItem = items[0].getItem();
            for (int i = 1; i < items.length; i++) {
                if (items[i].getItem() != firstItem) {
                    // Ingredient matches multiple item types (e.g., a tag) → skip recipe
                    return null;
                }
            }

            if (commonItem == null) {
                commonItem = firstItem;
            } else if (commonItem != firstItem) {
                // Different ingredients match different items → skip recipe
                return null;
            }
        }

        return commonItem;
    }

    /**
     * Count the number of non-empty ingredient slots in a recipe.
     */
    private static int countNonEmptyIngredients(CraftingRecipe recipe) {
        int count = 0;
        for (Ingredient ing : recipe.getIngredients()) {
            if (!ing.isEmpty()) count++;
        }
        return count;
    }

    /**
     * Build a reverse recipe map: for each item, list all single-material
     * recipes that produce it. The map is built once and reused across
     * multiple planning cycles.
     *
     * @param recipeManager  the client's recipe manager
     * @param registryAccess registry access for result item lookups
     * @return map from output item → list of recipes that produce it
     */
    public static Map<Item, List<RecipeEdge>> buildReverseRecipeMap(
        RecipeManager recipeManager,
        HolderLookup.Provider registryAccess
    ) {
        Map<Item, List<RecipeEdge>> reverseMap = new HashMap<>();
        List<RecipeHolder<CraftingRecipe>> allRecipes = recipeManager.getAllRecipesFor(RecipeType.CRAFTING);

        for (RecipeHolder<CraftingRecipe> holder : allRecipes) {
            CraftingRecipe recipe = holder.value();

            Item inputItem = getSingleIngredientItem(recipe);
            if (inputItem == null) continue;

            int inputCount = countNonEmptyIngredients(recipe);

            ItemStack resultStack;
            try {
                resultStack = recipe.getResultItem(registryAccess);
            } catch (Exception e) {
                continue;
            }

            if (resultStack.isEmpty()) continue;

            Item outputItem = resultStack.getItem();
            int outputCount = resultStack.getCount();

            if (inputItem == outputItem) continue;

            RecipeEdge edge = new RecipeEdge(inputItem, outputItem, inputCount, outputCount, holder.id());
            reverseMap.computeIfAbsent(outputItem, k -> new ArrayList<>()).add(edge);
        }

        return reverseMap;
    }
}
