package dev.astock.service.trading;

import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** The only logical writer for orders, fills, positions and their projections. */
@Component
public class OrderCommandBus {
    private final ExecutorService writer;
    private volatile Thread writerThread;

    public OrderCommandBus() {
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "astock-order-writer");
            thread.setDaemon(false);
            writerThread = thread;
            return thread;
        };
        this.writer = Executors.newSingleThreadExecutor(factory);
    }

    public <T> T call(Callable<T> command) {
        if (Thread.currentThread() == writerThread) {
            return invoke(command);
        }
        try {
            return writer.submit(command).get(Duration.ofSeconds(15).toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("order command interrupted", exception);
        } catch (TimeoutException exception) {
            throw new IllegalStateException("order writer timed out", exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("order command failed", cause);
        }
    }

    public void submit(Runnable command) {
        writer.submit(command);
    }

    private static <T> T invoke(Callable<T> command) {
        try {
            return command.call();
        } catch (RuntimeException runtime) {
            throw runtime;
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    @PreDestroy
    void close() {
        writer.close();
    }
}
