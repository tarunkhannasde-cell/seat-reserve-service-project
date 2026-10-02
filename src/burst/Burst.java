import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.regex.*;
import java.util.stream.*;

/**
 * On-sale stampede generator + correctness checker. Dependency-free; run with a JDK 21+:
 *
 *   java burst/Burst.java <BASE_URL> [--admin-key K] [--total 20000] [--concurrency 1000]
 *                                    [--hot-seats 5] [--storm 500] [--seats 2000]
 *
 * Fires, all interleaved at t=0 against one fresh show:
 *   hot-seat storm   --storm distinct users per hot seat, all grabbing the same seat
 *   stampede         many users grabbing 1-2 seats, biased to the "good" front rows
 *   idempotent retry same user, same key, same seats sent 5x concurrently
 *   key reuse        same user, same key, DIFFERENT seats, sent concurrently
 *   per-user limit   users firing 10 parallel single-seat reserves (limit 4) on uncontended seats
 *   spoofing         requests carrying "user_id" of a victim in the body; then cross-user cancels
 * While the burst runs, a monitor polls GET /shows/{id} and checks the reconciliation invariant.
 * Afterwards it verifies every correctness rule and reconciles /actuator/prometheus with the API.
 * Exit code 0 = all checks passed.
 */
public class Burst {

    // ---------------------------------------------------------------- config
    static String base;
    static String adminKey = System.getenv().getOrDefault("ADMIN_KEY", "dev-admin-key");
    static int total = 20_000, concurrency = 1000, hotSeats = 5, storm = 500, seatCount = 2000;
    static final int LIMIT = 4;

