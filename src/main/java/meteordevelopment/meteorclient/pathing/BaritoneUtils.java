/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.pathing;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.IBaritoneProvider;
import baritone.api.Settings;
import baritone.api.pathing.goals.Goal;
import baritone.api.utils.BetterBlockPos;
import net.minecraft.block.Block;
import net.minecraft.util.math.BlockPos;

import java.lang.reflect.Array;
import java.util.List;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Optional;

public class BaritoneUtils {
    private static Method getSettingsMethod;
    private static Method getProviderMethod;
    private static Method getPrimaryBaritoneMethod;
    private static Method getSelectionManagerMethod;
    private static Field settingValueField;
    private static Field settingDefaultValueField;

    private static Method getSelectionsMethod;
    private static Method removeAllSelectionsMethod;
    private static Method sm_addSelectionMethod; // a(BetterBlockPos, BetterBlockPos)

    public static boolean IS_AVAILABLE = false;

    // Cached Methods
    private static Method getPathingBehaviorMethod;
    private static Method getCustomGoalProcessMethod;
    private static Method getMineProcessMethod;
    private static Method getBuilderProcessMethod;
    private static Method getInputOverrideHandlerMethod;
    private static Method getProcessNameMethod; // IBaritoneProcess.displayName0 (b)

    private static Method pb_isPathingMethod; // a (abstract boolean)
    private static Method pb_cancelEverythingMethod; // c (abstract boolean)
    private static Method pb_forceCancelMethod; // a (abstract void)
    private static Method pb_estimatedTicksToGoalMethod; // b (abstract Optional)

    private static Method pb_currentMethod; // a()->PathExecutor (or getCurrent?)

    private static Method getPathingControlManagerMethod;
    private static Method pcm_registerProcessMethod; // a(IBaritoneProcess)
    private static Method pcm_mostRecentInControlMethod; // a() -> Optional<IBaritoneProcess>

    private static Method proc_displayName0Method; // b() -> String

    private static Method cgp_setGoalAndPathMethod; // b(Goal)
    private static Method cgp_setGoalMethod; // a(Goal)
    private static Method cgp_pathMethod; // b()
    private static Method cgp_getGoalMethod; // a()

    private static Method mp_mineMethod; // a(int, Lookup)

    private static Method bp_clearAreaMethod; // a(BlockPos, BlockPos)

    private static Method ioh_clearAllKeysMethod; // a()
    private static Method ioh_setInputForceStateMethod; // a(Input, boolean)

    // Cached Classes
    private static Class<?> pathingBehaviorClass;
    private static Class<?> customGoalProcessClass;
    private static Class<?> mineProcessClass;
    private static Class<?> builderProcessClass;
    private static Class<?> inputOverrideHandlerClass;
    private static Class<?> lookupClass;
    private static Class<?> blockOptionalMetaClass;
    private static Constructor<?> blockOptionalMetaConstructor;
    private static Constructor<?> lookupConstructor;

    // BetterBlockPos
    private static Class<?> betterBlockPosClass;
    private static Constructor<?> betterBlockPosConstructor; // (BlockPos)

    private static Class<?> pathClass;
    private static Method pb_getCurrentMethod; // PathingBehavior.getCurrent() -> IPath
    private static Method p_nodesMethod; // IPath.nodes() -> List<BetterBlockPos>

