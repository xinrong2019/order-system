package com.alibaba.middleware.race.table;

import com.alibaba.middleware.race.cache.ThreadPool;
import com.alibaba.middleware.race.index.HashIndex;
import com.alibaba.middleware.race.index.RecordIndex;
import com.alibaba.middleware.race.query.*;
import com.alibaba.middleware.race.result.ResultImpl;
import com.alibaba.middleware.race.store.Data;
import com.alibaba.middleware.race.store.DataPage;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousFileChannel;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;

public class OrderTable {
    public static OrderTable instance = new OrderTable();
    public static OrderTable getInstance() {
        return instance;
    }
    private OrderTable() {}

    public static final int BASE_SIZE = 1;
    private static final int GOOD_INDEX_BUCKET_SIZE = 64*BASE_SIZE;
    private static final int ORDER_INDEX_BUCKET_SIZE = 64*BASE_SIZE;
    private static final int BUYER_INDEX_BUCKET_SIZE = 128*BASE_SIZE;

    // 每页的大小，单位为byte
    private static final int GOOD_TABLE_PAGE_SIZE = 4*(1<<10);
    // 存储索引为goodId
    public HashTable goodIndex;

    private static final String[] INDEX_COLUMNS = {};   // 索引不需要列信息
    private static final int ORDER_INDEX_PAGE_SIZE = 4*(1<<10);  // 1KB
    // 索引为orderId
    public HashTable orderIndex;

    private static final int BUYER_INDEX_PAGE_SIZE = 4*(1<<10);
    // 索引为buyerId
    public HashTable buyerIndex;

    public HashMap<String, Byte> orderFilesMap;
    public String[] sortOrderFiles;
    private AsynchronousFileChannel[] fileChannels;

    public void init(Collection<String> storeFolders, Collection<String> orderFiles) {
        goodIndex = new HashTable("orderTable");
        goodIndex.setBaseColumns(INDEX_COLUMNS);
        goodIndex.init(storeFolders, GOOD_INDEX_BUCKET_SIZE, GOOD_TABLE_PAGE_SIZE);

        orderIndex = new HashTable("orderIndex");
        orderIndex.setBaseColumns(INDEX_COLUMNS);
        orderIndex.init(storeFolders, ORDER_INDEX_BUCKET_SIZE, ORDER_INDEX_PAGE_SIZE);

        buyerIndex = new HashTable("buyerIndex");
        buyerIndex.setBaseColumns(INDEX_COLUMNS);
        buyerIndex.init(storeFolders, BUYER_INDEX_BUCKET_SIZE, BUYER_INDEX_PAGE_SIZE);

        orderFilesMap = new HashMap<String, Byte>(orderFiles.size());
        sortOrderFiles = new String[orderFiles.size()];
        for (String file: orderFiles) {
            byte postfix = (byte)Integer.parseInt(file.substring(file.lastIndexOf('.')+1));
            orderFilesMap.put(file, postfix);
            sortOrderFiles[postfix] = file;
        }
    }

    private volatile boolean prepared = false;

    public boolean isPrepared() {
        return prepared;
    }

