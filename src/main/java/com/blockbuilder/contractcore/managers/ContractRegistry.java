package com.blockbuilder.contractcore.managers;

import com.blockbuilder.contractcore.models.Contract;
import com.blockbuilder.contractcore.models.ContractConfig;

import java.util.function.Function;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

public final class ContractRegistry {

    private volatile ContractConfig config = new ContractConfig(new ContractConfig.Settings(1, 20), List.of());
    private volatile Map<String, Contract> byId = Map.of();

    public void set(ContractConfig config) {
        this.config = config;
        this.byId = config.contracts().stream()
                .collect(Collectors.toUnmodifiableMap(Contract::id, Function.identity()));
    }

    public Optional<Contract> get(String id) {
        return Optional.ofNullable(byId.get(id.toLowerCase(Locale.ROOT)));
    }

    public List<Contract> enabled() {
        return config.contracts().stream().filter(Contract::enabled).toList();
    }

    public ContractConfig.Settings settings() {
        return config.settings();
    }
}
