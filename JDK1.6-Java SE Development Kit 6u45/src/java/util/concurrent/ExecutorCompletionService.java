/*
 * %W% %E%
 *
 * Copyright (c) 2006, Oracle and/or its affiliates. All rights reserved.
 * ORACLE PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 */

package java.util.concurrent;

/**
 * A {@link CompletionService} that uses a supplied {@link Executor}
 * to execute tasks.  This class arranges that submitted tasks are,
 * upon completion, placed on a queue accessible using <tt>take</tt>.
 * The class is lightweight enough to be suitable for transient(短暂的) use
 * when processing groups of tasks.
 *
 * <p>
 *
 * <b>Usage Examples.</b>
 *
 * Suppose(假设) you have a set of solvers(解决者) for a certain problem, each
 * returning a value of some type <tt>Result</tt>, and would like to
 * run them concurrently, processing the results of each of them that
 * return a non-null value, in some method <tt>use(Result r)</tt>. You
 * could write this as:
 *
 * <pre>
 *   void solve(Executor e,
 *              Collection&lt;Callable&lt;Result&gt;&gt; solvers)
 *     throws InterruptedException, ExecutionException {
 *       CompletionService&lt;Result&gt; ecs
 *           = new ExecutorCompletionService&lt;Result&gt;(e);
 *       for (Callable&lt;Result&gt; s : solvers)
 *           ecs.submit(s);
 *       int n = solvers.size();
 *       for (int i = 0; i &lt; n; ++i) {
 *           Result r = ecs.take().get();
 *           if (r != null)
 *               use(r);
 *       }
 *   }
 * </pre>
 *
 * Suppose instead that you would like to use the first non-null result
 * of the set of tasks, ignoring any that encounter exceptions,
 * and cancelling all other tasks when the first one is ready:
 *
 * <pre>
 *   void solve(Executor e,
 *              Collection&lt;Callable&lt;Result&gt;&gt; solvers)
 *     throws InterruptedException {
 *       CompletionService&lt;Result&gt; ecs
 *           = new ExecutorCompletionService&lt;Result&gt;(e);
 *       int n = solvers.size();
 *       List&lt;Future&lt;Result&gt;&gt; futures
 *           = new ArrayList&lt;Future&lt;Result&gt;&gt;(n);
 *       Result result = null;
 *       try {
 *           for (Callable&lt;Result&gt; s : solvers)
 *               futures.add(ecs.submit(s));
 *           for (int i = 0; i &lt; n; ++i) {
 *               try {
 *                   Result r = ecs.take().get();
 *                   if (r != null) {
 *                       result = r;
 *                       break;
 *                   }
 *               } catch (ExecutionException ignore) {}
 *           }
 *       }
 *       finally {
 *           for (Future&lt;Result&gt; f : futures)
 *               f.cancel(true);
 *       }
 *
 *       if (result != null)
 *           use(result);
 *   }
 * </pre>
 */
public class ExecutorCompletionService<V> implements CompletionService<V> {
    private final Executor executor;
    private final AbstractExecutorService aes;
    private final BlockingQueue<Future<V>> completionQueue;

    /**
     * FutureTask extension to enqueue upon completion
     */
    private class QueueingFuture extends FutureTask<Void> {
        QueueingFuture(RunnableFuture<V> task) {
            super(task, null);
            this.task = task;
        }

        /**
         * 当任务执行完成之后, 则将完成的任务加入到已完成队列
         */
        protected void done() { completionQueue.add(task); }
        private final Future<V> task;
    }

    private RunnableFuture<V> newTaskFor(Callable<V> task) {
        if (aes == null)
            return new FutureTask<V>(task);
        else
            return aes.newTaskFor(task);
    }

    private RunnableFuture<V> newTaskFor(Runnable task, V result) {
        if (aes == null)
            return new FutureTask<V>(task, result);
        else
            return aes.newTaskFor(task, result);
    }

    /**
     * Creates an ExecutorCompletionService using the supplied
     * executor for base task execution and a
     * {@link LinkedBlockingQueue} as a completion queue.
     *
     * @param executor the executor to use
     * @throws NullPointerException if executor is <tt>null</tt>
     */
    public ExecutorCompletionService(Executor executor) {
        if (executor == null)
            throw new NullPointerException();
        this.executor = executor;
        this.aes = (executor instanceof AbstractExecutorService) ?
            (AbstractExecutorService) executor : null;
        this.completionQueue = new LinkedBlockingQueue<Future<V>>();
    }

    /**
     * Creates an ExecutorCompletionService using the supplied
     * executor for base task execution and the supplied queue as its
     * completion queue.
     *
     * @param executor the executor to use
     * @param completionQueue the queue to use as the completion queue
     * normally one dedicated for use by this service
     * @throws NullPointerException if executor or completionQueue are <tt>null</tt>
     */
    public ExecutorCompletionService(Executor executor,
                                     BlockingQueue<Future<V>> completionQueue) {
        if (executor == null || completionQueue == null)
            throw new NullPointerException();
        this.executor = executor;
        this.aes = (executor instanceof AbstractExecutorService) ?
            (AbstractExecutorService) executor : null;
        this.completionQueue = completionQueue;
    }

    public Future<V> submit(Callable<V> task) {
        if (task == null) throw new NullPointerException();
        RunnableFuture<V> f = newTaskFor(task);
        executor.execute(new QueueingFuture(f));
        return f;
    }

    public Future<V> submit(Runnable task, V result) {
        if (task == null) throw new NullPointerException();
        RunnableFuture<V> f = newTaskFor(task, result);
        executor.execute(new QueueingFuture(f));
        return f;
    }

    /**
     * 该方法会产生一个阻塞, 直到有数据返回
     * @return
     * @throws InterruptedException
     */
    public Future<V> take() throws InterruptedException {
        return completionQueue.take();
    }

    /**
     * 该方法也是返回队列的第一个元素, 如果队列为空, 则返回null, 不会阻塞
     * @return
     */
    public Future<V> poll() {
        return completionQueue.poll();
    }

    /**
     * 如果队列的第一个元素不存在, 则等待{@code timeout}的时间之后, 仍然没有数据返回, 则返回true
     *
     * @param timeout how long to wait before giving up, in units of
     *        <tt>unit</tt>
     * @param unit a <tt>TimeUnit</tt> determining how to interpret the
     *        <tt>timeout</tt> parameter
     * @return
     * @throws InterruptedException
     */
    public Future<V> poll(long timeout, TimeUnit unit) throws InterruptedException {
        return completionQueue.poll(timeout, unit);
    }

}
