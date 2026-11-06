package org.andr36oid.subsystemsetup;

import java.util.Locale;

/** Presentation math only; all job timing and byte counts come from the broker. */
final class OptimizeProgress {
    private static final long WARMUP_SECONDS=15,MIN_ADVANCE_BYTES=4*1024*1024,MAX_ETA_SECONDS=4*60*60;
    private String job="";
    private long lastElapsed=-1,lastBytes,lastTotal;
    private long buildingElapsed,buildingBytes;
    private double rate;

    static int step(String phase) {
        switch(phase) {
            case "preparing":return 1;
            case "building":return 2;
            case "verifying":return 3;
            case "publishing":return 4;
            default:return 0;
        }
    }

    static int percent(long bytes,long total) {
        return total<=0?0:(int)Math.max(0,Math.min(100,100d*bytes/total));
    }

    static String bytes(long bytes,Locale locale) {
        return bytes(bytes,locale,"GB","MB");
    }

    /** Units come from resources so they can track the locale's conventions. */
    static String bytes(long bytes,Locale locale,String gigabyteUnit,String megabyteUnit) {
        boolean gigabytes=bytes>=1_000_000_000L;
        return String.format(locale,"%.1f %s",Math.max(0,bytes)/(gigabytes?1_000_000_000d:1_000_000d),gigabytes?gigabyteUnit:megabyteUnit);
    }

    static String elapsed(long seconds) {
        seconds=Math.max(0,seconds);
        return String.format(Locale.ROOT,"%d:%02d",seconds/60,seconds%60);
    }

    static long minutes(long seconds) {
        return Math.max(1,Math.round(seconds/60d));
    }

    void reset() {job="";lastElapsed=-1;lastBytes=lastTotal=buildingElapsed=buildingBytes=0;rate=0;}

    /** Seconds left in conversion, or -1 until settled. Repeated polls are inert. */
    long remaining(String job,String phase,long bytes,long total,long elapsed) {
        if(!"building".equals(phase)||bytes<0||elapsed<0) {
            reset();return -1;
        }
        if(!this.job.equals(job)||elapsed<lastElapsed||bytes<lastBytes||total!=lastTotal)reset();
        if(lastElapsed<0) {
            buildingElapsed=elapsed;buildingBytes=bytes;
        } else if(elapsed>lastElapsed) {
            // Exclude preparation and any output already present at first poll.
            double measuredRate=(bytes-buildingBytes)/(double)(elapsed-buildingElapsed);
            if(rate==0)rate=measuredRate;
            else {
                // Eight-second exponential smoothing of the building-phase rate.
                double alpha=1-Math.exp(-(elapsed-lastElapsed)/8d);
                rate+=alpha*(measuredRate-rate);
            }
        }
        this.job=job;lastElapsed=elapsed;lastBytes=bytes;lastTotal=total;
        if(total<=0||elapsed-buildingElapsed<WARMUP_SECONDS||bytes-buildingBytes<MIN_ADVANCE_BYTES||rate<=0)return -1;
        // The broker total is an upper bound; never imply conversion finishes the job.
        double seconds=Math.ceil(Math.max(0d,(double)total-bytes)/rate);
        return seconds>MAX_ETA_SECONDS?-1:(long)seconds;
    }
}
