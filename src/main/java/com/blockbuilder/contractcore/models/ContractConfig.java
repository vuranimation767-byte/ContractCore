package com.blockbuilder.contractcore.models;

import java.util.List;

public record ContractConfig(Settings settings, List<Contract> contracts) {

    public record Settings(int maxActiveContracts, int autosaveSeconds) {
    }
}
