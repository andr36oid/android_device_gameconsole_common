package org.andr36oid.usbmode;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class RequestTest {
    private static int checks;
    private static void check(boolean value) { checks++; if (!value) throw new AssertionError("USB request " + checks); }
    public static void main(String[] args) throws Exception {
        UsbRequest request = new UsbRequest();
        check(request.begin(true, true) == 0 && !request.pending());
        int first = request.begin(true, false);
        check(first != 0 && request.pending() && request.image());
        check(request.begin(false, false) == 0 && request.image() && request.current(first));
        request.cancel(); check(!request.current(first) && request.pending());
        check(request.begin(false, false) == 0); // cancellation must be acknowledged before another start
        check(request.finish(first) && !request.pending());
        int next = request.begin(false, false);
        check(next != 0 && !request.image() && request.current(next));
        check(!request.finish(first) && request.pending()); // late completion cannot clear a new request
        check(request.finish(next) && !request.pending());
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        StringBuilder order = new StringBuilder();
        try {
            UsbRequest.executor.submit(() -> {
                order.append("submit "); entered.countDown();
                try { if (!release.await(2, TimeUnit.SECONDS)) throw new AssertionError("release"); }
                catch (InterruptedException e) { throw new AssertionError(e); }
                order.append("cancel ");
            });
            check(entered.await(2, TimeUnit.SECONDS));
            java.util.concurrent.Future<?> newerActivity = UsbRequest.executor.submit(() -> order.append("new"));
            check(!newerActivity.isDone());
            release.countDown(); newerActivity.get(2, TimeUnit.SECONDS);
            check(order.toString().equals("submit cancel new"));
        } finally { release.countDown(); UsbRequest.executor.shutdownNow(); }
        System.out.println(checks + " submission, cancellation, retained request kind and activity ordering checks passed");
    }
}
