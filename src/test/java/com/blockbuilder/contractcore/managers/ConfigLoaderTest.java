package com.blockbuilder.contractcore.managers;

import com.blockbuilder.contractcore.models.Contract;
import com.blockbuilder.contractcore.models.ContractConfig;
import com.blockbuilder.contractcore.models.Difficulty;
import com.blockbuilder.contractcore.models.ObjectiveType;
import com.blockbuilder.contractcore.utils.ConfigException;
import com.blockbuilder.contractcore.utils.TestTypeValidator;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

class ConfigLoaderTest {

    private final ConfigLoader loader = new ConfigLoader(new TestTypeValidator());

    private ContractConfig parse(String yaml) throws ConfigException {
        Map<String, Object> root = new Yaml().load(yaml);
        return loader.parse(root);
    }

    private ConfigException failure(String yaml) {
        return assertThrows(ConfigException.class, () -> parse(yaml));
    }

    private static final String VALID = """
            settings:
              max-active-contracts: 2
              autosave-seconds: 15
            contracts:
              - id: stone-digger
                name: "Stone Digger"
                description: "Break stone."
                objective:
                  type: BREAK_BLOCK
                  target: STONE
                  amount: 64
                difficulty: EASY
                enabled: true
                repeatable: false
                reward:
                  exp: 150
              - id: zombie-hunter
                name: "Zombie Hunter"
                objective:
                  type: KILL_MOB
                  target: zombie
                  amount: 20
                difficulty: medium
                repeatable: true
                cooldown-seconds: 3600
                reward:
                  exp: 400
            """;

    @Test
    void validConfigParses() throws ConfigException {
        ContractConfig config = parse(VALID);
        assertEquals(2, config.settings().maxActiveContracts());
        assertEquals(15, config.settings().autosaveSeconds());
        assertEquals(2, config.contracts().size());
        Contract first = config.contracts().get(0);
        assertEquals("stone-digger", first.id());
        assertEquals(ObjectiveType.BREAK_BLOCK, first.objective().type());
        assertEquals("STONE", first.objective().target());
        assertEquals(Difficulty.EASY, first.difficulty());
        assertEquals(150, first.reward().exp());
        assertTrue(first.enabled());
        assertFalse(first.repeatable());
        Contract second = config.contracts().get(1);
        assertEquals(Difficulty.MEDIUM, second.difficulty());
        assertEquals("ZOMBIE", second.objective().target());
        assertEquals(3600, second.cooldownSeconds());
    }

    @Test
    void normalizesYamlConfigurationSectionsLikeThePluginDoes() throws ConfigException {
        // YamlConfiguration.getValues returns ConfigurationSection, not Map, for
        // nested nodes; this is the exact shape ContractCorePlugin feeds the loader.
        var yaml = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(new java.io.StringReader(VALID));
        ContractConfig config = loader.parse(ConfigLoader.normalize(yaml.getValues(false)));
        assertEquals(2, config.contracts().size());
        assertEquals(2, config.settings().maxActiveContracts());
        assertEquals("STONE", config.contracts().get(0).objective().target());
    }

    @Test
    void missingSettingsUsesDefaults() throws ConfigException {
        ContractConfig config = parse("""
                contracts:
                  - id: a
                    name: "A"
                    objective: {type: BREAK_BLOCK, target: STONE, amount: 1}
                    difficulty: EASY
                """);
        assertEquals(3, config.settings().maxActiveContracts());
        assertEquals(20, config.settings().autosaveSeconds());
        assertTrue(config.contracts().get(0).enabled());
        assertEquals(0, config.contracts().get(0).reward().exp());
    }

    @Test
    void duplicateIdsRejected() {
        ConfigException ex = failure("""
                contracts:
                  - id: dup
                    name: "A"
                    objective: {type: BREAK_BLOCK, target: STONE, amount: 1}
                    difficulty: EASY
                  - id: dup
                    name: "B"
                    objective: {type: BREAK_BLOCK, target: STONE, amount: 1}
                    difficulty: EASY
                """);
        assertTrue(ex.getMessage().contains("duplicate"));
    }

    @Test
    void unknownMaterialRejected() {
        ConfigException ex = failure("""
                contracts:
                  - id: a
                    name: "A"
                    objective: {type: BREAK_BLOCK, target: BEDROCK_LASER, amount: 1}
                    difficulty: EASY
                """);
        assertTrue(ex.getMessage().contains("not a valid material"));
    }

    @Test
    void unknownEntityRejected() {
        ConfigException ex = failure("""
                contracts:
                  - id: a
                    name: "A"
                    objective: {type: KILL_MOB, target: STEVE, amount: 1}
                    difficulty: EASY
                """);
        assertTrue(ex.getMessage().contains("not a valid entity"));
    }

    @Test
    void unknownObjectiveTypeRejected() {
        ConfigException ex = failure("""
                contracts:
                  - id: a
                    name: "A"
                    objective: {type: EAT_CAKE, target: STONE, amount: 1}
                    difficulty: EASY
                """);
        assertTrue(ex.getMessage().contains("unknown type"));
    }

