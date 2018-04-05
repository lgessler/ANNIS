/*
 * Copyright 2009-2011 Collaborative Research Centre SFB 632
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package annis.administration;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileFilter;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URISyntaxException;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

import javax.sql.DataSource;

import com.google.common.base.Charsets;
import com.google.common.base.Preconditions;
import com.google.common.base.Splitter;
import com.google.common.collect.Multimap;
import com.google.common.collect.TreeMultimap;
import com.google.common.io.Files;

import org.apache.commons.dbcp2.BasicDataSource;
import org.apache.commons.dbcp2.DelegatingConnection;
import org.apache.commons.dbutils.handlers.ColumnListHandler;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.filefilter.DirectoryFileFilter;
import org.apache.commons.io.filefilter.FileFileFilter;
import org.apache.commons.lang3.StringUtils;
import org.codehaus.jackson.map.AnnotationIntrospector;
import org.codehaus.jackson.map.ObjectMapper;
import org.codehaus.jackson.map.SerializationConfig;
import org.codehaus.jackson.xc.JaxbAnnotationIntrospector;
import org.postgresql.PGConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.WritableResource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.simple.ParameterizedSingleColumnRowMapper;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import annis.dao.autogenqueries.QueriesGenerator;
import annis.exceptions.AnnisException;
import annis.security.UserConfig;
import annis.tabledefs.ANNISFormatVersion;
import annis.tabledefs.Column;
import annis.tabledefs.Table;

/**
 *
 */
public class AdministrationDao extends AbstractAdminstrationDao {

  private static final Logger log = LoggerFactory.getLogger(AdministrationDao.class);

  // if this is true, the staging area is not deleted
  private boolean temporaryStagingArea;

  private DeleteCorpusDao deleteCorpusDao;

  private boolean hackDistinctLeftRightToken;

  /**
   * Searches for textes which are empty or only contains whitespaces. If that
   * is the case the visualizer and no document visualizer are defined in the
   * corpus properties file a new file is created and stores a new config which
   * disables document browsing.
   *
   *
   * @param corpusID
   *          The id of the corpus which texts are analyzed.
   */
  private void analyzeTextTable(String toplevelCorpusName) {
    List<String> rawTexts = getQueryDao().getRawText(toplevelCorpusName);

    // pattern for checking the token layer
    final Pattern WHITESPACE_MATCHER = Pattern.compile("^\\s+$");

    for (String s : rawTexts) {

      if (s != null && WHITESPACE_MATCHER.matcher(s).matches()) {
        // deactivate doc browsing if no document browser configuration is
        // exists
        if (getQueryDao().getDocBrowserConfiguration(toplevelCorpusName) == null) {
          // should exists anyway
          Properties corpusConf;
          try {
            corpusConf = getQueryDao().getCorpusConfiguration(toplevelCorpusName);
          } catch (FileNotFoundException ex) {
            log.error("not found a corpus configuration, so skip analyzing the text table", ex);
            return;
          }

          // disable document browsing if it is not explicit switch on by the
          // user in the corpus.properties
          boolean hasKey = corpusConf.containsKey("browse-documents");
          boolean isActive = Boolean.parseBoolean(corpusConf.getProperty("browse-documents"));

          if (!(hasKey && isActive)) {
            log.info("disable document browser");
            corpusConf.put("browse-documents", "false");
            getQueryDao().setCorpusConfiguration(toplevelCorpusName, corpusConf);
          }

          // once disabled don't search in further texts
          return;
        }
      }
    }
  }

  public ImportStatus initImportStatus() {
    return new CorpusAdministration.ImportStatsImpl();
  }

  public enum EXAMPLE_QUERIES_CONFIG {

    IF_MISSING, TRUE, FALSE

  }

  /**
   * If this is true and no example_queries.tab is found, automatic queries are
   * generated.
   */
  private EXAMPLE_QUERIES_CONFIG generateExampleQueries;

  private String schemaVersion;

  /**
   * A mapping for file-endings to mime types.
   */
  private Map<String, String> mimeTypeMapping;

  private Map<String, String> tableInsertSelect;

  private Map<String, String> tableInsertFrom;

  /**
   * Optional tab for example queries. If this tab not exist, a dummy file from
   * the resource folder is used.
   */
  private static final String EXAMPLE_QUERIES_TAB = "example_queries";

  /**
   * The name of the file and the relation containing the resolver information.
   */
  private static final String FILE_RESOLVER_VIS_MAP = "resolver_vis_map";
  // tables imported from bulk files
  // DO NOT CHANGE THE ORDER OF THIS LIST! Doing so may cause foreign key
  // failures during import.

  private final String[] importedTables = { "corpus", "corpus_annotation", "text", "node", "node_annotation",
      "component", "rank", "edge_annotation" };

  private final String[] tablesToCopyManually = { "corpus", "corpus_annotation", "text",
      "corpus_stats" };
  // tables created during import

  private final String[] createdTables = { "corpus_stats", "nodeidmapping" };

  private final ObjectMapper jsonMapper = new ObjectMapper();

  private QueriesGenerator queriesGenerator;

  private final Table resolverTable = new Table(FILE_RESOLVER_VIS_MAP)
      .c(new Column("id").type(Column.Type.INTEGER).primaryKey()).c(new Column("corpus").createIndex()).c("version")
      .c("namespace").c("element").c(new Column("vis_type").notNull()).c(new Column("display_name").notNull())
      .c("visibility").c_int("order").c("mappings");

  private final Table mediaFilesTable = new Table("media_files").c(new Column("filename").unique())
      .c(new Column("corpus_path").createIndex()).c(new Column("mime_type").createIndex())
      .c(new Column("title").createIndex());

  private final Table repositoryMetaDataTable = new Table("repository_metadata").c(new Column("name").primaryKey())
      .c("value");

  private final Table urlShortenerTable = new Table("url_shortener").c(new Column("id").primaryKey()).c("owner")
      .c("created").c(new Column("url").createIndex());

  private final Table exampleQueriesTable = new Table(EXAMPLE_QUERIES_TAB)
      .c(new Column("id").type(Column.Type.INTEGER).primaryKey()).c(new Column("example_query").notNull())
      .c(new Column("description").notNull())
      .c(new Column("corpus").notNull());

  /**
   * Called when Spring configuration finished
   */
  public void init() {
    AnnotationIntrospector introspector = new JaxbAnnotationIntrospector();
    jsonMapper.setAnnotationIntrospector(introspector);
    // the json should be as compact as possible in the database
    jsonMapper.configure(SerializationConfig.Feature.INDENT_OUTPUT, false);
  }

  ///// Subtasks of creating the database
  protected void dropDatabase(String database) {

    log.debug("dropping possible existing database");
    closeAllConnections(database);
    getJdbcTemplate().execute("DROP DATABASE IF EXISTS " + database);

  }

  protected void dropUser(String username) {
    log.debug("dropping possible existing user");
    getJdbcTemplate().execute("DROP USER IF EXISTS " + username);

  }

  protected void createUser(String username, String password) {
    log.info("creating user: " + username);
    getJdbcTemplate().execute("CREATE USER " + username + " PASSWORD '" + password + "'");
  }

  protected void createDatabase(String database, String owner) {
    log.info("creating database: " + database + " OWNER " + owner + " ENCODING = 'UTF8'");
    getJdbcTemplate().execute("CREATE DATABASE " + database + " OWNER " + owner + " ENCODING = 'UTF8'");
  }

  protected void installPlPgSql() {
    log.info("installing stored procedure language plpgsql");
    try {
      getJdbcTemplate().execute("CREATE LANGUAGE plpgsql");
    } catch (Exception ex) {
      log.warn("plpqsql was already installed: " + ex.getMessage());
    }
  }

