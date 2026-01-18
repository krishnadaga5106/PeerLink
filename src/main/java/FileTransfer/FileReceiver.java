package FileTransfer;

import Interfaces.EventListener;
import Interfaces.DataHandler;
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

@Slf4j
@RequiredArgsConstructor
public class FileReceiver implements DataHandler {
    private final P2PWebRTC webRTC;
    private final EventListener listener;

    private String dir;

    private long currentFileSize;
    private long totalBytesReceived;
    private FileOutputStream fos;
    private boolean isReceivingFile;

    public void start() {
//        write("\033[H\033[2J");
        //waiting for the transfer req
        write("Waiting for sender to start sending files...");
    }


    @Override
    public void handleBin(RTCDataChannelBuffer buffer) {
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
                write("File Downloaded.");
            }
        }catch(IOException e){
            log.error(e.getMessage());
        }
    }

    @Override
    public void handleText(String msg) {
        //prompt the user about the send req

        if(msg.startsWith("FILE_START")){
            try {
                prepareForTransfer(msg);
            } catch (FileNotFoundException e) {
                throw new RuntimeException(e);
            }
        }else if(msg.startsWith("REQ")){
            confirmFiles(msg);
            //then wait for the user ack, then proceed from the userAccept method
        }else if(msg.equals("COMPLETE")){
            onComplete();
        }
    }

    void prepareForTransfer(String msg) throws FileNotFoundException {
        String[] parts = msg.split("::");

        this.currentFileSize = Long.parseLong(parts[2]);
        this.totalBytesReceived = 0;

        File target = new File(dir + parts[1]);
        this.fos = new FileOutputStream(target);
        this.isReceivingFile = true;

        write("Starting Transfer: " + parts[1]);
    }

    void getDir(){
        String path = TinyFileDialogs.tinyfd_selectFolderDialog(
                "Select a Folder",
                System.getProperty("user.home")
        );
        if(path != null){
            dir = path + "/";
        }else {
            write("No folder selected?" + "\n" +
                    "/retry to Retry, /cancel to cancel: ");

            listener.onGettingDir();
            //wait for the main thread to notify about the input
            try{
                wait();
            }catch (InterruptedException e){
                log.error(e.getMessage());
            }
        }
    }

    void confirmFiles(String msg){
//        write("\033[H\033[2J");
        write("Received request to transfer following files:");
        //split the req string into "REQ::fileNames::totalSize"
        String[] msgComponents = msg.split("::");
        //split the individual file
        String[] fileNames = msgComponents[1].split("\\|");
        System.out.println();
        for(String fileName : fileNames){
            System.out.println(fileName);
        }
        System.out.println();
        write("Total transfer size: " + msgComponents[2] + "KB");

        write("Accept Files?" + "\n" +
                "/accept to accept, /deny to deny: ");

        //go to the controller now
        listener.onReviewing();
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

    public void userAccept(boolean positive){
        if(positive){
            //get the directory first to save the files
            getDir();
            if(dir != null){
                sendACK(true);
            }else{
                //maybe write canceling request
                write("Sending negative ACK");
                sendACK(false);
            }
        }
    }

    private void onComplete(){
        listener.onFileTransferComplete();
    }

    private void write(String string){
        System.out.println("\r[SYSTEM]: " + string);
        System.out.print("\r> ");
    }

    public void onDir(boolean retry) {
        if(retry){
            getDir();
        }
    }
}
