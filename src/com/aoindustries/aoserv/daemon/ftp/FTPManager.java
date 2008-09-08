package com.aoindustries.aoserv.daemon.ftp;

/*
 * Copyright 2001-2008 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.aoserv.client.*;
import com.aoindustries.aoserv.daemon.*;
import com.aoindustries.aoserv.daemon.unix.linux.*;
import com.aoindustries.aoserv.daemon.util.*;
import com.aoindustries.io.*;
import com.aoindustries.io.unix.*;
import com.aoindustries.profiler.*;
import com.aoindustries.table.*;
import com.aoindustries.util.*;
import java.io.*;
import java.sql.*;
import java.util.*;

/**
 * Handles the building of FTP configs and files.
 */
final public class FTPManager extends BuilderThread {

    /**
     * A list of usernames, that when available on a server, are always placed
     * into private FTP passwd files.
     */
    /*private static final String[] redhatGlobalUsernames={
        LinuxAccount.ROOT,
        LinuxAccount.BIN,
        LinuxAccount.OPERATOR,
        LinuxAccount.FTP,
        LinuxAccount.NOBODY
    };*/

    /**
     * A list of groups, that when available on a server, are always placed
     * into private FTP group files.
     */
    /*private static final String[] redhatGlobalGroups={
        LinuxGroup.ROOT,
        LinuxGroup.BIN,
        LinuxGroup.DAEMON,
        LinuxGroup.SYS,
        LinuxGroup.ADM,
        LinuxGroup.FTP
    };*/
    
    private static final UnixFile
        newWuFTPAccessFile=new UnixFile("/etc/ftpaccess.new"),
        wuFtpAccessFile=new UnixFile("/etc/ftpaccess"),
        backupWuFTPAccessFile=new UnixFile("/etc/ftpaccess.old")
    ;

    private static final UnixFile
        newProFtpdConf=new UnixFile("/etc/proftpd.conf.new"),
        proFtpdConf=new UnixFile("/etc/proftpd.conf")
    ;

    //private static final UnixFile redhatFtpTemplateDir=new UnixFile("/usr/aoserv/templates/ftp");
    
    /**
     * The directory that is used for site-independant FTP access.
     */
    public static final String SHARED_FTP_DIRECTORY="/var/ftp/pub";
    
    private static FTPManager ftpManager;

    private FTPManager() {
        Profiler.startProfile(Profiler.INSTANTANEOUS, FTPManager.class, "<init>()", null);
        Profiler.endProfile(Profiler.INSTANTANEOUS);
    }

    /**
     * Makes a copy of a file if the file size of modification time is different than
     * the source directory.  Symbolic links are also copied as needed.
     */
    public static void copyIfNeeded(UnixFile sourceDir, UnixFile destDir, String filename) throws IOException {
        Profiler.startProfile(Profiler.IO, FTPManager.class, "copyIfNeeded(UnixFile,UnixFile,String)", null);
        try {
            UnixFile sourceFile=new UnixFile(sourceDir, filename, false);
            Stat sourceFileStat = sourceFile.getStat();
            UnixFile destFile=new UnixFile(destDir, filename, false);
            Stat destFileStat = destFile.getStat();
            
            if(sourceFileStat.isSymLink()) {
                String linkTarget=sourceFile.readLink();
                if(
                    !destFileStat.exists()
                    || !destFileStat.isSymLink()
                    || !destFile.readLink().equals(linkTarget)
                ) {
                    if(destFileStat.exists()) destFile.delete();
                    destFile.symLink(linkTarget).chown(sourceFileStat.getUID(), sourceFileStat.getGID());
                }
            } else if(sourceFileStat.isBlockDevice()) throw new IOException("Cannot copy block device: "+sourceFile.getPath());
            else if(sourceFileStat.isCharacterDevice()) throw new IOException("Cannot copy character device: "+sourceFile.getPath());
            else if(sourceFileStat.isDirectory()) throw new IOException("Cannot copy directory: "+sourceFile.getPath());
            else if(sourceFileStat.isFIFO()) throw new IOException("Cannot copy fifo: "+sourceFile.getPath());
            else if(sourceFileStat.isSocket()) throw new IOException("Cannot copy socket: "+sourceFile.getPath());
            else {
                // Copy as regular file
                if(
                    !destFileStat.exists()
                    || sourceFileStat.getSize()!=destFileStat.getSize()
                    || sourceFileStat.getModifyTime()!=destFileStat.getModifyTime()
                ) {
                    sourceFile.copyTo(destFile, true);
                    destFile.utime(destFileStat.getAccessTime(), sourceFileStat.getModifyTime());
                }
            }
        } finally {
            Profiler.endProfile(Profiler.IO);
        }
    }

