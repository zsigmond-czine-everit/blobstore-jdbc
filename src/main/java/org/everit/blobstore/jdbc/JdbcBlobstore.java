/*
 * Copyright (C) 2011 Everit Kft. (http://www.everit.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.everit.blobstore.jdbc;

import java.sql.Blob;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Iterator;
import java.util.List;

import javax.sql.DataSource;

import org.everit.blobstore.BlobAccessor;
import org.everit.blobstore.BlobReader;
import org.everit.blobstore.Blobstore;
import org.everit.blobstore.NoSuchBlobException;
import org.everit.blobstore.jdbc.internal.BlobChannel;
import org.everit.blobstore.jdbc.internal.BytesBlobChannel;
import org.everit.blobstore.jdbc.internal.ConnectedBlob;
import org.everit.blobstore.jdbc.internal.DatabaseTypeEnum;
import org.everit.blobstore.jdbc.internal.EmptyReadOnlyBlob;
import org.everit.blobstore.jdbc.internal.JdbcBlobAccessor;
import org.everit.blobstore.jdbc.internal.JdbcBlobReader;
import org.everit.blobstore.jdbc.internal.StreamBlobChannel;
import org.everit.blobstore.jdbc.schema.qdsl.QBlobstoreBlob;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.UnmodifiableIterator;
import com.querydsl.core.Tuple;
import com.querydsl.core.types.Expression;
import com.querydsl.core.types.dsl.Expressions;
import com.querydsl.sql.Configuration;
import com.querydsl.sql.DerbyTemplates;
import com.querydsl.sql.HSQLDBTemplates;
import com.querydsl.sql.MySQLTemplates;
import com.querydsl.sql.OracleTemplates;
import com.querydsl.sql.PostgreSQLTemplates;
import com.querydsl.sql.SQLBindings;
import com.querydsl.sql.SQLExpressions;
import com.querydsl.sql.SQLQuery;
import com.querydsl.sql.SQLServerTemplates;
import com.querydsl.sql.SQLTemplates;
import com.querydsl.sql.SQLTemplatesRegistry;
import com.querydsl.sql.dml.SQLDeleteClause;
import com.querydsl.sql.dml.SQLInsertClause;

/**
 * JDBC based implementation of Blobstore.
 *
 */
public class JdbcBlobstore implements Blobstore {

  protected final BlobAccessMode blobAccessMode;

  protected final Expression<?> blobSelectionExpression;

  protected final DataSource dataSource;

  protected final Expression<Blob> emptyBlobExpression;

  private DatabaseTypeEnum guessedDatabaseType;

  protected final Configuration querydslConfiguration;

  protected final boolean updateSQLAfterBlobContentManipulationNecessary;

  /**
   * Constructor that is the same as calling
   * {@link #JdbcBlobstore(DataSource, JdbcBlobstoreConfiguration)} with <code>null</code> as the
   * configuration.
   *
   * @param dataSource
   *          The datasource that will be used to store and read blobs.
   */
  public JdbcBlobstore(final DataSource dataSource) {
    this(dataSource, null);
  }

  /**
   * Constructor.
   *
   * @param dataSource
   *          The datasource that will be used to store and read blobs.
   * @param configuration
   *          The configuration how the blobs should be written and read. Different database engines
   *          need different settings. If <code>null</code> the configuration is guessed
   *          automatically.
   */
  public JdbcBlobstore(final DataSource dataSource,
      final JdbcBlobstoreConfiguration configuration) {
    if (dataSource == null) {
      throw new IllegalArgumentException("DataSource must be defined");
    }
    this.dataSource = dataSource;
    this.querydslConfiguration = resolveQuerydslConfiguration(configuration);
    this.emptyBlobExpression = resolveEmptyBlobExpression(configuration);
    this.updateSQLAfterBlobContentManipulationNecessary =
        resolveUpdateSQLAfterBlobContentManipulationNecessary(
            configuration);
    this.blobSelectionExpression = resolveBlobSelectionExpression(configuration);
    this.blobAccessMode = resolveBlobAccessMode(configuration);
  }

  /**
   * This method is called when an {@link Error} or {@link RuntimeException} occured and the
   * connection should be closed.
   *
   * @param closeable
   *          The closeable instance that should be closed.
   * @param e
   *          The {@link Error} or {@link RuntimeException} that occured.
   */
  protected void closeCloseableDueToThrowable(final AutoCloseable closeable, final Throwable e) {
    try {
      closeable.close();
    } catch (Throwable th) {
      e.addSuppressed(th);
    }
    if (e instanceof Error) {
      throw (Error) e;
    } else if (e instanceof RuntimeException) {
      throw (RuntimeException) e;
    }
  }

