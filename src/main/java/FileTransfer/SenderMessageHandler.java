package FileTransfer;

import Interfaces.DataHandler;
import dev.onvoid.webrtc.RTCDataChannelBuffer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
public class SenderMessageHandler implements DataHandler {
    private final FileSender fileSender;

    @Override
    public void handleBin(RTCDataChannelBuffer buffer) {
        log.error("Received binary data as sender, ignoring.");
    }

    @Override
    public void handleText(String msg) {
        fileSender.onACK(msg);
    }
}
