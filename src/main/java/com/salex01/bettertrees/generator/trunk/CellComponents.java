package com.salex01.bettertrees.generator.trunk;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

// NO MINECRAFT IMPORTS — see plan §1.
/** Generic 4-connected component finder over a {@code Set<Cell>} — shared by hole detection (§8.2), layer-connectivity checks (§16 test 7) and repair (§8.7). */
public final class CellComponents {
    private CellComponents() {}

    public static List<Set<Cell>> find(Set<Cell> cells) {
        List<Set<Cell>> components = new ArrayList<>();
        Set<Cell> remaining = new HashSet<>(cells);
        while (!remaining.isEmpty()) {
            Cell seed = remaining.iterator().next();
            Set<Cell> component = new HashSet<>();
            Deque<Cell> queue = new ArrayDeque<>();
            component.add(seed);
            queue.add(seed);
            remaining.remove(seed);
            while (!queue.isEmpty()) {
                Cell current = queue.poll();
                for (Cell neighbor : current.neighbors4()) {
                    if (remaining.remove(neighbor)) {
                        component.add(neighbor);
                        queue.add(neighbor);
                    }
                }
            }
            components.add(component);
        }
        return components;
    }
}
