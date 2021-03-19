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
package org.apache.iotdb.db.engine.memtable;

import org.apache.iotdb.db.conf.IoTDBDescriptor;
import org.apache.iotdb.db.engine.querycontext.ReadOnlyMemChunk;
import org.apache.iotdb.db.exception.WriteProcessException;
import org.apache.iotdb.db.exception.metadata.MetadataException;
import org.apache.iotdb.db.exception.query.QueryProcessException;
import org.apache.iotdb.db.metadata.MetaUtils;
import org.apache.iotdb.db.metadata.PartialPath;
import org.apache.iotdb.db.metadata.mnode.MeasurementMNode;
import org.apache.iotdb.db.qp.physical.crud.InsertRowPlan;
import org.apache.iotdb.db.qp.physical.crud.InsertTabletPlan;
import org.apache.iotdb.db.rescon.TVListAllocator;
import org.apache.iotdb.db.utils.MemUtils;
import org.apache.iotdb.db.utils.datastructure.TVList;
import org.apache.iotdb.tsfile.file.metadata.enums.TSDataType;
import org.apache.iotdb.tsfile.read.common.TimeRange;
import org.apache.iotdb.tsfile.write.schema.IMeasurementSchema;
import org.apache.iotdb.tsfile.write.schema.VectorMeasurementSchema;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

public abstract class AbstractMemTable implements IMemTable {

  private final Map<String, Map<String, IWritableMemChunk>> memTableMap;
  /**
   * The initial value is true because we want calculate the text data size when recover memTable!!
   */
  protected boolean disableMemControl = true;

  private int avgSeriesPointNumThreshold =
      IoTDBDescriptor.getInstance().getConfig().getAvgSeriesPointNumberThreshold();
  /** memory size of data points, including TEXT values */
  private long memSize = 0;
  /**
   * memory usage of all TVLists memory usage regardless of whether these TVLists are full,
   * including TEXT values
   */
  private long tvListRamCost = 0;

  private int seriesNumber = 0;

  private long totalPointsNum = 0;

  private long totalPointsNumThreshold = 0;

  private long maxPlanIndex = Long.MIN_VALUE;

  private long minPlanIndex = Long.MAX_VALUE;

  public AbstractMemTable() {
    this.memTableMap = new HashMap<>();
  }

  public AbstractMemTable(Map<String, Map<String, IWritableMemChunk>> memTableMap) {
    this.memTableMap = memTableMap;
  }

  @Override
  public Map<String, Map<String, IWritableMemChunk>> getMemTableMap() {
    return memTableMap;
  }

  /**
   * check whether the given seriesPath is within this memtable.
   *
   * @return true if seriesPath is within this memtable
   */
  private boolean checkPath(String deviceId, String measurement) {
    return memTableMap.containsKey(deviceId) && memTableMap.get(deviceId).containsKey(measurement);
  }

  private IWritableMemChunk createIfNotExistAndGet(String deviceId, IMeasurementSchema schema) {
    Map<String, IWritableMemChunk> memSeries =
        memTableMap.computeIfAbsent(deviceId, k -> new HashMap<>());

    return memSeries.computeIfAbsent(
        schema.getMeasurementId(),
        k -> {
          seriesNumber++;
          totalPointsNumThreshold += avgSeriesPointNumThreshold;
          return genMemSeries(schema);
        });
  }

  protected abstract IWritableMemChunk genMemSeries(IMeasurementSchema schema);

  @Override
  public void insert(InsertRowPlan insertRowPlan) {
    updatePlanIndexes(insertRowPlan.getIndex());
    TSDataType[] types = insertRowPlan.getDataTypes();
    Object[] values = insertRowPlan.getValues();

    MeasurementMNode[] measurementMNodes = insertRowPlan.getMeasurementMNodes();
    int columnCount = 0;
    for (int i = 0; i < measurementMNodes.length; i++) {
      if (values[columnCount] == null) {
        continue;
      }

      if (measurementMNodes[i].getSchema().getType() == TSDataType.VECTOR) {
        // write vector
        Object[] vectorValue = new Object[measurementMNodes[i].getSchema().getValueTSDataTypeList().size()];
        for (int j = 0; j < vectorValue.length; j++) {
          memSize +=
              MemUtils.getRecordSize(
                  types[columnCount], values[columnCount], disableMemControl);
          vectorValue[j] = values[columnCount];
          columnCount++;
        }
        write(
            insertRowPlan.getDeviceId().getFullPath(),
            measurementMNodes[i].getSchema(),
            insertRowPlan.getTime(),
            vectorValue);
      }
      else {
        memSize +=
            MemUtils.getRecordSize(
                measurementMNodes[i].getSchema().getType(), values[columnCount], disableMemControl);

        write(
            insertRowPlan.getDeviceId().getFullPath(),
            measurementMNodes[i].getSchema(),
            insertRowPlan.getTime(),
            values[columnCount]);
        columnCount++;
      }
    }

    totalPointsNum +=
        insertRowPlan.getMeasurements().length - insertRowPlan.getFailedMeasurementNumber();
  }

