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

package org.apache.iotdb.db.query.executor;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.iotdb.db.conf.IoTDBDescriptor;
import org.apache.iotdb.db.exception.StorageEngineException;
import org.apache.iotdb.db.exception.path.PathException;
import org.apache.iotdb.db.exception.query.QueryProcessException;
import org.apache.iotdb.db.qp.physical.crud.AggregationPlan;
import org.apache.iotdb.db.query.aggregation.AggregateResult;
import org.apache.iotdb.db.query.context.QueryContext;
import org.apache.iotdb.db.query.control.QueryResourceManager;
import org.apache.iotdb.db.query.dataset.SingleDataSet;
import org.apache.iotdb.db.query.factory.AggreResultFactory;
import org.apache.iotdb.db.query.reader.IReaderByTimestamp;
import org.apache.iotdb.db.query.reader.seriesRelated.AggregateReader;
import org.apache.iotdb.db.query.reader.seriesRelated.IAggregateReader;
import org.apache.iotdb.db.query.reader.seriesRelated.SeriesReaderByTimestamp;
import org.apache.iotdb.db.query.timegenerator.EngineTimeGenerator;
import org.apache.iotdb.tsfile.file.metadata.enums.TSDataType;
import org.apache.iotdb.tsfile.file.metadata.statistics.Statistics;
import org.apache.iotdb.tsfile.read.common.Path;
import org.apache.iotdb.tsfile.read.common.RowRecord;
import org.apache.iotdb.tsfile.read.expression.IExpression;
import org.apache.iotdb.tsfile.read.expression.impl.GlobalTimeExpression;
import org.apache.iotdb.tsfile.read.filter.basic.Filter;
import org.apache.iotdb.tsfile.read.query.dataset.QueryDataSet;

public class AggregationExecutor {

  private List<Path> selectedSeries;
  private List<TSDataType> dataTypes;
  private List<String> aggregations;
  private IExpression expression;

  /**
   * aggregation batch calculation size.
   **/
  private int aggregateFetchSize;

  public AggregationExecutor(AggregationPlan aggregationPlan) {
    this.selectedSeries = aggregationPlan.getDeduplicatedPaths();
    this.dataTypes = aggregationPlan.getDeduplicatedDataTypes();
    this.aggregations = aggregationPlan.getDeduplicatedAggregations();
    this.expression = aggregationPlan.getExpression();
    this.aggregateFetchSize = IoTDBDescriptor.getInstance().getConfig().getBatchSize();
  }

  /**
   * execute aggregate function with only time filter or no filter.
   *
   * @param context query context
   */
  public QueryDataSet executeWithoutValueFilter(QueryContext context)
      throws StorageEngineException, IOException, QueryProcessException {

    Filter timeFilter = null;
    if (expression != null) {
      timeFilter = ((GlobalTimeExpression) expression).getFilter();
    }

    //TODO use multi-thread
    Map<Path, List<Integer>> seriesMap = mergeSameSeries(selectedSeries);
    AggregateResult[] aggregateResultList = new AggregateResult[selectedSeries.size()];
    for (Map.Entry<Path, List<Integer>> entry : seriesMap.entrySet()) {
      List<AggregateResult> aggregateResults = groupAggregationsBySeries(entry, timeFilter,
          context);
      int index = 0;
      for (int i : entry.getValue()) {
        aggregateResultList[i] = aggregateResults.get(index);
        index++;
      }
    }

    return constructDataSet(Arrays.asList(aggregateResultList));
  }

  /**
   * get aggregation result for one series
   *
   * @param series series map
   * @param timeFilter time filter
   * @param context query context
   * @return AggregateResult list
   */
  private List<AggregateResult> groupAggregationsBySeries(Map.Entry<Path, List<Integer>> series,
      Filter timeFilter, QueryContext context)
      throws IOException, QueryProcessException, StorageEngineException {
    List<AggregateResult> aggregateResultList = new ArrayList<>();
    List<Boolean> isCalculatedList = new ArrayList<>();
    Path seriesPath = series.getKey();
    TSDataType tsDataType = dataTypes.get(series.getValue().get(0));
    // construct series reader without value filter
    IAggregateReader seriesReader = new AggregateReader(
        series.getKey(), tsDataType, context, QueryResourceManager.getInstance()
        .getQueryDataSource(seriesPath, context, timeFilter), timeFilter, null);

    for (int i : series.getValue()) {
      // construct AggregateResult
      AggregateResult aggregateResult = AggreResultFactory
          .getAggrResultByName(aggregations.get(i), tsDataType);
      aggregateResultList.add(aggregateResult);
      isCalculatedList.add(false);
    }
    int remainingToCalculate = series.getValue().size();

    while (seriesReader.hasNextChunk()) {
      if (seriesReader.canUseCurrentChunkStatistics()) {
        Statistics chunkStatistics = seriesReader.currentChunkStatistics();
        for (int i = 0; i < aggregateResultList.size(); i++) {
          if (Boolean.FALSE.equals(isCalculatedList.get(i))) {
            AggregateResult aggregateResult = aggregateResultList.get(i);
            aggregateResult.updateResultFromStatistics(chunkStatistics);
            if (aggregateResult.isCalculatedAggregationResult()) {
              isCalculatedList.set(i, true);
              remainingToCalculate--;
              if (remainingToCalculate == 0) {
                return aggregateResultList;
              }
            }
          }
        }
        seriesReader.skipCurrentChunk();
        continue;
      }
      while (seriesReader.hasNextPage()) {
        //cal by pageheader
        if (seriesReader.canUseCurrentPageStatistics()) {
          Statistics pageStatistic = seriesReader.currentPageStatistics();
          for (int i = 0; i < aggregateResultList.size(); i++) {
            if (Boolean.FALSE.equals(isCalculatedList.get(i))) {
              AggregateResult aggregateResult = aggregateResultList.get(i);
              aggregateResult.updateResultFromStatistics(pageStatistic);
              if (aggregateResult.isCalculatedAggregationResult()) {
                isCalculatedList.set(i, true);
                remainingToCalculate--;
                if (remainingToCalculate == 0) {
                  return aggregateResultList;
                }
              }
            }
          }
          seriesReader.skipCurrentPage();
          continue;
        }
        //cal by pagedata
        while (seriesReader.hasNextOverlappedPage()) {
          for (int i = 0; i < aggregateResultList.size(); i++) {
            if (Boolean.FALSE.equals(isCalculatedList.get(i))) {
              AggregateResult aggregateResult = aggregateResultList.get(i);
              aggregateResult.updateResultFromPageData(seriesReader.nextOverlappedPage());
              if (aggregateResult.isCalculatedAggregationResult()) {
                isCalculatedList.set(i, true);
                remainingToCalculate--;
              }
              if (remainingToCalculate == 0) {
                return aggregateResultList;
              }
            }
          }
        }
      }
    }
    return aggregateResultList;
  }

