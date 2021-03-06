/*
 * Copyright 2015-16, Yahoo! Inc.
 * Licensed under the terms of the Apache License 2.0. See LICENSE file at the project root for terms.
 */

package com.yahoo.sketches.tuple;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.lang.reflect.Array;
import java.nio.ByteOrder;

import com.yahoo.memory.Memory;
import com.yahoo.memory.NativeMemory;
import com.yahoo.sketches.Family;
import com.yahoo.sketches.SketchesArgumentException;

/**
 * CompactSketches are never created directly. They are created as a result of
 * the compact() method of an UpdatableSketch or as a result of the getResult()
 * method of a set operation like Union, Intersection or AnotB. CompactSketch
 * consists of a compact list (i.e. no intervening spaces) of hash values,
 * corresponding list of Summaries, and a value for theta. The lists may or may
 * not be ordered. CompactSketch is read-only.
 *
 * @param <S> type of Summary
 */
public class CompactSketch<S extends Summary> extends Sketch<S> {
  private static final byte serialVersionUID = 1;
  
  private enum Flags { IS_BIG_ENDIAN, IS_EMPTY, HAS_ENTRIES, IS_THETA_INCLUDED }

  CompactSketch(final long[] keys, final S[] summaries, final long theta, final boolean isEmpty) {
    keys_ = keys;
    summaries_ = summaries;
    theta_ = theta;
    isEmpty_ = isEmpty;
  }

  /**
   * This is to create an instance of a CompactSketch given a serialized form
   * @param mem Memory object with serialized CompactSketch
   */
  @SuppressWarnings({"unchecked"})
  CompactSketch(final Memory mem) {
    int offset = 0;
    byte preambleLongs = mem.getByte(offset++);
    byte version = mem.getByte(offset++);
    byte familyId = mem.getByte(offset++);
    SerializerDeserializer.validateFamily(familyId, preambleLongs);
    if (version != serialVersionUID) {
      throw new SketchesArgumentException("Serial version mismatch. Expected: " + serialVersionUID 
          + ", actual: " + version);
    }
    SerializerDeserializer
      .validateType(mem.getByte(offset++), SerializerDeserializer.SketchType.CompactSketch);
    byte flags = mem.getByte(offset++);
    boolean isBigEndian = (flags & (1 << Flags.IS_BIG_ENDIAN.ordinal())) > 0;
    if (isBigEndian ^ ByteOrder.nativeOrder().equals(ByteOrder.BIG_ENDIAN)) {
      throw new SketchesArgumentException("Byte order mismatch");
    }
    isEmpty_ = (flags & (1 << Flags.IS_EMPTY.ordinal())) > 0;
    boolean isThetaIncluded = (flags & (1 << Flags.IS_THETA_INCLUDED.ordinal())) > 0;
    if (isThetaIncluded) {
      theta_ = mem.getLong(offset);
      offset += Long.BYTES;
    } else {
      theta_ = Long.MAX_VALUE;
    }
    boolean hasEntries = (flags & (1 << Flags.HAS_ENTRIES.ordinal())) > 0;
    if (hasEntries) {
      int classNameLength = mem.getByte(offset++);
      int count = mem.getInt(offset);
      offset += Integer.BYTES;
      byte[] classNameBuffer = new byte[classNameLength];
      mem.getByteArray(offset, classNameBuffer, 0, classNameLength);
      offset += classNameLength;
      String className = new String(classNameBuffer, UTF_8);
      keys_ = new long[count];
      for (int i = 0; i < count; i++) {
        keys_[i] = mem.getLong(offset);
        offset += Long.BYTES;
      }
      for (int i = 0; i < count; i++) {
        DeserializeResult<S> result = 
            SerializerDeserializer.deserializeFromMemory(mem, offset, className);
        S summary = result.getObject();
        offset += result.getSize();
        if (summaries_ == null) {
          summaries_ = (S[]) Array.newInstance(summary.getClass(), count);
        }
        summaries_[i] = summary;
      }
    }
  }

