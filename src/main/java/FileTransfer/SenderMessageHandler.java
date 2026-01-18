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
        if(msg.startsWith("FILE_START")){
            fileSender.onFileStart(msg);
        }
        else if (msg.equals("FILE_PAUSE")) {
            fileSender.pause(false);
        }
        else if (msg.equals("FILE_RESUME")) {
            fileSender.resume(false);
        }
        else{
            fileSender.onACK(msg);
        }
    }
}
