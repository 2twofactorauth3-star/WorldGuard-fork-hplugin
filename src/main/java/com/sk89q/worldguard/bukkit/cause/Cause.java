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

package com.sk89q.worldguard.bukkit.cause;

import com.google.common.base.Joiner;
import com.sk89q.worldguard.bukkit.internal.WGMetadata;
import com.sk89q.worldguard.bukkit.util.Entities;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.entity.AreaEffectCloud;
import org.bukkit.entity.Creature;
import org.bukkit.entity.Creeper;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.Firework;
import org.bukkit.entity.LightningStrike;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.entity.Tameable;
import org.bukkit.entity.Vehicle;
import org.bukkit.metadata.Metadatable;
import org.bukkit.projectiles.BlockProjectileSource;
import org.bukkit.projectiles.ProjectileSource;
import org.jspecify.annotations.NonNull;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * An instance of this object describes the actors that played a role in
 * causing an event, with the ability to describe a situation where one actor
 * controls several other actors to create the event.
 *
 * <p>For example, if a player fires an arrow that hits an item frame, the player
 * is the initiator, while the arrow is merely controlled by the player to
 * hit the item frame.</p>
 */
public final class Cause {

    private static final String CAUSE_KEY = "worldguard.cause";
    private static final Cause UNKNOWN = new Cause(Collections.emptyList(), false);
    private static final ClassValue<RelatedCauseType> RELATED_CAUSES = new ClassValue<>() {
        @Override
        protected RelatedCauseType computeValue(@NonNull Class<?> type) {
            return RelatedCauseType.of(type);
        }
    };

    private final List<Object> causes;
    private final boolean indirect;

    /**
     * Create a new instance.
     *
     * @param causes a list of causes
     * @param indirect whether the cause is indirect
     */
    private Cause(List<Object> causes, boolean indirect) {
        checkNotNull(causes);
        this.causes = causes;
        this.indirect = indirect;
    }

    /**
     * Test whether the traced cause is indirect.
     *
     * <p>If the cause is indirect, then the root cause may not be notified,
     * for example.</p>
     *
     * @return true if the cause is indirect
     */
    public boolean isIndirect() {
        return indirect;
    }

    /**
     * Return whether a cause is known. This method will return false if
     * the list of causes is empty or the root cause is really not known
     * (e.g. primed TNT).
     *
     * @return true if known
     */
    public boolean isKnown() {
        Object object = getRootCause();

        if (object == null) {
            return false;
        }

        if (object instanceof Tameable tameable && tameable.isTamed()) {
            // if they're tamed but also the root cause, the owner is offline
            // otherwise the owner will be the root cause (and known)
            return false;
        }

        if (object instanceof TNTPrimed || object instanceof Vehicle) {
            Entity entity = (Entity) object;
            return entity.getOrigin() != null;
        }

        return true;
    }

    @Nullable
    public Object getRootCause() {
        if (!causes.isEmpty()) {
            return causes.get(0);
        }

        return null;
    }

    @Nullable
    public Player getFirstPlayer() {
        for (Object object : causes) {
            if (object instanceof Player p && !Entities.isNPC(p)) {
                return p;
            }
        }

        return null;
    }

    @Nullable
    public Entity getFirstEntity() {
        for (Object object : causes) {
            if (object instanceof Entity e) {
                return e;
            }
        }

        return null;
    }

    @Nullable
    public Entity getFirstNonPlayerEntity() {
        for (Object object : causes) {
            if (object instanceof Entity e && (!(object instanceof Player) || Entities.isNPC(e))) {
                return e;
            }
        }

        return null;
    }

    @Nullable
    public Block getFirstBlock() {
        for (Object object : causes) {
            if (object instanceof Block b) {
                return b;
            }
        }

        return null;
    }

