package com.aoindustries.aoserv.daemon;

/*
 * Copyright 2000-2009 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.aoserv.client.AOServConnector;
import com.aoindustries.aoserv.client.AOServProtocol;
import com.aoindustries.aoserv.client.AOServer;
import com.aoindustries.aoserv.client.AOServerDaemonHost;
import com.aoindustries.aoserv.client.BusinessAdministrator;
import com.aoindustries.aoserv.client.DaemonProfile;
import com.aoindustries.aoserv.client.LinuxServerAccount;
import com.aoindustries.aoserv.client.MySQLDatabase;
import com.aoindustries.aoserv.client.MySQLServer;
import com.aoindustries.aoserv.client.NetDevice;
import com.aoindustries.aoserv.client.PostgresDatabase;
import com.aoindustries.aoserv.client.PostgresServer;
import com.aoindustries.aoserv.client.PostgresServerUser;
import com.aoindustries.aoserv.client.SchemaTable;
import com.aoindustries.aoserv.daemon.backup.BackupManager;
import com.aoindustries.aoserv.daemon.client.AOServDaemonProtocol;
import com.aoindustries.aoserv.daemon.distro.DistroManager;
import com.aoindustries.aoserv.daemon.email.EmailListManager;
import com.aoindustries.aoserv.daemon.email.ImapManager;
import com.aoindustries.aoserv.daemon.email.ProcmailManager;
import com.aoindustries.aoserv.daemon.failover.FailoverFileReplicationManager;
import com.aoindustries.aoserv.daemon.httpd.AWStatsManager;
import com.aoindustries.aoserv.daemon.httpd.HttpdManager;
import com.aoindustries.aoserv.daemon.httpd.HttpdServerManager;
import com.aoindustries.aoserv.daemon.httpd.HttpdSiteManager;
import com.aoindustries.aoserv.daemon.monitor.MrtgManager;
import com.aoindustries.aoserv.daemon.mysql.MySQLDBUserManager;
import com.aoindustries.aoserv.daemon.mysql.MySQLDatabaseManager;
import com.aoindustries.aoserv.daemon.mysql.MySQLServerManager;
import com.aoindustries.aoserv.daemon.mysql.MySQLUserManager;
import com.aoindustries.aoserv.daemon.net.NetDeviceManager;
import com.aoindustries.aoserv.daemon.postgres.PostgresDatabaseManager;
import com.aoindustries.aoserv.daemon.postgres.PostgresServerManager;
import com.aoindustries.aoserv.daemon.postgres.PostgresUserManager;
import com.aoindustries.aoserv.daemon.server.ServerManager;
import com.aoindustries.aoserv.daemon.unix.linux.LinuxAccountManager;
import com.aoindustries.io.CompressedDataInputStream;
import com.aoindustries.io.CompressedDataOutputStream;
import com.aoindustries.profiler.MethodProfile;
import com.aoindustries.profiler.Profiler;
import com.aoindustries.util.UnixCrypt;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The <code>AOServServerThread</code> handles a connection once it is accepted.
 *
 * @author  AO Industries, Inc.
 */
final public class AOServDaemonServerThread extends Thread {

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

    /** Keeps a copy to avoid multiple copies on each access. */
    private static final SchemaTable.TableID[] tableIDs = SchemaTable.TableID.values();

    /**
     * Creates a new, running <code>AOServServerThread</code>.
     */
    public AOServDaemonServerThread(AOServDaemonServer server, Socket socket) throws IOException {
	super("AOServ Daemon Server Thread - " + socket.getInetAddress().getHostAddress());
        this.server = server;
        this.socket = socket;
        this.in = new CompressedDataInputStream(new BufferedInputStream(socket.getInputStream()));
        this.out = new CompressedDataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
        this.out.flush();

        start();
    }

    private static boolean passwordMatches(String password, String crypted) throws IOException {
        // Try hash first
        String hashed = BusinessAdministrator.hash(password);
        if(hashed.equals(crypted)) return true;
        // Try old crypt next
        if(crypted.length()<=2) return false;
        String salt=crypted.substring(0,2);
        return UnixCrypt.crypt(password, salt).equals(crypted);
    }

