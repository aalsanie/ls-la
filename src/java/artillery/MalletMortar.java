package java.artillery;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.*;
import java.net.*;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

public class MalletMortar {

    // ---- Virtual user profile ----
    static class VirtualUser {
        final String userAgent;
        final String acceptLanguage;
        final String xForwardedFor;

        VirtualUser(String ua, String lang, String xff) {
            this.userAgent = ua;
            this.acceptLanguage = lang;
            this.xForwardedFor = xff;
        }
    }

    // ---- Request spec ----
    private static class RequestSpec {
        final String path;
        final String acceptOverride; // for images/fonts/etc.

        RequestSpec(String path, String acceptOverride) {
            this.path = path;
            this.acceptOverride = acceptOverride;
        }
    }

    public static void main(String[] args) throws Exception {
        String baseUrl    = args.length > 0 ? args[0] : "http://localhost:8080";
        int totalRequests = args.length > 1 ? Integer.parseInt(args[1]) : 1000;
        int concurrency   = args.length > 2 ? Integer.parseInt(args[2]) : 200;

        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }

        // ---- Build a small pool of “realistic” virtual users ----
        VirtualUser[] users = buildVirtualUsers(100);

        // Base Accept header for HTML-ish requests
        final String BASE_ACCEPT =
                "text/html,application/xhtml+xml,application/xml;q=0.9," +
                        "image/avif,image/webp,image/apng,*/*;q=0.8";



        // Simulated site structure
        final String[] HTML_PAGES = {"/", "/home", "/index", "/dashboard", "/login", "/api/pay" };
        final String[] STATIC_ASSETS = {
                "/static/css/main.css",
                "/static/css/app.css",
                "/static/js/app.js",
                "/static/js/chunk-vendors.js",
                "/static/img/logo.png",
                "/static/fonts/Inter-Regular.woff2"
        };
        final String[] CLICK_PATHS = {
                "/profile",
                "/settings",
                "/search",
                "/notifications",
                "/api/data",
                "/api/activity",
                "/help"
        };

        HttpClient client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .build();

        Semaphore semaphore = new Semaphore(concurrency);
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        AtomicInteger success     = new AtomicInteger();
        AtomicInteger failure     = new AtomicInteger();
        AtomicInteger rateLimited = new AtomicInteger();
        AtomicInteger exceptions  = new AtomicInteger();

        Map<Integer, AtomicInteger> statusCounts = new ConcurrentHashMap<>();

        Instant start = Instant.now();

        for (int i = 0; i < totalRequests; i++) {
            semaphore.acquire();

            // Pick a random virtual user (different fingerprints)
            VirtualUser vu = users[ThreadLocalRandom.current().nextInt(users.length)];
            RequestSpec spec = pickRequestSpec(HTML_PAGES, STATIC_ASSETS, CLICK_PATHS);

            String finalAccept = spec.acceptOverride != null ? spec.acceptOverride : BASE_ACCEPT;

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + spec.path))
                    .GET()
                    .header("User-Agent", vu.userAgent)
                    .header("Accept", finalAccept)
                    .header("Accept-Language", vu.acceptLanguage)
                    .header("X-Forwarded-For", vu.xForwardedFor)
                    .build();

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
                                rateLimited.incrementAndGet();
                            }
                            failure.incrementAndGet();
                        }
                    })
                    .exceptionally(e -> {
                        failure.incrementAndGet();
                        exceptions.incrementAndGet();
                        return null;
                    })
                    .whenComplete((r, t) -> semaphore.release());

            futures.add(f);
        }

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        Instant end = Instant.now();
        long millis = Duration.between(start, end).toMillis();

        int ok  = success.get();
        int ko  = failure.get();
        int lim = rateLimited.get();

        System.out.println("Base URL:     " + baseUrl);
        System.out.println("Total:        " + totalRequests);
        System.out.println("OK (2xx):     " + ok);
        System.out.println("Fail (!2xx):  " + ko);
        System.out.println("429s:         " + lim);
        System.out.println("Exceptions:   " + exceptions.get());
        System.out.println("Time:         " + millis + " ms");
        if (millis > 0) {
            double rps = (totalRequests * 1000.0) / millis;
            System.out.printf("RPS:          %.2f%n", rps);
        }

        System.out.println("\nStatus code breakdown:");
        statusCounts.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e ->
                        System.out.println(e.getKey() + " -> " + e.getValue().get())
                );
    }

    private static VirtualUser[] buildVirtualUsers(int count) {
        String[] userAgents = {
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                        "AppleWebKit/537.36 (KHTML, like Gecko) " +
                        "Chrome/122.0.0.0 Safari/537.36",
                "Mozilla/5.0 (Macintosh; Intel Mac OS X 13_5_1) " +
                        "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Safari/605.1.15",
                "Mozilla/5.0 (X11; Linux x86_64) " +
                        "AppleWebKit/537.36 (KHTML, like Gecko) " +
                        "Chrome/121.0.0.0 Safari/537.36",
                "Mozilla/5.0 (iPhone; CPU iPhone OS 17_2 like Mac OS X) " +
                        "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1",
                "Mozilla/5.0 (Linux; Android 14; Pixel 8) " +
                        "AppleWebKit/537.36 (KHTML, like Gecko) " +
                        "Chrome/121.0.0.0 Mobile Safari/537.36"
        };

        String[] acceptLangs = {
                "en-US,en;q=0.9",
                "en-GB,en;q=0.8",
                "en-US,ar;q=0.7",
                "ar-JO,ar;q=0.9,en;q=0.5",
                "fr-FR,fr;q=0.9,en;q=0.7"
        };

        VirtualUser[] users = new VirtualUser[count];

        for (int i = 0; i < count; i++) {
            String ua  = userAgents[ThreadLocalRandom.current().nextInt(userAgents.length)];
            String lang = acceptLangs[ThreadLocalRandom.current().nextInt(acceptLangs.length)];

            // Fake X-Forwarded-For in some 198.51.100.0/24 test block
            int lastOctet = 10 + ThreadLocalRandom.current().nextInt(200);
            String xff = "198.51.100." + lastOctet; // still all same /24

            users[i] = new VirtualUser(ua, lang, xff);
        }

        return users;
    }

    private static RequestSpec pickRequestSpec(String[] htmlPages,
                                               String[] staticAssets,
                                               String[] clickPaths) {
        double r = ThreadLocalRandom.current().nextDouble();

        if (r < 0.4) {
            // 40% HTML
            String path = randomFrom(htmlPages);
            return new RequestSpec(path, null);
        } else if (r < 0.8) {
            // 40% static assets
            String path = randomFrom(staticAssets);
            String acceptOverride;

            if (path.endsWith(".css")) {
                acceptOverride = "text/css,*/*;q=0.1";
            } else if (path.endsWith(".js")) {
                acceptOverride = "*/*";
            } else if (path.matches(".*\\.(png|jpg|jpeg|webp)$")) {
                acceptOverride = "image/avif,image/webp,image/apng,image/png,*/*;q=0.8";
            } else if (path.endsWith(".woff2")) {
                acceptOverride = "*/*";
            } else {
                acceptOverride = null;
            }

            return new RequestSpec(path, acceptOverride);
        } else {
            // 20% “clicky“ / API
            String path = randomFrom(clickPaths);
            return new RequestSpec(path, null);
        }
    }

    private static String randomFrom(String[] arr) {
        return arr[ThreadLocalRandom.current().nextInt(arr.length)];
    }
}


