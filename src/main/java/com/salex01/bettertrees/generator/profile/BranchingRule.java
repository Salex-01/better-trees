package com.salex01.bettertrees.generator.profile;

import com.salex01.bettertrees.generator.Diameter;

/** Rule A — legal parent-to-child diameter pairs (plan §5.2). Selected per profile by name. */
public interface BranchingRule {
    boolean legal(Diameter parent, Diameter child);

    /** 4 from 4/8/12 · 8 from 8/12/16 · 12 from 12/16 · 16 from 16. */
    BranchingRule SMALL_TREE = (parent, child) ->
            child.tier() <= parent.tier() && parent.tier() - child.tier() <= 2;

    /** 4 from 4/8 only · 8 from 8/12/16 · 12 from 12/16 · 16 from 16 — forbids a 4 straight off a 12. */
    BranchingRule BIG_TREE = (parent, child) ->
            child.px() <= parent.px() && parent.px() <= 2 * child.px();
}
