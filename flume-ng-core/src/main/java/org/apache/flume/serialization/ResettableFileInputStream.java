/**
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
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flume.serialization;

import com.google.common.base.Charsets;
import org.apache.flume.annotations.InterfaceAudience;
import org.apache.flume.annotations.InterfaceStability;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;

/**
 * <p/>This class makes the following assumptions:
 * <ol>
 *   <li>The underlying file is not changing while it is being read</li>
 * </ol>
 *
 * <p/>The ability to {@link #reset()} is dependent on the underlying {@link
 * PositionTracker} instance's durability semantics.
 */
@InterfaceAudience.Private
@InterfaceStability.Evolving
public class ResettableFileInputStream extends ResettableInputStream {

  public static final int DEFAULT_BUF_SIZE = 16384;

  private final File file;
  private final PositionTracker tracker;
  private final FileInputStream in;
  private final FileChannel chan;
  private final ByteBuffer buf;
  private final CharBuffer charBuf;
  private final byte[] byteBuf;
  private final long fileSize;
  private final CharsetDecoder decoder;
  private long position;
  private long syncPosition;

  /**
   *
   * @param file
   *        File to read
   *
   * @param tracker
   *        PositionTracker implementation to make offset position durable
   *
   * @throws FileNotFoundException
   */
  public ResettableFileInputStream(File file, PositionTracker tracker)
      throws IOException {
    this(file, tracker, DEFAULT_BUF_SIZE, Charsets.UTF_8);
  }

  /**
   *
   * @param file
   *        File to read
   *
   * @param tracker
   *        PositionTracker implementation to make offset position durable
   *
   * @param bufSize
   *        Size of the underlying buffer used for input
   *
   * @param charset
   *        Character set used for decoding text, as necessary
   *
   * @throws FileNotFoundException
   */
  public ResettableFileInputStream(File file, PositionTracker tracker,
                                   int bufSize, Charset charset)
      throws IOException {
    this.file = file;
    this.tracker = tracker;
    this.in = new FileInputStream(file);
    this.chan = in.getChannel();
    this.buf = ByteBuffer.allocateDirect(bufSize);
    buf.flip();
    this.byteBuf = new byte[1]; // single byte
    this.charBuf = CharBuffer.allocate(1); // single char
    charBuf.flip();
    this.fileSize = file.length();
    this.decoder = charset.newDecoder();
    this.position = 0;
    this.syncPosition = 0;

    seek(tracker.getPosition());
  }

  @Override
  public synchronized int read() throws IOException {
    int len = read(byteBuf, 0, 1);
    if (len == -1) {
      return -1;
    // len == 0 should never happen
    } else if (len == 0) {
      return -1;
    } else {
      return byteBuf[0];
    }
  }

  @Override
  public synchronized int read(byte[] b, int off, int len) throws IOException {
    if (position >= fileSize) {
      return -1;
    }

    if (!buf.hasRemaining()) {
      refillBuf();
    }

    int rem = buf.remaining();
    if (len > rem) {
      len = rem;
    }
    buf.get(b, off, len);
    incrPosition(len, true);
    return len;
  }

  @Override
  public synchronized int readChar() throws IOException {
    if (!buf.hasRemaining()) {
      refillBuf();
    }

    int start = buf.position();
    charBuf.clear();

    boolean isEndOfInput = false;
    if (position >= fileSize) {
      isEndOfInput = true;
    }

    CoderResult res = decoder.decode(buf, charBuf, isEndOfInput);
    if (res.isMalformed() || res.isUnmappable()) {
      res.throwException();
    }

    int delta = buf.position() - start;

    charBuf.flip();
    if (charBuf.hasRemaining()) {
      char c = charBuf.get();
      // don't increment the persisted location if we are in between a
      // surrogate pair, otherwise we may never recover if we seek() to this
      // location!
      incrPosition(delta, !Character.isHighSurrogate(c));
      return c;

    // there may be a partial character in the decoder buffer
    } else {
      incrPosition(delta, false);
      return -1;
    }

  }

  private void refillBuf() throws IOException {
    buf.compact();
    chan.position(position); // ensure we read from the proper offset
    chan.read(buf);
    buf.flip();
  }

  @Override
  public void mark() throws IOException {
    tracker.storePosition(tell());
  }

  @Override
  public void reset() throws IOException {
    seek(tracker.getPosition());
  }

  @Override
  public long tell() throws IOException {
    return syncPosition;
  }

  @Override
  public synchronized void seek(long newPos) throws IOException {

    // check to see if we can seek within our existing buffer
    long relativeChange = newPos - position;
    if (relativeChange == 0) return; // seek to current pos => no-op

    long newBufPos = buf.position() + relativeChange;
    if (newBufPos >= 0 && newBufPos < buf.limit()) {
      // we can reuse the read buffer
      buf.position((int)newBufPos);
    } else {
      // otherwise, we have to invalidate the read buffer
      buf.clear();
      buf.flip();
    }

    // clear decoder state
    decoder.reset();

    // perform underlying file seek
    chan.position(newPos);

    // reset position pointers
    position = syncPosition = newPos;
  }

  private void incrPosition(int incr, boolean updateSyncPosition) {
    position += incr;
    if (updateSyncPosition) {
      syncPosition = position;
    }
  }

  @Override
  public void close() throws IOException {
    tracker.close();
    in.close();
  }

}