  private void closeResultSet(final ResultSet resultSet,
      final boolean closeResultSetIsNotNecessaryAsItWillBeClosedByStatement) {

    if (closeResultSetIsNotNecessaryAsItWillBeClosedByStatement) {
      try {
        resultSet.close();
      } catch (SQLException e) {
        // TODO
        throw new RuntimeException(e);
      }
    }
  }

  /**
   * Gets a blob with its current version from the database. If an exception or error occures, this
   * method closes the database connection.
   *
   * @param blobId
   *          The id of the blob.
   * @param forUpdate
   *          Whether a pessimistic lock should be applied on the blob or not.
   * @param connection
   *          The database connection.
   * @return The blob and its current version.
   */
  protected ConnectedBlob connectBlob(final long blobId, final boolean forUpdate,
      final Connection connection) {
    QBlobstoreBlob qBlob = QBlobstoreBlob.blobstoreBlob;
    try {
      SQLQuery<Tuple> query = new SQLQuery<>(connection, querydslConfiguration)
          .select(qBlob.blobId, qBlob.version_.as("version_"),
              Expressions.as(blobSelectionExpression, "blob_")) // CS_DISABLE_LINE_LENGTH
                                                                // qBlob.blob_.as("blob_")
          .from(qBlob)
          .where(qBlob.blobId.eq(blobId));
      if (forUpdate) {
        query.forUpdate();
      }
      SQLBindings sqlBindings = query.getSQL();

      String sql = sqlBindings.getSQL();
      PreparedStatement preparedStatement = connection.prepareStatement(sql);
      ImmutableList<Object> bindings = sqlBindings.getBindings();
      int i = 0;
      UnmodifiableIterator<Object> iterator = bindings.iterator();
      while (iterator.hasNext()) {
        Object binding = iterator.next();
        i++;
        preparedStatement.setObject(i, binding);
      }

      long version;
      Blob blob;

      ResultSet resultSet = preparedStatement.executeQuery();
      try {
        if (!resultSet.next()) {
          throw new NoSuchBlobException(blobId);
        }

        version = resultSet.getLong("version_");
        blob = resultSet.getBlob("blob_");
      } finally {
        final boolean closeResultSetIsNotNecessaryAsItWillBeClosedByStatement = false;
        closeResultSet(resultSet, closeResultSetIsNotNecessaryAsItWillBeClosedByStatement);
      }

      BlobChannel blobChannel;
      if (blobAccessMode == BlobAccessMode.BYTES) {
        blobChannel = new BytesBlobChannel(blob);
      } else {
        blobChannel = new StreamBlobChannel(blob);
      }
      return new ConnectedBlob(blobId, blobChannel, version, preparedStatement);

    } catch (SQLException | RuntimeException | Error e) {
      closeCloseableDueToThrowable(connection, e);
      // TODO throw unchecked sql exception
      throw new RuntimeException(e);
    }
  }

  @Override
  public BlobAccessor createBlob() {
    Connection connection = createDatabaseConnection();

    QBlobstoreBlob qBlob = QBlobstoreBlob.blobstoreBlob;
    Long blobId;
    try {
      blobId = new SQLInsertClause(connection, querydslConfiguration, qBlob)
          .set(qBlob.version_, 0L).set(qBlob.blob_, emptyBlobExpression)
          .executeWithKey(qBlob.blobId);
    } catch (RuntimeException | Error e) {
      closeCloseableDueToThrowable(connection, e);
      throw new RuntimeException(e);
    }

    return updateBlob(blobId, connection, false);
  }

  /**
   * Creates a new database connection.
   *
   * @return The new database connection.
   */
  protected Connection createDatabaseConnection() {
    Connection connection;
    try {
      connection = dataSource.getConnection();
    } catch (SQLException e) {
      // TODO Auto-generated catch block
      throw new RuntimeException(e);
    }
    return connection;
  }

  @Override
  public void deleteBlob(final long blobId) {
    try (Connection connection = dataSource.getConnection()) {
      QBlobstoreBlob qBlob = QBlobstoreBlob.blobstoreBlob;
      long rowNum = new SQLDeleteClause(connection, querydslConfiguration, qBlob)
          .where(qBlob.blobId.eq(blobId))
          .execute();
      if (rowNum == 0) {
        throw new NoSuchBlobException(blobId);
      }
    } catch (SQLException e) {
      // TODO Auto-generated catch block
      throw new RuntimeException(e);
    }
  }

