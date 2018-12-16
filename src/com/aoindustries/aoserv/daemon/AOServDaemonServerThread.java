/*
 * Copyright 2000-2013, 2014, 2015, 2017, 2018 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.aoserv.daemon;

import com.aoindustries.aoserv.client.AOServConnector;
import com.aoindustries.aoserv.client.linux.DaemonAcl;
import com.aoindustries.aoserv.client.linux.Server;
import com.aoindustries.aoserv.client.linux.UserServer;
import com.aoindustries.aoserv.client.mysql.Database;
import com.aoindustries.aoserv.client.net.Bind;
import com.aoindustries.aoserv.client.net.Device;
import com.aoindustries.aoserv.client.pki.Certificate;
import com.aoindustries.aoserv.client.validator.HashedPassword;
import com.aoindustries.aoserv.client.validator.MySQLDatabaseName;
import com.aoindustries.aoserv.client.validator.MySQLServerName;
import com.aoindustries.aoserv.client.validator.MySQLTableName;
import com.aoindustries.aoserv.client.validator.MySQLUserId;
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
import com.aoindustries.aoserv.daemon.server.PhysicalServerManager;
import com.aoindustries.aoserv.daemon.server.ServerManager;
import com.aoindustries.aoserv.daemon.server.VirtualServerManager;
import com.aoindustries.aoserv.daemon.ssl.SslCertificateManager;
import com.aoindustries.aoserv.daemon.unix.linux.LinuxAccountManager;
import com.aoindustries.io.CompressedDataInputStream;
import com.aoindustries.io.CompressedDataOutputStream;
import com.aoindustries.net.Port;
import com.aoindustries.net.Protocol;
import com.aoindustries.noc.monitor.portmon.PortMonitor;
import com.aoindustries.util.Tuple2;
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
import java.util.Locale;
import java.util.logging.Level;
import javax.net.ssl.SSLHandshakeException;

/**
 * The <code>AOServServerThread</code> handles a connection once it is accepted.
 *
 * @author  AO Industries, Inc.
 */
final public class AOServDaemonServerThread extends Thread {

	/**
	 * The set of supported versions, with the most preferred versions first.
	 */
	private static final AOServDaemonProtocol.Version[] SUPPORTED_VERSIONS = {
		AOServDaemonProtocol.Version.VERSION_1_81_10,
		AOServDaemonProtocol.Version.VERSION_1_80_1,
		AOServDaemonProtocol.Version.VERSION_1_80_0,
		AOServDaemonProtocol.Version.VERSION_1_77
	};

