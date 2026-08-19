package org.synanton.synquest.acl;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Compact cuckoo filter used as the HIGH_SECURITY ACL pre-filter.
 */
public class CuckooAclFilter {

    private static final int BUCKET_SIZE = 4;
    private final long[] buckets;
    private final int bucketCount;

    public CuckooAclFilter(int capacity) {
        this.bucketCount = Math.max(16, Integer.highestOneBit(capacity - 1) << 1);
        this.buckets = new long[bucketCount * BUCKET_SIZE];
        Arrays.fill(buckets, 0L);
    }

    public void insert(String subjectId, String resourceId) {
        long fingerprint = fingerprint(subjectId, resourceId);
        int i1 = index1(fingerprint);
        int i2 = index2(i1, fingerprint);
        if (place(i1, fingerprint) || place(i2, fingerprint)) {
            return;
        }
        int index = i1;
        for (int kick = 0; kick < 32; kick++) {
            int slot = (index * BUCKET_SIZE) + (kick % BUCKET_SIZE);
            long displaced = buckets[slot];
            buckets[slot] = fingerprint;
            fingerprint = displaced;
            index = index == i1 ? i2 : i1;
            if (place(index, fingerprint)) {
                return;
            }
        }
    }

    public boolean contains(String subjectId, String resourceId) {
        long fingerprint = fingerprint(subjectId, resourceId);
        int i1 = index1(fingerprint);
        int i2 = index2(i1, fingerprint);
        return scan(i1, fingerprint) || scan(i2, fingerprint);
    }

    public void delete(String subjectId, String resourceId) {
        long fingerprint = fingerprint(subjectId, resourceId);
        int i1 = index1(fingerprint);
        int i2 = index2(i1, fingerprint);
        clear(i1, fingerprint);
        clear(i2, fingerprint);
    }

    private boolean place(int bucket, long fingerprint) {
        int base = bucket * BUCKET_SIZE;
        for (int offset = 0; offset < BUCKET_SIZE; offset++) {
            if (buckets[base + offset] == 0L) {
                buckets[base + offset] = fingerprint;
                return true;
            }
        }
        return false;
    }

    private boolean scan(int bucket, long fingerprint) {
        int base = bucket * BUCKET_SIZE;
        for (int offset = 0; offset < BUCKET_SIZE; offset++) {
            if (buckets[base + offset] == fingerprint) {
                return true;
            }
        }
        return false;
    }

    private void clear(int bucket, long fingerprint) {
        int base = bucket * BUCKET_SIZE;
        for (int offset = 0; offset < BUCKET_SIZE; offset++) {
            if (buckets[base + offset] == fingerprint) {
                buckets[base + offset] = 0L;
            }
        }
    }

    private int index1(long fingerprint) {
        return Math.floorMod(fingerprint, bucketCount);
    }

    private int index2(int index1, long fingerprint) {
        return Math.floorMod(index1 ^ Long.hashCode(fingerprint), bucketCount);
    }

    private static long fingerprint(String subjectId, String resourceId) {
        byte[] bytes = (subjectId + "|" + resourceId).getBytes(StandardCharsets.UTF_8);
        long hash = 1125899906842597L;
        for (byte value : bytes) {
            hash = 31 * hash + value;
        }
        return hash == 0 ? 1 : hash;
    }
}