  /**
   * Guess the database type based on the metadata information of the {@link Connection} that is
   * provided by the specified {@link DataSource}.
   *
   * @return The guessed type of the database.
   */
  protected DatabaseTypeEnum guessDatabaseType() {
    if (this.guessedDatabaseType != null) {
      return this.guessedDatabaseType;
    }
    String databaseProductName;

    try (Connection connection = dataSource.getConnection()) {
      databaseProductName = connection.getMetaData().getDatabaseProductName();
    } catch (SQLException e) {
      // TODO Auto-generated catch block
      throw new RuntimeException(e);
    }

    DatabaseTypeEnum[] databaseTypes = DatabaseTypeEnum.values();
    for (int i = 0; i < databaseTypes.length && guessedDatabaseType == null; i++) {
      DatabaseTypeEnum databaseType = databaseTypes[i];
      List<String> productNames = databaseType.getDatabaseProductNames();
      Iterator<String> iterator = productNames.iterator();

      while (iterator.hasNext() && guessedDatabaseType == null) {
        String productName = iterator.next();
        if (productName != null && productName.equalsIgnoreCase(databaseProductName)) {
          guessedDatabaseType = databaseType;
        }
      }
    }
    if (guessedDatabaseType == null) {
      guessedDatabaseType = DatabaseTypeEnum.UNKNOWN;
    }

    return guessedDatabaseType;
  }

  @Override
  public BlobReader readBlob(final long blobId) {
    Connection connection = createDatabaseConnection();
    ConnectedBlob connectedBlob = connectBlob(blobId, false, connection);
    return new JdbcBlobReader(connectedBlob, connection);
  }

  @Override
  public BlobReader readBlobForUpdate(final long blobId) {
    Connection connection = createDatabaseConnection();
    ConnectedBlob connectedBlob = connectBlob(blobId, true, connection);
    return new JdbcBlobReader(connectedBlob, connection);
  }

  /**
   * Resolving the access mode how the database should be accessed.
   *
   * @param configuration
   *          The configuration that might contain pre-defined access mode.
   * @return The database access mode.
   */
  protected BlobAccessMode resolveBlobAccessMode(final JdbcBlobstoreConfiguration configuration) {

    if (configuration != null && configuration.blobAccessMode != null) {
      return blobAccessMode;
    }

    BlobAccessMode result;
    DatabaseTypeEnum databaseType = guessDatabaseType();
    switch (databaseType) {
      case DERBY:
        result = BlobAccessMode.STREAM;
        break;
      case HSQLDB:
        result = BlobAccessMode.BYTES;
        break;
      case MYSQL:
        result = resolveMySqlBlobAccessModeFromDatabaseMetadata();

        break;
      case ORACLE:
        result = BlobAccessMode.STREAM;
        break;
      case POSTGRESQL:
        result = BlobAccessMode.STREAM;
        break;
      case SQLSERVER:
        result = BlobAccessMode.BYTES;
        break;
      default:
        result = BlobAccessMode.BYTES;
        break;
    }

    return result;
  }

  /**
   * Resolving the selection expression of the blob based on the configuration or if it is not
   * available in the configuration, based on the database type. This is necessary as for example if
   * MySQL is used with remote locator mode, instead of the blob column name, a string constant has
   * to be used that contains the column name in the SQL expression.
   *
   * @param configuration
   *          The configuration passed to this blobstore.
   * @return The selection expression of the blob.
   */
  protected Expression<?> resolveBlobSelectionExpression(
      final JdbcBlobstoreConfiguration configuration) {

    if (configuration != null && configuration.blobSelectionExpression != null) {
      return configuration.blobSelectionExpression;
    }

    DatabaseTypeEnum databaseType = guessDatabaseType();
    if (databaseType != DatabaseTypeEnum.MYSQL) {
      return QBlobstoreBlob.blobstoreBlob.blob_;
    }

    try (Connection connection = dataSource.getConnection()) {
      if (!connection.getMetaData().locatorsUpdateCopy()) {
        return Expressions.constant(QBlobstoreBlob.blobstoreBlob.blob_.getMetadata().getName());
      } else {
        return QBlobstoreBlob.blobstoreBlob.blob_;
      }
    } catch (SQLException e) {
      // TODO Auto-generated catch block
      throw new RuntimeException(e);
    }
  }

