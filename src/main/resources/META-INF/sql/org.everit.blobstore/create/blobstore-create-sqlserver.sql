--
-- Copyright (C) 2011 Everit Kft. (http://www.everit.org)
--
-- Licensed under the Apache License, Version 2.0 (the "License");
-- you may not use this file except in compliance with the License.
-- You may obtain a copy of the License at
--
--         http://www.apache.org/licenses/LICENSE-2.0
--
-- Unless required by applicable law or agreed to in writing, software
-- distributed under the License is distributed on an "AS IS" BASIS,
-- WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
-- See the License for the specific language governing permissions and
-- limitations under the License.
--

-- Before running this SQL script, you might need to initialize liqiubase tables, too

-- Changeset META-INF/liquibase/org.everit.blobstore.jdbc.changelog.xml::1.0.0::everit
CREATE TABLE [BLOBSTORE_BLOB] ([BLOB_ID] [bigint] IDENTITY (1, 1) NOT NULL, [VERSION_] [bigint] NOT NULL, [BLOB_] [varbinary](MAX) NOT NULL, CONSTRAINT [PK_BLOBSTORE_BLOB] PRIMARY KEY ([BLOB_ID]))
GO

INSERT INTO [DATABASECHANGELOG] ([ID], [AUTHOR], [FILENAME], [DATEEXECUTED], [ORDEREXECUTED], [MD5SUM], [DESCRIPTION], [COMMENTS], [EXECTYPE], [CONTEXTS], [LABELS], [LIQUIBASE]) VALUES ('1.0.0', 'everit', 'META-INF/liquibase/org.everit.blobstore.jdbc.changelog.xml', GETDATE(), 1, '7:7220e46e283e66cea6aa3d8573445b15', 'createTable, createProcedure, sql (x2)', '', 'EXECUTED', NULL, NULL, '3.4.0')
GO
