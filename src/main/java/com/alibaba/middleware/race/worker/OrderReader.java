package com.alibaba.middleware.race.worker;

import com.alibaba.middleware.race.index.RecordIndex;
import com.alibaba.middleware.race.table.OrderLine;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

public class OrderReader implements Runnable {
    private ArrayList<LinkedBlockingQueue<OrderLine>> outs;
    private HashMap<String, Byte> files;
    private long threadId;

    public OrderReader(HashMap<String, Byte> files, ArrayList<LinkedBlockingQueue<OrderLine>> outs) {
        this.outs = outs;
        this.files = files;
    }

    @Override
    public void run() {
        this.threadId = Thread.currentThread().getId();
        int step = (int) (threadId % 3) + 1;
        int i;
        int outSize = outs.size();
        long lineCount = 0;
        try {
            for (Map.Entry<String, Byte> entry : this.files.entrySet()) {
                FileChannel fileChannel = new RandomAccessFile(entry.getKey(), "r").getChannel();
                long fileSize = fileChannel.size();
                byte fileId = entry.getValue();
                int mapTime = (int)((fileSize-1)>>30)+1;
                for (int j=0; j < mapTime; j++) {
                    long mapSize = Math.min((1<<30), fileSize-(j*(1<<30)));
                    long mapBegin = j*(1<<30);
                    MappedByteBuffer buffer = fileChannel.map(FileChannel.MapMode.READ_ONLY, mapBegin, mapSize);
                    byte[] strBuffer = new byte[1024];
                    int posInStrBuffer = 0;
                    byte ch;
                    String line;
                    for (int posInMap = 0; posInMap < mapSize; posInMap++) {
                        ch = buffer.get();
                        if (ch == '\n') {
                            line = new String(strBuffer, 0, posInStrBuffer);
                            OrderLine orderLine = new OrderLine(new RecordIndex(fileId, mapBegin+posInMap-posInStrBuffer), line);
                            posInStrBuffer=0;
                            while (true) {
                                try {
                                    for (i = 1; i < 16; i++) {
                                        if (outs.get((int) (lineCount + i * step) % outSize)
                                                .offer(orderLine, 420, TimeUnit.MICROSECONDS)) {
                                            break;
                                        }
                                    }
                                    if (i == 16) {
                                        outs.get((int) (lineCount + threadId) % outSize).put(orderLine);
                                    }
                                    break;
                                } catch (InterruptedException e) {
                                    e.printStackTrace();
                                }
                            }
                            lineCount++;
                        } else {
                            strBuffer[posInStrBuffer++] = ch;
                        }
                    }
                }
                fileChannel.close();
            }
            System.out.println("INFO: Reader thread completed. lineCount:" + lineCount + " Thread id:" + threadId);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
