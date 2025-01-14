/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.relational;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.apache.kafka.common.config.ConfigDef.Importance;
import org.apache.kafka.common.config.ConfigDef.Type;
import org.apache.kafka.common.config.ConfigDef.Width;

import io.debezium.config.CommonConnectorConfig;
import io.debezium.config.Configuration;
import io.debezium.config.EnumeratedValue;
import io.debezium.config.Field;
import io.debezium.config.Field.ValidationOutput;
import io.debezium.jdbc.JdbcValueConverters.DecimalMode;
import io.debezium.relational.Selectors.TableIdToStringMapper;
import io.debezium.relational.Tables.TableFilter;

/**
 * Configuration options shared across the relational CDC connectors.
 *
 * @author Gunnar Morling
 */
public abstract class RelationalDatabaseConnectorConfig extends CommonConnectorConfig {
    private static final String TABLE_BLACKLIST_NAME = "table.blacklist";
    private static final String TABLE_WHITELIST_NAME = "table.whitelist";

    /**
     * The set of predefined DecimalHandlingMode options or aliases.
     */
    public enum DecimalHandlingMode implements EnumeratedValue {
        /**
         * Represent {@code DECIMAL} and {@code NUMERIC} values as precise {@link BigDecimal} values, which are
         * represented in change events in a binary form. This is precise but difficult to use.
         */
        PRECISE("precise"),

        /**
         * Represent {@code DECIMAL} and {@code NUMERIC} values as a string values. This is precise, it supports also special values
         * but the type information is lost.
         */
        STRING("string"),

        /**
         * Represent {@code DECIMAL} and {@code NUMERIC} values as precise {@code double} values. This may be less precise
         * but is far easier to use.
         */
        DOUBLE("double");

        private final String value;

        private DecimalHandlingMode(String value) {
            this.value = value;
        }

        @Override
        public String getValue() {
            return value;
        }

        public DecimalMode asDecimalMode() {
            switch (this) {
                case DOUBLE:
                    return DecimalMode.DOUBLE;
                case STRING:
                    return DecimalMode.STRING;
                case PRECISE:
                default:
                    return DecimalMode.PRECISE;
            }
        }

        /**
         * Determine if the supplied value is one of the predefined options.
         *
         * @param value the configuration property value; may not be null
         * @return the matching option, or null if no match is found
         */
        public static DecimalHandlingMode parse(String value) {
            if (value == null) {
                return null;
            }
            value = value.trim();
            for (DecimalHandlingMode option : DecimalHandlingMode.values()) {
                if (option.getValue().equalsIgnoreCase(value)) {
                    return option;
                }
            }
            return null;
        }

        /**
         * Determine if the supplied value is one of the predefined options.
         *
         * @param value the configuration property value; may not be null
         * @param defaultValue the default value; may be null
         * @return the matching option, or null if no match is found and the non-null default is invalid
         */
        public static DecimalHandlingMode parse(String value, String defaultValue) {
            DecimalHandlingMode mode = parse(value);
            if (mode == null && defaultValue != null) {
                mode = parse(defaultValue);
            }
            return mode;
        }
    }

    public static final Field SERVER_NAME = Field.create(DATABASE_CONFIG_PREFIX + "server.name")
            .withDisplayName("Namespace")
            .withType(Type.STRING)
            .withWidth(Width.MEDIUM)
            .withImportance(Importance.HIGH)
            .withValidation(Field::isRequired)
            .withDescription("Unique name that identifies the database server and all "
                    + "recorded offsets, and that is used as a prefix for all schemas and topics. "
                    + "Each distinct installation should have a separate namespace and be monitored by "
                    + "at most one Debezium connector.");

