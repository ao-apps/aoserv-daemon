package com.aoindustries.aoserv.daemon;

/*
 * Copyright 2001-2008 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.aoserv.client.AOServClientConfiguration;
import com.aoindustries.aoserv.client.AOServConnector;
import com.aoindustries.aoserv.client.AOServer;
import com.aoindustries.aoserv.client.NetBind;
import com.aoindustries.aoserv.client.SSLConnector;
import com.aoindustries.aoserv.client.Server;
import com.aoindustries.aoserv.client.Shell;
import com.aoindustries.aoserv.daemon.cvsd.CvsManager;
import com.aoindustries.aoserv.daemon.distro.DistroManager;
import com.aoindustries.aoserv.daemon.dns.DNSManager;
import com.aoindustries.aoserv.daemon.email.EmailAddressManager;
import com.aoindustries.aoserv.daemon.email.EmailDomainManager;
import com.aoindustries.aoserv.daemon.email.MajordomoManager;
import com.aoindustries.aoserv.daemon.email.ProcmailManager;
import com.aoindustries.aoserv.daemon.email.SendmailCFManager;
import com.aoindustries.aoserv.daemon.email.SmtpRelayManager;
import com.aoindustries.aoserv.daemon.email.SpamAssassinManager;
import com.aoindustries.aoserv.daemon.email.jilter.JilterConfigurationWriter;
import com.aoindustries.aoserv.daemon.failover.FailoverFileReplicationManager;
import com.aoindustries.aoserv.daemon.ftp.FTPManager;
import com.aoindustries.aoserv.daemon.httpd.AWStatsManager;
import com.aoindustries.aoserv.daemon.httpd.HttpdManager;
import com.aoindustries.aoserv.daemon.monitor.MrtgManager;
import com.aoindustries.aoserv.daemon.monitor.NetStatMonitor;
import com.aoindustries.aoserv.daemon.mysql.MySQLCreditCardScanner;
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
import com.aoindustries.aoserv.daemon.timezone.TimeZoneManager;
import com.aoindustries.aoserv.daemon.unix.linux.LinuxAccountManager;
import com.aoindustries.email.ErrorMailer;
import com.aoindustries.io.unix.Stat;
import com.aoindustries.io.unix.UnixFile;
import com.aoindustries.profiler.Profiler;
import com.aoindustries.util.BufferManager;
import com.aoindustries.util.ErrorHandler;
import com.aoindustries.util.ErrorPrinter;
import com.aoindustries.util.IntList;
import com.aoindustries.util.StringUtility;
import com.aoindustries.util.WrappedException;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.InterruptedIOException;
import java.io.Reader;
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
     * Not synchronized because multiple instantiation is acceptable.
     */
    private static Random random;
    public static Random getRandom() {
        if(random==null) {
            final String algorithm="SHA1PRNG";
            try {
                random=SecureRandom.getInstance(algorithm);
            } catch(NoSuchAlgorithmException err) {
                throw new WrappedException(err, new Object[] {"algorithm="+algorithm});
            }
        }
        return random;
    }

    /**
     * The default connection is used to the database, because it should be configured in the properties files.
     */
    private static AOServConnector conn;

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
    public static void findUnownedFiles(File file, IntList uids, List<File> deleteFileList, int recursionLevel) throws IOException {
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
    }

    public static AOServConnector getConnector() throws IOException {
        synchronized(AOServDaemon.class) {
            if(conn==null) {
                // Get the connector that will be used
                conn=AOServConnector.getConnector(getErrorHandler());
            }
            return conn;
        }
    }

    public static AOServer getThisAOServer() throws IOException, SQLException {
        String hostname=AOServDaemonConfiguration.getServerHostname();
        Server server=getConnector().servers.get(hostname);
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

                // Start up the managers
                AWStatsManager.start();
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
                MySQLCreditCardScanner.start();
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
    }

    /**
     * Logs a warning message to <code>System.err</code>.
     * Also sends email messages to <code>aoserv.daemon.warning.email.to</code>.
     */
    public static void reportWarning(Throwable T, Object[] extraInfo) {
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
    }

    /**
     * Logs a security message to <code>System.err</code>.
     * Also sends email messages to <code>aoserv.daemon.security.email.to</code>.
     */
    public static void reportErrorMessage(String message) {
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
    }

    /**
     * Logs a security message to <code>System.err</code>.
     * Also sends email messages to <code>aoserv.daemon.security.email.to</code>.
     */
    public static void reportFullMonitoringMessage(String message) {
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
    }

    /**
     * Logs a security message to <code>System.err</code>.
     * Also sends email messages to <code>aoserv.daemon.security.email.to</code>.
     */
    public static void reportSecurityMessage(String message) {
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
    }

    private static ErrorHandler errorHandler;
    public synchronized static ErrorHandler getErrorHandler() {
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
    }

    /**
     * Gets a single-String representation of the command.  This should be used
     * for display purposes only, because it doesn't quote things in a shell-safe way.
     */
    public static String getCommandString(String[] command) {
        StringBuilder SB = new StringBuilder();
        for(int c=0;c<command.length;c++) {
            if(c>0) SB.append(' ');
            String cmd=command[c];
            boolean needQuote=cmd.indexOf(' ')!=-1;
            if(needQuote) SB.append('"');
            SB.append(command[c]);
            if(needQuote) SB.append('"');
        };
        return SB.toString();
    }
    
    public static void exec(String[] command) throws IOException {
        if(DEBUG) {
            System.out.print("DEBUG: AOServDaemon.exec(): ");
            System.out.println(getCommandString(command));
        }
        Process P = Runtime.getRuntime().exec(command);
        try {
            P.getOutputStream().close();
        } finally {
            waitFor(command, P);
        }
    }

    /**
     * TODO: Capture error stream
     */
    public static void waitFor(String[] command, Process P) throws IOException {
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
     */
    public static String execAndCapture(String[] command) throws IOException {
        Process P = Runtime.getRuntime().exec(command);
        try {
            P.getOutputStream().close();
            // Read the results
            Reader in = new InputStreamReader(P.getInputStream());
            try {
                StringBuilder sb = new StringBuilder();
                char[] buff = BufferManager.getChars();
                try {
                    int count;
                    while((count=in.read(buff, 0, BufferManager.BUFFER_SIZE))!=-1) {
                        sb.append(buff, 0, count);
                    }
                    return sb.toString();
                } finally {
                    BufferManager.release(buff);
                }
            } finally {
                in.close();
            }
        } finally {
            // TODO: Put into waitFor and use here
            
            // Read the standard error
            StringBuilder errorSB = new StringBuilder();
            Reader errIn = new InputStreamReader(P.getErrorStream());
            try {
                char[] buff = BufferManager.getChars();
                try {
                    int ret;
                    while((ret=errIn.read(buff, 0, BufferManager.BUFFER_SIZE))!=-1) errorSB.append(buff, 0, ret);
                } finally {
                    BufferManager.release(buff);
                }
            } finally {
                errIn.close();
            }
            // Write any standard error to standard error
            String errorString = errorSB.toString();
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
     */
    public static void suexec(String username, String command) throws IOException {
        String[] cmd={
            "/bin/su",
            "-s",
            Shell.BASH,
            "-c",
            command,
            username
        };
        exec(cmd);
    }
}
