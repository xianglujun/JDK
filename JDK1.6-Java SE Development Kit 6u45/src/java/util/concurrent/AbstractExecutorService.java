/*
 * %W% %E%
 *
 * Copyright (c) 2006, Oracle and/or its affiliates. All rights reserved.
 * ORACLE PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 */

package java.util.concurrent;

import java.util.*;

/**
 * Provides default implementations of {@link ExecutorService} execution methods. This class
 * implements the <tt>submit</tt>,
 * <tt>invokeAny</tt> and <tt>invokeAll</tt> methods using a
 * {@link RunnableFuture} returned by <tt>newTaskFor</tt>, which defaults to the {@link FutureTask}
 * class provided in this package.  For example, the implementation of <tt>submit(Runnable)</tt>
 * creates an associated <tt>RunnableFuture</tt> that is executed and returned. Subclasses may
 * override the <tt>newTaskFor</tt> methods to return <tt>RunnableFuture</tt> implementations other
 * than
 * <tt>FutureTask</tt>.
 *
 * <p> <b>Extension example</b>. Here is a sketch of a class
 * that customizes {@link ThreadPoolExecutor} to use a <tt>CustomTask</tt> class instead of the
 * default <tt>FutureTask</tt>:
 * <pre>
 * public class CustomThreadPoolExecutor extends ThreadPoolExecutor {
 *
 *   static class CustomTask&lt;V&gt; implements RunnableFuture&lt;V&gt; {...}
 *
 *   protected &lt;V&gt; RunnableFuture&lt;V&gt; newTaskFor(Callable&lt;V&gt; c) {
 *       return new CustomTask&lt;V&gt;(c);
 *   }
 *   protected &lt;V&gt; RunnableFuture&lt;V&gt; newTaskFor(Runnable r, V v) {
 *       return new CustomTask&lt;V&gt;(r, v);
 *   }
 *   // ... add constructors, etc.
 * }
 * </pre>
 *
 * @author Doug Lea
 * @since 1.5
 */
public abstract class AbstractExecutorService implements ExecutorService {

  /**
   * Returns a <tt>RunnableFuture</tt> for the given runnable and default value.
   *
   * @param runnable the runnable task being wrapped
   * @param value the default value for the returned future
   * @return a <tt>RunnableFuture</tt> which when run will run the underlying runnable and which, as
   * a <tt>Future</tt>, will yield the given value as its result and provide for cancellation of the
   * underlying task.
   * @since 1.6
   */
  protected <T> RunnableFuture<T> newTaskFor(Runnable runnable, T value) {
    return new FutureTask<T>(runnable, value);
  }

  /**
   * Returns a <tt>RunnableFuture</tt> for the given callable task.
   *
   * @param callable the callable task being wrapped
   * @return a <tt>RunnableFuture</tt> which when run will call the underlying callable and which,
   * as a <tt>Future</tt>, will yield the callable's result as its result and provide for
   * cancellation of the underlying task.
   * @since 1.6
   */
  protected <T> RunnableFuture<T> newTaskFor(Callable<T> callable) {
    return new FutureTask<T>(callable);
  }

  public Future<?> submit(Runnable task) {
    if (task == null) {
      throw new NullPointerException();
    }
    RunnableFuture<Object> ftask = newTaskFor(task, null);
    execute(ftask);
    return ftask;
  }

  public <T> Future<T> submit(Runnable task, T result) {
    if (task == null) {
      throw new NullPointerException();
    }
    RunnableFuture<T> ftask = newTaskFor(task, result);
    execute(ftask);
    return ftask;
  }

  public <T> Future<T> submit(Callable<T> task) {
    if (task == null) {
      throw new NullPointerException();
    }
    RunnableFuture<T> ftask = newTaskFor(task);
    execute(ftask);
    return ftask;
  }

