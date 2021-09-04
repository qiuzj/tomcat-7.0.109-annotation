package org.apache.tomcat.util.threads;

import org.junit.Test;

import java.util.Date;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;

public class LimitLatchTest {

    @Test
    public void testWithLimitLatch() throws InterruptedException {
        LimitLatch limitLatch = new LimitLatch(2);
        for (int i = 0; i < 10; i++) {
            new Thread(() -> {
                try {
                    limitLatch.countUpOrAwait();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }

                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }

                System.out.println(new Date() + " " + Thread.currentThread().getName() + " done");

                limitLatch.countDown();
            }, "thread" + i).start();
        }

        new CountDownLatch(1).await();
    }

    @Test
    public void testWithSemaphore() throws InterruptedException {
        Semaphore semaphore = new Semaphore(2);
        for (int i = 0; i < 10; i++) {
            new Thread(() -> {
                try {
                    semaphore.acquire(1);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }

                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }

                System.out.println(new Date() + " " + Thread.currentThread().getName() + " done");

                semaphore.release(1);
            }, "thread" + i).start();
        }

        new CountDownLatch(1).await();
    }

}
