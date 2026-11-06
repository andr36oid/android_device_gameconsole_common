package org.andr36oid.cpuoverclock;
import java.io.IOException;
import java.util.*;
public class StockClockSessionTest {
    static int checks;
    static void check(boolean yes,String why) {checks++;if(!yes)throw new AssertionError(why);}
    static class Io implements StockClockSession.Io {
        Map<String,String> values=new HashMap<>();List<String>writes=new ArrayList<>();int failAt=-1;
        Io() {
            values.put("/sys/devices/system/cpu/cpufreq/boost","0");
            for(int i=0;i<3;i++) {
                String d=StockClockSession.DEVICES[i];
                values.put(d+(i==0?"scaling_available_frequencies":"available_frequencies"),i==0?"408000 600000 1008000 1296000 1512000":i==1?"400000000 520000000 600000000":"528000000 666000000 786000000 1040000000");
                values.put(d+(i==0?"scaling_min_freq":"min_freq"),i==0?"600000":"0");
                values.put(d+(i==0?"scaling_max_freq":"max_freq"),i==0?"1008000":i==1?"520000000":"786000000");
                values.put(d+(i==0?"scaling_governor":"governor"),i==0?"schedutil":"simple_ondemand");
            }
        }
        public String read(String path)throws IOException {if(!values.containsKey(path))throw new IOException(path);return values.get(path);}
        public void write(String path,String value)throws IOException {
            check(path.endsWith("min_freq")||path.endsWith("max_freq"),"only limit writes: "+path);
            int i=path.contains("policy0")?0:path.contains("ff400000")?1:2;
            check(Long.parseLong(value)<=StockClockSession.LIMITS[i],"no overclock value");
            writes.add(path+"="+value);if(writes.size()==failAt)throw new IOException("injected");
            values.put(path,value);
        }
    }
    static class Journal implements StockClockSession.Journal {
        StockClockSession.Snapshot saved; boolean fail;
        public StockClockSession.Snapshot load(){return saved;}
        public boolean save(StockClockSession.Snapshot next){if(fail)return false;saved=next;return true;}
    }
    public static void main(String[]args) {
        Io io=new Io();Journal j=new Journal();Map<String,String> original=new HashMap<>(io.values);
        StockClockSession s=new StockClockSession(io,j,"boot-a");
        check(s.start()&&s.pinned(),"pins all stock clocks");
        check(io.values.get(StockClockSession.CPU+"scaling_min_freq").equals("1296000"),"CPU exact max");
        check(io.values.get(StockClockSession.GPU+"min_freq").equals("520000000"),"GPU exact max");
        int n=io.writes.size();check(s.start()&&io.writes.size()==n,"duplicate start idempotent");
        check(s.stop()&&io.values.equals(original)&&j.saved==null,"exact prior limits/governors restored");
        check(s.stop(),"duplicate stop");
        for(int fail=1;fail<=6;fail++) {
            io=new Io();j=new Journal();original=new HashMap<>(io.values);io.failAt=fail;
            s=new StockClockSession(io,j,"boot-a");check(!s.start(),"partial failure reported "+fail);
            check(io.values.equals(original),"partial admission rollback "+fail);
        }
        io=new Io();j=new Journal();s=new StockClockSession(io,j,"boot-a");j.fail=true;
        check(!s.start()&&io.writes.isEmpty(),"journal failure precedes all writes");
        io=new Io();j=new Journal();s=new StockClockSession(io,j,"boot-a");s.start();io.failAt=io.writes.size()+1;
        check(!s.stop()&&j.saved!=null,"failed restore retains journal");
        io.failAt=-1;s=new StockClockSession(io,j,"boot-a");check(s.stop()&&j.saved==null,"restart retries restore");
        io=new Io();j=new Journal();s=new StockClockSession(io,j,"boot-a");s.start();n=io.writes.size();
        s=new StockClockSession(io,j,"boot-b");check(!s.active()&&io.writes.size()==n,"old boot never replayed");
        io=new Io();j=new Journal();io.values.put("/sys/devices/system/cpu/cpufreq/boost","1");s=new StockClockSession(io,j,"boot-a");
        check(!s.start()&&io.writes.isEmpty(),"preexisting boost excluded without touching it");
        io=new Io();j=new Journal();io.values.put(StockClockSession.GPU+"max_freq","600000000");s=new StockClockSession(io,j,"boot-a");
        check(!s.start()&&io.writes.isEmpty(),"OC snapshot not admitted or restored");
        io=new Io();j=new Journal();io.values.put(StockClockSession.CPU+"scaling_available_frequencies","1512000");s=new StockClockSession(io,j,"boot-a");
        check(!s.start()&&io.writes.isEmpty(),"never selects boost-only table");
        System.out.println("StockClockSessionTest: "+checks+" checks passed; all writes stubbed, only stock limits permitted");
    }
}
