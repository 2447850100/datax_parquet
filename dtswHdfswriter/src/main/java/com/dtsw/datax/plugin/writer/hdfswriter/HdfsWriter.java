package com.dtsw.datax.plugin.writer.hdfswriter;

import com.alibaba.datax.common.exception.DataXException;
import com.alibaba.datax.common.plugin.RecordReceiver;
import com.alibaba.datax.common.spi.Writer;
import com.alibaba.datax.common.util.Configuration;
import com.alibaba.datax.plugin.unstructuredstorage.writer.Constant;
import com.google.common.collect.Sets;
import org.apache.commons.io.Charsets;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Validate;
import org.apache.hadoop.fs.Path;
import org.apache.parquet.schema.MessageTypeParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


public class HdfsWriter extends Writer {
    public static class Job extends Writer.Job {
        private static final Logger LOG = LoggerFactory.getLogger(Job.class);

        private Configuration writerSliceConfig = null;

        private String defaultFS;
        private String path;
        public static String fileType;
        private String fileName;
        private List<Configuration> columns;
        private String writeMode;
        private String fieldDelimiter;
        private String compress;
        private String encoding;


        private String userName;

        private String password;

        private String jdbcUrl;

        // private String address;

        //private String database;

        private Boolean isEnableHive;

        private String tableName;

        static Character dirSeparator;
        static volatile boolean isFinished;

        static {
            dirSeparator = Optional.ofNullable(System.getProperty("dir_separator"))
                    .map(i -> i.toCharArray()[0]).orElse(IOUtils.DIR_SEPARATOR_UNIX);
        }

        public static final Set<String> SUPPORT_FORMAT = new HashSet<>(Arrays.asList("ORC", "PAR", "TEXT"));
        public static final Set<String> SUPPORTED_WRITE_MODE = new HashSet<>(Arrays.asList("append", "nonConflict", "truncate"));

        public static final Set<String> parSupportedCompress = Sets.newHashSet("NONE", "SNAPPY");

        public static final Set<String> orcSupportedCompress = Sets.newHashSet("NONE", "SNAPPY");

        public static final Set<String> textSupportedCompress = Sets.newHashSet("GZIP", "BZIP2");
        private HashSet<String> tmpFiles = new HashSet<String>();//临时文件全路径
        private HashSet<String> endFiles = new HashSet<String>();//最终文件全路径

        private HdfsHelper hdfsHelper = null;

        @Override
        public void init() {
            this.writerSliceConfig = this.getPluginJobConf();
            this.validateParameter();

            //创建textfile存储
            hdfsHelper = new HdfsHelper();

            hdfsHelper.getFileSystem(defaultFS, this.writerSliceConfig);
        }

