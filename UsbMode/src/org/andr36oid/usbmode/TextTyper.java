package org.andr36oid.usbmode;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class TextTyper {
    static final class Stroke {
        final int usage, modifiers;
        Stroke(int usage, int modifiers) { this.usage = usage; this.modifiers = modifiers; }
    }
    static final class Plan {
        final List<Stroke> keys = new ArrayList<>();
        final List<Integer> positions = new ArrayList<>();
        final String unsupported;
        final int characters;
        Plan(String text, String layout) {
            final Map<Integer,Stroke[]> map = mapping(layout);
            final LinkedHashSet<Integer> skipped = new LinkedHashSet<>();
            final int[] points = text.replace("\r\n","\n").replace('\r','\n').codePoints().toArray();
            characters = points.length;
            for (int i = 0; i < points.length; i++) {
                final Stroke[] strokes = map.get(points[i]);
                if (strokes == null) { skipped.add(points[i]); continue; }
                for (Stroke key : strokes) { keys.add(key); positions.add(i + 1); }
            }
            final StringBuilder names = new StringBuilder();
            for (int point : skipped) {
                if (names.length() != 0) names.append("  ");
                names.append(String.format("U+%04X",point));
                if (!Character.isISOControl(point)) names.append(" (").appendCodePoint(point).append(")");
            }
            unsupported = names.length() == 0 ? "—" : names.toString();
        }
    }
    private Plan mPlan;
    private int mIndex, mProgress, mInterval;
    private long mStart, mNext;
    private boolean mDown;
    boolean active() { return mPlan != null; }
    int progress() { return mProgress; }
    int total() { return mPlan == null ? mProgress : mPlan.characters; }
    int countdown(long time) { return mPlan == null ? 0 : (int) Math.max(0,(mStart - time + 999) / 1000); }
    void start(Plan plan, int speed, long time) {
        mPlan = plan; mIndex = mProgress = 0; mDown = false;
        mInterval = speed == 0 ? 100 : speed == 2 ? 10 : 25;
        mStart = mNext = time + 3000;
    }
    void abort() { mPlan = null; mDown = false; }
    byte[] update(long time, boolean abort) {
        final byte[] packet = new byte[16]; packet[0] = 1; packet[11] = 1;
        if (abort) { abort(); packet[12] = 1; return packet; }
        if (mPlan == null || time < mStart) return packet;
        if (time >= mNext) {
            if (mDown) { mDown = false; mProgress = mPlan.positions.get(mIndex); mIndex++; }
            else if (mIndex < mPlan.keys.size()) mDown = true;
            if (mIndex == mPlan.keys.size()) { mProgress = mPlan.characters; abort(); return packet; }
            mNext = time + mInterval;
        }
        if (mDown) { final Stroke key = mPlan.keys.get(mIndex); packet[1] = (byte) key.modifiers; packet[2] = (byte) key.usage; }
        return packet;
    }
    private static void put(Map<Integer,Stroke[]> map, int point, int usage, int modifiers) {
        map.put(point,new Stroke[] {new Stroke(usage,modifiers)});
    }
    private static void pair(Map<Integer,Stroke[]> map, int usage, String caps) {
        final int[] chars = caps.codePoints().toArray();
        for (int i = 0; i < chars.length; i++) put(map,chars[i],usage,i == 0 ? 0 : i == 1 ? 2 : 64);
    }
    private static void dead(Map<Integer,Stroke[]> map, int point, int usage, int modifiers) {
        map.put(point,new Stroke[] {new Stroke(usage,modifiers),new Stroke(0x2c,0)});
    }
    static Map<Integer,Stroke[]> mapping(String layout) {
        final Map<Integer,Stroke[]> map = new HashMap<>();
        final KeyboardModel labels = new KeyboardModel(layout);
        for (KeyboardModel.Key[] row : KeyboardModel.ROWS) for (KeyboardModel.Key key : row) {
            final String cap = labels.label(key);
            if (!("fr".equals(layout) && key.usage == 0x34) && cap.length() == 1 && Character.isLetter(cap.charAt(0)) && cap.charAt(0) != 'ß') {
                put(map,Character.toLowerCase(cap.charAt(0)),key.usage,0);
                put(map,Character.toUpperCase(cap.charAt(0)),key.usage,2);
            }
        }
        put(map,'\n',0x28,0); put(map,'\t',0x2b,0); put(map,' ',0x2c,0);
        if ("de".equals(layout)) {
            final String[] numbers = {"1!","2\"","3§","4$","5%","6&","7/{","8([","9)]","0=}"};
            for (int i = 0; i < 10; i++) pair(map,0x1e + i,numbers[i]);
            pair(map,0x2d,"ß?\\"); pair(map,0x30,"+*~"); pair(map,0x32,"#'");
            pair(map,0x36,",;"); pair(map,0x37,".:"); pair(map,0x38,"-_"); pair(map,0x64,"<>|");
            put(map,'@',0x14,64); put(map,'€',0x08,64); put(map,'µ',0x10,64); put(map,'°',0x35,2);
            dead(map,'^',0x35,0); dead(map,'´',0x2e,0); dead(map,'`',0x2e,2);
        } else if ("fr".equals(layout)) {
            final String[] numbers = {"&1","é2~","\"3#","'4{","(5[","-6|","è7`","_8\\","ç9","à0@"};
            for (int i = 0; i < 10; i++) pair(map,0x1e + i,numbers[i]);
            pair(map,0x2d,")°]"); pair(map,0x2e,"=+}"); pair(map,0x30,"$£¤");
            pair(map,0x32,"*µ"); pair(map,0x34,"ù%"); pair(map,0x35,"²"); pair(map,0x10,",?");
            pair(map,0x36,";."); pair(map,0x37,":/"); pair(map,0x38,"!§"); pair(map,0x64,"<>");
            put(map,'€',0x08,64); dead(map,'^',0x2f,0); dead(map,'¨',0x2f,2);
            dead(map,'~',0x1f,64); dead(map,'`',0x24,64);
        } else {
            final String shifted = "!@#$%^&*()";
            for (int i = 0; i < 10; i++) { put(map,'0' + (i + 1) % 10,0x1e + i,0); put(map,shifted.charAt(i),0x1e + i,2); }
            pair(map,0x2d,"-_"); pair(map,0x2e,"=+"); pair(map,0x2f,"[{"); pair(map,0x30,"]}");
            pair(map,0x31,"\\|"); pair(map,0x33,";:"); pair(map,0x34,"'\""); pair(map,0x35,"`~");
            pair(map,0x36,",<"); pair(map,0x37,".>"); pair(map,0x38,"/?");
        }
        return map;
    }
}
