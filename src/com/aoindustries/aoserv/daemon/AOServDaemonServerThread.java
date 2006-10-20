package com.aoindustries.aoserv.daemon;

/*
 * Copyright 2000-2006 by AO Industries, Inc.,
 * 816 Azalea Rd, Mobile, Alabama, 36693, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.aoserv.client.*;
import com.aoindustries.aoserv.daemon.backup.*;
import com.aoindustries.aoserv.daemon.client.AOServDaemonProtocol;
import com.aoindustries.aoserv.daemon.distro.*;
import com.aoindustries.aoserv.daemon.email.*;
import com.aoindustries.aoserv.daemon.failover.*;
import com.aoindustries.aoserv.daemon.ftp.*;
import com.aoindustries.aoserv.daemon.httpd.*;
import com.aoindustries.aoserv.daemon.interbase.*;
import com.aoindustries.aoserv.daemon.mysql.*;
import com.aoindustries.aoserv.daemon.postgres.*;
import com.aoindustries.aoserv.daemon.server.*;
import com.aoindustries.aoserv.daemon.unix.*;
import com.aoindustries.aoserv.daemon.unix.linux.*;
import com.aoindustries.io.*;
import com.aoindustries.md5.*;
import com.aoindustries.profiler.*;
import com.aoindustries.util.*;
import java.io.*;
import java.net.*;
import java.sql.*;
import java.util.*;

/**
 * The <code>AOServServerThread</code> handles a connection once it is accepted.
 *
 * @author  AO Industries, Inc.
 */
final public class AOServDaemonServerThread extends Thread {

    /**
     * The list of allowed hosts is calculated the first time it is needed.
     */
    private static String[] allowedHosts;

    /**
     * The <code>AOServServer</code> that created this <code>AOServServerThread</code>.
     */
    private final AOServDaemonServer server;

    /**
     * The <code>Socket</code> that is connected.
     */
    private final Socket socket;

    /**
     * The <code>CompressedDataInputStream</code> that is being read from.
     */
    private final CompressedDataInputStream in;

    /**
     * The <code>CompressedDataOutputStream</code> that is being written to.
     */
    private final CompressedDataOutputStream out;