        private void validateParameter() {


            this.defaultFS = this.writerSliceConfig.getNecessaryValue(Key.DEFAULT_FS, HdfsWriterErrorCode.REQUIRED_VALUE);
            //fileType check
            this.fileType = this.writerSliceConfig.getNecessaryValue(Key.FILE_TYPE, HdfsWriterErrorCode.REQUIRED_VALUE).toUpperCase().trim();
            if (!SUPPORT_FORMAT.contains(fileType)) {
                String message = String.format("[%s] 文件格式不支持， HdfsWriter插件目前仅支持 %s, ", fileType, SUPPORT_FORMAT);
                throw DataXException.asDataXException(HdfsWriterErrorCode.ILLEGAL_VALUE, message);
            }
            //配置了关联hive刷新分区相关信息 需要配置以下参数
            this.isEnableHive = this.writerSliceConfig.getBool(Key.IS_HIVE_ENABLE, true);
            if (isEnableHive) {
                this.userName = this.writerSliceConfig.getNecessaryValue(Key.USER_NAME,HdfsWriterErrorCode.CONFIG_INVALID_EXCEPTION);
                this.password = this.writerSliceConfig.getString(Key.PASSWORD, " ");
               // this.database = this.writerSliceConfig.getNecessaryValue(Key.DATABASE, HdfsWriterErrorCode.CONFIG_INVALID_EXCEPTION);
                this.jdbcUrl = this.writerSliceConfig.getNecessaryValue(Key.JDBC_URL, HdfsWriterErrorCode.CONFIG_INVALID_EXCEPTION);
                this.tableName = this.writerSliceConfig.getNecessaryValue(Key.TABLE_NAME, HdfsWriterErrorCode.CONFIG_INVALID_EXCEPTION);
            }
            //path
            this.path = this.writerSliceConfig.getNecessaryValue(Key.PATH, HdfsWriterErrorCode.REQUIRED_VALUE);
            if (!path.startsWith("/")) {
                String message = String.format("请检查参数path:[%s],需要配置为绝对路径", path);
                LOG.error(message);
                throw DataXException.asDataXException(HdfsWriterErrorCode.ILLEGAL_VALUE, message);
            }
            if (path.contains("*") || path.contains("?")) {
                String message = String.format("请检查参数path:[%s],不能包含*,?等特殊字符", path);
                LOG.error(message);
                throw DataXException.asDataXException(HdfsWriterErrorCode.ILLEGAL_VALUE, message);
            }
            //fileName
            this.fileName = this.writerSliceConfig.getNecessaryValue(Key.FILE_NAME, HdfsWriterErrorCode.REQUIRED_VALUE);
            //columns check
            this.columns = this.writerSliceConfig.getListConfiguration(Key.COLUMN);
            if (null == columns || columns.isEmpty()) {
                throw DataXException.asDataXException(HdfsWriterErrorCode.REQUIRED_VALUE, "您需要指定 columns");
            } else {
                for (Configuration eachColumnConf : columns) {
                    eachColumnConf.getNecessaryValue(Key.NAME, HdfsWriterErrorCode.COLUMN_REQUIRED_VALUE);
                    eachColumnConf.getNecessaryValue(Key.TYPE, HdfsWriterErrorCode.COLUMN_REQUIRED_VALUE);
                }
            }
            //writeMode check
            this.writeMode = this.writerSliceConfig.getNecessaryValue(Key.WRITE_MODE, HdfsWriterErrorCode.REQUIRED_VALUE);
            if (!SUPPORTED_WRITE_MODE.contains(writeMode)) {
                throw DataXException.asDataXException(HdfsWriterErrorCode.ILLEGAL_VALUE,
                        String.format("仅支持append, nonConflict, truncate三种模式, 不支持您配置的 writeMode 模式 : [%s]",
                                writeMode));
            }
            this.writerSliceConfig.set(Key.WRITE_MODE, writeMode);
            //fieldDelimiter check
            this.fieldDelimiter = this.writerSliceConfig.getString(Key.FIELD_DELIMITER, null);
            if (null == fieldDelimiter) {
                throw DataXException.asDataXException(HdfsWriterErrorCode.REQUIRED_VALUE,
                        String.format("您提供配置文件有误，[%s]是必填参数.", Key.FIELD_DELIMITER));
            } else if (1 != fieldDelimiter.length()) {
                // warn: if have, length must be one
                throw DataXException.asDataXException(HdfsWriterErrorCode.ILLEGAL_VALUE,
                        String.format("仅仅支持单字符切分, 您配置的切分为 : [%s]", fieldDelimiter));
            }

            //compress check
            this.compress = this.writerSliceConfig.getString(Key.COMPRESS, null);

            if (fileType.equalsIgnoreCase("TEXT")) {
                //用户可能配置的是compress:"",空字符串,需要将compress设置为null
                if (StringUtils.isBlank(compress)) {
                    this.writerSliceConfig.set(Key.COMPRESS, null);
                } else {
                    compress = compress.toUpperCase().trim();
                    if (!textSupportedCompress.contains(compress)) {
                        throw DataXException.asDataXException(HdfsWriterErrorCode.ILLEGAL_VALUE,
                                String.format("目前TEXT FILE仅支持GZIP、BZIP2 两种压缩, 不支持您配置的 compress 模式 : [%s]",
                                        compress));
                    }
                }
            } else if ("ORC".equalsIgnoreCase(fileType)) {
                if (null == compress) {
                    this.writerSliceConfig.set(Key.COMPRESS, "NONE");
                } else {
                    compress = compress.toUpperCase().trim();
                    if (!orcSupportedCompress.contains(compress)) {
                        throw DataXException.asDataXException(HdfsWriterErrorCode.ILLEGAL_VALUE,
                                String.format("目前ORC FILE仅支持SNAPPY压缩, 不支持您配置的 compress 模式 : [%s]",
                                        compress));
                    }
                }
            } else if ("PAR".equalsIgnoreCase(fileType)) {

                // parquet 默认的非压缩标志是 UNCOMPRESSED ，而不是常见的 NONE，这里统一为 NONE
                if (compress == null || "NONE".equals(compress) || "UNCOMPRESSED".equalsIgnoreCase(compress)) {
                    this.writerSliceConfig.set(Key.COMPRESS, "UNCOMPRESSED");
                } else {
                    compress = compress.toUpperCase().trim();
                    if (!parSupportedCompress.contains(compress)) {
                        throw DataXException.asDataXException(HdfsWriterErrorCode.ILLEGAL_VALUE,
                                String.format("目前PAR FILE仅支持SNAPPY压缩, 不支持您配置的 compress 模式 : [%s]",
                                        compress));
                    }
                }
            }
            //Kerberos check
            boolean haveKerberos = this.writerSliceConfig.getBool(Key.HAVE_KERBEROS, false);
            if (haveKerberos) {
                this.writerSliceConfig.getNecessaryValue(Key.KERBEROS_KEYTAB_FILE_PATH, HdfsWriterErrorCode.REQUIRED_VALUE);
                this.writerSliceConfig.getNecessaryValue(Key.KERBEROS_PRINCIPAL, HdfsWriterErrorCode.REQUIRED_VALUE);
            }

            // encoding check
            this.encoding = this.writerSliceConfig.getString(Key.ENCODING, Constant.DEFAULT_ENCODING);
            try {
                encoding = encoding.trim();
                this.writerSliceConfig.set(Key.ENCODING, encoding);
                Charsets.toCharset(encoding);
            } catch (Exception e) {
                throw DataXException.asDataXException(HdfsWriterErrorCode.ILLEGAL_VALUE,
                        String.format("不支持您配置的编码格式:[%s]", encoding), e);
            }
        }

