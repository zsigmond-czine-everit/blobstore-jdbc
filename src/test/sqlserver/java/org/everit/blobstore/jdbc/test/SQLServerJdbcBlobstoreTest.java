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
package org.everit.blobstore.jdbc.test;

import javax.sql.XADataSource;

import org.junit.Ignore;
import org.junit.Test;

import com.microsoft.sqlserver.jdbc.SQLServerXADataSource;

public class SQLServerJdbcBlobstoreTest extends AbstractJdbcBlobstoreTest {

  @Override
  protected XADataSource createXADataSource(final DatabaseAccessParametersDTO parameters) {
    SQLServerXADataSource xaDataSource = new SQLServerXADataSource();

    String url = "jdbc:sqlserver://" + parameters.host;
    if (parameters.port != null) {
      url += ":" + parameters.port;
    }

    if (parameters.connectionAttributes != null) {
      url += "?" + parameters.connectionAttributes;
    }

    xaDataSource.setURL(url);

    if (parameters.database != null) {
      xaDataSource.setDatabaseName(parameters.database);
    }

    if (parameters.user != null) {
      xaDataSource.setUser(parameters.user);
    }

    if (parameters.password != null) {
      xaDataSource.setPassword(parameters.password);
    }
    return xaDataSource;
  }

  @Override
  protected DatabaseTestAttributesDTO getDatabaseTestAttributes() {
    DatabaseTestAttributesDTO result = new DatabaseTestAttributesDTO();
    result.dbName = "sqlserver";
    result.enabledByDefault = false;

    DatabaseAccessParametersDTO accessParameters = new DatabaseAccessParametersDTO();
    accessParameters.host = "localhost";
    accessParameters.database = "test";
    accessParameters.user = "test";
    accessParameters.password = "test";

    result.defaultAccessParameters = accessParameters;
    return result;
  }

  @Test
  @Ignore
  @Override
  public void testParallelBlobManipulationWithTwoTransactions() {
    super.testParallelBlobManipulationWithTwoTransactions();
  }

}