  /**
   * execute aggregate function with value filter.
   *
   * @param context query context.
   */
  public QueryDataSet executeWithValueFilter(QueryContext context)
      throws StorageEngineException, PathException, IOException {

    EngineTimeGenerator timestampGenerator = new EngineTimeGenerator(expression, context);
    List<IReaderByTimestamp> readersOfSelectedSeries = new ArrayList<>();
    for (int i = 0; i < selectedSeries.size(); i++) {
      Path path = selectedSeries.get(i);
      SeriesReaderByTimestamp seriesReaderByTimestamp = new SeriesReaderByTimestamp(path,
          dataTypes.get(i), context,
          QueryResourceManager.getInstance().getQueryDataSource(path, context, null));
      readersOfSelectedSeries.add(seriesReaderByTimestamp);
    }

    List<AggregateResult> aggregateResults = new ArrayList<>();
    for (int i = 0; i < selectedSeries.size(); i++) {
      TSDataType type = dataTypes.get(i);
      AggregateResult result = AggreResultFactory.getAggrResultByName(aggregations.get(i), type);
      aggregateResults.add(result);
    }
    aggregateWithValueFilter(aggregateResults, timestampGenerator, readersOfSelectedSeries);
    return constructDataSet(aggregateResults);
  }

  /**
   * calculate aggregation result with value filter.
   */
  private void aggregateWithValueFilter(List<AggregateResult> aggregateResults,
      EngineTimeGenerator timestampGenerator, List<IReaderByTimestamp> readersOfSelectedSeries)
      throws IOException {

    while (timestampGenerator.hasNext()) {

      // generate timestamps for aggregate
      long[] timeArray = new long[aggregateFetchSize];
      int timeArrayLength = 0;
      for (int cnt = 0; cnt < aggregateFetchSize; cnt++) {
        if (!timestampGenerator.hasNext()) {
          break;
        }
        timeArray[timeArrayLength++] = timestampGenerator.next();
      }

      // cal part of aggregate result
      for (int i = 0; i < readersOfSelectedSeries.size(); i++) {
        aggregateResults.get(i).updateResultUsingTimestamps(timeArray, timeArrayLength,
            readersOfSelectedSeries.get(i));
      }
    }
  }

  /**
   * using aggregate result data list construct QueryDataSet.
   *
   * @param aggregateResultList aggregate result list
   */
  private QueryDataSet constructDataSet(List<AggregateResult> aggregateResultList) {
    List<TSDataType> dataTypes = new ArrayList<>();
    RowRecord record = new RowRecord(0);
    for (AggregateResult resultData : aggregateResultList) {
      TSDataType dataType = resultData.getDataType();
      dataTypes.add(dataType);
      record.addField(resultData.getResult(), dataType);
    }

    SingleDataSet dataSet = new SingleDataSet(selectedSeries, dataTypes);
    dataSet.setRecord(record);
    return dataSet;
  }

  /**
   * Merge same series and convert to series map. For example: Given: paths: s1, s2, s3, s1 and
   * aggregations: count, sum, count, sum seriesMap: s1 -> 0, 3; s2 -> 2; s3 -> 3
   *
   * @param selectedSeries selected series
   * @return series map
   */
  private Map<Path, List<Integer>> mergeSameSeries(List<Path> selectedSeries) {
    Map<Path, List<Integer>> seriesMap = new HashMap<>();
    for (int i = 0; i < selectedSeries.size(); i++) {
      Path series = selectedSeries.get(i);
      List<Integer> indexList = seriesMap.computeIfAbsent(series, key -> new ArrayList<>());
      indexList.add(i);
    }
    return seriesMap;
  }
}