  protected void createFunctionUniqueToplevelCorpusName() {
    log.info("creating trigger function: unique_toplevel_corpus_name");
    executeSqlFromScript("unique_toplevel_corpus_name.sql");
  }

  protected void createSchema() {
    log.info("creating ANNIS database schema (" + getSchemaVersion() + ")");
    executeSqlFromScript("schema.sql");

    // update schema version
    try (Connection conn = createSQLiteConnection()) {
      conn.setAutoCommit(false);

      getQueryRunner().update(conn, "DELETE FROM repository_metadata WHERE \"name\"='schema-version'");
      getQueryRunner().update(conn, "INSERT INTO repository_metadata " + "VALUES ('schema-version', ?)",
          getSchemaVersion());

      conn.commit();
    } catch (SQLException ex) {
      log.error("Error when creating schema", ex);
    }
  }

  protected void createSchemaIndexes() {
    log.info("creating ANNIS database schema indexes (" + getDatabaseSchemaVersion() + ")");
    executeSqlFromScript("schemaindex.sql");
  }

  protected void populateSchema() {
    log.info("creating immutable functions for extracting annotations");

    try (Connection conn = getJdbcTemplate().getDataSource().getConnection();) {

      DatabaseMetaData meta = conn.getMetaData();
      if (meta.getDatabaseMajorVersion() >= 10) {
        // there are some new regex functions in PostgreSQL 10 that don't exist in earlier versions
        executeSqlFromScript("functions_get_pg10.sql");
      } else {
        executeSqlFromScript("functions_get.sql");
      }
    } catch (SQLException ex) {
      log.error("could not get database version", ex);
    }

  }

  /**
   * Get the real schema name and version as used by the database.
   *
   * @return
   */
  @Transactional(readOnly = true, propagation = Propagation.REQUIRED)
  public String getDatabaseSchemaVersion() {
    try (Connection conn = createSQLiteConnection(true)) {

      List<String> result = getQueryRunner().query(conn,
          "SELECT \"value\" FROM repository_metadata WHERE \"name\"='schema-version'", new ColumnListHandler<>(1));

      String schema = result.size() > 0 ? (String) result.get(0) : "";
      return schema;
    } catch (SQLException ex) {
      String error = "Wrong database schema (too old to get the exact number), " + "please initialize the database.";
      log.error(error);
    }
    return "";
  }

  public boolean checkDatabaseSchemaVersion() throws AnnisException {
    String dbSchemaVersion = getDatabaseSchemaVersion();
    if (getSchemaVersion() != null && !getSchemaVersion().equalsIgnoreCase(dbSchemaVersion)) {
      String error = "Wrong database schema \"" + dbSchemaVersion + "\", please initialize the database.";
      log.error(error);
      throw new AnnisException(error);
    }
    return true;
  }

  public void initializeDatabase(String host, String port, String database, String user, String password,
      String defaultDatabase, String superUser, String superPassword, boolean useSSL, String pgSchema) {
    // connect as super user to the default database to create new user and
    // database
    if (superPassword != null) {
      log.info("Creating Annis database and user.");
      getDataSource().setInnerDataSource(
          createDataSource(host, port, defaultDatabase, superUser, superPassword, useSSL, pgSchema));

      createDatabaseAndUser(database, user, password);
    }
    // switch to new database as new user for the rest of the initialization
    // procedure
    getDataSource().setInnerDataSource(createDataSource(host, port, database, user, password, useSSL, pgSchema));

    //
    if (pgSchema != null && !"public".equalsIgnoreCase(pgSchema)) {
      pgSchema = pgSchema.toLowerCase().replaceAll("[^a-z0-9]", "_");
      log.info("creating PostgreSQL schema {}", pgSchema);
      // we have to create a schema before we can use it
      try {
        getJdbcTemplate().execute("CREATE SCHEMA " + pgSchema + ";");
      } catch (DataAccessException ex) {
        // ignore if the schema already exists
        log.info("schema " + pgSchema + " already exists");
      }
    }

    log.info("deleting old SQLite database");
    File dbFile = getDBFile();
    if (dbFile.exists() && dbFile.isFile()) {
      if (dbFile.delete() == false) {
        log.warn("Could not delete SQLite database {}", dbFile.getAbsolutePath());
      }
    }

    setupDatabase();

  }

  private void createDatabaseAndUser(String database, String user, String password) {
    dropDatabase(database);
    dropUser(user);
    createUser(user, password);
    createDatabase(database, user);

    installPlPgSql();
  }

  private void setupDatabase() {
    initSQLiteSchema();

    createFunctionUniqueToplevelCorpusName();
    createSchema();
    createSchemaIndexes();
    populateSchema();
  }

  private BasicDataSource createDataSource(File dbProperties) throws IOException, URISyntaxException {
    BasicDataSource result;

    Properties props = new Properties();
    try (InputStream is = new FileInputStream(dbProperties)) {
      props.load(is);

      String rawJdbcURL = props.getProperty("datasource.url").trim();

      rawJdbcURL = StringUtils.removeStart(rawJdbcURL, "jdbc:");
      URI jdbcURL = new URI(rawJdbcURL);

      result = createDataSource(jdbcURL.getHost(), "" + jdbcURL.getPort(), jdbcURL.getPath().substring(1), // remove the "/" at the beginning
          props.getProperty("datasource.username"), props.getProperty("datasource.password"),
          "true".equalsIgnoreCase(props.getProperty("datasource.ssl")), props.getProperty("datasource.schema"));
    }

    return result;
  }

  private BasicDataSource createDataSource(String host, String port, String database, String user, String password,
      boolean useSSL, String schema) {

    String url = "jdbc:postgresql://" + host + ":" + port + "/" + database;

    // DriverManagerDataSource is deprecated
    // return new DriverManagerDataSource("org.postgresql.Driver", url, user,
    // password);
    BasicDataSource result = new BasicDataSource();
    result.setUrl(url);
    if (useSSL) {
      result.setConnectionProperties("ssl=true");
    }
    result.setUsername(user);
    result.setPassword(password);

    result.setValidationQuery("SELECT 1;");
    result.setTestOnCreate(true);
    result.setTestOnBorrow(true);
    result.setTestOnReturn(true);
    result.setTestWhileIdle(true);
    result.setFastFailValidation(true);
    result.setDisconnectionSqlCodes(
        Arrays.asList("53000", "53300", "53400", "08000", "08003", "08006", "08001", "08004", "08007", "08P01"));

    result.setAccessToUnderlyingConnectionAllowed(true);
    if (schema == null) {
      schema = "public";
    }
    result.setConnectionInitSqls(Arrays.asList("SET search_path TO \"$user\"," + schema));

    result.setDriverClassName("org.postgresql.Driver");

    return result;
  }

