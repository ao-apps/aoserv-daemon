package com.aoindustries.aoserv.daemon;

/*
 * Copyright 2001-2007 by AO Industries, Inc.,
 * 816 Azalea Rd, Mobile, Alabama, 36693, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.aoserv.backup.BackupDaemon;
import com.aoindustries.aoserv.client.AOServClientConfiguration;
import com.aoindustries.aoserv.client.AOServConnector;
import com.aoindustries.aoserv.client.AOServer;
import com.aoindustries.aoserv.client.NetBind;
import com.aoindustries.aoserv.client.SSLConnector;
import com.aoindustries.aoserv.client.Server;
import com.aoindustries.aoserv.client.Shell;
import com.aoindustries.aoserv.daemon.backup.AOServerEnvironment;
import com.aoindustries.aoserv.daemon.cvsd.CvsManager;
import com.aoindustries.aoserv.daemon.distro.DistroManager;
import com.aoindustries.aoserv.daemon.dns.DNSManager;
import com.aoindustries.aoserv.daemon.email.EmailAddressManager;
import com.aoindustries.aoserv.daemon.email.EmailDomainManager;
import com.aoindustries.aoserv.daemon.email.MajordomoManager;
import com.aoindustries.aoserv.daemon.email.ProcmailManager;
import com.aoindustries.aoserv.daemon.email.SendmailCFManager;
import com.aoindustries.aoserv.daemon.email.SmtpRelayManager;
import com.aoindustries.aoserv.daemon.email.SmtpStatManager;
import com.aoindustries.aoserv.daemon.email.SpamAssassinManager;
import com.aoindustries.aoserv.daemon.email.jilter.JilterConfigurationWriter;
import com.aoindustries.aoserv.daemon.email.maillog.MailLogReader;
import com.aoindustries.aoserv.daemon.failover.FailoverFileReplicationManager;
import com.aoindustries.aoserv.daemon.ftp.FTPManager;
import com.aoindustries.aoserv.daemon.httpd.AWStatsManager;
import com.aoindustries.aoserv.daemon.httpd.HttpdManager;
import com.aoindustries.aoserv.daemon.monitor.MrtgManager;
import com.aoindustries.aoserv.daemon.monitor.NetStatMonitor;
import com.aoindustries.aoserv.daemon.mysql.MySQLDBUserManager;
import com.aoindustries.aoserv.daemon.mysql.MySQLDatabaseManager;
import com.aoindustries.aoserv.daemon.mysql.MySQLHostManager;
import com.aoindustries.aoserv.daemon.mysql.MySQLServerManager;
import com.aoindustries.aoserv.daemon.mysql.MySQLUserManager;
import com.aoindustries.aoserv.daemon.net.DhcpManager;
import com.aoindustries.aoserv.daemon.net.NetDeviceManager;
import com.aoindustries.aoserv.daemon.net.ssh.SshdManager;
import com.aoindustries.aoserv.daemon.net.xinetd.XinetdManager;
import com.aoindustries.aoserv.daemon.postgres.PgHbaManager;
import com.aoindustries.aoserv.daemon.postgres.PostgresDatabaseManager;
import com.aoindustries.aoserv.daemon.postgres.PostgresServerManager;
import com.aoindustries.aoserv.daemon.postgres.PostgresUserManager;
import com.aoindustries.aoserv.daemon.random.RandomEntropyManager;
import com.aoindustries.aoserv.daemon.report.ServerReportThread;
import com.aoindustries.aoserv.daemon.slocate.SLocateManager;
import com.aoindustries.aoserv.daemon.timezone.TimeZoneManager;
import com.aoindustries.aoserv.daemon.unix.linux.LinuxAccountManager;
import com.aoindustries.email.ErrorMailer;
import com.aoindustries.io.unix.Stat;
import com.aoindustries.io.unix.UnixFile;
import com.aoindustries.profiler.Profiler;
import com.aoindustries.util.ErrorHandler;
import com.aoindustries.util.ErrorPrinter;
import com.aoindustries.util.IntList;
import com.aoindustries.util.StringUtility;
import com.aoindustries.util.WrappedException;
import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.Security;
import java.sql.SQLException;
import java.util.List;
import java.util.Random;

/**
 * The <code>AOServDaemon</code> starts all of the services that run inside the Java VM.
 *
 * @author  AO Industries, Inc.
 */
final public class AOServDaemon {

    public static final boolean DEBUG=false;

    /**
     * A single random number generator is shared by all daemon resources to provide better randomness.
     */
    private static Random random;
    public static Random getRandom() {
	Profiler.startProfile(Profiler.FAST, AOServDaemon.class, "getRandom()", null);
	try {
	    synchronized(AOServDaemon.class) {
                String algorithm="SHA1PRNG";
		try {
		    if(random==null) random=SecureRandom.getInstance(algorithm);
		    return random;
		} catch(NoSuchAlgorithmException err) {
		    throw new WrappedException(err, new Object[] {"algorithm="+algorithm});
		}
	    }
	} finally {
	    Profiler.endProfile(Profiler.FAST);
	}
    }

