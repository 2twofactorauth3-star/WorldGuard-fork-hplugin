/*
 * WorldGuard, a suite of tools for Minecraft
 * Copyright (C) sk89q <http://www.sk89q.com>
 * Copyright (C) WorldGuard team and contributors
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License
 * for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package com.sk89q.worldguard.protection;

import com.google.common.collect.ImmutableSet;
import com.sk89q.worldguard.LocalPlayer;
import com.sk89q.worldguard.protection.association.RegionAssociable;
import com.sk89q.worldguard.protection.flags.Flag;
import com.sk89q.worldguard.protection.flags.MapFlag;
import com.sk89q.worldguard.protection.flags.StateFlag;
import com.sk89q.worldguard.protection.flags.StateFlag.State;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.sk89q.worldguard.protection.util.NormativeOrders;

import javax.annotation.Nullable;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * An implementation that calculates flags using a list of regions.
 */
public class RegionResultSet extends AbstractRegionSet {

    private final List<ProtectedRegion> applicable;
    private final FlagValueCalculator flagValueCalculator;
    private final boolean immutable;
    private final ProtectedRegion globalRegion;
    private volatile RegionFlagSnapshot flagSnapshot;
    @Nullable
    private Set<ProtectedRegion> regionSet;

    /**
     * Create a new region result set.
     *
     * <p>The given list must not contain duplicates or the behavior of
     * this instance will be undefined.</p>
     *
     * @param applicable the regions contained in this set
     * @param globalRegion the global region, set aside for special handling.
     */
    public RegionResultSet(List<ProtectedRegion> applicable, @Nullable ProtectedRegion globalRegion) {
        this(applicable, globalRegion, false);
    }

    /**
     * Create a new region result set.
     *
     * @param applicable the regions contained in this set
     * @param globalRegion the global region, set aside for special handling.
     */
    public RegionResultSet(Set<ProtectedRegion> applicable, @Nullable ProtectedRegion globalRegion) {
        this(NormativeOrders.fromSet(applicable), null, globalRegion, true, true);
        this.regionSet = copyRegions(applicable);
    }

    /**
     * Create a new region result set.
     *
     * <p>The list of regions may be first sorted with
     * {@link NormativeOrders}. If that is the case, {@code sorted} should be
     * {@code true}. Otherwise, the list will be sorted in-place.</p>
     * 
     * @param applicable the regions contained in this set
     * @param globalRegion the global region, set aside for special handling.
     * @param sorted true if the list is already sorted with {@link NormativeOrders}
     */
    public RegionResultSet(List<ProtectedRegion> applicable, @Nullable ProtectedRegion globalRegion, boolean sorted) {
        this(applicable, null, globalRegion, sorted);
    }

    public RegionResultSet(
            List<ProtectedRegion> applicable,
            @Nullable Set<ProtectedRegion> regionSet,
            @Nullable ProtectedRegion globalRegion,
            boolean sorted
    ) {
        this(applicable, regionSet, globalRegion, sorted, false);
    }

    private RegionResultSet(List<ProtectedRegion> applicable, @Nullable Set<ProtectedRegion> regionSet,
                            @Nullable ProtectedRegion globalRegion, boolean sorted, boolean immutable) {
        checkNotNull(applicable);
        if (!sorted) {
            NormativeOrders.sort(applicable);
        }
        this.applicable = Collections.unmodifiableList(applicable);
        this.regionSet = regionSet;
        this.flagValueCalculator = new FlagValueCalculator(applicable, globalRegion);
        this.immutable = immutable;
        this.globalRegion = globalRegion;
    }

    public static RegionResultSet fromSnapshot(List<ProtectedRegion> applicable,
                                               Set<ProtectedRegion> regionSet,
                                               @Nullable ProtectedRegion globalRegion) {
        return new RegionResultSet(applicable, regionSet, globalRegion, true, true);
    }

    public boolean mayHaveFlag(Flag<?> flag) {
        if (!immutable || getClass() != RegionResultSet.class) return true;
        RegionFlagSnapshot snapshot = snapshot();
        return snapshot.unknownRegions || snapshot.present.contains(flag);
    }