  @Override
  public void insertTablet(InsertTabletPlan insertTabletPlan, int start, int end)
      throws WriteProcessException {
    updatePlanIndexes(insertTabletPlan.getIndex());
    try {
      write(insertTabletPlan, start, end);
      memSize += MemUtils.getRecordSize(insertTabletPlan, start, end, disableMemControl);
      totalPointsNum +=
          (insertTabletPlan.getDataTypes().length - insertTabletPlan.getFailedMeasurementNumber())
              * (end - start);
    } catch (RuntimeException e) {
      throw new WriteProcessException(e);
    }
  }

  @Override
  public void write(
      String deviceId, IMeasurementSchema schema, long insertTime, Object objectValue) {
    IWritableMemChunk memSeries = createIfNotExistAndGet(deviceId, schema);
    memSeries.write(insertTime, objectValue);
  }

  @Override
  public void write(InsertTabletPlan insertTabletPlan, int start, int end) {
    int columnCount = 0;
    updatePlanIndexes(insertTabletPlan.getIndex());
    for (int i = 0; i < insertTabletPlan.getMeasurements().length; i++) {
      if (insertTabletPlan.getColumns()[i] == null) {
        continue;
      }
      IWritableMemChunk memSeries =
          createIfNotExistAndGet(
              insertTabletPlan.getDeviceId().getFullPath(),
              insertTabletPlan.getMeasurementMNodes()[i].getSchema());
      if (insertTabletPlan.getMeasurementMNodes()[i].getSchema().getType() == TSDataType.VECTOR) {
        VectorMeasurementSchema vectorSchema =
            (VectorMeasurementSchema) insertTabletPlan.getMeasurementMNodes()[i].getSchema();
        Object[] columns = new Object[vectorSchema.getValueMeasurementIdList().size()];
        for (int j = 0; j < vectorSchema.getValueMeasurementIdList().size(); j++) {
          columns[j] = insertTabletPlan.getColumns()[columnCount++];
        }
        memSeries.write(insertTabletPlan.getTimes(), columns, TSDataType.VECTOR, start, end);
      } else {
        memSeries.write(
            insertTabletPlan.getTimes(),
            insertTabletPlan.getColumns()[columnCount],
            insertTabletPlan.getDataTypes()[columnCount],
            start,
            end);
        columnCount++;
      }
    }
  }

  @Override
  public boolean checkIfChunkDoesNotExist(String deviceId, String measurement) {
    Map<String, IWritableMemChunk> memSeries = memTableMap.get(deviceId);
    if (null == memSeries) {
      return true;
    }

    return !memSeries.containsKey(measurement);
  }

  @Override
  public int getCurrentChunkPointNum(String deviceId, String measurement) {
    Map<String, IWritableMemChunk> memSeries = memTableMap.get(deviceId);
    IWritableMemChunk memChunk = memSeries.get(measurement);
    return memChunk.getTVList().size();
  }

  @Override
  public int getSeriesNumber() {
    return seriesNumber;
  }

  @Override
  public long getTotalPointsNum() {
    return totalPointsNum;
  }

  @Override
  public long size() {
    long sum = 0;
    for (Map<String, IWritableMemChunk> seriesMap : memTableMap.values()) {
      for (IWritableMemChunk writableMemChunk : seriesMap.values()) {
        sum += writableMemChunk.count();
      }
    }
    return sum;
  }

  @Override
  public long memSize() {
    return memSize;
  }

  @Override
  public boolean reachTotalPointNumThreshold() {
    if (totalPointsNum == 0) {
      return false;
    }
    return totalPointsNum >= totalPointsNumThreshold;
  }

  @Override
  public void clear() {
    memTableMap.clear();
    memSize = 0;
    seriesNumber = 0;
    totalPointsNum = 0;
    totalPointsNumThreshold = 0;
    tvListRamCost = 0;
    maxPlanIndex = 0;
  }

