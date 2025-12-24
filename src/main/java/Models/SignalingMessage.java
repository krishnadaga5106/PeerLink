package Models;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class SignalingMessage {
    private String roomCode;
    private String username;
    private MessageType messageType;
    private String message;

    public SignalingMessage(MessageType messageType, String message) {
        this.messageType = messageType;
        this.message = message;
    }

    public SignalingMessage(MessageType messageType) {
        this.messageType = messageType;
    }

}
