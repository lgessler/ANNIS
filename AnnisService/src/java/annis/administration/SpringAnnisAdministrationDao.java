package annis.administration;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.apache.commons.io.FileUtils;
import org.apache.log4j.Logger;
import org.postgresql.PGConnection;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.jdbc.core.simple.ParameterizedSingleColumnRowMapper;
import org.springframework.jdbc.core.simple.SimpleJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceUtils;

import annis.externalFiles.ExternalFileMgrDAO;

/**
 * - Transaktionen
 * - Datenbank-Zugriffsrechte für verschiedene Methoden
 * - Reihenfolge der Aufrufe
 * - Skripte in $ANNIS_HOME/scripts
 * - COPY mechanism for PostgreSQL, see http://kato.iki.fi/sw/db/postgresql/jdbc/copy/
 */
public class SpringAnnisAdministrationDao {

	private Logger log = Logger.getLogger(this.getClass());
	
	// helper object to store external files
	@Autowired private ExternalFileMgrDAO externalFileMgrDao;
	
	// external files path
	private String externalFilesPath;
	
	// script path
	private String scriptPath;
	
	// use Spring's JDBC support
	private SimpleJdbcTemplate simpleJdbcTemplate;
	private JdbcOperations jdbcOperations;
	
	// save the datasource to manually retrieve connections (needed for bulk-import)
	private DataSource dataSource;
	
	// tables imported from bulk files
	// DO NOT CHANGE THE ORDER OF THIS LIST!  Doing so may cause foreign key failures during import.
	private String[] importedTables = {
			"corpus", "corpus_annotation",
			"text", "node", "node_annotation", 
			"component", "rank", "edge_annotation", 
			"corp_2_viz", "xcorp_2_viz"
	};
	
	// tables created during import
	private String[] createdTables = { "corpus_stats" };
	
	// materialized tables
	private String[] materializedTables = 
		{ "edges", "struct_annotation", "rank_annotations", "rank_text_ref" };
	
	///// Subtasks of creating the database
	
	void dropDatabase(String database) {
		String sql = "SELECT count(*) FROM pg_database WHERE datname = :database";
		SqlParameterSource args = makeArgs().addValue("database", database);
		int count = simpleJdbcTemplate.queryForInt(sql, args);
		if (count != 0) {
			log.debug("dropping existing database");
			jdbcOperations.execute("DROP DATABASE " + database);
		}
	}

	void dropUser(String username) {
		String sql = "SELECT count(*) FROM pg_user WHERE usename = :username";
		SqlParameterSource args = makeArgs().addValue("username", username);
		int count = simpleJdbcTemplate.queryForInt(sql, args);
		if (count != 0) {
			log.debug("dropping existing user");
			jdbcOperations.execute("DROP USER " + username);
		} 
	}

	void createUser(String username, String password) {
		log.info("creating user: " + username);
		jdbcOperations.execute("CREATE USER " + username + " PASSWORD '" + password + "'");
	}
	
	void createDatabase(String database) {
		log.info("creating database: " + database);
		jdbcOperations.execute("CREATE DATABASE " + database);
	}

	void installPlPython() {
		log.info("installing stored procedure language plpythonu");
		jdbcOperations.execute("CREATE LANGUAGE plpythonu");
	}

	void installPlPgSql() {
		log.info("installing stored procedure language plpgsql");
		jdbcOperations.execute("CREATE LANGUAGE plpgsql");
	}

	void createFunctionComputeRankLevel() {
		log.info("creating stored procedure: compute_rank");
		executeSqlFromScript("compute_rank_level.sql");
	}

	void createFunctionComputeSpannedTokens() {
		log.info("creating stored procedure: compute_spanned_tokens");
		executeSqlFromScript("compute_spanned_tokens.sql");
	}
	
	void createFunctionUniqueToplevelCorpusName() {
		log.info("creating trigger function: unique_toplevel_corpus_name");
		executeSqlFromScript("unique_toplevel_corpus_name.sql");
	}
	
