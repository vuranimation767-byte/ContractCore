package com.blockbuilder.contractcore.models;

public record ContractProgress(
        int progress,
        Status status,
        long acceptedAt,
        long completedAt,
        long claimedAt,
        long cooldownUntil,
        int completedCount) {

    public ContractProgress add(int amount) {
        return new ContractProgress(progress + amount, status, acceptedAt, completedAt, claimedAt, cooldownUntil, completedCount);
    }

    public ContractProgress complete(long now) {
        return new ContractProgress(progress, Status.COMPLETE, acceptedAt, now, claimedAt, cooldownUntil, completedCount);
    }

    public ContractProgress claim(long now, long nextCooldownUntil) {
        return new ContractProgress(progress, Status.CLAIMED, acceptedAt, completedAt, now, nextCooldownUntil, completedCount + 1);
    }

    public ContractProgress abandon(long nextCooldownUntil) {
        return new ContractProgress(progress, Status.ABANDONED, acceptedAt, completedAt, claimedAt, nextCooldownUntil, completedCount);
    }

    public ContractProgress accept(long now) {
        return new ContractProgress(0, Status.ACTIVE, now, 0L, 0L, 0L, completedCount);
    }
}