  /**
   * Resolves the expression of an empty blob based on the configuration or if it is not available
   * in the configuration, based on the database type.
   *
   * @param configuration
   *          The configuration that was passed to the blobstore.
   * @return The expression of an empty blob that can be used in an insert statement.
   */
  protected Expression<Blob> resolveEmptyBlobExpression(
      final JdbcBlobstoreConfiguration configuration) {

    if (configuration != null && configuration.emptyBlobExpression != null) {
      return configuration.emptyBlobExpression;
    }

    DatabaseTypeEnum databaseType = guessDatabaseType();

    if (databaseType == DatabaseTypeEnum.ORACLE) {
      return SQLExpressions.relationalFunctionCall(Blob.class, "empty_blob");
    } else {
      return Expressions.constant(new EmptyReadOnlyBlob());
    }
  }

  private BlobAccessMode resolveMySqlBlobAccessModeFromDatabaseMetadata() {
    BlobAccessMode result;
    boolean locatorsUpdateCopy;

    try (Connection connection = dataSource.getConnection()) {
      locatorsUpdateCopy = connection.getMetaData().locatorsUpdateCopy();
    } catch (SQLException e) {
      // TODO Auto-generated catch block
      throw new RuntimeException(e);
    }
    if (locatorsUpdateCopy) {
      result = BlobAccessMode.STREAM;
    } else {
      result = BlobAccessMode.BYTES;
    }
    return result;
  }

  /**
   * Resolves the Querydsl {@link Configuration} from the {@link JdbcBlobstoreConfiguration} or if
   * it is not available there, based on the database type.
   *
   * @param blobstoreConfiguration
   *          The configuration that was passed to the blobstore.
   * @return The querydsl configuration.
   */
  protected Configuration resolveQuerydslConfiguration(
      final JdbcBlobstoreConfiguration blobstoreConfiguration) {
    if (blobstoreConfiguration != null && blobstoreConfiguration.querydslConfiguration != null) {
      return querydslConfiguration;
    }

    DatabaseTypeEnum databaseType = guessDatabaseType();

    SQLTemplates templates;
    switch (databaseType) {
      case DERBY:
        templates = new DerbyTemplates(true);
        break;
      case HSQLDB:
        templates = new HSQLDBTemplates(true);
        break;
      case MYSQL:
        templates = new MySQLTemplates(true);
        break;
      case ORACLE:
        templates = new OracleTemplates(true);
        break;
      case POSTGRESQL:
        templates = new PostgreSQLTemplates(true);
        break;
      case SQLSERVER:
        templates = new SQLServerTemplates(true);
        break;
      default:
        try (Connection connection = dataSource.getConnection()) {
          templates = new SQLTemplatesRegistry().getTemplates(connection.getMetaData());

        } catch (SQLException e) {
          // TODO Auto-generated catch block
          throw new RuntimeException(e);
        }
    }
    return new Configuration(templates);

  }

  /**
   * Resolves whether an update SQL statement is necessary after manipulating the content of a
   * selected blob or not.
   *
   * @param configuration
   *          The configuration that was passed to the blobstore.
   * @return Whether update SQL is necessary after blob manipulation or not.
   */
  protected boolean resolveUpdateSQLAfterBlobContentManipulationNecessary(
      final JdbcBlobstoreConfiguration configuration) {

    if (configuration != null
        && configuration.updateSQLAfterBlobContentManipulationNecessary != null) {
      return configuration.updateSQLAfterBlobContentManipulationNecessary;
    }

    DatabaseTypeEnum databaseType = guessDatabaseType();
    if (databaseType == DatabaseTypeEnum.HSQLDB) {
      return true;
    }

    try (Connection connection = dataSource.getConnection()) {
      return connection.getMetaData().locatorsUpdateCopy();
    } catch (SQLException e) {
      // TODO Auto-generated catch block
      throw new RuntimeException(e);
    }
  }

  @Override
  public BlobAccessor updateBlob(final long blobId) {
    Connection connection = createDatabaseConnection();
    return updateBlob(blobId, connection, true);
  }

  /**
   * Creates a new {@link BlobAccessor} or closes the connection if an {@link Exception} occurs.
   *
   * @param blobId
   *          The id of the Blob that the {@link BlobAccessor} will belong to.
   * @param connection
   *          The connection of the database.
   * @return An accessor to modify the blob content.
   */
  protected BlobAccessor updateBlob(final long blobId, final Connection connection,
      final boolean incrementVersion) {
    ConnectedBlob connectedBlob = connectBlob(blobId, true, connection);
    return new JdbcBlobAccessor(connectedBlob, connection, querydslConfiguration,
        updateSQLAfterBlobContentManipulationNecessary, incrementVersion);
  }

}
