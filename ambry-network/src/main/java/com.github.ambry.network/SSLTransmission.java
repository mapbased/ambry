package com.github.ambry.network;

import com.github.ambry.utils.ByteBufferOutputStream;
import com.github.ambry.utils.Time;
import com.github.ambry.utils.Utils;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.nio.channels.WritableByteChannel;
import java.security.GeneralSecurityException;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * PlainTextTransmission to interact with a given socketChannel using ssl encryption
 */
public class SSLTransmission extends Transmission implements ReadableByteChannel, WritableByteChannel{

  private static final Logger log = LoggerFactory.getLogger(SSLTransmission.class);
  protected final SSLEngine sslEngine;
  private SSLEngineResult.HandshakeStatus handshakeStatus;
  private SSLEngineResult handshakeResult;
  private boolean handshakeComplete = false;
  private boolean closing = false;
  private ByteBuffer netReadBuffer;
  private ByteBuffer netWriteBuffer;
  private ByteBuffer appReadBuffer;
  private ByteBuffer emptyBuf = ByteBuffer.allocate(0);
  private NetworkReceive networkReceive;
  private NetworkSend networkSend;
  private final SSLContext sslContext;

  public SSLTransmission(SSLTempFactory sslFactory, String connectionId, SocketChannel socketChannel, SelectionKey key,
      String remoteHost, int remotePort, Time time, NetworkMetrics metrics, Logger logger)
      throws GeneralSecurityException, IOException {
    super(connectionId, socketChannel, key, time, metrics, logger);
    this.sslContext = sslFactory.createSSLContext();
    this.sslEngine = sslFactory.createSSLEngine(sslContext, remoteHost, remotePort, true);
    this.netReadBuffer = ByteBuffer.allocate(packetBufferSize());
    this.netWriteBuffer = ByteBuffer.allocate(packetBufferSize());
    this.appReadBuffer = ByteBuffer.allocate(applicationBufferSize());
    startHandshake();
  }

  /**
   * starts sslEngine handshake process
   */
  private void startHandshake()
      throws IOException {
    //clear & set netRead & netWrite buffers
    netWriteBuffer.position(0);
    netWriteBuffer.limit(0);
    netReadBuffer.position(0);
    netReadBuffer.limit(0);
    handshakeComplete = false;
    closing = false;
    //initiate handshake
    sslEngine.beginHandshake();
    handshakeStatus = sslEngine.getHandshakeStatus();
  }

  @Override
  public boolean ready() {
    return handshakeComplete;
  }

  void prepare()
      throws IOException {
    if (!ready()) {
      handshake();
    }
  }

  /**
   * Sends a SSL close message and closes socketChannel.
   * @throws IOException if an I/O error occurs
   * @throws IOException if there is data on the outgoing network buffer and we are unable to flush it
   */
  @Override
  public void close()
      throws IOException {
    if (closing) {
      return;
    }
    closing = true;
    sslEngine.closeOutbound();
    try {
      if (!flush(netWriteBuffer)) {
        throw new IOException("Remaining data in the network buffer, can't send SSL close message.");
      }
      //prep the buffer for the close message
      netWriteBuffer.clear();
      //perform the close, since we called sslEngine.closeOutbound
      SSLEngineResult handshake = sslEngine.wrap(emptyBuf, netWriteBuffer);
      //we should be in a close state
      if (handshake.getStatus() != SSLEngineResult.Status.CLOSED) {
        throw new IOException("Invalid close state, will not send network data.");
      }
      netWriteBuffer.flip();
      flush(netWriteBuffer);
      socketChannel.socket().close();
      socketChannel.close();
    } catch (IOException ie) {
      log.warn("Failed to send SSL Close message ", ie);
    }
    key.attach(null);
    key.cancel();
  }

  /**
   * returns true if there are any pending contents in netWriteBuffer
   */
  /*@Override
  public boolean hasPendingWrites() {
    return netWriteBuffer.hasRemaining();
  } */

