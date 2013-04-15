/*
 * Copyright (C) 2013 The Guava Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.common.util.concurrent;

import com.google.caliper.Benchmark;
import com.google.caliper.Param;
import com.google.caliper.api.Footprint;
import com.google.caliper.api.VmOptions;
import com.google.caliper.runner.CaliperMain;
import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;

import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;

/**
 * Benchmarks for {@link ExecutionList}.
 */
@VmOptions({"-Xms3g", "-Xmx3g"})
public class ExecutionListBenchmark extends Benchmark {
  private static final int NUM_THREADS = 10;  // make a param?

  // We execute the listeners on the sameThreadExecutor because we don't really care about what the
  // listeners are doing, and they aren't doing much.
  private static final Executor SAME_THREAD_EXECUTOR = MoreExecutors.sameThreadExecutor();

  // simple interface to wrap our two implementations.
  interface ExecutionListWrapper {
    void add(Runnable runnable, Executor executor);
    void execute();
    /** Returns the underlying implementation, useful for the Footprint benchmark. */
    Object getImpl();
  }

  enum Impl {
    NEW {
      @Override ExecutionListWrapper newExecutionList() {
        return new ExecutionListWrapper() {
          final ExecutionList list = new ExecutionList();
          @Override public void add(Runnable runnable, Executor executor) {
            list.add(runnable, executor);
          }

          @Override public void execute() {
            list.execute();
          }

          @Override public Object getImpl() {
            return list;
          }
        };
      }
    },
    NEW_WITH_CAS {
      @Override ExecutionListWrapper newExecutionList() {
        return new ExecutionListWrapper() {
          final ExecutionListCAS list = new ExecutionListCAS();
          @Override public void add(Runnable runnable, Executor executor) {
            list.add(runnable, executor);
          }

          @Override public void execute() {
            list.execute();
          }

          @Override public Object getImpl() {
            return list;
          }
        };
      }
    },
    NEW_WITH_QUEUE {
      @Override ExecutionListWrapper newExecutionList() {
        return new ExecutionListWrapper() {
          final NewExecutionListQueue list = new NewExecutionListQueue();
          @Override public void add(Runnable runnable, Executor executor) {
            list.add(runnable, executor);
          }

          @Override public void execute() {
            list.execute();
          }

          @Override public Object getImpl() {
            return list;
          }
        };
      }
    },
    NEW_WITHOUT_REVERSE {
      @Override ExecutionListWrapper newExecutionList() {
        return new ExecutionListWrapper() {
          final NewExecutionListWithoutReverse list = new NewExecutionListWithoutReverse();
          @Override public void add(Runnable runnable, Executor executor) {
            list.add(runnable, executor);
          }

          @Override public void execute() {
            list.execute();
          }

          @Override public Object getImpl() {
            return list;
          }
        };
      }
    },
    OLD {
      @Override ExecutionListWrapper newExecutionList() {
        return new ExecutionListWrapper() {
          final OldExecutionList list = new OldExecutionList();
          @Override public void add(Runnable runnable, Executor executor) {
            list.add(runnable, executor);
          }

          @Override public void execute() {
            list.execute();
          }

          @Override public Object getImpl() {
            return list;
          }
        };
      }
    };
    abstract ExecutionListWrapper newExecutionList();
  }

  private ExecutorService executorService;
  private CountDownLatch listenerLatch;
  private ExecutionListWrapper list;

  @Param Impl impl;
  @Param({"1", "5", "10"}) int numListeners;

  private final Runnable listener = new Runnable() {
    @Override public void run() {
      listenerLatch.countDown();
    }
  };

  @Override protected void setUp() throws Exception {
    executorService = new ThreadPoolExecutor(NUM_THREADS,
        NUM_THREADS,
        Long.MAX_VALUE,
        TimeUnit.SECONDS,
        new ArrayBlockingQueue<Runnable>(1000));
    final AtomicInteger integer = new AtomicInteger();
    // Execute a bunch of tasks to ensure that our threads are allocated and hot
    for (int i = 0; i < NUM_THREADS * 10; i++) {
      executorService.submit(new Runnable() {
        @Override public void run() {
          integer.getAndIncrement();
        }});
    }
  }

