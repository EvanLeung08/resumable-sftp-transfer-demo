package com.eshare.resumablesftp;

import com.jcraft.jsch.*;

import java.io.*;
import java.math.BigInteger;
import java.nio.file.*;
import java.security.MessageDigest;

public class SFTPResumeDownload {
    private static final int PORT = 22;

    public static void main(String[] args) {
        String user = "parallels";
        String passwd = "xxx";
        String host = "192.168.50.33";
        String localFilePath = "/Users/evan/Downloads/test10.mp4";
        String remoteFilePath = "/tmp/evan/test10.mp4";


        try {
            // 设置JSch
            JSch jsch = new JSch();
            Session session = jsch.getSession(user, host, PORT);
            session.setPassword(passwd);

            // 设置配置信息
            java.util.Properties config = new java.util.Properties();
            config.put("StrictHostKeyChecking", "no");
            session.setConfig(config);

            // 连接到服务器
            session.connect();

            // 打开SFTP通道
            Channel channel = session.openChannel("sftp");
            channel.connect();
            ChannelSftp sftpChannel = (ChannelSftp) channel;

            // 检查远程文件是否存在
            SftpATTRS attrs = null;
            try {
                attrs = sftpChannel.lstat(remoteFilePath);
            } catch (SftpException e) {
                if (e.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) {
                    throw new FileNotFoundException("Remote file does not exist: " + remoteFilePath);
                }
                throw e;
            }

            // 检查本地文件大小
            long localSize = new File(localFilePath).length();

            // 打开远程文件
            long remoteSize = attrs.getSize();

            // 检查文件是否正常
            if (localSize >= remoteSize) {
                throw new FileSystemAlreadyExistsException("Local file exists and please check the size: " + remoteFilePath);
            }


            /// 计算从哪里开始下载
            long startPos = Math.max(0, localSize);


            // 文件下载
            FileOutputStream fos = new FileOutputStream(localFilePath, true);
            InputStream is = sftpChannel.get(remoteFilePath, new MyProgressMonitor(remoteSize - startPos), startPos);
            byte[] buffer = new byte[1024 * 1024];//1M
            int bytesRead;
            while ((bytesRead = is.read(buffer)) != -1) {
                fos.write(buffer, 0, bytesRead);
            }
            is.close();
            fos.close();

            // 检查文件下载是否已经完成
            if (new File(localFilePath).length() == remoteSize) {
                System.out.println("File download completed successfully.");
            } else {
                System.out.println("File download failed.");
            }

            // 关闭连接
            sftpChannel.exit();
            session.disconnect();
        } catch (JSchException | IOException | SftpException e) {
            e.printStackTrace();
        }
    }

    public static class MyProgressMonitor implements SftpProgressMonitor {
        private long totalBytes;
        private long transferredBytes = 0;

        public MyProgressMonitor(long totalBytes) {
            this.totalBytes = totalBytes;
        }

        @Override
        public void init(int op, String src, String dest, long max) {
            System.out.println("Starting transfer: " + src + " --> " + dest);
        }

        @Override
        public boolean count(long bytes) {
            transferredBytes += bytes;
            double percentage = (double) transferredBytes / totalBytes * 100;
            System.out.printf("Downloaded %d of %d bytes (%.2f%%)\n", transferredBytes, totalBytes, percentage);
            return true;
        }

        @Override
        public void end() {
            System.out.println("\nTransfer complete.");
        }
    }
}

