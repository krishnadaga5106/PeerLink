package FileTransfer;

import WebRTC.P2PWebRTC;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

import java.io.File;
import java.io.FileInputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;

@Slf4j
@RequiredArgsConstructor
public class FileSender {
    private final P2PWebRTC webRTC;
    private CountDownLatch latch;

    private String ackMsg;

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
        //if files is null reprompt and try to get files or EXIT

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
        String META_DATA = "REQ::" + fileNames + "::" + sizeInKB;
        ByteBuffer buffer = ByteBuffer.wrap(META_DATA.getBytes());

        //send transfer Req
        webRTC.send(buffer, false);
        System.out.println("Waiting for ACK...");

        //wait for ACK
        latch = new CountDownLatch(1);
        latch.await();

        if(ackMsg.startsWith("NACK")){
            System.out.println("Received Negative Acknowledgment");
            return;
        }

        //For now the ACK is simple but in the future, parse the ACK and send only the acknowledged files
        //using ackMSG

        for(File file : files){
            sendFile(file);
        }
        log.info("Files sent successfully");
    }

    private void sendFile(File file) throws Exception {
        //send the file starting header
        String header = "FILE_START::" + file.getName() + "::" + file.length();
        webRTC.send(ByteBuffer.wrap(header.getBytes()), false);
        //little wait for the receiver to prepare for transfer
        Thread.sleep(50);
        try(FileInputStream fis = new FileInputStream(file)){

            byte[] chunk = new byte[16 * 1024];
            //no of bytes read, will tell about the end of file
            int bytesRead;
            while((bytesRead = fis.read(chunk)) != -1){
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
        Thread.sleep(100);
    }


    private ArrayList<File> getFiles(){
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
        latch.countDown();
        this.ackMsg = ackMsg;
    }

}
