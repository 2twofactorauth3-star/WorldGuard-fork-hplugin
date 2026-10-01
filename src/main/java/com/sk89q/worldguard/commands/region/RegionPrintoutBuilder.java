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

package com.sk89q.worldguard.commands.region;

import com.sk89q.worldguard.protection.flags.registry.UnknownFlag;
import org.enginehub.squirrelid.cache.ProfileCache;
import com.sk89q.worldedit.extension.platform.Actor;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.util.Location;
import com.sk89q.worldedit.util.formatting.component.ErrorFormat;
import com.sk89q.worldedit.util.formatting.component.LabelFormat;
import com.sk89q.worldedit.util.formatting.component.MessageBox;
import com.sk89q.worldedit.util.formatting.component.SubtleFormat;
import com.sk89q.worldedit.util.formatting.component.TextComponentProducer;
import com.sk89q.worldedit.util.formatting.text.TextComponent;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.bukkit.BukkitMessages;
import com.sk89q.worldguard.domains.DefaultDomain;
import com.sk89q.worldguard.internal.permission.RegionPermissionModel;
import com.sk89q.worldguard.protection.FlagValueCalculator;
import com.sk89q.worldguard.protection.association.RegionAssociable;
import com.sk89q.worldguard.protection.flags.Flag;
import com.sk89q.worldguard.protection.flags.Flags;
import com.sk89q.worldguard.protection.flags.RegionGroupFlag;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;

import java.util.ArrayList;
import java.util.List;
import java.util.ListIterator;
import java.util.concurrent.Callable;

import javax.annotation.Nullable;

/**
 * Create a region printout, as used in /region info to show information about
 * a region.
 */
public class RegionPrintoutBuilder implements Callable<TextComponent> {

    private final String world;
    private final ProtectedRegion region;
    @Nullable
    private final ProfileCache cache;
    private final TextComponentProducer builder = new TextComponentProducer();
    private final RegionPermissionModel perms;
    private final boolean showPlayerUuids;

    /**
     * Create a new instance with a region to report on.
     *
     * @param region the region
     * @param cache a profile cache, or {@code null}
     */
    public RegionPrintoutBuilder(String world, ProtectedRegion region, @Nullable ProfileCache cache, @Nullable Actor actor) {
        this(world, region, cache, actor, false);
    }

    public RegionPrintoutBuilder(String world, ProtectedRegion region, @Nullable ProfileCache cache,
                                 @Nullable Actor actor, boolean showPlayerUuids) {
        this.world = world;
        this.region = region;
        this.cache = cache;
        this.perms = actor != null && actor.isPlayer() ? new RegionPermissionModel(actor) : null;
        this.showPlayerUuids = showPlayerUuids;
    }

    /**
     * Add a new line.
     */
    public void newline() {
        builder.append(TextComponent.newline());
    }
    
    /**
     * Add region name, type, and priority.
     */
    public void appendBasics() {
        String key = perms != null && perms.maySetPriority(region)
                ? "regionInfoBasicsEditable" : "regionInfoBasics";
        builder.append(TextComponent.of(BukkitMessages.template(
                key,
                "world", world,
                "region", region.getId(),
                "type", region.getType().getName(),
                "priority", region.getPriority())));
        newline();
    }

    /**
     * Add information about flags.
     */
    public void appendFlags() {
        builder.append(TextComponent.of("@wg:regionInfoFlagsLabel@"));
        
        appendFlagsList(true);
        
        newline();
    }
    
    /**
     * Append just the list of flags (without "Flags:"), including colors.
     *
     * @param useColors true to use colors
     */
    public void appendFlagsList(boolean useColors) {
        boolean hasFlags = false;
        for (Flag<?> flag : WorldGuard.getInstance().getFlagRegistry()) {
            if (isHiddenFlag(flag)) continue;
            Object val = region.getFlag(flag);
            if (val == null) continue;
            appendFlag(flag, val, useColors, hasFlags);
            hasFlags = true;
        }

        if (!hasFlags) {
            builder.append(TextComponent.of(useColors
                    ? "@wg:regionInfoNoFlags@" : "@wg:regionInfoNoFlagsPlain@"));
        }

        if (perms != null && perms.maySetFlag(region)) {
            builder.append(TextComponent.of(BukkitMessages.template(
                    "buttonFlags", "world", world, "region", region.getId())));
        }
    }

