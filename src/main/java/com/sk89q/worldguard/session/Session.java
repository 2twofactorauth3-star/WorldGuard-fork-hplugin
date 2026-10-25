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

package com.sk89q.worldguard.session;

import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.sk89q.worldedit.util.Location;
import com.sk89q.worldedit.util.formatting.text.TextComponent;
import com.sk89q.worldedit.world.World;
import com.sk89q.worldguard.LocalPlayer;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.config.ConfigurationManager;
import com.sk89q.worldguard.protection.ApplicableRegionSet;
import com.sk89q.worldguard.protection.RegionResultSet;
import com.sk89q.worldguard.protection.flags.StateFlag.State;
import com.sk89q.worldguard.protection.flags.Flags;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.sk89q.worldguard.protection.regions.RegionQuery;
import com.sk89q.worldguard.protection.regions.QueryCache;
import com.sk89q.worldguard.session.handler.Handler;
import com.sk89q.worldguard.session.handler.EntryFlag;
import com.sk89q.worldguard.session.handler.GreetingFlag;
import com.sk89q.worldguard.session.handler.FarewellFlag;
import com.sk89q.worldguard.util.Locations;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Keeps session information on a player.
 */
public class Session {

    private static final Handler[] NO_HANDLERS = new Handler[0];
    private final SessionManager manager;
    private final RegionQuery query;
    private final QueryCache.Cursor movementQuery = new QueryCache.Cursor();
    private boolean disableBypass;
    private final HashMap<Class<?>, Handler> handlers = Maps.newLinkedHashMap();
    private volatile Handler[] handlerArray = NO_HANDLERS;
    private volatile CallbackSet callbacks = CallbackSet.EMPTY;
    private Location lastValid;
    private Set<ProtectedRegion> lastRegionSet;
    private ApplicableRegionSet lastApplicableRegionSet;
    private boolean freshMoveSet;
    private volatile boolean needRefresh;
    private boolean initialized;
    private long boundaryFlagsRevision;
    private long boundaryStructureRevision;
    private boolean boundaryInitialized;
    private volatile World bypassWorld;
    private volatile boolean bypassValue;
    private volatile long bypassExpiresAt;

    /**
     * Create a new session.
     *
     * @param manager The session manager
     */
    public Session(SessionManager manager) {
        checkNotNull(manager, "manager");
        this.manager = manager;
        this.query = WorldGuard.getInstance().getPlatform().getRegionContainer().createQuery();
    }

    /**
     * Register a new handler.
     *
     * @param handler A new handler
     */
    public void register(Handler handler) {
        handlers.put(handler.getClass(), handler);
        handlerArray = handlers.values().toArray(Handler[]::new);
        refreshCallbacks();
    }

    void registerFactories(Iterable<Handler.Factory<? extends Handler>> factories) {
        for (Handler.Factory<? extends Handler> factory : factories) {
            Handler handler = factory.create(this);
            handlers.put(handler.getClass(), handler);
        }
        handlerArray = handlers.values().toArray(Handler[]::new);
        refreshCallbacks();
    }

    private void refreshCallbacks() {
        ArrayList<Handler> movement = new ArrayList<>();
        ArrayList<Handler> boundary = new ArrayList<>();
        ArrayList<Handler> continuous = new ArrayList<>();
        ArrayList<Handler> ticking = new ArrayList<>();
        ArrayList<Handler> invincibility = new ArrayList<>();
        boolean customTick = false;
        for (Handler handler : handlerArray) {
            HandlerCallbacks callbacks = HandlerCallbacks.of(handler);
            if (callbacks.movement) movement.add(handler);
            if (callbacks.boundary) boundary.add(handler);
            if (callbacks.continuous) continuous.add(handler);
            if (callbacks.tick) ticking.add(handler);
            if (callbacks.invincibility) invincibility.add(handler);
            customTick |= callbacks.customTick;
        }
        callbacks = new CallbackSet(movement.toArray(Handler[]::new), boundary.toArray(Handler[]::new),
                continuous.toArray(Handler[]::new), ticking.toArray(Handler[]::new),
                invincibility.toArray(Handler[]::new), customTick);
    }

    public boolean hasCustomTickHandlers() {
        return callbacks.customTick;
    }

    public boolean hasInvincibilityHandlers() {
        return callbacks.invincibility.length != 0;
    }

    /**
     * Get the session manager.
     *
     * @return The session manager
     */
    public SessionManager getManager() {
        return manager;
    }

    /**
     * Get a handler by class, if has been registered.
     *
     * @param type The type of handler
     * @param <T> The type of handler
     * @return A handler instance, otherwise null
     */
    @Nullable
    @SuppressWarnings("unchecked")
    public <T extends Handler> T getHandler(Class<T> type) {
        return (T) handlers.get(type);
    }

