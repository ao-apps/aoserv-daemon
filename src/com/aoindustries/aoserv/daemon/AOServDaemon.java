/*
 * Copyright 2001-2013, 2015, 2017, 2018 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.aoserv.daemon;

import com.aoindustries.aoserv.client.AOServClientConfiguration;
import com.aoindustries.aoserv.client.AOServConnector;
import com.aoindustries.aoserv.client.AOServer;
import com.aoindustries.aoserv.client.NetBind;
import com.aoindustries.aoserv.client.OperatingSystemVersion;
import com.aoindustries.aoserv.client.Server;
import com.aoindustries.aoserv.client.Shell;
import com.aoindustries.aoserv.client.validator.UserId;
import com.aoindustries.aoserv.daemon.cvsd.CvsManager;
import com.aoindustries.aoserv.daemon.distro.DistroManager;
import com.aoindustries.aoserv.daemon.dns.DNSManager;
import com.aoindustries.aoserv.daemon.email.EmailAddressManager;
import com.aoindustries.aoserv.daemon.email.EmailDomainManager;
import com.aoindustries.aoserv.daemon.email.ImapManager;
import com.aoindustries.aoserv.daemon.email.MajordomoManager;
import com.aoindustries.aoserv.daemon.email.ProcmailManager;
import com.aoindustries.aoserv.daemon.email.SaslauthdManager;
import com.aoindustries.aoserv.daemon.email.SendmailCFManager;
import com.aoindustries.aoserv.daemon.email.SmtpRelayManager;
import com.aoindustries.aoserv.daemon.email.SpamAssassinManager;
import com.aoindustries.aoserv.daemon.email.jilter.JilterConfigurationWriter;
import com.aoindustries.aoserv.daemon.failover.FailoverFileReplicationManager;
import com.aoindustries.aoserv.daemon.ftp.FTPManager;
import com.aoindustries.aoserv.daemon.httpd.AWStatsManager;
import com.aoindustries.aoserv.daemon.httpd.HttpdManager;
import com.aoindustries.aoserv.daemon.iptables.IpReputationManager;
import com.aoindustries.aoserv.daemon.monitor.MrtgManager;
import com.aoindustries.aoserv.daemon.monitor.NetworkMonitor;
import com.aoindustries.aoserv.daemon.mysql.MySQLDBUserManager;
import com.aoindustries.aoserv.daemon.mysql.MySQLDatabaseManager;
import com.aoindustries.aoserv.daemon.mysql.MySQLHostManager;
import com.aoindustries.aoserv.daemon.mysql.MySQLServerManager;
import com.aoindustries.aoserv.daemon.mysql.MySQLUserManager;
import com.aoindustries.aoserv.daemon.net.DhcpManager;
import com.aoindustries.aoserv.daemon.net.NetDeviceManager;
import com.aoindustries.aoserv.daemon.net.NullRouteManager;
import com.aoindustries.aoserv.daemon.net.firewalld.FirewalldManager;
import com.aoindustries.aoserv.daemon.net.ssh.SshdManager;
import com.aoindustries.aoserv.daemon.net.xinetd.XinetdManager;
import com.aoindustries.aoserv.daemon.postgres.PgHbaManager;
import com.aoindustries.aoserv.daemon.postgres.PostgresDatabaseManager;
import com.aoindustries.aoserv.daemon.postgres.PostgresServerManager;
import com.aoindustries.aoserv.daemon.postgres.PostgresUserManager;
import com.aoindustries.aoserv.daemon.random.RandomEntropyManager;
import com.aoindustries.aoserv.daemon.timezone.TimeZoneManager;
import com.aoindustries.aoserv.daemon.unix.linux.LinuxAccountManager;
import com.aoindustries.io.IoUtils;
import com.aoindustries.io.unix.Stat;
import com.aoindustries.io.unix.UnixFile;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.InterruptedIOException;
import java.io.Reader;
import java.security.SecureRandom;
import java.sql.SQLException;
import java.util.Collection;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The <code>AOServDaemon</code> starts all of the services that run inside the Java VM.
 * <p>
 * TODO: List AOServDaemon at http://www.firewalld.org/
 * </p>
 *
 * @author  AO Industries, Inc.
 */
