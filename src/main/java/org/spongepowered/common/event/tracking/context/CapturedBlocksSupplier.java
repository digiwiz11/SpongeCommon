/*
 * This file is part of Sponge, licensed under the MIT License (MIT).
 *
 * Copyright (c) SpongePowered <https://www.spongepowered.org>
 * Copyright (c) contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package org.spongepowered.common.event.tracking.context;

import static com.google.common.base.Preconditions.checkElementIndex;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkPositionIndex;
import static com.google.common.base.Preconditions.checkPositionIndexes;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.CollectPreconditions.checkRemove;

import com.google.common.collect.ListMultimap;
import com.google.common.collect.Multimap;
import com.google.common.collect.Multimaps;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.api.block.BlockSnapshot;
import org.spongepowered.api.world.BlockChangeFlag;
import org.spongepowered.common.util.NonNullArrayList;
import org.spongepowered.common.util.VecHelper;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.ListIterator;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.annotation.Nullable;

public final class CapturedBlocksSupplier implements Supplier<List<BlockSnapshot>>, ICaptureSupplier {

    // Generalized list of block snapshots. If a second snapshot is added for a pre-existing position
    // then the multimap listed below will be populated, and the insertion order of block changes can
    // be referenced from the usedPosses set, which is ordered.
    @Nullable private List<BlockSnapshot> list;
    @Nullable private Set<BlockPos> usedPosses;
    // Lazily created multimap in the event a second block snapshot is being added
    // after the initial list of captured block snapshots is created.
    @Nullable Multimap<BlockPos, BlockSnapshot> captured;

    public CapturedBlocksSupplier() {
    }


    public void add(BlockPos pos, BlockSnapshot snapshot) {
        if (this.list == null && this.captured == null) { // Means we can just capture using the list
            this.list = new NonNullArrayList<>();
            this.usedPosses = new LinkedHashSet<>();
            this.list.add(snapshot);
            this.usedPosses.add(pos);
        } else if (this.list != null && this.captured == null) {
            // If the list is not null, check if we've already captured a same position
            if (this.usedPosses.contains(pos)) {
                // At this point, we've already captured the relevant position, and the multimap is not populated...
                // so... we populate it.
                this.captured = Multimaps.newListMultimap(new HashMap<>(), NonNullArrayList::new);
                for (BlockSnapshot blockSnapshot : this.list) {
                    this.captured.put(VecHelper.toBlockPos(blockSnapshot.getPosition()), blockSnapshot);
                }
                // Now we can add to the multimap
                this.captured.put(pos, snapshot);
                // And just clean up so the if statements fail faster
                this.list.clear();
                this.list = null;
                this.usedPosses.clear();
                this.usedPosses = null;
                return;
            }
            // Otherwise, if we haven't captured this position, just add it to the general list.
            this.usedPosses.add(pos);
            this.list.add(snapshot);
            return;
        }
        // Otherwise, we now know that captured is not null, and we've already tracked that position.
        this.captured.put(pos, snapshot);
    }

    @Override
    public final List<BlockSnapshot> get() {
        if (this.list != null) {
            // We've only been capturing single block transactions per position
            return this.list;
        } else if (this.captured == null) {
            this.list = new NonNullArrayList<>();
            return this.list;
        }
        // Otherwise, we're going to need ot return a "list view"
        return this.captured.asMap()
            .values()
            .stream()
            .filter(list -> !list.isEmpty())
            .map(collection -> (List<BlockSnapshot>) collection)
            .map(list -> list.get(list.size() - 1))
            .collect(Collectors.toList());
    }

    /**
     * Returns {@code true} if there are no captured objects.
     *
     * @return {@code true} if empty
     */
    @Override
    public final boolean isEmpty() {
        return this.captured == null || this.captured.isEmpty();
    }

    /**
     * If not empty, activates the consumer then clears all captures.
     *
     * @param consumer The consumer to activate
     */
    public final void acceptAndClearIfNotEmpty(Consumer<List<BlockSnapshot>> consumer) {
        if (!this.isEmpty()) {
            final List<BlockSnapshot> consumed = get();
            this.captured.clear(); // We should be clearing after it is processed. Avoids extraneous issues
            consumer.accept(consumed);
            // with recycling the captured object.
        }
    }

    /**
     * If not empty, returns the captured {@link List}.
     * Otherwise, this will return the passed list.
     *
     * @param list The fallback list
     * @return If not empty, the captured list otherwise the fallback list
     */
    public final List<BlockSnapshot> orElse(List<BlockSnapshot> list) {
        return this.isEmpty() ? list : this.get();
    }

    public final List<BlockSnapshot> orEmptyList() {
        return this.captured == null ? Collections.emptyList() : this.get();
    }

    /**
     * If not empty, returns a sequential stream of values associated with key.
     *
     * @return A sequential stream of values
     */
    public final Stream<BlockSnapshot> stream() {
        return this.captured == null ? Stream.empty() : this.get().stream();
    }

    /**
     * This is to be used in the case of needing to traverse the reversed list
     * of snapshots. Usually restricted to undoing snapshots or calling
     * {@link BlockSnapshot#restore(boolean, BlockChangeFlag)} in the reverse
     * order in which the snapshots were added.
     *
     * <p>Note: Because of the possibility of multiple {@link BlockSnapshot}
     * changes for a single position, this list can either be the backing list,
     * or a "viewer" {@link ReverseList} backed by this supplier's {@link #captured}
     * {@link Multimap}. In the event a reverse list is used, it has been optimized
     * to still retain insertion order of block snapshots being added to the list
     * in the order of which they were received (FIFO), however, notifications
     * of neighboring blocks mya have been processed intermittently due to
     * vanilla mechanics.</p>
     *
     * @return
     */
    public final List<BlockSnapshot> reverse() {

    }

    class ReverseList extends AbstractList<BlockSnapshot> {




        ReverseList() {
        }


        @Override
        public void add(int index, @Nullable BlockSnapshot element) {
            throw new UnsupportedOperationException("Cannot insert into a multimap at specific indecies");
        }

        private int reversePosition(int index) {
            int size = size();
            checkPositionIndex(index, size);
            return size - index;
        }

        @Override
        public int size() {
            return CapturedBlocksSupplier.this.captured == null ? 0 : CapturedBlocksSupplier.this.captured.keys().size();
        }

        @Override
        public BlockSnapshot set(int index, @Nullable BlockSnapshot element) {
            throw new UnsupportedOperationException("Cannot insert into a multimap at specific indecies");
        }

        @Override
        public BlockSnapshot get(int index) {
            final Multimap<BlockPos, BlockSnapshot> captured = CapturedBlocksSupplier.this.captured;
            if (captured == null || captured.isEmpty()) {
                return null;
            }
            for
            return ((ListMultimap<BlockPos, BlockSnapshot>) captured).;
        }

        @Override
        public BlockSnapshot remove(int index) {
            for (int i = 0; i < CapturedBlocksSupplier)
            return forwardList.remove(reverseIndex(index));
        }

        private int reverseIndex(int index) {
            int size = size();
            checkElementIndex(index, size);
            return (size - 1) - index;
        }

        @Override
        public void clear() {
            throw new UnsupportedOperationException("Cannot clear a multimap's filtered values");
        }

        @Override
        public Iterator<BlockSnapshot> iterator() {
            return listIterator();
        }

        @Override
        public ListIterator<BlockSnapshot> listIterator(int index) {
            int start = reversePosition(index);
            final ListIterator<BlockSnapshot> forwardIterator = forwardList.listIterator(start);
            return new ListIterator<BlockSnapshot>() {

                boolean canRemoveOrSet;

                @Override
                public void add(BlockSnapshot e) {
                    forwardIterator.add(e);
                    forwardIterator.previous();
                    this.canRemoveOrSet = false;
                }

                @Override
                public boolean hasNext() {
                    return forwardIterator.hasPrevious();
                }

                @Override
                public boolean hasPrevious() {
                    return forwardIterator.hasNext();
                }

                @Override
                public BlockSnapshot next() {
                    if (!hasNext()) {
                        throw new NoSuchElementException();
                    }
                    this.canRemoveOrSet = true;
                    return forwardIterator.previous();
                }

                @Override
                public int nextIndex() {
                    return reversePosition(forwardIterator.nextIndex());
                }

                @Override
                public BlockSnapshot previous() {
                    if (!hasPrevious()) {
                        throw new NoSuchElementException();
                    }
                    this.canRemoveOrSet = true;
                    return forwardIterator.next();
                }

                @Override
                public int previousIndex() {
                    return nextIndex() - 1;
                }

                @Override
                public void remove() {
                    checkRemove(this.canRemoveOrSet);
                    forwardIterator.remove();
                    this.canRemoveOrSet = false;
                }

                @Override
                public void set(T e) {
                    checkState(this.canRemoveOrSet);
                    forwardIterator.set(e);
                }
            };

        }

        @Override
        public List<BlockSnapshot> subList(int fromIndex, int toIndex) {
            checkPositionIndexes(fromIndex, toIndex, size());
            return reverse(forwardList.subList(reversePosition(toIndex), reversePosition(fromIndex)));
        }

        @Override
        protected void removeRange(int fromIndex, int toIndex) {
            throw new UnsupportedOperationException("cannot remove from filtered list");
        }
    }

}