	void createSchema() {
		log.info("creating Annis database schema");
		executeSqlFromScript("schema.sql");
	}
	
	void populateSchema() {
		log.info("populating the schema with default values");
		bulkloadTableFromResource("viz_type", new FileSystemResource(new File(scriptPath, "viz_type.tab")));
	}

	///// Subtasks of importing a corpus
	
	void dropIndexes() {
		log.info("dropping indexes");
		for (String index : listIndexesOnTables(allTables())) {
			log.debug("dropping index: " + index);
			jdbcOperations.execute("DROP INDEX " + index);
		}
	}

	void createStagingArea() {
		log.info("creating staging area");
		executeSqlFromScript("staging_area.sql");
	}

	void bulkImport(String path) {
		log.info("bulk-loading data");
		for (String table : importedTables)
			bulkloadTableFromResource(tableInStagingArea(table), new FileSystemResource(new File(path, table + ".tab")));
	}
	
	void computeTopLevelCorpus() {
		log.info("computing top-level corpus");
		executeSqlFromScript("toplevel_corpus.sql");
	}

	void importBinaryData(String path) {
		log.info("importing binary data");
		
		// pattern marks binary data in the bulk files and corresponding regexp
		String extFilePattern = "\\[ExtFile\\]";
		String extFileRegExp = "^" + extFilePattern + ".+$";

		// search for annotations that have binary data
		String selectSql = "SELECT DISTINCT value FROM _node_annotation WHERE value ~ :extFileRegExp";
		SqlParameterSource selectArgs = makeArgs().addValue("extFileRegExp", extFileRegExp);
		List<String> list = simpleJdbcTemplate.query(selectSql, stringRowMapper(), selectArgs);

		for (String externalData : list) {
			// get rid of marker
			String filename = externalData.replaceFirst(extFilePattern, "");

			// copy file to extData directory
			File file = new File(filename);
			try {
				// XXX: equivalent to new File(parent = path, child = filename)?
				File src = new File(path + "/ExtData/" + filename);
				File dst = new File(externalFilesPath + "/" + filename);
				log.debug("copying '" + src + "' to '" + dst + "'");
				FileUtils.copyFile(src, dst);
			} catch (IOException e) {
				throw new FileAccessException(e);
			}
			
			// store reference in database
			// XXX: mp3 mime type hard-coded
			String name = file.getName();
			String branch = file.getParent();
			// FIXME: operates directly on the main database, can result orphaned entries in extdata when import fails
			long id = externalFileMgrDao.putExtFile(name, branch, "audio/x-mp3");
			log.debug("external file '" + filename + "' inserted with id " + id);
			
			// update annotation value
			String updateSql = "UPDATE _node_annotation SET value = :id WHERE value = :externalData";
			SqlParameterSource updateArgs = makeArgs().addValue("id", id).addValue("externalData", externalData);
			simpleJdbcTemplate.update(updateSql, updateArgs);
		}
		
		// FIXME: should be done in the converter
		// XXX: set namespace of node or of annotation?
		log.debug("set namespace of nodes that are annotated with AUDIO to audio");
		String updateSql = "" +
				"UPDATE _node SET namespace = 'audio' " +
				"FROM _node_annotation " +
				"WHERE _node.id = _node_annotation.node_ref AND _node_annotation.name = 'AUDIO'";
		simpleJdbcTemplate.update(updateSql);
	}
	
	void computeLeftTokenRightToken() {
		log.info("computing values for struct.left_token and struct.right_token");
		executeSqlFromScript("left_token_right_token.sql");
	}
	
	void computeLevel() {
		log.info("computing values for rank.level");
		executeSqlFromScript("level.sql");
	}
	
	void computeComponents() {
		log.info("computing components by edge type");
		executeSqlFromScript("pointing_relations.sql");
	}
	
	void computeCorpusStatistics() {
		log.info("computing statistics for top-level corpus");
		executeSqlFromScript("corpus_stats.sql");
	}
	
	void updateIds() {
		log.info("updating IDs in staging area");
		executeSqlFromScript("update_ids.sql");
	}
	
