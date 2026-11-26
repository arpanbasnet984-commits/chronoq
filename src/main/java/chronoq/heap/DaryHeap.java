package chronoq.heap;

import java.util.Arrays;
import java.util.Comparator;
import java.util.Objects;

/**
 * A min-heap in which every parent has 2^k children (a d-ary heap with d = 2, 4, 8, ...),
 * ordered by a {@link Comparator}.
 *
 * <p>Two things set it apart from {@link java.util.PriorityQueue}:
 * <ul>
 *   <li><b>Shift-based indexing.</b> Because the arity is a power of two, finding a parent or the first
 *       child is a bit shift: {@code parent(i) = (i - 1) >>> k} and {@code firstChild(i) = (i << k) + 1}.
 *       A wider heap is also shallower, so it has fewer levels to climb on insert.</li>
 *   <li><b>Handles.</b> {@link #add} returns a {@link Handle} that always knows its element's current slot.
 *       {@link #remove(Handle)} therefore takes O(d log_d n), instead of the O(n) linear search that
 *       {@code PriorityQueue.remove(Object)} needs. A task scheduler cancels tasks all the time, so this
 *       is the operation it depends on.</li>
 * </ul>
 *
 * <p>Costs with n elements and arity d: {@code add} O(log_d n), {@code poll} and {@code remove} O(d log_d n),
 * {@code peek} and {@code size} O(1).
 *
 * <p>Not thread-safe. The scheduler only touches it while holding its own lock.
 *
 * @param <E> element type
 */
public final class DaryHeap<E> {

    /** A reference to one element's position in a heap, used to remove it in logarithmic time. */
    public static final class Handle<E> {
        private final E element;
        private DaryHeap<E> owner; // null once the element has left the heap
        private int index;

        private Handle(E element, DaryHeap<E> owner) {
            this.element = element;
            this.owner = owner;
        }

        public E element() {
            return element;
        }

        /** Whether the element is still in a heap. False after it is polled, removed or cleared. */
        public boolean isQueued() {
            return owner != null;
        }
    }

    /** Arity 2^30 is already far larger than any useful heap; 31 would break int shifts. */
    public static final int MAX_ARITY_EXPONENT = 30;

    private static final int MAX_CAPACITY = Integer.MAX_VALUE - 8;

    private final int shift;
    private final int maxChildOffset;
    private final Comparator<? super E> comparator;
    private Handle<E>[] slots;
    private int size;

    /**
     * @param arityExponent k, where every parent has 2^k children; 0 to 30 (1 = binary, 2 = 4-ary, ...)
     * @param comparator    defines the order; the smallest element is at the top
     */
    @SuppressWarnings("unchecked")
    public DaryHeap(int arityExponent, Comparator<? super E> comparator) {
        if (arityExponent < 0 || arityExponent > MAX_ARITY_EXPONENT) {
            throw new IllegalArgumentException(
                    "arityExponent must be between 0 and " + MAX_ARITY_EXPONENT + ", was " + arityExponent);
        }
        this.comparator = Objects.requireNonNull(comparator, "comparator");
        this.shift = arityExponent;
        this.maxChildOffset = (1 << arityExponent) - 1;
        this.slots = (Handle<E>[]) new Handle<?>[16];
    }

    /** Number of children per parent, 2^arityExponent. */
    public int arity() {
        return 1 << shift;
    }

    public int size() {
        return size;
    }

    public boolean isEmpty() {
        return size == 0;
    }

    /** Adds an element and returns a handle that can later remove it in O(d log_d n). */
    public Handle<E> add(E element) {
        Objects.requireNonNull(element, "element");
        if (size == slots.length) {
            slots = Arrays.copyOf(slots, grownCapacity(slots.length));
        }
        Handle<E> handle = new Handle<>(element, this);
        siftUp(size++, handle);
        return handle;
    }

    /** The smallest element, or null if the heap is empty. */
    public E peek() {
        return size == 0 ? null : slots[0].element;
    }

    /** The handle of the smallest element, or null if the heap is empty. */
    public Handle<E> peekHandle() {
        return size == 0 ? null : slots[0];
    }