    /**
     * Initialize the session.
     *
     * @param player The player
     */
    public void initialize(LocalPlayer player) {
        Location location = player.getLocation();
        ApplicableRegionSet set = query.getApplicableRegions(location);

        lastValid = location;
        lastRegionSet = set.getRegions();
        lastApplicableRegionSet = set;
        freshMoveSet = true;
        ConfigurationManager cfg = WorldGuard.getInstance().getPlatform().getGlobalStateManager();
        disableBypass = cfg.disableDefaultBypass;
        if (cfg.announceBypassStatus && player.hasPermission("worldguard.region.toggle-bypass")) {
            player.printInfo(TextComponent.of(disableBypass
                    ? "@wg:bypassStatusDisabled@"
                    : "@wg:bypassStatusEnabled@"));
        }


        for (Handler handler : handlerArray) {
            handler.initialize(player, location, set);
        }
        boundaryFlagsRevision = ProtectedRegion.flagRevision();
        boundaryStructureRevision = ProtectedRegion.structureRevision();
        boundaryInitialized = false;
    }

    synchronized void ensureInitialized(LocalPlayer player, BiConsumer<Session, LocalPlayer> initializer) {
        if (initialized) {
            return;
        }
        initialized = true;
        initializer.accept(this, player);
    }

    /**
     * Uninitialize the session.
     *
     * @param player The player
     */
    public void uninitialize(LocalPlayer player) {
        Location location = player.getLocation();
        ApplicableRegionSet set = query.getApplicableRegions(location);

        for (Handler handler : handlerArray) {
            handler.uninitialize(player, location, set);
        }
    }

    /**
     * Tick the session.
     *
     * @param player The player
     */
    public void tick(LocalPlayer player) {
        Handler[] tickHandlers = callbacks.tick;
        if (tickHandlers.length == 0) return;
        ApplicableRegionSet set;
        if (freshMoveSet && lastApplicableRegionSet != null) {
            set = lastApplicableRegionSet;
            freshMoveSet = false;
        } else {
            set = query.getApplicableRegions(player.getLocation());
            lastApplicableRegionSet = set;
        }

        for (Handler handler : tickHandlers) {
            handler.tick(player, set);
        }
    }

    /**
     * Test whether the player is invincible according to the active session handlers.
     *
     * @param player The player
     * @return Whether the player is invincible
     */
    public boolean isInvincible(LocalPlayer player) {
        State regionState = query.getApplicableRegions(player.getLocation())
                .queryState(player, Flags.INVINCIBILITY);
        if (regionState == State.DENY) {
            return false;
        }
        boolean invincible = regionState == State.ALLOW;
        for (Handler handler : callbacks.invincibility) {
            State state = handler.getInvincibility(player);
            if (state != null) {
                switch (state) {
                    case DENY:
                        return false;
                    case ALLOW:
                        invincible = true;
                }
            }
        }
        return invincible;
    }

    @Nullable
    public ApplicableRegionSet getLastApplicableRegionSet() {
        return lastApplicableRegionSet;
    }

    /**
     * Re-initialize the session.
     *
     * @param player The player
     */
    public void resetState(LocalPlayer player) {
        initialize(player);
        needRefresh = true;
    }

    /**
     * Test movement to the given location.
     *
     * @param player The player
     * @param to The new location
     * @param moveType The type of move
     * @return The overridden location, if the location is being overridden
     * @see #testMoveTo(LocalPlayer, Location, MoveType, boolean) For an explanation
     */
    @Nullable
    public Location testMoveTo(LocalPlayer player, Location to, MoveType moveType) {
        return testMoveTo(player, to, moveType, false);
    }

    /**
     * Test movement to the given location.
     *
     * <p>If a non-null {@link Location} is returned, the player should be
     * at that location instead of where the player has tried to move to.</p>
     *
     * <p>If the {@code moveType} is cancellable
     * ({@link MoveType#isCancellable()}, then the last valid location will
     * be set to the given one.</p>
     *
     * @param player The player
     * @param to The new location
     * @param moveType The type of move
     * @param forced Whether to force a check
     * @return The overridden location, if the location is being overridden
     */
    @Nullable
    public Location testMoveTo(LocalPlayer player, Location to, MoveType moveType, boolean forced) {
        return testMoveTo(player, to, moveType, forced, null);
    }

