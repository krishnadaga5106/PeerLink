package FileTransfer;

import Interfaces.MessageHandler;
import dev.onvoid.webrtc.RTCDataChannelBuffer;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class SenderMessageHandler implements MessageHandler {
    private final FileSender fileSender;

    @Override
    public void handle(RTCDataChannelBuffer buffer) {
        if(!buffer.binary){
            byte[] bytes = new byte[buffer.data.remaining()];
            buffer.data.get(bytes);
            fileSender.onACK(new String(bytes));
        }
    }
}
