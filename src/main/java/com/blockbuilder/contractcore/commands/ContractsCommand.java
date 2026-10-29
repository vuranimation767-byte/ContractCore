package com.blockbuilder.contractcore.commands;

import com.blockbuilder.contractcore.managers.ContractGui;
import com.blockbuilder.contractcore.managers.ContractRegistry;
import com.blockbuilder.contractcore.managers.ContractService;
import com.blockbuilder.contractcore.models.Contract;
import com.blockbuilder.contractcore.models.ContractProgress;
import com.blockbuilder.contractcore.models.Status;
import com.blockbuilder.contractcore.utils.Keys;
import com.blockbuilder.contractcore.utils.Messages;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class ContractsCommand implements TabExecutor {

    private static final List<String> SUBCOMMANDS = List.of("accept", "claim", "abandon", "progress");

    private final ContractService service;
    private final ContractRegistry registry;
    private final Keys keys;

    public ContractsCommand(ContractService service, ContractRegistry registry, Keys keys) {
        this.service = service;
        this.registry = registry;
        this.keys = keys;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            Messages.send(sender, Messages.PLAYER_ONLY);
            return true;
        }
        if (args.length == 0) {
            ContractGui.open(player, service, registry, keys);
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "accept" -> {
                if (args.length < 2) {
                    Messages.send(player, Messages.USAGE_CONTRACTS);
                    return true;
                }
                service.reportAccept(player, service.accept(player.getUniqueId(), args[1]), args[1]);
            }
            case "claim" -> {
                if (args.length < 2) {
                    Messages.send(player, Messages.USAGE_CONTRACTS);
                    return true;
                }
                service.reportClaim(player, service.claim(player.getUniqueId(), args[1]), args[1]);
            }
            case "abandon" -> {
                if (args.length < 2) {
                    Messages.send(player, Messages.USAGE_CONTRACTS);
                    return true;
                }
                service.reportAbandon(player, service.abandon(player.getUniqueId(), args[1]), args[1]);
            }
            case "progress" -> printProgress(player);
            default -> Messages.send(player, Messages.USAGE_CONTRACTS);
        }
        return true;
    }

    private void printProgress(Player player) {
        Map<String, ContractProgress> contracts = service.contracts(player.getUniqueId());
        if (contracts.isEmpty()) {
            Messages.send(player, Messages.PROGRESS_EMPTY);
            return;
        }
        Messages.send(player, Messages.PROGRESS_HEADER);
        for (Map.Entry<String, ContractProgress> entry : contracts.entrySet()) {
            Contract contract = registry.get(entry.getKey()).orElse(null);
            if (contract == null) {
                continue;
            }
            ContractProgress progress = entry.getValue();
            Messages.send(player, Messages.PROGRESS_LINE,
                    contract.displayName(), progress.progress(), contract.objective().amount(), progress.status().name());
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            return List.of();
        }
        if (args.length == 1) {
            return filter(SUBCOMMANDS, args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("accept")) {
            return filter(registry.enabled().stream().map(Contract::id).toList(), args[1]);
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("claim") || args[0].equalsIgnoreCase("abandon"))) {
            List<String> ids = new ArrayList<>();
            service.contracts(player.getUniqueId()).forEach((id, progress) -> {
                if (progress.status() == Status.ACTIVE || progress.status() == Status.COMPLETE) {
                    ids.add(id);
                }
            });
            return filter(ids, args[1]);
        }
        return List.of();
    }

    private static List<String> filter(List<String> options, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        List<String> matches = new ArrayList<>();
        for (String option : options) {
            if (option.startsWith(lower)) {
                matches.add(option);
            }
        }
        return matches;
    }
}
