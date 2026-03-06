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

/**
 * Distributed locking service for coordinating cache operations across multiple pods.
 *
 * <p>ADK provides a baseline {@link InMemoryDistributedLock} implementation suitable for
 * development and single-pod deployments. For production multi-pod deployments, users can:
 *
 * <ul>
 *   <li>Implement this interface using Redis (Jedis, Lettuce)
 *   <li>Implement using their cloud provider's distributed locking service
 *   <li>Use Google Cloud Memorystore (Redis API compatible)
 *   <li>Use Firestore transactions for locking
 * </ul>
 *
 * <p><b>Example: Redis Implementation</b>
 *
 * <pre>{@code
 * public class RedisDistributedLock implements DistributedLock {
 *   private final JedisPool jedisPool;
 *
 *   public Single<LockHandle> acquire(String lockKey, Duration timeout) {
 *     return Single.create(emitter -> {
 *       String lockValue = UUID.randomUUID().toString();
 *       long start = System.currentTimeMillis();
 *
 *       while (System.currentTimeMillis() - start < timeout.toMillis()) {
 *         // Try atomic SET NX EX
 *         try (Jedis jedis = jedisPool.getResource()) {
 *           String result = jedis.set(lockKey, lockValue,
 *               SetParams.setParams().nx().ex(30));
 *
 *           if ("OK".equals(result)) {
 *             emitter.onSuccess(new RedisLockHandle(lockKey, lockValue, jedisPool));
 *             return;
 *           }
 *         }
 *         Thread.sleep(50);
 *       }
 *       emitter.onError(new LockTimeoutException("Timeout acquiring lock"));
 *     });
 *   }
 *
 *   public Single<LockHandle> tryAcquire(String lockKey, Duration ttl) {
 *     // Similar but returns error immediately if lock held
 *   }
 * }
 * }</pre>
 *
 * <p><b>Usage in ADK:</b>
 *
 * <pre>{@code
 * // Create custom Redis lock
 * DistributedLock redisLock = new RedisDistributedLock(jedisPool);
 *
 * // Provide to cache manager
 * GeminiContextCacheManager manager =
 *     new GeminiContextCacheManager(client, projectId, redisLock);
 *
 * // Cache creation now uses distributed locking
 * }</pre>
 *
 * <p><b>Note:</b> ADK's default behavior (without distributed lock) uses duplicate detection and
 * cleanup, which is sufficient for most deployments. Distributed locking is recommended only for
 * high-volume production systems (1000+ requests/hour) where duplicate creation costs become
 * significant.
 *
 * @see InMemoryDistributedLock
 * @since 0.4.0
 */
public interface DistributedLock {

  /**
   * Acquires a lock, blocking until available or timeout.
   *
   * <p>If another pod holds the lock, this method will retry until:
   *
   * <ul>
   *   <li>Lock is released by the other pod
   *   <li>Timeout duration is reached
   *   <li>Lock expires automatically (backend-dependent)
   * </ul>
   *
   * <p><b>Typical Usage:</b>
   *
   * <pre>{@code
   * lock.acquire("cache-creation:abc123", Duration.ofSeconds(10))
   *   .flatMap(handle -> {
   *     // Perform cache creation
   *     return createCache().doFinally(handle::release);
   *   });
   * }</pre>
   *
   * @param lockKey Unique key identifying the resource (e.g., "cache-creation:{fingerprint}")
   * @param timeout Maximum time to wait for lock acquisition
   * @return Single emitting LockHandle when lock is acquired
   * @throws LockTimeoutException if timeout is reached before lock is acquired
   */
  Single<LockHandle> acquire(String lockKey, Duration timeout);

  /**
   * Tries to acquire lock without blocking.
   *
   * <p>Returns immediately with success or failure - does not retry.
   *
   * @param lockKey Unique key identifying the resource
   * @param ttl How long to hold the lock before automatic release (prevents deadlocks)
   * @return Single emitting LockHandle if lock was acquired immediately
   * @throws LockAlreadyHeldException if lock is currently held by another pod
   */
  Single<LockHandle> tryAcquire(String lockKey, Duration ttl);

  /**
   * Handle for managing an acquired lock.
   *
   * <p>Supports try-with-resources pattern for automatic release:
   *
   * <pre>{@code
   * try (LockHandle lock = distributedLock.acquire(...).blockingGet()) {
   *   // Critical section
   * } // Automatically released
   * }</pre>
   */
  interface LockHandle extends AutoCloseable {
    /**
     * Releases the lock.
     *
     * <p>Safe to call multiple times - subsequent calls are no-ops.
     *
     * @return Completable that completes when lock is released
     */
    Completable release();

    /**
     * Extends the lock TTL (if backend supports it).
     *
     * <p>Useful for long-running operations that need to hold the lock beyond initial TTL.
     *
     * @param additionalTime Additional time to add to lock expiration
     * @return Completable that completes when TTL is extended
     * @throws IllegalStateException if lock has already been released
     */
    Completable extend(Duration additionalTime);

    /**
     * Releases lock when used in try-with-resources.
     *
     * <p>Blocks until release completes. For async release, use {@link #release()} directly.
     */
    @Override
    default void close() {
      release().blockingAwait();
    }
  }

  /** Exception thrown when lock acquisition times out. */
  class LockTimeoutException extends RuntimeException {
    public LockTimeoutException(String message) {
      super(message);
    }

    public LockTimeoutException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  /** Exception thrown when trying to acquire an already-held lock in non-blocking mode. */
  class LockAlreadyHeldException extends RuntimeException {
    public LockAlreadyHeldException(String message) {
      super(message);
    }

    public LockAlreadyHeldException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