    @Override
    public void run() {
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
                    if(!passwordMatches(key, correctKey)) {
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
            }
            out.writeBoolean(true);
            out.flush();

            int taskCode;
        Loop:
            while ((taskCode = in.readCompressedInt()) != AOServDaemonProtocol.QUIT) {
                try {
                    switch (taskCode) {
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
                                short fromServerYear = in.readShort();
                                short fromServerMonth = in.readShort();
                                short fromServerDay = in.readShort();
                                List<String> replicatedMySQLServers;
                                List<String> replicatedMySQLMinorVersions;
                                if(retention==1) {
                                    int len=in.readCompressedInt();
                                    replicatedMySQLServers=new ArrayList<String>(len);
                                    replicatedMySQLMinorVersions=new ArrayList<String>(len);
                                    for(int c=0;c<len;c++) {
                                        replicatedMySQLServers.add(in.readUTF());
                                        replicatedMySQLMinorVersions.add(in.readUTF());
                                    }
                                } else {
                                    replicatedMySQLServers=Collections.emptyList();
                                    replicatedMySQLMinorVersions=Collections.emptyList();
                                }
                                DaemonAccessEntry dae=AOServDaemonServer.getDaemonAccessEntry(daemonKey);
                                if(dae.command!=AOServDaemonProtocol.FAILOVER_FILE_REPLICATION) throw new IOException("Mismatched DaemonAccessEntry command, dae.command!="+AOServDaemonProtocol.FAILOVER_FILE_REPLICATION);
                                FailoverFileReplicationManager.failoverServer(
                                    socket,
                                    in,
                                    out,
                                    dae.param1, // fromServer
                                    useCompression,
                                    retention,
                                    dae.param2, // toPath (complete with server hostname and other path stuff)
                                    fromServerYear,
                                    fromServerMonth,
                                    fromServerDay,
                                    replicatedMySQLServers,
                                    replicatedMySQLMinorVersions,
                                    dae.param3==null ? -1 : Integer.parseInt(dae.param3) // quota_gid
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
                        case AOServDaemonProtocol.GET_CRON_TABLE :
                            {
                                if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing GET_CRON_TABLE, Thread="+toString());
                                String username=in.readUTF();
                                if(key==null) throw new IOException("Only the master server may GET_CRON_TABLE");
                                String cronTable=LinuxAccountManager.getCronTable(username);
                                out.write(AOServDaemonProtocol.DONE);
                                out.writeUTF(cronTable);
                            }
                            break;
                        case AOServDaemonProtocol.GET_NET_DEVICE_BONDING_REPORT :
                            {
                                if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing GET_NET_DEVICE_BONDING_REPORT, Thread="+toString());
                                int pkey = in.readCompressedInt();
                                if(key==null) throw new IOException("Only the master server may GET_NET_DEVICE_BONDING_REPORT");
                                NetDevice netDevice = connector.netDevices.get(pkey);
                                if(netDevice==null) throw new SQLException("Unable to find NetDevice: "+pkey);
                                String report = NetDeviceManager.getNetDeviceBondingReport(netDevice);
                                out.write(AOServDaemonProtocol.DONE);
                                out.writeUTF(report);
                            }
                            break;
                        case AOServDaemonProtocol.GET_NET_DEVICE_STATISTICS_REPORT :
                            {
                                if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing GET_NET_DEVICE_STATISTICS_REPORT, Thread="+toString());
                                int pkey = in.readCompressedInt();
                                if(key==null) throw new IOException("Only the master server may GET_NET_DEVICE_STATISTICS_REPORT");
                                NetDevice netDevice = connector.netDevices.get(pkey);
                                if(netDevice==null) throw new SQLException("Unable to find NetDevice: "+pkey);
                                String report = NetDeviceManager.getNetDeviceStatisticsReport(netDevice);
                                out.write(AOServDaemonProtocol.DONE);
                                out.writeUTF(report);
                            }
                            break;
                        case AOServDaemonProtocol.GET_3WARE_RAID_REPORT :
                            {
                                if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing GET_3WARE_RAID_REPORT, Thread="+toString());
                                if(key==null) throw new IOException("Only the master server may GET_3WARE_RAID_REPORT");
                                String report = ServerManager.get3wareRaidReport();
                                out.write(AOServDaemonProtocol.DONE);
                                out.writeUTF(report);
                            }
                            break;
                        case AOServDaemonProtocol.GET_MD_RAID_REPORT :
                            {
                                if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing GET_MD_RAID_REPORT, Thread="+toString());
                                if(key==null) throw new IOException("Only the master server may GET_MD_RAID_REPORT");
                                String report = ServerManager.getMdRaidReport();
                                out.write(AOServDaemonProtocol.DONE);
                                out.writeUTF(report);
                            }
                            break;
                        case AOServDaemonProtocol.GET_DRBD_REPORT :
                            {
                                if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing GET_DRBD_REPORT, Thread="+toString());
                                if(key==null) throw new IOException("Only the master server may GET_DRBD_REPORT");
                                String report = ServerManager.getDrbdReport();
                                out.write(AOServDaemonProtocol.DONE);
                                out.writeUTF(report);
                            }
                            break;
                        case AOServDaemonProtocol.GET_LVM_REPORT :
                            {
                                if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing GET_LVM_REPORT, Thread="+toString());
                                if(key==null) throw new IOException("Only the master server may GET_LVM_REPORT");
                                String[] report = ServerManager.getLvmReport();
                                out.write(AOServDaemonProtocol.DONE);
                                out.writeUTF(report[0]);
                                out.writeUTF(report[1]);
                                out.writeUTF(report[2]);
                            }
                            break;
                        case AOServDaemonProtocol.GET_HDD_TEMP_REPORT :
                            {
                                if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing GET_HDD_TEMP_REPORT, Thread="+toString());
                                if(key==null) throw new IOException("Only the master server may GET_HDD_TEMP_REPORT");
                                String report = ServerManager.getHddTempReport();
                                out.write(AOServDaemonProtocol.DONE);
                                out.writeUTF(report);
                            }
                            break;
                        case AOServDaemonProtocol.GET_HDD_MODEL_REPORT :
                            {
                                if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing GET_HDD_MODEL_REPORT, Thread="+toString());
                                if(key==null) throw new IOException("Only the master server may GET_HDD_MODEL_REPORT");
                                String report = ServerManager.getHddModelReport();
                                out.write(AOServDaemonProtocol.DONE);
                                out.writeUTF(report);
                            }
                            break;
                        case AOServDaemonProtocol.GET_FILESYSTEMS_CSV_REPORT :
                            {
                                if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing GET_FILESYSTEMS_CSV_REPORT, Thread="+toString());
                                if(key==null) throw new IOException("Only the master server may GET_FILESYSTEMS_CSV_REPORT");
                                String report = ServerManager.getFilesystemsCsvReport();
                                out.write(AOServDaemonProtocol.DONE);
                                out.writeUTF(report);
                            }
                            break;
                        case AOServDaemonProtocol.GET_AO_SERVER_LOADAVG_REPORT :
                            {
                                if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing GET_AO_SERVER_LOADAVG_REPORT, Thread="+toString());
                                if(key==null) throw new IOException("Only the master server may GET_AO_SERVER_LOADAVG_REPORT");
                                String report = ServerManager.getLoadAvgReport();
                                out.write(AOServDaemonProtocol.DONE);
                                out.writeUTF(report);
                            }
                            break;
                        case AOServDaemonProtocol.GET_AO_SERVER_MEMINFO_REPORT :
                            {
                                if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing GET_AO_SERVER_MEMINFO_REPORT, Thread="+toString());
                                if(key==null) throw new IOException("Only the master server may GET_AO_SERVER_MEMINFO_REPORT");
                                String report = ServerManager.getMemInfoReport();
                                out.write(AOServDaemonProtocol.DONE);
                                out.writeUTF(report);
                            }
                            break;
                        case AOServDaemonProtocol.GET_AO_SERVER_SYSTEM_TIME_MILLIS :
                            {
                                if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing GET_AO_SERVER_SYSTEM_TIME_MILLIS, Thread="+toString());
                                if(key==null) throw new IOException("Only the master server may GET_AO_SERVER_SYSTEM_TIME_MILLIS");
                                long currentTime = System.currentTimeMillis();
                                out.write(AOServDaemonProtocol.DONE);
                                out.writeLong(currentTime);
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
                                    String hostname=AOServDaemon.getThisAOServer().getHostname();
                                    List<MethodProfile> profs=Profiler.getMethodProfiles();
                                    int len=profs.size();
                                    for(int c=0;c<len;c++) {
                                        DaemonProfile dp=DaemonProfile.getDaemonProfile(
                                            hostname,
                                            profs.get(c)
                                        );
                                        out.write(AOServDaemonProtocol.NEXT);
                                        dp.write(out, AOServProtocol.Version.CURRENT_VERSION);
                                    }
                                }
                                out.write(AOServDaemonProtocol.DONE);
                            }
                            break;
                        case AOServDaemonProtocol.GET_DISK_DEVICE_TOTAL_SIZE :
                            {
                                if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing GET_DISK_DEVICE_TOTAL_SIZE, Thread="+toString());
                                String path=in.readUTF();
                                if(key==null) throw new IOException("Only the master server may GET_DISK_DEVICE_TOTAL_SIZE");
                                long bytes=BackupManager.getDiskDeviceTotalSize(path);
                                out.write(AOServDaemonProtocol.DONE);
                                out.writeLong(bytes);
                            }
                            break;
                        case AOServDaemonProtocol.GET_DISK_DEVICE_USED_SIZE :
                            {
                                if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing GET_DISK_DEVICE_USED_SIZE, Thread="+toString());
                                String path=in.readUTF();
                                if(key==null) throw new IOException("Only the master server may GET_DISK_DEVICE_USED_SIZE");
                                long bytes=BackupManager.getDiskDeviceUsedSize(path);
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
                                long[] sizes=ImapManager.getImapFolderSizes(username, folderNames);
                                out.write(AOServDaemonProtocol.DONE);
                                for(int c=0;c<numFolders;c++) out.writeLong(sizes[c]);
                            }
                            break;
                        case AOServDaemonProtocol.GET_INBOX_ATTRIBUTES :
                            {
                                if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing GET_INBOX_ATTRIBUTES, Thread="+toString());
                                String username=in.readUTF();
                                if(key==null) throw new IOException("Only the master server may GET_INBOX_ATTRIBUTES");
                                long fileSize=ImapManager.getInboxSize(username);
                                long lastModified=ImapManager.getInboxModified(username);
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
                                MrtgManager.getMrtgFile(filename, out);
                                out.write(AOServDaemonProtocol.DONE);
                            }
                            break;
                        case AOServDaemonProtocol.GET_MYSQL_MASTER_STATUS :
                            {
                                if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing GET_MYSQL_MASTER_STATUS, Thread="+toString());
                                int mysqlServer = in.readCompressedInt();
                                if(key==null) throw new IOException("Only the master server may GET_MYSQL_MASTER_STATUS");
                                MySQLDatabaseManager.getMasterStatus(mysqlServer, out);
                            }
                            break;
                        case AOServDaemonProtocol.GET_MYSQL_SLAVE_STATUS :
                            {
                                if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing GET_MYSQL_SLAVE_STATUS, Thread="+toString());
                                String failoverRoot = in.readUTF();
                                int nestedOperatingSystemVersion = in.readCompressedInt();
                                int port = in.readCompressedInt();
                                if(key==null) throw new IOException("Only the master server may GET_MYSQL_SLAVE_STATUS");
                                MySQLDatabaseManager.getSlaveStatus(failoverRoot, nestedOperatingSystemVersion, port, out);
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
                                HttpdServerManager.restartApache();
                                out.write(AOServDaemonProtocol.DONE);
                            }
                            break;
                        case AOServDaemonProtocol.RESTART_CRON :
                            if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing RESTART_CRON, Thread="+toString());
                            if(key==null) throw new IOException("Only the master server may RESTART_CRON");
                            ServerManager.restartCron();
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
                                ImapManager.setImapFolderSubscribed(username, folderName, subscribed);
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
                            HttpdServerManager.startApache();
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
                        case AOServDaemonProtocol.START_JVM :
                            {
                                if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing START_JVM, Thread="+toString());
                                int sitePKey=in.readCompressedInt();
                                if(key==null) throw new IOException("Only the master server may START_JVM");
                                String message=HttpdSiteManager.startHttpdSite(sitePKey);
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
                            HttpdServerManager.stopApache();
                            out.write(AOServDaemonProtocol.DONE);
                            break;
                        case AOServDaemonProtocol.STOP_CRON :
                            if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing STOP_CRON, Thread="+toString());
                            if(key==null) throw new IOException("Only the master server may STOP_CRON");
                            ServerManager.stopCron();
                            out.write(AOServDaemonProtocol.DONE);
                            break;
                        case AOServDaemonProtocol.STOP_JVM :
                            {
                                if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing STOP_JVM, Thread="+toString());
                                int sitePKey=in.readCompressedInt();
                                if(key==null) throw new IOException("Only the master server may STOP_JVM");
                                String message=HttpdSiteManager.stopHttpdSite(sitePKey);
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
                                SchemaTable.TableID tableID=tableIDs[in.readCompressedInt()];
                                if(key==null) throw new IOException("Only the master server may WAIT_FOR_REBUILD");
                                switch(tableID) {
                                    case HTTPD_SITES :
                                        HttpdManager.waitForRebuild();
                                        break;
                                    case LINUX_ACCOUNTS :
                                        LinuxAccountManager.waitForRebuild();
                                        break;
                                    case MYSQL_DATABASES :
                                        MySQLDatabaseManager.waitForRebuild();
                                        break;
                                    case MYSQL_DB_USERS :
                                        MySQLDBUserManager.waitForRebuild();
                                        break;
                                    case MYSQL_USERS :
                                        MySQLUserManager.waitForRebuild();
                                        break;
                                    case POSTGRES_DATABASES :
                                        PostgresDatabaseManager.waitForRebuild();
                                        break;
                                    case POSTGRES_SERVERS :
                                        MySQLServerManager.waitForRebuild();
                                        break;
                                    case POSTGRES_USERS :
                                        PostgresUserManager.waitForRebuild();
                                        break;
                                    default :
                                        throw new IOException("Unable to wait for rebuild on table "+tableID);
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
    }
}