package com.blockbuilder.contractcore.commands;

import com.blockbuilder.contractcore.managers.ContractRegistry;
import com.blockbuilder.contractcore.managers.ContractService;
import com.blockbuilder.contractcore.models.Contract;
import com.blockbuilder.contractcore.models.ContractProgress;
import com.blockbuilder.contractcore.utils.Messages;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.function.Supplier;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class ContractCoreCommand implements TabExecutor {

    private static final List<String> SUBCOMMANDS = List.of("reload", "assign", "complete", "reset", "info", "save");

    private final ContractService service;
    private final ContractRegistry registry;
    private final Supplier<String> reload;
    private final Runnable flush;

    public ContractCoreCommand(ContractService service, ContractRegistry registry, Supplier<String> reload, Runnable flush) {
        this.service = service;
        this.registry = registry;
        this.reload = reload;
        this.flush = flush;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            Messages.send(sender, Messages.USAGE_ADMIN);
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "reload" -> {
                if (!sender.hasPermission("contractcore.admin.reload")) {
                    return true;
                }
                String error = reload.get();
                if (error == null) {
                    Messages.send(sender, Messages.RELOADED, registry.enabled().size());
                } else {
                    Messages.send(sender, Messages.RELOAD_FAILED);
                    Messages.sendRaw(sender, error);
                }
            }
            case "assign" -> {
                if (!sender.hasPermission("contractcore.admin.manage")) {
                    return true;
                }
                if (args.length < 3) {
                    Messages.send(sender, Messages.USAGE_ADMIN);
                    return true;
                }
                Player target = Bukkit.getPlayerExact(args[1]);
                if (target == null) {
                    Messages.send(sender, Messages.PLAYER_NOT_ONLINE, args[1]);
                    return true;
                }
                if (service.adminAssign(target.getUniqueId(), args[2])) {
                    Messages.send(sender, Messages.ADMIN_ASSIGNED, displayName(args[2]), target.getName());
                } else {
                    Messages.send(sender, Messages.CONTRACT_NOT_FOUND, args[2]);
                }
            }
            case "complete" -> {
                if (!sender.hasPermission("contractcore.admin.manage")) {
                    return true;
                }
                if (args.length < 3) {
                    Messages.send(sender, Messages.USAGE_ADMIN);
                    return true;
                }
                Player target = Bukkit.getPlayerExact(args[1]);
                if (target == null) {
                    Messages.send(sender, Messages.PLAYER_NOT_ONLINE, args[1]);
                    return true;
                }
                if (service.adminComplete(target.getUniqueId(), args[2])) {
                    Messages.send(sender, Messages.ADMIN_COMPLETED, displayName(args[2]), target.getName());
                } else {
                    Messages.send(sender, Messages.CONTRACT_NOT_FOUND, args[2]);
                }
            }
            case "reset" -> {
                if (!sender.hasPermission("contractcore.admin.manage")) {
                    return true;
                }
                if (args.length < 2) {
                    Messages.send(sender, Messages.USAGE_ADMIN);
                    return true;
                }
                Player target = Bukkit.getPlayerExact(args[1]);
                if (target == null) {
                    Messages.send(sender, Messages.PLAYER_NOT_ONLINE, args[1]);
                    return true;
                }
                String contractId = args.length >= 3 ? args[2] : null;
                if (!service.adminReset(target.getUniqueId(), contractId)) {
                    Messages.send(sender, Messages.PROFILE_LOADING);
                    return true;
                }
                if (contractId == null) {
                    Messages.send(sender, Messages.ADMIN_RESET_ALL, target.getName());
                } else {
                    Messages.send(sender, Messages.ADMIN_RESET_ONE, displayName(contractId), target.getName());
                }
            }
            case "info" -> {
                if (!sender.hasPermission("contractcore.admin.manage")) {
                    return true;
                }
                if (args.length < 2) {
                    Messages.send(sender, Messages.USAGE_ADMIN);
                    return true;
                }
                printInfo(sender, args[1]);
            }
            case "save" -> {
                if (!sender.hasPermission("contractcore.admin.manage")) {
                    return true;
                }
                flush.run();
                Messages.send(sender, Messages.SAVED);
            }
            default -> Messages.send(sender, Messages.USAGE_ADMIN);
        }
        return true;
    }

    private void printInfo(CommandSender sender, String name) {
        Player target = Bukkit.getPlayerExact(name);
        if (target == null) {
            Messages.send(sender, Messages.PLAYER_NOT_ONLINE, name);
            return;
        }
        Map<String, ContractProgress> contracts = service.contracts(target.getUniqueId());
        if (contracts.isEmpty()) {
            Messages.send(sender, Messages.ADMIN_INFO_NONE, target.getName());
            return;
        }
        Messages.send(sender, Messages.PROGRESS_HEADER);
        for (Map.Entry<String, ContractProgress> entry : contracts.entrySet()) {
            var contract = registry.get(entry.getKey()).orElse(null);
            ContractProgress progress = entry.getValue();
            if (contract == null) {
                Messages.send(sender, Messages.ADMIN_INFO_LINE, entry.getKey(), progress.status().name(), progress.progress(), 0);
            } else {
                Messages.send(sender, Messages.ADMIN_INFO_LINE, entry.getKey(), progress.status().name(), progress.progress(), contract.objective().amount());
            }
        }
    }

    private String displayName(String contractId) {
        return registry.get(contractId).map(Contract::displayName).orElse(contractId);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1) {
            return filter(SUBCOMMANDS, args[0]);
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("assign")
                || args[0].equalsIgnoreCase("complete")
                || args[0].equalsIgnoreCase("reset")
                || args[0].equalsIgnoreCase("info"))) {
            List<String> names = new ArrayList<>();
            for (Player online : Bukkit.getOnlinePlayers()) {
                names.add(online.getName());
            }
            return filter(names, args[1]);
        }
        if (args.length == 3 && (args[0].equalsIgnoreCase("assign")
                || args[0].equalsIgnoreCase("complete")
                || args[0].equalsIgnoreCase("reset"))) {
            return filter(registry.enabled().stream().map(Contract::id).toList(), args[2]);
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
