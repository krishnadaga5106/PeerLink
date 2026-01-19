package FileTransfer;

import Interfaces.EventListener;
import Interfaces.FileTransfer;
import WebRTC.P2PWebRTC;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;

@Slf4j
@RequiredArgsConstructor
public class FileSender implements FileTransfer {
    private final int CHUNK_SIZE = 16 * 1024;
    private final P2PWebRTC webRTC;
    private final EventListener listener;
    private CountDownLatch latch;
    private CountDownLatch pauseLatch = new CountDownLatch(0);
    private boolean selfOriginatedPause;
    private boolean isPaused = false;

    private String ackMsg;
    private long fileStarPos;

    public void start() throws Exception {
        /*
        get files / folders
        get ACK from the receiver
        For each file, break into chunk and start transfer
        update the main thread regarding the progress
        */

        //Step-1
        //get the List of selected files
        ArrayList<File> files = getFiles();
        //if files are null, reprompt and try to get files or EXIT
        if(files == null){
            write("No files selected, exiting...");
            onComplete(false);
            return;
        }

        //FOR NOW JUST TO MAKE IT SIMPLER, GET ACK REGARDING THE WHOLE TRANSFER CONTENT, not ACK for individual file
        //TODO: get ACK for individual file
        long sizeInBytes = 0L;
        StringBuilder fileNames = new StringBuilder();
        for(File file : files){
            sizeInBytes += file.length();
            fileNames.append(file.getName()).append("|");
        }
        //convert to KB
        double sizeInKB = (double) sizeInBytes / 1024;
        String META_DATA = "FILE_REQ::" + fileNames + "::" + sizeInKB;
        ByteBuffer buffer = ByteBuffer.wrap(META_DATA.getBytes());

        //send transfer Req
        webRTC.send(buffer, false);
        write("Waiting for ACK...");

        //wait for ACK
        latch = new CountDownLatch(1);
        latch.await();

        if(ackMsg.startsWith("NACK")){
            write("Peer rejected the transfer request, exiting...");
            onComplete(false);
            return;
        }
        write("Starting file transfer.");
        //For now the ACK is simple but in the future, parse the ACK and send only the acknowledged files
        //using ackMSG

        for(File file : files){
            sendFile(file);
        }
        log.info("Files sent successfully");
        onComplete(true);
    }

    private void sendFile(File file) throws Exception {
        //send the file starting header
        String header = "FILE_START::" + file.getName() + "::" + file.length();
        webRTC.send(ByteBuffer.wrap(header.getBytes()), false);

        //wait for the response of the receiver, then send from the specified position of the file.
        latch = new CountDownLatch(1);
        latch.await();

        //means that the receiver already has the file, Skip this file
        if(fileStarPos == -1) return;

        //little wait for the receiver to prepare for transfer
        Thread.sleep(50);
        try(RandomAccessFile fis = new RandomAccessFile(file, "r")){
            //seek to the length told by receiver
            fis.seek(fileStarPos);

            byte[] chunk = new byte[CHUNK_SIZE];
            //no of bytes read, will tell about the end of file
            int bytesRead;
            while((bytesRead = fis.read(chunk)) != -1){

                //is the app paused?
                if(pauseLatch.getCount() > 0) pauseLatch.await();

                //if the buffered amount is > then 64KB wait for the buffer to clear
                while(webRTC.getDataChannel().getBufferedAmount() > 64 * 1024){
                    Thread.sleep(5);
                }

                //convert the chunk to exact size
                byte[] exactSizeChunk = new byte[bytesRead];
                System.arraycopy(chunk, 0, exactSizeChunk, 0, bytesRead);

                //wrap the buffer till only the useful data, i.e. no if bytes read
                webRTC.send(ByteBuffer.wrap(exactSizeChunk), true);
            }
        }
        //small pause between the files so that receiver does not overwhelms and is able to process the files
        Thread.sleep(5);
    }

    private ArrayList<File> getFiles(){
        write("Select Files:");
        String paths = TinyFileDialogs.tinyfd_openFileDialog(
                "Select Files",
                "",
                null,
                "Select Files",
                true
        );

        //process the Input
        if(paths == null) return null;

        String[] filePaths = paths.split("\\|");

        //get the files from the paths
        ArrayList<File> files = new ArrayList<>();
        for(String path : filePaths){
            files.add(new File(path));
        }
        return files;
    }

    public void onACK(String ackMsg){
        this.ackMsg = ackMsg;
        latch.countDown();
    }

    public void onFileStart(String msg){
        //start pos -1 then skip file
        String[] parts = msg.split("::");
        //FILE_START::FileName::StartPos
        fileStarPos = Long.parseLong(parts[2]);
        latch.countDown();
    }

    private void write(String string){
        System.out.println("\r[SYSTEM]: " + string);
        System.out.print("\r> ");
    }
    
    private void onComplete(boolean successful) throws Exception {
        String msg = successful ? "FILE_COMPLETE::SUCCESS::TRUE" : "FILE_COMPLETE::SUCCESS::FALSE";
        webRTC.send(ByteBuffer.wrap(msg.getBytes()), false);
        listener.onFileTransferComplete(successful);
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
            pauseLatch = new CountDownLatch(1);
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
                listener.onPause(false);
                pauseLatch.countDown();
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
                pauseLatch.countDown();
            }
        }
    }
}
