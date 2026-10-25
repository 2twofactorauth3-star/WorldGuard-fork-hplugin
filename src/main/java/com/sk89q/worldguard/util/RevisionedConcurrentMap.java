package com.sk89q.worldguard.util;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class RevisionedConcurrentMap<K, V> extends AbstractMap<K, V> implements ConcurrentMap<K, V> {
    private final ConcurrentMap<K, V> values;
    private final Runnable changed;

    public RevisionedConcurrentMap(@Nonnull Map<? extends K, ? extends V> initial, @Nonnull Runnable changed) {
        values = new ConcurrentHashMap<>(initial);
        this.changed = changed;
    }

    @Override
    @Nullable
    public V get(@Nonnull Object key) { return values.get(key); }

    @Override
    public boolean containsKey(@Nonnull Object key) { return values.containsKey(key); }

    @Override
    public int size() { return values.size(); }

    @Override
    @Nullable
    public V put(@Nonnull K key, @Nonnull V value) {
        V previous = values.put(key, value);
        if (!Objects.equals(previous, value)) changed.run();
        return previous;
    }

    @Override
    @Nullable
    public V putIfAbsent(@Nonnull K key, @Nonnull V value) {
        V previous = values.putIfAbsent(key, value);
        if (previous == null) changed.run();
        return previous;
    }

    @Override
    @Nullable
    public V remove(@Nonnull Object key) {
        V previous = values.remove(key);
        if (previous != null) changed.run();
        return previous;
    }

    @Override
    public boolean remove(@Nonnull Object key, @Nullable Object value) {
        boolean removed = values.remove(key, value);
        if (removed) changed.run();
        return removed;
    }

    @Override
    @Nullable
    public V replace(@Nonnull K key, @Nonnull V value) {
        V previous = values.replace(key, value);
        if (previous != null && !Objects.equals(previous, value)) changed.run();
        return previous;
    }

    @Override
    public boolean replace(@Nonnull K key, @Nonnull V previous, @Nonnull V value) {
        boolean replaced = values.replace(key, previous, value);
        if (replaced && !Objects.equals(previous, value)) changed.run();
        return replaced;
    }

    @Override
    public void clear() {
        if (!values.isEmpty()) {
            values.clear();
            changed.run();
        }
    }

    @Override
    @Nonnull
    public Set<Entry<K, V>> entrySet() {
        return new AbstractSet<>() {
            @Override
            public int size() { return values.size(); }

            @Override
            public void clear() { RevisionedConcurrentMap.this.clear(); }

            @Override
            public boolean contains(@Nullable Object value) { return values.entrySet().contains(value); }

            @Override
            public boolean remove(@Nullable Object value) {
                boolean removed = values.entrySet().remove(value);
                if (removed) changed.run();
                return removed;
            }

            @Override
            public boolean add(@Nonnull Entry<K, V> entry) {
                return !Objects.equals(put(entry.getKey(), entry.getValue()), entry.getValue());
            }

            @Override
            @Nonnull
            public Iterator<Entry<K, V>> iterator() {
                Iterator<Entry<K, V>> iterator = values.entrySet().iterator();
                return new Iterator<>() {
                    @Override
                    public boolean hasNext() { return iterator.hasNext(); }

                    @Override
                    @Nonnull
                    public Entry<K, V> next() {
                        Entry<K, V> entry = iterator.next();
                        return new SimpleEntry<>(entry) {
                            @Serial
                            private static final long serialVersionUID = 1L;

                            @Override
                            @Nonnull
                            public V setValue(@Nonnull V value) {
                                put(getKey(), value);
                                return super.setValue(value);
                            }
                        };
                    }

                    @Override
                    public void remove() {
                        iterator.remove();
                        changed.run();
                    }
                };
            }
        };
    }
}
