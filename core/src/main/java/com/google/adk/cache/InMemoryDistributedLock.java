/*
 * Copyright 2025 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.adk.cache;

import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Single;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * In-memory distributed lock for development and single-pod deployments.
 *
 * <p><b>⚠️ WARNING: Single-JVM Only</b>
 *
 * <p>This implementation only works within a single JVM process. It does <b>NOT</b> provide
 * distributed locking across multiple pods or processes. Concurrent cache creation from multiple
 * pods will still create duplicates.
 *
 * <p><b>Suitable for:</b>
 *
 * <ul>
 *   <li>Local development
 *   <li>Unit and integration testing
 *   <li>Single-pod deployments (Cloud Run with maxScale=1)
 * </ul>
 *
 * <p><b>NOT suitable for:</b>
 *
 * <ul>
 *   <li>Production multi-pod deployments
 *   <li>Kubernetes clusters with multiple replicas
 *   <li>Auto-scaling environments
 * </ul>
 *
 * <p><b>For production multi-pod deployments,</b> implement {@link DistributedLock} using:
 *
 * <ul>
 *   <li><b>Redis:</b> Jedis or Lettuce with SET NX pattern
 *   <li><b>Google Cloud Memorystore:</b> Redis API compatible
 *   <li><b>Firestore:</b> Transactions with document locking
 *   <li><b>Other:</b> Hazelcast, Apache Ignite, Etcd, etc.
 * </ul>
 *
 * <p><b>Example Redis Implementation:</b>
 *
 * <pre>{@code
 * public class RedisDistributedLock implements DistributedLock {
 *   private final JedisPool jedisPool;
 *
 *   public Single<LockHandle> acquire(String lockKey, Duration timeout) {
 *     // Use Redis SET NX EX pattern
 *     // See: https://redis.io/docs/manual/patterns/distributed-locks/
 *   }
 * }
 * }</pre>
 *
 * @see DistributedLock
 * @since 0.4.0
 */
public class InMemoryDistributedLock implements DistributedLock {

  private static final Logger logger = LoggerFactory.getLogger(InMemoryDistributedLock.class);
  private static final int POLL_INTERVAL_MS = 50;

  private final ConcurrentHashMap<String, LockEntry> locks = new ConcurrentHashMap<>();

  @Override
  public Single<LockHandle> acquire(String lockKey, Duration timeout) {
    return Single.create(
        emitter -> {
          String lockValue = UUID.randomUUID().toString();
          long startTime = System.currentTimeMillis();
          long timeoutMs = timeout.toMillis();

          while (System.currentTimeMillis() - startTime < timeoutMs) {
            if (tryAcquireInternal(lockKey, lockValue, timeout)) {
              logger.debug("Lock acquired: {}", lockKey);
              emitter.onSuccess(new InMemoryLockHandle(lockKey, lockValue, locks));
              return;
            }

            // Lock held by another thread - wait and retry
            try {
              Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
              emitter.onError(new RuntimeException("Lock acquisition interrupted", e));
              return;
            }
          }

          // Timeout reached
          emitter.onError(
              new LockTimeoutException(
                  String.format("Failed to acquire lock '%s' after %dms", lockKey, timeoutMs)));
        });
  }

  @Override
  public Single<LockHandle> tryAcquire(String lockKey, Duration ttl) {
    return Single.create(
        emitter -> {
          String lockValue = UUID.randomUUID().toString();

          if (tryAcquireInternal(lockKey, lockValue, ttl)) {
            logger.debug("Lock acquired (non-blocking): {}", lockKey);
            emitter.onSuccess(new InMemoryLockHandle(lockKey, lockValue, locks));
          } else {
            logger.debug("Lock already held: {}", lockKey);
            emitter.onError(new LockAlreadyHeldException("Lock already held: " + lockKey));
          }
        });
  }

  /**
   * Internal method to attempt lock acquisition.
   *
   * @param lockKey Key to lock
   * @param lockValue Unique value for this lock acquisition
   * @param ttl Time-to-live for the lock
   * @return True if lock was acquired, false if already held
   */
  private boolean tryAcquireInternal(String lockKey, String lockValue, Duration ttl) {
    long expiryTime = System.currentTimeMillis() + ttl.toMillis();
    LockEntry newEntry = new LockEntry(lockValue, expiryTime);

    // Try to put if absent
    LockEntry existing = locks.putIfAbsent(lockKey, newEntry);

    if (existing == null) {
      // Lock acquired
      return true;
    }

    // Check if existing lock has expired
    if (existing.isExpired()) {
      // Try to replace expired lock atomically
      if (locks.replace(lockKey, existing, newEntry)) {
        logger.debug("Lock acquired (replaced expired): {}", lockKey);
        return true;
      }
    }

    return false;
  }

  /** Lock entry with value and expiration time. */
  private static class LockEntry {
    final String value;
    final long expiryTime;

    LockEntry(String value, long expiryTime) {
      this.value = value;
      this.expiryTime = expiryTime;
    }

    boolean isExpired() {
      return System.currentTimeMillis() > expiryTime;
    }
  }

  /**
   * In-memory lock handle for releasing locks.
   *
   * <p>Uses compare-and-swap semantics to ensure only the lock owner can release the lock.
   */
  private static class InMemoryLockHandle implements LockHandle {
    private final String key;
    private final String value;
    private final ConcurrentHashMap<String, LockEntry> locks;

    InMemoryLockHandle(String key, String value, ConcurrentHashMap<String, LockEntry> locks) {
      this.key = key;
      this.value = value;
      this.locks = locks;
    }

    @Override
    public Completable release() {
      return Completable.fromAction(
          () -> {
            LockEntry entry = locks.get(key);

            // Only release if value matches (prevents releasing someone else's lock)
            if (entry != null && entry.value.equals(value)) {
              if (locks.remove(key, entry)) {
                logger.debug("Lock released: {}", key);
              } else {
                logger.warn("Lock was concurrently modified: {}", key);
              }
            } else {
              logger.debug("Lock was already released or expired: {}", key);
            }
          });
    }

    @Override
    public Completable extend(Duration additionalTime) {
      return Completable.fromAction(
          () -> {
            LockEntry entry = locks.get(key);

            if (entry == null || !entry.value.equals(value)) {
              throw new IllegalStateException("Cannot extend lock - already released or not owned");
            }

            // Create new entry with extended expiry
            long newExpiryTime = System.currentTimeMillis() + additionalTime.toMillis();
            LockEntry extended = new LockEntry(value, newExpiryTime);

            // Atomically replace if still the same entry
            if (locks.replace(key, entry, extended)) {
              logger.debug("Lock extended: {} by {}s", key, additionalTime.getSeconds());
            } else {
              throw new IllegalStateException("Cannot extend lock - concurrently modified");
            }
          });
    }
  }
}
