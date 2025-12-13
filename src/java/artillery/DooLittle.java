package java.artillery;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.LockSupport;

@SuppressWarnings("deprecation")
public class DooLittle {

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

    private static class RequestSpec {
        final String path;
        final String acceptOverride;

        RequestSpec(String path, String acceptOverride) {
            this.path = path;
            this.acceptOverride = acceptOverride;
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: java PolishedCannonV4 <baseUrl> [targetRps] [concurrency] [durationSeconds]");
            System.err.println("Example: java PolishedCannonV4 http://localhost:8080 500 200 60");
            System.err.println("         (500 RPS, 200 max concurrency, 60s)");
            return;
        }

        String baseUrl       = args[0];
        int targetRps        = args.length > 1 ? Integer.parseInt(args[1]) : 500;   // desired requests per second
        int concurrency      = args.length > 2 ? Integer.parseInt(args[2]) : 200;   // max in-flight requests
        long durationSeconds = args.length > 3 ? Long.parseLong(args[3]) : 0L;      // 0 or negative = run until Ctrl+C

        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }

        System.out.println("Base URL:          " + baseUrl);
        System.out.println("Target RPS:        " + targetRps);
        System.out.println("Max concurrency:   " + concurrency);
        System.out.println("Duration seconds:  " + (durationSeconds <= 0 ? "infinite (Ctrl+C to stop)" : durationSeconds));
        System.out.println();

        // ---- Virtual users ----
        VirtualUser[] users = buildVirtualUsers(100);

        // Paths to simulate HTML, static assets, and “clicks”
        final String[] HTML_PAGES = {"/", "/Default/Ar", "/index", "/dashboard","/admin","/lil-doo"};
        final String[] STATIC_ASSETS = {
                "/static/css/main.css",
                "/static/css/app.css",
                "/static/js/app.js",
                "/static/js/chunk-vendors.js",
                "/static/img/logo.png",
                "/static/fonts/Dog.woff2"
        };
        final String[] CLICK_PATHS = {
                "/profile",
                "/settings",
                "/search?q=xof",
                "/notifications",
                "/api/data",
                "/api/pay",
                "/help"
        };

        final String BASE_ACCEPT =
                "text/html,application/xhtml+xml,application/xml;q=0.9," +
                        "image/avif,image/webp,image/apng,*/*;q=0.8";