  @Override protected void tearDown() throws Exception {
    executorService.shutdown();
  }

  @Footprint(exclude = {Runnable.class, Executor.class})
  public Object measureSize() {
    list = impl.newExecutionList();
    for (int i = 0; i < numListeners; i++) {
      list.add(listener, SAME_THREAD_EXECUTOR);
    }
    return list.getImpl();
  }

  public int timeAddThenExecute_singleThreaded(int reps) {
    int returnValue = 0;
    for (int i = 0; i < reps; i++) {
      list = impl.newExecutionList();
      listenerLatch = new CountDownLatch(numListeners);
      for (int j = 0; j < numListeners; j++) {
        list.add(listener, SAME_THREAD_EXECUTOR);
        returnValue += listenerLatch.getCount();
      }
      list.execute();
      returnValue += listenerLatch.getCount();
    }
    return returnValue;
  }

  public int timeExecuteThenAdd_singleThreaded(int reps) {
    int returnValue = 0;
    for (int i = 0; i < reps; i++) {
      list = impl.newExecutionList();
      list.execute();
      listenerLatch = new CountDownLatch(numListeners);
      for (int j = 0; j < numListeners; j++) {
        list.add(listener, SAME_THREAD_EXECUTOR);
        returnValue += listenerLatch.getCount();
      }
      returnValue += listenerLatch.getCount();
    }
    return returnValue;
  }

  private final Runnable executeTask = new Runnable() {
    @Override public void run() {
      list.execute();
    }
  };

  public int timeAddThenExecute_multiThreaded(final int reps) throws InterruptedException {
    Runnable addTask = new Runnable() {
      @Override public void run() {
        for (int i = 0; i < numListeners; i++) {
          list.add(listener, SAME_THREAD_EXECUTOR);
        }
      }
    };
    int returnValue = 0;
    for (int i = 0; i < reps; i++) {
      list = impl.newExecutionList();
      listenerLatch = new CountDownLatch(numListeners * NUM_THREADS);
      for (int j = 0; j < NUM_THREADS; j++) {
        executorService.submit(addTask);
      }
      executorService.submit(executeTask);
      returnValue = (int) listenerLatch.getCount();
      listenerLatch.await();
    }
    return returnValue;
  }

  public int timeExecuteThenAdd_multiThreaded(final int reps) throws InterruptedException {
    Runnable addTask = new Runnable() {
      @Override public void run() {
        for (int i = 0; i < numListeners; i++) {
          list.add(listener, SAME_THREAD_EXECUTOR);
        }
      }
    };
    int returnValue = 0;
    for (int i = 0; i < reps; i++) {
      list = impl.newExecutionList();
      listenerLatch = new CountDownLatch(numListeners * NUM_THREADS);
      executorService.submit(executeTask);
      for (int j = 0; j < NUM_THREADS; j++) {
        executorService.submit(addTask);
      }
      returnValue = (int) listenerLatch.getCount();
      listenerLatch.await();
    }
    return returnValue;
  }

  public static void main(String[] args) {
    CaliperMain.main(ExecutionListBenchmark.class, args);
  }

  // This is the old implementation of ExecutionList using a LinkedList.
  private static final class OldExecutionList {
    static final Logger log = Logger.getLogger(OldExecutionList.class.getName());
    final Queue<OldExecutionList.RunnableExecutorPair> runnables = Lists.newLinkedList();
    boolean executed = false;

    public void add(Runnable runnable, Executor executor) {
      Preconditions.checkNotNull(runnable, "Runnable was null.");
      Preconditions.checkNotNull(executor, "Executor was null.");

      boolean executeImmediate = false;

      synchronized (runnables) {
        if (!executed) {
          runnables.add(new RunnableExecutorPair(runnable, executor));
        } else {
          executeImmediate = true;
        }
      }

      if (executeImmediate) {
        new RunnableExecutorPair(runnable, executor).execute();
      }
    }