  /**
   * Reads ANNIS files from several directories.
   *
   * @param path
   *          Specifies the path to the corpora, which should be imported.
   * @param aliasName
   *          An alias name for this corpus. Can be null.
   * @param overwrite
   *          If set to true conflicting top level corpora are deleted.
   * @param waitForOtherTasks
   *          If true wait for other tasks to finish, if false abort.
   *
   * @return true if successful
   */
  @Transactional(readOnly = false, propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
  public boolean importCorpus(String path, String aliasName, boolean overwrite) {

    // check schema version first
    checkDatabaseSchemaVersion();

    // explicitly unset any timeout
    getJdbcTemplate().update("SET statement_timeout TO 0");

    initSQLiteSchema();

    ANNISFormatVersion annisFormatVersion = getANNISFormatVersion(path);

    if (annisFormatVersion == ANNISFormatVersion.V3_3) {
      return importVersion4(path, aliasName, overwrite, annisFormatVersion);
    } else if (annisFormatVersion == ANNISFormatVersion.V3_1 || annisFormatVersion == ANNISFormatVersion.V3_2) {
      return importVersion3(path, aliasName, overwrite, annisFormatVersion);
    }

    log.error("Unknown ANNIS import format version");
    return false;
  }

  private boolean importVersion4(String path, String aliasName, boolean overwrite, ANNISFormatVersion version) {
    createStagingAreaV33(temporaryStagingArea);
    bulkImport(path, version);

    String toplevelCorpusName = getTopLevelCorpusFromTmpArea();

    // remove conflicting top level corpora, when override is set to true.
    if (overwrite) {
      deleteCorpusDao.checkAndRemoveTopLevelCorpus(toplevelCorpusName);
    } else {
      checkTopLevelCorpus();
    }

    applyConstraints();
    createStagingAreaIndexes(version);

    analyzeStagingTables();

    addDocumentNameMetaData();

    Offsets offsets = calculateOffsets();
    long corpusID = getNewToplevelCorpusID(offsets);
    createNodeIdMapping();

    importBinaryData(path, toplevelCorpusName);

    convertToGraphANNIS(toplevelCorpusName, path, version);
    importResolverTable(toplevelCorpusName, path, version);
    importExampleQueries(toplevelCorpusName, path, version);

    extendStagingText(corpusID);

    computeCorpusStatistics(path);

    analyzeStagingTables();

    insertCorpus(corpusID, offsets);

    computeCorpusPath(corpusID);

    createAnnotations(corpusID);

    createAnnoCategory(corpusID);

    // create the new facts table partition
    createFacts(corpusID, version, offsets);

    if (hackDistinctLeftRightToken) {
      adjustDistinctLeftRightToken(corpusID);
    }

    if (temporaryStagingArea) {
      dropStagingArea();
    }

    // create empty corpus properties file
    if (getQueryDao().getCorpusConfigurationSave(toplevelCorpusName) == null) {
      log.info("creating new corpus.properties file");
      getQueryDao().setCorpusConfiguration(toplevelCorpusName, new Properties());
    }

    analyzeFacts(corpusID);
    analyzeTextTable(toplevelCorpusName);
    generateExampleQueries(toplevelCorpusName);

    if (aliasName != null && !aliasName.isEmpty()) {
      addCorpusAlias(corpusID, aliasName);
    }
    return true;
  }

  private boolean importVersion3(String path, String aliasName, boolean overwrite, ANNISFormatVersion version) {
    createStagingAreaV32(temporaryStagingArea);
    bulkImport(path, version);

    String toplevelCorpusName = getTopLevelCorpusFromTmpArea();

    // remove conflicting top level corpora, when override is set to true.
    if (overwrite) {
      deleteCorpusDao.checkAndRemoveTopLevelCorpus(toplevelCorpusName);
    } else {
      checkTopLevelCorpus();
    }

    createStagingAreaIndexes(version);

    computeTopLevelCorpus();
    analyzeStagingTables();

    computeLeftTokenRightToken();

    removeUnecessarySpanningRelations();

    addUniqueNodeNameAppendix();
    adjustRankPrePost();
    adjustTextId();
    addDocumentNameMetaData();

    Offsets offsets = calculateOffsets();
    long corpusID = getNewToplevelCorpusID(offsets);
    createNodeIdMapping();

    importBinaryData(path, toplevelCorpusName);

    convertToGraphANNIS(toplevelCorpusName, path, version);
    importResolverTable(toplevelCorpusName, path, version);
    importExampleQueries(toplevelCorpusName, path, version);

    extendStagingText(corpusID);

    computeRealRoot();
    computeLevel();
    computeCorpusStatistics(path);
    computeSpanFromSegmentation();

    applyConstraints();
    analyzeStagingTables();

    insertCorpus(corpusID, offsets);

    computeCorpusPath(corpusID);

    createAnnotations(corpusID);

    createAnnoCategory(corpusID);

    // create the new facts table partition
    createFacts(corpusID, version, offsets);

    if (hackDistinctLeftRightToken) {
      adjustDistinctLeftRightToken(corpusID);
    }

    if (temporaryStagingArea) {
      dropStagingArea();
    }

    // create empty corpus properties file
    if (getQueryDao().getCorpusConfigurationSave(toplevelCorpusName) == null) {
      log.info("creating new corpus.properties file");
      getQueryDao().setCorpusConfiguration(toplevelCorpusName, new Properties());
    }

    analyzeFacts(corpusID);
    analyzeTextTable(toplevelCorpusName);
    generateExampleQueries(toplevelCorpusName);

    if (aliasName != null && !aliasName.isEmpty()) {
      addCorpusAlias(corpusID, aliasName);
    }
    return true;
  }

  ///// Subtasks of importing a corpus

  /**
   * Makes sure all tables needed by SQLite are available.
   * @throws java.sql.SQLException
   */
  protected void initSQLiteSchema() {
    try {
      createTableIfNotExists(repositoryMetaDataTable, null, null);
      createTableIfNotExists(resolverTable, new File(getScriptPath(), "resolver_vis_map.annis"), null);
      createTableIfNotExists(mediaFilesTable, null, null);
      createTableIfNotExists(urlShortenerTable, null, null);
      createTableIfNotExists(exampleQueriesTable, null, null);
    } catch (SQLException ex) {
      log.error("Can not create SQL schema", ex);
    }
  }

  protected void convertToGraphANNIS(String corpusName, String path, ANNISFormatVersion version) {

    log.info("importing corpus into graphANNIS");
    getQueryDao().getCorpusStorageManager().importRelANNIS(corpusName, path);

  }

  private void importResolverTable(String corpusName, String path, ANNISFormatVersion version) {

    log.info("importing resolver entries");
    File importDir = new File(path);

    Function<String[], String[]> lineModifier = (line) -> {
      if (line == null) {
        return line;
      }
      // check that the resolver entry is applied to correct corpus
      if (line[0] == null || !line[0].equals(corpusName)) {
        log.warn("resolver entry references wrong corpus \"" + line[0] + "\" and was rewritten");
        line[0] = corpusName;
      }
      if (line.length == 8) {
        String[] updateLine = new String[9];
        updateLine[0] = line[0];
        updateLine[1] = line[1];
        updateLine[2] = line[2];
        updateLine[3] = line[3];
        updateLine[4] = line[4];
        updateLine[5] = line[5];
        // use default value
        updateLine[6] = "hidden";
        updateLine[7] = line[6];
        updateLine[8] = line[7];

        return updateLine;
      } else {
        return line;
      }
    };

    try {
      importSQLiteTable(resolverTable, new File(importDir, FILE_RESOLVER_VIS_MAP + version.getFileSuffix()),
          lineModifier);
    } catch (SQLException ex) {
      log.error("Could not import resolver file {}", path, ex);
    }
  }

  private void importExampleQueries(String corpusName, String path, ANNISFormatVersion version) {

    File importDir = new File(path);
    File exampleFile = new File(importDir, EXAMPLE_QUERIES_TAB + version.getFileSuffix());

    if(exampleFile.exists() && exampleFile.isFile()) {
      log.info("importing example queries entries");

      if (generateExampleQueries == (EXAMPLE_QUERIES_CONFIG.IF_MISSING)) {
        generateExampleQueries = EXAMPLE_QUERIES_CONFIG.FALSE;
      }

      Function<String[], String[]> lineModifier = (line) -> {
        if (line == null) {
          return line;
        }
        // add the corpus name to the row
        if(line.length == 2) {
          String[] updateLine = new String[3];
          updateLine[0] = line[0];
          updateLine[1] = line[1];
          updateLine[2] = corpusName;
          return updateLine;
        } else {
          return line;
        }
      };

      try {
        importSQLiteTable(exampleQueriesTable, exampleFile,
            lineModifier);
      } catch (SQLException ex) {
        log.error("Could not import example queries file {}", path, ex);
      }
    } else {
      if (generateExampleQueries == EXAMPLE_QUERIES_CONFIG.IF_MISSING) {
        generateExampleQueries = EXAMPLE_QUERIES_CONFIG.TRUE;
      }

      log.info(EXAMPLE_QUERIES_TAB + version.getFileSuffix() + " file not found");
    }
    
  }

  protected void dropIndexes() {
    log.info("dropping indexes");
    for (String index : listIndexesOnTables(allTables())) {
      log.debug("dropping index: " + index);
      getJdbcTemplate().execute("DROP INDEX " + index);
    }
  }

  void createStagingAreaV33(boolean useTemporary) {
    log.info("creating staging area for import format version 3.3");
    MapSqlParameterSource args = makeArgs().addValue(":tmp", useTemporary ? "TEMPORARY" : "UNLOGGED");
    executeSqlFromScript("staging_area.sql", args);
  }

  void createStagingAreaV32(boolean useTemporary) {
    log.info("creating staging area for import format version 3.1/3.2");
    MapSqlParameterSource args = makeArgs().addValue(":tmp", useTemporary ? "TEMPORARY" : "UNLOGGED");
    executeSqlFromScript("staging_area_v32.sql", args);
  }

  /**
   * Reads tab seperated files from the filesystem, but it takes only files into
   * account with the {@link DefaultAdministrationDao#REL_ANNIS_FILE_SUFFIX}
   * suffix. Further it is straight forward except for the
   * {@link DefaultAdministrationDao#FILE_RESOLVER_VIS_MAP} and the
   * {@link DefaultAdministrationDao#EXAMPLE_QUERIES_TAB}. This is done by this
   * method automatically.
   *
   * <ul>
   *
   * <li>{@link DefaultAdministrationDao#FILE_RESOLVER_VIS_MAP}: For backwards
   * compatibility, the columns must be counted, since there exists one
   * additional column for visibility behaviour of visualizers.</li>
   *
   * </ul>
   *
   * @param path
   *          The path to the ANNIS files. The files have to have this suffix
   * @param version
   *          {@link DefaultAdministrationDao#REL_ANNIS_FILE_SUFFIX}
   */
  void bulkImport(String path, ANNISFormatVersion version) {
    log.info("bulk-loading data");

    for (String table : importedTables) {
      // check if example query exists. If not copy it from the resource folder.
      if (table.equalsIgnoreCase("node")) {
        bulkImportNode(path, version);
      } else {
        bulkloadTableFromResource(tableInStagingArea(table),
            new FileSystemResource(new File(path, table + version.getFileSuffix())));
      }
    }
  }

  private void bulkImportNode(String path, ANNISFormatVersion version) {
    // check column number by reading first line
    File nodeTabFile = new File(path, "node" + version.getFileSuffix());
    try (
        BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(nodeTabFile), "UTF-8"));) {

      String firstLine = reader.readLine();

      int columnNumber = firstLine == null ? 13 : StringUtils.splitPreserveAllTokens(firstLine, '\t').length;
      if (version == ANNISFormatVersion.V3_3 || version == ANNISFormatVersion.V3_2) {
        // new node table with segmentations
        // no special handling needed
        bulkloadTableFromResource(tableInStagingArea("node"), new FileSystemResource(nodeTabFile));
      } else if (version == ANNISFormatVersion.V3_1) {
        getJdbcTemplate().execute("DROP TABLE IF EXISTS _tmpnode;");
        // old node table without segmentations
        // create temporary table for bulk import
        getJdbcTemplate().execute("CREATE TEMPORARY TABLE _tmpnode" + "\n(\n" + "id bigint,\n" + "text_ref integer,\n"
            + "corpus_ref integer,\n" + "namespace varchar,\n" + "name varchar,\n" + "\"left\" integer,\n"
            + "\"right\" integer,\n" + "token_index integer,\n" + "continuous boolean,\n" + "span varchar\n" + ");");

        bulkloadTableFromResource("_tmpnode", new FileSystemResource(nodeTabFile));

        log.info("copying nodes from temporary helper table into staging area");
        getJdbcTemplate().execute("INSERT INTO " + tableInStagingArea("node") + "\n"
            + "  SELECT id, text_ref, corpus_ref, namespace AS layer, name, \"left\", " + "\"right\", token_index, "
            + "NULL AS seg_name, NULL AS seg_left, NULL AS seg_left, continuous, " + "span\n" + "FROM _tmpnode");
      } else {
        throw new RuntimeException("Illegal number of columns in node" + version.getFileSuffix() + ", "
            + "should be 13 or 10 but was " + columnNumber);
      }
    } catch (IOException ex) {
      log.error(null, ex);
    }
  }

  void createStagingAreaIndexes(ANNISFormatVersion version) {
    log.info("creating indexes for staging area");
    if (version == ANNISFormatVersion.V3_3) {
      executeSqlFromScript("indexes_staging_v33.sql");
    } else {
      executeSqlFromScript("indexes_staging_v32.sql");
    }
  }

  void computeTopLevelCorpus() {
    log.info("computing top-level corpus");
    executeSqlFromScript("toplevel_corpus.sql");
  }

  void importBinaryData(String path, String toplevelCorpusName) {
    log.info("importing all binary data from ExtData");
    File extData = new File(path + "/ExtData");
    if (extData.canRead() && extData.isDirectory()) {
      // import toplevel corpus media files
      File[] topFiles = extData.listFiles((FileFilter) FileFileFilter.FILE);
      for (File data : topFiles) {
        String extension = FilenameUtils.getExtension(data.getName());
        try {
          if (mimeTypeMapping.containsKey(extension)) {
            log.info("import " + data.getCanonicalPath() + " to staging area");

            importSingleFile(data.getCanonicalPath(), toplevelCorpusName);
          } else {
            log.warn("not importing " + data.getCanonicalPath() + " since file type is unknown");
          }
        } catch (IOException ex) {
          log.error("no canonical path given", ex);
        }
      }

      // get each subdirectory (which corresponds to an document name)
      File[] documents = extData.listFiles((FileFilter) DirectoryFileFilter.DIRECTORY);
      for (File doc : documents) {
        if (doc.isDirectory() && doc.canRead()) {
          File[] dataFiles = doc.listFiles((FileFilter) FileFileFilter.FILE);
          for (File data : dataFiles) {
            String extension = FilenameUtils.getExtension(data.getName());
            try {
              if (mimeTypeMapping.containsKey(extension)) {
                log.info("import " + data.getCanonicalPath() + " to staging area");

                importSingleFile(data.getCanonicalPath(), toplevelCorpusName + "/" + doc.getName());
              } else {
                log.warn("not importing " + data.getCanonicalPath() + " since file type is unknown");
              }
            } catch (IOException ex) {
              log.error("no canonical path given", ex);
            }
          }
        }
      }
    }
  }

  /**
   * Imports a single binary file.
   *
   * @param file
   *          Specifies the file to be imported.
   * @param toplevelCorpusName
   *          The toplevel corpus name
   */
  private void importSingleFile(String file, String toplevelCorpusName) {

    BinaryImportHelper preStat = new BinaryImportHelper(file, getRealDataDir(), toplevelCorpusName, mimeTypeMapping);
    try (Connection conn = createSQLiteConnection();
        PreparedStatement stmt = conn.prepareStatement(BinaryImportHelper.SQL)) {
      preStat.doInPreparedStatement(stmt);
    } catch (SQLException ex) {
      log.error("Cannot import binary file {}", file, ex);
    }
  }


  void extendStagingText(long toplevelID) {
    log.info("extending _text");
    executeSqlFromScript("extend_staging_text.sql", makeArgs().addValue(":id", toplevelID));
  }

  void computeLeftTokenRightToken() {
    log.info("computing values for struct.left_token and struct.right_token");
    executeSqlFromScript("left_token_right_token.sql");

    // re-analyze node since we added new columns
    log.info("analyzing node");
    getJdbcTemplate().execute("ANALYZE " + tableInStagingArea("node"));

  }

  void computeRealRoot() {
    log.info("computing real root for rank");
    executeSqlFromScript("root.sql");
  }

  void computeLevel() {
    log.info("computing values for rank.level (dominance and pointing relations)");
    executeSqlFromScript("level.sql");

    log.info("computing values for rank.level (coverage)");
    executeSqlFromScript("level_coverage.sql");
  }

  void computeCorpusStatistics(String path) {

    File f = new File(path);
    String absolutePath = path;
    try {
      absolutePath = f.getCanonicalPath();
    } catch (IOException ex) {
      log.error("Something went really wrong when calculating the canonical path", ex);
    }

    log.info("computing statistics for top-level corpus");
    MapSqlParameterSource args = makeArgs().addValue(":path", absolutePath);
    executeSqlFromScript("corpus_stats.sql", args);
  }

  void computeCorpusPath(long corpusID) {
    MapSqlParameterSource args = makeArgs().addValue(":id", corpusID);
    log.info("computing path information of the corpus tree for corpus with ID " + corpusID);
    executeSqlFromScript("compute_corpus_path.sql", args);
  }

  void addDocumentNameMetaData() {
    log.info("add the document name as metadata");
    executeSqlFromScript("adddocmetadata.sql");
  }

  protected void adjustRankPrePost() {
    log.info("updating pre and post order in _rank");
    executeSqlFromScript("adjustrankprepost.sql");
    log.info("analyzing _rank");
    getJdbcTemplate().execute("ANALYZE " + tableInStagingArea("rank"));
  }

  protected void adjustTextId() {
    log.info("updating id in _text and text_ref in _node");
    executeSqlFromScript("adjusttextid.sql");
    log.info("analyzing _node and _text");
    getJdbcTemplate().execute("ANALYZE " + tableInStagingArea("text"));
    getJdbcTemplate().execute("ANALYZE " + tableInStagingArea("node"));
  }

  protected void addUniqueNodeNameAppendix() {
    // first check if this is actually necessary
    log.info("check if node names are unique");

    getJdbcTemplate().execute("ALTER TABLE _node ADD COLUMN unique_name_appendix varchar;");

    List<Integer> checkDuplicate = getJdbcTemplate().queryForList(
        "SELECT COUNT(*) from _node GROUP BY \"name\", corpus_ref HAVING COUNT(*) > 1 LIMIT 1", Integer.class);
    if (checkDuplicate.isEmpty()) {
      log.info("node names are unique, no update necessary");
    } else {
      log.info("add an unique node name appendix");
      executeSqlFromScript("unique_node_name_appendix.sql");
    }
  }

  /**
   *
   * @return the new corpus ID
   */
  void createNodeIdMapping() {

    log.info("creating node ID mapping (for properly sorted IDs)");
    executeSqlFromScript("node_id_mapping.sql");

  }
  /*
   * private long getRecentCorpusID() { int numOfEntries =
   * getJdbcTemplate().queryForObject( "SELECT COUNT(*) from corpus_stats",
   * Integer.class);
   * 
   * long recentCorpusId = 0;
   * 
   * if (numOfEntries > 0) { recentCorpusId = getJdbcTemplate().queryForObject(
   * "SELECT max(id) FROM corpus_stats", Long.class);
   * log.debug("the id from recently imported corpus: {}", recentCorpusId); }
   * return recentCorpusId; }
   */

  long getNewToplevelCorpusID(Offsets offsets) {
    log.info("query for the new corpus ID");

    long maxLocalID = getJdbcTemplate().queryForObject("SELECT MAX(id) FROM _corpus WHERE top_level IS TRUE",
        Long.class);

    long result = maxLocalID + offsets.getCorpusID();

    log.info("new corpus ID is " + result);
    return result;
  }

  void computeSpanFromSegmentation() {
    log.info("computing span value for segmentation nodes");
    executeSqlFromScript("span_from_segmentation.sql");
  }

  void applyConstraints() {
    log.info("activating relational constraints");
    executeSqlFromScript("constraints.sql");
  }

  private Offsets calculateOffsets() {
    log.info("querying ID offsets");

    long offsetCorpusID = getJdbcTemplate()
        .queryForObject("SELECT COALESCE((SELECT max(max_corpus_id)+1 FROM corpus_stats),0)", Long.class);
    long offsetCorpusPost = getJdbcTemplate()
        .queryForObject("SELECT COALESCE((SELECT max(max_corpus_post)+1 FROM corpus_stats),0)", Long.class);

    long offsetNodeID = getJdbcTemplate()
        .queryForObject("SELECT COALESCE((SELECT max(max_node_id)+1 FROM corpus_stats),0)", Long.class);

    return new Offsets(offsetCorpusID, offsetCorpusPost, offsetNodeID);
  }

  void insertCorpus(long corpusID, Offsets offsets) {

    MapSqlParameterSource args = offsets.makeArgs().addValue(":id", corpusID);

    log.info("moving corpus from staging area to main db");
    for (String table : tablesToCopyManually) {
      log.debug("moving table \"{}\" from staging area to main db", table);
      StringBuilder sql = new StringBuilder();

      String predefinedFrom = tableInsertFrom == null ? null : tableInsertFrom.get(table);
      String predefinedSelect = tableInsertSelect == null ? null : tableInsertSelect.get(table);

      if (predefinedFrom != null || predefinedSelect != null) {
        if (predefinedFrom == null) {
          predefinedFrom = predefinedSelect;
        }

        sql.append("INSERT INTO ");
        sql.append(table);
        sql.append(" ( ");
        sql.append(predefinedSelect);

        sql.append(" ) (SELECT ");
        sql.append(predefinedFrom);
        sql.append(" FROM ");
        sql.append(tableInStagingArea(table)).append(")");
      } else {
        sql.append("INSERT INTO ");
        sql.append(table);
        sql.append(" (SELECT * FROM ");
        sql.append(tableInStagingArea(table)).append(")");
      }
      executeSql(sql.toString(), args);

    }
  }

  void dropStagingArea() {
    log.info("dropping staging area");

    // tables must be dropped in reverse order
    List<String> tables = importedAndCreatedTables();
    Collections.reverse(tables);

    for (String table : tables) {
      getJdbcTemplate().execute("DROP TABLE " + tableInStagingArea(table));
    }

  }

  void dropMaterializedTables() {
    log.info("dropping materialized tables");

    getJdbcTemplate().execute("DROP TABLE facts");

  }

  void analyzeStagingTables() {
    log.info("analyzing staging area");
    for (String t : importedTables) {
      log.debug("analyzing " + t);
      getJdbcTemplate().execute("ANALYZE " + tableInStagingArea(t));
    }
  }

  void createAnnotations(long corpusID) {
    MapSqlParameterSource args = makeArgs().addValue(":id", corpusID);
    log.info("creating annotations table for corpus with ID " + corpusID);
    executeSqlFromScript("annotations.sql", args);

    log.info("indexing annotations table for corpus with ID " + corpusID);
    executeSqlFromScript("indexes_annotations.sql", args);
  }

  void createAnnoCategory(long corpusID) {
    MapSqlParameterSource args = makeArgs().addValue(":id", corpusID);
    log.info("creating annotation category table for corpus with ID " + corpusID);
    executeSqlFromScript("annotation_category.sql", args);
  }

  void analyzeFacts(long corpusID) {
    log.info("analyzing facts table for corpus with ID " + corpusID);
    getJdbcTemplate().execute("ANALYZE facts_" + corpusID);
  }

  void adjustDistinctLeftRightToken(long corpusID) {
    /*
     * HACK: adjust the left/right_token value to the average maximal
     * left/right_token value per corpus/text on import to enhance the planner
     * selectivity estimations.
     */

    log.info("adjusting statistical information for left_token and right_token columns");

    int adjustedLeft = getJdbcTemplate().queryForObject("SELECT avg(maxleft)::integer\n" + "FROM\n"
        + "( SELECT max(left_token) maxleft FROM _node GROUP BY corpus_ref, text_ref ) AS m", Integer.class);
    int adjustedRight = getJdbcTemplate().queryForObject("SELECT avg(maxright)::integer\n" + "FROM\n"
        + "( SELECT max(right_token) maxright FROM _node GROUP BY corpus_ref, text_ref ) AS m", Integer.class);

    getJdbcTemplate()
        .execute("ALTER TABLE facts_" + corpusID + " ALTER COLUMN left_token SET (n_distinct=" + adjustedLeft + ")");
    getJdbcTemplate()
        .execute("ALTER TABLE facts_" + corpusID + " ALTER COLUMN right_token SET (n_distinct=" + adjustedRight + ")");
  }

  void createFacts(long corpusID, ANNISFormatVersion version, Offsets offsets) {
    MapSqlParameterSource args = offsets.makeArgs().addValue(":id", corpusID);

    log.info("creating materialized facts table for corpus with ID " + corpusID);

    String defaultStatTargetRaw = getJdbcTemplate().queryForObject("SHOW default_statistics_target", String.class);

    // this is the minimal value
    int selectedStatTarget = 250;

    if (defaultStatTargetRaw != null) {
      try {
        int defaultStatTargetConfig = Integer.parseInt(defaultStatTargetRaw);
        // make sure the sample size is not less than the default one
        selectedStatTarget = Math.max(selectedStatTarget, defaultStatTargetConfig);
      } catch (NumberFormatException ex) {
        log.warn("Could not parse the \"default_statistics_target\" PostgreSQL parameter.");
      }
    }
    args.addValue(":stat_target", selectedStatTarget);

    if (version == ANNISFormatVersion.V3_3) {
      executeSqlFromScript("facts.sql", args);
    } else {
      executeSqlFromScript("facts_v32.sql", args);
    }

    log.info("indexing the new facts table (general indexes)");
    executeSqlFromScript("indexes.sql", args);

    log.info("indexing the new facts table (edge related indexes)");
    executeSqlFromScript("indexes_edge.sql", args);

  }

  void removeUnecessarySpanningRelations() {
    log.info("setting \"continuous\" to a correct value");
    executeSqlFromScript("set_continuous.sql");

    // re-analyze node since we added new columns
    log.info("analyzing node");
    getJdbcTemplate().execute("ANALYZE " + tableInStagingArea("node"));

    log.info("removing unnecessary span relations");
    executeSqlFromScript("remove_span_relations.sql");

  }

  ///// Other sub tasks
  public List<Long> listToplevelCorpora() {
    String sql = "SELECT id FROM corpus WHERE top_level = 'y'";

    return getJdbcTemplate().query(sql, ParameterizedSingleColumnRowMapper.newInstance(Long.class));
  }

  /**
   * Delete files not used by this instance in the data directory.
   */
  @Transactional(readOnly = true)
  public void cleanupData() {

    try (Connection conn = createSQLiteConnection(true)) {

      List<String> allFilesInDatabaseList = getQueryRunner().query(conn, "SELECT filename FROM media_files AS m",
          new ColumnListHandler<>(1));

      File dataDir = getRealDataDir();

      Set<File> allFilesInDatabase = new HashSet<>();
      for (String singleFileName : allFilesInDatabaseList) {
        allFilesInDatabase.add(new File(dataDir, singleFileName));
      }

      log.info("Cleaning up the data directory");
      // go through each file of the folder and check if it is not included
      File[] childFiles = dataDir.listFiles();
      if (childFiles != null) {
        for (File f : childFiles) {
          if (f.isFile() && !allFilesInDatabase.contains(f)) {
            if (!f.delete()) {
              log.warn("Could not delete {}", f.getAbsolutePath());
            }
          }
        }
      }
    } catch (SQLException ex) {
      log.error("Error when cleaning up data", ex);
    }

  }

  public List<Map<String, Object>> listCorpusStats() {
    return getJdbcTemplate().queryForList("SELECT * FROM corpus_info ORDER BY name");
  }

  /**
   * Lists the corpora using the connection information of a given
   * "database.properties". file
   *
   * @param databaseProperties
   * @return
   */
  public List<Map<String, Object>> listCorpusStats(File databaseProperties) {
    List<Map<String, Object>> result = new LinkedList<>();

    DataSource origDataSource = getDataSource().getInnerDataSource();
    try {
      if (databaseProperties != null) {
        getDataSource().setInnerDataSource(createDataSource(databaseProperties));
      }
      result = getJdbcTemplate().queryForList("SELECT * FROM corpus_info ORDER BY name");
    } catch (IOException | URISyntaxException | DataAccessException ex) {
      if (databaseProperties == null) {
        log.error("Could not query corpus list", ex);
      } else {
        log.error("Could not query corpus list for the file " + databaseProperties.getAbsolutePath(), ex);
      }
    } finally {
      getDataSource().setInnerDataSource(origDataSource);
    }
    return result;
  }

  public List<String> listUsedIndexes() {
    log.info("retrieving list of used indexes");
    return listIndexDefinitions(true);
  }

  public List<String> listUnusedIndexes() {
    log.info("retrieving list of unused indexes");
    return listIndexDefinitions(false);
  }

  /**
   * Provides a list where the keys are the aliases and the values are the
   * corpus names.
   *
   * @param databaseProperties
   * @return
   */
  public Multimap<String, String> listCorpusAlias(File databaseProperties) {
    Multimap<String, String> result = TreeMultimap.create();

    DataSource origDataSource = getDataSource().getInnerDataSource();
    try {
      if (databaseProperties != null) {
        getDataSource().setInnerDataSource(createDataSource(databaseProperties));
      }
      result = getJdbcTemplate().query("SELECT a.alias AS alias, c.name AS corpus\n"
          + "FROM corpus_alias AS a, corpus AS c\n" + "WHERE\n" + " a.corpus_ref = c.id",
          new ResultSetExtractor<Multimap<String, String>>() {

            @Override
            public Multimap<String, String> extractData(ResultSet rs) throws SQLException, DataAccessException {
              Multimap<String, String> data = TreeMultimap.create();
              while (rs.next()) {
                // alias -> corpus name
                data.put(rs.getString(1), rs.getString(2));
              }
              return data;
            }
          });

    } catch (IOException | URISyntaxException | DataAccessException ex) {
      if (databaseProperties == null) {
        log.error("Could not query corpus list", ex);
      } else {
        log.error("Could not query corpus list for the file " + databaseProperties.getAbsolutePath(), ex);
      }
    } finally {
      getDataSource().setInnerDataSource(origDataSource);
    }

    return result;
  }

  @Transactional(readOnly = true)
  public UserConfig retrieveUserConfig(final String userName) {
    String sql = "SELECT * FROM user_config WHERE id=?";
    UserConfig config = getJdbcTemplate().query(sql, new Object[] { userName }, new ResultSetExtractor<UserConfig>() {
      @Override
      public UserConfig extractData(ResultSet rs) throws SQLException, DataAccessException {

        // default to empty config
        UserConfig c = new UserConfig();

        if (rs.next()) {
          try {
            c = jsonMapper.readValue(rs.getString("config"), UserConfig.class);
          } catch (IOException ex) {
            log.error("Could not parse JSON that is stored in database (user configuration)", ex);
          }
        }
        return c;
      }
    });

    return config;
  }

  @Transactional(readOnly = false)
  public void storeUserConfig(String userName, UserConfig config) {
    String sqlUpdate = "UPDATE user_config SET config=?::json WHERE id=?";
    String sqlInsert = "INSERT INTO user_config(id, config) VALUES(?,?)";
    try {
      String jsonVal = jsonMapper.writeValueAsString(config);

      // if no row was affected there is no entry yet and we should create one
      if (getJdbcTemplate().update(sqlUpdate, jsonVal, userName) == 0) {
        getJdbcTemplate().update(sqlInsert, userName, jsonVal);
      }
    } catch (IOException ex) {
      log.error("Cannot serialize user config JSON for database.", ex);
    }
  }

  @Transactional(readOnly = false)
  public void deleteUserConfig(String userName) {
    getJdbcTemplate().update("DELETE FROM user_config WHERE id=?", userName);
  }

  public void addCorpusAlias(long corpusID, String alias) {
    getJdbcTemplate().update(
        "INSERT INTO corpus_alias (alias, corpus_ref)\n" + "VALUES(\n" + "  ?, \n" + "  ?\n" + ");", alias, corpusID);
  }

  public void addCorpusAlias(String corpusName, String alias) {
    getJdbcTemplate().update("INSERT INTO corpus_alias (alias, corpus_ref)\n" + "SELECT ? AS alias, c.id\n"
        + "FROM corpus AS c WHERE c.top_level AND c.name=? LIMIT 1;", alias, corpusName);
  }

  ///// Helpers
  private List<String> importedAndCreatedTables() {
    List<String> tables = new ArrayList<>();
    tables.addAll(Arrays.asList(importedTables));
    tables.addAll(Arrays.asList(createdTables));
    return tables;
  }

  private List<String> allTables() {
    List<String> tables = new ArrayList<>();
    tables.addAll(Arrays.asList(importedTables));
    tables.addAll(Arrays.asList(createdTables));
    // tables.addAll(Arrays.asList(materializedTables));
    return tables;
  }

  private ParameterizedSingleColumnRowMapper<String> stringRowMapper() {
    return ParameterizedSingleColumnRowMapper.newInstance(String.class);
  }

  // executes an SQL script from $ANNIS_HOME/scripts
  @Transactional(propagation = Propagation.MANDATORY)
  public PreparedStatement executeSqlFromScript(String script) {
    return executeSqlFromScript(script, null);
  }

  // bulk-loads a table from a resource
  private void bulkloadTableFromResource(String table, Resource resource) {
    log.debug("bulk-loading data from '" + resource.getFilename() + "' into table '" + table + "'");
    String sql = "COPY \"" + table + "\" FROM STDIN WITH DELIMITER E'\t' NULL AS 'NULL'";

    try {
      // retrieve the currently open connection if running inside a transaction
      Connection originalCon = DataSourceUtils.getConnection(getDataSource());
      Connection con = originalCon;
      if (con instanceof DelegatingConnection) {
        DelegatingConnection<?> delCon = (DelegatingConnection<?>) con;
        con = delCon.getInnermostDelegate();
      }

      Preconditions.checkState(con instanceof PGConnection,
          "bulk-loading only works with a PostgreSQL JDBC connection");

      // Postgres JDBC4 8.4 driver now supports the copy API
      PGConnection pgCon = (PGConnection) con;
      pgCon.getCopyAPI().copyIn(sql, resource.getInputStream());

      DataSourceUtils.releaseConnection(originalCon, getDataSource());

    } catch (SQLException e) {
      throw new DatabaseAccessException(e);
    } catch (IOException e) {
      throw new FileAccessException(e);
    }
  }

  @Transactional(readOnly = false)
  public void restoreTableFromResource(String table, Resource resource) {
    // this is only a public wrapper function providing the transaction
    bulkloadTableFromResource(table, resource);
  }

  @Transactional(readOnly = true)
  public void dumpTableToResource(String table, WritableResource resource) {
    log.debug("dumping data to '" + resource.getFilename() + "' from table '" + table + "'");
    String sql = "COPY \"" + table + "\" TO STDOUT WITH DELIMITER E'\t' NULL AS 'NULL'";

    try {
      // retrieve the currently open connection if running inside a transaction
      Connection originalCon = DataSourceUtils.getConnection(getDataSource());
      Connection con = originalCon;
      if (con instanceof DelegatingConnection) {
        DelegatingConnection<?> delCon = (DelegatingConnection<?>) con;
        con = delCon.getInnermostDelegate();
      }

      Preconditions.checkState(con instanceof PGConnection,
          "bulk-loading only works with a PostgreSQL JDBC connection");

      // Postgres JDBC4 8.4 driver now supports the copy API
      PGConnection pgCon = (PGConnection) con;
      pgCon.getCopyAPI().copyOut(sql, resource.getOutputStream());

      DataSourceUtils.releaseConnection(originalCon, getDataSource());

    } catch (SQLException e) {
      throw new DatabaseAccessException(e);
    } catch (IOException e) {
      throw new FileAccessException(e);
    }
  }

  // get a list of indexes on the imported Snd created tables tables which are
  // not
  // auto-created by postgres (namely, primary key and unique constraints)
  // exploits the fact that the index has the same name as the constraint
  private List<String> listIndexesOnTables(List<String> tables) {
    String sql = "" + "SELECT indexname " + "FROM pg_indexes " + "WHERE tablename IN ("
        + StringUtils.repeat("?", ",", tables.size()) + ") " + "AND lower(indexname) NOT IN "
        + "	(SELECT lower(conname) FROM pg_constraint WHERE contype in ('p', 'u'))";

    return getJdbcTemplate().query(sql, tables.toArray(), stringRowMapper());
  }

  private List<String> listIndexDefinitions(boolean used) {
    return listIndexDefinitions(used, allTables());
  }

  /*
   * Returns the CREATE INDEX statement for all indexes on the Annis tables,
   * that are not auto-created by PostgreSQL (primary keys and unique
   * constraints).
   *
   * @param used If True, return used indexes. If False, return unused indexes
   * (scan count is 0).
   */
  public List<String> listIndexDefinitions(boolean used, List<String> tables) {
    String scansOp = used ? "!=" : "=";
    String sql = "SELECT pg_get_indexdef(x.indexrelid) AS indexdef " + "FROM pg_index x, pg_class c "
        + "WHERE x.indexrelid = c.oid " + "AND c.relname IN ( " + StringUtils.repeat("?", ",", tables.size()) + ") "
        + "AND pg_stat_get_numscans(x.indexrelid) " + scansOp + " 0";
    return getJdbcTemplate().query(sql, tables.toArray(), stringRowMapper());
  }

  public List<String> listIndexDefinitions(String... tables) {
    String sql = "" + "SELECT pg_get_indexdef(x.indexrelid) AS indexdef " + "FROM pg_index x, pg_class c, pg_indexes i "
        + "WHERE x.indexrelid = c.oid " + "AND c.relname = i.indexname " + "AND i.tablename IN ( "
        + StringUtils.repeat("?", ",", tables.length) + " )";
    return getJdbcTemplate().query(sql, tables, new ParameterizedSingleColumnRowMapper<String>());
  }

  public List<String> listUsedIndexes(String... tables) {
    String sql = "" + "SELECT pg_get_indexdef(x.indexrelid) AS indexdef " + "FROM pg_index x, pg_class c, pg_indexes i "
        + "WHERE x.indexrelid = c.oid " + "AND c.relname = i.indexname " + "AND i.tablename IN ( "
        + StringUtils.repeat("?", ",", tables.length) + " ) " + "AND pg_stat_get_numscans(x.indexrelid) != 0";
    return getJdbcTemplate().query(sql, tables, new ParameterizedSingleColumnRowMapper<String>());
  }

  public boolean resetStatistics() {
    try {
      getJdbcTemplate().queryForList("SELECT pg_stat_reset()");
      return true;
    } catch (DataAccessException e) {
      return false;
    }
  }

  /**
   * Retrieves the name of the top level corpus in the corpus.tab file.
   *
   * <p>
   * At this point, the tab files must be in the staging area.
   * </p>
   *
   * @return The name of the toplevel corpus or an empty String if no top level
   *         corpus is found.
   */
  private String getTopLevelCorpusFromTmpArea() {
    String sql = "SELECT name FROM " + tableInStagingArea("corpus") + " WHERE type='CORPUS'\n"
        + "AND pre = (SELECT min(pre) FROM " + tableInStagingArea("corpus") + ")\n"
        + "AND post = (SELECT max(post) FROM " + tableInStagingArea("corpus") + ")";

    return getJdbcTemplate().query(sql, new ResultSetExtractor<String>() {
      @Override
      public String extractData(ResultSet rs) throws SQLException, DataAccessException {
        if (rs.next()) {
          return rs.getString("name");
        } else {
          return null;
        }
      }
    });
  }

  ///// Getter / Setter
  public boolean isTemporaryStagingArea() {
    return temporaryStagingArea;
  }

  public void setTemporaryStagingArea(boolean temporaryStagingArea) {
    this.temporaryStagingArea = temporaryStagingArea;
  }

  /**
   * Get the name and version of the schema this @{link AdministrationDao} is
   * configured to work with.
   *
   * @return
   */
  public String getSchemaVersion() {
    return schemaVersion;
  }

  public void setSchemaVersion(String schemaVersion) {
    this.schemaVersion = schemaVersion;
  }

  public Map<String, String> getMimeTypeMapping() {
    return mimeTypeMapping;
  }

  public void setMimeTypeMapping(Map<String, String> mimeTypeMapping) {
    this.mimeTypeMapping = mimeTypeMapping;
  }

  public Map<String, String> getTableInsertSelect() {
    return tableInsertSelect;
  }

  public void setTableInsertSelect(Map<String, String> tableInsertSelect) {
    this.tableInsertSelect = tableInsertSelect;
  }

  public Map<String, String> getTableInsertFrom() {
    return tableInsertFrom;
  }

  public void setTableInsertFrom(Map<String, String> tableInsertFrom) {
    this.tableInsertFrom = tableInsertFrom;
  }

  /**
   * Generates example queries if no example queries tab file is defined by the
   * user.
   */
  private void generateExampleQueries(String toplevelCorpusName) {
    // set in the annis.properties file.
    if (generateExampleQueries == EXAMPLE_QUERIES_CONFIG.TRUE) {
      queriesGenerator.generateQueries(toplevelCorpusName);
    }
  }

  /**
   * @return the generateExampleQueries
   */
  public EXAMPLE_QUERIES_CONFIG isGenerateExampleQueries() {
    return generateExampleQueries;
  }

  /**
   * @param generateExampleQueries
   *          the generateExampleQueries to set
   */
  public void setGenerateExampleQueries(EXAMPLE_QUERIES_CONFIG generateExampleQueries) {
    this.generateExampleQueries = generateExampleQueries;
  }

  /**
   * @return the queriesGenerator
   */
  public QueriesGenerator getQueriesGenerator() {
    return queriesGenerator;
  }

  /**
   * @param queriesGenerator
   *          the queriesGenerator to set
   */
  public void setQueriesGenerator(QueriesGenerator queriesGenerator) {
    this.queriesGenerator = queriesGenerator;
  }

  public DeleteCorpusDao getDeleteCorpusDao() {
    return deleteCorpusDao;
  }

  public void setDeleteCorpusDao(DeleteCorpusDao deleteCorpusDao) {
    this.deleteCorpusDao = deleteCorpusDao;
  }

  public boolean isHackDistinctLeftRightToken() {
    return hackDistinctLeftRightToken;
  }

  public void setHackDistinctLeftRightToken(boolean hackDistinctLeftRightToken) {
    this.hackDistinctLeftRightToken = hackDistinctLeftRightToken;
  }

  /**
   * Checks, if a already exists a corpus with the same name of the top level
   * corpus in the corpus.tab file. If this is the case an Exception is thrown
   * and the import is aborted.
   *
   * @throws annis.administration.DefaultAdministrationDao.ConflictingCorpusException
   */
  private void checkTopLevelCorpus() throws ConflictingCorpusException {
    String corpusName = getTopLevelCorpusFromTmpArea();
    if (existConflictingTopLevelCorpus(corpusName)) {
      String msg = "There already exists a top level corpus with the name: " + corpusName;
      throw new ConflictingCorpusException(msg);
    }
  }

  private ANNISFormatVersion getANNISFormatVersion(String path) {
    File pathDir = new File(path);
    if (pathDir.isDirectory()) {
      // check for existance of "annis.version" file
      File versionFile = new File(pathDir, "annis.version");
      if (versionFile.isFile() && versionFile.exists()) {
        try {
          // read the first line
          String firstLine = Files.readFirstLine(versionFile, Charsets.UTF_8);
          if ("3.3".equals(firstLine.trim())) {
            return ANNISFormatVersion.V3_3;
          }
        } catch (IOException ex) {
          log.warn("Could not read annis.version file", ex);
        }
      } else {
        // we have to distinguish between 3.1 and 3.2
        File nodeTab = new File(pathDir, "node.tab");
        if (nodeTab.isFile() && nodeTab.exists()) {
          try {
            String firstLine = Files.readFirstLine(nodeTab, Charsets.UTF_8);
            List<String> cols = Splitter.on('\t').splitToList(firstLine);
            if (cols.size() == 13) {
              return ANNISFormatVersion.V3_2;
            } else if (cols.size() == 10) {
              return ANNISFormatVersion.V3_1;
            }
          } catch (IOException ex) {
            log.warn("Could not read node.tab file", ex);
          }
        }
      }
    }
    return ANNISFormatVersion.UNKNOWN;
  }

  public static class ConflictingCorpusException extends AnnisException {

    public ConflictingCorpusException(String msg) {
      super(msg);
    }
  }

  public static class Offsets {

    private final long corpusID;
    private final long corpusPost;
    private final long nodeID;

    public Offsets(long corpusID, long corpusPost, long nodeID) {
      this.corpusID = corpusID;
      this.corpusPost = corpusPost;
      this.nodeID = nodeID;
    }

    public long getCorpusID() {
      return corpusID;
    }

    public long getCorpusPost() {
      return corpusPost;
    }

    public MapSqlParameterSource makeArgs() {
      return new MapSqlParameterSource().addValue(":offset_corpus_id", corpusID)
          .addValue(":offset_corpus_post", corpusPost).addValue(":offset_node_id", nodeID);
    }
  }

}