    @Nullable
    public Location testMoveTo(LocalPlayer player, Location to, MoveType moveType, boolean forced,
                               @Nullable ApplicableRegionSet destination) {
        if (!forced && needRefresh) {
            needRefresh = false;
            forced = true;
        }

        if (forced || Locations.isDifferentBlock(lastValid, to)) {
            long flagsRevision = ProtectedRegion.flagRevision();
            long structureRevision = ProtectedRegion.structureRevision();
            ApplicableRegionSet toSet = destination != null ? destination : queryDestination(to);
            CallbackSet active = callbacks;

            boolean custom = manager.customHandlersRegistered();
            if (custom && !testMovementHandlers(active, player, to, toSet, moveType)) return lastValid;

            Set<ProtectedRegion> toRegions = toSet.getRegions();
            boolean unchanged = unchangedRegions(to, toRegions);
            boolean refresh = forced || boundaryFlagsRevision != flagsRevision
                    || boundaryStructureRevision != structureRevision || lastApplicableRegionSet != toSet
                    || custom && !boundaryInitialized;
            if ((!unchanged || custom || refresh)
                    && !testBoundaryHandlers(active, player, to, toSet, moveType, unchanged, refresh)) return lastValid;

            lastValid = to;
            lastRegionSet = toRegions;
            lastApplicableRegionSet = toSet;
            freshMoveSet = true;
            boundaryFlagsRevision = flagsRevision;
            boundaryStructureRevision = structureRevision;
            boundaryInitialized = true;
        }

        return null;
    }

    private ApplicableRegionSet queryDestination(Location destination) {
        return query.getClass() == RegionQuery.class
                ? query.getApplicableRegions(destination, RegionQuery.QueryOption.COMPUTE_PARENTS, movementQuery)
                : query.getApplicableRegions(destination);
    }

    private boolean unchangedRegions(Location to, Set<ProtectedRegion> regions) {
        return lastValid != null
                && lastValid.getExtent().equals(to.getExtent())
                && (regions == lastRegionSet || regions.equals(lastRegionSet));
    }

    private boolean testMovementHandlers(CallbackSet active, LocalPlayer player, Location to,
                                         ApplicableRegionSet regions, MoveType moveType) {
        for (Handler handler : active.movement) {
            if (!handler.testMoveTo(player, lastValid, to, regions, moveType) && moveType.isCancellable()) return false;
        }
        return true;
    }

    private boolean testBoundaryHandlers(CallbackSet active, LocalPlayer player, Location to,
                                         ApplicableRegionSet regions, MoveType moveType, boolean unchanged, boolean refresh) {
        Set<ProtectedRegion> entered = unchanged ? Collections.emptySet() : Sets.difference(regions.getRegions(), lastRegionSet);
        Set<ProtectedRegion> exited = unchanged ? Collections.emptySet() : Sets.difference(lastRegionSet, regions.getRegions());
        for (Handler handler : unchanged && !refresh ? active.continuous : active.boundary) {
            if (unchanged && !refresh && canSkipBoundary(handler, regions)) continue;
            if (!handler.onCrossBoundary(player, lastValid, to, regions, entered, exited, moveType)
                    && moveType.isCancellable()) return false;
        }
        return true;
    }

    @SuppressWarnings("deprecation")
    private static boolean canSkipBoundary(Handler handler, ApplicableRegionSet regions) {
        if (!(regions instanceof RegionResultSet result)) return false;
        Class<?> type = handler.getClass();
        if (type == EntryFlag.class) return !result.mayHaveFlag(Flags.ENTRY);
        if (type == GreetingFlag.class) return !result.hasDynamicFlags(Flags.GREET_MESSAGE, Flags.GREET_TITLE);
        return type == FarewellFlag.class && !result.hasDynamicFlags(Flags.FAREWELL_MESSAGE, Flags.FAREWELL_TITLE);
    }

    private static final class CallbackSet {
        private static final CallbackSet EMPTY = new CallbackSet(NO_HANDLERS, NO_HANDLERS, NO_HANDLERS,
                NO_HANDLERS, NO_HANDLERS, false);
        public final Handler[] movement;
        public final Handler[] boundary;
        public final Handler[] continuous;
        public final Handler[] tick;
        public final Handler[] invincibility;
        public final boolean customTick;

        private CallbackSet(Handler[] movement, Handler[] boundary, Handler[] continuous, Handler[] tick,
                            Handler[] invincibility, boolean customTick) {
            this.movement = movement;
            this.boundary = boundary;
            this.continuous = continuous;
            this.tick = tick;
            this.invincibility = invincibility;
            this.customTick = customTick;
        }
    }

    /**
     * @return true if the owner of this session should not bypass protection, even if they have bypass permissions
     */
    public boolean hasBypassDisabled() {
        return disableBypass;
    }

    /**
     * Toggle bypass disabling for this session.
     * @param disabled true to disable region bypass
     */
    public void setBypassDisabled(boolean disabled) {
        disableBypass = disabled;
        bypassExpiresAt = 0;
    }

    boolean hasBypass(LocalPlayer player, World world, BiPredicate<World, LocalPlayer> permissionTest) {
        long now = System.nanoTime();
        if (world.equals(bypassWorld) && now < bypassExpiresAt) {
            return bypassValue;
        }
        boolean value = permissionTest.test(world, player);
        bypassWorld = world;
        bypassValue = value;
        bypassExpiresAt = now + 2_000_000_000L;
        return value;
    }
}