    static {
        try {
            // Find BaritoneAPI.getSettings() -> returns baritone.api.Settings
            getSettingsMethod = Arrays.stream(BaritoneAPI.class.getMethods())
                    .filter(m -> m.getReturnType() == Settings.class)
                    .findFirst()
                    .orElseThrow(() -> new RuntimeException("Could not find getSettings method in BaritoneAPI"));

            // Find BaritoneAPI.getProvider() -> returns baritone.api.IBaritoneProvider
            getProviderMethod = Arrays.stream(BaritoneAPI.class.getMethods())
                    .filter(m -> m.getReturnType() == IBaritoneProvider.class)
                    .findFirst()
                    .orElseThrow(() -> new RuntimeException("Could not find getProvider method in BaritoneAPI"));

            // Find IBaritoneProvider.getPrimaryBaritone() -> returns baritone.api.IBaritone
            Class<?> providerClass = IBaritoneProvider.class;
            getPrimaryBaritoneMethod = Arrays.stream(providerClass.getMethods())
                    .filter(m -> m.getReturnType() == IBaritone.class)
                    .findFirst()
                    .orElse(null);

            // Find IBaritone methods (by return type names string check to avoid loading
            // implementation classes aggressively if possible, but forced here)
            // IBaritone returns concrete classes according to javap
            for (Method m : IBaritone.class.getMethods()) {
                String returnType = m.getReturnType().getName();
                if (returnType.endsWith("SelectionManager"))
                    getSelectionManagerMethod = m;
                else if (returnType.endsWith("PathingBehavior"))
                    getPathingBehaviorMethod = m;
                else if (returnType.endsWith("CustomGoalProcess"))
                    getCustomGoalProcessMethod = m;
                else if (returnType.endsWith("MineProcess"))
                    getMineProcessMethod = m;
                else if (returnType.endsWith("BuilderProcess"))
                    getBuilderProcessMethod = m;
                else if (returnType.endsWith("InputOverrideHandler"))
                    getInputOverrideHandlerMethod = m;
                else if (returnType.endsWith("PathingControlManager"))
                    getPathingControlManagerMethod = m;
            }

            // PathingControlManager methods
            if (getPathingControlManagerMethod != null) {
                Class<?> pcmClass = getPathingControlManagerMethod.getReturnType();
                // registerProcess is void a(IBaritoneProcess)
                // mostRecentInControl is Optional a()
                for (Method m : pcmClass.getMethods()) {
                    if (m.getName().equals("a") && m.getParameterCount() == 1
                            && m.getParameterTypes()[0].getName().endsWith("IBaritoneProcess")
                            && m.getReturnType() == void.class) {
                        pcm_registerProcessMethod = m;
                    } else if (m.getName().equals("a") && m.getParameterCount() == 0
                            && m.getReturnType() == Optional.class) {
                        pcm_mostRecentInControlMethod = m;
                    }
                }
            }

            // IBaritoneProcess methods
            Class<?> processClass = Class.forName("baritone.api.process.IBaritoneProcess");
            for (Method m : processClass.getMethods()) {
                if (m.getName().equals("b") && m.getParameterCount() == 0 && m.getReturnType() == String.class) {
                    proc_displayName0Method = m;
                }
            }

            // PathingBehavior methods (IPathingBehavior interface)
            Class<?> iPathingBehavior = Class.forName("baritone.api.behavior.IPathingBehavior");
            for (Method m : iPathingBehavior.getMethods()) {
                if (m.getName().equals("a") && m.getReturnType() == boolean.class && m.getParameterCount() == 0)
                    pb_isPathingMethod = m; // abstract boolean a()
                else if (m.getName().equals("c") && m.getReturnType() == boolean.class && m.getParameterCount() == 0)
                    pb_cancelEverythingMethod = m;
                else if (m.getName().equals("a") && m.getReturnType() == void.class && m.getParameterCount() == 0)
                    pb_forceCancelMethod = m;
                else if (m.getName().equals("b") && m.getReturnType() == Optional.class && m.getParameterCount() == 0)
                    pb_estimatedTicksToGoalMethod = m;
                // getCurrent? returns PathExecutor. class baritone.pathing.path.PathExecutor.
                else if (m.getReturnType().getName().endsWith("PathExecutor"))
                    pb_currentMethod = m;
            }

            // CustomGoalProcess methods (ICustomGoalProcess interface)
            Class<?> iCustomGoal = Class.forName("baritone.api.process.ICustomGoalProcess");
            for (Method m : iCustomGoal.getMethods()) {
                if (m.getName().equals("b") && m.getParameterCount() == 1)
                    cgp_setGoalAndPathMethod = m; // default void b(Goal)
                else if (m.getName().equals("a") && m.getParameterCount() == 1)
                    cgp_setGoalMethod = m; // void a(Goal)
                else if (m.getName().equals("b") && m.getParameterCount() == 0 && m.getReturnType() == void.class)
                    cgp_pathMethod = m; // void b()
                else if (m.getName().equals("a") && m.getParameterCount() == 0 && m.getReturnType() == Goal.class)
                    cgp_getGoalMethod = m; // Goal a()
            }

            // BuilderProcess methods (IBuilderProcess)
            Class<?> iBuilder = Class.forName("baritone.api.process.IBuilderProcess");
            for (Method m : iBuilder.getMethods()) {
                if (m.getName().equals("a") && m.getParameterCount() == 2) {
                    // Check param types strictly if possible, or assume (BlockPos, BlockPos)
                    // javap said a(class_2338, class_2338) -> void
                    // We just grab the one with 2 args (clearArea).
                    bp_clearAreaMethod = m;
                }
            }

            // MineProcess methods (IMineProcess interface)
            Class<?> iMine = Class.forName("baritone.api.process.IMineProcess");
            lookupClass = Class.forName("baritone.api.utils.BlockOptionalMetaLookup");
            blockOptionalMetaClass = Class.forName("baritone.api.utils.BlockOptionalMeta");
            blockOptionalMetaConstructor = blockOptionalMetaClass.getConstructor(Block.class);
            lookupConstructor = lookupClass.getConstructor(blockOptionalMetaClass.arrayType()); // Constructor(BlockOptionalMeta...)

            for (Method m : iMine.getMethods()) {
                if (m.getName().equals("a") && m.getParameterCount() == 2 && m.getParameterTypes()[1] == lookupClass)
                    mp_mineMethod = m; // a(int, Lookup)
            }

            // InputOverrideHandler (IInputOverrideHandler)
            Class<?> iInput = Class.forName("baritone.api.utils.IInputOverrideHandler");
            for (Method m : iInput.getMethods()) {
                if (m.getName().equals("a") && m.getParameterCount() == 0)
                    ioh_clearAllKeysMethod = m;
                else if (m.getName().equals("a") && m.getParameterCount() == 2)
                    ioh_setInputForceStateMethod = m;
            }

            // Find SelectionManager methods
            Class<?> selectionManagerClass = Class.forName("baritone.selection.SelectionManager");
            betterBlockPosClass = Class.forName("baritone.api.utils.BetterBlockPos");
            // Constructor(BlockPos)
            if (betterBlockPosClass != null) {
                for (Constructor<?> c : betterBlockPosClass.getConstructors()) {
                    if (c.getParameterCount() == 1) {
                        betterBlockPosConstructor = c;
                        break;
                    }
                }
            }

            if (selectionManagerClass != null) {
                Method[] methods = selectionManagerClass.getDeclaredMethods();
                for (Method m : methods) {
                    if (m.getReturnType().isArray()
                            && m.getReturnType().getComponentType().getName().contains("ISelection")) {
                        if (java.lang.reflect.Modifier.isSynchronized(m.getModifiers())) {
                            if (m.getParameterCount() == 0)
                                removeAllSelectionsMethod = m;
                        } else {
                            if (m.getParameterCount() == 0)
                                getSelectionsMethod = m;
                        }
                    }
                    if (m.getName().equals("a") && m.getParameterCount() == 2
                            && m.getParameterTypes()[0].getName().endsWith("BetterBlockPos")) {
                        sm_addSelectionMethod = m;
                    }
                }
            }

            // Find Settings.Setting.value and defaultValue
            Class<?> settingClass = Class.forName("baritone.api.Settings$Setting");
            settingValueField = Arrays.stream(settingClass.getDeclaredFields())
                    .filter(f -> !java.lang.reflect.Modifier.isFinal(f.getModifiers())
                            && !java.lang.reflect.Modifier.isStatic(f.getModifiers()))
                    .findFirst().orElse(null);
            settingDefaultValueField = Arrays.stream(settingClass.getDeclaredFields())
                    .filter(f -> java.lang.reflect.Modifier.isFinal(f.getModifiers())
                            && !java.lang.reflect.Modifier.isStatic(f.getModifiers()))
                    .findFirst().orElse(null);

            if (settingValueField != null)
                settingValueField.setAccessible(true);
            if (settingDefaultValueField != null)
                settingDefaultValueField.setAccessible(true);

            IS_AVAILABLE = true;

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static Settings getSettings() {
        try {
            return (Settings) getSettingsMethod.invoke(null);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static IBaritoneProvider getProvider() {
        try {
            return (IBaritoneProvider) getProviderMethod.invoke(null);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static IBaritone getPrimaryBaritone() {
        try {
            return (IBaritone) getPrimaryBaritoneMethod.invoke(getProvider());
        } catch (Exception e) {
            return null;
        }
    }

    public static Object getSelectionManager(IBaritone baritone) {
        try {
            if (getSelectionManagerMethod == null)
                return null;
            return getSelectionManagerMethod.invoke(baritone);
        } catch (Exception e) {
            return null;
        }
    }

    public static Object getSelectionManager(Object baritone) {
        return invoke(getSelectionManagerMethod, baritone);
    }

    public static Object[] getSelections(Object selectionManager) {
        try {
            if (getSelectionsMethod != null && selectionManager != null) {
                return (Object[]) getSelectionsMethod.invoke(selectionManager);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    public static void removeAllSelections(Object selectionManager) {
        try {
            if (removeAllSelectionsMethod != null && selectionManager != null) {
                removeAllSelectionsMethod.invoke(selectionManager);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void addSelection(Object selectionManager, BlockPos start, BlockPos end) {
        if (!IS_AVAILABLE)
            return;
        try {
            if (sm_addSelectionMethod != null && betterBlockPosConstructor != null) {
                Object bStart = betterBlockPosConstructor.newInstance(start);
                Object bEnd = betterBlockPosConstructor.newInstance(end);
                sm_addSelectionMethod.invoke(selectionManager, bStart, bEnd);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static Object getSettingValue(Object setting) {
        try {
            return settingValueField.get(setting);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static void setSettingValue(Object setting, Object value) {
        try {
            settingValueField.set(setting, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static Object getSettingDefaultValue(Object setting) {
        try {
            return settingDefaultValueField.get(setting);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static void setSetting(String name, Object value) {
        try {
            Settings settings = getSettings();
            Field field = settings.getClass().getDeclaredField(name);
            Object settingEntry = field.get(settings);
            // settingEntry is Settings.Setting<T>
            // set value
            setSettingValue(settingEntry, value);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static Object getSettingValue(String name) {
        try {
            Settings settings = getSettings();
            Field field = settings.getClass().getDeclaredField(name);
            Object settingEntry = field.get(settings);
            return getSettingValue(settingEntry);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    // NEW WRAPPERS

    public static Object getPathingBehavior(Object baritone) {
        return invoke(getPathingBehaviorMethod, baritone);
    }

    public static Object getCustomGoalProcess(Object baritone) {
        return invoke(getCustomGoalProcessMethod, baritone);
    }

    public static Object getMineProcess(Object baritone) {
        return invoke(getMineProcessMethod, baritone);
    }

    public static void saveSettings() {
        if (!IS_AVAILABLE)
            return;
        try {
            // baritone.api.utils.SettingsUtil.save(Settings)
            Class<?> settingsUtilClass = Class.forName("baritone.api.utils.SettingsUtil");
            Class<?> settingsClass = Class.forName("baritone.api.Settings");
            Method saveMethod = settingsUtilClass.getMethod("save", settingsClass);
            saveMethod.invoke(null, getSettings());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static Object createPathingCommandPause() {
        if (!IS_AVAILABLE)
            return null;
        try {
            // new PathingCommand(Goal, PathingCommandType)
            Class<?> commandClass = Class.forName("baritone.api.process.PathingCommand");
            Class<?> typeClass = Class.forName("baritone.api.process.PathingCommandType");
            Object pauseType = Enum.valueOf((Class<Enum>) typeClass, "REQUEST_PAUSE");
            Class<?> goalClass = Class.forName("baritone.api.pathing.goals.Goal");

            return commandClass.getConstructor(goalClass, typeClass).newInstance(null, pauseType);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    public static Object getInput(String name) {
        if (!IS_AVAILABLE)
            return null;
        try {
            Class<?> inputClass = Class.forName("baritone.api.utils.input.Input");
            return Enum.valueOf((Class<Enum>) inputClass, name);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    public static Object newBetterBlockPos(BlockPos pos) {
        if (!IS_AVAILABLE || betterBlockPosConstructor == null)
            return null;
        try {
            return betterBlockPosConstructor.newInstance(pos);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    public static Object getBuilderProcess(Object baritone) {
        return invoke(getBuilderProcessMethod, baritone);
    }

    public static void clearArea(Object builder, BlockPos start, BlockPos end) {
        invoke(bp_clearAreaMethod, builder, start, end);
    }

    public static boolean isBuilderActive(Object builder) {
        // Reuse isPathing logic (a())
        return isPathing(builder);
    }

    public static Object getInputOverrideHandler(Object baritone) {
        return invoke(getInputOverrideHandlerMethod, baritone);
    }

    public static Object getPathingControlManager(Object baritone) {
        return invoke(getPathingControlManagerMethod, baritone);
    }

    public static void registerProcess(Object pcm, Object process) {
        invoke(pcm_registerProcessMethod, pcm, process);
    }

    public static Optional<Object> getMostRecentInControl(Object pcm) {
        return (Optional<Object>) invoke(pcm_mostRecentInControlMethod, pcm);
    }

    public static String getProcessDisplayName0(Object process) {
        return (String) invoke(proc_displayName0Method, process);
    }

    // PathingBehavior
    public static boolean isPathing(Object behavior) {
        Boolean res = (Boolean) invoke(pb_isPathingMethod, behavior);
        return res != null && res;
    }

    public static void cancelEverything(Object behavior) {
        invoke(pb_cancelEverythingMethod, behavior);
    }

    public static void forceCancel(Object behavior) {
        invoke(pb_forceCancelMethod, behavior);
    }

    public static Optional<Double> estimatedTicksToGoal(Object behavior) {
        return (Optional<Double>) invoke(pb_estimatedTicksToGoalMethod, behavior);
    }

    public static Object getCurrentPath(Object behavior) {
        // returns IPath
        return invoke(pb_getCurrentMethod, behavior);
    }

    public static List<BetterBlockPos> getPathNodes(Object path) {
        if (path == null)
            return null;
        return (List<BetterBlockPos>) invoke(p_nodesMethod, path);
    }

    // CustomGoalProcess
    public static void setGoalAndPath(Object process, Goal goal) {
        invoke(cgp_setGoalAndPathMethod, process, goal);
    }

    public static void setGoal(Object process, Goal goal) {
        invoke(cgp_setGoalMethod, process, goal);
    }

    public static Goal getGoal(Object process) {
        return (Goal) invoke(cgp_getGoalMethod, process);
    }

    public static void path(Object process) {
        invoke(cgp_pathMethod, process);
    }

    // MineProcess
    public static void mine(Object process, Block... blocks) {
        try {
            if (process == null || mp_mineMethod == null)
                return;
            // Lookup(Block...) constructor? I found Lookup(BlockOptionalMeta...)
            // Convert Block... to BlockOptionalMeta...
            Object[] metaArray = (Object[]) Array.newInstance(blockOptionalMetaClass, blocks.length);
            for (int i = 0; i < blocks.length; i++) {
                metaArray[i] = blockOptionalMetaConstructor.newInstance(blocks[i]);
            }
            Object lookup = lookupConstructor.newInstance((Object) metaArray); // new Lookup(metaArray)
            mp_mineMethod.invoke(process, 0, lookup);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // InputOverrideHandler
    public static void clearAllKeys(Object handler) {
        invoke(ioh_clearAllKeysMethod, handler);
    }

    public static void setInputForceState(Object handler, Object input, boolean state) {
        if (!IS_AVAILABLE)
            return;
        try {
            // invoke(ioh_setInputForceStateMethod, handler, input, state); // This expects
            // Input type if ref is strongly typed
            // We need to look up method dynamically if we don't have Input class at compile
            // time?
            // But we do have Baritone API on classpath?
            // If Input is not resolved, use reflection lookup.
            Class<?> inputClass = Class.forName("baritone.api.utils.input.Input");
            Method method = handler.getClass().getMethod("setInputForceState", inputClass, boolean.class);
            method.invoke(handler, input, state);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static double getGoalHeuristic(Object goal, BlockPos pos) {
        if (!IS_AVAILABLE || goal == null || pos == null)
            return 0;
        try {
            // goal.heuristic(x, y, z)
            Method method = goal.getClass().getMethod("heuristic", int.class, int.class, int.class);
            return (double) method.invoke(goal, pos.getX(), pos.getY(), pos.getZ());
        } catch (Exception e) {
            return 0;
        }
    }

    private static Object invoke(Method m, Object instance, Object... args) {
        try {
            if (m != null && instance != null)
                return m.invoke(instance, args);
        } catch (Exception e) {
            // e.printStackTrace(); // Suppress spam?
        }
        return null;
    }

    // Missing methods restored
    public static String getPrefix() {
        try {
            Settings settings = getSettings();
            Field prefixField = settings.getClass().getField("prefix");
            Object prefixSetting = prefixField.get(settings);
            return (String) getSettingValue(prefixSetting);
        } catch (Exception e) {
            return "#";
        }
    }

    public static void acquireMiningProtection() {
    }

    public static void releaseMiningProtection() {
    }

    public static void acquireContainerProtection() {
    }

    public static void releaseContainerProtection() {
    }
}
