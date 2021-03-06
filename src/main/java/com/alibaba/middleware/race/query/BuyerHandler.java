package com.alibaba.middleware.race.query;

import com.alibaba.middleware.race.OrderSystemImpl;
import com.alibaba.middleware.race.index.RecordIndex;
import com.alibaba.middleware.race.result.KVImpl;
import com.alibaba.middleware.race.result.ResultImpl;
import com.alibaba.middleware.race.store.Data;
import com.alibaba.middleware.race.store.DataPage;
import com.alibaba.middleware.race.table.GoodTable;
import com.alibaba.middleware.race.table.HashTable;
import com.alibaba.middleware.race.table.OrderTable;

import java.nio.channels.CompletionHandler;
import java.util.ArrayList;
import java.util.HashMap;

public class BuyerHandler implements CompletionHandler<Integer, BuyerAttachment> {

    @Override
    public void completed(Integer readLen, BuyerAttachment attachment) {
        byte[] buffer = attachment.buffer;
        DataPage page = new DataPage(new Data(buffer), DataPage.HeaderLength);
        page.parseHeader();
        ArrayList<RecordIndex> indexes = HashTable.findIndex(page, attachment.condition);
        ArrayList<HashMap<String, String>> orderRecords = OrderTable.getInstance().findOrders(indexes);
        HashMap<String, HashMap<String, String>> goodRecords = GoodTable.getInstance().findGoodOfOrder(orderRecords);
        while (true) {
            try {
                attachment.waitBuyerLatch.await();
                break;
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        HashMap<String, KVImpl> result;
        long orderId, createTime;
        for (HashMap<String, String> orderRecord: orderRecords) {
            result = OrderSystemImpl.joinResult(orderRecord,
                    goodRecords.get(orderRecord.get("goodid")), attachment.buyerRecord);
            orderId = Long.parseLong(orderRecord.get("orderid"));
            createTime = Long.parseLong(orderRecord.get("createtime"));
            attachment.resultsSet.add(new ResultImpl(orderId, result, createTime));
        }
        attachment.waitForResult.countDown();
    }

    @Override
    public void failed(Throwable exc, BuyerAttachment attachment) {
        System.out.println("ERROR: Find Record failed with exception:");
        exc.printStackTrace();
    }
}
