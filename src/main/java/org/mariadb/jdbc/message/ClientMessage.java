// SPDX-License-Identifier: LGPL-2.1-or-later
// Copyright (c) 2012-2014 Monty Program Ab
// Copyright (c) 2015-2021 MariaDB Corporation Ab

package org.mariadb.jdbc.message;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.concurrent.locks.ReentrantLock;
import org.mariadb.jdbc.BasePreparedStatement;
import org.mariadb.jdbc.Statement;
import org.mariadb.jdbc.client.Column;
import org.mariadb.jdbc.client.Completion;
import org.mariadb.jdbc.client.Context;
import org.mariadb.jdbc.client.ReadableByteBuf;
import org.mariadb.jdbc.client.result.CompleteResult;
import org.mariadb.jdbc.client.result.StreamingResult;
import org.mariadb.jdbc.client.result.UpdatableResult;
import org.mariadb.jdbc.client.socket.Reader;
import org.mariadb.jdbc.client.socket.Writer;
import org.mariadb.jdbc.export.ExceptionFactory;
import org.mariadb.jdbc.message.server.ColumnDefinitionPacket;
import org.mariadb.jdbc.message.server.ErrorPacket;
import org.mariadb.jdbc.message.server.OkPacket;
import org.mariadb.jdbc.util.constants.ServerStatus;

public interface ClientMessage {

  /**
   * Encode client message to socket.
   *
   * @param writer socket writer
   * @param context connection context
   * @return number of client message written
   * @throws IOException if socket error occur
   * @throws SQLException if any issue occurs
   */
  int encode(Writer writer, Context context) throws IOException, SQLException;

  /**
   * Number of parameter rows, and so expected return length
   *
   * @return batch update length
   */
  default int batchUpdateLength() {
    return 0;
  }

  /**
   * Message description
   *
   * @return description
   */
  default String description() {
    return null;
  }

  /**
   * Are return value encoded in binary protocol
   *
   * @return use binary protocol
   */
  default boolean binaryProtocol() {
    return false;
  }

  /**
   * Can skip metadata
   *
   * @return can skip metadata
   */
  default boolean canSkipMeta() {
    return false;
  }

  /**
   * default packet resultset parser
   *
   * @param stmt caller
   * @param fetchSize fetch size
   * @param maxRows maximum number of rows
   * @param resultSetConcurrency resultset concurrency
   * @param resultSetType resultset type
   * @param closeOnCompletion must close caller on result parsing end
   * @param reader packet reader
   * @param writer packet writer
   * @param context connection context
   * @param exceptionFactory connection exception factory
   * @param lock thread safe locks
   * @param traceEnable is loggind trace enable
   * @return results
   * @throws IOException if any socket error occurs
   * @throws SQLException for other kind of errors
   */
  default Completion readPacket(
      Statement stmt,
      int fetchSize,
      long maxRows,
      int resultSetConcurrency,
      int resultSetType,
      boolean closeOnCompletion,
      Reader reader,
      Writer writer,
      Context context,
      ExceptionFactory exceptionFactory,
      ReentrantLock lock,
      boolean traceEnable)
      throws IOException, SQLException {

    ReadableByteBuf buf = reader.readPacket(true, traceEnable);

    switch (buf.getUnsignedByte()) {

        // *********************************************************************************************************
        // * OK response
        // *********************************************************************************************************
      case 0x00:
        return new OkPacket(buf, context);

        // *********************************************************************************************************
        // * ERROR response
        // *********************************************************************************************************
      case 0xff:
        // force current status to in transaction to ensure rollback/commit, since command may
        // have issue a transaction
        ErrorPacket errorPacket = new ErrorPacket(buf, context);
        throw exceptionFactory
            .withSql(this.description())
            .create(
                errorPacket.getMessage(), errorPacket.getSqlState(), errorPacket.getErrorCode());
      case 0xfb:
        buf.skip(1); // skip header
        String fileName = buf.readStringNullEnd();
        try (InputStream is = new FileInputStream(fileName)) {

          byte[] fileBuf = new byte[8192];
          int len;
          while ((len = is.read(fileBuf)) > 0) {
            writer.writeBytes(fileBuf, 0, len);
            writer.flush();
          }
          writer.writeEmptyPacket();
          return readPacket(
              stmt,
              fetchSize,
              maxRows,
              resultSetConcurrency,
              resultSetType,
              closeOnCompletion,
              reader,
              writer,
              context,
              exceptionFactory,
              lock,
              traceEnable);

        } catch (FileNotFoundException f) {
          writer.writeEmptyPacket();
          readPacket(
              stmt,
              fetchSize,
              maxRows,
              resultSetConcurrency,
              resultSetType,
              closeOnCompletion,
              reader,
              writer,
              context,
              exceptionFactory,
              lock,
              traceEnable);
          throw exceptionFactory
              .withSql(this.description())
              .create("Could not send file : " + f.getMessage(), "HY000", f);
        }

        // *********************************************************************************************************
        // * ResultSet
        // *********************************************************************************************************
      default:
        int fieldCount = buf.readLengthNotNull();

        Column[] ci;
        boolean canSkipMeta = context.canSkipMeta() && this.canSkipMeta();
        boolean skipMeta = canSkipMeta ? buf.readByte() == 0 : false;
        if (canSkipMeta && skipMeta) {
          ci = ((BasePreparedStatement) stmt).getMeta();
        } else {
          // read columns information's
          ci = new Column[fieldCount];
          for (int i = 0; i < fieldCount; i++) {
            ci[i] =
                new ColumnDefinitionPacket(
                    reader.readPacket(false, traceEnable), context.isExtendedInfo());
          }
        }
        if (canSkipMeta && !skipMeta) ((BasePreparedStatement) stmt).updateMeta(ci);

        // intermediate EOF
        if (!context.isEofDeprecated()) {
          reader.readPacket(true, traceEnable);
        }

        // read resultSet
        if (resultSetConcurrency == ResultSet.CONCUR_UPDATABLE) {
          return new UpdatableResult(
              stmt,
              binaryProtocol(),
              maxRows,
              ci,
              reader,
              context,
              resultSetType,
              closeOnCompletion,
              traceEnable);
        }

        if (fetchSize != 0) {
          if ((context.getServerStatus() & ServerStatus.MORE_RESULTS_EXISTS) > 0) {
            context.setServerStatus(context.getServerStatus() - ServerStatus.MORE_RESULTS_EXISTS);
          }

          return new StreamingResult(
              stmt,
              binaryProtocol(),
              maxRows,
              ci,
              reader,
              context,
              fetchSize,
              lock,
              resultSetType,
              closeOnCompletion,
              traceEnable);
        } else {
          return new CompleteResult(
              stmt,
              binaryProtocol(),
              maxRows,
              ci,
              reader,
              context,
              resultSetType,
              closeOnCompletion,
              traceEnable);
        }
    }
  }
}
