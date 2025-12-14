/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.autocraft;

import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.recipe.CraftingRecipe;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.lang.reflect.Method;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public final class AutoCraftPlanner {
    private AutoCraftPlanner() {
    }

    private static final int MAX_RECURSION_DEPTH = 6;

    public record MissingLine(Item item, int needed, int inInventory, int inChests, int missing) {
    }

    public record CraftLine(Item item, int needed) {
    }

    public record Plan(List<MissingLine> missingLeaves, List<CraftLine> intermediateCrafts) {
    }

    public static Plan computePlan(Item target, int targetCount) {
        if (mc.world == null || mc.player == null) return new Plan(List.of(), List.of());
        if (target == null || targetCount <= 0) return new Plan(List.of(), List.of());

        Map<Item, Object> recipeByOutput = indexCraftingRecipes();

        Object2IntOpenHashMap<Item> available = new Object2IntOpenHashMap<>();
        seedAvailableCounts(available);

        Object2IntOpenHashMap<Item> missingLeafCounts = new Object2IntOpenHashMap<>();
        Object2IntOpenHashMap<Item> craftNeeds = new Object2IntOpenHashMap<>();

        planMissingRecursive(
            target,
            targetCount,
            MAX_RECURSION_DEPTH,
            recipeByOutput,
            available,
            missingLeafCounts,
            craftNeeds,
            new HashSet<>()
        );

        AutoCraftMemory mem = AutoCraftMemory.get();
        Object2IntOpenHashMap<Item> inv = inventoryCounts();

        List<MissingLine> missingLines = new ArrayList<>();
        for (var e : missingLeafCounts.object2IntEntrySet()) {
            Item item = e.getKey();
            int need = e.getIntValue();
            int haveInv = inv.getOrDefault(item, 0);
            int haveChest = mem != null ? mem.countInChests(item) : 0;
            int miss = Math.max(0, need - haveInv - haveChest);
            if (miss > 0) missingLines.add(new MissingLine(item, need, haveInv, haveChest, miss));
        }
        missingLines.sort(Comparator.comparingInt(MissingLine::missing).reversed());

        List<CraftLine> craftLines = new ArrayList<>();
        for (var e : craftNeeds.object2IntEntrySet()) {
            Item item = e.getKey();
            int need = e.getIntValue();
            if (item != null && need > 0) craftLines.add(new CraftLine(item, need));
        }
        craftLines.sort(Comparator.comparingInt(CraftLine::needed).reversed());

        return new Plan(missingLines, craftLines);
    }

    public static Object findCraftingRecipeEntryFor(Item target) {
        if (mc.world == null || target == null) return null;

        try {
            Object recipeManager = mc.world.getRecipeManager();
            if (recipeManager == null) return null;

            for (Object entry : tryIterateAllRecipeEntries(recipeManager)) {
                Object recipe = unwrapRecipeEntry(entry);
                if (recipe == null) continue;
                if (!isCraftingRecipe(recipe)) continue;

                ItemStack out = tryGetRecipeResult(recipe);
                if (!out.isEmpty() && out.getItem() == target) return entry;
            }
        } catch (Throwable ignored) {
        }

        return null;
    }

    public static Object unwrapRecipeFromEntry(Object entry) {
        return unwrapRecipeEntry(entry);
    }

    public static List<MissingLine> computeMissing(Item target, int targetCount) {
        return computePlan(target, targetCount).missingLeaves();
    }

    private static Map<Item, Object> indexCraftingRecipes() {
        Map<Item, Object> out = new HashMap<>();
        if (mc.world == null) return out;

        try {
            Object recipeManager = mc.world.getRecipeManager();
            if (recipeManager == null) return out;

            for (Object entry : tryIterateAllRecipeEntries(recipeManager)) {
                Object recipe = unwrapRecipeEntry(entry);
                if (recipe == null) continue;

                // Only index actual crafting recipes. (Other recipe types break clickRecipe-based crafting.)
                if (!isCraftingRecipe(recipe)) continue;

                ItemStack result = tryGetRecipeResult(recipe);
                if (result.isEmpty()) continue;

                out.putIfAbsent(result.getItem(), recipe);
            }
        } catch (Throwable ignored) {
            // Best-effort; recipe APIs change across versions.
        }

        return out;
    }

    private static boolean isCraftingRecipe(Object recipe) {
        return recipe instanceof CraftingRecipe;
    }

    private static void planMissingRecursive(
        Item item,
        int neededCount,
        int depth,
        Map<Item, Object> recipeByOutput,
        Object2IntOpenHashMap<Item> available,
        Object2IntOpenHashMap<Item> missingLeaves,
        Object2IntOpenHashMap<Item> craftNeeds,
        Set<Item> recursionStack
    ) {
        if (item == null || neededCount <= 0) return;

        // Consume from shared availability first.
        int have = available.getInt(item);
        if (have > 0) {
            int consume = Math.min(have, neededCount);
            available.put(item, have - consume);
            neededCount -= consume;
            if (neededCount <= 0) return;
        }

        if (depth <= 0) {
            missingLeaves.addTo(item, neededCount);
            return;
        }

        if (!recursionStack.add(item)) {
            // Cycle.
            missingLeaves.addTo(item, neededCount);
            return;
        }

        try {
            Object recipe = recipeByOutput.get(item);
            if (recipe == null) {
                missingLeaves.addTo(item, neededCount);
                return;
            }

            int requiredNow = neededCount;
            if (requiredNow > 0) craftNeeds.addTo(item, requiredNow);

            ItemStack result = tryGetRecipeResult(recipe);
            int outCount = Math.max(1, result.isEmpty() ? 1 : result.getCount());
            int crafts = (int) Math.ceil(neededCount / (double) outCount);

            int produced = crafts * outCount;
            int surplus = produced - neededCount;
            if (surplus > 0) available.addTo(item, surplus);

            List<?> ingredients = tryGetRecipeIngredients(recipe);
            if (ingredients.isEmpty()) {
                missingLeaves.addTo(item, neededCount);
                return;
            }

            // Each non-empty ingredient entry is one item per craft.
            for (Object ing : ingredients) {
                if (ing == null) continue;
                if (isIngredientEmpty(ing)) continue;

                Item chosen = chooseBestCandidate(ing);
                if (chosen == null) continue;

                planMissingRecursive(chosen, crafts, depth - 1, recipeByOutput, available, missingLeaves, craftNeeds, recursionStack);
            }
        } finally {
            recursionStack.remove(item);
        }
    }

    private static void seedAvailableCounts(Object2IntOpenHashMap<Item> available) {
        Object2IntOpenHashMap<Item> inv = inventoryCounts();
        for (var e : inv.object2IntEntrySet()) {
            available.addTo(e.getKey(), e.getIntValue());
        }

        AutoCraftMemory mem = AutoCraftMemory.get();
        if (mem != null) {
            Object2IntOpenHashMap<Item> all = mem.aggregateAllItems();
            for (var e : all.object2IntEntrySet()) {
                available.addTo(e.getKey(), e.getIntValue());
            }
        }
    }

    private static Object2IntOpenHashMap<Item> inventoryCounts() {
        Object2IntOpenHashMap<Item> out = new Object2IntOpenHashMap<>();
        int total = Math.min(36, mc.player.getInventory().size());

        for (int i = 0; i < total; i++) {
            ItemStack s = mc.player.getInventory().getStack(i);
            if (s == null || s.isEmpty()) continue;
            out.addTo(s.getItem(), s.getCount());
        }

        return out;
    }

    private static Item chooseBestCandidate(Object ingredient) {
        ItemStack[] stacks = tryGetIngredientMatchingStacks(ingredient);
        if (stacks == null || stacks.length == 0) return null;

        // Prefer the candidate we already have most of (inv + remembered chests).
        Object2IntOpenHashMap<Item> inv = inventoryCounts();
        AutoCraftMemory mem = AutoCraftMemory.get();

        int bestScore = -1;
        Item best = null;

        for (ItemStack s : stacks) {
            if (s == null || s.isEmpty()) continue;
            Item item = s.getItem();
            int score = inv.getOrDefault(item, 0) + (mem != null ? mem.countInChests(item) : 0);
            if (score > bestScore) {
                bestScore = score;
                best = item;
            }
        }

        if (best != null) return best;

        // Fallback: first valid stack.
        for (ItemStack s : stacks) {
            if (s != null && !s.isEmpty()) return s.getItem();
        }

        return null;
    }

    private static Iterable<?> tryIterateAllRecipeEntries(Object recipeManager) {
        // Try a few common API shapes via reflection.
        Object values = invokeNoArg(recipeManager, "values");
        if (values instanceof Iterable<?> it) return it;

        Object recipes = invokeNoArg(recipeManager, "getRecipes");
        if (recipes instanceof Iterable<?> it) return it;

        // Some versions expose a map-like accessor.
        Object map = invokeNoArg(recipeManager, "getRecipes" , Class.class);
        if (map instanceof Iterable<?> it) return it;

        return List.of();
    }

    private static Object unwrapRecipeEntry(Object entry) {
        if (entry == null) return null;

        // Yarn often has RecipeEntry#value().
        Object v = invokeNoArg(entry, "value");
        if (v != null) return v;

        // Some shapes use getValue().
        v = invokeNoArg(entry, "getValue");
        if (v != null) return v;

        // Some iterate recipes directly.
        return entry;
    }

    private static ItemStack tryGetRecipeResult(Object recipe) {
        if (recipe == null || mc.world == null) return ItemStack.EMPTY;

        // Try common method names/signatures.
        Object out = invokeWithOneArg(recipe, "getResult", mc.world.getRegistryManager());
        if (out instanceof ItemStack s) return s;

        out = invokeNoArg(recipe, "getResult");
        if (out instanceof ItemStack s) return s;

        out = invokeWithOneArg(recipe, "getOutput", mc.world.getRegistryManager());
        if (out instanceof ItemStack s) return s;

        out = invokeNoArg(recipe, "getOutput");
        if (out instanceof ItemStack s) return s;

        return ItemStack.EMPTY;
    }

    private static List<?> tryGetRecipeIngredients(Object recipe) {
        if (recipe == null) return List.of();

        Object ing = invokeNoArg(recipe, "getIngredients");
        if (ing instanceof List<?> list) return list;

        // Some shapes return a DefaultedList or Collection.
        if (ing instanceof Iterable<?> it) {
            ArrayList<Object> out = new ArrayList<>();
            for (Object o : it) out.add(o);
            return out;
        }

        return List.of();
    }

    private static boolean isIngredientEmpty(Object ingredient) {
        if (ingredient == null) return true;
        Object empty = invokeNoArg(ingredient, "isEmpty");
        return empty instanceof Boolean b && b;
    }

    private static ItemStack[] tryGetIngredientMatchingStacks(Object ingredient) {
        if (ingredient == null) return new ItemStack[0];

        Object stacks = invokeNoArg(ingredient, "getMatchingStacks");
        if (stacks instanceof ItemStack[] arr) return arr;

        stacks = invokeNoArg(ingredient, "getMatchingStacksClient");
        if (stacks instanceof ItemStack[] arr) return arr;

        stacks = invokeNoArg(ingredient, "getMatchingStacks" , Object.class);
        if (stacks instanceof ItemStack[] arr) return arr;

        return new ItemStack[0];
    }

    private static Object invokeNoArg(Object target, String name) {
        if (target == null) return null;
        try {
            Method m = target.getClass().getMethod(name);
            return m.invoke(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object invokeNoArg(Object target, String name, Class<?> ignoredParam) {
        // Compatibility helper: ignore param and just attempt no-arg.
        return invokeNoArg(target, name);
    }

    private static Object invokeWithOneArg(Object target, String name, Object arg) {
        if (target == null || arg == null) return null;
        try {
            for (Method m : target.getClass().getMethods()) {
                if (!m.getName().equals(name)) continue;
                if (m.getParameterCount() != 1) continue;
                if (!m.getParameterTypes()[0].isAssignableFrom(arg.getClass())) continue;
                return m.invoke(target, arg);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    public static Item tryParseItemId(String text) {
        if (text == null) return null;
        String v = text.trim();
        if (v.isEmpty()) return null;

        try {
            Identifier id = Identifier.of(v);
            if (!Registries.ITEM.containsId(id)) return null;
            return Registries.ITEM.get(id);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
