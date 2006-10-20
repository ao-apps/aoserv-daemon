package com.aoindustries.aoserv.daemon.backup;

/*
 * Copyright 2001-2006 by AO Industries, Inc.,
 * 816 Azalea Rd, Mobile, Alabama, 36693, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.aoserv.backup.*;
import com.aoindustries.aoserv.client.Package;
import com.aoindustries.aoserv.client.*;
import com.aoindustries.aoserv.daemon.*;
import com.aoindustries.io.unix.*;
import com.aoindustries.profiler.*;
import com.aoindustries.util.*;
import java.io.*;
import java.sql.*;
import java.util.*;

/**
 * An <code>AOServerEnvironment</code> controls the backup system on
 * an <code>AOServer</code>.
 *
 * @see  AOServer
 *
 * @author  AO Industries, Inc.
 */
public class AOServerEnvironment extends BackupEnvironment {
    
    protected AOServer thisAOServer;
    protected Server thisServer;
    protected CvsRepositoryTable cvsRepositoryTable;
    protected FileBackupDeviceTable fileBackupDeviceTable;
    protected FileBackupSettingTable fileBackupSettingTable;
    protected UsernameTable usernameTable;
    protected List<BackupPartition> backupPartitions;
    protected List<PostgresServer> postgresServers;
    protected Package rootPackage;
    protected BackupLevel defaultBackupLevel;
    protected BackupLevel doNotBackup;
    protected BackupRetention defaultBackupRetention;

    public void init() throws IOException, SQLException {
        Profiler.startProfile(Profiler.FAST, AOServerEnvironment.class, "init()", null);
        try {
            AOServConnector conn=AOServDaemon.getConnector();
            thisAOServer=AOServDaemon.getThisAOServer();
            thisServer=thisAOServer.getServer();
            cvsRepositoryTable=conn.cvsRepositories;
            fileBackupDeviceTable=conn.fileBackupDevices;
            fileBackupSettingTable=conn.fileBackupSettings;
            usernameTable=conn.usernames;
            backupPartitions=thisAOServer.getBackupPartitions();
            postgresServers=thisAOServer.getPostgresServers();
            String rootAccounting=conn.businesses.getRootAccounting();
            rootPackage=conn.packages.get(rootAccounting);
            if(rootPackage==null) throw new SQLException("Unable to find Package: "+rootAccounting);
            defaultBackupLevel=conn.backupLevels.get(BackupLevel.BACKUP_PRIMARY);
            if(defaultBackupLevel==null) throw new SQLException("Unable to find BackupLevel: "+BackupLevel.BACKUP_PRIMARY);
            doNotBackup=conn.backupLevels.get(BackupLevel.DO_NOT_BACKUP);
            if(doNotBackup==null) throw new SQLException("Unable to find BackupLevel: "+BackupLevel.DO_NOT_BACKUP);
            defaultBackupRetention=conn.backupRetentions.get(BackupRetention.DEFAULT_BACKUP_RETENTION);
            if(defaultBackupRetention==null) throw new SQLException("Unable to find BackupRetention: "+BackupRetention.DEFAULT_BACKUP_RETENTION);
        } finally {
            Profiler.endProfile(Profiler.FAST);
        }
    }

    public void cleanup() {
        Profiler.startProfile(Profiler.INSTANTANEOUS, AOServerEnvironment.class, "cleanup()", null);
        try {
            thisAOServer=null;
            thisServer=null;
            cvsRepositoryTable=null;
            fileBackupDeviceTable=null;
            fileBackupSettingTable=null;
            usernameTable=null;
            backupPartitions=null;
            postgresServers=null;
            rootPackage=null;
            defaultBackupLevel=null;
            doNotBackup=null;
            defaultBackupRetention=null;
        } finally {
            Profiler.endProfile(Profiler.INSTANTANEOUS);
        }
    }

    public AOServConnector getConnector() throws IOException, SQLException {
        Profiler.startProfile(Profiler.INSTANTANEOUS, AOServerEnvironment.class, "getConnector()", null);
        try {
            return AOServDaemon.getConnector();
        } finally {
            Profiler.endProfile(Profiler.INSTANTANEOUS);
        }
    }

