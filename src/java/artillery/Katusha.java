package java.artillery;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class Katusha {

    public static void main(String[] args) throws Exception {
        String url = args.length > 0 ? args[0] : "http://localhost:8080/";
        int totalRequests = args.length > 1 ? Integer.parseInt(args[1]) : 1000;
        int concurrency   = args.length > 2 ? Integer.parseInt(args[2]) : 200;
        String userId = "user-" + (10 % 100000);
        HttpClient client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .build();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("X-User-Id", userId)
                .GET()
                .build();

        Semaphore semaphore = new Semaphore(concurrency);
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        AtomicInteger success = new AtomicInteger();
        AtomicInteger failure = new AtomicInteger();
        AtomicInteger rateLimited = new AtomicInteger(); // e.g. 429s
        // status code -> count
        Map<Integer, AtomicInteger> statusCounts = new ConcurrentHashMap<>();
        AtomicInteger exceptions = new AtomicInteger();

        Instant start = Instant.now();

        for (int i = 0; i < totalRequests; i++) {
            semaphore.acquire();

            CompletableFuture<Void> f = client.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                    .thenAccept(resp -> {
                        int code = resp.statusCode();
                        statusCounts
                                .computeIfAbsent(code, k -> new AtomicInteger())
                                .incrementAndGet();
                        if (code / 100 == 2) {
                            success.incrementAndGet();
                        } else {
                            if (code == 429) {
                                final int reqNumber = rateLimited.incrementAndGet();
                                System.out.println("Rate limited at request: " + reqNumber);
                            }
                            failure.incrementAndGet();
                        }
                    })
                    .exceptionally(e -> {
                        failure.incrementAndGet();
                        exceptions.incrementAndGet();
                        e.printStackTrace(); // uncomment if you want details
                        return null;
                    })
                    .whenComplete((r, t) -> semaphore.release());

            futures.add(f);
        }

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        Instant end = Instant.now();
        long millis = Duration.between(start, end).toMillis();

        int ok = success.get();
        int ko = failure.get();
        int limited = rateLimited.get();

        System.out.println("Total: " + totalRequests);
        System.out.println("OK:    " + ok);
        System.out.println("Fail:  " + ko);
        System.out.println("429s:  " + limited);
        System.out.println("Exceptions: " + exceptions.get());
        System.out.println("Time:  " + millis + " ms");
        if (millis > 0) {
            double rps = (totalRequests * 1000.0) / millis;
            System.out.printf("RPS:   %.2f%n", rps);
        }

        System.out.println("\nStatus code breakdown:");
        statusCounts.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e ->
                        System.out.println(e.getKey() + " -> " + e.getValue().get())
                );
    }
}

