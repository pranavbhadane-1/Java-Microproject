import java.io.IOException;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.stream.*;

/** Civic Complaint Triage Tracker - pure Java 21, no external libraries.
 *  Auto-classifies complaints, assigns priority, routes to a department,
 *  tracks SLA breaches, and persists to a TSV file.
 *  Run: java CivicTracker.java --demo   |   java CivicTracker.java (interactive) */
public class CivicTracker {

    enum Priority { LOW, MEDIUM, HIGH, CRITICAL }
    enum Status { OPEN, RESOLVED }

    enum Category {
        ROADS("Public Works", 72, "pothole", "road", "crack", "footpath", "streetlight", "traffic"),
        WATER("Water Supply Dept", 24, "water", "leak", "pipeline", "drain", "sewage", "overflow", "tap"),
        GARBAGE("Sanitation Dept", 48, "garbage", "waste", "trash", "dump", "litter", "smell"),
        POWER("Electricity Board", 12, "power", "electric", "wire", "transformer", "outage", "sparking"),
        SAFETY("Police / Disaster Mgmt", 6, "fire", "accident", "theft", "assault", "flood", "collapse"),
        OTHER("Citizen Help Desk", 96);

        final String dept; final int slaHours; final List<String> keys;

        Category(String dept, int slaHours, String... keys) {
            this.dept = dept;
            this.slaHours = slaHours;
            this.keys = List.of(keys);
        }

        int score(String t) { return (int) keys.stream().filter(t::contains).count(); }
    }

    static final List<String> URGENT = List.of("urgent", "danger", "child", "school", "hospital",
            "sparking", "fire", "collapse", "accident", "injur");

    static Category classify(String text) {
        String t = text.toLowerCase();
        return Arrays.stream(Category.values())
                .filter(c -> c != Category.OTHER)
                .max(Comparator.comparingInt(c -> c.score(t)))
                .filter(c -> c.score(t) > 0)
                .orElse(Category.OTHER);
    }

    static Priority prioritize(Category c, String text) {
        String t = text.toLowerCase();
        int base = switch (c) {
            case SAFETY -> 2;
            case POWER, WATER -> 1;
            default -> 0;
        };
        long urgentHits = URGENT.stream().filter(t::contains).count();
        return Priority.values()[Math.min(base + (int) Math.min(urgentHits, 2), 3)];
    }

    static class Complaint {
        final int id; final String area, text; final Instant created;
        final Category cat; final Priority pri; Status status = Status.OPEN;

        Complaint(int id, String area, String text, Instant created) {
            this.id = id;
            this.area = area;
            this.text = text.replace('\t', ' ');
            this.created = created;
            this.cat = classify(text);
            this.pri = prioritize(cat, text);
        }

        boolean breached(Instant now) {
            return status == Status.OPEN && Duration.between(created, now).toHours() >= cat.slaHours;
        }

        String toLine() {
            return String.join("\t", "" + id, "" + created.getEpochSecond(), status.name(), area, text);
        }
    }

    private final List<Complaint> all = new ArrayList<>();
    private final Path file;
    private int nextId = 1;

    CivicTracker(Path file) throws IOException {
        this.file = file;
        if (Files.exists(file)) {
            for (String l : Files.readAllLines(file)) {
                String[] f = l.split("\t", 5);
                Complaint c = new Complaint(Integer.parseInt(f[0]), f[3], f[4], Instant.ofEpochSecond(Long.parseLong(f[1])));
                c.status = Status.valueOf(f[2]);
                all.add(c);
                nextId = Math.max(nextId, c.id + 1);
            }
        }
    }

    Complaint add(String area, String text, Instant when) throws IOException {
        Complaint c = new Complaint(nextId++, area, text, when);
        all.add(c);
        save();
        return c;
    }

    boolean resolve(int id) throws IOException {
        Optional<Complaint> c = all.stream().filter(x -> x.id == id && x.status == Status.OPEN).findFirst();
        c.ifPresent(x -> x.status = Status.RESOLVED);
        if (c.isPresent()) save();
        return c.isPresent();
    }

    void save() throws IOException {
        Files.write(file, all.stream().map(Complaint::toLine).collect(Collectors.toList()));
    }

    static final Comparator<Complaint> TRIAGE =
            Comparator.comparing((Complaint c) -> c.pri).reversed().thenComparing(c -> c.created);

    void print(List<Complaint> list) {
        Instant now = Instant.now();
        System.out.printf("%-4s %-9s %-8s %-8s %-14s %-24s %s%n", "ID", "PRIORITY", "CATEGORY", "STATUS", "AREA", "ROUTED TO", "COMPLAINT");
        for (Complaint c : list) {
            System.out.printf("%-4d %-9s %-8s %-8s %-14s %-24s %s%s%n",
                    c.id, c.pri, c.cat, c.status, c.area,
                    c.cat.dept, c.text, c.breached(now) ? "  [SLA BREACHED]" : "");
        }
    }