        private static int getDecimalPrecision(String type) {

            String regEx = "[^0-9]";
            Pattern p = Pattern.compile(regEx);
            Matcher m = p.matcher(type);
            return Integer.parseInt(m.replaceAll(" ").trim().split(" ")[0]);

        }

        private static int getDecimalScale(String type) {

            if (!type.contains(",")) {
                return 0;
            } else {
                return Integer.parseInt(type.split(",")[1].replace(")", "").trim());
            }
        }


        @Override
        public void prepare() {
            try {
                if (!hdfsHelper.fileSystem.exists(new Path(path))) {
                    //如果不存在，创建文件夹
                    hdfsHelper.fileSystem.mkdirs(new Path(path));
                }
            } catch (IOException e) {
                throw DataXException.asDataXException(HdfsWriterErrorCode.ILLEGAL_VALUE,
                        String.format("您配置的path: [%s] 不是一个合法的目录, 请您注意文件重名, 不合法目录名等情况.",
                                path));
            }
            //若路径已经存在，检查path是否是目录
            if (hdfsHelper.isPathexists(path)) {
                if (!hdfsHelper.isPathDir(path)) {
                    throw DataXException.asDataXException(HdfsWriterErrorCode.ILLEGAL_VALUE,
                            String.format("您配置的path: [%s] 不是一个合法的目录, 请您注意文件重名, 不合法目录名等情况.",
                                    path));
                }
                //根据writeMode对目录下文件进行处理
                Path[] existFilePaths = hdfsHelper.hdfsDirList(path, fileName);
                boolean isExistFile = false;
                if (existFilePaths.length > 0) {
                    isExistFile = true;
                }
                /**
                 if ("truncate".equals(writeMode) && isExistFile ) {
                 LOG.info(String.format("由于您配置了writeMode truncate, 开始清理 [%s] 下面以 [%s] 开头的内容",
                 path, fileName));
                 hdfsHelper.deleteFiles(existFilePaths);
                 } else
                 */
                if ("append".equalsIgnoreCase(writeMode)) {
                    LOG.info(String.format("由于您配置了writeMode append, 写入前不做清理工作, [%s] 目录下写入相应文件名前缀  [%s] 的文件",
                            path, fileName));
                } else if ("nonconflict".equalsIgnoreCase(writeMode) && isExistFile) {
                    LOG.info(String.format("由于您配置了writeMode nonConflict, 开始检查 [%s] 下面的内容", path));
                    List<String> allFiles = new ArrayList<String>();
                    for (Path eachFile : existFilePaths) {
                        allFiles.add(eachFile.toString());
                    }
                    LOG.error(String.format("冲突文件列表为: [%s]", StringUtils.join(allFiles, ",")));
                    throw DataXException.asDataXException(HdfsWriterErrorCode.ILLEGAL_VALUE,
                            String.format("由于您配置了writeMode nonConflict,但您配置的path: [%s] 目录不为空, 下面存在其他文件或文件夹.", path));
                } else if ("truncate".equalsIgnoreCase(writeMode) && isExistFile) {
                    LOG.info(String.format("由于您配置了writeMode truncate,  [%s] 下面的内容将被覆盖重写", path));
                    hdfsHelper.deleteFiles(existFilePaths);
                }
            } else {
                throw DataXException.asDataXException(HdfsWriterErrorCode.ILLEGAL_VALUE,
                        String.format("您配置的path: [%s] 不存在, 请先在hive端创建对应的数据库和表.", path));
            }
        }