	void applyConstraints() {
		log.info("activating relational constraints");
		executeSqlFromScript("constraints.sql");
	}
	
	void insertCorpus() {
		log.info("moving corpus from staging area to main db");
		for (String table : importedAndCreatedTables())
			jdbcOperations.execute("INSERT INTO " + table + " (SELECT * FROM " + tableInStagingArea(table) + ")");
	}
	
	void dropStagingArea() {
		log.info("dropping staging area");
		
		// tables must be dropped in reverse order
		List<String> tables = importedAndCreatedTables();
		Collections.reverse(tables);
		
		for (String table : tables)
			jdbcOperations.execute("DROP TABLE " + tableInStagingArea(table));
	}
	
	void dropMaterializedTables() {
		log.info("dropping materialized tables");
		
		// clone the array before returning the list, so the source array is not reversed
		// XXX: don't have to reverse the MATERIALIZED tables, do I?
		List<String> oldTables = Arrays.asList(materializedTables.clone());
		Collections.reverse(oldTables);
		
		for (String table : oldTables)
			jdbcOperations.execute("DROP TABLE " + table);
	}

	void createMaterializedTables() {
		log.info("creating materialized tables");
		for (String table : materializedTables) {
			executeSqlFromScript(table + ".sql");
		}
	}
	
	void rebuildIndexes() {
		log.info("creating indexes");
		executeSqlFromScript("indexes.sql");
	}
	
	///// Other sub tasks
	
	List<Long> listToplevelCorpora() {
		String sql = "SELECT id FROM corpus WHERE top_level = 'y'";
		return simpleJdbcTemplate.query(sql, ParameterizedSingleColumnRowMapper.newInstance(Long.class));
	}
	
	// XXX: not all collections are deleted
	void deleteCorpora(List<Long> ids) {
		// select this corpus and subcorpara for deletions
		log.debug("marking corpora for deletion");
		String sql = "" +
				"CREATE TEMPORARY TABLE __delete_corpus AS " +
				"SELECT DISTINCT c1.id AS id " +
				"FROM corpus c1, corpus c2 " +
				"WHERE c1.pre >= c2.pre AND c1.post <= c2.post " +
				"AND c2.id IN ( :ids )";
		SqlParameterSource args = makeArgs().addValue("ids", ids);
		simpleJdbcTemplate.update(sql, args);
		
		// the rest is done in a script
		executeSqlFromScript("delete_corpus.sql");
	}
	
	List<Map<String, Object>> listCorpusStats() {
		return simpleJdbcTemplate.queryForList("SELECT * FROM corpus_info");
	}
	
	List<Map<String, Object>> listTableStats() {
		return simpleJdbcTemplate.queryForList("SELECT * FROM table_stats");
	}

	List<String> listUsedIndexes() {
		log.info("retrieving list of used indexes");
		return listIndexDefinitions(true);
	}

	List<String> listUnusedIndexes() {
		log.info("retrieving list of unused indexes");
		return listIndexDefinitions(false);
	}
	
	///// Helpers
	
	private List<String> importedAndCreatedTables() {
		List<String> tables = new ArrayList<String>();
		tables.addAll(Arrays.asList(importedTables));
		tables.addAll(Arrays.asList(createdTables));
		return tables;
	}
	
	private List<String> allTables() {
		List<String> tables = new ArrayList<String>();
		tables.addAll(Arrays.asList(importedTables));
		tables.addAll(Arrays.asList(createdTables));
		tables.addAll(Arrays.asList(materializedTables));
		return tables;
	}
	
	// tables in the staging area have their names prefixed with "_"
	private String tableInStagingArea(String table) {
		return "_" + table;
	}
	
	private MapSqlParameterSource makeArgs() {
		return new MapSqlParameterSource();
	}
	
	private ParameterizedSingleColumnRowMapper<String> stringRowMapper() {
		return ParameterizedSingleColumnRowMapper.newInstance(String.class);
	}