    @Test
    void unknownDifficultyRejected() {
        ConfigException ex = failure("""
                contracts:
                  - id: a
                    name: "A"
                    objective: {type: BREAK_BLOCK, target: STONE, amount: 1}
                    difficulty: NIGHTMARE
                """);
        assertTrue(ex.getMessage().contains("unknown difficulty"));
    }

    @Test
    void negativeAmountRejected() {
        ConfigException ex = failure("""
                contracts:
                  - id: a
                    name: "A"
                    objective: {type: BREAK_BLOCK, target: STONE, amount: -5}
                    difficulty: EASY
                """);
        assertTrue(ex.getMessage().contains("between 1 and 1000000"));
    }

    @Test
    void fractionalAmountRejected() {
        ConfigException ex = failure("""
                contracts:
                  - id: a
                    name: "A"
                    objective: {type: BREAK_BLOCK, target: STONE, amount: 64.5}
                    difficulty: EASY
                """);
        assertTrue(ex.getMessage().contains("whole number"));
    }

    @Test
    void missingAmountRejected() {
        ConfigException ex = failure("""
                contracts:
                  - id: a
                    name: "A"
                    objective: {type: BREAK_BLOCK, target: STONE}
                    difficulty: EASY
                """);
        assertTrue(ex.getMessage().contains("missing 'amount'"));
    }

    @Test
    void invalidIdCharactersRejected() {
        ConfigException ex = failure("""
                contracts:
                  - id: "Stone Digger"
                    name: "A"
                    objective: {type: BREAK_BLOCK, target: STONE, amount: 1}
                    difficulty: EASY
                """);
        assertTrue(ex.getMessage().contains("lowercase"));
    }

    @Test
    void missingNameRejected() {
        ConfigException ex = failure("""
                contracts:
                  - id: a
                    objective: {type: BREAK_BLOCK, target: STONE, amount: 1}
                    difficulty: EASY
                """);
        assertTrue(ex.getMessage().contains("missing 'name'"));
    }

    @Test
    void cooldownWithoutRepeatableRejected() {
        ConfigException ex = failure("""
                contracts:
                  - id: a
                    name: "A"
                    objective: {type: BREAK_BLOCK, target: STONE, amount: 1}
                    difficulty: EASY
                    cooldown-seconds: 60
                """);
        assertTrue(ex.getMessage().contains("requires 'repeatable: true'"));
    }

    @Test
    void negativeExpRejected() {
        ConfigException ex = failure("""
                contracts:
                  - id: a
                    name: "A"
                    objective: {type: BREAK_BLOCK, target: STONE, amount: 1}
                    difficulty: EASY
                    reward: {exp: -10}
                """);
        assertTrue(ex.getMessage().contains("between 0 and 1000000"));
    }

    @Test
    void unknownTopLevelKeyRejected() {
        ConfigException ex = failure("""
                contracts: []
                economy: {enabled: true}
                """);
        assertTrue(ex.getMessage().contains("Unknown top-level key"));
    }

    @Test
    void unknownContractKeyRejected() {
        ConfigException ex = failure("""
                contracts:
                  - id: a
                    name: "A"
                    objective: {type: BREAK_BLOCK, target: STONE, amount: 1}
                    difficulty: EASY
                    secret-flag: true
                """);
        assertTrue(ex.getMessage().contains("unknown key 'secret-flag'"));
    }

    @Test
    void contractsMustBeAList() {
        ConfigException ex = failure("contracts: not-a-list");
        assertTrue(ex.getMessage().contains("must be a list"));
    }

    @Test
    void contractEntryMustBeAMap() {
        ConfigException ex = failure("""
                contracts:
                  - just-a-string
                """);
        assertTrue(ex.getMessage().contains("must be a mapping"));
    }

    @Test
    void emptyContractListIsAllowed() throws ConfigException {
        ContractConfig config = parse("contracts: []");
        assertEquals(List.of(), config.contracts());
    }

    @Test
    void nonBooleanEnabledRejected() {
        ConfigException ex = failure("""
                contracts:
                  - id: a
                    name: "A"
                    objective: {type: BREAK_BLOCK, target: STONE, amount: 1}
                    difficulty: EASY
                    enabled: "yes"
                """);
        assertTrue(ex.getMessage().contains("must be true or false"));
    }

    @Test
    void allErrorsReportedAtOnce() {
        ConfigException ex = failure("""
                contracts:
                  - id: dup
                    name: "A"
                    objective: {type: BREAK_BLOCK, target: STONE, amount: 1}
                    difficulty: EASY
                  - id: dup
                    name: "B"
                    objective: {type: KILL_MOB, target: ZOMBIE, amount: -1}
                    difficulty: EASY
                """);
        assertTrue(ex.getMessage().contains("duplicate"));
        assertTrue(ex.getMessage().contains("between 1 and 1000000"));
    }
}
