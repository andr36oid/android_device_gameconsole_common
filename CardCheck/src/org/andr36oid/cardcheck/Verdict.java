package org.andr36oid.cardcheck;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Puts the card's ID, the speed test and the real size check together into plain advice
 * ("What this means" on the page). Plain Java for the host test; Texts has the words.
 */
final class Verdict {

    static final int SLOW = 0, OK = 1, GOOD = 2, UNKNOWN = -1;

    /** How the console talks to the card, from the kernel's "new ... card" line. */
    enum Bus {
        UNKNOWN(0), DEFAULT(12), HIGH(25), SDR12(12), SDR25(25), SDR50(50), DDR50(50),
        SDR104(104);

        /** Top speed of the link in MB/s. */
        final int mbps;

        Bus(int mbps) {
            this.mbps = mbps;
        }

        boolean uhs() {
            return this == SDR50 || this == DDR50 || this == SDR104;
        }

        static Bus of(String bus) {
            if (bus == null || bus.isEmpty()) return UNKNOWN;
            for (Bus b : new Bus[] {SDR104, SDR50, DDR50, SDR25, SDR12}) {
                if (bus.contains(b.name())) return b;
            }
            if (bus.contains("high_speed")) return HIGH;
            return bus.matches("[A-Za-z_]*SD[A-Z]*") ? DEFAULT : UNKNOWN;
        }
    }

    enum Kind {
        /** A real size check is paused and its test data fills the card. */
        PAUSED,
        /** The real size check failed: fake or damaged. */
        FAKE,
        /** The card reported a write error during the real size check. */
        WRITE_ERROR,
        /** The card's ID has signs of a fake (signs), and no real size check passed. */
        SIGNS,
        /** The ID looks odd, but the real size check passed. */
        SIGNS_BUT_REAL,
        /** Marked for at least a MB/s writing, wrote at b MB/s. */
        CLAIM_WRITE,
        /** Marked A{a}, but small blocks are far slower. */
        CLAIM_SMALL,
        /** Slow card. */
        SLOW,
        /** A UHS card that runs in a slower mode (a = the mode's top MB/s). */
        BUS_SLOW,
        /** No speed test yet. */
        UNTESTED,
        /** Nothing wrong found. */
        GOOD,
    }

    static final class Item {
        final Kind kind;
        final double a, b;
        final List<CardId.Sign> signs;

        Item(Kind kind, double a, double b, List<CardId.Sign> signs) {
            this.kind = kind;
            this.a = a;
            this.b = b;
            this.signs = signs;
        }

        @Override
        public String toString() {
            return kind + (a != 0 || b != 0 ? "(" + a + "," + b + ")" : "")
                    + (signs.isEmpty() ? "" : signs.toString());
        }
    }

    private Verdict() {
    }

    /** Sequential speed: the thresholds the speed test always used. */
    static int seqLevel(double write, double read) {
        if (Double.isNaN(write) || Double.isNaN(read)) return UNKNOWN;
        if (write >= 10 && read >= 40) return GOOD;
        if (read >= 15 && write >= 3) return OK;
        return SLOW;
    }

    /**
     * Small blocks. A1 promises 1500 reads and 500 writes a second; cheap cards manage a
     * few hundred reads and sometimes under 50 writes, which is what makes games made of
     * many files and saving slow.
     */
    static int smallLevel(double reads, double writes) {
        if (Double.isNaN(reads) || Double.isNaN(writes)) return UNKNOWN;
        if (reads >= 1500 && writes >= 500) return GOOD;
        if (reads >= 500 && writes >= 50) return OK;
        return SLOW;
    }

    /** Both together; small blocks count only when they were measured. */
    static int level(Map<String, String> speed) {
        if (!"done".equals(speed.get("state"))) return UNKNOWN;
        final long mb = Stats.number(speed, "size_mb");
        final int seq = seqLevel(Stats.speed(speed, mb, "w0", "w1"),
                Stats.speed(speed, mb, "r0", "r1"));
        final int small = smallLevel(Stats.iops(speed, "rr"), Stats.iops(speed, "rw"));
        if (small == UNKNOWN || seq == UNKNOWN) return seq;
        return Math.min(seq, small);
    }

    /**
     * The advice, most important first. {@code id} may be null (USB drive), the maps
     * empty. {@code paused}: a real size check is paused on this card.
     */
    static List<Item> advise(CardId id, long diskBytes, Map<String, String> speed,
            Map<String, String> full, boolean paused, int nowYear, int nowMonth) {
        final List<Item> items = new ArrayList<>();
        final List<CardId.Sign> none = Collections.emptyList();
        if (paused) items.add(new Item(Kind.PAUSED, 0, 0, none));

        final String result = "done".equals(full.get("state")) ? full.get("result") : null;
        if ("bad_data".equals(result) || "wraps".equals(result)) {
            items.add(new Item(Kind.FAKE, 0, 0, none));
            return items;
        }
        if ("write_error".equals(result)) items.add(new Item(Kind.WRITE_ERROR, 0, 0, none));

        final List<CardId.Sign> signs = id != null ? id.signs(diskBytes, nowYear, nowMonth)
                : none;
        if (!signs.isEmpty()) {
            items.add(new Item("pass".equals(result) ? Kind.SIGNS_BUT_REAL : Kind.SIGNS,
                    0, 0, signs));
        }

        final int level = level(speed);
        if (level == UNKNOWN) {
            items.add(new Item(Kind.UNTESTED, 0, 0, none));
            return items;
        }
        final long mb = Stats.number(speed, "size_mb");
        final double write = Stats.speed(speed, mb, "w0", "w1");
        final double reads = Stats.iops(speed, "rr");
        final double writes = Stats.iops(speed, "rw");
        if (id != null && id.promisedWriteMbps() > 0 && write < id.promisedWriteMbps() / 3.0) {
            items.add(new Item(Kind.CLAIM_WRITE, id.promisedWriteMbps(), write, none));
        }
        if (id != null && id.appClass > 0 && !Double.isNaN(reads)
                && (reads < id.promisedReadIops() / 3.0
                        || writes < id.promisedWriteIops() / 3.0)) {
            items.add(new Item(Kind.CLAIM_SMALL, id.appClass, 0, none));
        }
        final Bus bus = Bus.of(speed.get("bus"));
        if (id != null && id.uhsGrade > 0 && bus != Bus.UNKNOWN && !bus.uhs()) {
            items.add(new Item(Kind.BUS_SLOW, bus.mbps, 0, none));
        }
        if (level == SLOW) items.add(new Item(Kind.SLOW, 0, 0, none));
        if (items.isEmpty()) items.add(new Item(Kind.GOOD, 0, 0, none));
        return items;
    }
}