  @Override
  public S[] getSummaries() {
    if (keys_ == null || keys_.length == 0) {
      return null;
    }
    
    @SuppressWarnings("unchecked")
    S[] summaries = 
      (S[]) Array.newInstance(summaries_.getClass().getComponentType(), summaries_.length);
    for (int i = 0; i < summaries_.length; ++i) {
      summaries[i] = summaries_[i].copy();
    }
    return summaries;
  }

  @Override
  public int getRetainedEntries() {
    return keys_ == null ? 0 : keys_.length;
  }

  // Layout of first 8 bytes:
  // Long || Start Byte Adr:
  // Adr: 
  //      ||    7   |    6   |    5   |    4   |    3   |    2   |    1   |     0              |
  //  0   ||                          |  Flags | SkType | FamID  | SerVer |  Preamble_Longs    |
  @SuppressWarnings("null")
  @Override
  public byte[] toByteArray() {
    int summariesBytesLength = 0;
    byte[][] summariesBytes = null;
    int count = getRetainedEntries();
    if (count > 0) {
      summariesBytes = new byte[count][];
      for (int i = 0; i < count; i++) {
        summariesBytes[i] = summaries_[i].toByteArray();
        summariesBytesLength += summariesBytes[i].length;
      }
    }

    int sizeBytes =
        Byte.BYTES // preamble longs
      + Byte.BYTES // serial version
      + Byte.BYTES // family id
      + Byte.BYTES // sketch type
      + Byte.BYTES; // flags
    boolean isThetaIncluded = theta_ < Long.MAX_VALUE;
    if (isThetaIncluded) {
      sizeBytes += Long.BYTES; // theta
    }
    String summaryClassName = null;
    if (count > 0) {
      summaryClassName = summaries_[0].getClass().getName();
      sizeBytes +=
          Byte.BYTES // summary class name length
        + Integer.BYTES // count
        + summaryClassName.length() 
        + Long.BYTES * count + summariesBytesLength;
    }
    byte[] bytes = new byte[sizeBytes];
    Memory mem = new NativeMemory(bytes);
    int offset = 0;
    mem.putByte(offset++, PREAMBLE_LONGS);
    mem.putByte(offset++, serialVersionUID);
    mem.putByte(offset++, (byte) Family.TUPLE.getID());
    mem.putByte(offset++, (byte) SerializerDeserializer.SketchType.CompactSketch.ordinal());
    boolean isBigEndian = ByteOrder.nativeOrder().equals(ByteOrder.BIG_ENDIAN);
    mem.putByte(offset++, (byte) (
      (isBigEndian ? 1 << Flags.IS_BIG_ENDIAN.ordinal() : 0) 
      | (isEmpty_ ? 1 << Flags.IS_EMPTY.ordinal() : 0) 
      | (count > 0 ? 1 << Flags.HAS_ENTRIES.ordinal() : 0) 
      | (isThetaIncluded ? 1 << Flags.IS_THETA_INCLUDED.ordinal() : 0)
    ));
    if (isThetaIncluded) { //TODO check byte allignment to 8 bytes.
      mem.putLong(offset, theta_);
      offset += Long.BYTES;
    }
    if (count > 0) {
      mem.putByte(offset++, (byte) summaryClassName.length());
      mem.putInt(offset, getRetainedEntries());
      offset += Integer.BYTES;
      mem.putByteArray(offset, summaryClassName.getBytes(UTF_8), 0, summaryClassName.length());
      offset += summaryClassName.length();
      for (int i = 0; i < count; i++) {
        mem.putLong(offset, keys_[i]);
        offset += Long.BYTES;
      }
      for (int i = 0; i < count; i++) {
        mem.putByteArray(offset, summariesBytes[i], 0, summariesBytes[i].length);
        offset += summariesBytes[i].length;
      }
    }
    return bytes;
  }

}