    public void execute() {
      synchronized (runnables) {
        if (executed) {
          return;
        }
        executed = true;
      }

      while (!runnables.isEmpty()) {
        runnables.poll().execute();
      }
    }

    private static class RunnableExecutorPair {
      final Runnable runnable;
      final Executor executor;

      RunnableExecutorPair(Runnable runnable, Executor executor) {
        this.runnable = runnable;
        this.executor = executor;
      }

      void execute() {
        try {
          executor.execute(runnable);
        } catch (RuntimeException e) {
          log.log(Level.SEVERE, "RuntimeException while executing runnable "
              + runnable + " with executor " + executor, e);
        }
      }
    }
  }

  // A version of the execution list that doesn't reverse the stack in execute().
  private static final class NewExecutionListWithoutReverse {
    static final Logger log = Logger.getLogger(NewExecutionListWithoutReverse.class.getName());

    @GuardedBy("this")
    private RunnableExecutorPair runnables;
    @GuardedBy("this")
    private boolean executed;

    public void add(Runnable runnable, Executor executor) {
      Preconditions.checkNotNull(runnable, "Runnable was null.");
      Preconditions.checkNotNull(executor, "Executor was null.");

      synchronized (this) {
        if (!executed) {
          runnables = new RunnableExecutorPair(runnable, executor, runnables);
          return;
        }
      }
      executeListener(runnable, executor);
    }

    public void execute() {
      RunnableExecutorPair list;
      synchronized (this) {
        if (executed) {
          return;
        }
        executed = true;
        list = runnables;
        runnables = null;  // allow GC to free listeners even if this stays around for a while.
      }
      while (list != null) {
        executeListener(list.runnable, list.executor);
        list = list.next;
      }
    }

    private static void executeListener(Runnable runnable, Executor executor) {
      try {
        executor.execute(runnable);
      } catch (RuntimeException e) {
        log.log(Level.SEVERE, "RuntimeException while executing runnable "
            + runnable + " with executor " + executor, e);
      }
    }

    private static final class RunnableExecutorPair {
      final Runnable runnable;
      final Executor executor;
      @Nullable RunnableExecutorPair next;

      RunnableExecutorPair(Runnable runnable, Executor executor, RunnableExecutorPair next) {
        this.runnable = runnable;
        this.executor = executor;
        this.next = next;
      }
    }
  }

  // A version of the ExecutionList that uses an explicit tail pointer to keep the nodes in order
  // rather than flipping the stack in execute().
  private static final class NewExecutionListQueue {
    static final Logger log = Logger.getLogger(NewExecutionListQueue.class.getName());

    @GuardedBy("this")
    private RunnableExecutorPair head;
    @GuardedBy("this")
    private RunnableExecutorPair tail;
    @GuardedBy("this")
    private boolean executed;

    public void add(Runnable runnable, Executor executor) {
      Preconditions.checkNotNull(runnable, "Runnable was null.");
      Preconditions.checkNotNull(executor, "Executor was null.");

      synchronized (this) {
        if (!executed) {
          RunnableExecutorPair newTail = new RunnableExecutorPair(runnable, executor);
          if (head == null) {
            head = newTail;
            tail = newTail;
          } else {
            tail.next = newTail;
            tail = newTail;
          }
          return;
        }
      }
      executeListener(runnable, executor);
    }

    public void execute() {
      RunnableExecutorPair list;
      synchronized (this) {
        if (executed) {
          return;
        }
        executed = true;
        list = head;
        head = null;  // allow GC to free listeners even if this stays around for a while.
        tail = null;
      }
      while (list != null) {
        executeListener(list.runnable, list.executor);
        list = list.next;
      }
    }

    private static void executeListener(Runnable runnable, Executor executor) {
      try {
        executor.execute(runnable);
      } catch (RuntimeException e) {
        log.log(Level.SEVERE, "RuntimeException while executing runnable "
            + runnable + " with executor " + executor, e);
      }
    }

    private static final class RunnableExecutorPair {
      Runnable runnable;
      Executor executor;
      @Nullable RunnableExecutorPair next;