    public void reopen() {
        goodIndex.reopen();
        orderIndex.reopen();
        buyerIndex.reopen();
        this.prepared = true;
        this.fileChannels = new AsynchronousFileChannel[this.sortOrderFiles.length];
        try {
            HashSet<StandardOpenOption>openOptions = new HashSet<StandardOpenOption>(
                    Collections.singleton(StandardOpenOption.READ));
            for (int i = 0; i < sortOrderFiles.length; i++) {
                this.fileChannels[i] = AsynchronousFileChannel.open(Paths.get(this.sortOrderFiles[i]),
                        openOptions, ThreadPool.getInstance().pool);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public RecordIndex findOderIdIndex(long orderId) {
        return this.orderIndex.findIndex(orderId);
    }

    public ArrayList<RecordIndex> findGoodIdIndex(String goodId) {
        return this.goodIndex.findIndex(goodId);
    }

    public HashMap<String, String> findOrderRecord(RecordIndex recordIndex) {
        HashMap<String, String> result = new HashMap<String, String>();
        String fileName = this.sortOrderFiles[recordIndex.getFileId()];
        try {
            RandomAccessFile randomAccessFile = new RandomAccessFile(fileName, "r");
            long fileSize = randomAccessFile.length();
            long pos = recordIndex.getAddress();
            byte[] buffer = new byte[(int)Math.min(2048, fileSize-pos)];
            randomAccessFile.seek(pos);
            randomAccessFile.readFully(buffer);
            int begin = 0;
            String key="";
            for (int i=0; i<buffer.length; i++) {
                if (buffer[i] == '\n') {
                    result.put(key, new String(buffer, begin, i-begin));
                    break;
                }
                if (buffer[i] == ':') {
                    key = new String(buffer, begin, i-begin);
                    begin = i+1;
                } else if (buffer[i] == '\t') {
                    result.put(key, new String(buffer, begin, i-begin));
                    begin = i+1;
                }
            }
            randomAccessFile.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return result;
    }

    public ArrayList<HashMap<String, String>> findOrders(ArrayList<RecordIndex> recordIndices) {
        ArrayList<HashMap<String, String>> results = new ArrayList<HashMap<String, String>>();
        try {
            findOrderRecords(recordIndices, results);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return results;
    }

    private void findOrderRecords(ArrayList<RecordIndex> recordIndexes, ArrayList<HashMap<String, String>> results)
            throws IOException, ExecutionException, InterruptedException {
        CountDownLatch latch = new CountDownLatch(recordIndexes.size());
        ArrayList<RecordAttachment> attachments = new ArrayList<RecordAttachment>(recordIndexes.size());
        Collections.sort(recordIndexes);
        byte fileId;
        RecordHandler recordHandler = new RecordHandler();
        for (RecordIndex recordIndex:recordIndexes) {
            fileId = recordIndex.getFileId();
            RecordAttachment attachment = new RecordAttachment(latch, recordIndex, 600);
            attachments.add(attachment);
            this.fileChannels[fileId].read(ByteBuffer.wrap(attachment.buffer), recordIndex.getAddress(),
                    attachment, recordHandler);
        }
        latch.await();
        for (RecordAttachment attachment: attachments) {
            results.add(attachment.record);
        }
    }

    public ArrayList<ResultImpl> findByBuyer(String buyerId, long startTime, long endTime) {
        ArrayList<ResultImpl> results = new ArrayList<ResultImpl>();
        CountDownLatch waitForBuyer;
        HashMap<String, String> buyerRecord;
        waitForBuyer = new CountDownLatch(1);
        // 查找buyerRecord
        buyerRecord = new HashMap<String, String>();
        BuyerTable.getInstance().findBuyer(buyerId, waitForBuyer, buyerRecord);
        CountDownLatch waitForResult = new CountDownLatch(1);
        BuyerCondition condition = new BuyerCondition(Data.getKeyPostfix(buyerId), startTime, endTime);
        int fileId = OrderTable.getInstance().buyerIndex.getIndex().getFileIndex(HashIndex.getHashCode(buyerId));
        int pageId = OrderTable.getInstance().buyerIndex.getIndex().getBucketId(HashIndex.getHashCode(buyerId));
        AsynchronousFileChannel fileChannel = buyerIndex.getFileChannel(fileId);
        byte[] buffer = new byte[BUYER_INDEX_PAGE_SIZE];
        Data data = new Data(buffer, DataPage.NextPos);
        BuyerAttachment attachment = new BuyerAttachment(condition, buffer, waitForBuyer,
                buyerRecord, waitForResult, results);
        BuyerHandler buyerHandler = new BuyerHandler();
        while (pageId != -1) {
            fileChannel.read(ByteBuffer.wrap(attachment.buffer),
                    ((long)pageId*BUYER_INDEX_PAGE_SIZE), attachment, buyerHandler);
            // 等待结果
            while (true) {
                try {
                    attachment.waitForResult.await();
                    break;
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
            attachment.waitForResult = new CountDownLatch(1);
            data.setPos(DataPage.NextPos);
            pageId = data.readInt();
        }

        return results;
    }
}
