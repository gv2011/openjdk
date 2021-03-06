/*
 * Copyright (c) 2013, 2016, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package sun.hotspot.tools.ctw;

import java.lang.management.ManagementFactory;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;

import java.util.List;
import java.util.concurrent.*;

public class CompileTheWorld {
    // in case when a static constructor changes System::out and System::err
    // we hold these values of output streams
    static PrintStream OUT = System.out;
    static final PrintStream ERR = System.err;
    /**
     * Entry point. Compiles classes in {@code paths}
     *
     * @param paths paths to jar/zip, dir contains classes, or to .lst file
     *              contains list of classes to compile
     */
    public static void main(String[] paths) {
        if (paths.length == 0) {
            throw new IllegalArgumentException("Expect a path to a compile target.");
        }
        String logfile = Utils.LOG_FILE;
        PrintStream os = null;
        if (logfile != null) {
            try {
                os = new PrintStream(Files.newOutputStream(Paths.get(logfile)));
            } catch (IOException io) {
            }
        }
        if (os != null) {
            OUT = os;
        }

        boolean passed = false;

        try {
            try {
                if (ManagementFactory.getCompilationMXBean() == null) {
                    throw new RuntimeException(
                            "CTW can not work in interpreted mode");
                }
            } catch (java.lang.NoClassDefFoundError e) {
                // compact1, compact2 support
            }
            ExecutorService executor = createExecutor();
            long start = System.currentTimeMillis();
            try {
                String path;
                for (int i = 0, n = paths.length; i < n
                        && !PathHandler.isFinished(); ++i) {
                    path = paths[i];
                    PathHandler.create(path, executor).process();
                }
            } finally {
                await(executor);
            }
            CompileTheWorld.OUT.printf("Done (%d classes, %d methods, %d ms)%n",
                    PathHandler.getClassCount(),
                    Compiler.getMethodCount(),
                    System.currentTimeMillis() - start);
            passed = true;
        } finally {
            // <clinit> might have started new threads
            System.exit(passed ? 0 : 1);
        }
    }

    private static ExecutorService createExecutor() {
        final int threadsCount = Math.min(
                Runtime.getRuntime().availableProcessors(),
                Utils.CI_COMPILER_COUNT);
        ExecutorService result;
        if (threadsCount > 1) {
            result = new ThreadPoolExecutor(threadsCount, threadsCount,
                    /* keepAliveTime */ 0L, TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<>(threadsCount),
                    new ThreadPoolExecutor.CallerRunsPolicy());
        } else {
            result = new CurrentThreadExecutor();
        }
        return result;
    }

    private static void await(ExecutorService executor) {
        executor.shutdown();
        while (!executor.isTerminated()) {
            try {
                executor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private static class CurrentThreadExecutor extends AbstractExecutorService {
        private boolean isShutdown;

        @Override
        public void shutdown() {
            this.isShutdown = true;
        }

        @Override
        public List<Runnable> shutdownNow() {
            return null;
        }

        @Override
        public boolean isShutdown() {
            return isShutdown;
        }

        @Override
        public boolean isTerminated() {
            return isShutdown;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit)
                throws InterruptedException {
            return isShutdown;
        }

        @Override
        public void execute(Runnable command) {
            command.run();
        }
    }
}

