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

package org.apache.iotdb.db.query.udf.datastructure;

import org.apache.iotdb.db.mpp.transformation.datastructure.tv.SerializableBinaryTVList;
import org.apache.iotdb.db.mpp.transformation.datastructure.tv.SerializableTVList;
import org.apache.iotdb.tsfile.file.metadata.enums.TSDataType;
import org.apache.iotdb.tsfile.utils.Binary;

import org.junit.After;
import org.junit.Before;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class SerializableBinaryTVListTest extends SerializableTVListTest {

  private List<Binary> originalList;
  private SerializableBinaryTVList testList;

  @Override
  @Before
  public void setUp() throws Exception {
    super.setUp();
    originalList = new ArrayList<>();
    testList =
        (SerializableBinaryTVList)
            SerializableTVList.newSerializableTVList(TSDataType.TEXT, QUERY_ID);
  }

  @Override
  @After
  public void tearDown() {
    super.tearDown();
  }

  @Override
  protected void generateData(int index) {
    Binary value = Binary.valueOf(String.valueOf(index));
    originalList.add(value);
    testList.putBinary(index, value);
  }

  @Override
  protected void serializeAndDeserializeOnce() {
    try {
      testList.serialize();
    } catch (IOException e) {
      fail();
    }
    try {
      testList.deserialize();
    } catch (IOException e) {
      fail();
    }
    int count = 0;
    while (testList.hasCurrent()) {
      assertEquals(count, testList.currentTime());
      assertEquals(originalList.get(count), testList.getBinary());
      testList.next();
      ++count;
    }
    assertEquals(ITERATION_TIMES, count);
  }
}
