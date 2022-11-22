package io.seata.serializer.kryo;

import com.esotericsoftware.kryo.Kryo;

import java.lang.ref.SoftReference;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

public interface KryoPool {

    /** Takes a {@link Kryo} instance from the pool or creates a new one (using the factory) if the pool is empty. */
    Kryo borrow ();

    /** Returns the given {@link Kryo} instance to the pool. */
    void release (Kryo kryo);

    /** Runs the provided {@link KryoCallback} with a {@link Kryo} instance from the pool (borrow/release around
     * {@link KryoCallback#execute(Kryo)}). */
    <T> T run (KryoCallback<T> callback);

    /** Builder for a {@link com.esotericsoftware.kryo.pool.KryoPool} instance, constructs a {@link KryoPoolQueueImpl} instance. */
    public static class Builder {

        private final KryoFactory factory;
        private Queue<Kryo> queue = new ConcurrentLinkedQueue<Kryo>();
        private boolean softReferences;

        public Builder (KryoFactory factory) {
            if (factory == null) {
                throw new IllegalArgumentException("factory must not be null");
            }
            this.factory = factory;
        }

        /** Use the given queue for pooling kryo instances (by default a {@link ConcurrentLinkedQueue} is used). */
        public KryoPool.Builder queue (Queue<Kryo> queue) {
            if (queue == null) {
                throw new IllegalArgumentException("queue must not be null");
            }
            this.queue = queue;
            return this;
        }

        /** Use {@link SoftReference}s for pooled {@link Kryo} instances, so that instances may be garbage collected when there's
         * memory demand (by default disabled). */
        public KryoPool.Builder softReferences () {
            softReferences = true;
            return this;
        }

        /** Build the pool. */
        public KryoPool build () {
            Queue<Kryo> q = softReferences ? new SoftReferenceQueue(queue) : queue;
            return new KryoPoolQueueImpl(factory, q);
        }

        @Override
        public String toString () {
            return getClass().getName() + "[queue.class=" + queue.getClass() + ", softReferences=" + softReferences + "]";
        }
    }

}