    public String getErrorEmailFrom() throws IOException {
        Profiler.startProfile(Profiler.INSTANTANEOUS, AOServerEnvironment.class, "getErrorEmailFrom()", null);
        try {
            return AOServDaemonConfiguration.getErrorEmailFrom();
        } finally {
            Profiler.endProfile(Profiler.INSTANTANEOUS);
        }
    }
    
    public String getWarningEmailFrom() throws IOException {
        Profiler.startProfile(Profiler.INSTANTANEOUS, AOServerEnvironment.class, "getWarningEmailFrom()", null);
        try {
            return AOServDaemonConfiguration.getWarningEmailFrom();
        } finally {
            Profiler.endProfile(Profiler.INSTANTANEOUS);
        }
    }

    public String getErrorEmailTo() throws IOException {
        Profiler.startProfile(Profiler.INSTANTANEOUS, AOServerEnvironment.class, "getErrorEmailTo()", null);
        try {
            return AOServDaemonConfiguration.getErrorEmailTo();
        } finally {
            Profiler.endProfile(Profiler.INSTANTANEOUS);
        }
    }

    public String getWarningEmailTo() throws IOException {
        Profiler.startProfile(Profiler.INSTANTANEOUS, AOServerEnvironment.class, "getWarningEmailTo()", null);
        try {
            return AOServDaemonConfiguration.getWarningEmailTo();
        } finally {
            Profiler.endProfile(Profiler.INSTANTANEOUS);
        }
    }

    public Random getRandom() {
        Profiler.startProfile(Profiler.INSTANTANEOUS, AOServerEnvironment.class, "getRandom()", null);
        try {
            return AOServDaemon.getRandom();
        } finally {
            Profiler.endProfile(Profiler.INSTANTANEOUS);
        }
    }

    public String getErrorSmtpServer() throws IOException {
        Profiler.startProfile(Profiler.INSTANTANEOUS, AOServerEnvironment.class, "getErrorSmtpServer()", null);
        try {
            return AOServDaemonConfiguration.getErrorSmtpServer();
        } finally {
            Profiler.endProfile(Profiler.INSTANTANEOUS);
        }
    }

    public String getWarningSmtpServer() throws IOException {
        Profiler.startProfile(Profiler.INSTANTANEOUS, AOServerEnvironment.class, "getWarningSmtpServer()", null);
        try {
            return AOServDaemonConfiguration.getWarningSmtpServer();
        } finally {
            Profiler.endProfile(Profiler.INSTANTANEOUS);
        }
    }

    public Server getThisServer() throws IOException, SQLException {
        Profiler.startProfile(Profiler.FAST, AOServerEnvironment.class, "getThisServer()", null);
        try {
            return AOServDaemon.getThisAOServer().getServer();
        } finally {
            Profiler.endProfile(Profiler.FAST);
        }
    }

    private final Object unixFileCacheLock=new Object();
    private String lastFilename;
    private UnixFile lastUnixFile;
    private UnixFile getUnixFile(String filename) {
        Profiler.startProfile(Profiler.FAST, AOServerEnvironment.class, "getUnixFile(String)", null);
        try {
            synchronized(unixFileCacheLock) {
                if(!filename.equals(lastFilename)) {
                    lastFilename=filename;
                    lastUnixFile=new UnixFile(filename);
                }
                return lastUnixFile;
            }
        } finally {
            Profiler.endProfile(Profiler.FAST);
        }
    }

    private static final String[] roots={"/"};

    public String[] getFilesystemRoots() {
        Profiler.startProfile(Profiler.INSTANTANEOUS, AOServerEnvironment.class, "getFilesystemRoots()", null);
        try {
            return roots;
        } finally {
            Profiler.endProfile(Profiler.INSTANTANEOUS);
        }
    }

    public long getStatMode(String filename) throws IOException {
        Profiler.startProfile(Profiler.FAST, AOServerEnvironment.class, "getStatMode(String)", null);
        try {
            return getUnixFile(filename).getStat().getRawMode();
        } finally {
            Profiler.endProfile(Profiler.FAST);
        }
    }

