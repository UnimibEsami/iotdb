/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.iotdb.db.utils.datastructure;

import org.apache.iotdb.db.rescon.PrimitiveArrayManager;
import org.apache.iotdb.tsfile.file.metadata.enums.TSDataType;
import org.apache.iotdb.tsfile.file.metadata.enums.TSEncoding;
import org.apache.iotdb.tsfile.read.TimeValuePair;
import org.apache.iotdb.tsfile.utils.Binary;
import org.apache.iotdb.tsfile.utils.TsPrimitiveType;

import java.util.ArrayList;
import java.util.List;

import static org.apache.iotdb.db.rescon.PrimitiveArrayManager.ARRAY_SIZE;

public class BinaryTVList extends TVList {

  private List<Binary[]> values;

  private Binary[][] sortedValues;

  private Binary pivotValue;

  BinaryTVList() {
    super();
    values = new ArrayList<>();
  }

  @Override
  public void putBinary(long timestamp, Binary value) {
    checkExpansion();
    int arrayIndex = size / ARRAY_SIZE;
    int elementIndex = size % ARRAY_SIZE;
    timestamps.get(arrayIndex)[elementIndex] = timestamp;
    values.get(arrayIndex)[elementIndex] = value;
    size++;
    if (sorted && size > 1 && timestamp < getTime(size - 2)) {
      sorted = false;
    }
  }

  @Override
  public Binary getBinary(int index) {
    if (index >= size) {
      throw new ArrayIndexOutOfBoundsException(index);
    }
    int arrayIndex = index / ARRAY_SIZE;
    int elementIndex = index % ARRAY_SIZE;
    return values.get(arrayIndex)[elementIndex];
  }

  protected void set(int index, long timestamp, Binary value) {
    if (index >= size) {
      throw new ArrayIndexOutOfBoundsException(index);
    }
    int arrayIndex = index / ARRAY_SIZE;
    int elementIndex = index % ARRAY_SIZE;
    timestamps.get(arrayIndex)[elementIndex] = timestamp;
    values.get(arrayIndex)[elementIndex] = value;
  }

  @Override
  public BinaryTVList clone() {
    BinaryTVList cloneList = new BinaryTVList();
    cloneAs(cloneList);
    for (Binary[] valueArray : values) {
      cloneList.values.add(cloneValue(valueArray));
    }
    return cloneList;
  }

  private Binary[] cloneValue(Binary[] array) {
    Binary[] cloneArray = new Binary[array.length];
    System.arraycopy(array, 0, cloneArray, 0, array.length);
    return cloneArray;
  }

  @Override
  public void sort() {
    if (sortedTimestamps == null
        || sortedTimestamps.length < PrimitiveArrayManager.getArrayRowCount(size)) {
      sortedTimestamps =
          (long[][]) PrimitiveArrayManager.createDataListsByType(TSDataType.INT64, size);
    }
    if (sortedValues == null
        || sortedValues.length < PrimitiveArrayManager.getArrayRowCount(size)) {
      sortedValues =
          (Binary[][]) PrimitiveArrayManager.createDataListsByType(TSDataType.TEXT, size);
    }
    sort(0, size);
    clearSortedValue();
    clearSortedTime();
    sorted = true;
  }

  @Override
  void clearValue() {
    if (values != null) {
      for (Binary[] dataArray : values) {
        PrimitiveArrayManager.release(dataArray);
      }
      values.clear();
    }
  }

  @Override
  void clearSortedValue() {
    if (sortedValues != null) {
      sortedValues = null;
    }
  }

  @Override
  protected void setFromSorted(int src, int dest) {
    set(
        dest,
        sortedTimestamps[src / ARRAY_SIZE][src % ARRAY_SIZE],
        sortedValues[src / ARRAY_SIZE][src % ARRAY_SIZE]);
  }

  @Override
  protected void set(int src, int dest) {
    long srcT = getTime(src);
    Binary srcV = getBinary(src);
    set(dest, srcT, srcV);
  }

  @Override
  protected void setToSorted(int src, int dest) {
    sortedTimestamps[dest / ARRAY_SIZE][dest % ARRAY_SIZE] = getTime(src);
    sortedValues[dest / ARRAY_SIZE][dest % ARRAY_SIZE] = getBinary(src);
  }

  @Override
  protected void reverseRange(int lo, int hi) {
    hi--;
    while (lo < hi) {
      long loT = getTime(lo);
      Binary loV = getBinary(lo);
      long hiT = getTime(hi);
      Binary hiV = getBinary(hi);
      set(lo++, hiT, hiV);
      set(hi--, loT, loV);
    }
  }

  @Override
  protected void expandValues() {
    values.add((Binary[]) getPrimitiveArraysByType(TSDataType.TEXT));
  }

  @Override
  protected void saveAsPivot(int pos) {
    pivotTime = getTime(pos);
    pivotValue = getBinary(pos);
  }

  @Override
  protected void setPivotTo(int pos) {
    set(pos, pivotTime, pivotValue);
  }

  @Override
  public TimeValuePair getTimeValuePair(int index) {
    return new TimeValuePair(
        getTime(index), TsPrimitiveType.getByType(TSDataType.TEXT, getBinary(index)));
  }

  @Override
  protected TimeValuePair getTimeValuePair(
      int index, long time, Integer floatPrecision, TSEncoding encoding) {
    return new TimeValuePair(time, TsPrimitiveType.getByType(TSDataType.TEXT, getBinary(index)));
  }

  @Override
  protected void releaseLastValueArray() {
    PrimitiveArrayManager.release(values.remove(values.size() - 1));
  }

  @Override
  public void putBinaries(long[] time, Binary[] value, int start, int end) {
    checkExpansion();
    int idx = start;

    updateMinTimeAndSorted(time, start, end);

    while (idx < end) {
      int inputRemaining = end - idx;
      int arrayIdx = size / ARRAY_SIZE;
      int elementIdx = size % ARRAY_SIZE;
      int internalRemaining = ARRAY_SIZE - elementIdx;
      if (internalRemaining >= inputRemaining) {
        // the remaining inputs can fit the last array, copy all remaining inputs into last array
        System.arraycopy(time, idx, timestamps.get(arrayIdx), elementIdx, inputRemaining);
        System.arraycopy(value, idx, values.get(arrayIdx), elementIdx, inputRemaining);
        size += inputRemaining;
        break;
      } else {
        // the remaining inputs cannot fit the last array, fill the last array and create a new
        // one and enter the next loop
        System.arraycopy(time, idx, timestamps.get(arrayIdx), elementIdx, internalRemaining);
        System.arraycopy(value, idx, values.get(arrayIdx), elementIdx, internalRemaining);
        idx += internalRemaining;
        size += internalRemaining;
        checkExpansion();
      }
    }
  }

  @Override
  public TSDataType getDataType() {
    return TSDataType.TEXT;
  }
}
