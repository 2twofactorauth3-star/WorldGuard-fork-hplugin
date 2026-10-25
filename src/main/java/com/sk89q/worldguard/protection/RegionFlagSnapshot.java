package com.sk89q.worldguard.protection;

import com.sk89q.worldguard.protection.flags.Flag;
import com.sk89q.worldguard.protection.flags.Flags;
import com.sk89q.worldguard.protection.flags.RegionGroup;
import com.sk89q.worldguard.protection.flags.StateFlag;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.sk89q.worldguard.protection.regions.ProtectedCuboidRegion;
import com.sk89q.worldguard.protection.regions.ProtectedPolygonalRegion;
import com.sk89q.worldguard.protection.regions.GlobalProtectedRegion;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

final class RegionFlagSnapshot {
    static final Object DYNAMIC = new Object();
    private static final Object ABSENT = new Object();
    public final long flagsRevision;
    public final long structureRevision;
    public final Set<Flag<?>> present;
    public final Set<Flag<?>> dynamic;
    public final boolean unknownRegions;
    private final ConcurrentMap<StateFlag, Object> states = new ConcurrentHashMap<>();

    RegionFlagSnapshot(List<ProtectedRegion> regions, ProtectedRegion global, long flagsRevision,
                       long structureRevision) {
        this.flagsRevision = flagsRevision;
        this.structureRevision = structureRevision;
        Set<Flag<?>> present = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<Flag<?>> dynamic = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<ProtectedRegion> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        boolean unknown = false;
        for (ProtectedRegion region : regions) unknown |= collect(region, visited, present, dynamic);
        unknownRegions = collect(global, visited, present, dynamic) || unknown;
        this.present = present;
        this.dynamic = dynamic;
    }

    private static boolean collect(ProtectedRegion region, Set<ProtectedRegion> visited,
                                Set<Flag<?>> present, Set<Flag<?>> dynamic) {
        boolean unknown = false;
        while (region != null && visited.add(region)) {
            Class<?> type = region.getClass();
            unknown |= type != ProtectedCuboidRegion.class && type != ProtectedPolygonalRegion.class
                    && type != GlobalProtectedRegion.class;
            for (Flag<?> flag : region.getFlags().keySet()) {
                present.add(flag);
                var groupFlag = flag.getRegionGroupFlag();
                if (groupFlag != null) {
                    RegionGroup group = region.getFlag(groupFlag);
                    if ((group == null ? groupFlag.getDefault() : group) != RegionGroup.ALL) dynamic.add(flag);
                }
            }
            region = region.getParent();
        }
        return unknown;
    }

    Object state(StateFlag flag, FlagValueCalculator calculator) {
        if (unknownRegions || flag == Flags.PASSTHROUGH) return DYNAMIC;
        if (!present.contains(flag)) return flag.getDefault();
        if (dynamic.contains(flag)) return DYNAMIC;
        Object value = states.get(flag);
        if (value == null) {
            value = states.computeIfAbsent(flag, current -> {
                Object computed = calculator.queryValue(null, current);
                return computed == null ? ABSENT : computed;
            });
        }
        return value == ABSENT ? null : value;
    }
}