    /**
     * The default connection is used to the database, because it should be configured in the properties files.
     */
    private static AOServConnector conn;

    /**
     * Create no instances.
     */
    private AOServDaemon() {
        Profiler.startProfile(Profiler.INSTANTANEOUS, AOServDaemon.class, "<init>()", null);
        Profiler.endProfile(Profiler.INSTANTANEOUS);
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
    public static void findUnownedFiles(File file, IntList uids, List<File> deleteFileList, int recursionLevel) throws IOException {
        Profiler.startProfile(Profiler.IO, AOServDaemon.class, "findUnownedFiles(File,IntList,List<File>,int)", recursionLevel==0?null:Integer.valueOf(recursionLevel));
        try {
            if(file.exists()) {
                // Figure out the ownership
                UnixFile unixFile=new UnixFile(file.getPath());
                Stat stat = unixFile.getStat();
                int uid=stat.getUID();
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
        } finally {
            Profiler.endProfile(Profiler.IO);
        }
    }

    public static AOServConnector getConnector() throws IOException {
        Profiler.startProfile(Profiler.FAST, AOServDaemon.class, "getConnector()", null);
        try {
	    synchronized(AOServDaemon.class) {
		if(conn==null) {
		    // Get the connector that will be used
		    conn=AOServConnector.getConnector(getErrorHandler());
		}
		return conn;
	    }
        } finally {
            Profiler.endProfile(Profiler.FAST);
        }
    }

    public static AOServer getThisAOServer() throws IOException, SQLException {
        Profiler.startProfile(Profiler.FAST, AOServDaemon.class, "getThisAOServer()", null);
        try {
            String hostname=AOServDaemonConfiguration.getServerHostname();
            Server server=getConnector().servers.get(hostname);
            if(server==null) throw new SQLException("Unable to find Server: "+hostname);
            AOServer ao=server.getAOServer();
            if(ao==null) throw new SQLException("Server is not an AOServer: "+hostname);
            return ao;
        } finally {
            Profiler.endProfile(Profiler.FAST);
        }
    }

    /**
     * Runs the <code>AOServDaemon</code> with the values
     * provided in <code>com/aoindustries/aoserv/daemon/aoserv-daemon.properties</code>.
     * This will typically be called by the init scripts of the dedicated machine.
     */
    public static void main(String[] args) {
        // Not profiled because the profiler is not enabled here
	boolean done=false;
	while(!done) {
            try {
                Profiler.setProfilerLevel(AOServDaemonConfiguration.getProfilerLevel());

                // Configure the SSL
                synchronized(SSLConnector.class) {
                    if(!SSLConnector.sslProviderLoaded[0]) {
                        boolean useSSL=false;
                        String trustStorePath=AOServClientConfiguration.getSslTruststorePath();
                        if(trustStorePath!=null && trustStorePath.length()>0) {
                            System.setProperty("javax.net.ssl.trustStore", trustStorePath);
                            useSSL=true;
                        }
                        String trustStorePassword=AOServClientConfiguration.getSslTruststorePassword();
                        if(trustStorePassword!=null && trustStorePassword.length()>0) {
                            System.setProperty("javax.net.ssl.trustStorePassword", trustStorePassword);
                            useSSL=true;
                        }
                        String keyStorePath=AOServDaemonConfiguration.getSSLKeystorePath();
                        if(keyStorePath!=null && keyStorePath.length()>0) {
                            System.setProperty("javax.net.ssl.keyStore", keyStorePath);
                            useSSL=true;
                        }
                        String keyStorePassword=AOServDaemonConfiguration.getSSLKeystorePassword();
                        if(keyStorePassword!=null && keyStorePassword.length()>0) {
                            System.setProperty("javax.net.ssl.keyStorePassword", keyStorePassword);
                            useSSL=true;
                        }
                        if(useSSL) {
                            Security.addProvider(new com.sun.net.ssl.internal.ssl.Provider());
                            SSLConnector.sslProviderLoaded[0]=true;
                        }
                    }
                }

                // Start up the resource monitoring
                ServerReportThread.startThread();
                SmtpStatManager.start();
                MailLogReader.start();

                // Start up the managers
                AWStatsManager.start();
                if(AOServDaemonConfiguration.isManagerEnabled(AOServerEnvironment.class)) BackupDaemon.start(new AOServerEnvironment());
                CvsManager.start();
                DhcpManager.start();
                DistroManager.start();
                DNSManager.start();
                EmailAddressManager.start();
                EmailDomainManager.start();
                FailoverFileReplicationManager.start();
                FTPManager.start();
                HttpdManager.start();
                // TODO: Enable once data is created InterBaseManager.start();
                JilterConfigurationWriter.start();
                LinuxAccountManager.start();
                MajordomoManager.start();
                MrtgManager.start();
                MySQLDatabaseManager.start();
                MySQLDBUserManager.start();
                MySQLHostManager.start();
                MySQLServerManager.start();
                MySQLUserManager.start();
                NetDeviceManager.start();
                PgHbaManager.start();
                PostgresDatabaseManager.start();
                PostgresServerManager.start();
                PostgresUserManager.start();
                ProcmailManager.start();
                RandomEntropyManager.start();
                SendmailCFManager.start();
                SpamAssassinManager.start();
                SshdManager.start();
                SLocateManager.start();
                SmtpRelayManager.start();
                XinetdManager.start();
                TimeZoneManager.start();

                // Start up the AOServDaemonServers
                NetBind bind=getThisAOServer().getDaemonBind();
                if(bind!=null) new AOServDaemonServer(bind.getIPAddress().getIPAddress(), bind.getPort().getPort(), bind.getAppProtocol().getProtocol());

                // Start up the reliability monitoring
                NetStatMonitor.start();

                done=true;
            } catch (ThreadDeath TD) {
                throw TD;
            } catch (Throwable T) {
                reportError(T, null);
                try {
                    Thread.sleep(60000);
                } catch(InterruptedException err) {
                    reportWarning(err, null);
                }
            }
	}
    }

    /**
     * Logs an error message to <code>System.err</code>.
     * Also sends email messages to <code>aoserv.daemon.error.email.to</code>.
     */
    public static void reportError(Throwable T, Object[] extraInfo) {
        Profiler.startProfile(Profiler.UNKNOWN, AOServDaemon.class, "reportError(Throwable,Object[])", null);
        try {
            ErrorPrinter.printStackTraces(T, extraInfo);
            try {
                String smtp=AOServDaemonConfiguration.getErrorSmtpServer();
                if(smtp!=null && smtp.length()>0) {
                    List<String> addys=StringUtility.splitStringCommaSpace(AOServDaemonConfiguration.getErrorEmailTo());
                    String from=AOServDaemonConfiguration.getErrorEmailFrom();
                    for(int c=0;c<addys.size();c++) {
                        ErrorMailer.reportError(
                            getRandom(),
                            T,
                            extraInfo,
                            smtp,
                            from,
                            addys.get(c),
                            "AOServDaemon Error"
                        );
                    }
                }
            } catch(IOException err) {
                ErrorPrinter.printStackTraces(err);
            }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    /**
     * Logs a warning message to <code>System.err</code>.
     * Also sends email messages to <code>aoserv.daemon.warning.email.to</code>.
     */
    public static void reportWarning(Throwable T, Object[] extraInfo) {
        Profiler.startProfile(Profiler.UNKNOWN, AOServDaemon.class, "reportWarning(Throwable,Object[])", null);
        try {
            ErrorPrinter.printStackTraces(T, extraInfo);
            try {
                String smtp=AOServDaemonConfiguration.getWarningSmtpServer();
                if(smtp!=null && smtp.length()>0) {
                    List<String> addys=StringUtility.splitStringCommaSpace(AOServDaemonConfiguration.getWarningEmailTo());
                    String from=AOServDaemonConfiguration.getWarningEmailFrom();
                    for(int c=0;c<addys.size();c++) {
                        ErrorMailer.reportError(
                            getRandom(),
                            T,
                            extraInfo,
                            smtp,
                            from,
                            addys.get(c),
                            "AOServDaemon Warning"
                        );
                    }
                }
            } catch(IOException err) {
                ErrorPrinter.printStackTraces(err);
            }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    /**
     * Logs a security message to <code>System.err</code>.
     * Also sends email messages to <code>aoserv.daemon.security.email.to</code>.
     */
    public static void reportErrorMessage(String message) {
        Profiler.startProfile(Profiler.UNKNOWN, AOServDaemon.class, "reportErrorMessage(String)", null);
        try {
            System.err.println(message);
            try {
                String smtp=AOServDaemonConfiguration.getErrorSmtpServer();
                if(smtp!=null && smtp.length()>0) {
                    String from=AOServDaemonConfiguration.getErrorEmailFrom();
                    List<String> addys=StringUtility.splitStringCommaSpace(AOServDaemonConfiguration.getErrorEmailTo());
                    for(int c=0;c<addys.size();c++) {
                        ErrorMailer.reportError(
                            getRandom(),
                            message,
                            smtp,
                            from,
                            addys.get(c),
                            "AOServDaemon Error"
                        );
                    }
                }
            } catch(IOException err) {
                ErrorPrinter.printStackTraces(err);
            }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    /**
     * Logs a security message to <code>System.err</code>.
     * Also sends email messages to <code>aoserv.daemon.security.email.to</code>.
     */
    public static void reportFullMonitoringMessage(String message) {
        Profiler.startProfile(Profiler.UNKNOWN, AOServDaemon.class, "reportFullMonitoringMessage(String)", null);
        try {
            System.err.println(message);
            try {
                String smtp=AOServDaemonConfiguration.getMonitorSmtpServer();
                if(smtp!=null && smtp.length()>0) {
                    String from=AOServDaemonConfiguration.getMonitorEmailFullFrom();
                    List<String> addys=StringUtility.splitStringCommaSpace(AOServDaemonConfiguration.getMonitorEmailFullTo());
                    for(int c=0;c<addys.size();c++) {
                        ErrorMailer.reportError(
                            getRandom(),
                            message,
                            smtp,
                            from,
                            addys.get(c),
                            "AOServMonitoring"
                        );
                    }
                }
            } catch(IOException err) {
                ErrorPrinter.printStackTraces(err);
            }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    /**
     * Logs a security message to <code>System.err</code>.
     * Also sends email messages to <code>aoserv.daemon.security.email.to</code>.
     */
    public static void reportSecurityMessage(String message) {
        Profiler.startProfile(Profiler.UNKNOWN, AOServDaemon.class, "reportSecurityMessage(String)", null);
        try {
            System.err.println(message);
            try {
                String smtp=AOServDaemonConfiguration.getSecuritySmtpServer();
                if(smtp!=null && smtp.length()>0) {
                    String from=AOServDaemonConfiguration.getSecurityEmailFrom();
                    List<String> addys=StringUtility.splitStringCommaSpace(AOServDaemonConfiguration.getSecurityEmailTo());
                    for(int c=0;c<addys.size();c++) {
                        ErrorMailer.reportError(
                            getRandom(),
                            message,
                            smtp,
                            from,
                            addys.get(c),
                            "AOServDaemonSec"
                        );
                    }
                }
            } catch(IOException err) {
                ErrorPrinter.printStackTraces(err);
            }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    private static ErrorHandler errorHandler;
    public synchronized static ErrorHandler getErrorHandler() {
        Profiler.startProfile(Profiler.FAST, AOServDaemon.class, "getErrorHandler()", null);
        try {
            if(errorHandler==null) {
                errorHandler=new ErrorHandler() {
                    public final void reportError(Throwable T, Object[] extraInfo) {
                        AOServDaemon.reportError(T, extraInfo);
                    }

                    public final void reportWarning(Throwable T, Object[] extraInfo) {
                        AOServDaemon.reportWarning(T, extraInfo);
                    }
                };
            }
            return errorHandler;
        } finally {
            Profiler.endProfile(Profiler.FAST);
        }
    }

    public static void exec(String[] command) throws IOException {
        Profiler.startProfile(Profiler.UNKNOWN, AOServDaemon.class, "exec(String[])", null);
        try {
            if(DEBUG) {
                System.out.print("DEBUG: AOServDaemon.exec(): ");
                for(int c=0;c<command.length;c++) {
                    if(c>0) System.out.print(' ');
                    String cmd=command[c];
                    boolean needQuote=cmd.indexOf(' ')!=-1;
                    if(needQuote) System.out.print('"');
                    System.out.print(command[c]);
                    if(needQuote) System.out.print('"');
                };
                System.out.println();
            }
            Process P = Runtime.getRuntime().exec(command);
            waitFor(command, P);
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    public static void waitFor(String[] command, Process P) throws IOException {
        Profiler.startProfile(Profiler.UNKNOWN, AOServDaemon.class, "waitFor(String[],Process)", null);
        try {
            try {
                P.waitFor();
            } catch (InterruptedException err) {
                InterruptedIOException ioErr=new InterruptedIOException();
                ioErr.initCause(err);
                throw ioErr;
            }
            int exit = P.exitValue();
            if(exit!=0) {
                StringBuilder SB=new StringBuilder();
                SB.append("Non-zero exit status from '");
                for(int c=0;c<command.length;c++) {
                    if(c>0) SB.append(' ');
                    String cmd=command[c];
                    boolean needQuote=cmd.indexOf(' ')!=-1;
                    if(needQuote) SB.append('"');
                    SB.append(command[c]);
                    if(needQuote) SB.append('"');
                };
                SB.append("': ").append(exit);
                throw new IOException(SB.toString());
            }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    /**
     * Switches to the specified user and executes a command.
     */
    public static void suexec(String username, String command) throws IOException {
        Profiler.startProfile(Profiler.UNKNOWN, AOServDaemon.class, "suexec(String,String)", null);
        try {
            String[] cmd={
                "/bin/su",
                "-s",
                Shell.BASH,
                "-c",
                command,
                username
            };
            exec(cmd);
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }
}
