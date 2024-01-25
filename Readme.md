# 背景
因项目需要，我们服务每天都需要通过SFTP协议来对接上下游进行文件传输，但是对于一些大文件，在与第三方公司的服务器对接过程中很可能会因为网络问题或上下游服务器性能问题导致文件上传或者下载被中断，每次重试都需要重新对文件进行上传和下载，非常浪费带宽、服务器资源和时间，因此我们需要尽量提升文件传输效率，减少不必要的文件传输损耗。
# 解决思路
我们平时用一些下载软件，都有个断点续传功能，可以基于上一次已经传输的偏移量进行传输，不需要重复传输已经传输完整的数据，大大节省文件下载或者文件上传时间。

在通过SFTP进行文件传输，同样可以利用该原理进行断点续传。
![](https://img-blog.csdnimg.cn/direct/4a5578a29f804c98a4431e129895e005.png#pic_center)
## 文件上传原理
上传文件时，你首先需要与SFTP服务器建立一个安全会话（Session）。这需要提供用户名、密码、SFTP服务器的地址及端口。一旦会话建立，就可以打开一个SFTP通道（Channel）进行文件传输。

在处理大文件时，为了防止因网络问题导致的文件传输中断，以及减少不必要的重复传输，我们通常会采用断点续传的方式。这意味着如果文件传输在中途中断，下一次传输可以从上次结束的地方开始，而不是重新开始。

JSch库的``put``方法支持断点续传。通过检查远程文件的大小，你可以确定已经上传的数据量。然后，使用``FileInputStream``来打开本地文件，并使用skip方法跳过已上传的部分。最后，使用``put``方法的``RESUME``标志从上次中断的地方开始上传剩余的文件部分。

这种方法的好处是：

- 节省时间：不需要重新上传已经传输过的部分。
- 减少资源消耗：减少网络带宽的使用，特别是在网络不稳定或计费昂贵的环境中。
- 提高可靠性：即使在传输过程中发生中断，也可以保证最终文件的完整性。

## 文件下载原理
下载文件的原理与上传类似。同样需要建立会话和打开SFTP通道。使用``get``方法从SFTP服务器下载文件。如果你需要实现断点续传下载，你需要检查本地文件的大小，以此来确定已经下载的数据量。

如果本地文件的大小小于远程文件的大小，说明下载尚未完成，你可以从本地文件的末尾开始继续下载。JSch的``get``方法同样支持``RESUME``标志，允许你指定从远程文件的某个位置开始下载。

断点续传下载的好处包括：
- 节省时间：如果下载被中断，可以继续从中断点开始，而不是从头开始。
- 减少资源消耗：只下载尚未接收的文件部分，节约网络带宽。
- 提高可靠性：保证即使在网络不稳定情况下，也可以最终获取完整文件。


# 代码实现
这里使用了``com.github.mwiede``的Jsch版本，是基于Jcraft ``0.1.55``增加了一些新算法的支持。
```xml<!-- https://mvnrepository.com/artifact/com.github.mwiede/jsch -->
<dependency>
    <groupId>com.github.mwiede</groupId>
    <artifactId>jsch</artifactId>
    <version>0.2.16</version>
</dependency>
```
## 文件上传断点续传实现：
加入``SftpProgressMonitor``可以更好监控文件传输的进度
```java
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


```
### 断点续传测试步骤
1.我本地放一个2.1G的测试文件
![](https://img-blog.csdnimg.cn/direct/a38005dded5342ab9450ae6bf2ce8168.png#pic_center)

2.准备好远程目录，这里提前创建好一个测试目录在远程虚拟机``/tmp/evan``
![](https://img-blog.csdnimg.cn/direct/578a359ef6834f86a17b1151d50e218e.png#pic_center)
3.启动程序，控制台会打印文件传输进度，文件传输到52%左右我把程序直接杀死来模拟网络中断或者传输中断的情况
![](https://img-blog.csdnimg.cn/direct/e82beff21cbf48ce8c2331b5ce456c02.png)
4.重新启动程序，让程序自动从上一次传输的偏移量继续上传，大家可以尝试多次中断来模拟。
![](https://img-blog.csdnimg.cn/direct/f57bfddbbaad41be824bea8515d09d6b.png)
5.文件传输完成后，到远程目录对比文件大小，这里也可以通过文件``checksum``来进行对比，以下输出结果可以看到文件被成功上传。

![](https://img-blog.csdnimg.cn/direct/02d1c1d02e9d4947bb3ff0e70382969d.png)


## 文件下载断点续传实现
```java
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
            session.setConfig("server_host_key", session.getConfig("server_host_key") + ",ssh-rsa");
            session.setConfig("PubkeyAcceptedAlgorithms", session.getConfig("PubkeyAcceptedAlgorithms") + ",ssh-rsa,rsa-sha2-256");
            session.setConfig("dhgex_min", "1024");
            session.setConfig("dhgex_max", "2048");
            session.setConfig("dhgex_preferred", "2048");
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

```

### 断点续传测试步骤
1.我远程放一个2.1G的测试文件
```shell
parallels@ubuntu-linux-22-04-desktop:/tmp/evan$ ls -lh test10.mp4 
-rw-rw-r-- 1 parallels parallels 2.1G Jan 23 11:15 test10.mp4
```
2.准备好本地目录，这里是我本机下载目录``/Users/evan/Downloads/``
3.启动程序，控制台会打印文件传输进度，文件传输到86%左右我把程序直接杀死来模拟网络中断或者传输中断的情况
![](https://img-blog.csdnimg.cn/direct/2130c86d04c14b5fb655800b02d3401f.png)
4.重新启动程序，让程序自动从上一次传输的偏移量继续上传，大家可以尝试多次中断来模拟。
![](https://img-blog.csdnimg.cn/direct/15f7a997104c48558ec8a8ff4f749a50.png)
5.文件传输完成后，到远程目录对比文件大小，这里也可以通过文件``checksum``来进行对比，以下输出结果可以看到文件被成功上传。
```shell
evan@EvandeMBP Downloads % ls -lh test10.mp4 
-rw-r--r--  1 evan  staff   2.1G Jan 23 14:39 test10.mp4
evan@EvandeMBP Downloads % 
```
