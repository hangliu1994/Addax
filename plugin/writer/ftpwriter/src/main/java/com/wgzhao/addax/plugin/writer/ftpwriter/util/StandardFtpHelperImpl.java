/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package com.wgzhao.addax.plugin.writer.ftpwriter.util;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.serializer.SerializerFeature;
import com.wgzhao.addax.common.exception.AddaxException;
import com.wgzhao.addax.plugin.writer.ftpwriter.FtpWriterErrorCode;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPFile;
import org.apache.commons.net.ftp.FTPReply;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.UnknownHostException;
import java.util.HashSet;
import java.util.Set;

import static org.apache.commons.net.ftp.FTP.BINARY_FILE_TYPE;

public class StandardFtpHelperImpl
        implements IFtpHelper
{
    private static final Logger LOG = LoggerFactory.getLogger(StandardFtpHelperImpl.class);
    FTPClient ftpClient = null;

    @Override
    public void loginFtpServer(String host, int port, String username, String password, String keyPath, String keyPass, int timeout)
    {
        this.ftpClient = new FTPClient();
        try {
            this.ftpClient.setControlEncoding("UTF-8");
            // ???????????????ftp server???OS TYPE,FTPClient getSystemType()?????????????????????
            // this.ftpClient.configure(new FTPClientConfig(FTPClientConfig.SYS_UNIX));
            this.ftpClient.setDefaultTimeout(timeout);
            this.ftpClient.setConnectTimeout(timeout);
            this.ftpClient.setDataTimeout(timeout);

            // ????????????
            this.ftpClient.connect(host, port);
            this.ftpClient.login(username, password);

            this.ftpClient.enterRemotePassiveMode();
            this.ftpClient.enterLocalPassiveMode();
            // Always use binary transfer mode
            this.ftpClient.setFileType(BINARY_FILE_TYPE);
            int reply = this.ftpClient.getReplyCode();
            if (!FTPReply.isPositiveCompletion(reply)) {
                this.ftpClient.disconnect();
                String message = String
                        .format("???ftp???????????????????????????,host:%s, port:%s, username:%s, replyCode:%s",
                                host, port, username, reply);
                LOG.error(message);
                throw AddaxException.asAddaxException(
                        FtpWriterErrorCode.FAIL_LOGIN, message);
            }
        }
        catch (UnknownHostException e) {
            String message = String.format(
                    "?????????ftp??????????????????????????????????????????????????????: [%s] ???ftp?????????, errorMessage:%s",
                    host, e.getMessage());
            LOG.error(message);
            throw AddaxException.asAddaxException(
                    FtpWriterErrorCode.FAIL_LOGIN, message, e);
        }
        catch (IllegalArgumentException e) {
            String message = String.format(
                    "???????????????ftp?????????????????????????????????????????????: [%s], errorMessage:%s", port,
                    e.getMessage());
            LOG.error(message);
            throw AddaxException.asAddaxException(
                    FtpWriterErrorCode.FAIL_LOGIN, message, e);
        }
        catch (Exception e) {
            String message = String
                    .format("???ftp???????????????????????????,host:%s, port:%s, username:%s, errorMessage:%s",
                            host, port, username, e.getMessage());
            LOG.error(message);
            throw AddaxException.asAddaxException(
                    FtpWriterErrorCode.FAIL_LOGIN, message, e);
        }
    }

    @Override
    public void logoutFtpServer()
    {
        if (this.ftpClient.isConnected()) {
            try {
                this.ftpClient.logout();
            }
            catch (IOException e) {
                String message = String.format(
                        "???ftp???????????????????????????, errorMessage:%s", e.getMessage());
                LOG.error(message);
                throw AddaxException.asAddaxException(
                        FtpWriterErrorCode.FAIL_DISCONNECT, message, e);
            }
            finally {
                if (this.ftpClient.isConnected()) {
                    try {
                        this.ftpClient.disconnect();
                    }
                    catch (IOException e) {
                        String message = String.format(
                                "???ftp???????????????????????????, errorMessage:%s",
                                e.getMessage());
                        LOG.error(message);
                    }
                }
                this.ftpClient = null;
            }
        }
    }

    @Override
    public void mkdir(String directoryPath)
    {
        String message = String.format("????????????:%s???????????????,????????????ftp????????????????????????,????????????????????????",
                directoryPath);
        try {
            this.printWorkingDirectory();
            boolean isDirExist = this.ftpClient
                    .changeWorkingDirectory(directoryPath);
            if (!isDirExist) {
                int replayCode = this.ftpClient.mkd(directoryPath);
                message = String
                        .format("%s,replayCode:%s", message, replayCode);
                if (replayCode != FTPReply.COMMAND_OK
                        && replayCode != FTPReply.PATHNAME_CREATED) {
                    throw AddaxException.asAddaxException(
                            FtpWriterErrorCode.COMMAND_FTP_IO_EXCEPTION,
                            message);
                }
            }
        }
        catch (IOException e) {
            message = String.format("%s, errorMessage:%s", message,
                    e.getMessage());
            LOG.error(message);
            throw AddaxException.asAddaxException(
                    FtpWriterErrorCode.COMMAND_FTP_IO_EXCEPTION, message, e);
        }
    }

    @Override
    public void mkDirRecursive(String directoryPath)
    {
        StringBuilder dirPath = new StringBuilder();
        dirPath.append(IOUtils.DIR_SEPARATOR_UNIX);
        String[] dirSplit = StringUtils.split(directoryPath, IOUtils.DIR_SEPARATOR_UNIX);
        String message = String.format("????????????:%s???????????????,????????????ftp????????????????????????,????????????????????????", directoryPath);
        try {
            // ftp server???????????????????????????,????????????????????????
            for (String dirName : dirSplit) {
                dirPath.append(dirName);
                boolean mkdirSuccess = mkDirSingleHierarchy(dirPath.toString());
                dirPath.append(IOUtils.DIR_SEPARATOR_UNIX);
                if (!mkdirSuccess) {
                    throw AddaxException.asAddaxException(
                            FtpWriterErrorCode.COMMAND_FTP_IO_EXCEPTION,
                            message);
                }
            }
        }
        catch (IOException e) {
            message = String.format("%s, errorMessage:%s", message,
                    e.getMessage());
            LOG.error(message);
            throw AddaxException.asAddaxException(
                    FtpWriterErrorCode.COMMAND_FTP_IO_EXCEPTION, message, e);
        }
    }

    public boolean mkDirSingleHierarchy(String directoryPath)
            throws IOException
    {
        boolean isDirExist = this.ftpClient
                .changeWorkingDirectory(directoryPath);
        // ??????directoryPath???????????????,?????????
        if (!isDirExist) {
            int replayCode = this.ftpClient.mkd(directoryPath);
            return replayCode == FTPReply.COMMAND_OK
                    || replayCode == FTPReply.PATHNAME_CREATED;
        }
        return true;
    }

    @Override
    public OutputStream getOutputStream(String filePath)
    {
        try {
            this.printWorkingDirectory();
            String parentDir = filePath.substring(0,
                    StringUtils.lastIndexOf(filePath, IOUtils.DIR_SEPARATOR));
            this.ftpClient.changeWorkingDirectory(parentDir);
            this.printWorkingDirectory();
            OutputStream writeOutputStream = this.ftpClient
                    .appendFileStream(filePath);
            String message = String.format(
                    "??????FTP??????[%s]????????????????????????,???????????????%s????????????????????????????????????", filePath,
                    filePath);
            if (null == writeOutputStream) {
                throw AddaxException.asAddaxException(
                        FtpWriterErrorCode.OPEN_FILE_ERROR, message);
            }

            return writeOutputStream;
        }
        catch (IOException e) {
            String message = String.format(
                    "???????????? : [%s] ?????????,???????????????:[%s]????????????????????????????????????, errorMessage:%s",
                    filePath, filePath, e.getMessage());
            LOG.error(message);
            throw AddaxException.asAddaxException(
                    FtpWriterErrorCode.OPEN_FILE_ERROR, message);
        }
    }

    @Override
    public String getRemoteFileContent(String filePath)
    {
        try {
            this.completePendingCommand();
            this.printWorkingDirectory();
            String parentDir = filePath.substring(0,
                    StringUtils.lastIndexOf(filePath, IOUtils.DIR_SEPARATOR));
            this.ftpClient.changeWorkingDirectory(parentDir);
            this.printWorkingDirectory();
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream(22);
            this.ftpClient.retrieveFile(filePath, outputStream);
            String result = outputStream.toString();
            IOUtils.closeQuietly(outputStream, null);
            return result;
        }
        catch (IOException e) {
            String message = String.format(
                    "???????????? : [%s] ?????????,???????????????:[%s]???????????????????????????????????????, errorMessage:%s",
                    filePath, filePath, e.getMessage());
            LOG.error(message);
            throw AddaxException.asAddaxException(
                    FtpWriterErrorCode.OPEN_FILE_ERROR, message);
        }
    }

    @Override
    public Set<String> getAllFilesInDir(String dir, String prefixFileName)
    {
        Set<String> allFilesWithPointedPrefix = new HashSet<>();
        try {
            boolean isDirExist = this.ftpClient.changeWorkingDirectory(dir);
            if (!isDirExist) {
                throw AddaxException.asAddaxException(
                        FtpWriterErrorCode.COMMAND_FTP_IO_EXCEPTION,
                        String.format("????????????[%s]??????", dir));
            }
            this.printWorkingDirectory();
            FTPFile[] fs = this.ftpClient.listFiles(dir);
            // LOG.debug(JSON.toJSONString(this.ftpClient.listNames(dir)));
            LOG.debug(String.format("ls: %s",
                    JSON.toJSONString(fs, SerializerFeature.UseSingleQuotes)));
            for (FTPFile ff : fs) {
                String strName = ff.getName();
                if (strName.startsWith(prefixFileName)) {
                    allFilesWithPointedPrefix.add(strName);
                }
            }
        }
        catch (IOException e) {
            String message = String
                    .format("??????path:[%s] ????????????????????????I/O??????,????????????ftp????????????????????????,????????????ls??????, errorMessage:%s",
                            dir, e.getMessage());
            LOG.error(message);
            throw AddaxException.asAddaxException(
                    FtpWriterErrorCode.COMMAND_FTP_IO_EXCEPTION, message, e);
        }
        return allFilesWithPointedPrefix;
    }

    @Override
    public void deleteFiles(Set<String> filesToDelete)
    {
        String eachFile = null;
        boolean deleteOk;
        try {
            this.printWorkingDirectory();
            for (String each : filesToDelete) {
                LOG.info(String.format("delete file [%s].", each));
                eachFile = each;
                deleteOk = this.ftpClient.deleteFile(each);
                if (!deleteOk) {
                    String message = String.format(
                            "????????????:[%s] ?????????,????????????????????????????????????", eachFile);
                    throw AddaxException.asAddaxException(
                            FtpWriterErrorCode.COMMAND_FTP_IO_EXCEPTION,
                            message);
                }
            }
        }
        catch (IOException e) {
            String message = String.format(
                    "????????????:[%s] ???????????????,????????????????????????????????????,????????????????????????, errorMessage:%s",
                    eachFile, e.getMessage());
            LOG.error(message);
            throw AddaxException.asAddaxException(
                    FtpWriterErrorCode.COMMAND_FTP_IO_EXCEPTION, message, e);
        }
    }

    private void printWorkingDirectory()
    {
        try {
            LOG.info(String.format("current working directory:%s",
                    this.ftpClient.printWorkingDirectory()));
        }
        catch (Exception e) {
            LOG.warn(String.format("printWorkingDirectory error:%s",
                    e.getMessage()));
        }
    }

    @Override
    public void completePendingCommand()
    {
        /*
         * Q:After I perform a file transfer to the server,
         * printWorkingDirectory() returns null. A:You need to call
         * completePendingCommand() after transferring the file. wiki:
         * http://wiki.apache.org/commons/Net/FrequentlyAskedQuestions
         */
        try {
            boolean isOk = this.ftpClient.completePendingCommand();
            if (!isOk) {
                throw AddaxException.asAddaxException(
                        FtpWriterErrorCode.COMMAND_FTP_IO_EXCEPTION,
                        "??????ftp completePendingCommand??????????????????");
            }
        }
        catch (IOException e) {
            String message = String.format(
                    "??????ftp completePendingCommand??????????????????, errorMessage:%s",
                    e.getMessage());
            LOG.error(message);
            throw AddaxException.asAddaxException(
                    FtpWriterErrorCode.COMMAND_FTP_IO_EXCEPTION, message, e);
        }
    }
}