    public String[] getDirectoryList(String filename) {
        Profiler.startProfile(Profiler.FAST, AOServerEnvironment.class, "getDirectoryList(String)", null);
        try {
            return getUnixFile(filename).list();
        } finally {
            Profiler.endProfile(Profiler.FAST);
        }
    }

    public FileBackupDevice getFileBackupDevice(String filename) throws IOException, SQLException {
        Profiler.startProfile(Profiler.FAST, AOServerEnvironment.class, "getFileBackupDevice(String)", null);
        try {
            long device=getUnixFile(filename).getStat().getDevice();
            FileBackupDevice dev=fileBackupDeviceTable.get(device);
            if(dev==null) throw new IOException("Unable to find FileBackupDevice for '"+filename+"': "+device);
            return dev;
        } finally {
            Profiler.endProfile(Profiler.FAST);
        }
    }

    public long getInode(String filename) throws IOException {
        Profiler.startProfile(Profiler.FAST, AOServerEnvironment.class, "getInode(String)", null);
        try {
            long inode=getUnixFile(filename).getStat().getInode();
            if(inode==-1) throw new IOException("Inode value of -1 conflicts with internal use of -1 as null");
            return inode;
        } finally {
            Profiler.endProfile(Profiler.FAST);
        }
    }

    public int getUID(String filename) throws IOException {
        Profiler.startProfile(Profiler.FAST, AOServerEnvironment.class, "getUID(String)", null);
        try {
            return getUnixFile(filename).getStat().getUID();
        } finally {
            Profiler.endProfile(Profiler.FAST);
        }
    }

    public int getGID(String filename) throws IOException {
        Profiler.startProfile(Profiler.FAST, AOServerEnvironment.class, "getGID(String)", null);
        try {
            return getUnixFile(filename).getStat().getGID();
        } finally {
            Profiler.endProfile(Profiler.FAST);
        }
    }

    public long getModifyTime(String filename) throws IOException {
        Profiler.startProfile(Profiler.FAST, AOServerEnvironment.class, "getModifyTime(String)", null);
        try {
            return getUnixFile(filename).getStat().getModifyTime();
        } finally {
            Profiler.endProfile(Profiler.FAST);
        }
    }

    public long getFileLength(String filename) {
        Profiler.startProfile(Profiler.FAST, AOServerEnvironment.class, "getFileLength(String)", null);
        try {
            return getUnixFile(filename).getFile().length();
        } finally {
            Profiler.endProfile(Profiler.FAST);
        }
    }

    public String readLink(String filename) throws IOException {
        Profiler.startProfile(Profiler.FAST, AOServerEnvironment.class, "readLink(String)", null);
        try {
            return getUnixFile(filename).readLink();
        } finally {
            Profiler.endProfile(Profiler.FAST);
        }
    }

    public long getDeviceIdentifier(String filename) throws IOException {
        Profiler.startProfile(Profiler.FAST, AOServerEnvironment.class, "getDeviceIdentifier(String)", null);
        try {
            return getUnixFile(filename).getStat().getDeviceIdentifier();
        } finally {
            Profiler.endProfile(Profiler.FAST);
        }
    }

    public InputStream getInputStream(String filename) throws IOException {
        Profiler.startProfile(Profiler.FAST, AOServerEnvironment.class, "getInputStream(String)", null);
        try {
            return new FileInputStream(getUnixFile(filename).getFile());
        } finally {
            Profiler.endProfile(Profiler.FAST);
        }
    }

    public String getNameOfFile(String filename) {
        Profiler.startProfile(Profiler.FAST, AOServerEnvironment.class, "getNameOfFile(String)", null);
        try {
            return getUnixFile(filename).getFile().getName();
        } finally {
            Profiler.endProfile(Profiler.FAST);
        }
    }