final public class AOServDaemon {

	public static final boolean DEBUG=false;

	/**
	 * A single random number generator is shared by all daemon resources to provide better randomness.
	 */
	private static final Random random = new SecureRandom();
	public static Random getRandom() {
		return random;
	}

	/**
	 * The default connection is used to the database, because it should be configured in the properties files.
	 */
	private static AOServConnector conn;

	/**
	 * An unbounded executor for daemon-wide tasks.
	 */
	public final static ExecutorService executorService = Executors.newCachedThreadPool();

	/**
	 * Create no instances.
	 */
	private AOServDaemon() {
	}

	/**
	 * Recursively searches for any files that are not owned by a UID in
	 * the provided list.  If an unowned file is found and is a directory,
	 * its contents are not searched.  To avoid infinite recursion, symbolic
	 * links are not followed but may be deleted.
	 * 
	 * @param  file  the <code>File</code> to search from
	 * @param  uids  the <code>IntList</code> containing the list of uids
	 */
	public static void findUnownedFiles(File file, Collection<Integer> uids, List<File> deleteFileList, int recursionLevel) throws IOException {
		if(file.exists()) {
			// Figure out the ownership
			UnixFile unixFile=new UnixFile(file.getPath());
			Stat stat = unixFile.getStat();
			int uid=stat.getUid();
			if(uids.contains(uid)) {
				if(!stat.isSymLink()) {
					// Search any children files
					String[] list=file.list();
					if(list!=null) {
						int newRecursionLevel=recursionLevel+1;
						int len=list.length;
						for(int c=0;c<len;c++) findUnownedFiles(new File(file, list[c]), uids, deleteFileList, newRecursionLevel);
					}
				}
			} else deleteFileList.add(file);
		}
	}

	public static AOServConnector getConnector() throws IOException {
		synchronized(AOServDaemon.class) {
			if(conn==null) {
				// Get the connector that will be used
				conn=AOServConnector.getConnector(Logger.getLogger(AOServConnector.class.getName()));
			}
			return conn;
		}
	}

	public static AOServer getThisAOServer() throws IOException, SQLException {
		String hostname=AOServDaemonConfiguration.getServerHostname();
		Server server=getConnector().getServers().get(hostname);
		if(server==null) throw new SQLException("Unable to find Server: "+hostname);
		AOServer ao=server.getAOServer();
		if(ao==null) throw new SQLException("Server is not an AOServer: "+hostname);
		return ao;
	}

