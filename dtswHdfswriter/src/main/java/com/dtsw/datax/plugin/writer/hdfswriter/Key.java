package com.dtsw.datax.plugin.writer.hdfswriter;

/**
 * Created by shf on 15/10/8.
 */
public class Key {
    // must have
    public static final String PATH = "path";

    public static final String JAVA_SECURITY_KRB5_CONF = "java.security.krb5.conf";
    public static final String KERBEROS_KRB5_CONF = "kerberosKrb5Conf";
    //must have
    public final static String DEFAULT_FS = "defaultFS";
    public final static String IS_HIVE_ENABLE = "haveHive";
    public final static String USER_NAME = "userName";
    public final static String PASSWORD = "password";
    public final static String DATABASE = "database";
    public final static String ADDRESS = "address";

    public final static String HADOOP_CONFIG_PATH = "hadoopConfigPath";

    //must have
    public final static String FILE_TYPE = "fileType";
    // must have
    public static final String FILE_NAME = "fileName";
    // must have for column
    public static final String COLUMN = "column";
    public static final String NAME = "name";
    public static final String TYPE = "type";
    public static final String SCALE = "scale";

    public static final String DATE_FORMAT = "dateFormat";
    // must have
    public static final String WRITE_MODE = "writeMode";
    // must have
    public static final String FIELD_DELIMITER = "fieldDelimiter";
    // not must, default UTF-8
    public static final String ENCODING = "encoding";
    // not must, default no compress
    public static final String COMPRESS = "compress";
    // not must, not default \N
    public static final String NULL_FORMAT = "nullFormat";
    // Kerberos
    public static final String HAVE_KERBEROS = "haveKerberos";
    public static final String KERBEROS_KEYTAB_FILE_PATH = "kerberosKeytabFilePath";
    public static final String KERBEROS_PRINCIPAL = "kerberosPrincipal";
    // hadoop config
    public static final String HADOOP_CONFIG = "hadoopConfig";

    // useOldRawDataTransf
    public final static String PARQUET_FILE_USE_RAW_DATA_TRANSF = "useRawDataTransf";

    public final static String DATAX_PARQUET_MODE = "dataxParquetMode";

    // hdfs username 默认值 admin
    public final static String HDFS_USERNAME = "hdfsUsername";

    public static final String PROTECTION = "protection";

    public static final String PARQUET_SCHEMA = "parquetSchema";
    public static final String PARQUET_MERGE_RESULT = "parquetMergeResult";
    public static final String PRECISION = "precision";
    public static final String TABLE_NAME = "tableName";
    public static final String JDBC_URL = "jdbcUrl";
    public static final String PARTITION_FIELDS = "partitionField";
}
