package com.alibaba.middleware.race.table;

import com.alibaba.middleware.race.store.Data;
import com.alibaba.middleware.race.type.*;

import java.util.Map;
import java.util.TreeMap;

public class Row {
    private TreeMap<Integer, Object> values;
    private int hashCode;

    public Row() {
        this.values = new TreeMap<Integer, Object>();
        this.hashCode = 0;
    }

    public void insert(int key, String strValue) {
        Object value;
        if (strValue.equals(ValueBoolean.booleanFalseValue))  {
            value = false;
        }else if(strValue.equals(ValueBoolean.booleanTrueValue)) {
            value = true;
        } else {
            try {
                value = Long.parseLong(strValue);
            } catch (NumberFormatException e) {
                try {
                    value = Double.parseDouble(strValue);
                } catch (NumberFormatException e2) {
                    value = strValue;
                }
            }
        }
        this.values.put(key, value);
    }

    public void setHashCode(int hashCode) {
        this.hashCode = hashCode;
    }

    public int getHashCode() {
        return this.hashCode;
    }

    public boolean isEmpty() {
        return values.isEmpty();
    }

    public Object getValue(int columnId) {
        return values.get(columnId);
    }

    /**
     * 将数据序列化到byte数组中
     * @param buffer 保证要足够大
     */
    public int writeToBytes(Data buffer) {
        Object value;
        buffer.reset();
        buffer.skip(4);
        for (Map.Entry<Integer, Object> entry: values.entrySet()) {
            buffer.writeInt(entry.getKey());
            value = entry.getValue();
            if (value instanceof Boolean) {
                if (((Boolean) value)) {
                    buffer.writeByte(Value.BOOLEAN_TRUE);
                } else {
                    buffer.writeByte(Value.BOOLEAN_FALSE);
                }
            } else if (value instanceof Long) {
                buffer.writeByte(Value.LONG);
                buffer.writeLong((Long) value);
            } else if (value instanceof Double) {
                buffer.writeByte(Value.DOUBLE);
                buffer.writeDouble((Double) value);
            } else {
                buffer.writeString(((String) value));
            }
        }
        buffer.setInt(0, buffer.getPos()-4);
        return buffer.getPos();
    }
}