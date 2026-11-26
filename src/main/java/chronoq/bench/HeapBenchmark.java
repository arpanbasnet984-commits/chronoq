package chronoq.bench;

import chronoq.heap.DaryHeap;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.function.LongSupplier;

/**
 * Rough benchmark of {@link DaryHeap} against {@link PriorityQueue}. Each case gets warm-up rounds and
 * then the best of several timed rounds. It is not a JMH harness, so read the numbers as relative.
 *
 * <ol>
 *   <li><b>Push + pop:</b> insert N random longs, then remove them all in order.</li>
 *   <li><b>Cancel:</b> fill a queue with N tasks, then remove C of them chosen at random. A scheduler
 *       does this whenever a task is cancelled. PriorityQueue has to search for the element; DaryHeap
 *       already has a handle to it.</li>
 * </ol>
 */
public final class HeapBenchmark {

    private static final int PUSH_POP_N = 1_000_000;
    private static final int CANCEL_N = 100_000;
    private static final int CANCEL_COUNT = 10_000;
    private static final int WARMUP = 3;
    private static final int ROUNDS = 5;

    private static long sink; // keeps the JIT from discarding the work

    private HeapBenchmark() {
    }

    public static void main(String[] args) {
        Random rnd = new Random(42);
        long[] values = new long[PUSH_POP_N];
        for (int i = 0; i < values.length; i++) {
            values[i] = rnd.nextLong();
        }

        System.out.printf("Push + pop: %,d random longs (ms, best of %d)%n", PUSH_POP_N, ROUNDS);
        double pq = best(() -> pushPopPriorityQueue(values));
        System.out.printf("  %-34s %8.1f%n", "java.util.PriorityQueue (binary)", pq);
        for (int k = 1; k <= 4; k++) {
            final int exp = k;
            double t = best(() -> pushPopDary(exp, values));
            System.out.printf("  %-34s %8.1f   %.2fx PriorityQueue%n",
                    "DaryHeap, " + (1 << k) + " children per node", t, t / pq);
        }

        List<Long> ids = new ArrayList<>(CANCEL_N);
        for (long i = 0; i < CANCEL_N; i++) {
            ids.add(i * 7919 % CANCEL_N); // distinct values in scrambled order
        }
        List<Integer> toCancel = new ArrayList<>(CANCEL_N);
        for (int i = 0; i < CANCEL_N; i++) {
            toCancel.add(i);
        }
        Collections.shuffle(toCancel, new Random(7));
        int[] victims = toCancel.subList(0, CANCEL_COUNT).stream().mapToInt(Integer::intValue).toArray();

        System.out.printf("%nCancel: remove %,d of %,d queued tasks (ms, best of %d)%n",
                CANCEL_COUNT, CANCEL_N, ROUNDS);
        double pqCancel = best(() -> cancelPriorityQueue(ids, victims));
        double daryCancel = best(() -> cancelDary(ids, victims));
        System.out.printf("  %-34s %8.1f%n", "PriorityQueue.remove(Object)", pqCancel);
        System.out.printf("  %-34s %8.1f   %.0fx faster%n", "DaryHeap.remove(handle), 4-ary", daryCancel,
                pqCancel / daryCancel);
        System.out.println("\n(checksum " + sink + ")");
    }

    private static double best(LongSupplier work) {
        for (int i = 0; i < WARMUP; i++) {
            sink += work.getAsLong();
        }
        long bestNs = Long.MAX_VALUE;
        for (int i = 0; i < ROUNDS; i++) {
            long t0 = System.nanoTime();
            sink += work.getAsLong();
            bestNs = Math.min(bestNs, System.nanoTime() - t0);
        }
        return bestNs / 1e6;
    }

    private static long pushPopPriorityQueue(long[] values) {
        PriorityQueue<Long> q = new PriorityQueue<>();
        for (long v : values) {
            q.add(v);
        }
        long acc = 0;
        Long v;
        while ((v = q.poll()) != null) {
            acc += v;
        }
        return acc;
    }

    private static long pushPopDary(int exponent, long[] values) {
        DaryHeap<Long> q = new DaryHeap<>(exponent, Comparator.naturalOrder());
        for (long v : values) {
            q.add(v);
        }
        long acc = 0;
        Long v;
        while ((v = q.poll()) != null) {
            acc += v;
        }
        return acc;
    }

    private static long cancelPriorityQueue(List<Long> ids, int[] victims) {
        PriorityQueue<Long> q = new PriorityQueue<>(ids);
        for (int i : victims) {
            q.remove(ids.get(i));
        }
        return q.size();
    }

    private static long cancelDary(List<Long> ids, int[] victims) {
        DaryHeap<Long> q = new DaryHeap<>(2, Comparator.naturalOrder());
        List<DaryHeap.Handle<Long>> handles = new ArrayList<>(ids.size());
        for (Long id : ids) {
            handles.add(q.add(id));
        }
        for (int i : victims) {
            q.remove(handles.get(i));
        }
        return q.size();
    }
}