  /**
   * Flushes the buffer to the network, non blocking
   * @param buf ByteBuffer
   * @return boolean true if the buffer has been emptied out, false otherwise
   * @throws IOException
   */
  private boolean flush(ByteBuffer buf)
      throws IOException {
    int remaining = buf.remaining();
    if (remaining > 0) {
      int written = socketChannel.write(buf);
      return written >= remaining;
    }
    return true;
  }

  /**
   * Performs SSL handshake, non blocking.
   * Before application data (kafka protocols) can be sent client & kafka broker must
   * perform ssl handshake.
   * During the handshake SSLEngine generates encrypted data  that will be transported over socketChannel.
   * Each SSLEngine operation generates SSLEngineResult , of which SSLEngineResult.handshakeStatus field is used to
   * determine what operation needs to occur to move handshake along.
   * A typical handshake might look like this.
   * +-------------+----------------------------------+-------------+
   * |  client     |  SSL/TLS message                 | HSStatus    |
   * +-------------+----------------------------------+-------------+
   * | wrap()      | ClientHello                      | NEED_UNWRAP |
   * | unwrap()    | ServerHello/Cert/ServerHelloDone | NEED_WRAP   |
   * | wrap()      | ClientKeyExchange                | NEED_WRAP   |
   * | wrap()      | ChangeCipherSpec                 | NEED_WRAP   |
   * | wrap()      | Finished                         | NEED_UNWRAP |
   * | unwrap()    | ChangeCipherSpec                 | NEED_UNWRAP |
   * | unwrap()    | Finished                         | FINISHED    |
   * +-------------+----------------------------------+-------------+
   *
   * @throws IOException
   */
  private void handshake()
      throws IOException {
    boolean read = key.isReadable();
    boolean write = key.isWritable();
    handshakeComplete = false;
    handshakeStatus = sslEngine.getHandshakeStatus();
    if (!flush(netWriteBuffer)) {
      key.interestOps(key.interestOps() | SelectionKey.OP_WRITE);
      return;
    }
    try {
      switch (handshakeStatus) {
        case NEED_TASK:
          log.trace(
              "SSLHandshake NEED_TASK channelId {}, appReadBuffer pos {}, netReadBuffer pos {}, netWriteBuffer pos {}",
              getConnectionId(), appReadBuffer.position(), netReadBuffer.position(), netWriteBuffer.position());
          handshakeStatus = runDelegatedTasks();
          break;
        case NEED_WRAP:
          log.trace(
              "SSLHandshake NEED_WRAP channelId {}, appReadBuffer pos {}, netReadBuffer pos {}, netWriteBuffer pos {}",
              getConnectionId(), appReadBuffer.position(), netReadBuffer.position(), netWriteBuffer.position());
          handshakeResult = handshakeWrap(write);
          if (handshakeResult.getStatus() == SSLEngineResult.Status.BUFFER_OVERFLOW) {
            int currentPacketBufferSize = packetBufferSize();
            netWriteBuffer = Utils.ensureCapacity(netWriteBuffer, currentPacketBufferSize);
            if (netWriteBuffer.position() >= currentPacketBufferSize) {
              throw new IllegalStateException("Buffer overflow when available data size (" + netWriteBuffer.position() +
                  ") >= network buffer size (" + currentPacketBufferSize + ")");
            }
          } else if (handshakeResult.getStatus() == SSLEngineResult.Status.BUFFER_UNDERFLOW) {
            throw new IllegalStateException("Should not have received BUFFER_UNDERFLOW during handshake WRAP.");
          } else if (handshakeResult.getStatus() == SSLEngineResult.Status.CLOSED) {
            throw new EOFException();
          }
          log.trace(
              "SSLHandshake NEED_WRAP channelId {}, handshakeResult {}, appReadBuffer pos {}, netReadBuffer pos {}, netWriteBuffer pos {}",
              getConnectionId(), handshakeResult, appReadBuffer.position(), netReadBuffer.position(),
              netWriteBuffer.position());
          //if handshake status is not NEED_UNWRAP or unable to flush netWriteBuffer contents
          //we will break here otherwise we can do need_unwrap in the same call.
          if (handshakeStatus != SSLEngineResult.HandshakeStatus.NEED_UNWRAP || !flush(netWriteBuffer)) {
            key.interestOps(key.interestOps() | SelectionKey.OP_WRITE);
            break;
          }
        case NEED_UNWRAP:
          log.trace(
              "SSLHandshake NEED_UNWRAP channelId {}, appReadBuffer pos {}, netReadBuffer pos {}, netWriteBuffer pos {}",
              getConnectionId(), appReadBuffer.position(), netReadBuffer.position(), netWriteBuffer.position());
          handshakeResult = handshakeUnwrap(read);
          if (handshakeResult.getStatus() == SSLEngineResult.Status.BUFFER_UNDERFLOW) {
            int currentPacketBufferSize = packetBufferSize();
            netReadBuffer = Utils.ensureCapacity(netReadBuffer, currentPacketBufferSize);
            if (netReadBuffer.position() >= currentPacketBufferSize) {
              throw new IllegalStateException("Buffer underflow when there is available data");
            }
          } else if (handshakeResult.getStatus() == SSLEngineResult.Status.BUFFER_OVERFLOW) {
            int currentAppBufferSize = applicationBufferSize();
            appReadBuffer = Utils.ensureCapacity(appReadBuffer, currentAppBufferSize);
            if (appReadBuffer.position() > currentAppBufferSize) {
              throw new IllegalStateException("Buffer underflow when available data size (" + appReadBuffer.position() +
                  ") > packet buffer size (" + currentAppBufferSize + ")");
            }
          } else if (handshakeResult.getStatus() == SSLEngineResult.Status.CLOSED) {
            throw new EOFException("SSL handshake status CLOSED during handshake UNWRAP");
          }
          log.trace(
              "SSLHandshake NEED_UNWRAP channelId {}, handshakeResult {}, appReadBuffer pos {}, netReadBuffer pos {}, netWriteBuffer pos {}",
              getConnectionId(), handshakeResult, appReadBuffer.position(), netReadBuffer.position(),
              netWriteBuffer.position());

          //if handshakeStatus completed than fall-through to finished status.
          //after handshake is finished there is no data left to read/write in socketChannel.
          //so the selector won't invoke this channel if we don't go through the handshakeFinished here.
          if (handshakeStatus != SSLEngineResult.HandshakeStatus.FINISHED) {
            if (handshakeStatus == SSLEngineResult.HandshakeStatus.NEED_WRAP) {
              key.interestOps(key.interestOps() | SelectionKey.OP_WRITE);
            } else if (handshakeStatus == SSLEngineResult.HandshakeStatus.NEED_UNWRAP) {
              key.interestOps(key.interestOps() & ~SelectionKey.OP_WRITE);
            }
            break;
          }
        case FINISHED:
          handshakeFinished();
          break;
        case NOT_HANDSHAKING:
          handshakeFinished();
          break;
        default:
          throw new IllegalStateException(String.format("Unexpected status [%s]", handshakeStatus));
      }
    } catch (SSLException e) {
      handshakeFailure();
      throw e;
    }
  }