    /**
     * Add information about parents.
     */
    public void appendParents() {
        appendParentTree(true);
    }
    
    /**
     * Add information about parents.
     * 
     * @param useColors true to use colors
     */
    public void appendParentTree(boolean useColors) {
        if (region.getParent() == null) {
            return;
        }
        
        List<ProtectedRegion> inheritance = new ArrayList<>();

        ProtectedRegion r = region;
        inheritance.add(r);
        while (r.getParent() != null) {
            r = r.getParent();
            inheritance.add(r);
        }

        ListIterator<ProtectedRegion> it = inheritance.listIterator(
                inheritance.size());

        ProtectedRegion last = null;
        int indent = 0;
        while (it.hasPrevious()) {
            ProtectedRegion cur = it.previous();

            // Put symbol for child
            String namePrefix = indent == 0 ? "" : " ".repeat(indent) + "⤷";

            boolean parent = !cur.equals(region);
            boolean clickable = perms != null && perms.mayLookup(cur);
            String key;
            if (parent) {
                key = clickable
                        ? "regionInfoInheritanceParentClickable" : "regionInfoInheritanceParent";
            } else {
                key = clickable
                        ? "regionInfoInheritanceCurrentClickable" : "regionInfoInheritanceCurrent";
            }
            builder.append(TextComponent.of(BukkitMessages.template(
                    key,
                    "indent", namePrefix,
                    "world", world,
                    "region", cur.getId(),
                    "priority", cur.getPriority())));
            if (last != null && cur.equals(region) && perms != null && perms.maySetParent(cur, last)) {
                builder.append(TextComponent.of(BukkitMessages.template(
                        "buttonUnlink", "world", world, "region", cur.getId())));
            }

            last = cur;
            indent++;
            newline();
        }
    }

    /**
     * Add information about members.
     */
    public void appendDomain() {
        builder.append(TextComponent.of("@wg:regionInfoOwnersLabel@"));
        addDomainString(region.getOwners(),
                perms != null && perms.mayAddOwners(region) ? "addowner" : null,
                perms != null && perms.mayRemoveOwners(region) ? "removeowner" : null);
        newline();

        builder.append(TextComponent.of("@wg:regionInfoMembersLabel@"));
        addDomainString(region.getMembers(),
                perms != null && perms.mayAddMembers(region) ? "addmember" : null,
                perms != null && perms.mayRemoveMembers(region) ? "removemember" : null);
        newline();
    }

    private void addDomainString(DefaultDomain domain, String addCommand, String removeCommand) {
        if (domain.size() == 0) {
            builder.append(ErrorFormat.wrap("@wg:none@"));
        } else {
            if (perms != null) {
                builder.append(domain.toUserFriendlyComponent(cache, showPlayerUuids));
            } else {
                builder.append(LabelFormat.wrap(domain.toUserFriendlyString(cache)));
            }
        }
        if (addCommand != null) {
            builder.append(TextComponent.of(BukkitMessages.template(
                    "buttonAdd",
                    "command", addCommand,
                    "world", world,
                    "region", region.getId())));
        }
        if (removeCommand != null && domain.size() > 0) {
            builder.append(TextComponent.of(BukkitMessages.template(
                    "buttonRemove",
                    "command", removeCommand,
                    "world", world,
                    "region", region.getId())));
            builder.append(TextComponent.of(BukkitMessages.template(
                    "buttonClear",
                    "command", removeCommand,
                    "world", world,
                    "region", region.getId())));
        }
    }

