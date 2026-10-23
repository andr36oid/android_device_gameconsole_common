package org.andr36oid.usbmode;

import java.util.Map;
import java.util.Arrays;

public final class TextTyperTest {
    private static int checks;
    private static void check(boolean ok) { checks++; if (!ok) throw new AssertionError("Text typing " + checks); }
    private static void key(String layout, char character, int usage, int modifiers) {
        TextTyper.Stroke[] keys = TextTyper.mapping(layout).get((int) character);
        check(keys != null && keys[0].usage == usage && keys[0].modifiers == modifiers);
    }
    private static byte[] frame(int buttons) {
        byte[] f = new byte[16]; f[0] = f[13] = 1;
        f[1] = (byte) buttons; f[2] = (byte) (buttons >> 8); return f;
    }
    public static void main(String[] args) {
        key("us",'a',4,0); key("us",'A',4,2); key("us",'@',0x1f,2); key("us",'?',0x38,2);
        key("de",'z',0x1c,0); key("de",'Y',0x1d,2); key("de",'ü',0x2f,0); key("de",'Ö',0x33,2);
        key("de",'@',0x14,64); key("de",'€',0x08,64); key("de",'\\',0x2d,64);
        key("fr",'a',0x14,0); key("fr",'q',4,0); key("fr",'m',0x33,0); key("fr",'M',0x33,2);
        key("fr",'1',0x1e,2); key("fr",'é',0x1f,0); key("fr",'@',0x27,64); key("fr",'.',0x36,2);
        for (String layout : KeyboardModel.LAYOUTS) {
            for (char c = 'a'; c <= 'z'; c++) check(TextTyper.mapping(layout).containsKey((int)c));
            for (char c = '0'; c <= '9'; c++) check(TextTyper.mapping(layout).containsKey((int)c));
            key(layout,'\n',0x28,0); key(layout,'\t',0x2b,0);
            final TextTyper.Plan plan = new TextTyper.Plan("a\r\nb\tc🙂🙂中",layout);
            check(plan.characters == 8 && plan.keys.size() == 5);
            check(plan.unsupported.contains("U+1F642") && plan.unsupported.contains("U+4E2D"));
            check(plan.unsupported.indexOf("U+1F642") == plan.unsupported.lastIndexOf("U+1F642"));
        }
        check(!TextTyper.mapping("fr").containsKey((int)'Ù'));
        TextTyper.Plan dead = new TextTyper.Plan("^`~","de"); check(dead.keys.size() == 5);
        TextTyper typer = new TextTyper(); typer.start(new TextTyper.Plan("AA","us"),1,0);
        check(typer.countdown(0) == 3 && typer.countdown(2000) == 1);
        check(typer.update(2999,false)[2] == 0);
        check(typer.update(3000,false)[2] == 4 && typer.update(3001,false)[1] == 2);
        check(typer.update(3025,false)[2] == 0); check(typer.update(3050,false)[2] == 4);
        check(typer.update(3051,true)[2] == 0 && !typer.active());
        byte[] release = typer.update(9000,false); check(release[1] == 0 && release[2] == 0);
        for (int abort : new int[] {1 << 11, (1 << 4) | (1 << 5), 0}) {
            KeyboardModel model = new KeyboardModel("us"); model.typer.start(new TextTyper.Plan("A","us"),2,0);
            check(model.update(frame(0),3000)[2] == 4);
            byte[] f = frame(abort); if (abort == 0) f[14] = 1;
            release = model.update(f,3001);
            check(!model.typer.active() && release[12] == 1 && release[1] == 0 && release[2] == 0 && release[8] == 0);
            check(model.update(frame(1 << 11),3100)[2] == 0);
        }
        KeyboardModel caps = new KeyboardModel("us"); caps.typer.start(new TextTyper.Plan("a","us"),1,0);
        byte[] capsFrame = frame(0); capsFrame[12] = 2;
        check(caps.update(capsFrame,3000)[1] == 2);
        KeyboardModel model = new KeyboardModel("de"); model.typer.start(new TextTyper.Plan("a","de"),1,0);
        model.reset(); check(!model.typer.active());
        System.out.println(checks + " file typing checks: layout bytes, unsupported listing, countdown, key releases and immediate abort passed");
    }
}