  /**
   * Executes the SSLEngine tasks needed.
   * @return HandshakeStatus
   */
  private SSLEngineResult.HandshakeStatus runDelegatedTasks() {
    for (; ; ) {
      Runnable task = delegatedTask();
      if (task == null) {
        break;
      }
      task.run();
    }
    return sslEngine.getHandshakeStatus();
  }

  /**
   * Checks if the handshake status is finished
   * Sets the interestOps for the selectionKey.
   */
  private void handshakeFinished()
      throws IOException {
    // SSLEnginge.getHandshakeStatus is transient and it doesn't record FINISHED status properly.
    // It can move from FINISHED status to NOT_HANDSHAKING after the handshake is completed.
    // Hence we also need to check handshakeResult.getHandshakeStatus() if the handshake finished or not
    if (handshakeResult.getHandshakeStatus() == SSLEngineResult.HandshakeStatus.FINISHED) {
      //we are complete if we have delivered the last package
      handshakeComplete = !netWriteBuffer.hasRemaining();
      //remove OP_WRITE if we are complete, otherwise we still have data to write
      if (!handshakeComplete) {
        key.interestOps(key.interestOps() | SelectionKey.OP_WRITE);
      } else {
        key.interestOps(key.interestOps() & ~SelectionKey.OP_WRITE);
      }

      log.trace(
          "SSLHandshake FINISHED channelId {}, appReadBuffer pos {}, netReadBuffer pos {}, netWriteBuffer pos {} ",
          getConnectionId(), appReadBuffer.position(), netReadBuffer.position(), netWriteBuffer.position());
    } else {
      throw new IOException("NOT_HANDSHAKING during handshake");
    }
  }