    static final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(20))
            .executor(Executors.newVirtualThreadPerTaskExecutor())
            .build();

    record Res(int code, String body, long micros) {
    }

    /** One reservation attempt and what came back. */
    record Attempt(String phase, String user, List<String> seats, String key, Res res) {
        String error() { return str(res.body, "error"); }
        String reservationId() { return str(res.body, "reservation_id"); }
        String bodyUser() { return str(res.body, "user_id"); }
    }

    public static void main(String[] args) throws Exception {
        parseArgs(args);
        System.out.printf("== seat burst against %s  (total≈%d, in-flight cap %d)%n", base, total, concurrency);

        // ------------------------------------------------------------ show
        List<String> seats = seatLabels(seatCount);
        List<String> hot = seats.subList(0, hotSeats);                         // A1..A5: the "good" seats
        int limitUsers = 50;
        List<String> limitSeats = seats.subList(seats.size() - limitUsers * 10, seats.size()); // uncontended block
        List<String> general = seats.subList(hotSeats, seats.size() - limitUsers * 10);

        String showBody = "{\"name\":\"burst-" + System.currentTimeMillis() + "\",\"price_paise\":25000,\"per_user_limit\":" + LIMIT
                + ",\"seats\":[" + seats.stream().map(s -> "\"" + s + "\"").collect(Collectors.joining(",")) + "]}";
        Res created = post("/shows", showBody, null, Map.of("X-Admin-Key", adminKey));
        if (created.code != 201) {
            die("could not create show: " + created.code + " " + created.body);
        }
        String showId = str(created.body, "id");
        System.out.printf("show %s: %d seats, per_user_limit=%d%n", showId, seatCount, LIMIT);

        // ------------------------------------------------------------ workload
        Random rnd = new Random(42);
        String run = Long.toString(System.currentTimeMillis(), 36);
        List<Attempt> plan = new ArrayList<>();
        Map<String, String> victimOf = new HashMap<>();

        for (String h : hot) {                                           // 1. hot-seat storm
            for (int i = 0; i < storm; i++) {
                plan.add(new Attempt("hot-storm", "storm-" + h + "-" + i + "-" + run, List.of(h), uuid(), null));
            }
        }
        int idemUsers = 300;
        for (int u = 0; u < idemUsers; u++) {                            // 3. idempotent retries
            String user = "idem-" + u + "-" + run;
            List<String> want = pick(general, 1 + rnd.nextInt(2), rnd, true);
            String key = uuid();
            for (int r = 0; r < 5; r++) {
                plan.add(new Attempt("idem-retry", user, want, key, null));
            }
        }
        int reuseUsers = 200;
        for (int u = 0; u < reuseUsers; u++) {                           // 4. same key, different body
            String user = "reuse-" + u + "-" + run;
            String key = uuid();
            plan.add(new Attempt("key-reuse", user, pick(general, 1, rnd, false), key, null));
            plan.add(new Attempt("key-reuse", user, pick(general, 1, rnd, false), key, null));
        }
        for (int u = 0; u < limitUsers; u++) {                           // 5. per-user limit
            String user = "limit-" + u + "-" + run;
            for (int i = 0; i < 10; i++) {
                plan.add(new Attempt("per-user-limit", user, List.of(limitSeats.get(u * 10 + i)), uuid(), null));
            }
        }
        for (int u = 0; u < 200; u++) {                                  // 6. spoofed body identity
            String user = "spoof-" + u + "-" + run;
            victimOf.put(user, "victim-" + u + "-" + run);
            plan.add(new Attempt("spoof", user, pick(general, 1, rnd, false), uuid(), null));
        }
        int stampede = Math.max(0, total - plan.size());                 // 2. general stampede
        int stampedeUsers = Math.max(1, stampede / 3);
        for (int i = 0; i < stampede; i++) {
            String user = "fan-" + (i % stampedeUsers) + "-" + run;
            plan.add(new Attempt("stampede", user, pick(general, 1 + rnd.nextInt(2), rnd, true), uuid(), null));
        }
        Collections.shuffle(plan, rnd);

        Set<String> users = new HashSet<>();
        plan.forEach(a -> users.add(a.user));
        System.out.printf("minting %d user tokens...%n", users.size());
        Map<String, String> tokens = mintTokens(users);

        // ------------------------------------------------------------ fire
        Semaphore inFlight = new Semaphore(concurrency);
        CountDownLatch go = new CountDownLatch(1);
        List<Attempt> done = Collections.synchronizedList(new ArrayList<>(plan.size()));
        AtomicBoolean firing = new AtomicBoolean(true);
        List<String> monitorViolations = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger monitorSamples = new AtomicInteger();

        Thread monitor = Thread.ofVirtual().start(() -> {
            while (firing.get()) {
                try {
                    Res st = get("/shows/" + showId);
                    if (st.code == 200) {
                        monitorSamples.incrementAndGet();
                        long[] c = counts(st.body);
                        if (c[0] + c[1] + c[2] != c[3] || !st.body.contains("\"reconciled\":true")) {
                            monitorViolations.add(Arrays.toString(c));
                        }
                    }
                    Thread.sleep(200);
                } catch (Exception ignored) {
                }
            }
        });

        long start = System.nanoTime();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (Attempt a : plan) {
                pool.submit(() -> {
                    go.await();
                    inFlight.acquire();
                    try {
                        StringBuilder body = new StringBuilder("{\"seats\":[")
                                .append(a.seats.stream().map(s -> "\"" + s + "\"").collect(Collectors.joining(",")))
                                .append("],\"idempotency_key\":\"").append(a.key).append("\"");
                        if (victimOf.containsKey(a.user)) {
                            body.append(",\"user_id\":\"").append(victimOf.get(a.user)).append("\"");
                        }
                        body.append("}");
                        Res r = post("/shows/" + showId + "/reserve", body.toString(), tokens.get(a.user), Map.of());
                        done.add(new Attempt(a.phase, a.user, a.seats, a.key, r));
                    } finally {
                        inFlight.release();
                    }
                    return null;
                });
            }
            go.countDown();
        }
        double seconds = (System.nanoTime() - start) / 1e9;
        firing.set(false);
        monitor.join();

        // ------------------------------------------------------------ cross-user cancel attempts
        List<Attempt> winners = done.stream().filter(a -> a.res.code == 201).toList();
        int crossCancels = 0, crossCancelBlocked = 0;
        List<String> userList = new ArrayList<>(tokens.keySet());
        for (int i = 0; i < Math.min(100, winners.size()); i++) {
            Attempt w = winners.get(i);
            String attacker = userList.get(i % userList.size());
            if (attacker.equals(w.user)) continue;
            crossCancels++;
            Res r = post("/reservations/" + w.reservationId() + "/cancel", "", tokens.get(attacker), Map.of());
            if (r.code == 404 || r.code == 403) crossCancelBlocked++;
        }

        // ------------------------------------------------------------ report
        Res finalState = get("/shows/" + showId);
        long[] fc = counts(finalState.body);
        Map<String, String> finalStatus = seatStatuses(finalState.body);

        System.out.printf("%nfired %d reservations in %.2fs (%.0f req/s)%n", done.size(), seconds, done.size() / seconds);
        long[] lat = done.stream().mapToLong(a -> a.res.micros).sorted().toArray();
        System.out.printf("latency ms  p50=%.1f  p95=%.1f  p99=%.1f  max=%.1f%n",
                pct(lat, 50), pct(lat, 95), pct(lat, 99), lat.length == 0 ? 0 : lat[lat.length - 1] / 1000.0);

        System.out.println("\noutcome distribution");
        Map<String, Long> dist = done.stream().collect(Collectors.groupingBy(Burst::outcome, TreeMap::new, Collectors.counting()));
        dist.forEach((k, v) -> System.out.printf("  %-34s %7d%n", k, v));

        System.out.println("\nby phase");
        Map<String, Map<String, Long>> byPhase = done.stream().collect(Collectors.groupingBy(a -> a.phase, TreeMap::new,
                Collectors.groupingBy(Burst::outcome, TreeMap::new, Collectors.counting())));
        byPhase.forEach((p, m) -> System.out.printf("  %-15s %s%n", p, m));

        // ------------------------------------------------------------ checks
        List<String[]> checks = new ArrayList<>();

        long fiveXX = done.stream().filter(a -> a.res.code >= 500).count();
        long transport = done.stream().filter(a -> a.res.code < 0).count();
        checks.add(chk("zero 5xx across the burst", fiveXX == 0, fiveXX + " x 5xx"));
        checks.add(chk("no transport errors (refused/timeouts)", transport == 0, transport + " errors"));

        Map<String, List<String>> seatWinners = new HashMap<>();
        for (Attempt w : winners) for (String s : w.seats) seatWinners.computeIfAbsent(s, k -> new ArrayList<>()).add(w.user);
        long doubleSold = seatWinners.values().stream().filter(l -> l.size() > 1).count();
        checks.add(chk("no seat confirmed to two users (from 201s)", doubleSold == 0, doubleSold + " seats with >1 winner"));

        StringBuilder hotDetail = new StringBuilder();
        boolean hotOk = true;
        for (String h : hot) {
            List<Attempt> hs = done.stream().filter(a -> a.phase.equals("hot-storm") && a.seats.get(0).equals(h)).toList();
            long w201 = hs.stream().filter(a -> a.res.code == 201).count();
            long l409 = hs.stream().filter(a -> a.res.code == 409).count();
            hotOk &= w201 == 1 && l409 == hs.size() - 1;
            hotDetail.append(h).append(": ").append(w201).append("x201/").append(l409).append("x409  ");
        }
        checks.add(chk("hot seats: exactly one 201, everyone else 409", hotOk, hotDetail.toString().trim()));

        boolean invariant = fc[0] + fc[1] + fc[2] == fc[3] && fc[3] == seatCount;
        checks.add(chk("final reconciliation available+held+confirmed==total", invariant,
                String.format("%d + %d + %d = %d (total %d)", fc[0], fc[1], fc[2], fc[0] + fc[1] + fc[2], fc[3])));
        checks.add(chk("reconciliation held during burst", monitorViolations.isEmpty(),
                monitorSamples.get() + " samples, " + monitorViolations.size() + " violations"));

        Set<String> confirmedInApi = finalStatus.entrySet().stream().filter(e -> e.getValue().equals("confirmed"))
                .map(Map.Entry::getKey).collect(Collectors.toSet());
        boolean apiMatches = confirmedInApi.equals(seatWinners.keySet());
        checks.add(chk("confirmed seats in API == seats won by 201s", apiMatches,
                confirmedInApi.size() + " confirmed vs " + seatWinners.size() + " won"));

        Map<String, List<Attempt>> idem = done.stream().filter(a -> a.phase.equals("idem-retry"))
                .collect(Collectors.groupingBy(a -> a.user));
        long idemBad = idem.values().stream().filter(l -> {
            long c201 = l.stream().filter(a -> a.res.code == 201).count();
            long ids = l.stream().filter(a -> a.res.code / 100 == 2).map(Attempt::reservationId).distinct().count();
            return c201 > 1 || ids > 1;
        }).count();
        long replays = idem.values().stream().flatMap(List::stream).filter(a -> a.res.code == 200).count();
        checks.add(chk("idempotent retries: one reservation per key", idemBad == 0,
                idem.size() + " keys x5, " + replays + " replays, " + idemBad + " keys with >1 reservation"));

        Map<String, List<Attempt>> reuse = done.stream().filter(a -> a.phase.equals("key-reuse"))
                .collect(Collectors.groupingBy(a -> a.user));
        long reuseBad = reuse.values().stream().filter(l -> l.stream().filter(a -> a.res.code / 100 == 2).count() > 1).count();
        long reuseConflict = reuse.values().stream().flatMap(List::stream).filter(a -> "idempotency_key_conflict".equals(a.error())).count();
        checks.add(chk("same key + different seats never both succeed", reuseBad == 0,
                reuseConflict + " x 409 idempotency_key_conflict, " + reuseBad + " double successes"));

        Map<String, Integer> seatsPerUser = new HashMap<>();
        for (Attempt w : winners) seatsPerUser.merge(w.user, w.seats.size(), Integer::sum);
        long overLimit = seatsPerUser.values().stream().filter(n -> n > LIMIT).count();
        long limitUsersAt4 = seatsPerUser.entrySet().stream().filter(e -> e.getKey().startsWith("limit-") && e.getValue() == LIMIT).count();
        checks.add(chk("per-user limit holds for every user", overLimit == 0,
                overLimit + " users over " + LIMIT + "; limit-test users at exactly " + LIMIT + ": " + limitUsersAt4 + "/" + limitUsers));

        long spoofWrong = done.stream().filter(a -> a.phase.equals("spoof") && a.res.code == 201 && !a.user.equals(a.bodyUser())).count();
        checks.add(chk("identity is token-derived (spoofed body ignored)", spoofWrong == 0, spoofWrong + " acted as victim"));
        checks.add(chk("cross-user cancel always refused", crossCancelBlocked == crossCancels,
                crossCancelBlocked + "/" + crossCancels + " refused"));

        // metrics reconciliation (gauges refresh every 1s)
        Thread.sleep(2500);
        Res prom = get("/actuator/prometheus");
        if (prom.code == 200) {
            double mConfirmed = metric(prom.body, "reservations_confirmed_total", showId, null);
            double mAvail = metric(prom.body, "seats_available", showId, null);
            double mConfSeats = metric(prom.body, "seats_confirmed", showId, null);
            double mTaken = metric(prom.body, "reservations_declined_total", showId, "seat_taken");
            double mLimit = metric(prom.body, "reservations_declined_total", showId, "per_user_limit");
            double mReplay = metric(prom.body, "reservations_declined_total", showId, "idempotent_replay");
            long cTaken = done.stream().filter(a -> "seat_taken".equals(a.error())).count();
            long cLimit = done.stream().filter(a -> "per_user_limit".equals(a.error())).count();
            long cReplay = done.stream().filter(a -> a.res.code == 200).count();
            System.out.printf("%nmetrics: confirmed=%.0f seat_taken=%.0f per_user_limit=%.0f idempotent_replay=%.0f seats_available=%.0f seats_confirmed=%.0f%n",
                    mConfirmed, mTaken, mLimit, mReplay, mAvail, mConfSeats);
            boolean metricsOk = mConfirmed == winners.size() && mAvail == fc[0] && mConfSeats == fc[2]
                    && mTaken == cTaken && mLimit == cLimit && mReplay == cReplay;
            checks.add(chk("metrics reconcile with API + client view", metricsOk,
                    String.format("201s %d/%.0f, avail %d/%.0f, taken %d/%.0f, limit %d/%.0f, replay %d/%.0f (client/metric)",
                            winners.size(), mConfirmed, fc[0], mAvail, cTaken, mTaken, cLimit, mLimit, cReplay, mReplay)));
        } else {
            checks.add(chk("metrics endpoint reachable", false, "HTTP " + prom.code));
        }

        System.out.println("\ncorrectness checks");
        boolean allOk = true;
        for (String[] c : checks) {
            System.out.printf("  [%s] %-50s %s%n", c[0], c[1], c[2]);
            allOk &= c[0].equals("PASS");
        }
        System.out.printf("%nshow %s  final: available=%d held=%d confirmed=%d total=%d%n", showId, fc[0], fc[1], fc[2], fc[3]);
        System.out.println(allOk ? "\nRESULT: PASS" : "\nRESULT: FAIL");
        System.exit(allOk ? 0 : 1);
    }

    // ---------------------------------------------------------------- helpers

    static String outcome(Attempt a) {
        int c = a.res.code;
        if (c < 0) return "transport_error";
        if (c == 201) return "201 confirmed";
        if (c == 200) return "200 idempotent_replay";
        if (c >= 500) return c + " SERVER_ERROR";
        String e = a.error();
        return c + " " + (e == null ? "?" : e);
    }

    static Map<String, String> mintTokens(Set<String> users) throws Exception {
        Map<String, String> tokens = new ConcurrentHashMap<>();
        Semaphore sem = new Semaphore(Math.min(concurrency, 200));
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (String u : users) {
                pool.submit(() -> {
                    sem.acquire();
                    try {
                        for (int attempt = 0; attempt < 3; attempt++) {
                            Res r = post("/auth/token", "{\"user_id\":\"" + u + "\"}", null, Map.of());
                            if (r.code == 200) {
                                tokens.put(u, str(r.body, "token"));
                                break;
                            }
                        }
                    } finally {
                        sem.release();
                    }
                    return null;
                });
            }
        }
        if (tokens.size() != users.size()) die("minted only " + tokens.size() + "/" + users.size() + " tokens");
        return tokens;
    }

    static Res post(String path, String body, String token, Map<String, String> headers) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path))
                .timeout(Duration.ofSeconds(90))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (token != null) b.header("Authorization", "Bearer " + token);
        headers.forEach(b::header);
        return send(b.build());
    }

    static Res get(String path) {
        return send(HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(30)).GET().build());
    }

    static Res send(HttpRequest req) {
        long t = System.nanoTime();
        try {
            HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
            return new Res(r.statusCode(), r.body(), (System.nanoTime() - t) / 1000);
        } catch (Exception e) {
            return new Res(-1, e.toString(), (System.nanoTime() - t) / 1000);
        }
    }

    static String str(String json, String field) {
        if (json == null) return null;
        Matcher m = Pattern.compile("\"" + field + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        return m.find() ? m.group(1) : null;
    }

    static long[] counts(String body) {
        Matcher m = Pattern.compile("\"counts\":\\{\"available\":(\\d+),\"held\":(\\d+),\"confirmed\":(\\d+),\"total\":(\\d+)}").matcher(body);
        if (!m.find()) return new long[]{-1, -1, -1, -2};
        return new long[]{Long.parseLong(m.group(1)), Long.parseLong(m.group(2)), Long.parseLong(m.group(3)), Long.parseLong(m.group(4))};
    }

    static Map<String, String> seatStatuses(String body) {
        Map<String, String> out = new HashMap<>();
        Matcher m = Pattern.compile("\\{\"(?:seat|status)\":\"([^\"]+)\",\"(?:seat|status)\":\"([^\"]+)\"}").matcher(body);
        while (m.find()) {
            String a = m.group(1), b = m.group(2);
            boolean aIsStatus = a.equals("available") || a.equals("held") || a.equals("confirmed");
            out.put(aIsStatus ? b : a, aIsStatus ? a : b);
        }
        return out;
    }

    static double metric(String prom, String name, String showId, String reason) {
        double sum = 0;
        for (String line : prom.split("\n")) {
            if (!line.startsWith(name + "{") || !line.contains("show_id=\"" + showId + "\"")) continue;
            if (reason != null && !line.contains("reason=\"" + reason + "\"")) continue;
            sum += Double.parseDouble(line.substring(line.lastIndexOf(' ') + 1));
        }
        return sum;
    }

    /** Pick n distinct seats; when biased, prefer the front of the list (the "good" rows). */
    static List<String> pick(List<String> from, int n, Random rnd, boolean biased) {
        Set<String> out = new LinkedHashSet<>();
        while (out.size() < n) {
            double x = rnd.nextDouble();
            int idx = biased ? (int) (Math.pow(x, 3) * from.size()) : (int) (x * from.size());
            out.add(from.get(Math.min(idx, from.size() - 1)));
        }
        return new ArrayList<>(out);
    }

    static List<String> seatLabels(int n) {
        List<String> out = new ArrayList<>(n);
        int perRow = 50;
        for (int i = 0; i < n; i++) {
            int row = i / perRow;
            String rowName = row < 26 ? String.valueOf((char) ('A' + row)) : "R" + row;
            out.add(rowName + (i % perRow + 1));
        }
        return out;
    }

    static double pct(long[] sorted, int p) {
        if (sorted.length == 0) return 0;
        return sorted[Math.min(sorted.length - 1, (int) Math.ceil(p / 100.0 * sorted.length) - 1)] / 1000.0;
    }

    static String[] chk(String name, boolean ok, String detail) {
        return new String[]{ok ? "PASS" : "FAIL", name, detail};
    }

    static String uuid() {
        return UUID.randomUUID().toString();
    }

    static void die(String msg) {
        System.err.println("ERROR: " + msg);
        System.exit(2);
    }

    static void parseArgs(String[] args) {
        if (args.length == 0 || args[0].startsWith("-")) {
            die("usage: java burst/Burst.java <BASE_URL> [--admin-key K] [--total N] [--concurrency N] [--hot-seats N] [--storm N] [--seats N]");
        }
        base = args[0].replaceAll("/+$", "");
        for (int i = 1; i + 1 < args.length; i += 2) {
            String v = args[i + 1];
            switch (args[i]) {
                case "--admin-key" -> adminKey = v;
                case "--total" -> total = Integer.parseInt(v);
                case "--concurrency" -> concurrency = Integer.parseInt(v);
                case "--hot-seats" -> hotSeats = Integer.parseInt(v);
                case "--storm" -> storm = Integer.parseInt(v);
                case "--seats" -> seatCount = Integer.parseInt(v);
                default -> die("unknown option " + args[i]);
            }
        }
        if (seatCount < hotSeats + 500 + 100) die("--seats must be at least hot-seats + 600");
    }
}