    /**
     * A comma-separated list of regular expressions that match the fully-qualified names of tables to be monitored.
     * Fully-qualified names for tables are of the form {@code <databaseName>.<tableName>} or
     * {@code <databaseName>.<schemaName>.<tableName>}. May not be used with {@link #TABLE_BLACKLIST}, and superseded by database
     * inclusions/exclusions.
     */
    public static final Field TABLE_WHITELIST = Field.create(TABLE_WHITELIST_NAME)
                                                     .withDisplayName("Included tables")
                                                     .withType(Type.LIST)
                                                     .withWidth(Width.LONG)
                                                     .withImportance(Importance.HIGH)
                                                     .withValidation(Field::isListOfRegex)
                                                     .withDescription("The tables for which changes are to be captured");

    /**
     * A comma-separated list of regular expressions that match the fully-qualified names of tables to be excluded from
     * monitoring. Fully-qualified names for tables are of the form {@code <databaseName>.<tableName>} or
     * {@code <databaseName>.<schemaName>.<tableName>}. May not be used with {@link #TABLE_WHITELIST}.
     */
    public static final Field TABLE_BLACKLIST = Field.create(TABLE_BLACKLIST_NAME)
                                                     .withDisplayName("Excluded tables")
                                                     .withType(Type.STRING)
                                                     .withWidth(Width.LONG)
                                                     .withImportance(Importance.MEDIUM)
                                                     .withValidation(Field::isListOfRegex, RelationalDatabaseConnectorConfig::validateTableBlacklist)
                                                     .withInvisibleRecommender();

    public static final Field TABLE_IGNORE_BUILTIN = Field.create("table.ignore.builtin")
            .withDisplayName("Ignore system databases")
            .withType(Type.BOOLEAN)
            .withWidth(Width.SHORT)
            .withImportance(Importance.LOW)
            .withDefault(true)
            .withValidation(Field::isBoolean)
            .withDescription("Flag specifying whether built-in tables should be ignored.");

    /**
     * A comma-separated list of regular expressions that match fully-qualified names of columns to be excluded from monitoring
     * and change messages. The exact form of fully qualified names for columns might vary between connector types.
     * For instance, they could be of the form {@code <databaseName>.<tableName>.<columnName>} or
     * {@code <schemaName>.<tableName>.<columnName>} or {@code <databaseName>.<schemaName>.<tableName>.<columnName>}.
     */
    public static final Field COLUMN_BLACKLIST = Field.create("column.blacklist")
            .withDisplayName("Exclude Columns")
            .withType(Type.STRING)
            .withWidth(Width.LONG)
            .withImportance(Importance.MEDIUM)
            .withDescription("");

    public static final Field DECIMAL_HANDLING_MODE = Field.create("decimal.handling.mode")
            .withDisplayName("Decimal Handling")
            .withEnum(DecimalHandlingMode.class, DecimalHandlingMode.PRECISE)
            .withWidth(Width.SHORT)
            .withImportance(Importance.MEDIUM)
            .withDescription("Specify how DECIMAL and NUMERIC columns should be represented in change events, including:"
                    + "'precise' (the default) uses java.math.BigDecimal to represent values, which are encoded in the change events using a binary representation and Kafka Connect's 'org.apache.kafka.connect.data.Decimal' type; "
                    + "'string' uses string to represent values; "
                    + "'double' represents values using Java's 'double', which may not offer the precision but will be far easier to use in consumers.");

    public static final Field SNAPSHOT_SELECT_STATEMENT_OVERRIDES_BY_TABLE = Field.create("snapshot.select.statement.overrides")
            .withDisplayName("List of tables where the default select statement used during snapshotting should be overridden.")
            .withType(Type.STRING)
            .withWidth(Width.LONG)
            .withImportance(Importance.MEDIUM)
            .withDescription(" This property contains a comma-separated list of fully-qualified tables (DB_NAME.TABLE_NAME) or (SCHEMA_NAME.TABLE_NAME), depending on the" +
                    "specific connectors . Select statements for the individual tables are " +
                    "specified in further configuration properties, one for each table, identified by the id 'snapshot.select.statement.overrides.[DB_NAME].[TABLE_NAME]' or " +
                    "'snapshot.select.statement.overrides.[SCHEMA_NAME].[TABLE_NAME]', respectively. " +
                    "The value of those properties is the select statement to use when retrieving data from the specific table during snapshotting. " +
                    "A possible use case for large append-only tables is setting a specific point where to start (resume) snapshotting, in case a previous snapshotting was interrupted.");

