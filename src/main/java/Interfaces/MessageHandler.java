package Interfaces;

import dev.onvoid.webrtc.RTCDataChannelBuffer;

public interface MessageHandler {
    void handle(RTCDataChannelBuffer buffer);
}