        @Override
        public void post() {
            hdfsHelper.renameFile(tmpFiles, endFiles);
            synchronized (HdfsWriter.class) {
                if (this.isEnableHive) {
                    if (!isFinished) {
                        if (HdfsHelper.haveKerberos) {
                            try (Connection connection = DriverManager.getConnection(this.jdbcUrl)) {
                                connection.prepareStatement("set hive.msck.path.validation=ignore").execute();
                                boolean execute = connection.prepareStatement(String.format("msck repair table %s", this.tableName)).execute();
                                if (!execute) {
                                    LOG.info("hive分区刷新成功");
                                }
                            } catch (Exception e) {
                                LOG.error(String.format("获取hive连接失败，请手动执行刷新指令: %s", String.format("msck repair table %s", this.tableName)));
                            }
                        } else {
                            String sql = "hive -u " + this.jdbcUrl + " -n " + this.userName + " -p " + this.password + "";
                            String shellSql = String.format("%s%s", sql, String.format(" -e 'set hive.msck.path.validation=ignore; msck repair table %s'", this.tableName));
                            executeCommand(shellSql);
                        }
                        isFinished = true;
                    }
                }
            }
        }

        private void executeCommand(String shellSql) {

            String spilt = ".bat";
            String os = System.getProperty("os.name");
            String temp = "shell_" + UUID.randomUUID();
            String url = temp + spilt;
            boolean isWindows = os.toLowerCase().startsWith("win");
            try {
                Process process;
                if (isWindows) {
                    FileWriter writer = new FileWriter(url);
                    writer.write(shellSql);
                    writer.close();
                    // 创建批处理文件
                    process = Runtime.getRuntime().exec("cmd.exe /c start /wait " + url);
                } else {
                    spilt = ".sh";
                    url = temp + spilt;
                    FileWriter writer = new FileWriter(url);
                    writer.write(shellSql);
                    writer.close();
                    process = Runtime.getRuntime().exec(new String[]{"/bin/bash", url});
                    LOG.info("执行 shellSql  " + shellSql);
                }

                if (!process.waitFor(20, TimeUnit.SECONDS)) {
                    throw new DataXException("执行command命令超时,请检查网络io");
                }

            } catch (Exception e) {
                LOG.error("异常原因:", e);
                LOG.error("数据已传输完,程序刷新hive分区出现问题，请手动前往目标源执行hive分区刷新指令 :" + String.format("hive -e 'set hive.msck.path.validation=ignore; msck repair table  %s  ' ", this.tableName));
            } finally {
                url = isWindows ? temp + ".bat" : temp + ".sh";
                File file = new File(url);
                file.delete();
            }
        }

        protected String commandInterpreter() {

            String os = System.getProperty("os.name");
            return os.toLowerCase().startsWith("win") ? "cmd.exe" : "sh";
        }

        private void buildProcess(String commandFile) throws IOException {
            // setting up user to run commands
            List<String> command = new LinkedList<>();

            //init process builder
            ProcessBuilder processBuilder = new ProcessBuilder();
            // setting up a working directory

            // merge error information to standard output stream
            processBuilder.redirectErrorStream(true);


            command.add(commandInterpreter());
            command.addAll(Collections.emptyList());
            command.add(commandFile);

            // setting commands
            processBuilder.command(command);
            processBuilder.start();


        }

        @Override
        public void destroy() {
            hdfsHelper.closeFileSystem();
        }

