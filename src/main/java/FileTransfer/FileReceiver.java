package FileTransfer;

import Interfaces.MessageHandler;
import WebRTC.P2PWebRTC;
import dev.onvoid.webrtc.RTCDataChannelBuffer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.sql.SQLOutput;
import java.util.Scanner;
import java.util.concurrent.CountDownLatch;

@Slf4j
@RequiredArgsConstructor
public class FileReceiver implements MessageHandler {
    private final P2PWebRTC webRTC;
    private final Scanner scanner;
    private CountDownLatch latch;

    public void start() throws InterruptedException {
        //waiting for the transfer req
        latch = new CountDownLatch(1);
        latch.await();

        //start for preparing the file receiving

    }

    @Override
    public void handle(RTCDataChannelBuffer buffer) {
        if(!buffer.binary){
            //prompt the user about the send req
            byte[] bytes = new byte[buffer.data.remaining()];
            buffer.data.get(bytes);
            String msg = new String(bytes);
            if(msg.startsWith("REQ")){
                boolean ack = confirmFiles(msg);

                if(ack){
                    sendACK();
                    latch.countDown();
                }
            }
        }
    }

    boolean confirmFiles(String msg){
        System.out.println("Received request to transfer following files:");
        //split the req string into "REQ::fileNames::totalSize"
        String[] msgComponents = msg.split("::");
        //split the individual file
        String[] fileNames = msgComponents[1].split("\\|");
        System.out.println();
        for(String fileName : fileNames){
            System.out.println(fileName);
        }
        System.out.println();
        System.out.println("Total transfer size: " + msgComponents[2] + "KB");

        System.out.print("Accept Files(Y/N): ");
        String resp = scanner.nextLine().trim();

        return !(resp.equals("N") || resp.equals("n"));
    }

    void sendACK(){
        //TODO: for individual file Ack, develop a format ACK::FILE_NAMES
        String ack = "ACK";
        try {
            webRTC.send(ByteBuffer.wrap(ack.getBytes()), false);
        }catch (Exception e){
            log.error(e.getMessage());
            e.printStackTrace();
        }
    }

    public FileReceiver(P2PWebRTC webRTC) {
        this.webRTC = webRTC;
        this.scanner = new Scanner(System.in);
    }

}