      RunnableExecutorPair(Runnable runnable, Executor executor) {
        this.runnable = runnable;
        this.executor = executor;
      }
    }
  }

  // A version of the list that uses compare and swap to manage the stack without locks.
  private static final class ExecutionListCAS {
    static final Logger log = Logger.getLogger(ExecutionListCAS.class.getName());

    private static final sun.misc.Unsafe UNSAFE;
    private static final long HEAD_OFFSET;

    /**
     * A special instance of {@link RunnableExecutorPair} that is used as a sentinel value for the
     * bottom of the stack.
     */
    private static final RunnableExecutorPair NULL_PAIR = new RunnableExecutorPair(null, null);

    static {
      try {
        UNSAFE = getUnsafe();
        HEAD_OFFSET = UNSAFE.objectFieldOffset(ExecutionListCAS.class.getDeclaredField("head"));
      } catch (Exception ex) {
        throw new Error(ex);
      }
    }

    /**
     * TODO(user):  This was copied verbatim from Striped64.java... standardize this?
     */
    private static sun.misc.Unsafe getUnsafe() {
        try {
            return sun.misc.Unsafe.getUnsafe();
        } catch (SecurityException tryReflectionInstead) {}
        try {
            return java.security.AccessController.doPrivileged
            (new java.security.PrivilegedExceptionAction<sun.misc.Unsafe>() {
                @Override public sun.misc.Unsafe run() throws Exception {
                    Class<sun.misc.Unsafe> k = sun.misc.Unsafe.class;
                    for (java.lang.reflect.Field f : k.getDeclaredFields()) {
                        f.setAccessible(true);
                        Object x = f.get(null);
                        if (k.isInstance(x))
                            return k.cast(x);
                    }
                    throw new NoSuchFieldError("the Unsafe");
                }});
        } catch (java.security.PrivilegedActionException e) {
            throw new RuntimeException("Could not initialize intrinsics",
                                       e.getCause());
        }
    }
    private volatile RunnableExecutorPair head = NULL_PAIR;

    public void add(Runnable runnable, Executor executor) {
      Preconditions.checkNotNull(runnable, "Runnable was null.");
      Preconditions.checkNotNull(executor, "Executor was null.");

      RunnableExecutorPair newHead = new RunnableExecutorPair(runnable, executor);
      RunnableExecutorPair oldHead;
      do {
        oldHead = head;
        if (oldHead == null) {
          // If runnables == null then execute() has been called so we should just execute our
          // listener immediately.
          newHead.execute();
          return;
        }
        // Try to make newHead the new head of the stack at runnables.
        newHead.next = oldHead;
      } while(!UNSAFE.compareAndSwapObject(this, HEAD_OFFSET, oldHead, newHead));
    }

    public void execute() {
      RunnableExecutorPair stack;
      do {
        stack = head;
        if (stack == null) {
          // If head == null then execute() has been called so we should just return
          return;
        }
        // try to swap null into head.
      } while(!UNSAFE.compareAndSwapObject(this, HEAD_OFFSET, stack, null));

      RunnableExecutorPair reversedStack = null;
      while (stack != NULL_PAIR) {
        RunnableExecutorPair head = stack;
        stack = stack.next;
        head.next = reversedStack;
        reversedStack = head;
      }
      stack = reversedStack;
      while (stack != null) {
        stack.execute();
        stack = stack.next;
      }
    }

    private static class RunnableExecutorPair {
      final Runnable runnable;
      final Executor executor;
      // Volatile because this is written on one thread and read on another with no synchronization.
      @Nullable volatile RunnableExecutorPair next;

      RunnableExecutorPair(Runnable runnable, Executor executor) {
        this.runnable = runnable;
        this.executor = executor;
      }

      void execute() {
        try {
          executor.execute(runnable);
        } catch (RuntimeException e) {
          log.log(Level.SEVERE, "RuntimeException while executing runnable "
              + runnable + " with executor " + executor, e);
        }
      }
    }
  }
}