        @Override
        public List<Configuration> split(int mandatoryNumber) {
            LOG.info("begin do split...");
            List<Configuration> writerSplitConfigs = new ArrayList<Configuration>();
            String filePrefix = fileName;

            Set<String> allFiles = new HashSet<String>();

            //获取该路径下的所有已有文件列表
            if (hdfsHelper.isPathexists(path)) {
                allFiles.addAll(Arrays.asList(hdfsHelper.hdfsDirList(path)));
            }

            String fileSuffix;
            //临时存放路径
            String storePath = buildTmpFilePath(this.path);
            //最终存放路径
            String endStorePath = buildFilePath();
            this.path = endStorePath;
            for (int i = 0; i < mandatoryNumber; i++) {
                // handle same file name

                Configuration splitedTaskConfig = this.writerSliceConfig.clone();
                String fullFileName = null;
                String endFullFileName = null;

                fileSuffix = UUID.randomUUID().toString().replace('-', '_');

                fullFileName = String.format("%s%s%s__%s", defaultFS, storePath, filePrefix, fileSuffix);
                endFullFileName = String.format("%s%s%s__%s", defaultFS, endStorePath, filePrefix, fileSuffix);

                while (allFiles.contains(endFullFileName)) {
                    fileSuffix = UUID.randomUUID().toString().replace('-', '_');
                    fullFileName = String.format("%s%s%s__%s", defaultFS, storePath, filePrefix, fileSuffix);
                    endFullFileName = String.format("%s%s%s__%s", defaultFS, endStorePath, filePrefix, fileSuffix);
                }
                allFiles.add(endFullFileName);

                //设置临时文件全路径和最终文件全路径
                if ("GZIP".equalsIgnoreCase(this.compress)) {
                    this.tmpFiles.add(fullFileName + ".gz");
                    this.endFiles.add(endFullFileName + ".gz");
                } else if ("BZIP2".equalsIgnoreCase(compress)) {
                    this.tmpFiles.add(fullFileName + ".bz2");
                    this.endFiles.add(endFullFileName + ".bz2");
                } else {
                    this.tmpFiles.add(fullFileName);
                    this.endFiles.add(endFullFileName);
                }

                splitedTaskConfig
                        .set(com.alibaba.datax.plugin.unstructuredstorage.writer.Key.FILE_NAME,
                                fullFileName);

                LOG.info(String.format("splited write file name:[%s]",
                        fullFileName));

                writerSplitConfigs.add(splitedTaskConfig);
            }
            LOG.info("end do split.");
            return writerSplitConfigs;
        }

        private String buildFilePath() {
            boolean isEndWithSeparator = false;
            switch (dirSeparator) {
                case IOUtils.DIR_SEPARATOR_UNIX:
                    isEndWithSeparator = this.path.endsWith(String
                            .valueOf(IOUtils.DIR_SEPARATOR));
                    break;
                case IOUtils.DIR_SEPARATOR_WINDOWS:
                    isEndWithSeparator = this.path.endsWith(String
                            .valueOf(IOUtils.DIR_SEPARATOR_WINDOWS));
                    break;
                default:
                    break;
            }
            if (!isEndWithSeparator) {
                this.path = this.path + dirSeparator;
            }
            return this.path;
        }

        /**
         * 创建临时目录
         *
         * @param userPath
         * @return
         */
        private String buildTmpFilePath(String userPath) {
            String tmpFilePath;
            boolean isEndWithSeparator = false;
            switch (dirSeparator) {
                case IOUtils.DIR_SEPARATOR_UNIX:
                    isEndWithSeparator = userPath.endsWith(String
                            .valueOf(IOUtils.DIR_SEPARATOR));
                    break;
                case IOUtils.DIR_SEPARATOR_WINDOWS:
                    isEndWithSeparator = userPath.endsWith(String
                            .valueOf(IOUtils.DIR_SEPARATOR_WINDOWS));
                    break;
                default:
                    break;
            }
            String tmpSuffix;
            tmpSuffix = UUID.randomUUID().toString().replace('-', '_');
            if (!isEndWithSeparator) {
                tmpFilePath = String.format("%s__%s%s", userPath, tmpSuffix, dirSeparator);
            } else if ("/".equals(userPath)) {
                tmpFilePath = String.format("%s__%s%s", userPath, tmpSuffix, dirSeparator);
            } else {
                tmpFilePath = String.format("%s__%s%s", userPath.substring(0, userPath.length() - 1), tmpSuffix, dirSeparator);
            }
            while (hdfsHelper.isPathexists(tmpFilePath)) {
                tmpSuffix = UUID.randomUUID().toString().replace('-', '_');
                if (!isEndWithSeparator) {
                    tmpFilePath = String.format("%s__%s%s", userPath, tmpSuffix, dirSeparator);
                } else if ("/".equals(userPath)) {
                    tmpFilePath = String.format("%s__%s%s", userPath, tmpSuffix, dirSeparator);
                } else {
                    tmpFilePath = String.format("%s__%s%s", userPath.substring(0, userPath.length() - 1), tmpSuffix, dirSeparator);
                }
            }
            return tmpFilePath;
        }

