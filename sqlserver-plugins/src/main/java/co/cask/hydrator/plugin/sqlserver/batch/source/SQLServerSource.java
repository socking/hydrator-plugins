/*
 * Copyright © 2015 Cask Data, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package co.cask.hydrator.plugin.sqlserver.batch.source;



import co.cask.cdap.api.annotation.Description;
import co.cask.cdap.api.annotation.Name;
import co.cask.cdap.api.annotation.Plugin;
import co.cask.cdap.api.data.format.StructuredRecord;
import co.cask.cdap.api.dataset.lib.KeyValue;
import co.cask.cdap.api.plugin.PluginConfig;
import co.cask.cdap.api.plugin.PluginProperties;
import co.cask.cdap.etl.api.Emitter;
import co.cask.cdap.etl.api.PipelineConfigurer;
import co.cask.cdap.etl.api.batch.BatchRuntimeContext;
import co.cask.cdap.etl.api.batch.BatchSource;
import co.cask.cdap.etl.api.batch.BatchSourceContext;
import co.cask.cdap.etl.common.DBConfig;
import co.cask.cdap.etl.common.DBRecord;
import co.cask.cdap.etl.common.DBUtils;
import co.cask.cdap.etl.common.ETLDBInputFormat;
import co.cask.cdap.etl.common.FieldCase;
import co.cask.cdap.etl.common.StructuredRecordUtils;
import com.google.common.base.Preconditions;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.lib.db.DBConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Driver;

/**
 * Batch source to read from a Teradata table
 */
@Plugin(type = "batchsource")
@Name("SQLServer")
@Description("Reads from a Teradata table using a configurable SQL query." +
  " Outputs one record for each row returned by the query.")
public class SQLServerSource extends BatchSource<LongWritable, DBRecord, StructuredRecord> {
  private static final Logger LOG = LoggerFactory.getLogger(SQLServerSource.class);

  public static final String BOUNDING_QUERY = "boundingQuery";
  public static final String SPLIT_BY = "splitBy";
  public static final String COLUMN_NAME_CASE = "columnNameCase";
  private static final String IMPORT_QUERY_DESCRIPTION = "The SELECT query to use to import data from the specified " +
    "table. You can specify an arbitrary number of columns to import, or import all columns using *. The Query should" +
    "contain the '$CONDITIONS' string. For example, 'SELECT * FROM table WHERE $CONDITIONS'. The '$CONDITIONS' string" +
    "will be replaced by 'splitBy' field limits specified by the bounding query.";
  private static final String BOUNDING_QUERY_DESCRIPTION = "Bounding Query should return the min and max of the " +
    "values of the 'splitBy' field. For example, 'SELECT MIN(id),MAX(id) FROM table'";
  private static final String SPLIT_FIELD_DESCRIPTION = "Field Name which will be used to generate splits.";
  private static final String COLUMN_NAME_CASE_DESCRIPTION = "Sets the case of the column names returned from " +
    "the query. Possible options are upper or lower. By default or for any other input, the column names are not " +
    "modified and the names returned from the database are used as-is. Note that setting this property provides " +
    "predictability of column name cases across different databases but might result in column name conflicts " +
    "if multiple column names are the same when the case is ignored.";


  private final SQLServerConfig sourceConfig;
  private Class<? extends Driver> driverClass;

  public SQLServerSource(SQLServerConfig sourceConfig) {
    this.sourceConfig = sourceConfig;
  }

  @Override
  public void configurePipeline(PipelineConfigurer pipelineConfigurer) {
    Preconditions.checkArgument(sourceConfig.user != null || sourceConfig.password == null,
                                "dbUser is null. Please provide both user name and password if the database " +
                                  "requires authentication. If not, please remove dbPassword and retry.");
    Preconditions.checkArgument(sourceConfig.user == null || sourceConfig.password != null,
                                "dbPassword is null. Please provide both user name and password if the database " +
                                  "requires authentication. If not, please remove dbUser and retry.");
    String jdbcPluginId = String.format("%s.%s.%s", "source", sourceConfig.jdbcPluginType, sourceConfig.jdbcPluginName);
    Class jdbcDriverClass = pipelineConfigurer.usePluginClass(sourceConfig.jdbcPluginType, sourceConfig.jdbcPluginName,
                                                              jdbcPluginId, PluginProperties.builder().build());
    Preconditions.checkArgument(jdbcDriverClass != null, "Unable to load JDBC Driver class for plugin name \'%s\'. " +
      "Please make sure that the plugin \'%s\' of type \'%s\' containing the driver has been installed correctly.",
                                sourceConfig.jdbcPluginName, sourceConfig.jdbcPluginName, sourceConfig.jdbcPluginType);
    Preconditions.checkArgument(sourceConfig.importQuery.contains("$CONDITIONS"), "Import Query %s must contain the " +
      "string '$CONDITIONS'.", sourceConfig.importQuery);
  }

  @Override
  public void prepareRun(BatchSourceContext context) throws Exception {
    LOG.debug("pluginType = {}; pluginName = {}; connectionString = {}; importQuery = {}; " +
                "boundingQuery = {}",
              sourceConfig.jdbcPluginType, sourceConfig.jdbcPluginName,
              sourceConfig.connectionString, sourceConfig.importQuery, sourceConfig.boundingQuery);

    Job job = context.getHadoopJob();
    Configuration hConf = job.getConfiguration();

    // Load the plugin class to make sure it is available.
    Class<? extends Driver> driverClass = context.loadPluginClass(getJDBCPluginId());
    if (sourceConfig.user == null && sourceConfig.password == null) {
      DBConfiguration.configureDB(hConf, driverClass.getName(), sourceConfig.connectionString);
    } else {
      DBConfiguration.configureDB(hConf, driverClass.getName(), sourceConfig.connectionString,
                                  sourceConfig.user, sourceConfig.password);
    }
    DataDrivenETLDBInputFormat.setInput(job, DBRecord.class, sourceConfig.importQuery, sourceConfig.boundingQuery);
    job.setInputFormatClass(DataDrivenETLDBInputFormat.class);
    job.getConfiguration().set(DBConfiguration.INPUT_ORDER_BY_PROPERTY, sourceConfig.splitBy);
  }

  @Override
  public void initialize(BatchRuntimeContext context) throws Exception {
    super.initialize(context);
    driverClass = context.loadPluginClass(getJDBCPluginId());
  }

  @Override
  public void transform(KeyValue<LongWritable, DBRecord> input, Emitter<StructuredRecord> emitter) throws Exception {
    emitter.emit(StructuredRecordUtils.convertCase(
      input.getValue().getRecord(), FieldCase.toFieldCase(sourceConfig.columnNameCase)));
  }

  @Override
  public void destroy() {
    DBUtils.cleanup(driverClass);
  }

  private String getJDBCPluginId() {
    return String.format("%s.%s.%s", "source", sourceConfig.jdbcPluginType, sourceConfig.jdbcPluginName);
  }

  /**
   * {@link PluginConfig} for {@link SQLServerSource}
   */
  public static class SQLServerConfig extends DBConfig {
    @Description(IMPORT_QUERY_DESCRIPTION)
    String importQuery;

    @Description(BOUNDING_QUERY_DESCRIPTION)
    String boundingQuery;

    @Description(SPLIT_FIELD_DESCRIPTION)
    String splitBy;

    @Description(COLUMN_NAME_CASE_DESCRIPTION)
    String columnNameCase;
  }
}