package org.andr36oid.usbmode;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

final class TextFiles {
    static String request(String command) throws IOException {
        try (LocalSocket socket = new LocalSocket(LocalSocket.SOCKET_SEQPACKET)) {
            socket.connect(new LocalSocketAddress("andr36oid-controller-files")); socket.setSoTimeout(3000);
            socket.getOutputStream().write(command.getBytes(StandardCharsets.UTF_8));
            final byte[] bytes = new byte[65538];
            final int count = socket.getInputStream().read(bytes);
            if (count < 1 || count > 65537 || bytes[0] != 'O') throw new IOException("Text file");
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes,1,count - 1)).toString();
        }
    }
}