  /**
   * Performs the WRAP function
   * @param doWrite boolean
   * @return SSLEngineResult
   * @throws IOException
   */
  private SSLEngineResult handshakeWrap(Boolean doWrite)
      throws IOException {
    log.trace("SSLHandshake handshakeWrap", getConnectionId());
    if (netWriteBuffer.hasRemaining()) {
      throw new IllegalStateException("handshakeWrap called with netWriteBuffer not empty");
    }
    //this should never be called with a network buffer that contains data
    //so we can clear it here.
    netWriteBuffer.clear();
    SSLEngineResult result = sslEngine.wrap(emptyBuf, netWriteBuffer);
    //prepare the results to be written
    netWriteBuffer.flip();
    handshakeStatus = result.getHandshakeStatus();
    if (result.getStatus() == SSLEngineResult.Status.OK
        && result.getHandshakeStatus() == SSLEngineResult.HandshakeStatus.NEED_TASK) {
      handshakeStatus = runDelegatedTasks();
    }

    if (doWrite) {
      flush(netWriteBuffer);
    }
    return result;
  }

  /**
   * Perform handshake unwrap
   * @param doRead boolean
   * @return SSLEngineResult
   * @throws IOException
   */
  private SSLEngineResult handshakeUnwrap(Boolean doRead)
      throws IOException {
    log.trace("SSLHandshake handshakeUnwrap", getConnectionId());
    SSLEngineResult result;
    boolean cont = false;
    int read = 0;
    if (doRead) {
      read = socketChannel.read(netReadBuffer);
      if (read == -1) {
        throw new EOFException("EOF during handshake.");
      }
    }
    do {
      //prepare the buffer with the incoming data
      netReadBuffer.flip();
      result = sslEngine.unwrap(netReadBuffer, appReadBuffer);
      netReadBuffer.compact();
      handshakeStatus = result.getHandshakeStatus();
      if (result.getStatus() == SSLEngineResult.Status.OK
          && result.getHandshakeStatus() == SSLEngineResult.HandshakeStatus.NEED_TASK) {
        handshakeStatus = runDelegatedTasks();
      }
      cont = result.getStatus() == SSLEngineResult.Status.OK
          && handshakeStatus == SSLEngineResult.HandshakeStatus.NEED_UNWRAP;
      log.trace("SSLHandshake handshakeUnwrap: handshakeStatus ", handshakeStatus);
    } while (netReadBuffer.position() != 0 && cont);

    return result;
  }

  @Override
  long read()
      throws IOException {
    if (!hasReceive()) {
      networkReceive = new NetworkReceive(getConnectionId(), new BoundedByteBufferReceive(), time);
    }
    return networkReceive.getReceivedBytes().readFrom(this);
  }

