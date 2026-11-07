package com.hajala.app;

import java.util.List;

/**
 * Runs data-schema migrations one step at a time, safely.
 *
 * Rules that make it safe:
 *  - a snapshot is taken before every step; if the step throws, the snapshot is restored and the
 *    schema number is NOT advanced, so the user's old data is left exactly as it was;
 *  - the schema number advances after each successful step, so a crash resumes at the right step;
 *  - a step that was in progress when the process died is rolled back before anything else runs;
 *  - steps are only ever run forward; a newer stored schema (older app) is left untouched.
 *
 * Pure Java on purpose: the algorithm is unit-tested on a plain JVM (see scripts/test-core.sh).
 */
final class MigrationRunner<C> {
    interface Step<C> {
        void run(C ctx) throws Exception;
    }

    interface Meta {
        int schema();

        void setSchema(int v);

        String inProgress();

        void setInProgress(String tag);

        void setLastError(String error);
    }

    interface Guard {
        void snapshot(String tag) throws Exception;

        void restore(String tag) throws Exception;
    }

    static final class Result {
        final int from;
        final int to;
        final boolean ok;
        final String error;

        Result(int from, int to, boolean ok, String error) {
            this.from = from;
            this.to = to;
            this.ok = ok;
            this.error = error;
        }

        boolean migrated() {
            return ok && to > from;
        }
    }

    private MigrationRunner() {}

    /** [steps].get(i) upgrades schema (i + 1) to (i + 2); steps.size() must equal current - 1. */
    static <C> Result run(C ctx, Meta meta, Guard guard, List<Step<C>> steps, int current) {
        if (steps.size() != current - 1) {
            return new Result(meta.schema(), meta.schema(), false, "migration table does not match schema " + current);
        }
        String pending = meta.inProgress();
        if (pending != null && !pending.isEmpty()) {
            try {
                guard.restore(pending);
                meta.setInProgress("");
            } catch (Exception e) {
                return new Result(meta.schema(), meta.schema(), false, "rollback failed: " + e.getMessage());
            }
        }
        int start = Math.max(1, meta.schema());
        if (start >= current) return new Result(start, start, true, "");
        int v = start;
        while (v < current) {
            String tag = "pre-schema-" + v;
            try {
                guard.snapshot(tag);
            } catch (Exception e) {
                String msg = "snapshot failed: " + e.getMessage();
                meta.setLastError(msg);
                return new Result(start, v, false, msg);
            }
            meta.setInProgress(tag);
            try {
                steps.get(v - 1).run(ctx);
            } catch (Throwable t) {
                String msg = "step " + v + "->" + (v + 1) + " failed: " + t;
                try {
                    guard.restore(tag);
                    meta.setInProgress("");
                } catch (Exception re) {
                    msg += " (rollback also failed: " + re.getMessage() + ")";
                }
                meta.setLastError(msg);
                return new Result(start, v, false, msg);
            }
            v++;
            meta.setSchema(v);
            meta.setInProgress("");
            meta.setLastError("");
        }
        return new Result(start, v, true, "");
    }
}