	// reads the content from a resource into a string
	private String readSqlFromResource(Resource resource) {
		try {
			String sql = "";
			BufferedReader reader = new BufferedReader(new FileReader(resource.getFile()));
			for (String line = reader.readLine(); line != null; line = reader.readLine())
				sql += line + "\n";
			return sql;
		} catch (IOException e) {
			log.error("Couldn't read SQL schema from resource file.", e);
			throw new FileAccessException("Couldn't read SQL schema from resource file.", e);
		}
	}
	
	// executes an SQL script from $ANNIS_HOME/scripts
	private void executeSqlFromScript(String script) {
		Resource resource = new FileSystemResource(new File(scriptPath, script));
		log.debug("executing SQL script: " + resource.getFilename());
		String sql = readSqlFromResource(resource);
		jdbcOperations.execute(sql);
	}

	// bulk-loads a table from a resource
	private void bulkloadTableFromResource(String table, Resource resource) {
		log.debug("bulk-loading data from '" + resource.getFilename() + "' into table '" + table + "'");
		String sql = "COPY " + table + " FROM STDIN WITH DELIMITER E'\t' NULL AS 'NULL'";

		try {
			// retrieve the currently open connection if running inside a transaction
			Connection con = DataSourceUtils.getConnection(dataSource);
			
			// COPY mechanism for PostgreSQL, see http://kato.iki.fi/sw/db/postgresql/jdbc/copy/
			((PGConnection) con).getCopyAPI().copyIntoDB(sql, resource.getInputStream());
			
			// XXX: does this connection leak when it is not transaction managed?
			// can't close it, otherwise the next time it is used in code that does run
			// inside a transaction (the usual case during import) will fail
		} catch (SQLException e) {
			throw new DatabaseAccessException(e);
		} catch (IOException e) {
			throw new FileAccessException(e);
		}
	}

	// get a list of indexes on the imported and created tables tables which are not
	// auto-created by postgres (namely, primary key and unique constraints)
	// exploits the fact that the index has the same name as the constraint
	private List<String> listIndexesOnTables(List<String> tables) {
		String sql = "" +
				"SELECT indexname " +
				"FROM pg_indexes " +
				"WHERE tablename IN ( :tables ) " +
				"AND lower(indexname) NOT IN " +
				"	(SELECT lower(conname) FROM pg_constraint WHERE contype in ('p', 'u'))";
		SqlParameterSource args = makeArgs().addValue("tables", tables);
		return simpleJdbcTemplate.query(sql, stringRowMapper(), args);
	}

	/*
	 * Returns the CREATE INDEX statement for all indexes on the Annis tables,
	 * that are not auto-created by PostgreSQL (primary keys and unique constraints).
	 * 
	 * @param used	If True, return used indexes.
	 * 				If False, return unused indexes (scan count is 0).
	 */
	private List<String> listIndexDefinitions(boolean used) {
		String scansOp = used ? "!=" : "=";
		String sql = 
			"SELECT pg_get_indexdef(x.indexrelid) AS indexdef " +
			"FROM pg_index x, pg_class c " +
			"WHERE x.indexrelid = c.oid " +
			"AND c.relname IN ( :indexes ) " +
			"AND pg_stat_get_numscans(x.indexrelid) " + scansOp + " 0";
		SqlParameterSource args = makeArgs().addValue("indexes", listIndexesOnTables(allTables()));
		return simpleJdbcTemplate.query(sql, stringRowMapper(), args);
	}

	///// Getter / Setter
	
	@Autowired
	public void setDataSource(DataSource dataSource) {
		this.dataSource = dataSource;
		simpleJdbcTemplate = new SimpleJdbcTemplate(dataSource);
		jdbcOperations = simpleJdbcTemplate.getJdbcOperations();
	}

	public String getScriptPath() {
		return scriptPath;
	}

	public void setScriptPath(String scriptPath) {
		this.scriptPath = scriptPath;
	}

	public String getExternalFilesPath() {
		return externalFilesPath;
	}

	public void setExternalFilesPath(String externalFilesPath) {
		this.externalFilesPath = externalFilesPath;
	}

}