  @Override
  public boolean isEmpty() {
    return memTableMap.isEmpty();
  }

  @Override
  public ReadOnlyMemChunk query(
      String deviceId,
      String measurement,
      IMeasurementSchema schema,
      long timeLowerBound,
      List<TimeRange> deletionList)
      throws IOException, QueryProcessException, MetadataException {
    if (schema.getType() == TSDataType.VECTOR) {
      if (!memTableMap.containsKey(deviceId)) {
        return null;
      }
      IWritableMemChunk memChunk = memTableMap.get(deviceId).get(schema.getMeasurementId());

      List<String> measurementIdList = schema.getValueMeasurementIdList();
      List<Integer> columns = new ArrayList<>();
      IMeasurementSchema vectorSchema = memChunk.getSchema();
      for (String queryingMeasurement : measurementIdList) {
        columns.add(vectorSchema.getValueMeasurementIdList().indexOf(queryingMeasurement));
      }
      // get sorted tv list is synchronized so different query can get right sorted list reference
      TVList chunkCopy = memChunk.getSortedTVListForQuery(columns);
      int curSize = chunkCopy.size();
      return new ReadOnlyMemChunk(schema, chunkCopy, curSize, deletionList);
    } else {
      if (!checkPath(deviceId, measurement)) {
        return null;
      }
      IWritableMemChunk memChunk = memTableMap.get(deviceId).get(schema.getMeasurementId());
      // get sorted tv list is synchronized so different query can get right sorted list reference
      TVList chunkCopy = memChunk.getSortedTVListForQuery();
      int curSize = chunkCopy.size();
      return new ReadOnlyMemChunk(
          measurement,
          schema.getType(),
          schema.getEncodingType(),
          chunkCopy,
          schema.getProps(),
          curSize,
          deletionList);
    }
  }

  @Override
  public void delete(
      PartialPath originalPath, PartialPath devicePath, long startTimestamp, long endTimestamp) {
    Map<String, IWritableMemChunk> deviceMap = memTableMap.get(devicePath.getFullPath());
    if (deviceMap == null) {
      return;
    }

    Iterator<Entry<String, IWritableMemChunk>> iter = deviceMap.entrySet().iterator();
    while (iter.hasNext()) {
      Entry<String, IWritableMemChunk> entry = iter.next();
      IWritableMemChunk chunk = entry.getValue();
      PartialPath fullPath = devicePath.concatNode(entry.getKey());
      IMeasurementSchema schema = chunk.getSchema();
      if (originalPath.matchFullPath(fullPath)) {
        if (startTimestamp == Long.MIN_VALUE && endTimestamp == Long.MAX_VALUE) {
          iter.remove();
        }
        int deletedPointsNumber = chunk.delete(startTimestamp, endTimestamp);
        totalPointsNum -= deletedPointsNumber;
      }
      // for vector type
      else if (schema.getType() == TSDataType.VECTOR) {
        List<String> measurements = MetaUtils.getMeasurementsInPartialPath(originalPath);
        if (measurements.containsAll(schema.getValueMeasurementIdList())) {
          if (startTimestamp == Long.MIN_VALUE && endTimestamp == Long.MAX_VALUE) {
            iter.remove();
          }
          int deletedPointsNumber = chunk.delete(startTimestamp, endTimestamp);
          totalPointsNum -= deletedPointsNumber;
        }
      }
    }
  }

  @Override
  public void addTVListRamCost(long cost) {
    this.tvListRamCost += cost;
  }

  @Override
  public long getTVListsRamCost() {
    return tvListRamCost;
  }

  @Override
  public void addTextDataSize(long testDataSize) {
    this.memSize += testDataSize;
  }

  @Override
  public void release() {
    for (Entry<String, Map<String, IWritableMemChunk>> entry : memTableMap.entrySet()) {
      for (Entry<String, IWritableMemChunk> subEntry : entry.getValue().entrySet()) {
        TVList list = subEntry.getValue().getTVList();
        if (list.getReferenceCount() == 0) {
          TVListAllocator.getInstance().release(list);
        }
      }
    }
  }

  @Override
  public long getMaxPlanIndex() {
    return maxPlanIndex;
  }

  @Override
  public long getMinPlanIndex() {
    return minPlanIndex;
  }

  void updatePlanIndexes(long index) {
    maxPlanIndex = Math.max(index, maxPlanIndex);
    minPlanIndex = Math.min(index, minPlanIndex);
  }
}
