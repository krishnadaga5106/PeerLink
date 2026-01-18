package Core;

import Interfaces.DataHandler;
import Interfaces.SystemHandler;
import dev.onvoid.webrtc.RTCDataChannelBuffer;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;

@RequiredArgsConstructor
public class MessageHandler {

    private final SystemHandler systemHandler;
    @Setter
    @Getter
    private DataHandler dataHandler;

    public void handle(RTCDataChannelBuffer buffer){
        if(!buffer.binary){
            //if the data is chat data?
            byte[] bytes = new byte[buffer.data.remaining()];
            buffer.data.get(bytes);
            String msg = new String(bytes);

            if(msg.startsWith("CHAT") || msg.startsWith("SYS"))
                systemHandler.handleMessage(msg);
            else
                dataHandler.handleText(msg);
        }
        else
            dataHandler.handleBin(buffer);
    }
}
