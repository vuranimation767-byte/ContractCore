package com.blockbuilder.contractcore.managers;

import com.blockbuilder.contractcore.models.Contract;
import com.blockbuilder.contractcore.models.ContractConfig;
import com.blockbuilder.contractcore.models.Difficulty;
import com.blockbuilder.contractcore.models.Objective;
import com.blockbuilder.contractcore.models.ObjectiveType;
import com.blockbuilder.contractcore.models.Reward;
import com.blockbuilder.contractcore.utils.ConfigException;
import com.blockbuilder.contractcore.utils.TypeValidator;

import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.Set;

/**
 * Parses and validates the config.yml root map. The loader is deliberately free
 * of server state so it can run against a parsed YAML map in tests; the plugin
 * performs the actual file reading.
 */
public final class ConfigLoader {

    private static final Pattern ID_PATTERN = Pattern.compile("[a-z0-9_-]+");
    private static final int MAX_AMOUNT = 1_000_000;
    private static final Set<String> ROOT_KEYS = Set.of("settings", "contracts");
    private static final Set<String> SETTINGS_KEYS = Set.of("max-active-contracts", "autosave-seconds");
    private static final Set<String> CONTRACT_KEYS = Set.of(
            "id", "name", "description", "objective", "difficulty", "enabled", "repeatable", "cooldown-seconds", "reward");
    private static final Set<String> OBJECTIVE_KEYS = Set.of("type", "target", "amount");
    private static final Set<String> REWARD_KEYS = Set.of("exp");

    private final TypeValidator types;

    public ConfigLoader(TypeValidator types) {
        this.types = types;
    }

    /**
     * YamlConfiguration.getValues returns nested sections as ConfigurationSection
     * instances rather than Maps; this converts the whole tree to plain Maps and
     * Lists before parsing.
     */
    public static Map<String, Object> normalize(Map<String, Object> values) {
        Map<String, Object> root = new HashMap<>();
        values.forEach((key, value) -> root.put(key, plain(value)));
        return root;
    }

    private static Object plain(Object value) {
        if (value instanceof ConfigurationSection section) {
            Map<String, Object> map = new HashMap<>();
            for (String key : section.getKeys(false)) {
                map.put(key, plain(section.get(key)));
            }
            return map;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(ConfigLoader::plain).toList();
        }
        return value;
    }

    public ContractConfig parse(Map<String, Object> root) throws ConfigException {
        List<String> errors = new ArrayList<>();
        for (Object key : root.keySet()) {
            if (!(key instanceof String string) || !ROOT_KEYS.contains(string)) {
                errors.add("Unknown top-level key '" + key + "' (allowed: settings, contracts)");
            }
        }
        ContractConfig.Settings settings = parseSettings(root, errors);
        List<Contract> contracts = parseContracts(root, errors);
        if (!errors.isEmpty()) {
            throw new ConfigException("Invalid config.yml:\n  - " + String.join("\n  - ", errors));
        }
        return new ContractConfig(settings, contracts);
    }

    private ContractConfig.Settings parseSettings(Map<String, Object> root, List<String> errors) {
        Map<?, ?> settings = asMap(root.get("settings"), "settings", errors);
        if (settings == null) {
            return new ContractConfig.Settings(3, 20);
        }
        rejectUnknownKeys(settings, SETTINGS_KEYS, "settings", errors);
        int maxActive = intField(settings, "max-active-contracts", "settings", errors, 3, 1, 100);
        int autosave = intField(settings, "autosave-seconds", "settings", errors, 20, 5, 3600);
        return new ContractConfig.Settings(maxActive, autosave);
    }