    /** Removes and returns the smallest element, or null if the heap is empty. */
    public E poll() {
        if (size == 0) {
            return null;
        }
        E top = slots[0].element;
        removeAt(0);
        return top;
    }

    /**
     * Removes the element behind {@code handle}.
     *
     * @return true if it was removed; false if it was already gone or belongs to a different heap
     */
    public boolean remove(Handle<E> handle) {
        if (handle == null || handle.owner != this) {
            return false;
        }
        removeAt(handle.index);
        return true;
    }

    /** Removes every element. Every outstanding handle reports {@code isQueued() == false}. */
    public void clear() {
        for (int i = 0; i < size; i++) {
            slots[i].owner = null;
            slots[i] = null;
        }
        size = 0;
    }

    // ------------------------------------------------------------------------------------------ internals

    private void removeAt(int index) {
        Handle<E> removed = slots[index];
        removed.owner = null;
        int last = --size;
        Handle<E> moved = slots[last];
        slots[last] = null;
        if (index == last) {
            return; // removed the last slot: nothing to fill
        }
        // The last element fills the hole. It may belong above the hole or below it, but never both.
        if (index > 0 && comparator.compare(moved.element, slots[(index - 1) >>> shift].element) < 0) {
            siftUp(index, moved);
        } else {
            siftDown(index, moved);
        }
    }

    /**
     * Moves {@code handle} up from {@code hole}. Smaller-ranked parents are shifted down into the hole,
     * and the handle is written once at its final slot, which needs fewer writes than swapping.
     */
    private void siftUp(int hole, Handle<E> handle) {
        final E element = handle.element;
        while (hole > 0) {
            int parentIndex = (hole - 1) >>> shift;
            Handle<E> parent = slots[parentIndex];
            if (comparator.compare(element, parent.element) >= 0) {
                break;
            }
            place(parent, hole);
            hole = parentIndex;
        }
        place(handle, hole);
    }

    /**
     * Moves {@code handle} down from {@code hole}, each time promoting the smallest child into the hole.
     *
     * <p>No int overflow: a node has children only if {@code (i << shift) + 1 <= size - 1}, that is
     * {@code i <= (size - 2) >> shift}. Inside the loop the first child is therefore at most size - 1.
     * The signed shift makes lastParent -1 when size is 1, so the loop is simply skipped.
     */
    private void siftDown(int hole, Handle<E> handle) {
        final E element = handle.element;
        final int lastIndex = size - 1;
        final int lastParent = (size - 2) >> shift;
        while (hole <= lastParent) {
            int firstChild = (hole << shift) + 1;
            int lastChild = firstChild + Math.min(maxChildOffset, lastIndex - firstChild);
            int bestIndex = firstChild;
            E best = slots[firstChild].element;
            for (int c = firstChild + 1; c <= lastChild; c++) {
                E candidate = slots[c].element;
                if (comparator.compare(candidate, best) < 0) {
                    best = candidate;
                    bestIndex = c;
                }
            }
            if (comparator.compare(best, element) >= 0) {
                break;
            }
            place(slots[bestIndex], hole);
            hole = bestIndex;
        }
        place(handle, hole);
    }

    private void place(Handle<E> handle, int index) {
        slots[index] = handle;
        handle.index = index;
    }

    static int grownCapacity(int oldCapacity) {
        if (oldCapacity >= MAX_CAPACITY) {
            throw new IllegalStateException("heap is full (" + MAX_CAPACITY + " elements)");
        }
        int grown = oldCapacity + (oldCapacity >> 1) + 1;
        return (grown < 0 || grown > MAX_CAPACITY) ? MAX_CAPACITY : grown;
    }

    /** Test hook: throws if the heap order or any handle's recorded slot is wrong. */
    void checkInvariants() {
        for (int i = 0; i < size; i++) {
            Handle<E> h = slots[i];
            if (h.owner != this || h.index != i) {
                throw new AssertionError("handle at slot " + i + " records slot " + h.index);
            }
            if (i > 0 && comparator.compare(slots[(i - 1) >>> shift].element, h.element) > 0) {
                throw new AssertionError("heap order violated at slot " + i);
            }
        }
        for (int i = size; i < slots.length; i++) {
            if (slots[i] != null) {
                throw new AssertionError("stale reference left at slot " + i);
            }
        }
    }
}
