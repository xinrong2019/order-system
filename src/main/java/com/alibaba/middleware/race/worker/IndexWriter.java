package com.alibaba.middleware.race.worker;

import com.alibaba.middleware.race.index.HashIndex;
import com.alibaba.middleware.race.index.RowIndex;
import com.alibaba.middleware.race.store.Data;
import com.alibaba.middleware.race.store.PageStore;

import java.util.concurrent.LinkedBlockingQueue;

public class IndexWriter<T extends RowIndex> implements Runnable {

    private LinkedBlockingQueue<T> in;
    private T row;
    private Data buffer;
    private PageStore pageFile;
    private HashIndex index;
    private long inCount;
    private long threadId;

    public IndexWriter(LinkedBlockingQueue<T> in,
                       PageStore pageFile, HashIndex index) {
        this.in = in;
        this.row = null;
        this.pageFile = pageFile;
        this.index = index;
        this.buffer = new Data(new byte[512]);
        this.inCount = 0;
        this.threadId = 0;
    }

    private void nextRow() {
        while (true) {
            row = null;
            try {
                row = in.take();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            if (row != null) {
                return;
            }
        }
    }

    @Override
    public void run() {
        this.threadId = Thread.currentThread().getId();
        System.out.println("INFO: Writer thread is running. Thread id:" + threadId);
        while (true) {
            this.nextRow();
            if(row.isEmpty()) {
                break;
            }
            row.writeToBuffer(buffer);
            int PageId = index.getBucketIndex(row.getHashCode());
            pageFile.insertIndexData(PageId, buffer);
            inCount++;
            if(inCount % 30 == 0) {
                System.out.println("INFO: Writer count is:" + inCount + ". Thread id:" + threadId);
            }
        }
        this.pageFile.close();
        System.out.println("INFO: Writer thread completed. Thread id:" + threadId);
    }
}
