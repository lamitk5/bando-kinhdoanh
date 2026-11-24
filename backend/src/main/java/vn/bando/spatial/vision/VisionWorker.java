package vn.bando.spatial.vision;

import java.time.Duration;

import jakarta.annotation.PostConstruct;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "vision.worker.enabled", havingValue = "true", matchIfMissing = true)
public class VisionWorker implements org.springframework.beans.factory.DisposableBean {

    private final StringRedisTemplate redis;
    private final VisionJobRepository jobs;
    private final VisionService service;
    private volatile boolean running = true;
    private Thread thread;

    public VisionWorker(StringRedisTemplate redis, VisionJobRepository jobs, VisionService service) {
        this.redis = redis;
        this.jobs = jobs;
        this.service = service;
    }

    @PostConstruct
    void start() {
        redis.delete(VisionService.QUEUE);
        for (String id : jobs.requeueLeftovers()) {
            redis.opsForList().leftPush(VisionService.QUEUE, id);
        }
        thread = new Thread(this::loop, "vision-worker");
        thread.setDaemon(true);
        thread.start();
    }

    private void loop() {
        while (running) {
            try {
                String id = redis.opsForList().rightPop(VisionService.QUEUE, Duration.ofSeconds(1));
                if (id != null) {
                    service.process(id);
                }
            } catch (RuntimeException exception) {
                if (!running) {
                    return;
                }
                try {
                    Thread.sleep(200);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    @Override
    public void destroy() {
        running = false;
        if (thread != null) {
            thread.interrupt();
        }
    }
}
