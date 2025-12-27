package FileTransfer;

import Interfaces.MessageHandler;
import WebRTC.P2PWebRTC;
import dev.onvoid.webrtc.RTCDataChannelBuffer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Scanner;
import java.util.concurrent.CountDownLatch;

@Slf4j
@RequiredArgsConstructor
public class FileReceiver implements MessageHandler {
    private final P2PWebRTC webRTC;
    private final Scanner scanner;
    private CountDownLatch latch;
    private String dir;

    private long currentFileSize;
    private long totalBytesReceived;
    private FileOutputStream fos;
    private boolean isReceivingFile;

    public void start() throws InterruptedException {
        //waiting for the transfer req
        latch = new CountDownLatch(1);
        latch.await();
    }

    @Override
    public void handle(RTCDataChannelBuffer buffer) {
        if(buffer.binary)
            handleBinary(buffer);
        else
            handleText(buffer);
    }

    void handleBinary(RTCDataChannelBuffer buffer) {
        if(fos == null || !isReceivingFile) {
            log.error("Something went wrong..");
            return;
        }

        byte[] data = new byte[buffer.data.remaining()];
        buffer.data.get(data);

        try{
            fos.write(data);
            totalBytesReceived += data.length;

            //check if the file is completed
            if (totalBytesReceived >= currentFileSize) {
                fos.close();
                isReceivingFile = false;
                System.out.println("File Downloaded.");
            }

        }catch(IOException e){
            log.error(e.getMessage());
        }

    }

    void handleText(RTCDataChannelBuffer buffer) {
        //prompt the user about the send req
        byte[] bytes = new byte[buffer.data.remaining()];
        buffer.data.get(bytes);
        String msg = new String(bytes);

        if(msg.startsWith("FILE_START")){
            try {
                prepareForTransfer(msg);
            } catch (FileNotFoundException e) {
                throw new RuntimeException(e);
            }
        }else if(msg.startsWith("REQ")){
            boolean ack = confirmFiles(msg);
            if(ack){
                //get the directory first to save the files
                getDir();
                if(dir != null){
                    sendACK(true);
                    latch.countDown();
                }else{
                    System.out.println("Sending negative ACK");
                    sendACK(false);
                }
            }
        }
    }

    void prepareForTransfer(String msg) throws FileNotFoundException {
        String[] parts = msg.split("::");

        this.currentFileSize = Long.parseLong(parts[2]);
        this.totalBytesReceived = 0;

        File target = new File(dir + parts[1]);
        this.fos = new FileOutputStream(target);
        this.isReceivingFile = true;

        System.out.println("Starting Transfer: " + parts[1]);
    }

    void getDir(){
        String path = TinyFileDialogs.tinyfd_selectFolderDialog(
                "Select a Folder",
                System.getProperty("user.home")
        );
        if(path != null){
            dir = path + "/";
        }else {
            System.out.println("No folder selected, Retry?(Y/N): ");
            String choice = scanner.nextLine();
            if(!choice.equalsIgnoreCase("N")){
                getDir();
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

    void sendACK(boolean positive){
        //TODO: for individual file Ack, develop a format ACK::FILE_NAMES
        String ack = positive ? "ACK" : "NACK";
        try {
            webRTC.send(ByteBuffer.wrap(ack.getBytes()), false);
        }catch (Exception e){
            log.error(e.getMessage());
        }
    }

    public FileReceiver(P2PWebRTC webRTC) {
        this.webRTC = webRTC;
        this.scanner = new Scanner(System.in);
    }

}