    /**
     * Find the first type matching one in the given array.
     *
     * @param types an array of types
     * @return a found type or null
     */
    @Nullable
    public EntityType find(EntityType... types) {
        for (Object object : causes) {
            if (object instanceof Entity) {
                for (EntityType type : types) {
                    if (((Entity) object).getType() == type) {
                        return type;
                    }
                }
            }
        }

        return null;
    }

    @Override
    public String toString() {
        return Joiner.on(" | ").join(causes);
    }

    /**
     * Create a new instance with the given objects as the cause,
     * where the first-most object is the initial initiator and those
     * following it are controlled by the previous entry.
     *
     * @param cause an array of causing objects
     * @return a cause
     */
    public static Cause create(@Nullable Object... cause) {
        if (cause != null) {
            Builder builder = new Builder(cause.length);
            builder.addAll(cause);
            return builder.build();
        } else {
            return UNKNOWN;
        }
    }

    public static Cause create(@Nullable Object cause) {
        if (cause == null) {
            return UNKNOWN;
        }
        Builder builder = new Builder(1);
        builder.add(cause);
        return builder.build();
    }

    /**
     * Create a new instance that indicates that the cause is not known.
     *
     * @return a cause
     */
    public static Cause unknown() {
        return UNKNOWN;
    }

    /**
     * Add a parent cause to a {@code Metadatable} object.
     *
     * <p>Note that {@code target} cannot be an instance of
     * {@link Block} because {@link #create(Object...)} will not bother
     * checking for such data on blocks (because it is relatively costly
     * to do so).</p>
     *
     * @param target the target
     * @param parent the parent cause
     * @throws IllegalArgumentException thrown if {@code target} is an instance of {@link Block}
     */
    public static void trackParentCause(Metadatable target, Object parent) {
        if (target instanceof Block) {
            throw new IllegalArgumentException("Can't track causes on Blocks because Cause doesn't check block metadata");
        }

        WGMetadata.put(target, CAUSE_KEY, parent);
    }

    /**
     * Remove a parent cause from a {@code Metadatable} object.
     *
     * @param target the target
     */
    public static void untrackParentCause(Metadatable target) {
        WGMetadata.remove(target, CAUSE_KEY);
    }

    /**
     * Builds causes.
     */
    private static final class Builder {
        private final int expectedSize;
        private List<Object> causes;
        private Object firstCause;
        private Object firstSeen;
        private List<Object> additionalSeen;
        private boolean indirect;

        private Builder(int expectedSize) {
            this.expectedSize = expectedSize;
        }

        private void addAll(@Nullable Object... element) {
            if (element == null) {
                return;
            }
            for (Object o : element) {
                add(o);
            }
        }

        private void add(@Nullable Object source) {
            if (source == null || !markSeen(source)) {
                return;
            }
            addRelatedCauses(source);
            addTrackedParentCauses(source);
            appendCause(source);
        }

        private void appendCause(Object source) {
            if (causes == null && firstCause == null) {
                firstCause = source;
            } else {
                mutableCauses().add(source);
            }
        }

        private List<Object> mutableCauses() {
            if (causes == null) {
                causes = new ArrayList<>(Math.max(2, expectedSize));
                if (firstCause != null) {
                    causes.add(firstCause);
                    firstCause = null;
                }
            }
            return causes;
        }

        private boolean markSeen(Object source) {
            if (firstSeen == null) {
                firstSeen = source;
                return true;
            }
            if (firstSeen.equals(source)) {
                return false;
            }
            if (additionalSeen == null) {
                additionalSeen = new ArrayList<>(2);
            } else if (additionalSeen.contains(source)) {
                return false;
            }
            additionalSeen.add(source);
            return true;
        }

