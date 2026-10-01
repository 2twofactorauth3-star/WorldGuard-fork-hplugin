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

package com.sk89q.worldguard.bukkit.listener.debounce;

import com.sk89q.worldguard.bukkit.util.Events;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;

import javax.annotation.Nullable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

public class EventDebounce<K> {

    private static final int GENERATION_CAPACITY = 1024;

    private final long debounceNanos;
    private volatile ConcurrentMap<K, Entry> current = new ConcurrentHashMap<>();
    private volatile ConcurrentMap<K, Entry> previous = new ConcurrentHashMap<>();
    private final AtomicInteger currentSize = new AtomicInteger();

    public EventDebounce(int debounceTime) {
        debounceNanos = debounceTime * 1_000_000L;
    }

    public <T extends Event & Cancellable> void fireToCancel(Cancellable originalEvent, T firedEvent, K key) {
        Entry entry = getOrCreate(key);
        synchronized (entry) {
            if (entry.cancelled != null) {
                if (entry.cancelled) {
                    originalEvent.setCancelled(true);
                }
            } else {
                boolean cancelled = Events.fireAndTestCancel(firedEvent);
                if (cancelled) {
                    originalEvent.setCancelled(true);
                }
                entry.cancelled = cancelled;
            }
        }
    }

    @Nullable
    public <T extends Event & Cancellable> Entry getIfNotPresent(K key, Cancellable originalEvent) {
        Entry entry = getOrCreate(key);
        synchronized (entry) {
            if (entry.cancelled != null) {
                if (entry.cancelled) {
                    originalEvent.setCancelled(true);
                }
                return null;
            }
            return entry;
        }
    }

    private Entry getOrCreate(K key) {
        long now = System.nanoTime();
        Entry entry = current.get(key);
        if (entry == null) {
            entry = previous.get(key);
        }
        if (entry != null && now < entry.expiresAtNanos) {
            return entry;
        }

        Entry created = new Entry(now + debounceNanos);
        Entry raced = current.putIfAbsent(key, created);
        if (raced != null && now < raced.expiresAtNanos) {
            return raced;
        }
        if (raced != null) {
            current.replace(key, raced, created);
        }
        if (currentSize.incrementAndGet() >= GENERATION_CAPACITY) {
            rotate();
        }
        return created;
    }

    private synchronized void rotate() {
        if (currentSize.get() < GENERATION_CAPACITY) {
            return;
        }
        previous = current;
        current = new ConcurrentHashMap<>();
        currentSize.set(0);
    }

    public static <K> EventDebounce<K> create(int debounceTime) {
        return new EventDebounce<>(debounceTime);
    }

    public static class Entry {
        public final long expiresAtNanos;
        private volatile Boolean cancelled;

        private Entry(long expiresAtNanos) {
            this.expiresAtNanos = expiresAtNanos;
        }

        public void setCancelled(boolean cancelled) {
            this.cancelled = cancelled;
        }
    }

}