    public boolean hasDynamicFlags(Flag<?> first, Flag<?> second) {
        if (!immutable || getClass() != RegionResultSet.class) return true;
        RegionFlagSnapshot snapshot = snapshot();
        return snapshot.unknownRegions || snapshot.dynamic.contains(first) || snapshot.dynamic.contains(second);
    }

    private RegionFlagSnapshot snapshot() {
        RegionFlagSnapshot current = flagSnapshot;
        while (true) {
            long flags = ProtectedRegion.flagRevision();
            long structure = ProtectedRegion.structureRevision();
            if (current != null && current.flagsRevision == flags && current.structureRevision == structure) return current;
            current = new RegionFlagSnapshot(applicable, globalRegion, flags, structure);
            if (flags == ProtectedRegion.flagRevision() && structure == ProtectedRegion.structureRevision()) {
                flagSnapshot = current;
                return current;
            }
        }
    }

    @Override
    public boolean isVirtual() {
        return false;
    }

    @Override
    @Nullable
    public State queryState(@Nullable RegionAssociable subject, StateFlag... flags) {
        State combined = null;
        for (StateFlag flag : flags) {
            State state = calculate(subject, flag);
            if (state == State.DENY) return state;
            if (state != null) combined = state;
        }
        return combined;
    }

    @Override
    @Nullable
    public <V> V queryValue(@Nullable RegionAssociable subject, Flag<V> flag) {
        return calculate(subject, flag);
    }

    @SuppressWarnings("unchecked")
    private <V> V calculate(@Nullable RegionAssociable subject, Flag<V> flag) {
        if (immutable && getClass() == RegionResultSet.class && flag != null && flag.getClass() == StateFlag.class) {
            Object value = snapshot().state((StateFlag) flag, flagValueCalculator);
            if (value != RegionFlagSnapshot.DYNAMIC) return (V) value;
        }
        return flagValueCalculator.queryValue(subject, flag);
    }

    @Override
    public <V> Collection<V> queryAllValues(@Nullable RegionAssociable subject, Flag<V> flag) {
        return flagValueCalculator.queryAllValues(subject, flag);
    }

    @Override
    @Nullable
    public <V, K> V queryMapValue(@Nullable RegionAssociable subject, MapFlag<K, V> flag, K key) {
        return flagValueCalculator.queryMapValue(subject, flag, key, null);
    }

    @Override
    @Nullable
    public <V, K> V queryMapValue(@Nullable RegionAssociable subject, MapFlag<K, V> flag, K key, Flag<V> fallback) {
        return flagValueCalculator.queryMapValue(subject, flag, key, fallback);
    }

    @Override
    public boolean isOwnerOfAll(LocalPlayer player) {
        checkNotNull(player);

        for (ProtectedRegion region : applicable) {
            if (!region.isOwner(player)) {
                return false;
            }
        }

        return true;
    }

    @Override
    public boolean isMemberOfAll(LocalPlayer player) {
        checkNotNull(player);

        for (ProtectedRegion region : applicable) {
            if (!region.isMember(player)) {
                return false;
            }
        }

        return true;
    }
    
    @Override
    public int size() {
        return applicable.size();
    }

    @Override
    public Set<ProtectedRegion> getRegions() {
        if (regionSet != null) {
            return regionSet;
        }
        regionSet = copyRegions(applicable);
        return regionSet;
    }

    private static Set<ProtectedRegion> copyRegions(Collection<ProtectedRegion> regions) {
        return switch (regions.size()) {
            case 0 -> Collections.emptySet();
            case 1 -> Collections.singleton(regions.iterator().next());
            default -> ImmutableSet.copyOf(regions);
        };
    }

    @Override
    @javax.annotation.Nonnull
    public Iterator<ProtectedRegion> iterator() {
        return applicable.iterator();
    }

    /**
     * Create a new instance using a list of regions that is known to
     * already be sorted by priority descending.
     *
     * @param regions a list of regions
     * @param globalRegion a global region
     * @return an instance
     */
    public static RegionResultSet fromSortedList(List<ProtectedRegion> regions, @Nullable ProtectedRegion globalRegion) {
        return new RegionResultSet(regions, globalRegion, true);
    }

}