        public void unitizeParquetConfig(Configuration writerSliceConfig) {
            String parquetSchema = writerSliceConfig.getString(Key.PARQUET_SCHEMA);
            if (StringUtils.isNotBlank(parquetSchema)) {
                LOG.info("parquetSchema has config. use parquetSchema:\n{}", parquetSchema);
                return;
            }

            List<Configuration> columns = writerSliceConfig.getListConfiguration(Key.COLUMN);
            if (columns == null || columns.isEmpty()) {
                throw DataXException.asDataXException("parquetSchema or column can't be blank!");
            }

            parquetSchema = generateParquetSchemaFromColumn(columns);
            // 为了兼容历史逻辑,对之前的逻辑做保留，但是如果配置的时候报错，则走新逻辑
            try {
                MessageTypeParser.parseMessageType(parquetSchema);
            } catch (Throwable e) {
                LOG.warn("The generated parquetSchema {} is illegal, try to generate parquetSchema in another way", parquetSchema);
                parquetSchema = HdfsHelper.generateParquetSchemaFromColumnAndType(columns);
                LOG.info("The last generated parquet schema is {}", parquetSchema);
            }
            writerSliceConfig.set(Key.PARQUET_SCHEMA, parquetSchema);
            LOG.info("dataxParquetMode use default fields.");
            writerSliceConfig.set(Key.DATAX_PARQUET_MODE, "fields");
        }

        private String generateParquetSchemaFromColumn(List<Configuration> columns) {
            StringBuffer parquetSchemaStringBuffer = new StringBuffer();
            parquetSchemaStringBuffer.append("message m {");
            for (Configuration column : columns) {
                String name = column.getString("name");
                Validate.notNull(name, "column.name can't be null");

                String type = column.getString("type");
                Validate.notNull(type, "column.type can't be null");

                String parquetColumn = String.format("optional %s %s;", type, name);
                parquetSchemaStringBuffer.append(parquetColumn);
            }
            parquetSchemaStringBuffer.append("}");
            String parquetSchema = parquetSchemaStringBuffer.toString();
            LOG.info("generate parquetSchema:\n{}", parquetSchema);
            return parquetSchema;
        }

    }


    public static class Task extends Writer.Task {
        private static final Logger LOG = LoggerFactory.getLogger(Task.class);

        private Configuration writerSliceConfig;

        private String defaultFS;
        private String fileType;
        private String fileName;

        private HdfsHelper hdfsHelper = null;

        @Override
        public void init() {
            this.writerSliceConfig = this.getPluginJobConf();

            this.defaultFS = this.writerSliceConfig.getString(Key.DEFAULT_FS);
            this.fileType = this.writerSliceConfig.getString(Key.FILE_TYPE);
            //得当的已经是绝对路径，eg：hdfs://10.101.204.12:9000/user/hive/warehouse/writer.db/text/test.textfile
            this.fileName = this.writerSliceConfig.getString(Key.FILE_NAME);

            hdfsHelper = new HdfsHelper();
            hdfsHelper.getFileSystem(defaultFS, writerSliceConfig);
        }

        @Override
        public void prepare() {

        }

        @Override
        public void startWrite(RecordReceiver lineReceiver) {
            LOG.info("begin do write...");
            LOG.info(String.format("write to file : [%s]", this.fileName));
            if (fileType.equalsIgnoreCase("TEXT")) {
                //写TEXT FILE
                hdfsHelper.textFileStartWrite(lineReceiver, this.writerSliceConfig, this.fileName,
                        this.getTaskPluginCollector());
            } else if (fileType.equalsIgnoreCase("ORC")) {
                //写ORC FILE
                hdfsHelper.orcFileStartWrite(lineReceiver, this.writerSliceConfig, this.fileName,
                        this.getTaskPluginCollector());
            } else if (fileType.equalsIgnoreCase("PAR")) {
                hdfsHelper.parquetFileStartWrite(lineReceiver, writerSliceConfig, fileName, getTaskPluginCollector());
            }

            LOG.info("end do write");
        }

        @Override
        public void post() {

        }

        @Override
        public void destroy() {

        }
    }
}