  /**
   * Reads a sequence of bytes from this channel into the given buffer.
   *
   * @param dst The buffer into which bytes are to be transferred
   * @return The number of bytes read, possible zero or -1 if the channel has reached end-of-stream
   * @throws IOException if some other I/O error occurs
   */
  public int read(ByteBuffer dst)
      throws IOException {
    if (closing) {
      return -1;
    }
    int read = 0;
    if (!handshakeComplete) {
      return read;
    }

    //if we have unread decrypted data in appReadBuffer read that into dst buffer.
    if (appReadBuffer.position() > 0) {
      read = readFromAppBuffer(dst);
    }

    if (dst.remaining() > 0) {
      netReadBuffer = Utils.ensureCapacity(netReadBuffer, packetBufferSize());
      if (netReadBuffer.remaining() > 0) {
        int netread = socketChannel.read(netReadBuffer);
        if (netread == 0) {
          return netread;
        } else if (netread < 0) {
          throw new EOFException("EOF during read");
        }
      }
      do {
        netReadBuffer.flip();
        SSLEngineResult unwrapResult = sslEngine.unwrap(netReadBuffer, appReadBuffer);
        netReadBuffer.compact();
        // handle ssl renegotiation.
        if (unwrapResult.getHandshakeStatus() != SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING) {
          log.trace(
              "SSLChannel Read begin renegotiation getConnectionId() {}, appReadBuffer pos {}, netReadBuffer pos {}, netWriteBuffer pos {}",
              getConnectionId(), appReadBuffer.position(), netReadBuffer.position(), netWriteBuffer.position());
          handshake();
          break;
        }

        if (unwrapResult.getStatus() == SSLEngineResult.Status.OK) {
          read += readFromAppBuffer(dst);
        } else if (unwrapResult.getStatus() == SSLEngineResult.Status.BUFFER_OVERFLOW) {
          int currentApplicationBufferSize = applicationBufferSize();
          appReadBuffer = Utils.ensureCapacity(appReadBuffer, currentApplicationBufferSize);
          if (appReadBuffer.position() >= currentApplicationBufferSize) {
            throw new IllegalStateException("Buffer overflow when available data size (" + appReadBuffer.position() +
                ") >= application buffer size (" + currentApplicationBufferSize + ")");
          }

          // appReadBuffer will extended upto currentApplicationBufferSize
          // we need to read the existing content into dst before we can do unwrap again.  If there are no space in dst
          // we can break here.
          if (dst.hasRemaining()) {
            read += readFromAppBuffer(dst);
          } else {
            break;
          }
        } else if (unwrapResult.getStatus() == SSLEngineResult.Status.BUFFER_UNDERFLOW) {
          int currentPacketBufferSize = packetBufferSize();
          netReadBuffer = Utils.ensureCapacity(netReadBuffer, currentPacketBufferSize);
          if (netReadBuffer.position() >= currentPacketBufferSize) {
            throw new IllegalStateException("Buffer underflow when available data size (" + netReadBuffer.position() +
                ") > packet buffer size (" + currentPacketBufferSize + ")");
          }
          break;
        } else if (unwrapResult.getStatus() == SSLEngineResult.Status.CLOSED) {
          throw new EOFException();
        }
      } while (netReadBuffer.position() != 0);
    }
    return read;
  }

  @Override
  boolean write()
      throws IOException {
    Send send = networkSend.getPayload();
    if (send == null) {
      throw new IllegalStateException("Registered for write interest but no response attached to key.");
    }
    send.writeTo(this);
    logger
        .trace("Bytes written to {} using key {}", socketChannel.socket().getRemoteSocketAddress(), getConnectionId());
    return send.isSendComplete();
  }