    void list() { print(all.stream().sorted(TRIAGE).toList()); }

    void next() {
        all.stream().filter(c -> c.status == Status.OPEN).min(TRIAGE)
                .ifPresentOrElse(c -> {
                    System.out.println("Handle next:");
                    print(List.of(c));
                }, () -> System.out.println("No open complaints."));
    }

    void stats() {
        Instant now = Instant.now();
        Map<Category, Long> byCat = all.stream().collect(Collectors.groupingBy(c -> c.cat, TreeMap::new, Collectors.counting()));
        long open = all.stream().filter(c -> c.status == Status.OPEN).count();
        long breached = all.stream().filter(c -> c.breached(now)).count();
        Map<String, Long> byArea = all.stream().collect(Collectors.groupingBy(c -> c.area, TreeMap::new, Collectors.counting()));
        System.out.printf("Total: %d | Open: %d | Resolved: %d | SLA breached: %d%n", all.size(), open, all.size() - open, breached);
        System.out.println("By category: " + byCat);
        System.out.println("By area    : " + byArea);
        double rate = all.isEmpty() ? 0 : 100.0 * (all.size() - open) / all.size();
        System.out.printf("Resolution rate: %.1f%%%n", rate);
    }

    static void demo() throws IOException {
        Path f = Path.of("complaints.tsv");
        Files.deleteIfExists(f);
        CivicTracker t = new CivicTracker(f);
        Instant now = Instant.now();
        System.out.println("=== 1. Filing complaints (auto-classified & routed) ===");
        t.add("Kothrud", "Huge pothole on main road near school, urgent", now.minus(Duration.ofHours(5)));
        t.add("Baner", "Water pipeline leak flooding the lane", now.minus(Duration.ofHours(30)));
        t.add("Hadapsar", "Garbage dump not cleared for a week, bad smell", now.minus(Duration.ofHours(60)));
        t.add("Aundh", "Transformer sparking near hospital gate, danger", now.minus(Duration.ofHours(1)));
        t.add("Wakad", "Streetlight not working on the footpath", now.minus(Duration.ofHours(10)));
        t.add("Viman Nagar", "Need info about ward office timings", now);
        t.list();
        System.out.println("\n=== 2. Triage: what to handle first ===");
        t.next();
        System.out.println("\n=== 3. Resolve #4 (transformer) and #2 (water) ===");
        System.out.println("resolve 4 -> " + t.resolve(4) + ", resolve 2 -> " + t.resolve(2) + ", resolve 99 -> " + t.resolve(99));
        t.next();
        System.out.println("\n=== 4. Stats ===");
        t.stats();
        System.out.println("\n=== 5. Persistence check: reload from disk in a fresh instance ===");
        CivicTracker reloaded = new CivicTracker(f);
        reloaded.list();
    }

    static void interactive() throws IOException {
        CivicTracker t = new CivicTracker(Path.of("complaints.tsv"));
        System.out.println("Commands: add <area> | <text>, list, next, resolve <id>, stats, quit");
        Scanner in = new Scanner(System.in);
        while (true) {
            System.out.print("> ");
            if (!in.hasNextLine()) break;
            String line = in.nextLine().trim();
            if (line.isEmpty()) continue;
            String cmd = line.split("\\s+")[0].toLowerCase();
            try {
                switch (cmd) {
                    case "add" -> {
                        String[] p = line.substring(3).split("\\|", 2);
                        if (p.length < 2) {
                            System.out.println("Usage: add <area> | <text>");
                            break;
                        }
                        Complaint c = t.add(p[0].trim(), p[1].trim(), Instant.now());
                        System.out.printf("Filed #%d -> %s, %s priority%n", c.id, c.cat.dept, c.pri);
                    }
                    case "list" -> t.list();
                    case "next" -> t.next();
                    case "resolve" -> System.out.println(t.resolve(Integer.parseInt(line.split("\\s+")[1])) ? "Resolved." : "Not found / already resolved.");
                    case "stats" -> t.stats();
                    case "quit", "exit" -> { return; }
                    default -> System.out.println("Unknown command.");
                }
            } catch (RuntimeException e) {
                System.out.println("Error: " + e.getMessage());
            }
        }
    }

    public static void main(String[] args) throws IOException {
        if (args.length > 0 && args[0].equals("--demo")) {
            demo();
        } else {
            interactive();
        }
    }
}