    /**
     * A comma-separated list of regular expressions that match schema names to be monitored.
     * May not be used with {@link #SCHEMA_BLACKLIST}.
     */
    public static final Field SCHEMA_WHITELIST = Field.create("schema.whitelist")
                                                      .withDisplayName("Schemas")
                                                      .withType(Type.LIST)
                                                      .withWidth(Width.LONG)
                                                      .withImportance(Importance.HIGH)
                                                      .withDependents(TABLE_WHITELIST_NAME)
                                                      .withDescription("The schemas for which events should be captured");

    /**
     * A comma-separated list of regular expressions that match schema names to be excluded from monitoring.
     * May not be used with {@link #SCHEMA_WHITELIST}.
     */
    public static final Field SCHEMA_BLACKLIST = Field.create("schema.blacklist")
                                                      .withDisplayName("Exclude Schemas")
                                                      .withType(Type.STRING)
                                                      .withWidth(Width.LONG)
                                                      .withImportance(Importance.MEDIUM)
                                                      .withValidation(RelationalDatabaseConnectorConfig::validateSchemaBlacklist)
                                                      .withInvisibleRecommender()
                                                      .withDescription("The schemas for which events must not be captured");

    private final RelationalTableFilters tableFilters;

    protected RelationalDatabaseConnectorConfig(Configuration config, String logicalName, TableFilter systemTablesFilter,
                                                TableIdToStringMapper tableIdMapper, int defaultSnapshotFetchSize) {
        super(config, logicalName, defaultSnapshotFetchSize);

        if (systemTablesFilter != null && tableIdMapper != null) {
            this.tableFilters = new RelationalTableFilters(config, systemTablesFilter, tableIdMapper);
        }
        // handled by sub-classes for the time being
        else {
            this.tableFilters = null;
        }
    }

    public RelationalTableFilters getTableFilters() {
        return tableFilters;
    }

    /**
     * Returns the Decimal mode Enum for {@code decimal.handling.mode}
     * configuration. This defaults to {@code precise} if nothing is provided.
     */
    public DecimalMode getDecimalMode() {
        return DecimalHandlingMode
                .parse(this.getConfig().getString(DECIMAL_HANDLING_MODE))
                .asDecimalMode();
    }

    private static int validateTableBlacklist(Configuration config, Field field, ValidationOutput problems) {
        String whitelist = config.getString(TABLE_WHITELIST);
        String blacklist = config.getString(TABLE_BLACKLIST);

        if (whitelist != null && blacklist != null) {
            problems.accept(TABLE_BLACKLIST, blacklist, "Table whitelist is already specified");
            return 1;
        }

        return 0;
    }

    /**
     * Returns any SELECT overrides, if present.
     */
    public Map<TableId, String> getSnapshotSelectOverridesByTable() {
        String tableList = getConfig().getString(SNAPSHOT_SELECT_STATEMENT_OVERRIDES_BY_TABLE);

        if (tableList == null) {
            return Collections.emptyMap();
        }

        Map<TableId, String> snapshotSelectOverridesByTable = new HashMap<>();

        for (String table : tableList.split(",")) {
            snapshotSelectOverridesByTable.put(
                TableId.parse(table),
                getConfig().getString(SNAPSHOT_SELECT_STATEMENT_OVERRIDES_BY_TABLE + "." + table)
            );
        }

        return Collections.unmodifiableMap(snapshotSelectOverridesByTable);
    }

    private static int validateSchemaBlacklist(Configuration config, Field field, Field.ValidationOutput problems) {
        String whitelist = config.getString(SCHEMA_WHITELIST);
        String blacklist = config.getString(SCHEMA_BLACKLIST);
        if (whitelist != null && blacklist != null) {
            problems.accept(SCHEMA_BLACKLIST, blacklist, "Schema whitelist is already specified");
            return 1;
        }
        return 0;
    }
}