  /**
   * Writes a sequence of bytes to this channel from the given buffer.
   *
   * @param src The buffer from which bytes are to be retrieved
   * @returns The number of bytes read, possibly zero, or -1 if the channel has reached end-of-stream
   * @throws IOException If some other I/O error occurs
   */
  public int write(ByteBuffer src)
      throws IOException {
    int written = 0;
    if (closing) {
      throw new IllegalStateException("Channel is in closing state");
    }
    if (!handshakeComplete) {
      return written;
    }

    if (!flush(netWriteBuffer)) {
      return written;
    }

    netWriteBuffer.clear();
    SSLEngineResult wrapResult = sslEngine.wrap(src, netWriteBuffer);
    netWriteBuffer.flip();

    //handle ssl renegotiation
    if (wrapResult.getHandshakeStatus() != SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING) {
      handshake();
      return written;
    }

    if (wrapResult.getStatus() == SSLEngineResult.Status.OK) {
      written = wrapResult.bytesConsumed();
      flush(netWriteBuffer);
    } else if (wrapResult.getStatus() == SSLEngineResult.Status.BUFFER_OVERFLOW) {
      int currentPacketBufferSize = packetBufferSize();
      netWriteBuffer = Utils.ensureCapacity(netWriteBuffer, packetBufferSize());
      if (netWriteBuffer.position() >= currentPacketBufferSize) {
        throw new IllegalStateException(
            "SSL BUFFER_OVERFLOW when available data size (" + netWriteBuffer.position() + ") >= network buffer size ("
                + currentPacketBufferSize + ")");
      }
    } else if (wrapResult.getStatus() == SSLEngineResult.Status.BUFFER_UNDERFLOW) {
      throw new IllegalStateException("SSL BUFFER_UNDERFLOW during write");
    } else if (wrapResult.getStatus() == SSLEngineResult.Status.CLOSED) {
      throw new EOFException();
    }
    return written;
  }

  public ByteBuffer getByteBufferFromChannel(Send response)
      throws IOException {
    ByteBuffer buffer;
    // Avoid possibility of overflow for 2GB-4 byte buffer
    if (response.sizeInBytes() > Integer.MAX_VALUE - 4) {
      throw new IllegalStateException(
          "Maximum allowable size for a bounded buffer is " + (Integer.MAX_VALUE - 4) + ".");
    }

    int size = (int) response.sizeInBytes();
    buffer = ByteBuffer.allocate(size + 4);
    buffer.putInt(size);
    WritableByteChannel channel = Channels.newChannel(new ByteBufferOutputStream(buffer));
    response.writeTo(channel);
    buffer.rewind();
    return buffer;
  }

  /**
   * returns delegatedTask for the SSLEngine.
   */
  protected Runnable delegatedTask() {
    return sslEngine.getDelegatedTask();
  }

  /**
   * transfers appReadBuffer contents (decrypted data) into dst bytebuffer
   * @param dst ByteBuffer
   */
  private int readFromAppBuffer(ByteBuffer dst) {
    appReadBuffer.flip();
    int remaining = Math.min(appReadBuffer.remaining(), dst.remaining());
    if (remaining > 0) {
      int limit = appReadBuffer.limit();
      appReadBuffer.limit(appReadBuffer.position() + remaining);
      dst.put(appReadBuffer);
      appReadBuffer.limit(limit);
    }
    appReadBuffer.compact();
    return remaining;
  }

  private int packetBufferSize() {
    return sslEngine.getSession().getPacketBufferSize();
  }

  private int applicationBufferSize() {
    return sslEngine.getSession().getApplicationBufferSize();
  }

  private void handshakeFailure() {
    //Release all resources such as internal buffers that SSLEngine is managing
    sslEngine.closeOutbound();
    try {
      sslEngine.closeInbound();
    } catch (SSLException e) {
      log.debug("SSLEngine.closeInBound() raised an exception.", e);
    }
  }

  /*@Override
  public boolean isMute() {
    return  key.isValid() && (key.interestOps() & SelectionKey.OP_READ) == 0;
  } */
}