    private List<Contract> parseContracts(Map<String, Object> root, List<String> errors) {
        Object raw = root.get("contracts");
        if (raw == null) {
            errors.add("Missing 'contracts' list");
            return List.of();
        }
        if (!(raw instanceof List<?> list)) {
            errors.add("'contracts' must be a list of mappings");
            return List.of();
        }
        List<Contract> contracts = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < list.size(); i++) {
            String context = "contracts[" + i + "]";
            Map<?, ?> map = asMap(list.get(i), context, errors);
            if (map == null) {
                continue;
            }
            rejectUnknownKeys(map, CONTRACT_KEYS, context, errors);
            Object rawId = map.get("id");
            if (rawId instanceof String id && !ids.add(id)) {
                errors.add(context + ": duplicate contract id '" + id + "'");
            }
            Contract contract = parseContract(map, context, errors);
            if (contract != null) {
                contracts.add(contract);
            }
        }
        return contracts;
    }

    private Contract parseContract(Map<?, ?> map, String context, List<String> errors) {
        String id = stringField(map, "id", context, errors, null);
        if (id == null) {
            errors.add(context + ": missing 'id'");
        } else if (!ID_PATTERN.matcher(id).matches()) {
            errors.add(context + ": id '" + id + "' must be lowercase and only contain a-z, 0-9, _ and -");
            id = null;
        }
        String name = stringField(map, "name", context, errors, null);
        if (name == null) {
            errors.add(context + ": missing 'name'");
            name = "";
        }
        String description = stringField(map, "description", context, errors, "");
        Objective objective = parseObjective(map, context, errors);
        Difficulty difficulty = parseEnum(map, "difficulty", context, errors, Difficulty.class);
        boolean enabled = boolField(map, "enabled", context, errors, true);
        boolean repeatable = boolField(map, "repeatable", context, errors, false);
        int cooldown = intField(map, "cooldown-seconds", context, errors, 0, 0, Integer.MAX_VALUE);
        if (cooldown > 0 && !repeatable) {
            errors.add(context + ": 'cooldown-seconds' requires 'repeatable: true'");
        }
        Reward reward = parseReward(map, context, errors);
        if (id == null || objective == null || difficulty == null) {
            return null;
        }
        return new Contract(id, name, description, objective, difficulty, reward, enabled, repeatable, cooldown);
    }

    private Objective parseObjective(Map<?, ?> contractMap, String context, List<String> errors) {
        String objectiveContext = context + ".objective";
        Map<?, ?> map = asMap(contractMap.get("objective"), objectiveContext, errors);
        if (map == null) {
            errors.add(objectiveContext + ": missing");
            return null;
        }
        rejectUnknownKeys(map, OBJECTIVE_KEYS, objectiveContext, errors);
        String rawType = stringField(map, "type", objectiveContext, errors, null);
        ObjectiveType type = null;
        if (rawType != null) {
            try {
                type = ObjectiveType.valueOf(rawType.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException unknown) {
                errors.add(objectiveContext + ": unknown type '" + rawType + "' (allowed: BREAK_BLOCK, KILL_MOB, COLLECT_ITEM)");
            }
        } else {
            errors.add(objectiveContext + ": missing 'type'");
        }
        String target = stringField(map, "target", objectiveContext, errors, null);
        if (target == null) {
            errors.add(objectiveContext + ": missing 'target'");
        } else if (type != null) {
            boolean valid = switch (type) {
                case BREAK_BLOCK, COLLECT_ITEM -> types.isMaterial(target);
                case KILL_MOB -> types.isEntity(target);
            };
            if (!valid) {
                errors.add(objectiveContext + ": '" + target + "' is not a valid " + (type == ObjectiveType.KILL_MOB ? "entity" : "material"));
            }
        }
        Integer amount = intField(map, "amount", objectiveContext, errors, null, 1, MAX_AMOUNT);
        if (amount == null) {
            if (!map.containsKey("amount")) {
                errors.add(objectiveContext + ": missing 'amount'");
            }
            return null;
        }
        if (type == null || target == null) {
            return null;
        }
        return new Objective(type, target.toUpperCase(Locale.ROOT), amount);
    }

    private Reward parseReward(Map<?, ?> contractMap, String context, List<String> errors) {
        String rewardContext = context + ".reward";
        Object raw = contractMap.get("reward");
        if (raw == null) {
            return new Reward(0);
        }
        Map<?, ?> map = asMap(raw, rewardContext, errors);
        if (map == null) {
            return new Reward(0);
        }
        rejectUnknownKeys(map, REWARD_KEYS, rewardContext, errors);
        return new Reward(intField(map, "exp", rewardContext, errors, 0, 0, MAX_AMOUNT));
    }

    private static <E extends Enum<E>> E parseEnum(Map<?, ?> map, String key, String context, List<String> errors, Class<E> type) {
        String value = stringField(map, key, context, errors, null);
        if (value == null) {
            return null;
        }
        try {
            return Enum.valueOf(type, value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            errors.add(context + ": unknown " + key + " '" + value + "'");
            return null;
        }
    }

    private static Map<?, ?> asMap(Object value, String context, List<String> errors) {
        if (value == null) {
            return null;
        }
        if (value instanceof Map<?, ?> map) {
            return map;
        }
        errors.add(context + ": must be a mapping");
        return null;
    }

    private static void rejectUnknownKeys(Map<?, ?> map, Set<String> allowed, String context, List<String> errors) {
        for (Object key : map.keySet()) {
            if (!(key instanceof String string) || !allowed.contains(string)) {
                errors.add(context + ": unknown key '" + key + "'");
            }
        }
    }

    private static String stringField(Map<?, ?> map, String key, String context, List<String> errors, String fallback) {
        Object value = map.get(key);
        if (value == null) {
            return fallback;
        }
        if (value instanceof String string && !string.isBlank()) {
            return string;
        }
        errors.add(context + ": '" + key + "' must be a non-blank string");
        return fallback;
    }

    private static Integer intField(Map<?, ?> map, String key, String context, List<String> errors, Integer fallback, int min, int max) {
        Object value = map.get(key);
        if (value == null) {
            return fallback;
        }
        if (value instanceof Number number && number.doubleValue() == Math.floor(number.doubleValue()) && Double.isFinite(number.doubleValue())) {
            int parsed = number.intValue();
            if (parsed >= min && parsed <= max) {
                return parsed;
            }
            errors.add(context + ": '" + key + "' must be between " + min + " and " + max);
            return fallback;
        }
        errors.add(context + ": '" + key + "' must be a whole number");
        return fallback;
    }

    private static boolean boolField(Map<?, ?> map, String key, String context, List<String> errors, boolean fallback) {
        Object value = map.get(key);
        if (value == null) {
            return fallback;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        errors.add(context + ": '" + key + "' must be true or false");
        return fallback;
    }
}