	/**
	 * Runs the <code>AOServDaemon</code> with the values
	 * provided in <code>com/aoindustries/aoserv/daemon/aoserv-daemon.properties</code>.
	 * This will typically be called by the init scripts of the dedicated machine.
	 */
	public static void main(String[] args) {
		boolean done=false;
		while(!done) {
			try {
				// Configure the SSL
				String trustStorePath=AOServClientConfiguration.getSslTruststorePath();
				if(trustStorePath!=null && trustStorePath.length()>0) {
					System.setProperty("javax.net.ssl.trustStore", trustStorePath);
				}
				String trustStorePassword=AOServClientConfiguration.getSslTruststorePassword();
				if(trustStorePassword!=null && trustStorePassword.length()>0) {
					System.setProperty("javax.net.ssl.trustStorePassword", trustStorePassword);
				}
				String keyStorePath=AOServDaemonConfiguration.getSSLKeystorePath();
				if(keyStorePath!=null && keyStorePath.length()>0) {
					System.setProperty("javax.net.ssl.keyStore", keyStorePath);
				}
				String keyStorePassword=AOServDaemonConfiguration.getSSLKeystorePassword();
				if(keyStorePassword!=null && keyStorePassword.length()>0) {
					System.setProperty("javax.net.ssl.keyStorePassword", keyStorePassword);
				}

				OperatingSystemVersion osv = getThisAOServer().getServer().getOperatingSystemVersion();
				int osvId = osv.getPkey();
				// TODO: Verify operating system version is correct on start-up to protect against config mistakes.
				// TODO: Verify operating system version matches via /etc/release...

				// Start up the managers
				// cvsd
				CvsManager.start();
				// distro
				DistroManager.start();
				// dns
				DNSManager.start();
				// email
				EmailAddressManager.start();
				EmailDomainManager.start();
				ImapManager.start();
				MajordomoManager.start();
				ProcmailManager.start();
				SaslauthdManager.start();
				SendmailCFManager.start();
				SpamAssassinManager.start();
				SmtpRelayManager.start();
				// email.jilter
				JilterConfigurationWriter.start();
				// failover
				FailoverFileReplicationManager.start();
				// ftp
				FTPManager.start();
				// httpd
				AWStatsManager.start();
				HttpdManager.start();
				// iptables
				IpReputationManager.start();
				// monitor
				MrtgManager.start();
				NetworkMonitor.start();
				// mysql
				// TODO: Move to aoserv-daemon: MySQLCreditCardScanner.start();
				MySQLDatabaseManager.start();
				MySQLDBUserManager.start();
				MySQLHostManager.start();
				MySQLServerManager.start();
				MySQLUserManager.start();
				// net
				DhcpManager.start();
				NetDeviceManager.start();
				NullRouteManager.start();
				// net.firewalld
				FirewalldManager.start();
				// net.ssh
				SshdManager.start();
				// net.xinetd
				XinetdManager.start();
				// postgres
				PgHbaManager.start();
				PostgresDatabaseManager.start();
				PostgresServerManager.start();
				PostgresUserManager.start();
				// random
				RandomEntropyManager.start();
				// timezone
				TimeZoneManager.start();
				// unix.linux
				LinuxAccountManager.start();

				// Start up the AOServDaemonServers
				NetBind bind = getThisAOServer().getDaemonBind();
				if(bind != null) {
					AOServDaemonServer server = new AOServDaemonServer(
						bind.getIPAddress().getInetAddress(),
						bind.getPort().getPort(),
						bind.getAppProtocol().getProtocol()
					);
					server.start();
				}
				done = true;
			} catch (ThreadDeath TD) {
				throw TD;
			} catch (Throwable T) {
				Logger logger = LogFactory.getLogger(AOServDaemon.class);
				logger.log(Level.SEVERE, null, T);
				try {
					Thread.sleep(60000);
				} catch(InterruptedException err) {
					logger.log(Level.WARNING, null, err);
				}
			}
		}
	}

	/**
	 * Gets a single-String representation of the command.  This should be used
	 * for display purposes only, because it doesn't quote things in a shell-safe way.
	 */
	public static String getCommandString(String... command) {
		StringBuilder SB = new StringBuilder();
		for(int c=0;c<command.length;c++) {
			if(c>0) SB.append(' ');
			String cmd=command[c];
			boolean needQuote=cmd.indexOf(' ')!=-1;
			if(needQuote) SB.append('"');
			SB.append(command[c]);
			if(needQuote) SB.append('"');
		}
		return SB.toString();
	}

	/**
	 * TODO: First parameter as UnixPath object?
	 */
	public static void exec(String... command) throws IOException {
		if(DEBUG) {
			System.out.print("DEBUG: AOServDaemon.exec(): ");
			System.out.println(getCommandString(command));
		}
		Process P = Runtime.getRuntime().exec(command);
		try {
			P.getOutputStream().close();
		} finally {
			waitFor(P, command);
		}
	}

	/**
	 * TODO: Capture error stream
	 */
	public static void waitFor(Process P, String... command) throws IOException {
		try {
			P.waitFor();
		} catch (InterruptedException err) {
			InterruptedIOException ioErr = new InterruptedIOException("Interrupted while waiting for '"+getCommandString(command)+"'");
			ioErr.initCause(err);
			throw ioErr;
		}
		int exit = P.exitValue();
		if(exit!=0) {
			StringBuilder SB=new StringBuilder();
			SB.append("Non-zero exit status from '");
			SB.append(getCommandString(command));
			SB.append("': ").append(exit);
			throw new IOException(SB.toString());
		}
	}

