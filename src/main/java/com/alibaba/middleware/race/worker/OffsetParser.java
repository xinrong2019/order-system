package com.alibaba.middleware.race.worker;

import com.alibaba.middleware.race.cache.IndexCache;
import com.alibaba.middleware.race.cache.IndexEntry;
import com.alibaba.middleware.race.index.RecordIndex;
import com.alibaba.middleware.race.store.Data;
import com.alibaba.middleware.race.table.HashTable;
import com.alibaba.middleware.race.table.OffsetLine;

import java.util.StringTokenizer;
import java.util.concurrent.LinkedBlockingQueue;

public class OffsetParser implements Runnable {
    private LinkedBlockingQueue<OffsetLine> in;
    private OffsetLine line;
    private IndexCache indexCache;
    private HashTable table;
    private int rowCount;
    private long threadId;

    public OffsetParser(LinkedBlockingQueue<OffsetLine> in, IndexCache indexCache, HashTable table) {
        this.in = in;
        this.line = null;
        this.indexCache = indexCache;
        this.table = table;
        this.rowCount = 0;
        this.threadId = 0;
    }

    private void parseRow(OffsetLine line) {
        StringTokenizer tokenizer = new StringTokenizer(line.getLine(), ":\t");
        String key;
        String value;
        String rowKey = "";
        while (tokenizer.hasMoreTokens()) {
            key = tokenizer.nextToken();
            value = tokenizer.nextToken();
            int columnId =this.table.getColumnId(key);
            if (columnId == 0) {
                rowKey = value;
            }
        }
        RecordIndex recordIndex = line.getRecodeIndex();
        Long postfix = Data.getKeyPostfix(rowKey);
        short prefix = Data.getKeyPrefix(rowKey);
        this.indexCache.put(postfix, new IndexEntry(prefix, recordIndex.getFileId(),
                (int)recordIndex.getAddress()));
    }

    private void nextLine() {
        while (true) {
            line = null;
            try {
                line = in.take();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            if (line != null) {
                return;
            }
        }
    }

    @Override
    public void run() {
        this.threadId = Thread.currentThread().getId();
        while (true) {
            this.nextLine();
            if(line.getLine().isEmpty()) {
                break;
            }
            parseRow(line);
            rowCount ++;
        }
        System.out.println("INFO: Parser thread completed. rowCount:" + rowCount + " Thread id:" + threadId);
    }
}
