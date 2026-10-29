package com.blockbuilder.contractcore;

import com.blockbuilder.contractcore.commands.ContractCoreCommand;
import com.blockbuilder.contractcore.commands.ContractsCommand;
import com.blockbuilder.contractcore.listeners.GuiListener;
import com.blockbuilder.contractcore.listeners.ObjectiveListener;
import com.blockbuilder.contractcore.listeners.PlayerLifecycleListener;
import com.blockbuilder.contractcore.managers.ConfigLoader;
import com.blockbuilder.contractcore.managers.ContractRegistry;
import com.blockbuilder.contractcore.managers.ContractService;
import com.blockbuilder.contractcore.managers.PlayerDataStore;
import com.blockbuilder.contractcore.managers.XpRewardHandler;
import com.blockbuilder.contractcore.models.ContractConfig;
import com.blockbuilder.contractcore.utils.ConfigException;
import com.blockbuilder.contractcore.utils.Keys;
import com.blockbuilder.contractcore.utils.Messages;
import com.blockbuilder.contractcore.utils.ServerTypeValidator;

import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.sql.SQLException;
import java.util.concurrent.TimeUnit;

public final class ContractCorePlugin extends JavaPlugin {

    private ContractRegistry registry;
    private PlayerDataStore store;
    private ContractService service;
    private Keys keys;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.registry = new ContractRegistry();
        this.keys = new Keys(this);

        ContractConfig config;
        try {
            config = loadConfig();
        } catch (ConfigException ex) {
            getSLF4JLogger().error("ContractCore is disabled because config.yml is invalid:\n{}", ex.getMessage());
            setEnabled(false);
            return;
        }
        registry.set(config);

        try {
            this.store = new PlayerDataStore(getDataFolder().toPath().resolve("data").resolve("contracts.db"), getLogger());
        } catch (IOException | SQLException ex) {
            getSLF4JLogger().error("ContractCore is disabled because the database could not be opened", ex);
            setEnabled(false);
            return;
        }

        this.service = new ContractService(
                registry,
                store,
                new XpRewardHandler(),
                task -> getServer().getGlobalRegionScheduler().execute(this, task),
                (uuid, template, args) -> {
                    Player player = Bukkit.getPlayer(uuid);
                    if (player != null) {
                        Messages.send(player, template, args);
                    }
                },
                getLogger());

        getServer().getPluginManager().registerEvents(new ObjectiveListener(service, keys), this);
        getServer().getPluginManager().registerEvents(new PlayerLifecycleListener(service), this);
        getServer().getPluginManager().registerEvents(new GuiListener(), this);

        ContractsCommand contractsCommand = new ContractsCommand(service, registry, keys);
        PluginCommand contracts = getCommand("contracts");
        if (contracts != null) {
            contracts.setExecutor(contractsCommand);
            contracts.setTabCompleter(contractsCommand);
        }
        ContractCoreCommand adminCommand = new ContractCoreCommand(service, registry, this::reloadConfiguration, () -> service.flushDirty());
        PluginCommand admin = getCommand("contractcore");
        if (admin != null) {
            admin.setExecutor(adminCommand);
            admin.setTabCompleter(adminCommand);
        }

        // Covers plugin-manager reloads with players already online.
        for (Player online : Bukkit.getOnlinePlayers()) {
            service.loadPlayer(online.getUniqueId());
        }

        int autosave = config.settings().autosaveSeconds();
        Bukkit.getAsyncScheduler().runAtFixedRate(this, task -> service.flushDirty(), autosave, autosave, TimeUnit.SECONDS);

        getSLF4JLogger().info("ContractCore enabled with {} contracts", registry.enabled().size());
    }

    private ContractConfig loadConfig() throws ConfigException {
        File file = new File(getDataFolder(), "config.yml");
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        return new ConfigLoader(new ServerTypeValidator()).parse(ConfigLoader.normalize(yaml.getValues(false)));
    }

    private String reloadConfiguration() {
        try {
            registry.set(loadConfig());
            getSLF4JLogger().info("Configuration reloaded: {} contracts", registry.enabled().size());
            return null;
        } catch (ConfigException ex) {
            getSLF4JLogger().warn("Configuration reload rejected:\n{}", ex.getMessage());
            return ex.getMessage();
        }
    }

    @Override
    public void onDisable() {
        HandlerList.unregisterAll(this);
        if (service != null) {
            service.flushDirty();
        }
        if (store != null) {
            store.close();
        }
    }
}
