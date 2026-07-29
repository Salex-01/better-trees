package com.salex01.bettertrees.generator;

// NO MINECRAFT IMPORTS — see plan §0/§1. Pure value type shared by generator/ and world/.
public enum Diameter {
    D4(4),
    D8(8),
    D12(12),
    D16(16);

    private final int px;

    Diameter(int px) {
        this.px = px;
    }

    public int px() {
        return px;
    }

    public float blocks() {
        return px / 16f;
    }

    public int tier() {
        return ordinal();
    }
}
