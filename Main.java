import java.nio.file.*;
import java.util.*;

public class Main {
    enum Cat {
        ROADS("Public Works", 72, "pothole", "road", "footpath", "streetlight"),
        WATER("Water Dept", 24, "water", "leak", "pipeline", "drain"),
        GARBAGE("Sanitation", 48, "garbage", "waste", "dump", "smell"),
        POWER("Electricity Board", 12, "power", "wire", "transformer", "sparking"),
        OTHER("Help Desk", 96);

        final String dept; final int sla; final String[] keys;
        Cat(String dept, int sla, String... keys) { this.dept = dept; this.sla = sla; this.keys = keys; }
        int score(String t) {
            int n = 0;
            for (String k : keys) if (t.contains(k)) n++;
            return n;
        }
    }

    static final String[] URGENT = {"urgent", "danger", "school", "hospital", "sparking", "fire"};
    static final String[] LEVEL = {"LOW", "MEDIUM", "HIGH", "CRITICAL"};

    record Complaint(int id, String area, String text, int ageHours) {
        Cat cat() {
            Cat best = Cat.OTHER;
            int top = 0;
            String lower = text.toLowerCase();
            for (Cat c : Cat.values()) {
                int score = c.score(lower);
                if (score > top) {
                    top = score;
                    best = c;
                }
            }
            return best;
        }

        int priority() {
            int p = (cat() == Cat.POWER || cat() == Cat.WATER) ? 1 : 0;
            for (String u : URGENT) if (text.toLowerCase().contains(u)) p++;
            return Math.min(p, 3);
        }

        boolean breached() { return ageHours >= cat().sla; }
    }

    public static void main(String[] args) throws Exception {
        List<Complaint> list = new ArrayList<>(List.of(
            new Complaint(1, "Kothrud", "Huge pothole on main road near school, urgent", 5),
            new Complaint(2, "Baner", "Water pipeline leak in the lane", 30),
            new Complaint(3, "Hadapsar", "Garbage dump not cleared, bad smell", 60),
            new Complaint(4, "Aundh", "Transformer sparking near hospital, danger", 1),
            new Complaint(5, "Viman Nagar", "Need ward office timings", 0)
        ));

        list.sort((x, y) -> y.priority() != x.priority() ? y.priority() - x.priority() : y.ageHours() - x.ageHours());

        for (Complaint c : list) {
            System.out.printf("#%d %-8s %-8s -> %-18s %s%s%n",
                c.id(), LEVEL[c.priority()], c.cat(), c.cat().dept, c.text(), c.breached() ? "  [SLA BREACHED]" : "");
        }

        Path f = Path.of("complaints.txt");
        List<String> lines = new ArrayList<>();
        for (Complaint c : list) {
            lines.add(c.id() + "|" + c.area() + "|" + c.text() + "|" + c.ageHours());
        }
        Files.write(f, lines);
        System.out.println("Saved " + Files.readAllLines(f).size() + " complaints to " + f);
    }
}