	/**
	 * Executes a command and captures the output.
	 *
	 * TODO: First parameter as UnixPath object?
	 */
	public static String execAndCapture(String... command) throws IOException {
		Process P = Runtime.getRuntime().exec(command);
		try {
			P.getOutputStream().close();
			try (Reader in = new InputStreamReader(P.getInputStream())) {
				return IoUtils.readFully(in);
			}
		} finally {
			// Read the standard error
			String errorString;
			try (Reader errIn = new InputStreamReader(P.getErrorStream())) {
				errorString = IoUtils.readFully(errIn);
			}
			// Write any standard error to standard error
			if(errorString.length()>0) System.err.println("'"+getCommandString(command)+"': "+errorString);
			try {
				int retCode = P.waitFor();
				if(retCode!=0) throw new IOException("Non-zero exit status from '"+getCommandString(command)+"': "+retCode+", standard error was: "+errorString);
			} catch(InterruptedException err) {
				InterruptedIOException ioErr = new InterruptedIOException("Interrupted while waiting for '"+getCommandString(command)+"'");
				ioErr.initCause(err);
				throw ioErr;
			}
		}
	}

	/**
	 * Executes a command and captures the output.
	 */
	public static byte[] execAndCaptureBytes(String... command) throws IOException {
		Process P = Runtime.getRuntime().exec(command);
		try {
			P.getOutputStream().close();
			try (InputStream in = P.getInputStream()) {
				return IoUtils.readFully(in);
			}
		} finally {
			// Read the standard error
			String errorString;
			try (Reader errIn = new InputStreamReader(P.getErrorStream())) {
				errorString = IoUtils.readFully(errIn);
			}
			// Write any standard error to standard error
			if(errorString.length()>0) System.err.println("'"+getCommandString(command)+"': "+errorString);
			try {
				int retCode = P.waitFor();
				if(retCode!=0) throw new IOException("Non-zero exit status from '"+getCommandString(command)+"': "+retCode+", standard error was: "+errorString);
			} catch(InterruptedException err) {
				InterruptedIOException ioErr = new InterruptedIOException("Interrupted while waiting for '"+getCommandString(command)+"'");
				ioErr.initCause(err);
				throw ioErr;
			}
		}
	}

	/**
	 * Switches to the specified user and executes a command.
	 * 
	 * @param  nice  a nice level passed to /bin/nice, a value of zero (0) will cause nice to not be called
	 */
	public static void suexec(UserId username, String command, int nice) throws IOException {
		/*
		 * Not needed because command is passed as String[] and any funny stuff will
		 * be executed as the proper user.
		if(command==null) throw new IllegalArgumentException("command is null");
		int len = command.length();
		if(len==0) throw new IllegalArgumentException("command is empty");
		for(int c=0;c<len;c++) {
			char ch = command.charAt(c);
			if(
				(ch<'a' || ch>'z')
				&& (ch<'A' || ch>'Z')
				&& (ch<'0' || ch>'9')
				&& ch!=' '
				&& ch!='-'
				&& ch!='_'
				&& ch!='.'
				&& ch!='/'
			) {
				throw new IllegalArgumentException("Invalid command character: "+ch);
			}
		}*/

		String[] cmd;
		if(nice!=0) {
			cmd = new String[] {
				"/bin/nice",
				"-n",
				Integer.toString(nice),
				"/bin/su",
				"-s",
				Shell.BASH.toString(),
				"-c",
				command,
				username.toString()
			};
		} else {
			cmd = new String[] {
				"/bin/su",
				"-s",
				Shell.BASH.toString(),
				"-c",
				command,
				username.toString()
			};
		}
		exec(cmd);
	}
}