    /**
     * Creates a new, running <code>AOServServerThread</code>.
     */
    public AOServDaemonServerThread(AOServDaemonServer server, Socket socket) throws IOException {
	super("AOServ Daemon Server Thread - " + socket.getInetAddress().getHostAddress());
        Profiler.startProfile(Profiler.UNKNOWN, AOServDaemonServerThread.class, "(AOServDaemonServer,Socket)", null);
        try {
            this.server = server;
            this.socket = socket;
            this.in = new CompressedDataInputStream(new BufferedInputStream(socket.getInputStream()));
            this.out = new CompressedDataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
            this.out.flush();

            start();
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    public void run() {
        Profiler.startProfile(Profiler.IO, AOServDaemonServerThread.class, "run()", null);
        try {
            try {
                AOServConnector connector=AOServDaemon.getConnector();
                AOServer thisAOServer=AOServDaemon.getThisAOServer();

                // Read the key first so that any failed connection looks the same from the outside,
                // regardless of reason
                String protocolVersion=in.readUTF();
                String key=in.readBoolean()?in.readUTF():null;
                if(!protocolVersion.equals(AOServDaemonProtocol.CURRENT_VERSION)) {
                    out.writeBoolean(false);
                    out.writeUTF(AOServDaemonProtocol.CURRENT_VERSION);
                    out.flush();
                    return;
                } else {
                    out.writeBoolean(true);
                }
                if(key!=null) {
                    // Must come from one of the hosts listed in the database
                    String hostAddress = socket.getInetAddress().getHostAddress();
                    boolean isOK=false;
                    for(AOServerDaemonHost allowedHost : thisAOServer.getAOServerDaemonHosts()) {
                        String tempAddress = InetAddress.getByName(allowedHost.getHost()).getHostAddress();
                        if (tempAddress.equals(hostAddress)) {
                            isOK=true;
                            break;
                        }
                    }
                    if(isOK) {
                        // Authenticate the client first
                        String correctKey=thisAOServer.getDaemonKey();
                        if(!HttpdManager.passwordMatches(key, correctKey)) {
                            System.err.println("Connection attempted from " + hostAddress + " with invalid key: " + key);
                            out.writeBoolean(false);
                            out.flush();
                            return;
                        }
                    } else {
                        AOServDaemon.reportSecurityMessage("Connection attempted from " + hostAddress + " but not listed in server_daemon_hosts");
                        out.writeBoolean(false);
                        out.flush();
                        return;
                    }
                };
                out.writeBoolean(true);
                out.flush();

                int taskCode;
            Loop:
                while ((taskCode = in.readCompressedInt()) != AOServDaemonProtocol.QUIT) {
                    try {
                        switch (taskCode) {
                            case AOServDaemonProtocol.BACKUP_INTERBASE_DATABASE :
                                if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing BACKUP_INTERBASE_DATABASE, Thread="+toString());
                                if(key==null) throw new IOException("Only the master server may BACKUP_INTERBASE_DATABASE");
                                InterBaseManager.backupDatabase(in, out);
                                break;
                            case AOServDaemonProtocol.BACKUP_MYSQL_DATABASE :
                                if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing BACKUP_MYSQL_DATABASE, Thread="+toString());
                                if(key==null) throw new IOException("Only the master server may BACKUP_MYSQL_DATABASE");
                                MySQLDatabaseManager.backupDatabase(in, out);
                                break;
                            case AOServDaemonProtocol.BACKUP_POSTGRES_DATABASE :
                                if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing BACKUP_POSTGRES_DATABASE, Thread="+toString());
                                if(key==null) throw new IOException("Only the master server may BACKUP_POSTGRES_DATABASE");
                                PostgresDatabaseManager.backupDatabase(in, out);
                                break;
                            case AOServDaemonProtocol.BACKUP_POSTGRES_DATABASE_SEND_DATA :
                                {
                                    if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing BACKUP_POSTGRES_DATABASE_SEND_DATA, Thread="+toString());
                                    long daemonKey=in.readLong();
                                    DaemonAccessEntry dae=AOServDaemonServer.getDaemonAccessEntry(daemonKey);
                                    if(dae.command!=AOServDaemonProtocol.BACKUP_POSTGRES_DATABASE_SEND_DATA) throw new IOException("Mismatched DaemonAccessEntry command, dae.command!="+AOServDaemonProtocol.BACKUP_POSTGRES_DATABASE_SEND_DATA);
                                    String relativePath = dae.param1;
                                    int backupPartitionPKey = Integer.parseInt(dae.param2);
                                    String expectedMD5 = dae.param3;
                                    BackupPartition bp=connector.backupPartitions.get(backupPartitionPKey);
                                    if(bp==null) throw new SQLException("Unable to find BackupPartition: "+backupPartitionPKey);
                                    String md5 = BackupManager.storeBackupData(bp, relativePath, true, in, null);
                                    if(!expectedMD5.equals(md5)) throw new IOException("Unexpected MD5 digest: "+md5);
                                    out.write(AOServDaemonProtocol.DONE);
                                }
                                break;
                            case AOServDaemonProtocol.COMPARE_LINUX_ACCOUNT_PASSWORD :
                                {
                                    if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing COMPARE_LINUX_ACCOUNT_PASSWORD, Thread="+toString());
                                    String username=in.readUTF();
                                    String password=in.readUTF();
                                    if(key==null) throw new IOException("Only the master server may COMPARE_LINUX_ACCOUNT_PASSWORD");
                                    boolean matches=LinuxAccountManager.comparePassword(username, password);
                                    out.write(AOServDaemonProtocol.DONE);
                                    out.writeBoolean(matches);
                                }
                                break;
                            case AOServDaemonProtocol.DUMP_INTERBASE_DATABASE :
                                {
                                    if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing DUMP_INTERBASE_DATABASE, Thread="+toString());
                                    int pkey=in.readCompressedInt();
                                    if(key==null) throw new IOException("Only the master server may DUMP_INTERBASE_DATABASE");
                                    InterBaseDatabase id=connector.interBaseDatabases.get(pkey);
                                    if(id==null) throw new SQLException("Unable to find InterBaseDatabase: "+pkey);
                                    InterBaseManager.dumpDatabase(id, out);
                                    out.write(AOServDaemonProtocol.DONE);
                                }
                                break;
                            case AOServDaemonProtocol.DUMP_MYSQL_DATABASE :
                                {
                                    if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing DUMP_MYSQL_DATABASE, Thread="+toString());
                                    int pkey=in.readCompressedInt();
                                    if(key==null) throw new IOException("Only the master server may DUMP_MYSQL_DATABASE");
                                    MySQLDatabase md=connector.mysqlDatabases.get(pkey);
                                    if(md==null) throw new SQLException("Unable to find MySQLDatabase: "+pkey);
                                    MySQLDatabaseManager.dumpDatabase(md, out);
                                    out.write(AOServDaemonProtocol.DONE);
                                }
                                break;
                            case AOServDaemonProtocol.DUMP_POSTGRES_DATABASE :
                                {
                                    if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing DUMP_POSTGRES_DATABASE, Thread="+toString());
                                    int pkey=in.readCompressedInt();
                                    if(key==null) throw new IOException("Only the master server may DUMP_POSTGRES_DATABASE");
                                    PostgresDatabase pd=connector.postgresDatabases.get(pkey);
                                    if(pd==null) throw new SQLException("Unable to find PostgresDatabase: "+pkey);
                                    PostgresDatabaseManager.dumpDatabase(pd, out);
                                    out.write(AOServDaemonProtocol.DONE);
                                }
                                break;
                            case AOServDaemonProtocol.FAILOVER_FILE_REPLICATION :
                                {
                                    if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing FAILOVER_FILE_REPLICATION, Thread="+toString());
                                    long daemonKey=in.readLong();
                                    boolean useCompression = in.readBoolean();
                                    short retention = in.readShort();
                                    long fromServerTime = in.readLong();
                                    DaemonAccessEntry dae=AOServDaemonServer.getDaemonAccessEntry(daemonKey);
                                    if(dae.command!=AOServDaemonProtocol.FAILOVER_FILE_REPLICATION) throw new IOException("Mismatched DaemonAccessEntry command, dae.command!="+AOServDaemonProtocol.FAILOVER_FILE_REPLICATION);
                                    FailoverFileReplicationManager.failoverServer(
                                        socket,
                                        in,
                                        out,
                                        dae.param1,
                                        useCompression,
                                        retention,
                                        dae.param2,
                                        "t".equals(dae.param3),
                                        fromServerTime
                                    );
                                }
                                break;
                            case AOServDaemonProtocol.GET_AUTORESPONDER_CONTENT :
                                {
                                    if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing GET_AUTORESPONDER_CONTENT, Thread="+toString());
                                    String path=in.readUTF();
                                    if(key==null) throw new IOException("Only the master server may GET_AUTORESPONDER_CONTENT");
                                    String content=LinuxAccountManager.getAutoresponderContent(path);
                                    out.write(AOServDaemonProtocol.DONE);
                                    out.writeUTF(content);
                                }
                                break;
                            case AOServDaemonProtocol.GET_AWSTATS_FILE :
                                {
                                    if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing GET_AWSTATS_FILE, Thread="+toString());
                                    String siteName=in.readUTF();
                                    String path=in.readUTF();
                                    String queryString=in.readUTF();
                                    if(key==null) throw new IOException("Only the master server may GET_AWSTATS_FILE");
                                    AWStatsManager.getAWStatsFile(siteName, path, queryString, out);
                                    out.write(AOServDaemonProtocol.DONE);
                                }
                                break;
                            case AOServDaemonProtocol.GET_BACKUP_DATA :
                                {
                                    if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing GET_BACKUP_DATA, Thread="+toString());
                                    String path=in.readUTF();
                                    long skipBytes=in.readLong();
                                    if(key==null) throw new IOException("Only the master server may GET_BACKUP_DATA");
                                    BackupManager.getBackupData(path, out, skipBytes);
                                    out.write(AOServDaemonProtocol.DONE);
                                }
                                break;
                            case AOServDaemonProtocol.GET_CRON_TABLE :
                                {
                                    if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing GET_CRON_TABLE, Thread="+toString());
                                    String username=in.readUTF();
                                    if(key==null) throw new IOException("Only the master server may GET_CRON_TABLE");
                                    String cronTable=LinuxAccountManager.getCronTable(out, username);
                                    out.write(AOServDaemonProtocol.DONE);
                                    out.writeUTF(cronTable);
                                }
                                break;
                            case AOServDaemonProtocol.IS_PROCMAIL_MANUAL :
                                {
                                    if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing IS_PROCMAIL_MANUAL, Thread="+toString());
                                    int lsaPKey=in.readCompressedInt();
                                    if(key==null) throw new IOException("Only the master server may IS_PROCMAIL_MANUAL");
                                    LinuxServerAccount lsa=connector.linuxServerAccounts.get(lsaPKey);
                                    if(lsa==null) throw new SQLException("Unable to find LinuxServerAccount: "+lsaPKey);
                                    boolean isManual=ProcmailManager.isManual(lsa);
                                    out.write(AOServDaemonProtocol.DONE);
                                    out.writeBoolean(isManual);
                                }
                                break;
                            case AOServDaemonProtocol.GET_DAEMON_PROFILE :
                                {
                                    if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing GET_DAEMON_PROFILE, Thread="+toString());
                                    if(key==null) throw new IOException("Only the master server may GET_DAEMON_PROFILE");
                                    if(Profiler.getProfilerLevel()>Profiler.NONE) {
                                        String hostname=AOServDaemon.getThisAOServer().getServer().getHostname();
                                        MethodProfile[] profs=Profiler.getMethodProfiles();
                                        int len=profs.length;
                                        for(int c=0;c<len;c++) {
                                            DaemonProfile dp=DaemonProfile.getDaemonProfile(
                                                hostname,
                                                profs[c]
                                            );
                                            out.write(AOServDaemonProtocol.NEXT);
                                            dp.write(out, AOServProtocol.CURRENT_VERSION);
                                        }
                                    }
                                    out.write(AOServDaemonProtocol.DONE);
                                }
                                break;
                            case AOServDaemonProtocol.GET_DISK_DEVICE_TOTAL_SIZE :
                                {
                                    if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing GET_DISK_DEVICE_TOTAL_SIZE, Thread="+toString());
                                    String device=in.readUTF();
                                    if(key==null) throw new IOException("Only the master server may GET_DISK_DEVICE_TOTAL_SIZE");
                                    long bytes=BackupManager.getDiskDeviceTotalSize(device);
                                    out.write(AOServDaemonProtocol.DONE);
                                    out.writeLong(bytes);
                                }
                                break;
                            case AOServDaemonProtocol.GET_DISK_DEVICE_USED_SIZE :
                                {
                                    if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing GET_DISK_DEVICE_USED_SIZE, Thread="+toString());
                                    String device=in.readUTF();
                                    if(key==null) throw new IOException("Only the master server may GET_DISK_DEVICE_USED_SIZE");
                                    long bytes=BackupManager.getDiskDeviceUsedSize(device);
                                    out.write(AOServDaemonProtocol.DONE);
                                    out.writeLong(bytes);
                                }
                                break;
                            case AOServDaemonProtocol.GET_EMAIL_LIST_FILE :
                                {
                                    if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing GET_EMAIL_LIST_FILE, Thread="+toString());
                                    String path=in.readUTF();
                                    if(key==null) throw new IOException("Only the master server may GET_EMAIL_LIST_FILE");
                                    String file=EmailListManager.getEmailListFile(path);
                                    out.write(AOServDaemonProtocol.DONE);
                                    out.writeUTF(file);
                                }
                                break;
                            case AOServDaemonProtocol.GET_ENCRYPTED_INTERBASE_USER_PASSWORD :
                                {
                                    if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing GET_ENCRYPTED_INTERBASE_USER_PASSWORD, Thread="+toString());
                                    String username=in.readUTF();
                                    if(key==null) throw new IOException("Only the master server may GET_ENCRYPTED_INTERBASE_USER_PASSWORD");
                                    String encryptedPassword=InterBaseManager.getEncryptedPassword(username);
                                    out.write(AOServDaemonProtocol.DONE);
                                    out.writeUTF(encryptedPassword);
                                }
                                break;
                            case AOServDaemonProtocol.GET_ENCRYPTED_LINUX_ACCOUNT_PASSWORD :
                                {
                                    if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing GET_ENCRYPTED_LINUX_ACCOUNT_PASSWORD, Thread="+toString());
                                    String username=in.readUTF();
                                    if(key==null) throw new IOException("Only the master server may GET_ENCRYPTED_LINUX_ACCOUNT_PASSWORD");
                                    String encryptedPassword=LinuxAccountManager.getEncryptedPassword(username);
                                    out.write(AOServDaemonProtocol.DONE);
                                    out.writeUTF(encryptedPassword);
                                }
                                break;
                            case AOServDaemonProtocol.GET_ENCRYPTED_MYSQL_USER_PASSWORD :
                                {
                                    if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing GET_ENCRYPTED_MYSQL_USER_PASSWORD, Thread="+toString());
                                    int pkey=in.readCompressedInt();
                                    String username=in.readUTF();
                                    if(key==null) throw new IOException("Only the master server may GET_ENCRYPTED_MYSQL_USER_PASSWORD");
                                    MySQLServer mysqlServer=connector.mysqlServers.get(pkey);
                                    if(mysqlServer==null) throw new SQLException("Unable to find MySQLServer: "+pkey);
                                    String encryptedPassword=MySQLUserManager.getEncryptedPassword(mysqlServer, username);
                                    out.write(AOServDaemonProtocol.DONE);
                                    out.writeUTF(encryptedPassword);
                                }
                                break;
                            case AOServDaemonProtocol.GET_IMAP_FOLDER_SIZES :
                                {
                                    if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing GET_IMAP_FOLDER_SIZES, Thread="+toString());
                                    String username=in.readUTF();
                                    int numFolders=in.readCompressedInt();
                                    String[] folderNames=new String[numFolders];
                                    for(int c=0;c<numFolders;c++) folderNames[c]=in.readUTF();
                                    if(key==null) throw new IOException("Only the master server may GET_IMAP_FOLDER_SIZES");
                                    long[] sizes=EmailAddressManager.getImapFolderSizes(username, folderNames);
                                    out.write(AOServDaemonProtocol.DONE);
                                    for(int c=0;c<numFolders;c++) out.writeLong(sizes[c]);
                                }
                                break;
                            case AOServDaemonProtocol.GET_INBOX_ATTRIBUTES :
                                {
                                    if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing GET_INBOX_ATTRIBUTES, Thread="+toString());
                                    String username=in.readUTF();
                                    if(key==null) throw new IOException("Only the master server may GET_INBOX_ATTRIBUTES");
                                    long fileSize=EmailAddressManager.getInboxSize(username);
                                    long lastModified=EmailAddressManager.getInboxModified(username);
                                    out.write(AOServDaemonProtocol.DONE);
                                    out.writeLong(fileSize);
                                    out.writeLong(lastModified);
                                }
                                break;
                            case AOServDaemonProtocol.GET_MRTG_FILE :
                                {
                                    if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing GET_MRTG_FILE, Thread="+toString());
                                    String filename=in.readUTF();
                                    if(key==null) throw new IOException("Only the master server may GET_MRTG_FILE");
                                    ServerManager.getMrtgFile(filename, out);
                                    out.write(AOServDaemonProtocol.DONE);
                                }
                                break;
                            case AOServDaemonProtocol.GET_POSTGRES_PASSWORD :
                                {
                                    if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing GET_POSTGRES_PASSWORD, Thread="+toString());
                                    int pkey=in.readCompressedInt();
                                    if(key==null) throw new IOException("Only the master server may GET_POSTGRES_PASSWORD");
                                    PostgresServerUser psu=connector.postgresServerUsers.get(pkey);
                                    if(psu==null) throw new SQLException("Unable to find PostgresServerUser: "+pkey);
                                    String password=PostgresUserManager.getPassword(psu);
                                    out.write(AOServDaemonProtocol.DONE);
                                    out.writeUTF(password);
                                }
                                break;
                            case AOServDaemonProtocol.GRANT_DAEMON_ACCESS :
                                {
                                    if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing GRANT_DAEMON_ACCESS, Thread="+toString());
                                    long accessKey = in.readLong();
                                    int command = in.readCompressedInt();
                                    String param1 = in.readBoolean() ? in.readUTF() : null;
                                    String param2 = in.readBoolean() ? in.readUTF() : null;
                                    String param3 = in.readBoolean() ? in.readUTF() : null;
                                    if(key==null) throw new IOException("Only the master server may GRANT_DAEMON_ACCESS");
                                    AOServDaemonServer.grantDaemonAccess(accessKey, command, param1, param2, param3);
                                    out.write(AOServDaemonProtocol.DONE);
                                }
                                break;
                            /*case AOServDaemonProtocol.INITIALIZE_HTTPD_SITE_PASSWD_FILE :
                                {
                                    if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing INITIALIZE_HTTPD_SITE_PASSWD_FILE, Thread="+toString());
                                    int sitePKey=in.readCompressedInt();
                                    String username=in.readUTF();
                                    String encPassword=in.readUTF();
                                    if(key==null) throw new IOException("Only the master server may INITIALIZE_HTTPD_SITE_PASSWD_FILE");
                                    HttpdManager.initializeHttpdSitePasswdFile(
                                        sitePKey,
                                        username,
                                        encPassword
                                    );
                                    out.write(AOServDaemonProtocol.DONE);
                                }
                                break;
                             */
                            case AOServDaemonProtocol.REMOVE_BACKUP_DATA :
                                {
                                    if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing REMOVE_BACKUP_DATA, Thread="+toString());
                                    int backupPartitionPKey=in.readCompressedInt();
                                    BackupPartition bp=connector.backupPartitions.get(backupPartitionPKey);
                                    if(bp==null) throw new SQLException("Unable to find BackupPartition: "+backupPartitionPKey);
                                    String relativePath=in.readUTF();
                                    if(key==null) throw new IOException("Only the master server may REMOVE_BACKUP_DATA");
                                    BackupManager.removeBackupData(bp, relativePath);
                                    out.write(AOServDaemonProtocol.DONE);
                                }
                                break;
                            case AOServDaemonProtocol.REMOVE_EMAIL_LIST :
                                {
                                    if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing REMOVE_EMAIL_LIST, Thread="+toString());
                                    String path=in.readUTF();
                                    if(key==null) throw new IOException("Only the master server may REMOVE_EMAIL_LIST");
                                    EmailListManager.removeEmailListAddresses(path);
                                    out.write(AOServDaemonProtocol.DONE);
                                }
                                break;
                            case AOServDaemonProtocol.RESTART_APACHE :
                                {
                                    if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing RESTART_APACHE, Thread="+toString());
                                    if(key==null) throw new IOException("Only the master server may RESTART_APACHE");
                                    HttpdManager.restartApache();
                                    out.write(AOServDaemonProtocol.DONE);
                                }
                                break;
                            case AOServDaemonProtocol.RESTART_CRON :
                                if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing RESTART_CRON, Thread="+toString());
                                if(key==null) throw new IOException("Only the master server may RESTART_CRON");
                                ServerManager.restartCron();
                                out.write(AOServDaemonProtocol.DONE);
                                break;
                            case AOServDaemonProtocol.RESTART_INTERBASE :
                                if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing RESTART_INTERBASE, Thread="+toString());
                                if(key==null) throw new IOException("Only the master server may RESTART_INTERBASE");
                                InterBaseManager.restartInterBase();
                                out.write(AOServDaemonProtocol.DONE);
                                break;
                            case AOServDaemonProtocol.RESTART_MYSQL :
                                {
                                    if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing RESTART_MYSQL, Thread="+toString());
                                    int pkey=in.readCompressedInt();
                                    if(key==null) throw new IOException("Only the master server may RESTART_MYSQL");
                                    MySQLServer mysqlServer=connector.mysqlServers.get(pkey);
                                    if(mysqlServer==null) throw new SQLException("Unable to find MySQLServer: "+pkey);
                                    MySQLServerManager.restartMySQL(mysqlServer);
                                    out.write(AOServDaemonProtocol.DONE);
                                }
                                break;
                            case AOServDaemonProtocol.RESTART_POSTGRES :
                                {
                                    if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing RESTART_POSTGRES, Thread="+toString());
                                    int pkey=in.readCompressedInt();
                                    if(key==null) throw new IOException("Only the master server may RESTART_POSTGRES");
                                    PostgresServer ps=connector.postgresServers.get(pkey);
                                    if(ps==null) throw new SQLException("Unable to find PostgresServer: "+pkey);
                                    PostgresServerManager.restartPostgreSQL(ps);
                                    out.write(AOServDaemonProtocol.DONE);
                                }
                                break;
                            case AOServDaemonProtocol.RESTART_XFS :
                                if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing RESTART_XFS, Thread="+toString());
                                if(key==null) throw new IOException("Only the master server may RESTART_XFS");
                                ServerManager.restartXfs();
                                out.write(AOServDaemonProtocol.DONE);
                                break;
                            case AOServDaemonProtocol.RESTART_XVFB :
                                if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing RESTART_XVFB, Thread="+toString());
                                if(key==null) throw new IOException("Only the master server may RESTART_XVFB");
                                ServerManager.restartXvfb();
                                out.write(AOServDaemonProtocol.DONE);
                                break;
                            case AOServDaemonProtocol.SET_AUTORESPONDER_CONTENT :
                                {
                                    if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing SET_AUTORESPONDER_CONTENT, Thread="+toString());
                                    String path=in.readUTF();
                                    String content=in.readBoolean()?in.readUTF():null;
                                    int uid=in.readCompressedInt();
                                    int gid=in.readCompressedInt();
                                    if(key==null) throw new IOException("Only the master server may SET_AUTORESPONDER_CONTENT");
                                    LinuxAccountManager.setAutoresponderContent(
                                        path,
                                        content,
                                        uid,
                                        gid
                                    );
                                    out.write(AOServDaemonProtocol.DONE);
                                }
                                break;
                            case AOServDaemonProtocol.SET_CRON_TABLE :
                                {
                                    if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing SET_CRON_TABLE, Thread="+toString());
                                    String username=in.readUTF();
                                    String cronTable=in.readUTF();
                                    if(key==null) throw new IOException("Only the master server may SET_CRON_TABLE");
                                    LinuxAccountManager.setCronTable(username, cronTable);
                                    out.write(AOServDaemonProtocol.DONE);
                                }
                                break;
                            case AOServDaemonProtocol.SET_ENCRYPTED_LINUX_ACCOUNT_PASSWORD :
                                {
                                    if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing SET_ENCRYPTED_LINUX_ACCOUNT_PASSWORD, Thread="+toString());
                                    String username=in.readUTF();
                                    String encryptedPassword=in.readUTF();
                                    if(key==null) throw new IOException("Only the master server may SET_ENCRYPTED_LINUX_ACCOUNT_PASSWORD");
                                    LinuxAccountManager.setEncryptedPassword(username, encryptedPassword);
                                    out.write(AOServDaemonProtocol.DONE);
                                }
                                break;
                            case AOServDaemonProtocol.SET_EMAIL_LIST_FILE :
                                {
                                    if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing SET_EMAIL_LIST_FILE, Thread="+toString());
                                    String path=in.readUTF();
                                    String file=in.readUTF();
                                    int uid=in.readCompressedInt();
                                    int gid=in.readCompressedInt();
                                    int mode=in.readCompressedInt();
                                    if(key==null) throw new IOException("Only the master server may SET_EMAIL_LIST_FILE");
                                    EmailListManager.setEmailListFile(
                                        path,
                                        file,
                                        uid,
                                        gid,
                                        mode
                                    );
                                    out.write(AOServDaemonProtocol.DONE);
                                }
                                break;
                            case AOServDaemonProtocol.SET_IMAP_FOLDER_SUBSCRIBED :
                                {
                                    if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing SET_IMAP_FOLDER_SUBSCRIBED, Thread="+toString());
                                    String username=in.readUTF();
                                    String folderName=in.readUTF();
                                    boolean subscribed=in.readBoolean();
                                    if(key==null) throw new IOException("Only the master server may SET_IMAP_FOLDER_SUBSCRIBED");
                                    EmailAddressManager.setImapFolderSubscribed(username, folderName, subscribed);
                                    out.write(AOServDaemonProtocol.DONE);
                                }
                                break;
                            case AOServDaemonProtocol.SET_INTERBASE_USER_PASSWORD :
                                {
                                    if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing SET_INTERBASE_USER_PASSWORD, Thread="+toString());
                                    String username=in.readUTF();
                                    String password=in.readBoolean()?in.readUTF():null;
                                    if(key==null) throw new IOException("Only the master server may SET_INTERBASE_USER_PASSWORD");
                                    InterBaseManager.setPassword(username, password);
                                    out.write(AOServDaemonProtocol.DONE);
                                }
                                break;
                            case AOServDaemonProtocol.SET_LINUX_SERVER_ACCOUNT_PASSWORD :
                                {
                                    if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing SET_LINUX_SERVER_ACCOUNT_PASSWORD, Thread="+toString());
                                    String username=in.readUTF();
                                    String plainPassword=in.readUTF();
                                    if(key==null) throw new IOException("Only the master server may SET_LINUX_SERVER_ACCOUNT_PASSWORD");
                                    LinuxAccountManager.setPassword(username, plainPassword);
                                    out.write(AOServDaemonProtocol.DONE);
                                }
                                break;
                            case AOServDaemonProtocol.SET_MYSQL_USER_PASSWORD :
                                {
                                    if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing SET_MYSQL_USER_PASSWORD, Thread="+toString());
                                    int mysqlServerPKey=in.readCompressedInt();
                                    String username=in.readUTF();
                                    String password=in.readBoolean()?in.readUTF():null;
                                    if(key==null) throw new IOException("Only the master server may SET_MYSQL_USER_PASSWORD");
                                    MySQLServer mysqlServer=connector.mysqlServers.get(mysqlServerPKey);
                                    if(mysqlServer==null) throw new SQLException("Unable to find MySQLServer: "+mysqlServerPKey);
                                    MySQLUserManager.setPassword(mysqlServer, username, password);
                                    out.write(AOServDaemonProtocol.DONE);
                                }
                                break;
                            case AOServDaemonProtocol.SET_POSTGRES_USER_PASSWORD :
                                {
                                    if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing SET_POSTGRES_USER_PASSWORD, Thread="+toString());
                                    int pkey=in.readCompressedInt();
                                    String password=in.readBoolean()?in.readUTF():null;
                                    PostgresServerUser psu=connector.postgresServerUsers.get(pkey);
                                    if(psu==null) throw new SQLException("Unable to find PostgresServerUser: "+pkey);
                                    PostgresUserManager.setPassword(psu, password, false);
                                    out.write(AOServDaemonProtocol.DONE);
                                }
                                break;
                            case AOServDaemonProtocol.START_APACHE :
                                if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing START_APACHE, Thread="+toString());
                                if(key==null) throw new IOException("Only the master server may START_APACHE");
                                HttpdManager.startApache();
                                out.write(AOServDaemonProtocol.DONE);
                                break;
                            case AOServDaemonProtocol.START_CRON :
                                if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing START_CRON, Thread="+toString());
                                if(key==null) throw new IOException("Only the master server may START_CRON");
                                ServerManager.startCron();
                                out.write(AOServDaemonProtocol.DONE);
                                break;
                            case AOServDaemonProtocol.START_DISTRO :
                                {
                                    if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing START_DISTRO, Thread="+toString());
                                    boolean includeUser=in.readBoolean();
                                    if(key==null) throw new IOException("Only the master server may START_DISTRO");
                                    DistroManager.startDistro(includeUser);
                                    out.write(AOServDaemonProtocol.DONE);
                                }
                                break;
                            case AOServDaemonProtocol.START_INTERBASE :
                                if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing START_INTERBASE, Thread="+toString());
                                if(key==null) throw new IOException("Only the master server may START_INTERBASE");
                                InterBaseManager.startInterBase();
                                out.write(AOServDaemonProtocol.DONE);
                                break;
                            case AOServDaemonProtocol.START_JVM :
                                {
                                    if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing START_JVM, Thread="+toString());
                                    int sitePKey=in.readCompressedInt();
                                    if(key==null) throw new IOException("Only the master server may START_JVM");
                                    String message=HttpdManager.startJVM(sitePKey);
                                    out.write(AOServDaemonProtocol.DONE);
                                    out.writeBoolean(message!=null);
                                    if(message!=null) out.writeUTF(message);
                                }
                                break;
                            case AOServDaemonProtocol.START_MYSQL :
                                {
                                    if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing START_MYSQL, Thread="+toString());
                                    int pkey=in.readCompressedInt();
                                    if(key==null) throw new IOException("Only the master server may START_MYSQL");
                                    MySQLServer mysqlServer=connector.mysqlServers.get(pkey);
                                    if(mysqlServer==null) throw new SQLException("Unable to find MySQLServer: "+pkey);
                                    MySQLServerManager.startMySQL(mysqlServer);
                                    out.write(AOServDaemonProtocol.DONE);
                                }
                                break;
                            case AOServDaemonProtocol.START_POSTGRESQL :
                                {
                                    if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing START_POSTGRESQL, Thread="+toString());
                                    int pkey=in.readCompressedInt();
                                    if(key==null) throw new IOException("Only the master server may START_POSTGRESQL");
                                    PostgresServer ps=connector.postgresServers.get(pkey);
                                    if(ps==null) throw new SQLException("Unable to find PostgresServer: "+pkey);
                                    PostgresServerManager.startPostgreSQL(ps);
                                    out.write(AOServDaemonProtocol.DONE);
                                }
                                break;
                            case AOServDaemonProtocol.START_XFS :
                                if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing START_XFS, Thread="+toString());
                                if(key==null) throw new IOException("Only the master server may START_XFS");
                                ServerManager.startXfs();
                                out.write(AOServDaemonProtocol.DONE);
                                break;
                            case AOServDaemonProtocol.START_XVFB :
                                if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing START_XVFB, Thread="+toString());
                                if(key==null) throw new IOException("Only the master server may START_XVFB");
                                ServerManager.startXvfb();
                                out.write(AOServDaemonProtocol.DONE);
                                break;
                            case AOServDaemonProtocol.STOP_APACHE :
                                if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing STOP_APACHE, Thread="+toString());
                                if(key==null) throw new IOException("Only the master server may STOP_APACHE");
                                HttpdManager.stopApache();
                                out.write(AOServDaemonProtocol.DONE);
                                break;
                            case AOServDaemonProtocol.STOP_CRON :
                                if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing STOP_CRON, Thread="+toString());
                                if(key==null) throw new IOException("Only the master server may STOP_CRON");
                                ServerManager.stopCron();
                                out.write(AOServDaemonProtocol.DONE);
                                break;
                            case AOServDaemonProtocol.STOP_INTERBASE :
                                if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing STOP_INTERBASE, Thread="+toString());
                                if(key==null) throw new IOException("Only the master server may STOP_INTERBASE");
                                InterBaseManager.stopInterBase();
                                out.write(AOServDaemonProtocol.DONE);
                                break;
                            case AOServDaemonProtocol.STOP_JVM :
                                {
                                    if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing STOP_JVM, Thread="+toString());
                                    int sitePKey=in.readCompressedInt();
                                    if(key==null) throw new IOException("Only the master server may STOP_JVM");
                                    String message=HttpdManager.stopJVM(sitePKey);
                                    out.write(AOServDaemonProtocol.DONE);
                                    out.writeBoolean(message!=null);
                                    if(message!=null) out.writeUTF(message);
                                }
                                break;
                            case AOServDaemonProtocol.STOP_MYSQL :
                                {
                                    if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing STOP_MYSQL, Thread="+toString());
                                    int pkey=in.readCompressedInt();
                                    if(key==null) throw new IOException("Only the master server may STOP_MYSQL");
                                    MySQLServer mysqlServer=connector.mysqlServers.get(pkey);
                                    if(mysqlServer==null) throw new SQLException("Unable to find MySQLServer: "+pkey);
                                    MySQLServerManager.stopMySQL(mysqlServer);
                                    out.write(AOServDaemonProtocol.DONE);
                                }
                                break;
                            case AOServDaemonProtocol.STOP_POSTGRESQL :
                                {
                                    if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing STOP_POSTGRESQL, Thread="+toString());
                                    int pkey=in.readCompressedInt();
                                    if(key==null) throw new IOException("Only the master server may STOP_POSTGRESQL");
                                    PostgresServer ps=connector.postgresServers.get(pkey);
                                    if(ps==null) throw new SQLException("Unable to find PostgresServer: "+pkey);
                                    PostgresServerManager.stopPostgreSQL(ps);
                                    out.write(AOServDaemonProtocol.DONE);
                                }
                                break;
                            case AOServDaemonProtocol.STOP_XFS :
                                if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing STOP_XFS, Thread="+toString());
                                if(key==null) throw new IOException("Only the master server may STOP_XFS");
                                ServerManager.stopXfs();
                                out.write(AOServDaemonProtocol.DONE);
                                break;
                            case AOServDaemonProtocol.STOP_XVFB :
                                if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing STOP_XVFB, Thread="+toString());
                                if(key==null) throw new IOException("Only the master server may STOP_XVFB");
                                ServerManager.stopXvfb();
                                out.write(AOServDaemonProtocol.DONE);
                                break;
                            case AOServDaemonProtocol.STORE_BACKUP_DATA :
                                {
                                    if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing STORE_BACKUP_DATA, Thread="+toString());
                                    int backupPartitionPKey=in.readCompressedInt();
                                    BackupPartition bp=connector.backupPartitions.get(backupPartitionPKey);
                                    if(bp==null) throw new SQLException("Unable to find BackupPartition: "+backupPartitionPKey);
                                    String relativePath=in.readUTF();
                                    boolean isCompressed = in.readBoolean();
                                    long expectedMD5Hi = in.readLong();
                                    long expectedMD5Lo = in.readLong();
                                    if(key==null) throw new IOException("Only the master server may STORE_BACKUP_DATA");
                                    String expectedMD5 = MD5.getMD5String(expectedMD5Hi, expectedMD5Lo);
                                    String md5 = BackupManager.storeBackupData(bp, relativePath, isCompressed, in, null);
                                    boolean isGood=in.readBoolean();
                                    if(!isGood) {
                                        // If the MD5 hash did not match during the file upload, then this backup is canceled
                                        BackupManager.removeBackupData(bp, relativePath);
                                    } else {
                                        // Otherwise, make sure the MD5 is still matching
                                        if(!expectedMD5.equals(md5)) throw new IOException("Unexpected MD5 digest: "+md5);
                                    }
                                    out.write(AOServDaemonProtocol.DONE);
                                }
                                break;
                            case AOServDaemonProtocol.STORE_BACKUP_DATA_DIRECT_ACCESS :
                                {
                                    if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing STORE_BACKUP_DATA_DIRECT_ACCESS, Thread="+toString());
                                    // Read the parameters from the client
                                    long daemonKey=in.readLong();
                                    String filename=in.readUTF();
                                    boolean isCompressed = in.readBoolean();
                                    // Get the values provided by the master
                                    DaemonAccessEntry dae=AOServDaemonServer.getDaemonAccessEntry(daemonKey);
                                    if(dae.command!=AOServDaemonProtocol.STORE_BACKUP_DATA_DIRECT_ACCESS) throw new IOException("Mismatched DaemonAccessEntry command, dae.command!="+AOServDaemonProtocol.STORE_BACKUP_DATA_DIRECT_ACCESS);
                                    int backupPartitionPKey=Integer.parseInt(dae.param1);
                                    int backupData=Integer.parseInt(dae.param2);
                                    String expectedMD5=dae.param3;
                                    // Calculate other necessary parameters
                                    BackupPartition bp=connector.backupPartitions.get(backupPartitionPKey);
                                    if(bp==null) throw new SQLException("Unable to find BackupPartition: "+backupPartitionPKey);
                                    String relativePath=
                                        isCompressed
                                        ? (BackupData.getRelativePathPrefix(backupData) + filename + ".gz")
                                        : (BackupData.getRelativePathPrefix(backupData) + filename)
                                    ;
                                    // Store the data
                                    long[] byteCount = new long[1];
                                    String md5 = BackupManager.storeBackupData(bp, relativePath, isCompressed, in, byteCount);
                                    if(!md5.equals(expectedMD5)) {
                                        // If the MD5 hash did not match during the file upload, then this backup is canceled
                                        BackupManager.removeBackupData(bp, relativePath);
                                        throw new IOException("Unexpected MD5 digest: "+md5);
                                    } else {
                                        // Otherwise, tell the master server that it is now stored
                                        connector.backupDatas.flagAsStored(backupData, isCompressed, byteCount[0]);
                                    }
                                    out.write(AOServDaemonProtocol.DONE);
                                }
                                break;
                            case AOServDaemonProtocol.TAR_HOME_DIRECTORY :
                                {
                                    if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing TAR_HOME_DIRECTORY, Thread="+toString());
                                    String username=in.readUTF();
                                    if(key==null) throw new IOException("Only the master server may TAR_HOME_DIRECTORY");
                                    LinuxAccountManager.tarHomeDirectory(out, username);
                                    out.write(AOServDaemonProtocol.DONE);
                                }
                                break;
                            case AOServDaemonProtocol.UNTAR_HOME_DIRECTORY :
                                {
                                    if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing UNTAR_HOME_DIRECTORY, Thread="+toString());
                                    String username=in.readUTF();
                                    if(key==null) throw new IOException("Only the master server may UNTAR_HOME_DIRECTORY");
                                    LinuxAccountManager.untarHomeDirectory(in, username);
                                    out.write(AOServDaemonProtocol.DONE);
                                }
                                break;
                            case AOServDaemonProtocol.WAIT_FOR_REBUILD :
                                {
                                    if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing WAIT_FOR_REBUILD, Thread="+toString());
                                    int tableID=in.readCompressedInt();
                                    if(key==null) throw new IOException("Only the master server may WAIT_FOR_REBUILD");
                                    switch(tableID) {
                                        case SchemaTable.HTTPD_SITES :
                                            HttpdManager.waitForRebuild();
                                            break;
                                        case SchemaTable.LINUX_ACCOUNTS :
                                            LinuxAccountManager.waitForRebuild();
                                            break;
                                        case SchemaTable.INTERBASE_USERS :
                                            InterBaseManager.waitForRebuild();
                                            break;
                                        case SchemaTable.MYSQL_DATABASES :
                                            MySQLDatabaseManager.waitForRebuild();
                                            break;
                                        case SchemaTable.MYSQL_DB_USERS :
                                            MySQLDBUserManager.waitForRebuild();
                                            break;
                                        case SchemaTable.MYSQL_USERS :
                                            MySQLUserManager.waitForRebuild();
                                            break;
                                        case SchemaTable.POSTGRES_DATABASES :
                                            PostgresDatabaseManager.waitForRebuild();
                                            break;
                                        case SchemaTable.POSTGRES_SERVERS :
                                            MySQLServerManager.waitForRebuild();
                                            break;
                                        case SchemaTable.POSTGRES_USERS :
                                            PostgresUserManager.waitForRebuild();
                                            break;
                                        default :
                                            throw new IOException("Unable to wait for rebuild on table #"+tableID);
                                    }
                                    out.write(AOServDaemonProtocol.DONE);
                                }
                                break;
                            default :
                                break Loop;
                        }
                    } catch (IOException err) {
                        String message=err.getMessage();
                        if(
                            !"Connection reset by peer".equals(message)
                        ) AOServDaemon.reportError(err, null);
                        out.write(AOServDaemonProtocol.IO_EXCEPTION);
                        out.writeUTF(message == null ? "null" : message);
                    } catch (SQLException err) {
                        AOServDaemon.reportError(err, null);
                        out.write(AOServDaemonProtocol.SQL_EXCEPTION);
                        String message = err.getMessage();
                        out.writeUTF(message == null ? "null" : message);
                    }
                    out.flush();
                }
            } catch(EOFException err) {
                // Normal for abrupt connection closing
            } catch(ThreadDeath TD) {
                throw TD;
            } catch(SocketException err) {
                String message=err.getMessage();
                if(
                    !"Socket closed".equals(message)
                ) AOServDaemon.reportError(err, null);
            } catch(Throwable T) {
                AOServDaemon.reportError(T, null);
            } finally {
                // Close the socket
                try {
                    socket.close();
                } catch (IOException err) {
                    // Ignore any socket close problems
                }
            }
        } finally {
            Profiler.endProfile(Profiler.IO);
        }
    }
}