    public void getBackupFileSetting(BackupFileSetting fileSetting) throws IOException, SQLException {
        Profiler.startProfile(Profiler.FAST, AOServerEnvironment.class, "getBackupFileSetting(BackupFileSetting)", null);
        try {
            String filename=fileSetting.getFilename();

            // Set the defaults so other parts of this method may simply return for defaults
            fileSetting.setSettings(rootPackage, defaultBackupLevel, defaultBackupRetention);

            // First, don't backup the backup partitions
            for(BackupPartition bp : backupPartitions) if(filename.equals(bp.getPath())) {
                fileSetting.setRecurse(false);
                return;
            }

            // Second, the <code>FileBackupSetting</code>s come into play.
            FileBackupSetting bestMatch=null;
            for(FileBackupSetting fbs : fileBackupSettingTable) {
                if(
                    fbs.getServer().equals(thisServer)
                    && filename.startsWith(fbs.getPath())
                    && (
                        bestMatch==null
                        || fbs.getPath().length()>bestMatch.getPath().length()
                    )
                ) bestMatch=fbs;
            }
            if(bestMatch!=null) {
                fileSetting.setSettings(
                    bestMatch.getPackage(),
                    bestMatch.getBackupLevel(),
                    bestMatch.getBackupRetention(),
                    bestMatch.isRecursible()
                );
            }

            // Third, the FileBackupDevices take effect
            UnixFile unixFile=getUnixFile(filename);
            Stat stat = unixFile.getStat();
            long device = stat.getDevice();
            FileBackupDevice fileDevice=fileBackupDeviceTable.get(device);
            if(fileDevice==null) throw new IOException("Unable to find FileBackupDevice for device #"+device+": filename='"+filename+'\'');
            if(!fileDevice.canBackup()) {
                fileSetting.setRecurse(false);
                return;
            }
            if(bestMatch!=null) return;
            
            // Fourth, some hard-coded no-recurse directories
            if(
                filename.equals("/mnt/cdrom")
                || filename.equals("/mnt/floppy")
                || filename.equals("/tmp")
                || filename.equals("/var/backup")
                || filename.equals("/var/backup1")
                || filename.equals("/var/backup2")
                || filename.equals("/var/backup3")
                || filename.equals("/var/backup4")
                || filename.equals("/var/failover")
                //|| filename.equals("/var/lib/interbase")
                || filename.equals("/var/lib/mysql")
                || filename.equals("/var/oldaccounts")
                || filename.equals("/var/spool/mqueue")
                || filename.equals("/var/tmp")
            ) {
                fileSetting.setRecurse(false);
                return;
            }

            // Fifth, don't backup selected parts of PostgresServers
            if(filename.startsWith(PostgresServer.DATA_BASE_DIR+'/')) {
                int pos=filename.indexOf('/', PostgresServer.DATA_BASE_DIR.length()+1);
                if(pos!=-1) {
                    String name=filename.substring(PostgresServer.DATA_BASE_DIR.length()+1, pos);
                    PostgresServer postgresServer=null;
                    for(PostgresServer ps : postgresServers) {
                        if(ps.getName().equals(name)) {
                            postgresServer=ps;
                            break;
                        }
                    }
                    if(postgresServer!=null) {
                        String remainingPath=filename.substring(pos+1);
                        if(
                            remainingPath.startsWith("base")
                            || remainingPath.startsWith("global")
                            || remainingPath.startsWith("pg_clog")
                            || remainingPath.startsWith("pg_xlog")
                        ) {
                            fileSetting.setSettings(rootPackage, defaultBackupLevel, defaultBackupRetention, false);
                            return;
                        }
                    }
                }
            }
            
            int len=filename.length();
            // All config files located in /etc/
            if(filename.startsWith("/etc/")) {
                // The config files for httpd_sites
                if(filename.startsWith("/etc/httpd/conf/hosts/")) {
                    // By site name
                    filename=filename.substring(22);
                    len=filename.length();
                    if(len>3 && filename.substring(len-3).equals(".80")) filename=filename.substring(0,len-3);
                    else if(len>4 && filename.substring(len-4).equals(".443")) filename=filename.substring(0,len-4);
                    else if(len>5 && filename.substring(len-5).equals(".4321")) filename=filename.substring(0,len-5);
                    HttpdSite hs=thisAOServer.getHttpdSite(filename);
                    if(hs!=null) fileSetting.setSettings(hs.getPackage(), hs.getConfigBackupLevel(), hs.getConfigBackupRetention());
                    return;
                }
                if(filename.startsWith("/etc/httpd/conf/logrotate.d/")) {
                    // By site name
                    filename=filename.substring(28);
                    HttpdSite hs=thisAOServer.getHttpdSite(filename);
                    if(hs!=null) fileSetting.setSettings(hs.getPackage(), hs.getConfigBackupLevel(), hs.getConfigBackupRetention());
                    return;
                }

                // The config files for httpd_shared_tomcats
                if(filename.startsWith("/etc/httpd/conf/wwwgroup/")) {
                    // By shared tomcat name
                    filename=filename.substring(25);
                    HttpdSharedTomcat hst=thisAOServer.getHttpdSharedTomcat(filename);
                    if(hst!=null) fileSetting.setSettings(hst.getLinuxServerAccount().getLinuxAccount().getUsername().getPackage(), hst.getConfigBackupLevel(), hst.getConfigBackupRetention());
                    return;
                }

                // Skip the access and access.db files
                if(
                    filename.equals("/etc/mail/access")
                    || filename.equals("/etc/mail/access.db")
                ) {
                    fileSetting.setSettings(rootPackage, doNotBackup, defaultBackupRetention, false);
                    return;
                }

                // The email_lists content
                if(filename.startsWith("/etc/mail/lists/")) {
                    // By list path
                    EmailList el=thisAOServer.getEmailList(filename);
                    if(el!=null) fileSetting.setSettings(el.getLinuxServerGroup().getLinuxGroup().getPackage(), el.getBackupLevel(), el.getBackupRetention());
                    return;
                }

                // The majordomo_servers and majordomo_lists
                if(filename.startsWith("/etc/mail/majordomo/")) {
                    // By list path first
                    EmailList el=thisAOServer.getEmailList(filename);
                    if(el!=null) {
                        fileSetting.setSettings(el.getLinuxServerGroup().getLinuxGroup().getPackage(), el.getBackupLevel(), el.getBackupRetention());
                        return;
                    }
                    // email domain
                    filename=filename.substring(20);
                    int pos=filename.indexOf('/');
                    if(pos>=0) filename=filename.substring(0,pos);
                    EmailDomain sd=thisAOServer.getEmailDomain(filename);
                    if(sd!=null) {
                        MajordomoServer ms=sd.getMajordomoServer();
                        if(ms!=null) {
                            fileSetting.setSettings(sd.getPackage(), ms.getBackupLevel(), ms.getBackupRetention());
                            return;
                        }
                    }
                    return;
                }
            }

            // CVS repositories may exist in these locations, and override their environment for backup retention
            if(
                filename.startsWith("/home/")
                || filename.startsWith("/var/cvs/")
                || filename.startsWith(HttpdSite.WWW_DIRECTORY+'/')
                || filename.startsWith(HttpdSharedTomcat.WWW_GROUP_DIR+'/')
            ) {
                // By CVS repository
                for(CvsRepository cr : thisAOServer.getCvsRepositories()) {
                    String path=cr.getPath();
                    if(filename.equals(path) || filename.startsWith(path+'/')) {
                        fileSetting.setSettings(cr.getLinuxServerAccount().getLinuxAccount().getUsername().getPackage(), cr.getBackupLevel(), cr.getBackupRetention());
                        return;
                    }
                }
            }

            // linux_server_account home directories
            if(filename.startsWith("/home/")) {
                // By username
                String origFilename=filename;
                filename=filename.substring(6);
                if(filename.length()>2 && filename.charAt(1)=='/') {
                    filename=filename.substring(2);
                    int pos=filename.indexOf('/');
                    if(pos>=0) filename=filename.substring(0,pos);
                    Username un=usernameTable.get(filename);
                    if(un!=null) {
                        LinuxAccount la=un.getLinuxAccount();
                        if(la!=null) {
                            LinuxServerAccount lsa=la.getLinuxServerAccount(thisAOServer);
                            if(lsa!=null) {
                                fileSetting.setSettings(un.getPackage(), lsa.getHomeBackupLevel(), lsa.getHomeBackupRetention());
                                return;
                            }
                        }
                    }
                }
                return;
            }

            // httpd_site logs
            if(filename.startsWith("/logs/")) {
                // By site name
                filename=filename.substring(6);
                int pos=filename.indexOf('/');
                if(pos>=0) filename=filename.substring(0,pos);
                HttpdSite hs=thisAOServer.getHttpdSite(filename);
                if(hs!=null) fileSetting.setSettings(hs.getPackage(), hs.getLogBackupLevel(), hs.getLogBackupRetention());
                return;
            }

            if(filename.startsWith("/var/")) {
                // httpd_site ftp content
                if(filename.startsWith("/var/ftp/pub/")) {
                    // By site name
                    filename=filename.substring(13);
                    int pos=filename.indexOf('/');
                    if(pos>=0) filename=filename.substring(0,pos);
                    HttpdSite hs=thisAOServer.getHttpdSite(filename);
                    if(hs!=null) fileSetting.setSettings(hs.getPackage(), hs.getFtpBackupLevel(), hs.getFtpBackupRetention());
                    return;
                }

                // system log files
                if(filename.startsWith("/var/log/")) {
                    return;
                }

                // linux_server_account cron table
                if(filename.startsWith("/var/spool/cron/")) {
                    // By username
                    filename=filename.substring(16);
                    Username un=usernameTable.get(filename);
                    if(un!=null) {
                        LinuxAccount la=un.getLinuxAccount();
                        if(la!=null) {
                            LinuxServerAccount lsa=la.getLinuxServerAccount(thisAOServer);
                            if(lsa!=null) {
                                fileSetting.setSettings(un.getPackage(), lsa.getCronBackupLevel(), lsa.getCronBackupRetention());
                                return;
                            }
                        }
                    }
                    return;
                }

                // linux_server_account email inbox
                if(filename.startsWith("/var/spool/mail/")) {
                    // By username
                    filename=filename.substring(16);
                    Username un=usernameTable.get(filename);
                    if(un!=null) {
                        LinuxAccount la=un.getLinuxAccount();
                        if(la!=null) {
                            LinuxServerAccount lsa=la.getLinuxServerAccount(thisAOServer);
                            if(lsa!=null) {
                                fileSetting.setSettings(un.getPackage(), lsa.getInboxBackupLevel(), lsa.getInboxBackupRetention());
                                return;
                            }
                        }
                    }
                    return;
                }
            }

            // Web sites
            if(filename.startsWith(HttpdSite.WWW_DIRECTORY+'/')) {
                // By site name
                filename=filename.substring(5);
                len=filename.length();
                int pos=filename.indexOf('/');
                String siteName=pos==-1?filename:filename.substring(0,pos);
                HttpdSite hs=thisAOServer.getHttpdSite(siteName);
                if(hs!=null) {
                    if(filename.startsWith(siteName+"/ftp/") && thisAOServer.getPrivateFTPServer(HttpdSite.WWW_DIRECTORY+'/'+siteName+"/ftp")!=null) {
                        // ftp files
                        fileSetting.setSettings(hs.getPackage(), hs.getFtpBackupLevel(), hs.getFtpBackupRetention());
                    } else if(filename.startsWith(siteName+"/var/log/")) {
                        // log files
                        fileSetting.setSettings(hs.getPackage(), hs.getLogBackupLevel(), hs.getLogBackupRetention());
                    } else if(filename.equals(siteName+"/var/analog.dns")) {
                        BackupLevel hsBL=hs.getFileBackupLevel();
                        short bl=hsBL.getLevel();
                        fileSetting.setSettings(
                            hs.getPackage(),
                            bl==BackupLevel.DO_NOT_BACKUP?hsBL:defaultBackupLevel,
                            hs.getFileBackupRetention()
                        );
                    } else {
                        fileSetting.setSettings(hs.getPackage(), hs.getFileBackupLevel(), hs.getFileBackupRetention());
                    }
                    return;
                }

                // By /www/site_name home directory
                String home=HttpdSite.WWW_DIRECTORY+'/'+siteName;
                List<LinuxServerAccount> lsas = thisAOServer.getLinuxServerAccounts();
                for(LinuxServerAccount lsa : lsas) {
                    if(lsa.getHome().equals(home)) {
                        fileSetting.setSettings(lsa.getLinuxAccount().getUsername().getPackage(), lsa.getHomeBackupLevel(), lsa.getHomeBackupRetention());
                        return;
                    }
                }

                // By /www/site_name/webapps home directory
                if(pos>=0) {
                    filename=filename.substring(pos+1);
                    pos=filename.indexOf('/');
                    if(pos>=0) filename=filename.substring(0,pos);
                    if(filename.equals("webapps")) {
                        home=home+"/webapps";
                        for(LinuxServerAccount lsa : lsas) {
                            if(lsa.getHome().equals(home)) {
                                fileSetting.setSettings(lsa.getLinuxAccount().getUsername().getPackage(), lsa.getHomeBackupLevel(), lsa.getHomeBackupRetention());
                                return;
                            }
                        }
                    }
                }
                return;
            }

            // httpd_shared_tomcat directories
            if(filename.startsWith(HttpdSharedTomcat.WWW_GROUP_DIR+'/')) {
                // By shared tomcat name
                filename=filename.substring(10);
                len=filename.length();
                int pos=filename.indexOf('/');
                String tomcatName=pos==-1?filename:filename.substring(0,pos);
                HttpdSharedTomcat hst=thisAOServer.getHttpdSharedTomcat(tomcatName);
                if(hst!=null) {
                    if(filename.startsWith(tomcatName+"/var/log/")) {
                        // log files
                        fileSetting.setSettings(hst.getLinuxServerAccount().getLinuxAccount().getUsername().getPackage(), hst.getLogBackupLevel(), hst.getLogBackupRetention());
                    } else {
                        fileSetting.setSettings(hst.getLinuxServerAccount().getLinuxAccount().getUsername().getPackage(), hst.getFileBackupLevel(), hst.getFileBackupRetention());
                    }
                    return;
                }

                // By /wwwgroup/site_name home directory
                String home=HttpdSharedTomcat.WWW_GROUP_DIR+'/'+tomcatName;
                for(LinuxServerAccount lsa : thisAOServer.getLinuxServerAccounts()) {
                    if(lsa.getHome().equals(home)) {
                        fileSetting.setSettings(lsa.getLinuxAccount().getUsername().getPackage(), lsa.getHomeBackupLevel(), lsa.getHomeBackupRetention());
                        return;
                    }
                }
            }
        } finally {
            Profiler.endProfile(Profiler.FAST);
        }
    }

