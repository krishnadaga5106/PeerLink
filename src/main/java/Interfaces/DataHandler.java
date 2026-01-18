package Interfaces;

import dev.onvoid.webrtc.RTCDataChannelBuffer;

public interface DataHandler {
    void handleBin(RTCDataChannelBuffer buffer);
    void handleText(String msg);
}
