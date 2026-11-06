package org.andr36oid.gamecomfort;

/** Device-independent compare-before-restore policy. All methods run on one owner
 * thread. The journal is saved before changing a setting, including an in-flight
 * target so process death between the setting write and journal commit is safe. */
final class BrightnessState {
    static final int NONE = -1;

    interface Device {
        boolean manual();
        int read();
        boolean write(int value);
    }

    interface Store {
        Record load();
        /** null clears the ownership journal. */
        boolean save(Record record);
    }

    static final class Record {
        final int original, applied, pending;
        final boolean suppressed;

        Record(int original, int applied, int pending, boolean suppressed) {
            this.original = original;
            this.applied = applied;
            this.pending = pending;
            this.suppressed = suppressed;
        }

        boolean valid() {
            return original > 0 && applied > 0 && (pending == NONE || pending > 0);
        }

        boolean owns(int value) {
            return value > 0 && (value == applied || value == pending);
        }
    }

    private final Device device;
    private final Store store;
    private Record record;
    private boolean suppressed;

    BrightnessState(Device device, Store store) {
        this.device = device;
        this.store = store;
        Record saved = store.load();
        if (saved != null && saved.valid()) {
            record = saved;
            suppressed = saved.suppressed;
        }
    }

    boolean suppressed() {
        return suppressed;
    }

    /** Observe slider changes without reapplying a rule in response to them. */
    void settingsChanged() {
        if (record == null || suppressed) return;
        if (!device.manual()) {
            release(false);
            return;
        }
        int current = device.read();
        if (current > 0 && !record.owns(current)) abandon();
    }

    /** A configured app may acquire/change brightness only with a durable baseline. */
    boolean apply(int target) {
        if (target <= 0 || !device.manual()) return false;
        int current = device.read();
        if (current <= 0 || suppressed) return false;
        if (record != null && !record.owns(current)) {
            abandon();
            return false;
        }
        if (record != null && current == target && record.pending == NONE) return true;
        int original = record == null ? current : record.original;
        Record intent = new Record(original, current, target, false);
        if (!persist(intent)) return false;
        if (current == target) {
            persist(new Record(original, target, NONE, false));
            return true;
        }
        boolean written = device.write(target);
        int after = device.read();
        if (after == target) {
            // If this commit fails the durable pending target still proves ownership.
            persist(new Record(original, target, NONE, false));
            return written;
        }
        if (after == current) {
            // A rejected write must not replace the original baseline with a rule.
            persist(new Record(original, current, NONE, false));
        } else if (after > 0) {
            abandon();
        }
        return false;
    }

    /** endSession=false skips a rule temporarily (window override/unknown/manual
     * mode), preserving a slider-change veto across configured-app transitions. */
    boolean release(boolean endSession) {
        if (record != null && !suppressed) {
            int current = device.read();
            if (current <= 0) return false; // unreadable is not a safe restoration level
            if (device.manual() && record.owns(current) && current != record.original) {
                if (!device.write(record.original) || device.read() != record.original) return false;
            } else if (device.manual() && current != record.original && !record.owns(current)) {
                abandon();
            }
        }
        if (suppressed && !endSession) return false;
        if (!store.save(null)) return false;
        record = null;
        if (endSession) suppressed = false;
        return true;
    }

    private boolean persist(Record next) {
        if (!store.save(next)) return false;
        record = next;
        return true;
    }

    private void abandon() {
        suppressed = true;
        if (record != null) {
            Record next = new Record(record.original, record.applied, record.pending, true);
            // Remember the veto in memory even if the disk commit is rejected. On
            // restart an unmatched current value also vetoes the old ownership.
            store.save(next);
            record = next;
        }
    }
}