    /**
     * Add information about coordinates.
     */
    public void appendBounds() {
        if (!region.isPhysicalArea()) {
            builder.append(TextComponent.of("@wg:regionInfoBoundsGlobal@"));
        } else {
            BlockVector3 min = region.getMinimumPoint();
            BlockVector3 max = region.getMaximumPoint();
            String key = perms != null && perms.maySelect(region)
                    ? "regionInfoBoundsSelectable" : "regionInfoBounds";
            builder.append(TextComponent.of(BukkitMessages.template(
                    key,
                    "region", region.getId(),
                    "minimum", formatPoint(min),
                    "maximum", formatPoint(max))));
        }
        final Location teleFlag = FlagValueCalculator.getEffectiveFlagOf(region, Flags.TELE_LOC, perms != null && perms.getSender() instanceof RegionAssociable ? (RegionAssociable) perms.getSender() : null);
        if (teleFlag != null && perms != null && perms.mayTeleportTo(region)) {
            builder.append(TextComponent.of(BukkitMessages.template(
                    "buttonTeleport",
                    "world", world,
                    "region", region.getId(),
                    "x", teleFlag.getBlockX(),
                    "y", teleFlag.getBlockY(),
                    "z", teleFlag.getBlockZ())));
        } else if (perms != null && perms.mayTeleportToCenter(region) && region.isPhysicalArea()) {
            builder.append(TextComponent.of(BukkitMessages.template(
                    "buttonTeleportCenter", "world", world, "region", region.getId())));
        }

        newline();
    }

    private static String formatPoint(BlockVector3 point) {
        return "(" + point.x() + ", " + point.y() + ", " + point.z() + ")";
    }

    private void appendRegionInformation() {
        appendBasics();
        appendFlags();
        appendParents();
        appendDomain();
        appendBounds();

        if (cache != null && perms == null) {
            builder.append(SubtleFormat.wrap("@wg:regionInfoStaleNames@"));
        }
    }

    private boolean isHiddenFlag(Flag<?> flag) {
        return flag == Flags.BLOCKED_CMDS_MESSAGES
                && !WorldGuard.getInstance().getPlatform().getGlobalStateManager()
                .showBlockedCommandMessagesInRegionInfo;
    }

    private void appendFlag(Flag<?> flag, Object value, boolean useColors, boolean hasFlags) {
        RegionGroupFlag groupFlag = flag.getRegionGroupFlag();
        Object group = groupFlag == null ? null : region.getFlag(groupFlag);
        boolean editable = perms != null && perms.maySetFlag(region, flag);
        builder.append(TextComponent.of(BukkitMessages.template(
                flagMessageKey(flagStyle(flag, useColors), editable),
                "separator", hasFlags ? ", " : "",
                "world", world,
                "region", region.getId(),
                "flag", flag.getName(),
                "group", group == null ? "" : " -g " + group,
                "value", value)));
    }

    private String flagStyle(Flag<?> flag, boolean useColors) {
        if (!useColors) return "Plain";
        if (FlagHelperBox.DANGER_ZONE.contains(flag)
                && !(region.getId().equals(ProtectedRegion.GLOBAL_REGION)
                && flag == Flags.PASSTHROUGH)) return "Danger";
        if (Flags.INBUILT_FLAGS.contains(flag.getName())) return "Builtin";
        if (flag instanceof UnknownFlag) return "Unknown";
        return "ThirdParty";
    }

    private static String flagMessageKey(String style, boolean editable) {
        return switch (style) {
            case "Plain" -> editable ? "regionInfoFlagPlainEditable" : "regionInfoFlagPlain";
            case "Danger" -> editable ? "regionInfoFlagDangerEditable" : "regionInfoFlagDanger";
            case "Builtin" -> editable ? "regionInfoFlagBuiltinEditable" : "regionInfoFlagBuiltin";
            case "Unknown" -> editable ? "regionInfoFlagUnknownEditable" : "regionInfoFlagUnknown";
            default -> editable
                    ? "regionInfoFlagThirdPartyEditable" : "regionInfoFlagThirdParty";
        };
    }

    @Override
    public TextComponent call() {
        MessageBox box = new MessageBox("@wg:regionInfoTitle@", builder);
        appendRegionInformation();
        return box.create();
    }

    /**
     * Send the report to a {@link Actor}.
     *
     * @param sender the recipient
     */
    public void send(Actor sender) {
        sender.print(toComponent());
    }

    public TextComponentProducer append(String str) {
        return builder.append(TextComponent.of(str));
    }

    public TextComponentProducer append(TextComponent component) {
        return builder.append(component);
    }

    public TextComponent toComponent() {
        return builder.create();
    }

    @Override
    public String toString() {
        return builder.toString().trim();
    }

}
