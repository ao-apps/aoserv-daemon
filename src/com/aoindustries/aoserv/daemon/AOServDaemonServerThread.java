package com.aoindustries.aoserv.daemon;

/*
 * Copyright 2000-2009 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.aoserv.client.AOServConnector;
import com.aoindustries.aoserv.client.AOServer;
import com.aoindustries.aoserv.client.AOServerDaemonHost;
import com.aoindustries.aoserv.client.LinuxAccount;
import com.aoindustries.aoserv.client.MySQLDatabase;
import com.aoindustries.aoserv.client.MySQLServer;
import com.aoindustries.aoserv.client.NetBind;
import com.aoindustries.aoserv.client.NetDevice;
import com.aoindustries.aoserv.client.PostgresDatabase;
import com.aoindustries.aoserv.client.PostgresServer;
import com.aoindustries.aoserv.client.PostgresUser;
import com.aoindustries.aoserv.client.ServiceName;
import com.aoindustries.aoserv.client.validator.DomainName;
import com.aoindustries.aoserv.client.validator.HashedPassword;
import com.aoindustries.aoserv.client.validator.Hostname;
import com.aoindustries.aoserv.client.validator.MySQLDatabaseName;
import com.aoindustries.aoserv.client.validator.MySQLTableName;
import com.aoindustries.aoserv.client.validator.NetPort;
import com.aoindustries.aoserv.client.validator.UnixPath;
import com.aoindustries.aoserv.client.validator.UserId;
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
import com.aoindustries.math.LongLong;
import com.aoindustries.noc.monitor.portmon.PortMonitor;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;
import javax.net.ssl.SSLHandshakeException;

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

    @Override
    public void run() {
        try {
            AOServConnector<?,?> connector=AOServDaemon.getConnector();
            AOServer thisAOServer=AOServDaemon.getThisAOServer();

            // Read the key first so that any failed connection looks the same from the outside,
            // regardless of reason
            String protocolVersion=in.readUTF();
            String daemonKey=in.readBoolean()?in.readUTF():null;
            if(!protocolVersion.equals(AOServDaemonProtocol.CURRENT_VERSION)) {
                out.writeBoolean(false);
                out.writeUTF(AOServDaemonProtocol.CURRENT_VERSION);
                out.flush();
                return;
            } else {
                out.writeBoolean(true);
            }
            if(daemonKey!=null) {
                // Must come from one of the hosts listed in the database
                com.aoindustries.aoserv.client.validator.InetAddress hostAddress = com.aoindustries.aoserv.client.validator.InetAddress.valueOf(socket.getInetAddress().getHostAddress());
                boolean isOK=false;
            HOSTS:
                for(AOServerDaemonHost allowedHost : thisAOServer.getAoServerDaemonHosts()) {
                    Hostname allowedHostname = allowedHost.getHost();
                    com.aoindustries.aoserv.client.validator.InetAddress allowedIp = allowedHostname.getInetAddress();
                    if(allowedIp!=null) {
                        // Match IP addresses
                        if(allowedIp.equals(hostAddress)) {
                            isOK = true;
                            break HOSTS;
                        }
                    } else {
                        // Match result of DNS query
                        for(InetAddress dnsResult : InetAddress.getAllByName(allowedHostname.getDomainName().getDomain()+".")) {
                            if(com.aoindustries.aoserv.client.validator.InetAddress.valueOf(dnsResult.getHostAddress()).equals(hostAddress)) {
                                isOK=true;
                                break HOSTS;
                            }
                        }
                    }
                }
                if(isOK) {
                    // Authenticate the client first
                    HashedPassword correctKey=thisAOServer.getDaemonKey();
                    if(!correctKey.passwordMatches(daemonKey)) {
                        System.err.println("Connection attempted from " + hostAddress + " with invalid key");
                        out.writeBoolean(false);
                        out.flush();
                        return;
                    }
                } else {
                    LogFactory.getLogger(AOServDaemonServerThread.class).log(Level.WARNING, "Connection attempted from " + hostAddress + " but not listed in ao_server_daemon_hosts");
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
                                if(daemonKey==null) throw new IOException("Only the master server may COMPARE_LINUX_ACCOUNT_PASSWORD");
                                boolean matches=LinuxAccountManager.comparePassword(UserId.valueOf(username), password);
                                out.write(AOServDaemonProtocol.DONE);
                                out.writeBoolean(matches);
                            }
                            break;
                        case AOServDaemonProtocol.DUMP_MYSQL_DATABASE :
                            {
                                if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing DUMP_MYSQL_DATABASE, Thread="+toString());
                                int pkey=in.readCompressedInt();
                                if(daemonKey==null) throw new IOException("Only the master server may DUMP_MYSQL_DATABASE");
                                MySQLDatabase md=connector.getMysqlDatabases().get(pkey);
                                if(md==null) throw new AssertionError("Unable to find MySQLDatabase: "+pkey);
                                MySQLDatabaseManager.dumpDatabase(md, out);
                                out.write(AOServDaemonProtocol.DONE);
                            }
                            break;
                        case AOServDaemonProtocol.DUMP_POSTGRES_DATABASE :
                            {
                                if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing DUMP_POSTGRES_DATABASE, Thread="+toString());
                                int pkey=in.readCompressedInt();
                                if(daemonKey==null) throw new IOException("Only the master server may DUMP_POSTGRES_DATABASE");
                                PostgresDatabase pd=connector.getPostgresDatabases().get(pkey);
                                if(pd==null) throw new AssertionError("Unable to find PostgresDatabase: "+pkey);
                                PostgresDatabaseManager.dumpDatabase(pd, out);
                                out.write(AOServDaemonProtocol.DONE);
                            }
                            break;
                        case AOServDaemonProtocol.FAILOVER_FILE_REPLICATION :
                            {
                                if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing FAILOVER_FILE_REPLICATION, Thread="+toString());
                                long daemonAccessKey=in.readLong();
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
                                DaemonAccessEntry dae=AOServDaemonServer.getDaemonAccessEntry(daemonAccessKey);
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
                        case AOServDaemonProtocol.VNC_CONSOLE :
                            {
                                if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing VNC_CONSOLE, Thread="+toString());
                                long daemonAccessKey=in.readLong();
                                DaemonAccessEntry dae=AOServDaemonServer.getDaemonAccessEntry(daemonAccessKey);
                                if(dae.command!=AOServDaemonProtocol.VNC_CONSOLE) throw new IOException("Mismatched DaemonAccessEntry command, dae.command!="+AOServDaemonProtocol.VNC_CONSOLE);
                                ServerManager.vncConsole(
                                    socket,
                                    in,
                                    out,
                                    dae.param1 // server_name
                                );
                            }
                            break;
                        case AOServDaemonProtocol.GET_AUTORESPONDER_CONTENT :
                            {
                                if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing GET_AUTORESPONDER_CONTENT, Thread="+toString());
                                String path=in.readUTF();
                                if(daemonKey==null) throw new IOException("Only the master server may GET_AUTORESPONDER_CONTENT");
                                String content=LinuxAccountManager.getAutoresponderContent(UnixPath.valueOf(path));
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
                                if(daemonKey==null) throw new IOException("Only the master server may GET_AWSTATS_FILE");
                                AWStatsManager.getAWStatsFile(DomainName.valueOf(siteName), path, queryString, out);
                                out.write(AOServDaemonProtocol.DONE);
                            }
                            break;
                        case AOServDaemonProtocol.GET_CRON_TABLE :
                            {
                                if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing GET_CRON_TABLE, Thread="+toString());
                                String username=in.readUTF();
                                if(daemonKey==null) throw new IOException("Only the master server may GET_CRON_TABLE");
                                String cronTable=LinuxAccountManager.getCronTable(UserId.valueOf(username));
                                out.write(AOServDaemonProtocol.DONE);
                                out.writeUTF(cronTable);
                            }
                            break;
                        case AOServDaemonProtocol.GET_NET_DEVICE_BONDING_REPORT :
                            {
                                if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing GET_NET_DEVICE_BONDING_REPORT, Thread="+toString());
                                int pkey = in.readCompressedInt();
                                if(daemonKey==null) throw new IOException("Only the master server may GET_NET_DEVICE_BONDING_REPORT");
                                NetDevice netDevice = connector.getNetDevices().get(pkey);
                                if(netDevice==null) throw new AssertionError("Unable to find NetDevice: "+pkey);
                                String report = NetDeviceManager.getNetDeviceBondingReport(netDevice);
                                out.write(AOServDaemonProtocol.DONE);
                                out.writeUTF(report);
                            }
                            break;
                        case AOServDaemonProtocol.GET_NET_DEVICE_STATISTICS_REPORT :
                            {
                                if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing GET_NET_DEVICE_STATISTICS_REPORT, Thread="+toString());
                                int pkey = in.readCompressedInt();
                                if(daemonKey==null) throw new IOException("Only the master server may GET_NET_DEVICE_STATISTICS_REPORT");
                                NetDevice netDevice = connector.getNetDevices().get(pkey);
                                if(netDevice==null) throw new AssertionError("Unable to find NetDevice: "+pkey);
                                String report = NetDeviceManager.getNetDeviceStatisticsReport(netDevice);
                                out.write(AOServDaemonProtocol.DONE);
                                out.writeUTF(report);
                            }
                            break;
                        case AOServDaemonProtocol.GET_3WARE_RAID_REPORT :
                            {
                                if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing GET_3WARE_RAID_REPORT, Thread="+toString());
                                if(daemonKey==null) throw new IOException("Only the master server may GET_3WARE_RAID_REPORT");
                                String report = ServerManager.get3wareRaidReport();
                                out.write(AOServDaemonProtocol.DONE);
                                out.writeUTF(report);
                            }
                            break;
                        case AOServDaemonProtocol.GET_MD_RAID_REPORT :
                            {
                                if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing GET_MD_RAID_REPORT, Thread="+toString());
                                if(daemonKey==null) throw new IOException("Only the master server may GET_MD_RAID_REPORT");
                                String report = ServerManager.getMdRaidReport();
                                out.write(AOServDaemonProtocol.DONE);
                                out.writeUTF(report);
                            }
                            break;
                        case AOServDaemonProtocol.GET_DRBD_REPORT :
                            {
                                if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing GET_DRBD_REPORT, Thread="+toString());
                                if(daemonKey==null) throw new IOException("Only the master server may GET_DRBD_REPORT");
                                String report = ServerManager.getDrbdReport();
                                out.write(AOServDaemonProtocol.DONE);
                                out.writeUTF(report);
                            }
                            break;
                        case AOServDaemonProtocol.GET_LVM_REPORT :
                            {
                                if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing GET_LVM_REPORT, Thread="+toString());
                                if(daemonKey==null) throw new IOException("Only the master server may GET_LVM_REPORT");
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
                                if(daemonKey==null) throw new IOException("Only the master server may GET_HDD_TEMP_REPORT");
                                String report = ServerManager.getHddTempReport();
                                out.write(AOServDaemonProtocol.DONE);
                                out.writeUTF(report);
                            }
                            break;
                        case AOServDaemonProtocol.GET_HDD_MODEL_REPORT :
                            {
                                if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing GET_HDD_MODEL_REPORT, Thread="+toString());
                                if(daemonKey==null) throw new IOException("Only the master server may GET_HDD_MODEL_REPORT");
                                String report = ServerManager.getHddModelReport();
                                out.write(AOServDaemonProtocol.DONE);
                                out.writeUTF(report);
                            }
                            break;
                        case AOServDaemonProtocol.GET_FILESYSTEMS_CSV_REPORT :
                            {
                                if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing GET_FILESYSTEMS_CSV_REPORT, Thread="+toString());
                                if(daemonKey==null) throw new IOException("Only the master server may GET_FILESYSTEMS_CSV_REPORT");
                                String report = ServerManager.getFilesystemsCsvReport();
                                out.write(AOServDaemonProtocol.DONE);
                                out.writeUTF(report);
                            }
                            break;
                        case AOServDaemonProtocol.GET_AO_SERVER_LOADAVG_REPORT :
                            {
                                if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing GET_AO_SERVER_LOADAVG_REPORT, Thread="+toString());
                                if(daemonKey==null) throw new IOException("Only the master server may GET_AO_SERVER_LOADAVG_REPORT");
                                String report = ServerManager.getLoadAvgReport();
                                out.write(AOServDaemonProtocol.DONE);
                                out.writeUTF(report);
                            }
                            break;
                        case AOServDaemonProtocol.GET_AO_SERVER_MEMINFO_REPORT :
                            {
                                if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing GET_AO_SERVER_MEMINFO_REPORT, Thread="+toString());
                                if(daemonKey==null) throw new IOException("Only the master server may GET_AO_SERVER_MEMINFO_REPORT");
                                String report = ServerManager.getMemInfoReport();
                                out.write(AOServDaemonProtocol.DONE);
                                out.writeUTF(report);
                            }
                            break;
                        case AOServDaemonProtocol.CHECK_PORT :
                            {
                                if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing CHECK_PORT, Thread="+toString());
                                if(daemonKey==null) throw new IOException("Only the master server may CHECK_PORT");
                                com.aoindustries.aoserv.client.validator.InetAddress ipAddress = com.aoindustries.aoserv.client.validator.InetAddress.valueOf(
                                    LongLong.valueOf(
                                        in.readLong(),
                                        in.readLong()
                                    )
                                );
                                int port = in.readCompressedInt();
                                String netProtocol = in.readUTF();
                                String appProtocol = in.readUTF();
                                String monitoringParameters = in.readUTF();
                                String result = PortMonitor.getPortMonitor(ipAddress, NetPort.valueOf(port), netProtocol, appProtocol, NetBind.decodeParameters(monitoringParameters)).checkPort();
                                out.write(AOServDaemonProtocol.DONE);
                                out.writeUTF(result);
                            }
                            break;
                        case AOServDaemonProtocol.CHECK_SMTP_BLACKLIST :
                            {
                                if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing CHECK_SMTP_BLACKLIST, Thread="+toString());
                                if(daemonKey==null) throw new IOException("Only the master server may CHECK_SMTP_BLACKLIST");
                                String sourceIp = in.readUTF();
                                String connectIp = in.readUTF();
                                String result = NetDeviceManager.checkSmtpBlacklist(sourceIp, connectIp);
                                out.write(AOServDaemonProtocol.DONE);
                                out.writeUTF(result);
                            }
                            break;
                        case AOServDaemonProtocol.GET_AO_SERVER_SYSTEM_TIME_MILLIS :
                            {
                                if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing GET_AO_SERVER_SYSTEM_TIME_MILLIS, Thread="+toString());
                                if(daemonKey==null) throw new IOException("Only the master server may GET_AO_SERVER_SYSTEM_TIME_MILLIS");
                                long currentTime = System.currentTimeMillis();
                                out.write(AOServDaemonProtocol.DONE);
                                out.writeLong(currentTime);
                            }
                            break;
                        case AOServDaemonProtocol.IS_PROCMAIL_MANUAL :
                            {
                                if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing IS_PROCMAIL_MANUAL, Thread="+toString());
                                int laPKey=in.readCompressedInt();
                                if(daemonKey==null) throw new IOException("Only the master server may IS_PROCMAIL_MANUAL");
                                LinuxAccount la=connector.getLinuxAccounts().get(laPKey);
                                if(la==null) throw new AssertionError("Unable to find LinuxAccount: "+laPKey);
                                boolean isManual=ProcmailManager.isManual(la);
                                out.write(AOServDaemonProtocol.DONE);
                                out.writeBoolean(isManual);
                            }
                            break;
                        case AOServDaemonProtocol.GET_DISK_DEVICE_TOTAL_SIZE :
                            {
                                if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing GET_DISK_DEVICE_TOTAL_SIZE, Thread="+toString());
                                String path=in.readUTF();
                                if(daemonKey==null) throw new IOException("Only the master server may GET_DISK_DEVICE_TOTAL_SIZE");
                                long bytes=BackupManager.getDiskDeviceTotalSize(path);
                                out.write(AOServDaemonProtocol.DONE);
                                out.writeLong(bytes);
                            }
                            break;
                        case AOServDaemonProtocol.GET_DISK_DEVICE_USED_SIZE :
                            {
                                if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing GET_DISK_DEVICE_USED_SIZE, Thread="+toString());
                                String path=in.readUTF();
                                if(daemonKey==null) throw new IOException("Only the master server may GET_DISK_DEVICE_USED_SIZE");
                                long bytes=BackupManager.getDiskDeviceUsedSize(path);
                                out.write(AOServDaemonProtocol.DONE);
                                out.writeLong(bytes);
                            }
                            break;
                        case AOServDaemonProtocol.GET_EMAIL_LIST_FILE :
                            {
                                if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing GET_EMAIL_LIST_FILE, Thread="+toString());
                                String path=in.readUTF();
                                if(daemonKey==null) throw new IOException("Only the master server may GET_EMAIL_LIST_FILE");
                                String file=EmailListManager.getEmailListFile(path);
                                out.write(AOServDaemonProtocol.DONE);
                                out.writeUTF(file);
                            }
                            break;
                        case AOServDaemonProtocol.GET_ENCRYPTED_LINUX_ACCOUNT_PASSWORD :
                            {
                                if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing GET_ENCRYPTED_LINUX_ACCOUNT_PASSWORD, Thread="+toString());
                                String username=in.readUTF();
                                if(daemonKey==null) throw new IOException("Only the master server may GET_ENCRYPTED_LINUX_ACCOUNT_PASSWORD");
                                String encryptedPassword=LinuxAccountManager.getEncryptedPassword(UserId.valueOf(username));
                                out.write(AOServDaemonProtocol.DONE);
                                out.writeUTF(encryptedPassword);
                            }
                            break;
                        case AOServDaemonProtocol.GET_ENCRYPTED_MYSQL_USER_PASSWORD :
                            {
                                if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing GET_ENCRYPTED_MYSQL_USER_PASSWORD, Thread="+toString());
                                int pkey=in.readCompressedInt();
                                String username=in.readUTF();
                                if(daemonKey==null) throw new IOException("Only the master server may GET_ENCRYPTED_MYSQL_USER_PASSWORD");
                                MySQLServer mysqlServer=connector.getMysqlServers().get(pkey);
                                if(mysqlServer==null) throw new AssertionError("Unable to find MySQLServer: "+pkey);
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
                                if(daemonKey==null) throw new IOException("Only the master server may GET_IMAP_FOLDER_SIZES");
                                long[] sizes=ImapManager.getImapFolderSizes(UserId.valueOf(username), folderNames);
                                out.write(AOServDaemonProtocol.DONE);
                                for(int c=0;c<numFolders;c++) out.writeLong(sizes[c]);
                            }
                            break;
                        case AOServDaemonProtocol.GET_INBOX_ATTRIBUTES :
                            {
                                if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing GET_INBOX_ATTRIBUTES, Thread="+toString());
                                String username=in.readUTF();
                                if(daemonKey==null) throw new IOException("Only the master server may GET_INBOX_ATTRIBUTES");
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
                                if(daemonKey==null) throw new IOException("Only the master server may GET_MRTG_FILE");
                                MrtgManager.getMrtgFile(filename, out);
                                out.write(AOServDaemonProtocol.DONE);
                            }
                            break;
                        case AOServDaemonProtocol.GET_MYSQL_MASTER_STATUS :
                            {
                                if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing GET_MYSQL_MASTER_STATUS, Thread="+toString());
                                int mysqlServer = in.readCompressedInt();
                                if(daemonKey==null) throw new IOException("Only the master server may GET_MYSQL_MASTER_STATUS");
                                MySQLDatabaseManager.getMasterStatus(mysqlServer, out);
                            }
                            break;
                        case AOServDaemonProtocol.GET_MYSQL_SLAVE_STATUS :
                            {
                                if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing GET_MYSQL_SLAVE_STATUS, Thread="+toString());
                                String failoverRoot = in.readUTF();
                                int nestedOperatingSystemVersion = in.readCompressedInt();
                                int port = in.readCompressedInt();
                                if(daemonKey==null) throw new IOException("Only the master server may GET_MYSQL_SLAVE_STATUS");
                                MySQLDatabaseManager.getSlaveStatus(UnixPath.valueOf(failoverRoot), nestedOperatingSystemVersion, NetPort.valueOf(port), out);
                            }
                            break;
                        case AOServDaemonProtocol.GET_MYSQL_TABLE_STATUS :
                            {
                                if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing GET_MYSQL_TABLE_STATUS, Thread="+toString());
                                String failoverRoot = in.readUTF();
                                int nestedOperatingSystemVersion = in.readCompressedInt();
                                int port = in.readCompressedInt();
                                String databaseName = in.readUTF();
                                if(daemonKey==null) throw new IOException("Only the master server may GET_MYSQL_TABLE_STATUS");
                                MySQLDatabaseManager.getTableStatus(UnixPath.valueOf(failoverRoot), nestedOperatingSystemVersion, NetPort.valueOf(port), MySQLDatabaseName.valueOf(databaseName), out);
                            }
                            break;
                        case AOServDaemonProtocol.CHECK_MYSQL_TABLES :
                            {
                                if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing CHECK_MYSQL_TABLES, Thread="+toString());
                                String failoverRoot = in.readUTF();
                                int nestedOperatingSystemVersion = in.readCompressedInt();
                                int port = in.readCompressedInt();
                                String databaseName = in.readUTF();
                                int numTables = in.readCompressedInt();
                                List<String> tableNames = new ArrayList<String>(numTables);
                                for(int c=0;c<numTables;c++) {
                                    tableNames.add(in.readUTF());
                                }
                                if(daemonKey==null) throw new IOException("Only the master server may CHECK_MYSQL_TABLES");
                                List<MySQLTableName> mysqlTableNames = new ArrayList<MySQLTableName>(tableNames.size());
                                for(String tableName : tableNames) mysqlTableNames.add(MySQLTableName.valueOf(tableName));
                                MySQLDatabaseManager.checkTables(UnixPath.valueOf(failoverRoot), nestedOperatingSystemVersion, NetPort.valueOf(port), MySQLDatabaseName.valueOf(databaseName), mysqlTableNames, out);
                            }
                            break;
                        case AOServDaemonProtocol.GET_POSTGRES_PASSWORD :
                            {
                                if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing GET_POSTGRES_PASSWORD, Thread="+toString());
                                int pkey=in.readCompressedInt();
                                if(daemonKey==null) throw new IOException("Only the master server may GET_POSTGRES_PASSWORD");
                                PostgresUser pu=connector.getPostgresUsers().get(pkey);
                                if(pu==null) throw new AssertionError("Unable to find PostgresUser: "+pkey);
                                String password=PostgresUserManager.getPassword(pu);
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
                                if(daemonKey==null) throw new IOException("Only the master server may GRANT_DAEMON_ACCESS");
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
                                if(daemonKey==null) throw new IOException("Only the master server may REMOVE_EMAIL_LIST");
                                EmailListManager.removeEmailListAddresses(path);
                                out.write(AOServDaemonProtocol.DONE);
                            }
                            break;
                        case AOServDaemonProtocol.RESTART_APACHE :
                            {
                                if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing RESTART_APACHE, Thread="+toString());
                                if(daemonKey==null) throw new IOException("Only the master server may RESTART_APACHE");
                                HttpdServerManager.restartApache();
                                out.write(AOServDaemonProtocol.DONE);
                            }
                            break;
                        case AOServDaemonProtocol.RESTART_CRON :
                            if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing RESTART_CRON, Thread="+toString());
                            if(daemonKey==null) throw new IOException("Only the master server may RESTART_CRON");
                            ServerManager.restartCron();
                            out.write(AOServDaemonProtocol.DONE);
                            break;
                        case AOServDaemonProtocol.RESTART_MYSQL :
                            {
                                if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing RESTART_MYSQL, Thread="+toString());
                                int pkey=in.readCompressedInt();
                                if(daemonKey==null) throw new IOException("Only the master server may RESTART_MYSQL");
                                MySQLServer mysqlServer=connector.getMysqlServers().get(pkey);
                                if(mysqlServer==null) throw new AssertionError("Unable to find MySQLServer: "+pkey);
                                MySQLServerManager.restartMySQL(mysqlServer);
                                out.write(AOServDaemonProtocol.DONE);
                            }
                            break;
                        case AOServDaemonProtocol.RESTART_POSTGRES :
                            {
                                if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing RESTART_POSTGRES, Thread="+toString());
                                int pkey=in.readCompressedInt();
                                if(daemonKey==null) throw new IOException("Only the master server may RESTART_POSTGRES");
                                PostgresServer ps=connector.getPostgresServers().get(pkey);
                                if(ps==null) throw new AssertionError("Unable to find PostgresServer: "+pkey);
                                PostgresServerManager.restartPostgreSQL(ps);
                                out.write(AOServDaemonProtocol.DONE);
                            }
                            break;
                        case AOServDaemonProtocol.RESTART_XFS :
                            if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing RESTART_XFS, Thread="+toString());
                            if(daemonKey==null) throw new IOException("Only the master server may RESTART_XFS");
                            ServerManager.restartXfs();
                            out.write(AOServDaemonProtocol.DONE);
                            break;
                        case AOServDaemonProtocol.RESTART_XVFB :
                            if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing RESTART_XVFB, Thread="+toString());
                            if(daemonKey==null) throw new IOException("Only the master server may RESTART_XVFB");
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
                                if(daemonKey==null) throw new IOException("Only the master server may SET_AUTORESPONDER_CONTENT");
                                LinuxAccountManager.setAutoresponderContent(
                                    UnixPath.valueOf(path),
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
                                if(daemonKey==null) throw new IOException("Only the master server may SET_CRON_TABLE");
                                LinuxAccountManager.setCronTable(UserId.valueOf(username), cronTable);
                                out.write(AOServDaemonProtocol.DONE);
                            }
                            break;
                        case AOServDaemonProtocol.SET_ENCRYPTED_LINUX_ACCOUNT_PASSWORD :
                            {
                                if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing SET_ENCRYPTED_LINUX_ACCOUNT_PASSWORD, Thread="+toString());
                                String username=in.readUTF();
                                String encryptedPassword=in.readUTF();
                                if(daemonKey==null) throw new IOException("Only the master server may SET_ENCRYPTED_LINUX_ACCOUNT_PASSWORD");
                                LinuxAccountManager.setEncryptedPassword(UserId.valueOf(username), encryptedPassword);
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
                                if(daemonKey==null) throw new IOException("Only the master server may SET_EMAIL_LIST_FILE");
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
                        case AOServDaemonProtocol.SET_LINUX_SERVER_ACCOUNT_PASSWORD :
                            {
                                if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing SET_LINUX_SERVER_ACCOUNT_PASSWORD, Thread="+toString());
                                String username=in.readUTF();
                                String plainPassword=in.readUTF();
                                if(daemonKey==null) throw new IOException("Only the master server may SET_LINUX_SERVER_ACCOUNT_PASSWORD");
                                LinuxAccountManager.setPassword(UserId.valueOf(username), plainPassword);
                                out.write(AOServDaemonProtocol.DONE);
                            }
                            break;
                        case AOServDaemonProtocol.SET_MYSQL_USER_PASSWORD :
                            {
                                if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing SET_MYSQL_USER_PASSWORD, Thread="+toString());
                                int mysqlServerPKey=in.readCompressedInt();
                                String username=in.readUTF();
                                String password=in.readBoolean()?in.readUTF():null;
                                if(daemonKey==null) throw new IOException("Only the master server may SET_MYSQL_USER_PASSWORD");
                                MySQLServer mysqlServer=connector.getMysqlServers().get(mysqlServerPKey);
                                if(mysqlServer==null) throw new AssertionError("Unable to find MySQLServer: "+mysqlServerPKey);
                                MySQLUserManager.setPassword(mysqlServer, username, password);
                                out.write(AOServDaemonProtocol.DONE);
                            }
                            break;
                        case AOServDaemonProtocol.SET_POSTGRES_USER_PASSWORD :
                            {
                                if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing SET_POSTGRES_USER_PASSWORD, Thread="+toString());
                                int pkey=in.readCompressedInt();
                                String password=in.readBoolean()?in.readUTF():null;
                                PostgresUser pu=connector.getPostgresUsers().get(pkey);
                                if(pu==null) throw new AssertionError("Unable to find PostgresUser: "+pkey);
                                PostgresUserManager.setPassword(pu, password, false);
                                out.write(AOServDaemonProtocol.DONE);
                            }
                            break;
                        case AOServDaemonProtocol.START_APACHE :
                            if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing START_APACHE, Thread="+toString());
                            if(daemonKey==null) throw new IOException("Only the master server may START_APACHE");
                            HttpdServerManager.startApache();
                            out.write(AOServDaemonProtocol.DONE);
                            break;
                        case AOServDaemonProtocol.START_CRON :
                            if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing START_CRON, Thread="+toString());
                            if(daemonKey==null) throw new IOException("Only the master server may START_CRON");
                            ServerManager.startCron();
                            out.write(AOServDaemonProtocol.DONE);
                            break;
                        case AOServDaemonProtocol.START_DISTRO :
                            {
                                if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing START_DISTRO, Thread="+toString());
                                boolean includeUser=in.readBoolean();
                                if(daemonKey==null) throw new IOException("Only the master server may START_DISTRO");
                                DistroManager.startDistro(includeUser);
                                out.write(AOServDaemonProtocol.DONE);
                            }
                            break;
                        case AOServDaemonProtocol.START_JVM :
                            {
                                if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing START_JVM, Thread="+toString());
                                int sitePKey=in.readCompressedInt();
                                if(daemonKey==null) throw new IOException("Only the master server may START_JVM");
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
                                if(daemonKey==null) throw new IOException("Only the master server may START_MYSQL");
                                MySQLServer mysqlServer=connector.getMysqlServers().get(pkey);
                                if(mysqlServer==null) throw new AssertionError("Unable to find MySQLServer: "+pkey);
                                MySQLServerManager.startMySQL(mysqlServer);
                                out.write(AOServDaemonProtocol.DONE);
                            }
                            break;
                        case AOServDaemonProtocol.START_POSTGRESQL :
                            {
                                if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing START_POSTGRESQL, Thread="+toString());
                                int pkey=in.readCompressedInt();
                                if(daemonKey==null) throw new IOException("Only the master server may START_POSTGRESQL");
                                PostgresServer ps=connector.getPostgresServers().get(pkey);
                                if(ps==null) throw new AssertionError("Unable to find PostgresServer: "+pkey);
                                PostgresServerManager.startPostgreSQL(ps);
                                out.write(AOServDaemonProtocol.DONE);
                            }
                            break;
                        case AOServDaemonProtocol.START_XFS :
                            if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing START_XFS, Thread="+toString());
                            if(daemonKey==null) throw new IOException("Only the master server may START_XFS");
                            ServerManager.startXfs();
                            out.write(AOServDaemonProtocol.DONE);
                            break;
                        case AOServDaemonProtocol.START_XVFB :
                            if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing START_XVFB, Thread="+toString());
                            if(daemonKey==null) throw new IOException("Only the master server may START_XVFB");
                            ServerManager.startXvfb();
                            out.write(AOServDaemonProtocol.DONE);
                            break;
                        case AOServDaemonProtocol.STOP_APACHE :
                            if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing STOP_APACHE, Thread="+toString());
                            if(daemonKey==null) throw new IOException("Only the master server may STOP_APACHE");
                            HttpdServerManager.stopApache();
                            out.write(AOServDaemonProtocol.DONE);
                            break;
                        case AOServDaemonProtocol.STOP_CRON :
                            if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing STOP_CRON, Thread="+toString());
                            if(daemonKey==null) throw new IOException("Only the master server may STOP_CRON");
                            ServerManager.stopCron();
                            out.write(AOServDaemonProtocol.DONE);
                            break;
                        case AOServDaemonProtocol.STOP_JVM :
                            {
                                if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing STOP_JVM, Thread="+toString());
                                int sitePKey=in.readCompressedInt();
                                if(daemonKey==null) throw new IOException("Only the master server may STOP_JVM");
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
                                if(daemonKey==null) throw new IOException("Only the master server may STOP_MYSQL");
                                MySQLServer mysqlServer=connector.getMysqlServers().get(pkey);
                                if(mysqlServer==null) throw new AssertionError("Unable to find MySQLServer: "+pkey);
                                MySQLServerManager.stopMySQL(mysqlServer);
                                out.write(AOServDaemonProtocol.DONE);
                            }
                            break;
                        case AOServDaemonProtocol.STOP_POSTGRESQL :
                            {
                                if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing STOP_POSTGRESQL, Thread="+toString());
                                int pkey=in.readCompressedInt();
                                if(daemonKey==null) throw new IOException("Only the master server may STOP_POSTGRESQL");
                                PostgresServer ps=connector.getPostgresServers().get(pkey);
                                if(ps==null) throw new AssertionError("Unable to find PostgresServer: "+pkey);
                                PostgresServerManager.stopPostgreSQL(ps);
                                out.write(AOServDaemonProtocol.DONE);
                            }
                            break;
                        case AOServDaemonProtocol.STOP_XFS :
                            if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing STOP_XFS, Thread="+toString());
                            if(daemonKey==null) throw new IOException("Only the master server may STOP_XFS");
                            ServerManager.stopXfs();
                            out.write(AOServDaemonProtocol.DONE);
                            break;
                        case AOServDaemonProtocol.STOP_XVFB :
                            if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing STOP_XVFB, Thread="+toString());
                            if(daemonKey==null) throw new IOException("Only the master server may STOP_XVFB");
                            ServerManager.stopXvfb();
                            out.write(AOServDaemonProtocol.DONE);
                            break;
                        case AOServDaemonProtocol.TAR_HOME_DIRECTORY :
                            {
                                if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing TAR_HOME_DIRECTORY, Thread="+toString());
                                String username=in.readUTF();
                                if(daemonKey==null) throw new IOException("Only the master server may TAR_HOME_DIRECTORY");
                                LinuxAccountManager.tarHomeDirectory(out, UserId.valueOf(username));
                                out.write(AOServDaemonProtocol.DONE);
                            }
                            break;
                        case AOServDaemonProtocol.UNTAR_HOME_DIRECTORY :
                            {
                                if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing UNTAR_HOME_DIRECTORY, Thread="+toString());
                                String username=in.readUTF();
                                if(daemonKey==null) throw new IOException("Only the master server may UNTAR_HOME_DIRECTORY");
                                LinuxAccountManager.untarHomeDirectory(in, UserId.valueOf(username));
                                out.write(AOServDaemonProtocol.DONE);
                            }
                            break;
                        case AOServDaemonProtocol.WAIT_FOR_REBUILD :
                            {
                                if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing WAIT_FOR_REBUILD, Thread="+toString());
                                ServiceName serviceName=ServiceName.valueOf(in.readUTF());
                                if(daemonKey==null) throw new IOException("Only the master server may WAIT_FOR_REBUILD");
                                switch(serviceName) {
                                    case httpd_sites :
                                        HttpdManager.waitForRebuild();
                                        break;
                                    case linux_accounts :
                                        LinuxAccountManager.waitForRebuild();
                                        break;
                                    case mysql_databases :
                                        MySQLDatabaseManager.waitForRebuild();
                                        break;
                                    case mysql_db_users :
                                        MySQLDBUserManager.waitForRebuild();
                                        break;
                                    case mysql_users :
                                        MySQLUserManager.waitForRebuild();
                                        break;
                                    case postgres_databases :
                                        PostgresDatabaseManager.waitForRebuild();
                                        break;
                                    case postgres_servers :
                                        MySQLServerManager.waitForRebuild();
                                        break;
                                    case postgres_users :
                                        PostgresUserManager.waitForRebuild();
                                        break;
                                    default :
                                        throw new IOException("Unable to wait for rebuild on service "+serviceName);
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
                    ) {
                        LogFactory.getLogger(AOServDaemonServerThread.class).log(Level.SEVERE, null, err);
                    }
                    out.write(AOServDaemonProtocol.REMOTE_EXCEPTION);
                    out.writeUTF(message == null ? "null" : message);
                } catch (RuntimeException err) {
                    LogFactory.getLogger(AOServDaemonServerThread.class).log(Level.SEVERE, null, err);
                    out.write(AOServDaemonProtocol.REMOTE_EXCEPTION);
                    String message = err.getMessage();
                    out.writeUTF(message == null ? "null" : message);
                }
                out.flush();
            }
        } catch(EOFException err) {
            // Normal for abrupt connection closing
        } catch(SSLHandshakeException err) {
            String message = err.getMessage();
            if(!"Remote host closed connection during handshake".equals(message)) LogFactory.getLogger(AOServDaemonServerThread.class).log(Level.SEVERE, null, err);
        } catch(ThreadDeath TD) {
            throw TD;
        } catch(SocketException err) {
            String message=err.getMessage();
            if(
                !"Socket closed".equals(message)
                && !"Socket is closed".equals(message)
            ) {
                LogFactory.getLogger(AOServDaemonServerThread.class).log(Level.SEVERE, null, err);
            }
        } catch(Throwable T) {
            LogFactory.getLogger(AOServDaemonServerThread.class).log(Level.SEVERE, null, T);
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