  /**
   * the main mechanics of invokeAny.
   */
  private <T> T doInvokeAny(Collection<? extends Callable<T>> tasks,
      boolean timed, long nanos)
      throws InterruptedException, ExecutionException, TimeoutException {
    if (tasks == null) {
      throw new NullPointerException();
    }
    int ntasks = tasks.size();
    if (ntasks == 0) {
      throw new IllegalArgumentException();
    }

    // 创建一个Future列表, 不来储存任务的执行结果
    List<Future<T>> futures = new ArrayList<Future<T>>(ntasks);

    // 这里主要是通过ExecutorCompletionService来实现
    ExecutorCompletionService<T> ecs =
        new ExecutorCompletionService<T>(this);

    // For efficiency, especially in executors with limited
    // parallelism, check to see if previously submitted tasks are
    // done before submitting more of them. This interleaving
    // plus the exception mechanics account for messiness of main
    // loop.

    try {
      // Record exceptions so that if we fail to obtain any
      // result, we can throw the last exception we got.
      ExecutionException ee = null;
      long lastTime = (timed) ? System.nanoTime() : 0;
      Iterator<? extends Callable<T>> it = tasks.iterator();

      // Start one task for sure; the rest incrementally
      futures.add(ecs.submit(it.next()));
      --ntasks;
      int active = 1;

      // 自旋获取任务完成结果
      for (; ; ) {
        // 获取已经完成的结果
        Future<T> f = ecs.poll();
        // 没有完成的任务
        if (f == null) {
          // 表示还有任务未执行, 这就意味着, 如果第一个任务没有返回结果的时候
          // 则会玄幻的执行所有的任务
          if (ntasks > 0) {
            --ntasks;
            // 新增任务
            futures.add(ecs.submit(it.next()));
            ++active;
          } else if (active == 0) {
            // 如果active == 0, 表明没有活跃的任务, 这时就停止循环
            break;
          } else if (timed) {
            // 该处表明有超时的验证, 该方法会等待nanos的时间, 并返回结果
            f = ecs.poll(nanos, TimeUnit.NANOSECONDS);
            if (f == null) {
              throw new TimeoutException();
            }

            long now = System.nanoTime();
            nanos -= now - lastTime;
            lastTime = now;
          } else {
            // 如果所有的任务执行完成,
            // 并且没有超时时间的限制, 则阻塞等待结果
            f = ecs.take();
          }
        }

        // 如果返回了结果, 则从返回的结果中返回数据并返回
        if (f != null) {
          --active;
          try {
            return f.get();
          } catch (InterruptedException ie) {
            throw ie;
          } catch (ExecutionException eex) {
            ee = eex;
          } catch (RuntimeException rex) {
            ee = new ExecutionException(rex);
          }
        }
      }

      // 如果最终没有返回结果, 抛出异常
      if (ee == null) {
        ee = new ExecutionException();
      }
      throw ee;

    } finally {
      // 最终停止所有的任务
      for (Future<T> f : futures) {
        f.cancel(true);
      }
    }
  }

  public <T> T invokeAny(Collection<? extends Callable<T>> tasks)
      throws InterruptedException, ExecutionException {
    try {
      return doInvokeAny(tasks, false, 0);
    } catch (TimeoutException cannotHappen) {
      assert false;
      return null;
    }
  }

  public <T> T invokeAny(Collection<? extends Callable<T>> tasks,
      long timeout, TimeUnit unit)
      throws InterruptedException, ExecutionException, TimeoutException {
    return doInvokeAny(tasks, true, unit.toNanos(timeout));
  }

  public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks)
      throws InterruptedException {
    if (tasks == null) {
      throw new NullPointerException();
    }
    List<Future<T>> futures = new ArrayList<Future<T>>(tasks.size());
    boolean done = false;
    try {
      // 这里就是循环执行所有的任务
      for (Callable<T> t : tasks) {
        RunnableFuture<T> f = newTaskFor(t);
        futures.add(f);
        execute(f);
      }

      // 等待所有的任务执行完成
      for (Future<T> f : futures) {
        if (!f.isDone()) {
          try {
            f.get();
          } catch (CancellationException ignore) {
          } catch (ExecutionException ignore) {
          }
        }
      }
      done = true;
      return futures;
    } finally {
      // 如果有所的任务还没有完成, 则取消所有执行的任务
      if (!done) {
        for (Future<T> f : futures) {
          f.cancel(true);
        }
      }
    }
  }

  public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks,
      long timeout, TimeUnit unit)
      throws InterruptedException {
    if (tasks == null || unit == null) {
      throw new NullPointerException();
    }
    long nanos = unit.toNanos(timeout);
    List<Future<T>> futures = new ArrayList<Future<T>>(tasks.size());
    boolean done = false;
    try {
      for (Callable<T> t : tasks) {
        futures.add(newTaskFor(t));
      }

      // 记录执行的时间
      long lastTime = System.nanoTime();

      // Interleave time checks and calls to execute in case
      // executor doesn't have any/much parallelism.
      Iterator<Future<T>> it = futures.iterator();
      while (it.hasNext()) {
        execute((Runnable) (it.next()));
        long now = System.nanoTime();
        nanos -= now - lastTime;
        lastTime = now;

        // 如果已经超时, 则返回已经提交给线程池执行的任务
        // 已经提交给任务池的任务, 并不能保证所有的任务都执行完成
        // 因此会在finally中取消那些没有执行完成的任务
        if (nanos <= 0) {
          return futures;
        }
      }

      // 执行到这里, 就表明了任务提交完成
      // 并且等待任务执行完成, 但是在指定时间任务内
      // 任务没有执行完, 则会取消执行没有执行完成的任务
      for (Future<T> f : futures) {
        if (!f.isDone()) {
          if (nanos <= 0) {
            return futures;
          }
          try {
            f.get(nanos, TimeUnit.NANOSECONDS);
          } catch (CancellationException ignore) {
          } catch (ExecutionException ignore) {
          } catch (TimeoutException toe) {
            return futures;
          }
          long now = System.nanoTime();
          nanos -= now - lastTime;
          lastTime = now;
        }
      }
      done = true;
      return futures;
    } finally {
      if (!done) {
        for (Future<T> f : futures) {
          f.cancel(true);
        }
      }
    }
  }

}