        private void addRelatedCauses(Object source) {
            switch (RELATED_CAUSES.get(source.getClass())) {
                case TNT -> add(((TNTPrimed) source).getSource());
                case PROJECTILE -> addProjectileSource((Projectile) source);
                case VEHICLE -> addPassengers((Vehicle) source);
                case CLOUD -> addIndirect(((AreaEffectCloud) source).getSource());
                case TAMEABLE -> addTameableOwner((Tameable) source);
                case CREEPER -> {
                    Creeper creeper = (Creeper) source;
                    addIndirect(creeper.getTarget(), creeper.getIgniter());
                }
                case CREATURE -> addIndirect(((Creature) source).getTarget());
                case BLOCK_PROJECTILE -> add(((BlockProjectileSource) source).getBlock());
                case LIGHTNING -> addLightningSource((LightningStrike) source);
                case FALLING_BLOCK -> addFallingBlockOrigin((FallingBlock) source);
                case NONE -> { }
            }
        }

        private void addPassengers(Vehicle vehicle) {
            for (Entity passenger : vehicle.getPassengers()) add(passenger);
        }

        private void addProjectileSource(Projectile projectile) {
            ProjectileSource shooter = projectile.getShooter();
            add(shooter);
            if (shooter == null && projectile instanceof Firework firework) {
                addFireworkSpawner(firework);
            }
        }

        private void addFireworkSpawner(Firework firework) {
            UUID spawningUuid = firework.getSpawningEntity();
            if (spawningUuid != null) {
                add(Bukkit.getEntity(spawningUuid));
            }
        }

        private void addTameableOwner(Tameable tameable) {
            UUID ownerId = tameable.getOwnerUniqueId();
            addIndirect(ownerId == null ? null : Bukkit.getPlayer(ownerId));
        }

        private void addLightningSource(LightningStrike lightning) {
            if (lightning.getCausingEntity() != null) {
                addIndirect(lightning.getCausingEntity());
            }
        }

        private void addFallingBlockOrigin(FallingBlock fallingBlock) {
            if (fallingBlock.getOrigin() != null) {
                addIndirect(fallingBlock.getOrigin().getBlock());
            }
        }

        private void addIndirect(@Nullable Object... sources) {
            indirect = true;
            addAll(sources);
        }

        private void addIndirect(@Nullable Object source) {
            indirect = true;
            add(source);
        }

        private void addTrackedParentCauses(Object original) {
            Object source = original;
            int index = causes == null ? (firstCause == null ? 0 : 1) : causes.size();
            while (source instanceof Metadatable && !(source instanceof Block)) {
                source = WGMetadata.getIfPresent((Metadatable) source, CAUSE_KEY, Object.class);
                if (source != null) {
                    if (!markSeen(source)) {
                        break;
                    }
                    if (causes == null && firstCause == null) {
                        firstCause = source;
                    } else {
                        mutableCauses().add(index, source);
                    }
                }
            }
        }

        public Cause build() {
            if (causes != null) return new Cause(causes, indirect);
            if (firstCause != null) return new Cause(Collections.singletonList(firstCause), indirect);
            return indirect ? new Cause(Collections.emptyList(), true) : UNKNOWN;
        }
    }

    private enum RelatedCauseType {
        TNT, PROJECTILE, VEHICLE, CLOUD, TAMEABLE, CREEPER, CREATURE,
        BLOCK_PROJECTILE, LIGHTNING, FALLING_BLOCK, NONE;

        private static RelatedCauseType of(Class<?> type) {
            if (TNTPrimed.class.isAssignableFrom(type)) return TNT;
            if (Projectile.class.isAssignableFrom(type)) return PROJECTILE;
            if (Vehicle.class.isAssignableFrom(type)) return VEHICLE;
            if (AreaEffectCloud.class.isAssignableFrom(type)) return CLOUD;
            if (Tameable.class.isAssignableFrom(type)) return TAMEABLE;
            if (Creeper.class.isAssignableFrom(type)) return CREEPER;
            if (Creature.class.isAssignableFrom(type)) return CREATURE;
            if (BlockProjectileSource.class.isAssignableFrom(type)) return BLOCK_PROJECTILE;
            if (LightningStrike.class.isAssignableFrom(type)) return LIGHTNING;
            if (FallingBlock.class.isAssignableFrom(type)) return FALLING_BLOCK;
            return NONE;
        }
    }

}
