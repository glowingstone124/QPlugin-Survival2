package vip.qoriginal.quantumplugin.lightscan;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Owns every expensive scan/export operation; scheduling a callback never changes this boundary. */
final class LightScanWorker implements AutoCloseable {
    private volatile Thread workerThread;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "QO-light-scan");
        thread.setDaemon(true);
        workerThread = thread;
        return thread;
    });

    void execute(Runnable task) {
        executor.execute(task);
    }

    void requireWorkerThread() {
        if (Thread.currentThread() != workerThread) {
            throw new IllegalStateException("光源扫描计算和导出只能在 QO-light-scan 后台线程运行。");
        }
    }

    @Override
    public void close() {
        executor.shutdown();
    }
}