	/**
	 * The <code>AOServServer</code> that created this <code>AOServServerThread</code>.
	 */
	//private final AOServDaemonServer server;

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
		setName("AOServ Daemon Host Thread #" + getId() + " - " + socket.getInetAddress().getHostAddress());
		//this.server = server;
		this.socket = socket;
		this.in = new CompressedDataInputStream(new BufferedInputStream(socket.getInputStream()));
		this.out = new CompressedDataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
		this.out.flush();
	}

	@Override
	public void run() {
		try {
			final AOServConnector connector = AOServDaemon.getConnector();
			final Server thisAOServer = AOServDaemon.getThisAOServer();

			final AOServDaemonProtocol.Version protocolVersion;
			final String daemonKey;
			{
				// Write the most preferred version
				String preferredVersion = in.readUTF();
				// Then connector key
				daemonKey = in.readNullUTF();
				// Now additional versions.
				String[] versions;
				if(preferredVersion.equals(AOServDaemonProtocol.Version.VERSION_1_77.getVersion())) {
					// Client 1.77 only sends the single preferred version
					versions = new String[] {preferredVersion};
				} else {
					versions = new String[1 + in.readCompressedInt()];
					versions[0] = preferredVersion;
					for(int i = 1; i < versions.length; i++) {
						versions[i] = in.readUTF();
					}
				}
				// Select the first supported version
				AOServDaemonProtocol.Version selectedVersion = null;
				SELECT_VERSION :
				for(String version : versions) {
					for(AOServDaemonProtocol.Version supportedVersion : SUPPORTED_VERSIONS) {
						if(supportedVersion.getVersion().equals(version)) {
							selectedVersion = supportedVersion;
							break SELECT_VERSION;
						}
					}
				}
				if(selectedVersion == null) {
					out.writeBoolean(false);
					out.writeUTF(SUPPORTED_VERSIONS[0].getVersion());
					if(!preferredVersion.equals(AOServDaemonProtocol.Version.VERSION_1_77.getVersion())) {
						// Client 1.77 only expects the single preferred version
						out.writeCompressedInt(SUPPORTED_VERSIONS.length - 1);
						for(int i = 1; i < SUPPORTED_VERSIONS.length; i++) {
							out.writeUTF(SUPPORTED_VERSIONS[i].getVersion());
						}
					}
					out.flush();
					return;
				}
				out.writeBoolean(true);
				protocolVersion = selectedVersion;
			}
			if(daemonKey!=null) {
				// Must come from one of the hosts listed in the database
				String hostAddress = socket.getInetAddress().getHostAddress();
				boolean isOK=false;
				for(DaemonAcl allowedHost : thisAOServer.getAOServerDaemonHosts()) {
					String tempAddress = InetAddress.getByName(allowedHost.getHost().toString()).getHostAddress();
					if (tempAddress.equals(hostAddress)) {
						isOK=true;
						break;
					}
				}
				if(isOK) {
					// Authenticate the client first
					HashedPassword correctKey=thisAOServer.getDaemonKey();
					if(!correctKey.passwordMatches(daemonKey)) {
						System.err.println("Connection attempted from " + hostAddress + " with invalid key: " + daemonKey);
						out.writeBoolean(false);
						out.flush();
						return;
					}
				} else {
					LogFactory.getLogger(AOServDaemonServerThread.class).log(Level.WARNING, "Connection attempted from " + hostAddress + " but not listed in server_daemon_hosts");
					out.writeBoolean(false);
					out.flush();
					return;
				}
			}
			out.writeBoolean(true);
			// Command sequence starts at a random value
			final long startSeq;
			if(protocolVersion.compareTo(AOServDaemonProtocol.Version.VERSION_1_80_0) >= 0) {
				out.writeUTF(protocolVersion.getVersion());
				startSeq = AOServDaemon.getRandom().nextLong();
				out.writeLong(startSeq);
			} else {
				startSeq = 0;
			}
			out.flush();

			long seq = startSeq;
		Loop:
			while(true) {
				// Verify client sends matching sequence
				if(protocolVersion.compareTo(AOServDaemonProtocol.Version.VERSION_1_80_0) >= 0) {
					long clientSeq = in.readLong();
					if(clientSeq != seq) throw new IOException("Sequence mismatch: " + clientSeq + " != " + seq);
				}
				// Send command sequence
				if(protocolVersion.compareTo(AOServDaemonProtocol.Version.VERSION_1_80_1) >= 0) {
					out.writeLong(seq); // out is buffered, so no I/O created by writing this early
				}
				// Increment sequence
				seq++;
				// Continue with task
				int taskCode = in.readCompressedInt();
				if(taskCode == AOServDaemonProtocol.QUIT) break Loop;
				boolean logIOException = true;
				try {
					switch (taskCode) {
						case AOServDaemonProtocol.COMPARE_LINUX_ACCOUNT_PASSWORD :
							{
								if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing COMPARE_LINUX_ACCOUNT_PASSWORD, Thread="+toString());
								UserId username=UserId.valueOf(in.readUTF());
								String password=in.readUTF();
								if(daemonKey==null) throw new IOException("Only the master server may COMPARE_LINUX_ACCOUNT_PASSWORD");
								boolean matches=LinuxAccountManager.comparePassword(username, password);
								out.write(AOServDaemonProtocol.DONE);
								out.writeBoolean(matches);
							}
							break;
						case AOServDaemonProtocol.DUMP_MYSQL_DATABASE :
							{
								if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing DUMP_MYSQL_DATABASE, Thread="+toString());
								int pkey=in.readCompressedInt();
								boolean gzip;
								if(protocolVersion.compareTo(AOServDaemonProtocol.Version.VERSION_1_80_0) >= 0) {
									gzip = in.readBoolean();
								} else {
									gzip = false;
								}
								if(daemonKey==null) throw new IOException("Only the master server may DUMP_MYSQL_DATABASE");
								Database md=connector.getMysql().getMysqlDatabases().get(pkey);
								if(md==null) throw new SQLException("Unable to find Database: "+pkey);
								MySQLDatabaseManager.dumpDatabase(md, protocolVersion, out, gzip);
								out.write(AOServDaemonProtocol.DONE);
							}
							break;
						case AOServDaemonProtocol.DUMP_POSTGRES_DATABASE :
							{
								if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing DUMP_POSTGRES_DATABASE, Thread="+toString());
								int pkey=in.readCompressedInt();
								boolean gzip;
								if(protocolVersion.compareTo(AOServDaemonProtocol.Version.VERSION_1_80_0) >= 0) {
									gzip = in.readBoolean();
								} else {
									gzip = false;
								}
								if(daemonKey==null) throw new IOException("Only the master server may DUMP_POSTGRES_DATABASE");
								com.aoindustries.aoserv.client.postgresql.Database pd=connector.getPostgresql().getPostgresDatabases().get(pkey);
								if(pd==null) throw new SQLException("Unable to find Database: "+pkey);
								PostgresDatabaseManager.dumpDatabase(pd, protocolVersion, out, gzip);
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
								List<MySQLServerName> replicatedMySQLServers;
								List<String> replicatedMySQLMinorVersions;
								if(retention==1) {
									int len=in.readCompressedInt();
									replicatedMySQLServers=new ArrayList<>(len);
									replicatedMySQLMinorVersions=new ArrayList<>(len);
									for(int c=0;c<len;c++) {
										replicatedMySQLServers.add(MySQLServerName.valueOf(in.readUTF()));
										replicatedMySQLMinorVersions.add(in.readUTF());
									}
								} else {
									replicatedMySQLServers=Collections.emptyList();
									replicatedMySQLMinorVersions=Collections.emptyList();
								}
								DaemonAccessEntry dae=AOServDaemonServer.getDaemonAccessEntry(daemonAccessKey);
								if(dae.command != AOServDaemonProtocol.FAILOVER_FILE_REPLICATION) throw new IOException("Mismatched DaemonAccessEntry command, dae.command!="+AOServDaemonProtocol.FAILOVER_FILE_REPLICATION);
								FailoverFileReplicationManager.failoverServer(
									socket,
									in,
									out,
									Integer.parseInt(dae.param1), // failover_file_replication.pkey
									dae.param2, // fromServer
									useCompression,
									retention,
									dae.param3, // backupPartition
									fromServerYear,
									fromServerMonth,
									fromServerDay,
									replicatedMySQLServers,
									replicatedMySQLMinorVersions,
									dae.param4==null ? -1 : Integer.parseInt(dae.param4) // quota_gid
								);
							}
							break;
						case AOServDaemonProtocol.GET_AUTORESPONDER_CONTENT :
							{
								if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing GET_AUTORESPONDER_CONTENT, Thread="+toString());
								UnixPath path = UnixPath.valueOf(in.readUTF());
								if(daemonKey==null) throw new IOException("Only the master server may GET_AUTORESPONDER_CONTENT");
								String content=LinuxAccountManager.getAutoresponderContent(path);
								out.write(AOServDaemonProtocol.DONE);
								out.writeUTF(content);
							}
							break;
						case AOServDaemonProtocol.GET_AWSTATS_FILE :
							{
								if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing GET_AWSTATS_FILE, Thread="+toString());
								String siteName=in.readUTF();
								String path = in.readUTF();
								String queryString=in.readUTF();
								if(daemonKey==null) throw new IOException("Only the master server may GET_AWSTATS_FILE");
								AWStatsManager.getAWStatsFile(siteName, path, queryString, out);
								out.write(AOServDaemonProtocol.DONE);
							}
							break;
						case AOServDaemonProtocol.GET_CRON_TABLE :
							{
								if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing GET_CRON_TABLE, Thread="+toString());
								UserId username = UserId.valueOf(in.readUTF());
								if(daemonKey==null) throw new IOException("Only the master server may GET_CRON_TABLE");
								String cronTable=LinuxAccountManager.getCronTable(username);
								out.write(AOServDaemonProtocol.DONE);
								out.writeUTF(cronTable);
							}
							break;
						case AOServDaemonProtocol.GET_FAILOVER_FILE_REPLICATION_ACTIVITY :
							{
								if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing GET_FAILOVER_FILE_REPLICATION_ACTIVITY, Thread="+toString());
								int replication = in.readCompressedInt();
								if(daemonKey==null) throw new IOException("Only the master server may GET_FAILOVER_FILE_REPLICATION_ACTIVITY");
								FailoverFileReplicationManager.Activity activity = FailoverFileReplicationManager.getActivity(replication);
								long timeSince;
								String message;
								synchronized(activity) {
									long time = activity.getTime();
									if(time == -1) {
										timeSince = -1;
										message = "";
									} else {
										timeSince = System.currentTimeMillis() - time;
										if(timeSince < 0) timeSince = 0;
										message = activity.getMessage();
									}
								}
								out.write(AOServDaemonProtocol.DONE);
								out.writeLong(timeSince);
								out.writeUTF(message);
							}
							break;
						case AOServDaemonProtocol.GET_NET_DEVICE_BONDING_REPORT :
							{
								if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing GET_NET_DEVICE_BONDING_REPORT, Thread="+toString());
								int pkey = in.readCompressedInt();
								if(daemonKey==null) throw new IOException("Only the master server may GET_NET_DEVICE_BONDING_REPORT");
								Device netDevice = connector.getNet().getNetDevices().get(pkey);
								if(netDevice==null) throw new SQLException("Unable to find Device: "+pkey);
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
								Device netDevice = connector.getNet().getNetDevices().get(pkey);
								if(netDevice==null) throw new SQLException("Unable to find Device: "+pkey);
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
						case AOServDaemonProtocol.GET_MD_STAT_REPORT :
							{
								if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing GET_MD_RAID_REPORT, Thread="+toString());
								if(daemonKey==null) throw new IOException("Only the master server may GET_MD_RAID_REPORT");
								String report = ServerManager.getMdStatReport();
								out.write(AOServDaemonProtocol.DONE);
								out.writeUTF(report);
							}
							break;
						case AOServDaemonProtocol.GET_MD_MISMATCH_REPORT :
							{
								if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing GET_MD_STAT_REPORT, Thread="+toString());
								if(daemonKey==null) throw new IOException("Only the master server may GET_MD_STAT_REPORT");
								String report = ServerManager.getMdMismatchReport();
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
								com.aoindustries.net.InetAddress ipAddress = com.aoindustries.net.InetAddress.valueOf(in.readUTF());
								Port port;
								{
									int portNum = in.readCompressedInt();
									Protocol protocol;
									if(protocolVersion.compareTo(AOServDaemonProtocol.Version.VERSION_1_80_0) < 0) {
										// Old protocol transferred lowercase
										protocol = Protocol.valueOf(in.readUTF().toUpperCase(Locale.ROOT));
									} else {
										protocol = in.readEnum(Protocol.class);
									}
									port = Port.valueOf(portNum, protocol);
								}
								String appProtocol = in.readUTF();
								String monitoringParameters = in.readUTF();
								PortMonitor portMonitor = PortMonitor.getPortMonitor(
									ipAddress,
									port,
									appProtocol,
									Bind.decodeParameters(monitoringParameters)
								);
								logIOException = false;
								String result = portMonitor.checkPort();
								logIOException = true;
								out.write(AOServDaemonProtocol.DONE);
								out.writeUTF(result);
							}
							break;
						case AOServDaemonProtocol.CHECK_SMTP_BLACKLIST :
							{
								if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing CHECK_SMTP_BLACKLIST, Thread="+toString());
								if(daemonKey==null) throw new IOException("Only the master server may CHECK_SMTP_BLACKLIST");
								com.aoindustries.net.InetAddress sourceIp = com.aoindustries.net.InetAddress.valueOf(in.readUTF());
								com.aoindustries.net.InetAddress connectIp = com.aoindustries.net.InetAddress.valueOf(in.readUTF());
								String result = NetDeviceManager.checkSmtpBlacklist(sourceIp, connectIp);
								out.write(AOServDaemonProtocol.DONE);
								out.writeUTF(result);
							}
							break;
						case AOServDaemonProtocol.CHECK_SSL_CERTIFICATE :
							{
								if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing CHECK_SSL_CERTIFICATE, Thread="+toString());
								int pkey = in.readCompressedInt();
								if(daemonKey==null) throw new IOException("Only the master server may CHECK_SSL_CERTIFICATE");
								Certificate certificate = connector.getPki().getSslCertificates().get(pkey);
								if(certificate == null) throw new SQLException("Unable to find Certificate: " + pkey);
								List<Certificate.Check> results = SslCertificateManager.checkSslCertificate(certificate);
								out.write(AOServDaemonProtocol.NEXT);
								int size = results.size();
								out.writeCompressedInt(size);
								for(int i = 0; i < size; i++) {
									Certificate.Check check = results.get(i);
									out.writeUTF(check.getCheck());
									out.writeUTF(check.getValue());
									out.writeUTF(check.getAlertLevel().name());
									out.writeNullUTF(check.getMessage());
								}
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
								int lsaPKey=in.readCompressedInt();
								if(daemonKey==null) throw new IOException("Only the master server may IS_PROCMAIL_MANUAL");
								UserServer lsa=connector.getLinux().getLinuxServerAccounts().get(lsaPKey);
								if(lsa==null) throw new SQLException("Unable to find UserServer: "+lsaPKey);
								boolean isManual=ProcmailManager.isManual(lsa);
								out.write(AOServDaemonProtocol.DONE);
								out.writeBoolean(isManual);
							}
							break;
						case AOServDaemonProtocol.GET_DISK_DEVICE_TOTAL_SIZE :
							{
								if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing GET_DISK_DEVICE_TOTAL_SIZE, Thread="+toString());
								UnixPath path = UnixPath.valueOf(in.readUTF());
								if(daemonKey==null) throw new IOException("Only the master server may GET_DISK_DEVICE_TOTAL_SIZE");
								long bytes=BackupManager.getDiskDeviceTotalSize(path);
								out.write(AOServDaemonProtocol.DONE);
								out.writeLong(bytes);
							}
							break;
						case AOServDaemonProtocol.GET_DISK_DEVICE_USED_SIZE :
							{
								if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing GET_DISK_DEVICE_USED_SIZE, Thread="+toString());
								UnixPath path = UnixPath.valueOf(in.readUTF());
								if(daemonKey==null) throw new IOException("Only the master server may GET_DISK_DEVICE_USED_SIZE");
								long bytes=BackupManager.getDiskDeviceUsedSize(path);
								out.write(AOServDaemonProtocol.DONE);
								out.writeLong(bytes);
							}
							break;
						case AOServDaemonProtocol.GET_EMAIL_LIST_FILE :
							{
								if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing GET_EMAIL_LIST_FILE, Thread="+toString());
								UnixPath path = UnixPath.valueOf(in.readUTF());
								if(daemonKey==null) throw new IOException("Only the master server may GET_EMAIL_LIST_FILE");
								String file=EmailListManager.getEmailListFile(path);
								out.write(AOServDaemonProtocol.DONE);
								out.writeUTF(file);
							}
							break;
						case AOServDaemonProtocol.GET_ENCRYPTED_LINUX_ACCOUNT_PASSWORD :
							{
								if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing GET_ENCRYPTED_LINUX_ACCOUNT_PASSWORD, Thread="+toString());
								UserId username=UserId.valueOf(in.readUTF());
								if(daemonKey==null) throw new IOException("Only the master server may GET_ENCRYPTED_LINUX_ACCOUNT_PASSWORD");
								Tuple2<String,Integer> encryptedPassword = LinuxAccountManager.getEncryptedPassword(username);
								out.write(AOServDaemonProtocol.DONE);
								out.writeUTF(encryptedPassword.getElement1());
								if(protocolVersion.compareTo(AOServDaemonProtocol.Version.VERSION_1_80_1) >= 0) {
									Integer changeDate = encryptedPassword.getElement2();
									out.writeCompressedInt(changeDate==null ? -1 : changeDate);
								}
							}
							break;
						case AOServDaemonProtocol.GET_ENCRYPTED_MYSQL_USER_PASSWORD :
							{
								if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing GET_ENCRYPTED_MYSQL_USER_PASSWORD, Thread="+toString());
								int pkey=in.readCompressedInt();
								MySQLUserId username = MySQLUserId.valueOf(in.readUTF());
								if(daemonKey==null) throw new IOException("Only the master server may GET_ENCRYPTED_MYSQL_USER_PASSWORD");
								com.aoindustries.aoserv.client.mysql.Server mysqlServer=connector.getMysql().getMysqlServers().get(pkey);
								if(mysqlServer==null) throw new SQLException("Unable to find Server: "+pkey);
								String encryptedPassword=MySQLUserManager.getEncryptedPassword(mysqlServer, username);
								out.write(AOServDaemonProtocol.DONE);
								out.writeUTF(encryptedPassword);
							}
							break;
						case AOServDaemonProtocol.GET_HTTPD_SERVER_CONCURRENCY :
							{
								if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing GET_HTTPD_SERVER_CONCURRENCY, Thread="+toString());
								int httpdServer = in.readCompressedInt();
								if(daemonKey == null) throw new IOException("Only the master server may GET_HTTPD_SERVER_CONCURRENCY");
								int concurrency = HttpdServerManager.getHttpdServerConcurrency(httpdServer);
								out.write(AOServDaemonProtocol.DONE);
								out.writeCompressedInt(concurrency);
							}
							break;
						case AOServDaemonProtocol.GET_IMAP_FOLDER_SIZES :
							{
								if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing GET_IMAP_FOLDER_SIZES, Thread="+toString());
								UserId username = UserId.valueOf(in.readUTF());
								int numFolders=in.readCompressedInt();
								String[] folderNames=new String[numFolders];
								for(int c=0;c<numFolders;c++) folderNames[c]=in.readUTF();
								if(daemonKey==null) throw new IOException("Only the master server may GET_IMAP_FOLDER_SIZES");
								long[] sizes=ImapManager.getImapFolderSizes(username, folderNames);
								out.write(AOServDaemonProtocol.DONE);
								for(int c=0;c<numFolders;c++) out.writeLong(sizes[c]);
							}
							break;
						case AOServDaemonProtocol.GET_INBOX_ATTRIBUTES :
							{
								if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing GET_INBOX_ATTRIBUTES, Thread="+toString());
								UserId username = UserId.valueOf(in.readUTF());
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
						case AOServDaemonProtocol.GET_UPS_STATUS :
							{
								if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing GET_UPS_STATUS, Thread="+toString());
								if(daemonKey==null) throw new IOException("Only the master server may GET_UPS_STATUS");
								String report = PhysicalServerManager.getUpsStatus();
								out.write(AOServDaemonProtocol.DONE);
								out.writeUTF(report);
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
								UnixPath failoverRoot;
								{
									String failoverRootStr = in.readUTF();
									failoverRoot = failoverRootStr.isEmpty() ? null : UnixPath.valueOf(failoverRootStr);
								}	
								int nestedOperatingSystemVersion = in.readCompressedInt();
								Port port = Port.valueOf(
									in.readCompressedInt(),
									Protocol.TCP
								);
								if(daemonKey==null) throw new IOException("Only the master server may GET_MYSQL_SLAVE_STATUS");
								MySQLDatabaseManager.getSlaveStatus(failoverRoot, nestedOperatingSystemVersion, port, out);
							}
							break;
						case AOServDaemonProtocol.GET_MYSQL_TABLE_STATUS :
							{
								if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing GET_MYSQL_TABLE_STATUS, Thread="+toString());
								UnixPath failoverRoot;
								{
									String failoverRootStr = in.readUTF();
									failoverRoot = failoverRootStr.isEmpty() ? null : UnixPath.valueOf(failoverRootStr);
								}	
								int nestedOperatingSystemVersion = in.readCompressedInt();
								Port port = Port.valueOf(
									in.readCompressedInt(),
									Protocol.TCP
								);
								MySQLDatabaseName databaseName = MySQLDatabaseName.valueOf(in.readUTF());
								if(daemonKey==null) throw new IOException("Only the master server may GET_MYSQL_TABLE_STATUS");
								MySQLDatabaseManager.getTableStatus(failoverRoot, nestedOperatingSystemVersion, port, databaseName, out);
							}
							break;
						case AOServDaemonProtocol.CHECK_MYSQL_TABLES :
							{
								if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing CHECK_MYSQL_TABLES, Thread="+toString());
								UnixPath failoverRoot;
								{
									String failoverRootStr = in.readUTF();
									failoverRoot = failoverRootStr.isEmpty() ? null : UnixPath.valueOf(failoverRootStr);
								}	
								int nestedOperatingSystemVersion = in.readCompressedInt();
								Port port = Port.valueOf(
									in.readCompressedInt(),
									Protocol.TCP
								);
								MySQLDatabaseName databaseName = MySQLDatabaseName.valueOf(in.readUTF());
								int numTables = in.readCompressedInt();
								List<MySQLTableName> tableNames = new ArrayList<>(numTables);
								for(int c=0;c<numTables;c++) {
									tableNames.add(MySQLTableName.valueOf(in.readUTF()));
								}
								if(daemonKey==null) throw new IOException("Only the master server may CHECK_MYSQL_TABLES");
								MySQLDatabaseManager.checkTables(failoverRoot, nestedOperatingSystemVersion, port, databaseName, tableNames, out);
							}
							break;
						case AOServDaemonProtocol.GET_POSTGRES_PASSWORD :
							{
								if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing GET_POSTGRES_PASSWORD, Thread="+toString());
								int pkey=in.readCompressedInt();
								if(daemonKey==null) throw new IOException("Only the master server may GET_POSTGRES_PASSWORD");
								com.aoindustries.aoserv.client.postgresql.UserServer psu=connector.getPostgresql().getPostgresServerUsers().get(pkey);
								if(psu==null) throw new SQLException("Unable to find UserServer: "+pkey);
								String password=PostgresUserManager.getPassword(psu);
								out.write(AOServDaemonProtocol.DONE);
								out.writeUTF(password);
							}
							break;
						case AOServDaemonProtocol.GET_XEN_AUTO_START_LINKS :
							{
								if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing GET_XEN_AUTO_START_LINKS, Thread="+toString());
								if(daemonKey==null) throw new IOException("Only the master server may GET_XEN_AUTO_START_LINKS");
								String[] links = ServerManager.getXenAutoStartLinks();
								out.write(AOServDaemonProtocol.DONE);
								out.writeCompressedInt(links.length);
								for(String link : links) out.writeUTF(link);
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
								String param4 = in.readBoolean() ? in.readUTF() : null;
								if(daemonKey==null) throw new IOException("Only the master server may GRANT_DAEMON_ACCESS");
								AOServDaemonServer.grantDaemonAccess(accessKey, command, param1, param2, param3, param4);
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
								UnixPath path = UnixPath.valueOf(in.readUTF());
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
								com.aoindustries.aoserv.client.mysql.Server mysqlServer=connector.getMysql().getMysqlServers().get(pkey);
								if(mysqlServer==null) throw new SQLException("Unable to find Server: "+pkey);
								MySQLServerManager.restartMySQL(mysqlServer);
								out.write(AOServDaemonProtocol.DONE);
							}
							break;
						case AOServDaemonProtocol.RESTART_POSTGRES :
							{
								if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing RESTART_POSTGRES, Thread="+toString());
								int pkey=in.readCompressedInt();
								if(daemonKey==null) throw new IOException("Only the master server may RESTART_POSTGRES");
								com.aoindustries.aoserv.client.postgresql.Server ps=connector.getPostgresql().getPostgresServers().get(pkey);
								if(ps==null) throw new SQLException("Unable to find Server: "+pkey);
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
								UnixPath path = UnixPath.valueOf(in.readUTF());
								String content=in.readBoolean()?in.readUTF():null;
								int uid=in.readCompressedInt();
								int gid=in.readCompressedInt();
								if(daemonKey==null) throw new IOException("Only the master server may SET_AUTORESPONDER_CONTENT");
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
								UserId username = UserId.valueOf(in.readUTF());
								String cronTable=in.readUTF();
								if(daemonKey==null) throw new IOException("Only the master server may SET_CRON_TABLE");
								LinuxAccountManager.setCronTable(username, cronTable);
								out.write(AOServDaemonProtocol.DONE);
							}
							break;
						case AOServDaemonProtocol.SET_ENCRYPTED_LINUX_ACCOUNT_PASSWORD :
							{
								if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing SET_ENCRYPTED_LINUX_ACCOUNT_PASSWORD, Thread="+toString());
								UserId username=UserId.valueOf(in.readUTF());
								String encryptedPassword = in.readUTF();
								Integer changedDate;
								if(protocolVersion.compareTo(AOServDaemonProtocol.Version.VERSION_1_80_1) >= 0) {
									int i = in.readCompressedInt();
									changedDate = i==-1 ? null : i;
								} else {
									changedDate = null;
								}
								if(daemonKey==null) throw new IOException("Only the master server may SET_ENCRYPTED_LINUX_ACCOUNT_PASSWORD");
								LinuxAccountManager.setEncryptedPassword(username, encryptedPassword, changedDate);
								out.write(AOServDaemonProtocol.DONE);
							}
							break;
						case AOServDaemonProtocol.SET_EMAIL_LIST_FILE :
							{
								if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing SET_EMAIL_LIST_FILE, Thread="+toString());
								UnixPath path = UnixPath.valueOf(in.readUTF());
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
								UserId username=UserId.valueOf(in.readUTF());
								String plainPassword=in.readUTF();
								if(daemonKey==null) throw new IOException("Only the master server may SET_LINUX_SERVER_ACCOUNT_PASSWORD");
								LinuxAccountManager.setPassword(username, plainPassword, true);
								out.write(AOServDaemonProtocol.DONE);
							}
							break;
						case AOServDaemonProtocol.SET_MYSQL_USER_PASSWORD :
							{
								if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing SET_MYSQL_USER_PASSWORD, Thread="+toString());
								int mysqlServerPKey=in.readCompressedInt();
								MySQLUserId username = MySQLUserId.valueOf(in.readUTF());
								String password=in.readBoolean()?in.readUTF():null;
								if(daemonKey==null) throw new IOException("Only the master server may SET_MYSQL_USER_PASSWORD");
								com.aoindustries.aoserv.client.mysql.Server mysqlServer=connector.getMysql().getMysqlServers().get(mysqlServerPKey);
								if(mysqlServer==null) throw new SQLException("Unable to find Server: "+mysqlServerPKey);
								MySQLUserManager.setPassword(mysqlServer, username, password);
								out.write(AOServDaemonProtocol.DONE);
							}
							break;
						case AOServDaemonProtocol.SET_POSTGRES_USER_PASSWORD :
							{
								if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing SET_POSTGRES_USER_PASSWORD, Thread="+toString());
								int pkey=in.readCompressedInt();
								String password=in.readBoolean()?in.readUTF():null;
								com.aoindustries.aoserv.client.postgresql.UserServer psu=connector.getPostgresql().getPostgresServerUsers().get(pkey);
								if(psu==null) throw new SQLException("Unable to find UserServer: "+pkey);
								PostgresUserManager.setPassword(psu, password, false);
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
								com.aoindustries.aoserv.client.mysql.Server mysqlServer=connector.getMysql().getMysqlServers().get(pkey);
								if(mysqlServer==null) throw new SQLException("Unable to find Server: "+pkey);
								MySQLServerManager.startMySQL(mysqlServer);
								out.write(AOServDaemonProtocol.DONE);
							}
							break;
						case AOServDaemonProtocol.START_POSTGRESQL :
							{
								if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing START_POSTGRESQL, Thread="+toString());
								int pkey=in.readCompressedInt();
								if(daemonKey==null) throw new IOException("Only the master server may START_POSTGRESQL");
								com.aoindustries.aoserv.client.postgresql.Server ps=connector.getPostgresql().getPostgresServers().get(pkey);
								if(ps==null) throw new SQLException("Unable to find Server: "+pkey);
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
								com.aoindustries.aoserv.client.mysql.Server mysqlServer=connector.getMysql().getMysqlServers().get(pkey);
								if(mysqlServer==null) throw new SQLException("Unable to find Server: "+pkey);
								MySQLServerManager.stopMySQL(mysqlServer);
								out.write(AOServDaemonProtocol.DONE);
							}
							break;
						case AOServDaemonProtocol.STOP_POSTGRESQL :
							{
								if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing STOP_POSTGRESQL, Thread="+toString());
								int pkey=in.readCompressedInt();
								if(daemonKey==null) throw new IOException("Only the master server may STOP_POSTGRESQL");
								com.aoindustries.aoserv.client.postgresql.Server ps=connector.getPostgresql().getPostgresServers().get(pkey);
								if(ps==null) throw new SQLException("Unable to find Server: "+pkey);
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
								UserId username = UserId.valueOf(in.readUTF());
								if(daemonKey==null) throw new IOException("Only the master server may TAR_HOME_DIRECTORY");
								LinuxAccountManager.tarHomeDirectory(out, username);
								out.write(AOServDaemonProtocol.DONE);
							}
							break;
						case AOServDaemonProtocol.UNTAR_HOME_DIRECTORY :
							{
								if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing UNTAR_HOME_DIRECTORY, Thread="+toString());
								UserId username = UserId.valueOf(in.readUTF());
								if(daemonKey==null) throw new IOException("Only the master server may UNTAR_HOME_DIRECTORY");
								LinuxAccountManager.untarHomeDirectory(in, username);
								out.write(AOServDaemonProtocol.DONE);
							}
							break;
						case AOServDaemonProtocol.OLD_WAIT_FOR_REBUILD :
							{
								if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing OLD_WAIT_FOR_REBUILD, Thread="+toString());
								int oldTableID = in.readCompressedInt();
								if(daemonKey==null) throw new IOException("Only the master server may OLD_WAIT_FOR_REBUILD");
								if(protocolVersion.compareTo(AOServDaemonProtocol.Version.VERSION_1_80_0) >= 0) {
									throw new IOException(
										"OLD_WAIT_FOR_REBUILD should not be used by "
										+ AOServDaemonProtocol.Version.VERSION_1_80_0
										+ " or newer"
									);
								}
								switch(oldTableID) {
									case AOServDaemonProtocol.OLD_HTTPD_SITES_TABLE_ID :
										HttpdManager.waitForRebuild();
										break;
									case AOServDaemonProtocol.OLD_LINUX_ACCOUNTS_TABLE_ID :
										LinuxAccountManager.waitForRebuild();
										break;
									case AOServDaemonProtocol.OLD_MYSQL_DATABASES_TABLE_ID :
										MySQLDatabaseManager.waitForRebuild();
										break;
									case AOServDaemonProtocol.OLD_MYSQL_DB_USERS_TABLE_ID :
										MySQLDBUserManager.waitForRebuild();
										break;
									case AOServDaemonProtocol.OLD_MYSQL_USERS_TABLE_ID :
										MySQLUserManager.waitForRebuild();
										break;
									case AOServDaemonProtocol.OLD_POSTGRES_DATABASES_TABLE_ID :
										PostgresDatabaseManager.waitForRebuild();
										break;
									case AOServDaemonProtocol.OLD_POSTGRES_SERVERS_TABLE_ID :
										PostgresServerManager.waitForRebuild();
										break;
									case AOServDaemonProtocol.OLD_POSTGRES_USERS_TABLE_ID :
										PostgresUserManager.waitForRebuild();
										break;
									default :
										throw new IOException("Unable to wait for rebuild on table "+oldTableID);
								}
								out.write(AOServDaemonProtocol.DONE);
							}
							break;
						case AOServDaemonProtocol.WAIT_FOR_HTTPD_SITE_REBUILD :
							{
								if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing WAIT_FOR_HTTPD_SITE_REBUILD, Thread="+toString());
								if(daemonKey==null) throw new IOException("Only the master server may WAIT_FOR_HTTPD_SITE_REBUILD");
								HttpdManager.waitForRebuild();
								out.write(AOServDaemonProtocol.DONE);
							}
							break;
						case AOServDaemonProtocol.WAIT_FOR_LINUX_ACCOUNT_REBUILD :
							{
								if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing WAIT_FOR_LINUX_ACCOUNT_REBUILD, Thread="+toString());
								if(daemonKey==null) throw new IOException("Only the master server may WAIT_FOR_LINUX_ACCOUNT_REBUILD");
								LinuxAccountManager.waitForRebuild();
								out.write(AOServDaemonProtocol.DONE);
							}
							break;
						case AOServDaemonProtocol.WAIT_FOR_MYSQL_DATABASE_REBUILD :
							{
								if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing WAIT_FOR_MYSQL_DATABASE_REBUILD, Thread="+toString());
								if(daemonKey==null) throw new IOException("Only the master server may WAIT_FOR_MYSQL_DATABASE_REBUILD");
								MySQLDatabaseManager.waitForRebuild();
								out.write(AOServDaemonProtocol.DONE);
							}
							break;
						case AOServDaemonProtocol.WAIT_FOR_MYSQL_DB_USER_REBUILD :
							{
								if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing WAIT_FOR_MYSQL_DB_USER_REBUILD, Thread="+toString());
								if(daemonKey==null) throw new IOException("Only the master server may WAIT_FOR_MYSQL_DB_USER_REBUILD");
								MySQLDBUserManager.waitForRebuild();
								out.write(AOServDaemonProtocol.DONE);
							}
							break;
						case AOServDaemonProtocol.WAIT_FOR_MYSQL_SERVER_REBUILD :
							{
								if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing WAIT_FOR_MYSQL_SERVER_REBUILD, Thread="+toString());
								if(daemonKey==null) throw new IOException("Only the master server may WAIT_FOR_MYSQL_SERVER_REBUILD");
								MySQLServerManager.waitForRebuild();
								out.write(AOServDaemonProtocol.DONE);
							}
							break;
						case AOServDaemonProtocol.WAIT_FOR_MYSQL_USER_REBUILD :
							{
								if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing WAIT_FOR_MYSQL_USER_REBUILD, Thread="+toString());
								if(daemonKey==null) throw new IOException("Only the master server may WAIT_FOR_MYSQL_USER_REBUILD");
								MySQLUserManager.waitForRebuild();
								out.write(AOServDaemonProtocol.DONE);
							}
							break;
						case AOServDaemonProtocol.WAIT_FOR_POSTGRES_DATABASE_REBUILD :
							{
								if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing WAIT_FOR_POSTGRES_DATABASE_REBUILD, Thread="+toString());
								if(daemonKey==null) throw new IOException("Only the master server may WAIT_FOR_POSTGRES_DATABASE_REBUILD");
								PostgresDatabaseManager.waitForRebuild();
								out.write(AOServDaemonProtocol.DONE);
							}
							break;
						case AOServDaemonProtocol.WAIT_FOR_POSTGRES_SERVER_REBUILD :
							{
								if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing WAIT_FOR_POSTGRES_SERVER_REBUILD, Thread="+toString());
								if(daemonKey==null) throw new IOException("Only the master server may WAIT_FOR_POSTGRES_SERVER_REBUILD");
								PostgresServerManager.waitForRebuild();
								out.write(AOServDaemonProtocol.DONE);
							}
							break;
						case AOServDaemonProtocol.WAIT_FOR_POSTGRES_USER_REBUILD :
							{
								if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing WAIT_FOR_POSTGRES_USER_REBUILD, Thread="+toString());
								if(daemonKey==null) throw new IOException("Only the master server may WAIT_FOR_POSTGRES_USER_REBUILD");
								PostgresUserManager.waitForRebuild();
								out.write(AOServDaemonProtocol.DONE);
							}
							break;
						// <editor-fold desc="Virtual Disks" defaultstate="collapsed">
						case AOServDaemonProtocol.VERIFY_VIRTUAL_DISK :
							{
								if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing VERIFY_VIRTUAL_DISK, Thread="+toString());
								String virtualServer = in.readUTF();
								String device = in.readUTF();
								if(daemonKey==null) throw new IOException("Only the master server may VERIFY_VIRTUAL_DISK");
								long lastVerified = VirtualServerManager.verifyVirtualDisk(virtualServer, device);
								out.write(AOServDaemonProtocol.DONE);
								out.writeLong(lastVerified);
							}
							break;
						case AOServDaemonProtocol.UPDATE_VIRTUAL_DISK_LAST_UPDATED :
							{
								if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing UPDATE_VIRTUAL_DISK_LAST_UPDATED, Thread="+toString());
								String virtualServer = in.readUTF();
								String device = in.readUTF();
								long lastVerified = in.readLong();
								if(daemonKey==null) throw new IOException("Only the master server may UPDATE_VIRTUAL_DISK_LAST_UPDATED");
								VirtualServerManager.updateVirtualDiskLastVerified(virtualServer, device, lastVerified);
								out.write(AOServDaemonProtocol.DONE);
							}
							break;
						// </editor-fold>
						// <editor-fold desc="Virtual Servers" defaultstate="collapsed">
						case AOServDaemonProtocol.VNC_CONSOLE :
							{
								if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing VNC_CONSOLE, Thread="+toString());
								long daemonAccessKey=in.readLong();
								DaemonAccessEntry dae=AOServDaemonServer.getDaemonAccessEntry(daemonAccessKey);
								if(dae.command!=AOServDaemonProtocol.VNC_CONSOLE) throw new IOException("Mismatched DaemonAccessEntry command, dae.command!="+AOServDaemonProtocol.VNC_CONSOLE);
								VirtualServerManager.vncConsole(
									socket,
									in,
									out,
									dae.param1 // server_name
								);
							}
							break;
						case AOServDaemonProtocol.CREATE_VIRTUAL_SERVER :
							{
								if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing CREATE_VIRTUAL_SERVER, Thread="+toString());
								String virtualServer = in.readUTF();
								if(daemonKey==null) throw new IOException("Only the master server may CREATE_VIRTUAL_SERVER");
								String output = VirtualServerManager.createVirtualServer(virtualServer);
								out.write(AOServDaemonProtocol.DONE);
								out.writeUTF(output);
							}
							break;
						case AOServDaemonProtocol.REBOOT_VIRTUAL_SERVER :
							{
								if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing REBOOT_VIRTUAL_SERVER, Thread="+toString());
								String virtualServer = in.readUTF();
								if(daemonKey==null) throw new IOException("Only the master server may REBOOT_VIRTUAL_SERVER");
								String output = VirtualServerManager.rebootVirtualServer(virtualServer);
								out.write(AOServDaemonProtocol.DONE);
								out.writeUTF(output);
							}
							break;
						case AOServDaemonProtocol.SHUTDOWN_VIRTUAL_SERVER :
							{
								if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing SHUTDOWN_VIRTUAL_SERVER, Thread="+toString());
								String virtualServer = in.readUTF();
								if(daemonKey==null) throw new IOException("Only the master server may SHUTDOWN_VIRTUAL_SERVER");
								String output = VirtualServerManager.shutdownVirtualServer(virtualServer);
								out.write(AOServDaemonProtocol.DONE);
								out.writeUTF(output);
							}
							break;
						case AOServDaemonProtocol.DESTROY_VIRTUAL_SERVER :
							{
								if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing DESTROY_VIRTUAL_SERVER, Thread="+toString());
								String virtualServer = in.readUTF();
								if(daemonKey==null) throw new IOException("Only the master server may DESTROY_VIRTUAL_SERVER");
								String output = VirtualServerManager.destroyVirtualServer(virtualServer);
								out.write(AOServDaemonProtocol.DONE);
								out.writeUTF(output);
							}
							break;
						case AOServDaemonProtocol.PAUSE_VIRTUAL_SERVER :
							{
								if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing PAUSE_VIRTUAL_SERVER, Thread="+toString());
								String virtualServer = in.readUTF();
								if(daemonKey==null) throw new IOException("Only the master server may PAUSE_VIRTUAL_SERVER");
								String output = VirtualServerManager.pauseVirtualServer(virtualServer);
								out.write(AOServDaemonProtocol.DONE);
								out.writeUTF(output);
							}
							break;
						case AOServDaemonProtocol.UNPAUSE_VIRTUAL_SERVER :
							{
								if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing UNPAUSE_VIRTUAL_SERVER, Thread="+toString());
								String virtualServer = in.readUTF();
								if(daemonKey==null) throw new IOException("Only the master server may UNPAUSE_VIRTUAL_SERVER");
								String output = VirtualServerManager.unpauseVirtualServer(virtualServer);
								out.write(AOServDaemonProtocol.DONE);
								out.writeUTF(output);
							}
							break;
						case AOServDaemonProtocol.GET_VIRTUAL_SERVER_STATUS :
							{
								if(AOServDaemon.DEBUG) System.out.println("DEBUG: AOServDaemonServerThread performing GET_VIRTUAL_SERVER_STATUS, Thread="+toString());
								String virtualServer = in.readUTF();
								if(daemonKey==null) throw new IOException("Only the master server may GET_VIRTUAL_SERVER_STATUS");
								int status = VirtualServerManager.getVirtualServerStatus(virtualServer);
								out.write(AOServDaemonProtocol.DONE);
								out.writeCompressedInt(status);
							}
							break;
						// </editor-fold>
						default :
							break Loop;
					}
				} catch (IOException err) {
					String message=err.getMessage();
					if(
						logIOException
						&& !"Connection reset by peer".equals(message)
					) {
						LogFactory.getLogger(AOServDaemonServerThread.class).log(Level.SEVERE, null, err);
					}
					out.write(AOServDaemonProtocol.IO_EXCEPTION);
					out.writeUTF(message == null ? "null" : message);
				} catch (SQLException err) {
					LogFactory.getLogger(AOServDaemonServerThread.class).log(Level.SEVERE, null, err);
					out.write(AOServDaemonProtocol.SQL_EXCEPTION);
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
