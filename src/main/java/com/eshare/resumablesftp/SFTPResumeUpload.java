package com.eshare.resumablesftp;

import com.jcraft.jsch.*;

import java.io.*;

public class SFTPResumeUpload {
    private static final int PORT = 22;

    public static void main(String[] args) {
        String user = "parallels";
        String passwd = "xxx";
        String host = "192.168.50.33";
        String localFilePath = "/Users/evan/Downloads/1080p.mp4";
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
            long remoteSize = 0;

            // 检查远程文件是否存在
            SftpATTRS attrs = sftpChannel.lstat(remoteFilePath);
            if (!attrs.isReg()) {
                throw new FileNotFoundException("Remote file does not exist: " + remoteFilePath);
            }
            // 检查远程文件大小
            remoteSize = attrs.getSize();

            // 打开本地文件
            RandomAccessFile raf = new RandomAccessFile(localFilePath, "r");

            // 计算从哪里开始上传
            long startPos = Math.max(0, remoteSize);
            raf.seek(startPos);

            // 文件上传
            long totalBytes = raf.length();
            OutputStream os = sftpChannel.put(remoteFilePath, new MyProgressMonitor(totalBytes - remoteSize), ChannelSftp.RESUME);
            byte[] buffer = new byte[1024 * 1024];//1M
            int bytesRead;
            while ((bytesRead = raf.read(buffer)) != -1) {
                os.write(buffer, 0, bytesRead);
            }
            os.close();
            raf.close();

            // 检查文件传输是否已经完成ÒÒ
            if (sftpChannel.lstat(remoteFilePath).getSize() == totalBytes) {
                System.out.println("File upload completed successfully.");
            } else {
                System.out.println("File upload failed.");
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
            System.out.printf("Transferred %d of %d bytes (%.2f%%)\n", transferredBytes, totalBytes, percentage);
            return true;
        }

        @Override
        public void end() {
            System.out.println("\nTransfer complete.");
        }
    }

}
