package chronoq.heap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DaryHeapTest {

    private static final Comparator<Integer> NATURAL = Comparator.naturalOrder();

    @Test
    void rejectsInvalidArguments() {
        assertThrows(IllegalArgumentException.class, () -> new DaryHeap<>(-1, NATURAL));
        assertThrows(IllegalArgumentException.class, () -> new DaryHeap<>(31, NATURAL));
        assertThrows(NullPointerException.class, () -> new DaryHeap<Integer>(2, null));
        assertThrows(NullPointerException.class, () -> new DaryHeap<>(2, NATURAL).add(null));
    }

    @Test
    void reportsArity() {
        assertEquals(1, new DaryHeap<>(0, NATURAL).arity());
        assertEquals(4, new DaryHeap<>(2, NATURAL).arity());
        assertEquals(1 << 30, new DaryHeap<>(30, NATURAL).arity());
    }

    @Test
    void emptyHeapReturnsNull() {
        DaryHeap<Integer> heap = new DaryHeap<>(2, NATURAL);
        assertTrue(heap.isEmpty());
        assertNull(heap.peek());
        assertNull(heap.peekHandle());
        assertNull(heap.poll());
        assertEquals(0, heap.size());
    }

    @ParameterizedTest(name = "2^{0} children per node")
    @ValueSource(ints = {0, 1, 2, 3, 5, 8, 30})
    void pollsInSortedOrder(int exponent) {
        Random rnd = new Random(exponent);
        DaryHeap<Integer> heap = new DaryHeap<>(exponent, NATURAL);
        List<Integer> expected = new ArrayList<>();
        int n = exponent == 0 ? 2_000 : 20_000; // arity 1 is a sorted chain: O(n) per insert
        for (int i = 0; i < n; i++) {
            int v = rnd.nextInt(1_000); // small range forces many duplicates
            heap.add(v);
            expected.add(v);
        }
        heap.checkInvariants();
        expected.sort(NATURAL);
        for (int v : expected) {
            assertEquals(v, heap.poll());
        }
        assertTrue(heap.isEmpty());
    }

    /**
     * Random adds, polls and handle removals, checked step by step against java.util.PriorityQueue.
     * After every operation the heap's order and every handle's recorded slot are verified.
     */
    @ParameterizedTest(name = "2^{0} children per node")
    @ValueSource(ints = {0, 1, 2, 3, 4, 6, 30})
    void matchesPriorityQueueUnderRandomOperations(int exponent) {
        Random rnd = new Random(1000 + exponent);
        DaryHeap<Integer> heap = new DaryHeap<>(exponent, NATURAL);
        PriorityQueue<Integer> reference = new PriorityQueue<>();
        List<DaryHeap.Handle<Integer>> live = new ArrayList<>();

        for (int op = 0; op < 20_000; op++) {
            int choice = rnd.nextInt(10);
            if (choice < 5 || reference.isEmpty()) {
                int v = rnd.nextInt(500);
                live.add(heap.add(v));
                reference.add(v);
            } else if (choice < 7) {
                Integer polled = heap.poll();
                assertEquals(reference.poll(), polled);
            } else {
                DaryHeap.Handle<Integer> h = live.remove(rnd.nextInt(live.size()));
                if (h.isQueued()) {
                    assertTrue(heap.remove(h));
                    assertTrue(reference.remove(h.element()));
                } else {
                    assertFalse(heap.remove(h), "a polled element's handle must not remove anything");
                }
            }
            assertEquals(reference.size(), heap.size());
            assertEquals(reference.peek(), heap.peek());
            heap.checkInvariants();
        }
    }

    @Test
    void handleIsUsableOnlyOnce() {
        DaryHeap<Integer> heap = new DaryHeap<>(2, NATURAL);
        DaryHeap.Handle<Integer> h = heap.add(7);
        heap.add(3);
        assertTrue(h.isQueued());
        assertTrue(heap.remove(h));
        assertFalse(h.isQueued());
        assertFalse(heap.remove(h));
        assertEquals(1, heap.size());
        assertEquals(3, heap.peek());
    }

    @Test
    void handleOfPolledElementIsNoLongerQueued() {
        DaryHeap<Integer> heap = new DaryHeap<>(1, NATURAL);
        DaryHeap.Handle<Integer> h = heap.add(1);
        heap.add(2);
        assertSame(h, heap.peekHandle());
        assertEquals(1, heap.poll());
        assertFalse(h.isQueued());
        assertFalse(heap.remove(h));
        assertEquals(1, heap.size());
    }

    @Test
    void handleFromAnotherHeapIsRejected() {
        DaryHeap<Integer> a = new DaryHeap<>(2, NATURAL);
        DaryHeap<Integer> b = new DaryHeap<>(2, NATURAL);
        DaryHeap.Handle<Integer> fromA = a.add(1);
        b.add(1);
        assertFalse(b.remove(fromA));
        assertFalse(b.remove(null));
        assertEquals(1, b.size());
        assertTrue(fromA.isQueued());
    }

    @Test
    void removingTheLastSlotAndTheRootWorks() {
        DaryHeap<Integer> heap = new DaryHeap<>(2, NATURAL);
        List<DaryHeap.Handle<Integer>> hs = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            hs.add(heap.add(i));
        }
        assertTrue(heap.remove(hs.get(9))); // the last slot: nothing needs to move
        assertTrue(heap.remove(hs.get(0))); // the root
        heap.checkInvariants();
        for (int expected = 1; expected <= 8; expected++) {
            assertEquals(expected, heap.poll());
        }
    }

    @Test
    void clearDetachesEveryHandle() {
        DaryHeap<Integer> heap = new DaryHeap<>(2, NATURAL);
        List<DaryHeap.Handle<Integer>> hs = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            hs.add(heap.add(i));
        }
        heap.clear();
        assertTrue(heap.isEmpty());
        assertTrue(hs.stream().noneMatch(DaryHeap.Handle::isQueued));
        heap.checkInvariants();
        heap.add(5);
        assertEquals(5, heap.poll());
    }

    @Test
    void comparatorDecidesTheOrder() {
        DaryHeap<String> byLength = new DaryHeap<>(2, Comparator.comparingInt(String::length));
        byLength.add("ccc");
        byLength.add("a");
        byLength.add("bb");
        assertEquals("a", byLength.poll());
        assertEquals("bb", byLength.poll());
        assertEquals("ccc", byLength.poll());
    }

    @Test
    void growthNeverOverflows() {
        assertEquals(1, DaryHeap.grownCapacity(0));
        assertEquals(25, DaryHeap.grownCapacity(16));
        assertEquals(Integer.MAX_VALUE - 8, DaryHeap.grownCapacity(1_500_000_000));
        assertThrows(IllegalStateException.class, () -> DaryHeap.grownCapacity(Integer.MAX_VALUE - 8));
    }
}