    private static final Object rebuildLock=new Object();
    protected void doRebuild() throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, FTPManager.class, "doRebuild()", null);
        try {
            AOServConnector conn=AOServDaemon.getConnector();
            AOServer thisAOServer=AOServDaemon.getThisAOServer();

            int osv=thisAOServer.getServer().getOperatingSystemVersion().getPkey();
            final Stat tempStat = new Stat();
            synchronized(rebuildLock) {
                if(
                    osv==OperatingSystemVersion.MANDRAKE_10_1_I586
                    || osv==OperatingSystemVersion.MANDRIVA_2006_0_I586
                ) {
                    // Rebuild the /etc/proftpd.conf file
		    int bindCount=0;
                    ChainWriter out=new ChainWriter(new BufferedOutputStream(newProFtpdConf.getSecureOutputStream(UnixFile.ROOT_UID, UnixFile.ROOT_GID, 0600, true)));
                    try {
                        out.print("#\n"
                                + "# Automatically generated by ").print(FTPManager.class.getName()).print("\n"
                                + "#\n"
                                // Overall server settings
                                + "ServerName \"ProFTPD Server\"\n"
                                + "ServerIdent on \"ProFTPD Server [").print(thisAOServer.getHostname()).print("]\"\n"
                                + "ServerAdmin \"support@aoindustries.com\"\n"
                                + "ServerType standalone\n"
                                + "DefaultServer off\n"
                                + "AllowStoreRestart on\n"
                                + "Port 0\n"
                                + "SocketBindTight on\n"
                                + "PassivePorts 49152 50175\n"
                                + "Umask 002\n"
                                + "MaxInstances 100\n"
                                + "User "+LinuxAccount.NOBODY+"\n"
                                + "Group "+LinuxGroup.NOGROUP+"\n"
                                + "<Directory />\n"
                                + "  AllowOverwrite on\n"
                                + "</Directory>\n"
                                + "TimeoutIdle 7200\n"
                                + "TimeoutNoTransfer 7200\n"
                                + "TimesGMT on\n"
                                + "PersistentPasswd off\n"
                                + "SystemLog /var/log/proftpd/proftpd.log\n"
                                + "<Global>\n"
                                + "  DefaultRoot ~ "+LinuxGroup.PROFTPD_JAILED+"\n"
                                + "</Global>\n"
                        );

                        for(NetBind bind : thisAOServer.getServer().getNetBinds(conn.protocols.get(Protocol.FTP))) {
                            NetTcpRedirect redirect=bind.getNetTcpRedirect();
                            PrivateFTPServer privateServer=bind.getPrivateFTPServer();
                            if(redirect!=null) {
                                if(privateServer!=null) throw new SQLException("NetBind allocated as both NetTcpRedirect and PrivateFTPServer: "+bind.getPkey());
                            } else {
                                String netProtocol=bind.getNetProtocol().getProtocol();
                                if(!netProtocol.equals(NetProtocol.TCP)) throw new SQLException("ProFTPD may only be configured for TCP service:  (net_binds.pkey="+bind.getPkey()+").net_protocol="+netProtocol);

                                IPAddress ia=bind.getIPAddress();
                                bindCount++;
                                out.print("<VirtualHost ").print(ia.getIPAddress()).print(">\n"
                                        + "  Port ").print(bind.getPort().getPort()).print('\n');
                                if(privateServer!=null) out.print("  TransferLog \"").print(privateServer.getLogfile()).print("\"\n");
                                out.print("  ServerIdent on \"ProFTPD Server [").print(privateServer!=null?privateServer.getHostname():ia.isWildcard()?thisAOServer.getHostname():ia.getHostname()).print("]\"\n"
																						     + "  AllowOverwrite on\n");
                                if(privateServer!=null) out.print("  ServerAdmin \"").print(privateServer.getEmail()).print("\"\n");
                                if(privateServer==null || privateServer.allowAnonymous()) {
                                    out.print("  <Anonymous \"").print(privateServer==null?"~ftp":privateServer.getRoot()).print("\">\n"
                                            + "    User ftp\n"
                                            + "    Group ftp\n"
					    + "    AllowOverwrite off\n"
                                            + "    UserAlias anonymous ftp\n"
                                            + "    MaxClients 20\n"
                                            + "    RequireValidShell off\n"
                                            + "    AnonRequirePassword off\n"
                                            + "    DisplayLogin welcome.msg\n"
                                            + "    DisplayFirstChdir .message\n"
                                            + "    <Limit WRITE>\n"
                                            + "      DenyAll\n"
                                            + "    </Limit>\n"
                                            + "  </Anonymous>\n");
                                }
                                out.print("</VirtualHost>\n");
                            }
                        }
                    } finally {
                        out.flush();
                        out.close();
                    }

                    // Move into place if different than existing
                    boolean configChanged;
                    if(proFtpdConf.getStat(tempStat).exists() && newProFtpdConf.contentEquals(proFtpdConf)) {
                        newProFtpdConf.delete();
                        configChanged=false;
                    } else {
                        newProFtpdConf.renameTo(proFtpdConf);
                        configChanged=true;
                    }

                    // Enable/disable rc.d entry and start/stop process if change needed, otherwise restart if config changed
                    UnixFile rcFile=new UnixFile("/etc/rc.d/rc3.d/S85proftpd");
                    if(bindCount==0) {
                        // Turn off proftpd completely if not already off
                        if(rcFile.getStat(tempStat).exists()) {
                            AOServDaemon.exec(new String[] {"/etc/rc.d/init.d/proftpd", "stop"});
                            AOServDaemon.exec(new String[] {"/sbin/chkconfig", "--del", "proftpd"});
                        }
                    } else {
                        // Turn on proftpd if not already on
                        if(!rcFile.getStat(tempStat).exists()) {
                            AOServDaemon.exec(new String[] {"/sbin/chkconfig", "--add", "proftpd"});
                            AOServDaemon.exec(new String[] {"/etc/rc.d/init.d/proftpd", "start"});
                        } else if(configChanged) {
                            AOServDaemon.exec(new String[] {"/etc/rc.d/init.d/proftpd", "reload"});
                        }
                    }
                } else throw new SQLException("Unsupported OperatingSystemVersion: "+osv);
            }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    /*public static boolean isRedHatGlobalGroup(String group) {
        Profiler.startProfile(Profiler.FAST, FTPManager.class, "isRedHatGlobalGroup(String)", null);
        try {
            int len=redhatGlobalGroups.length;
            for(int c=0;c<len;c++) if(redhatGlobalGroups[c].equals(group)) return true;
            return false;
        } finally {
            Profiler.endProfile(Profiler.FAST);
        }
    }*/

    /*public static boolean isRedHatGlobalUsername(String username) {
        Profiler.startProfile(Profiler.FAST, FTPManager.class, "isRedHatGlobalUsername(String)", null);
        try {
            int len=redhatGlobalUsernames.length;
            for(int c=0;c<len;c++) if(redhatGlobalUsernames[c].equals(username)) return true;
            return false;
        } finally {
            Profiler.endProfile(Profiler.FAST);
        }
    }*/

    public static void start() throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, FTPManager.class, "start()", null);
        try {
            if(AOServDaemonConfiguration.isManagerEnabled(FTPManager.class) && ftpManager==null) {
                synchronized(System.out) {
                    if(ftpManager==null) {
                        System.out.print("Starting FTPManager: ");
                        AOServConnector conn=AOServDaemon.getConnector();
                        ftpManager=new FTPManager();
                        conn.ftpGuestUsers.addTableListener(ftpManager, 0);
                        conn.ipAddresses.addTableListener(ftpManager, 0);
                        conn.linuxAccounts.addTableListener(ftpManager, 0);
                        conn.linuxGroupAccounts.addTableListener(ftpManager, 0);
                        conn.linuxServerAccounts.addTableListener(ftpManager, 0);
                        conn.linuxServerGroups.addTableListener(ftpManager, 0);
                        conn.netBinds.addTableListener(ftpManager, 0);
                        conn.privateFTPServers.addTableListener(ftpManager, 0);
                        System.out.println("Done");
                    }
                }
            }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    /**
     * Removes any file in the directory that is not listed in <code>files</code>.
     */
    public static void trimFiles(UnixFile dir, String[] files) throws IOException {
        Profiler.startProfile(Profiler.UNKNOWN, FTPManager.class, "trimFiles(UnixFile,String[])", null);
        try {
            String[] list=dir.list();
            if(list!=null) {
                final Stat tempStat = new Stat();
                int len=list.length;
                int flen=files.length;
                for(int c=0;c<len;c++) {
                    String filename=list[c];
                    boolean found=false;
                    for(int d=0;d<flen;d++) {
                        if(filename.equals(files[d])) {
                            found=true;
                            break;
                        }
                    }
                    if(!found) {
                        UnixFile file=new UnixFile(dir, filename, false);
                        if(file.getStat(tempStat).exists()) file.delete();
                    }
                }
            }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    /**
     * Removes any file in the directory that is not listed in <code>files</code>.
     */
    public static void trimFiles(UnixFile dir, List<String> files) throws IOException {
        Profiler.startProfile(Profiler.FAST, FTPManager.class, "trimFiles(UnixFile,List<String>)", null);
        try {
            String[] SA=new String[files.size()];
            files.toArray(SA);
            trimFiles(dir, SA);
        } finally {
            Profiler.endProfile(Profiler.FAST);
        }
    }

    public String getProcessTimerDescription() {
        return "Rebuild FTP";
    }
}
