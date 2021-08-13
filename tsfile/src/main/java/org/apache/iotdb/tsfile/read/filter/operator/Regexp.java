package org.apache.iotdb.tsfile.read.filter.operator;

import org.apache.iotdb.tsfile.file.metadata.statistics.Statistics;
import org.apache.iotdb.tsfile.read.filter.basic.Filter;
import org.apache.iotdb.tsfile.read.filter.factory.FilterSerializeId;
import org.apache.iotdb.tsfile.read.filter.factory.FilterType;
import org.apache.iotdb.tsfile.utils.ReadWriteIOUtils;

import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** @Author: Architect @Date: 2021-08-13 14:01 */
public class Regexp<T extends Comparable<T>> implements Filter {

  protected String value;

  protected FilterType filterType;

  protected Pattern pattern;

  private Regexp() {}

  public Regexp(String value, FilterType filterType) {
    this.value = value;
    this.filterType = filterType;
    try {
      this.pattern = Pattern.compile(this.value);
    } catch (PatternSyntaxException e) {
      throw new PatternSyntaxException("Regular expression error", value.toString(), e.getIndex());
    }
  }

  @Override
  public boolean satisfy(Statistics statistics) {
    return true;
  }

  @Override
  public boolean satisfy(long time, Object value) {
    if (filterType != FilterType.VALUE_FILTER) {
      return false;
    }
    return pattern.matcher(value.toString()).find();
  }

  @Override
  public boolean satisfyStartEndTime(long startTime, long endTime) {
    return true;
  }

  @Override
  public boolean containStartEndTime(long startTime, long endTime) {
    return true;
  }

  @Override
  public Filter copy() {
    return new Regexp(value, filterType);
  }

  @Override
  public void serialize(DataOutputStream outputStream) {
    try {
      outputStream.write(getSerializeId().ordinal());
      outputStream.write(filterType.ordinal());
      ReadWriteIOUtils.writeObject(value, outputStream);
    } catch (IOException ex) {
      throw new IllegalArgumentException("Failed to serialize outputStream of type:", ex);
    }
  }

  @Override
  public void deserialize(ByteBuffer buffer) {
    filterType = FilterType.values()[buffer.get()];
    value = ReadWriteIOUtils.readString(buffer);
  }

  @Override
  public String toString() {
    return filterType + " is " + value;
  }

  @Override
  public FilterSerializeId getSerializeId() {
    return FilterSerializeId.REGEXP;
  }
}
