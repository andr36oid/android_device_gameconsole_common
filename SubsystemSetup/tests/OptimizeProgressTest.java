package org.andr36oid.subsystemsetup;

import java.io.File;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.NodeList;

/** Real progress samples and the English resource formats, without Android. */
public final class OptimizeProgressTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        Map<String,String> strings=new HashMap<>();
        org.w3c.dom.Document doc=DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(new File(args[0]));
        NodeList nodes=doc.getElementsByTagName("string");
        for(int i=0;i<nodes.getLength();i++)strings.put(nodes.item(i).getAttributes()
                .getNamedItem("name").getNodeValue(),nodes.item(i).getTextContent());

        String[] phases={"preparing","building","verifying","publishing"};
        String[] names={"preparing","converting","verifying","saving"};
        String[] labels={"Preparing","Converting","Verifying","Saving"};
        for(int i=0;i<phases.length;i++) {
            equal(i+1,OptimizeProgress.step(phases[i]),"ordered step "+phases[i]);
            equal("Step "+(i+1)+" of 4: "+labels[i],String.format(Locale.US,strings.get("ss_opt_step"),
                    OptimizeProgress.step(phases[i]),strings.get("ss_opt_"+names[i])),"step label "+phases[i]);
            check(!strings.get("ss_opt_"+names[i]+"_description").isEmpty(),"step description "+phases[i]);
        }
        for(String phase:new String[]{"done","interrupted","failed",""})equal(0,OptimizeProgress.step(phase),"no numbered terminal step");
        equal("0:00",OptimizeProgress.elapsed(0),"initial elapsed");
        equal("1:23",OptimizeProgress.elapsed(83),"minutes and seconds");
        equal("60:01",OptimizeProgress.elapsed(3601),"long jobs keep elapsed minutes");
        equal("0:00",OptimizeProgress.elapsed(-2),"invalid elapsed");
        equal("Elapsed 1:23",String.format(Locale.US,strings.get("ss_opt_elapsed"),OptimizeProgress.elapsed(83)),"elapsed resource");
        equal("0.0 MB",OptimizeProgress.bytes(0,Locale.US),"zero bytes");
        equal("123.5 MB",OptimizeProgress.bytes(123_456_789,Locale.US),"megabytes one decimal");
        equal("1.0 GB",OptimizeProgress.bytes(1_000_000_000,Locale.US),"gigabyte boundary");
        equal("1,5 GB",OptimizeProgress.bytes(1_500_000_000,Locale.GERMANY),"locale decimal separator");
        equal("50% · 1.0 GB of 2.0 GB",String.format(Locale.US,strings.get("ss_opt_bytes"),
                OptimizeProgress.percent(1_000_000_000,2_000_000_000),OptimizeProgress.bytes(1_000_000_000,Locale.US),
                OptimizeProgress.bytes(2_000_000_000,Locale.US)),"visible percent and amount");
        equal(0,OptimizeProgress.percent(20,0),"unknown total");
        equal(0,OptimizeProgress.percent(-20,100),"bad bytes");
        equal(100,OptimizeProgress.percent(120,100),"upper bound exceeded");
        equal(100,OptimizeProgress.percent(Long.MAX_VALUE,Long.MAX_VALUE),"percent avoids overflow");

        OptimizeProgress progress=new OptimizeProgress();
        long mb=1024*1024;
        equal(-1,progress.remaining("a","preparing",0,400*mb,1),"preparing does not start rate timing");
        equal(-1,progress.remaining("a","building",100*mb,400*mb,100),"first building poll starts warmup even with prior bytes");
        equal(-1,progress.remaining("a","building",114*mb,400*mb,114),"fourteen seconds still estimates");
        equal(285,progress.remaining("a","building",115*mb,400*mb,115),"fifteen seconds uses building byte/time deltas, excludes preparation");
        equal(285,progress.remaining("a","building",115*mb,400*mb,115),"repeated poll does not bias smoothing");
        equal(284,progress.remaining("a","building",116*mb,400*mb,116),"steady rate countdown");
        long smoothed=progress.remaining("a","building",132*mb,400*mb,117);
        check(smoothed>240&&smoothed<250,"one output burst is smoothed, raw building ETA would be 143 seconds");
        equal(smoothed,progress.remaining("a","building",132*mb,400*mb,117),"burst repeated poll is inert");
        equal(-1,progress.remaining("b","building",132*mb,400*mb,117),"new job restarts settle window");
        equal(253,progress.remaining("b","building",147*mb,400*mb,132),"new job measures from its own baseline");
        equal(-1,progress.remaining("b","building",148*mb,400*mb,120),"elapsed rollback alone resets");
        equal(237,progress.remaining("b","building",163*mb,400*mb,135),"elapsed rollback gets new baseline");
        equal(-1,progress.remaining("b","building",100*mb,400*mb,136),"byte rollback alone resets");
        equal(285,progress.remaining("b","building",115*mb,400*mb,151),"byte rollback gets new baseline");
        equal(-1,progress.remaining("b","building",115*mb,200*mb,151),"changed estimate resets smoothing and settle");
        equal(70,progress.remaining("b","building",130*mb,200*mb,166),"changed estimate can settle again");
        for(String phase:new String[]{"preparing","verifying","publishing","done","failed","interrupted"}) {
            equal(-1,progress.remaining("b",phase,130*mb,200*mb,166),"no ETA outside building: "+phase);
            equal(-1,progress.remaining("b","building",130*mb,200*mb,200),"phase exit resets baseline: "+phase);
            equal(55,progress.remaining("b","building",145*mb,200*mb,215),"phase reentry settles: "+phase);
        }
        progress.reset();
        equal(-1,progress.remaining("c","building",0,40*mb,0),"zero elapsed and bytes establish building baseline");
        equal(-1,progress.remaining("c","building",4*mb-1,40*mb,15),"fifteen seconds with too little byte advance estimates");
        progress.reset();
        equal(-1,progress.remaining("c","building",0,40*mb,500),"long preparation is excluded");
        equal(135,progress.remaining("c","building",4*mb,40*mb,515),"exact four MiB and fifteen second thresholds");
        progress.reset();
        equal(-1,progress.remaining("c","building",0,0,0),"unknown total estimates");
        equal(-1,progress.remaining("c","building",15*mb,0,15),"unknown total never exposes ETA");
        equal(-1,progress.remaining("c","building",0,40*mb,16),"known total starts fresh settle");
        equal(-1,progress.remaining("c","building",0,40*mb,31),"stalled writer has no rate");
        equal(-1,progress.remaining("c","building",-1,40*mb,32),"negative bytes reset");
        equal(-1,progress.remaining("c","building",0,40*mb,-1),"negative elapsed resets");
        equal(-1,progress.remaining("c","building",0,40*mb,100),"invalid samples reset baseline");
        equal(0,progress.remaining("c","building",40*mb,40*mb,115),"no negative ETA at bound after settling");
        equal(0,progress.remaining("c","building",50*mb,40*mb,116),"no negative ETA beyond bound");
        progress.reset();
        equal(-1,progress.remaining("slow","building",0,76_127_449_918L,400),"large old denominator starts estimating");
        equal(-1,progress.remaining("slow","building",4*mb,76_127_449_918L,415),"absurd multi-day ETA stays estimating after warmup");
        check(progress.remaining("slow","building",2_000*mb,76_127_449_918L,800)>0,"faster output can settle below sanity cap");
        progress.reset();
        equal(-1,progress.remaining("cap","building",0,14_415*mb,0),"cap baseline");
        equal(14_400,progress.remaining("cap","building",15*mb,14_415*mb,15),"four hour sanity boundary");
        progress.reset();
        equal(-1,progress.remaining("cap","building",0,14_416*mb,0),"over-cap baseline");
        equal(-1,progress.remaining("cap","building",15*mb,14_416*mb,15),"over four hours stays estimating");
        equal(1,OptimizeProgress.minutes(60),"minute boundary");
        equal(1,OptimizeProgress.minutes(61),"no zero minutes");
        equal(2,OptimizeProgress.minutes(90),"sensible rounding");
        equal(3,OptimizeProgress.minutes(180),"three minute estimate");
        equal("Conversion: about 3 min left",String.format(Locale.US,pluralItem(doc,"ss_opt_eta_minutes","other"),
                OptimizeProgress.minutes(180)),"ETA resource (plural other)");
        equal("Conversion: about 1 min left",String.format(Locale.US,pluralItem(doc,"ss_opt_eta_minutes","one"),
                OptimizeProgress.minutes(60)),"ETA resource (plural one)");
        equal("Conversion: under a minute left",strings.get("ss_opt_eta_under_minute"),"short estimate resource");
        equal("Estimating.",strings.get("ss_opt_estimating"),"unknown estimate resource");
        System.out.println("OptimizeProgress: "+checks+" step, format and ETA checks passed");
    }

    private static String pluralItem(org.w3c.dom.Document doc,String name,String quantity) {
        NodeList plurals=doc.getElementsByTagName("plurals");
        for(int i=0;i<plurals.getLength();i++) {
            org.w3c.dom.Node p=plurals.item(i);
            if(!name.equals(p.getAttributes().getNamedItem("name").getNodeValue()))continue;
            NodeList items=p.getChildNodes();
            for(int j=0;j<items.getLength();j++) {
                org.w3c.dom.Node item=items.item(j);
                if(item.getNodeType()!=org.w3c.dom.Node.ELEMENT_NODE||!"item".equals(item.getNodeName()))continue;
                if(quantity.equals(item.getAttributes().getNamedItem("quantity").getNodeValue()))return item.getTextContent();
            }
        }
        throw new AssertionError("missing plural "+name+"/"+quantity);
    }

    private static void equal(Object expected,Object actual,String description) {
        check(expected.toString().equals(actual.toString()),description+": expected "+expected+", got "+actual);
    }

    private static void check(boolean pass,String description) {
        checks++;
        if(!pass)throw new AssertionError(description);
    }
}