    public void preBackup() throws IOException, SQLException {
        Profiler.startProfile(Profiler.INSTANTANEOUS, AOServerEnvironment.class, "preBackup()", null);
        try {
            BackupManager.cleanVarOldaccounts();

            BackupManager.backupInterBaseDatabases();
            BackupManager.removeExpiredInterBaseBackups();

            BackupManager.backupMySQLDatabases();
            BackupManager.removeExpiredMySQLBackups();

            BackupManager.backupPostgresDatabases();
            BackupManager.removeExpiredPostgresBackups();
        } finally {
            Profiler.endProfile(Profiler.INSTANTANEOUS);
        }
    }

    public void postBackup() throws IOException, SQLException {
        Profiler.startProfile(Profiler.FAST, AOServerEnvironment.class, "postBackup()", null);
        try {
            BackupManager.pruneBackupPartitions();
        } finally {
            Profiler.endProfile(Profiler.FAST);
        }
    }

    /**
     * Block transfer sizes used by the <code>BitRateOutputStream</code>.
     */
    public int getBlockSize() {
        Profiler.startProfile(Profiler.INSTANTANEOUS, AOServerEnvironment.class, "getBlockSize()", null);
        try {
            return BufferManager.BUFFER_SIZE;
        } finally {
            Profiler.endProfile(Profiler.INSTANTANEOUS);
        }
    }
    
    /**
     * The bits per second for transfers.
     */
    public int getBitRate() throws IOException {
        Profiler.startProfile(Profiler.FAST, AOServerEnvironment.class, "getBitRate()", null);
        try {
            return AOServDaemonConfiguration.getBackupBandwidthLimit();
        } finally {
            Profiler.endProfile(Profiler.FAST);
        }
    }
    
    public ErrorHandler getErrorHandler() {
        Profiler.startProfile(Profiler.FAST, AOServerEnvironment.class, "getErrorHandler()", null);
        try {
            return AOServDaemon.getErrorHandler();
        } finally {
            Profiler.endProfile(Profiler.FAST);
        }
    }
}