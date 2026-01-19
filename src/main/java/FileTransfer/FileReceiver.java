package FileTransfer;

import Core.AppState;
import Interfaces.EventListener;
import Interfaces.DataHandler;
import Interfaces.FileTransfer;
import WebRTC.P2PWebRTC;
import dev.onvoid.webrtc.RTCDataChannelBuffer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

import java.io.*;
import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;

@Slf4j
@RequiredArgsConstructor
public class FileReceiver implements DataHandler, FileTransfer {
    private final int CHUNK_SIZE = 16 * 1024;
    
    private final P2PWebRTC webRTC;
    private final EventListener listener;

    private String dir;

    private long currentFileSize;
    private long totalBytesReceived;
    private String fileName;
    private RandomAccessFile fos;
    private boolean isReceivingFile;
    private boolean isPaused = false;
    private boolean selfOriginatedPause;

    private boolean userAccepted = false;
    private boolean retry = false;
    private CountDownLatch latch;

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
                renameFile();
                isReceivingFile = false;
                write("File Downloaded.");
            }
        }
        catch(IOException e){
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
        }
        else if(msg.startsWith("FILE_REQ")){
            confirmFiles(msg);
            //then wait for the user ack, then proceed from the userAccept method
        }
        else if(msg.startsWith("FILE_COMPLETE")){
            onComplete(msg);
        }
        else if (msg.equals("FILE_PAUSE")) {
            pause(false);
        }
        else if (msg.equals("FILE_RESUME")) {
            resume(false);
        }
    }

    void prepareForTransfer(String msg) throws FileNotFoundException {
        String[] parts = msg.split("::");

        this.currentFileSize = Long.parseLong(parts[2]);
        this.fileName = parts[1];
        this.totalBytesReceived = 0;

        //parse the file name to search for fileName.part
        long existingLen = checkForFile();


        //file is already present with ext
        if(existingLen == -1){
            sendFileStart(fileName, existingLen);
            return;
        }
        this.fos = new RandomAccessFile(dir + fileName + ".part", "rw");
        this.isReceivingFile = true;

        try{
            fos.seek(existingLen);
            totalBytesReceived = existingLen;
        }catch (IOException e){
            log.error(e.getMessage());
        }
        write("Starting Transfer: " + fileName);
        sendFileStart(fileName, existingLen);
    }

    @SuppressWarnings("ResultOfMethodCallIgnored")
    private long checkForFile(){
        //check for both fileName and fileName.part
        File target = new File(dir + fileName);
        //means that the current file was already transferred
        if(target.exists()) return -1;
        
        //check if the file alr exists?
        target = new File(dir + fileName + ".part");
        
        //if the file doesn't exist, send the starting point as the length of the file
        if(!target.exists())
            return 0;

        //if the existing file has a size > actual size
        if(target.length() > currentFileSize){
            target.delete();
            return 0;
        }
        //divide the curr len into the chunk size and get the floor, then multiply it by chunk size to get the last incomplete chunk
        return (Math.floorDiv(target.length(), (CHUNK_SIZE)) * (CHUNK_SIZE));
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
            latch = new CountDownLatch(1);
            try {
                latch.await();
            }
            catch (InterruptedException e){
                log.error(e.getMessage());
            }
            if (retry) getDir();
        }
    }

    void confirmFiles(String msg){
//        write("\033[H\033[2J");
        write("Received request to transfer following files:");
        //split the req string into "REQ::fileNames::totalSize"
        String[] msgComponents = msg.split("::");
        //split the individual file
        String[] fileNames = msgComponents[1].split("\\|");
        System.out.println("\r");
        for(String fileName : fileNames){
            System.out.println(fileName);
        }
        System.out.println("\r");
        write("Total transfer size: " + msgComponents[2] + "KB");

        write("Accept Files?" + "\n" +
                "/accept to accept, /deny to deny: ");

        //go to the controller now
        listener.onReviewing();

        //set a new latch here
        latch = new CountDownLatch(1);
        try {
            latch.await();
        }
        catch (InterruptedException e){
            log.error(e.getMessage());
        }

        if(userAccepted){
            //get the directory first to save the files
            getDir();
            if(dir != null){
                listener.setAppState(AppState.TRANSFERRING);
                sendACK(true);
            }else{
                //maybe write canceling request
                write("Rejecting transfer request.");
                listener.setAppState(AppState.CHATTING);
                sendACK(false);
            }
        }
        else {
            //maybe write canceling request
            write("Rejecting transfer request.");
            listener.setAppState(AppState.CHATTING);
            sendACK(false);
        }
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
        this.userAccepted = positive;
        latch.countDown();
    }

    private void onComplete(String msg){
        String[] parts = msg.split("::");
        boolean success = parts[2].equals("TRUE");

        listener.onFileTransferComplete(success);
    }

    private void write(String string){
        System.out.println("\r[SYSTEM]: " + string);
        System.out.print("\r> ");
    }

    public void onDir(boolean retry) {
        this.retry = retry;
        this.latch.countDown();
    }

    @SuppressWarnings("ResultOfMethodCallIgnored")
    private void renameFile() {
        //currently the file has ext .part
        File target = new File(dir + fileName + ".part");
        target.renameTo(new File(dir + fileName));
    }

    @Override
    public void pause(boolean selfOriginated) {
        if(isPaused) {
            write("File transfer already paused.");
            return;
        }
        try {
            if (selfOriginated)
                webRTC.send(ByteBuffer.wrap("FILE_PAUSE".getBytes()), false);
        }
        catch (Exception e){
            log.error(e.getMessage());
        }
        finally {
            if(!selfOriginated)
                write("File transfer paused by other peer.");
            selfOriginatedPause = selfOriginated;
            isPaused = true;
            listener.onPause(true);
        }
    }

    @Override
    public void resume(boolean selfOriginated) {
        if(!isPaused){
            write("File transfer not paused.");
            return;
        }
        try {
            if (selfOriginated && selfOriginatedPause) {
                isPaused = false;
                webRTC.send(ByteBuffer.wrap("FILE_RESUME".getBytes()), false);
            }
            else if (selfOriginated)
                write("Cannot resume file transfer, other peer paused it.");
        }
        catch (Exception e){
            log.error(e.getMessage());
        }
        finally {
            if (!selfOriginated) {
                write("File transfer resumed by other peer.");
                isPaused = false;
                listener.onPause(false);
            }
        }
    }

    private void sendFileStart(String fileName, long startPos) {
        String header = "FILE_START::" + fileName + "::" + startPos;
        try {
            webRTC.send(ByteBuffer.wrap(header.getBytes()), false);
        }
        catch (Exception e){
            log.error(e.getMessage());
        }
    }
}
