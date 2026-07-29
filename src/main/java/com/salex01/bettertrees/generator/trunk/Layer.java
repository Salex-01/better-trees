package com.salex01.bettertrees.generator.trunk;

import java.util.Set;

// NO MINECRAFT IMPORTS — see plan §1.
/** One horizontal cross-section of the trunk stack (plan §8.3), post-cavity-overlay and post-repair — exactly what plan §16 test 7 inspects. */
public record Layer(int y, Set<Cell> cells) {}