        // ---- HttpClient with bounded thread pool ----
        ExecutorService executor = Executors.newFixedThreadPool(concurrency, r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            t.setName("CannonWorker-" + t.getId());
            return t;
        });

        HttpClient client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .executor(executor)
                .build();

        // ---- Concurrency guard ----
        Semaphore inFlightLimiter = new Semaphore(concurrency);

        // ---- Stats ----
        AtomicLong totalSent      = new AtomicLong();
        AtomicLong totalOk        = new AtomicLong();
        AtomicLong totalFail      = new AtomicLong();
        AtomicLong total429       = new AtomicLong();
        AtomicLong totalExceptions= new AtomicLong();
        ConcurrentHashMap<Integer, AtomicLong> statusCounts = new ConcurrentHashMap<>();

        Instant start = Instant.now();
        long startNano = System.nanoTime();

        // Stats printer thread
        Thread statsThread = new Thread(() -> {
            try {
                while (true) {
                    Thread.sleep(5000);
                    printStats(start, totalSent, totalOk, totalFail, total429, totalExceptions, statusCounts);
                }
            } catch (InterruptedException ignored) {
            }
        });
        statsThread.setDaemon(true);
        statsThread.start();

        // ---- Main fire loop ----
        long intervalNanos = (long) (1_000_000_000L / (double) targetRps);
        long nextFire = System.nanoTime();

        outer:
        while (true) {
            long now = System.nanoTime();

            if (durationSeconds > 0) {
                long elapsed = (now - startNano) / 1_000_000_000L;
                if (elapsed >= durationSeconds) {
                    break outer;
                }
            }

            if (now < nextFire) {
                long sleepNanos = nextFire - now;
                if (sleepNanos > 0) {
                    LockSupport.parkNanos(sleepNanos);
                }
                continue;
            }

            // We’re at or past nextFire → send one request
            nextFire += intervalNanos;

            // Acquire a slot for concurrency
            inFlightLimiter.acquire();

            // Pick virtual user + path
            VirtualUser vu = users[ThreadLocalRandom.current().nextInt(users.length)];
            RequestSpec spec = pickRequestSpec(HTML_PAGES, STATIC_ASSETS, CLICK_PATHS);
            String finalAccept = spec.acceptOverride != null ? spec.acceptOverride : BASE_ACCEPT;

            String path = spec.path;
            URI uri = URI.create(baseUrl + path);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(uri)
                    .GET()
                    .header("User-Agent", vu.userAgent)
                    .header("Accept", finalAccept)
                    .header("Accept-Language", vu.acceptLanguage)
                    .header("X-Forwarded-For", vu.xForwardedFor)
                    .build();

            totalSent.incrementAndGet();

            client.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                    .whenComplete((resp, throwable) -> {
                        try {
                            if (throwable != null) {
                                totalFail.incrementAndGet();
                                totalExceptions.incrementAndGet();
                                return;
                            }

                            int code = resp.statusCode();
                            statusCounts.computeIfAbsent(code, k -> new AtomicLong()).incrementAndGet();

                            if (code / 100 == 2) {
                                totalOk.incrementAndGet();
                            } else {
                                totalFail.incrementAndGet();
                                if (code == 429) {
                                    total429.incrementAndGet();
                                }
                            }
                        } finally {
                            inFlightLimiter.release();
                        }
                    });
        }

        // Stop: wait a bit for remaining requests to finish
        System.out.println("Stopping fire loop, waiting for in-flight requests to finish...");
        while (inFlightLimiter.availablePermits() != concurrency) {
            Thread.sleep(200);
        }

        printStats(start, totalSent, totalOk, totalFail, total429, totalExceptions, statusCounts);

        executor.shutdownNow();
        System.out.println("CannonV4 done.");
    }

    // ---- Helpers ----

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
            String ua   = userAgents[ThreadLocalRandom.current().nextInt(userAgents.length)];
            String lang = acceptLangs[ThreadLocalRandom.current().nextInt(acceptLangs.length)];

            int lastOctet = 10 + ThreadLocalRandom.current().nextInt(200);
            String xff = "198.51.100." + lastOctet; // test range

            users[i] = new VirtualUser(ua, lang, xff);
        }

        return users;
    }

    private static RequestSpec pickRequestSpec(String[] htmlPages,
                                               String[] staticAssets,
                                               String[] clickPaths) {
        double r = ThreadLocalRandom.current().nextDouble();

        if (r < 0.4) {
            String path = randomFrom(htmlPages);
            return new RequestSpec(path, null);
        } else if (r < 0.8) {
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
            String path = randomFrom(clickPaths);
            return new RequestSpec(path, null);
        }
    }

    private static String randomFrom(String[] arr) {
        return arr[ThreadLocalRandom.current().nextInt(arr.length)];
    }

    private static void printStats(Instant start,
                                   AtomicLong totalSent,
                                   AtomicLong totalOk,
                                   AtomicLong totalFail,
                                   AtomicLong total429,
                                   AtomicLong totalExceptions,
                                   ConcurrentHashMap<Integer, AtomicLong> statusCounts) {
        Instant now = Instant.now();
        long millis = Duration.between(start, now).toMillis();
        double seconds = millis / 1000.0;
        long sent = totalSent.get();

        System.out.println("---- Stats @ " + now + " ----");
        System.out.println("Total sent:     " + sent);
        System.out.println("OK (2xx):       " + totalOk.get());
        System.out.println("Fail (!2xx):    " + totalFail.get());
        System.out.println("429s:           " + total429.get());
        System.out.println("Exceptions:     " + totalExceptions.get());
        if (seconds > 0) {
            double rps = sent / seconds;
            System.out.printf("Observed RPS:   %.2f%n", rps);
        }

        System.out.println("Status breakdown:");
        statusCounts.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> System.out.println("  " + e.getKey() + " -> " + e.getValue().get()));
        System.out.println("------------------------------");
    }
}


