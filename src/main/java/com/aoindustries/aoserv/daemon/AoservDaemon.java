/*
 * aoserv-daemon - Server management daemon for the AOServ Platform.
 * Copyright (C) 2001-2013, 2015, 2017, 2018, 2019, 2020, 2021, 2022, 2024  AO Industries, Inc.
 *     support@aoindustries.com
 *     7262 Bull Pen Cir
 *     Mobile, AL 36695
 *
 * This file is part of aoserv-daemon.
 *
 * aoserv-daemon is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * aoserv-daemon is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with aoserv-daemon.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.aoindustries.aoserv.daemon;

import com.aoapps.io.posix.PosixFile;
import com.aoapps.io.posix.Stat;
import com.aoapps.lang.Throwables;
import com.aoapps.lang.concurrent.ExecutionExceptions;
import com.aoapps.lang.exception.ConfigurationException;
import com.aoapps.lang.function.ConsumerE;
import com.aoapps.lang.function.FunctionE;
import com.aoapps.lang.io.IoUtils;
import com.aoapps.lang.io.NullOutputStream;
import com.aoindustries.aoserv.client.AoservClientConfiguration;
import com.aoindustries.aoserv.client.AoservConnector;
import com.aoindustries.aoserv.client.distribution.OperatingSystemVersion;
import com.aoindustries.aoserv.client.linux.Server;
import com.aoindustries.aoserv.client.linux.Shell;
import com.aoindustries.aoserv.client.linux.User;
import com.aoindustries.aoserv.client.net.Bind;
import com.aoindustries.aoserv.client.net.Host;
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
import com.aoindustries.aoserv.daemon.net.fail2ban.Fail2banManager;
import com.aoindustries.aoserv.daemon.net.firewalld.FirewalldManager;
import com.aoindustries.aoserv.daemon.net.ssh.SshdManager;
import com.aoindustries.aoserv.daemon.net.xinetd.XinetdManager;
import com.aoindustries.aoserv.daemon.posix.linux.LinuxAccountManager;
import com.aoindustries.aoserv.daemon.postgres.PgHbaManager;
import com.aoindustries.aoserv.daemon.postgres.PostgresDatabaseManager;
import com.aoindustries.aoserv.daemon.postgres.PostgresServerManager;
import com.aoindustries.aoserv.daemon.postgres.PostgresUserManager;
import com.aoindustries.aoserv.daemon.random.RandomEntropyManager;
import com.aoindustries.aoserv.daemon.timezone.TimeZoneManager;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.io.Reader;
import java.security.SecureRandom;
import java.sql.SQLException;
import java.util.Collection;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The <code>AoservDaemon</code> starts all of the services that run inside the Java VM.
 *
 * <p>TODO: List AoservDaemon at http://www.firewalld.org/</p>
 *
 * @author  AO Industries, Inc.
 */
public final class AoservDaemon {

  /** Make no instances. */
  private AoservDaemon() {
    throw new AssertionError();
  }

  private static final Logger logger = Logger.getLogger(AoservDaemon.class.getName());

  public static final boolean DEBUG = false;

  private static final SecureRandom secureRandom = new SecureRandom();

  /**
   * A single random number generator is shared by all daemon resources.
   *
   * <p>Note: This is not a {@linkplain SecureRandom#getInstanceStrong() strong instance} to avoid blocking.</p>
   */
  public static SecureRandom getSecureRandom() {
    return secureRandom;
  }

  private static final Random fastRandom = new Random(IoUtils.bufferToLong(secureRandom.generateSeed(Long.BYTES)));

  /**
   * A fast pseudo-random number generator for non-cryptographic purposes.
   */
  public static Random getFastRandom() {
    return fastRandom;
  }

  /**
   * The default connection is used to the database, because it should be configured in the properties files.
   */
  private static AoservConnector conn;

  /**
   * An unbounded executor for daemon-wide tasks.
   */
  public static final ExecutorService executorService = Executors.newCachedThreadPool();

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
    if (file.exists()) {
      // Figure out the ownership
      PosixFile unixFile = new PosixFile(file.getPath());
      Stat stat = unixFile.getStat();
      int uid = stat.getUid();
      if (uids.contains(uid)) {
        if (!stat.isSymLink()) {
          // Search any children files
          String[] list = file.list();
          if (list != null) {
            int newRecursionLevel = recursionLevel + 1;
            int len = list.length;
            for (int c = 0; c < len; c++) {
              findUnownedFiles(new File(file, list[c]), uids, deleteFileList, newRecursionLevel);
            }
          }
        }
      } else {
        deleteFileList.add(file);
      }
    }
  }

  public static AoservConnector getConnector() throws ConfigurationException {
    synchronized (AoservDaemon.class) {
      if (conn == null) {
        // Get the connector that will be used
        conn = AoservConnector.getConnector();
      }
      return conn;
    }
  }

  public static Server getThisServer() throws IOException, SQLException {
    String hostname = AoservDaemonConfiguration.getServerHostname();
    Host host = getConnector().getNet().getHost().get(hostname);
    if (host == null) {
      throw new SQLException("Unable to find Host: " + hostname);
    }
    Server linuxServer = host.getLinuxServer();
    if (linuxServer == null) {
      throw new SQLException("Host is not a linux.Server: " + hostname);
    }
    return linuxServer;
  }

  /**
   * Runs the <code>AoservDaemon</code> with the values
   * provided in <code>com/aoindustries/aoserv/daemon/aoserv-daemon.properties</code>.
   * This will typically be called by the init scripts of the dedicated machine.
   */
  @SuppressWarnings({"UseSpecificCatch", "BroadCatchBlock", "TooBroadCatch", "SleepWhileInLoop"})
  public static void main(String[] args) {
    boolean done = false;
    while (!Thread.currentThread().isInterrupted() && !done) {
      try {
        // Configure the SSL
        String trustStorePath = AoservClientConfiguration.getSslTruststorePath();
        if (trustStorePath != null && trustStorePath.length() > 0) {
          System.setProperty("javax.net.ssl.trustStore", trustStorePath);
        }
        String trustStorePassword = AoservClientConfiguration.getSslTruststorePassword();
        if (trustStorePassword != null && trustStorePassword.length() > 0) {
          System.setProperty("javax.net.ssl.trustStorePassword", trustStorePassword);
        }
        String keyStorePath = AoservDaemonConfiguration.getSslKeystorePath();
        if (keyStorePath != null && keyStorePath.length() > 0) {
          System.setProperty("javax.net.ssl.keyStore", keyStorePath);
        }
        String keyStorePassword = AoservDaemonConfiguration.getSslKeystorePassword();
        if (keyStorePassword != null && keyStorePassword.length() > 0) {
          System.setProperty("javax.net.ssl.keyStorePassword", keyStorePassword);
        }

        OperatingSystemVersion osv = getThisServer().getHost().getOperatingSystemVersion();
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
        // net.fail2ban
        Fail2banManager.start();
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
        Bind bind = getThisServer().getDaemonBind();
        if (bind != null) {
          AoservDaemonServer server = new AoservDaemonServer(
              bind.getIpAddress().getInetAddress(),
              bind.getPort().getPort(),
              bind.getAppProtocol().getProtocol()
          );
          server.start();
        }
        done = true;
      } catch (ThreadDeath td) {
        throw td;
      } catch (Throwable t) {
        logger.log(Level.SEVERE, null, t);
        try {
          Thread.sleep(60000);
        } catch (InterruptedException err) {
          logger.log(Level.WARNING, null, err);
          // Restore the interrupted status
          Thread.currentThread().interrupt();
        }
      }
    }
  }

  /**
   * Gets a single-String representation of the command.  This should be used
   * for display purposes only, because it doesn't quote things in a shell-safe way.
   */
  // TODO: Use ao-encoding SH encoder?
  public static String getCommandString(String... command) {
    StringBuilder sb = new StringBuilder();
    for (int c = 0; c < command.length; c++) {
      if (c > 0) {
        sb.append(' ');
      }
      String cmd = command[c];
      boolean needQuote = cmd.indexOf(' ') != -1;
      if (needQuote) {
        sb.append('"');
      }
      sb.append(command[c]);
      if (needQuote) {
        sb.append('"');
      }
    }
    return sb.toString();
  }

  /**
   * Executes a command, performing any arbitrary action with the command's output stream.
   * Command's input is written on the current thread.
   * Command's output is read, and handled, on a different thread.
   * Command's error output is also read on a different thread.
   *
   * <p>The command's standard error is logged to {@link System#err}.</p>
   *
   * <p>Any non-zero exit value will result in an exception, including the standard error output when available.</p>
   */
  // TODO: First parameter as PosixPath object?
  @SuppressWarnings({"UseSpecificCatch", "TooBroadCatch", "UseOfSystemOutOrSystemErr", "overloads"})
  public static <V> V execCall(
      ConsumerE<? super OutputStream, ? extends IOException> stdin,
      FunctionE<? super InputStream, V, ? extends IOException> stdout,
      File workingDirectory,
      String... command
  ) throws IOException {
    if (DEBUG) {
      System.out.print("DEBUG: AoservDaemon.execCall(): ");
      System.out.println(getCommandString(command));
    }
    Process process = new ProcessBuilder(command).directory(workingDirectory).start();
    // Read and handle the standard output concurrently
    Future<V> outputFuture = executorService.submit(() -> {
      try (InputStream in = process.getInputStream()) {
        return stdout.apply(in);
      }
    });
    // Read the standard error concurrently
    Future<String> errorFuture = executorService.submit(() -> {
      try (Reader errIn = new InputStreamReader(process.getErrorStream())) {
        return IoUtils.readFully(errIn);
      }
    });
    // Write any output on the current thread
    Throwable t0;
    try {
      try (OutputStream out = process.getOutputStream()) {
        stdin.accept(out);
      }
      t0 = null;
    } catch (Throwable t) {
      t0 = t;
    }
    // Finish reading the standard input
    V result = null;
    try {
      try {
        result = outputFuture.get();
      } catch (ExecutionException e) {
        ExecutionExceptions.wrapAndThrow(e, IOException.class, IOException::new);
      }
    } catch (Throwable t) {
      t0 = Throwables.addSuppressed(t0, t);
    }
    // Finish reading the standard error
    String errorString = null;
    try {
      try {
        errorString = errorFuture.get();
        // Write any standard error to standard error
        if (!errorString.isEmpty()) {
          System.err.println("'" + getCommandString(command) + "': " + errorString);
        }
      } catch (ExecutionException e) {
        ExecutionExceptions.wrapAndThrow(e, IOException.class, IOException::new);
      }
    } catch (Throwable t) {
      t0 = Throwables.addSuppressed(t0, t);
    }
    try {
      // Wait for exit status
      try {
        int retCode = process.waitFor();
        if (retCode != 0) {
          if (errorString == null) {
            throw new IOException("Non-zero exit status from '" + getCommandString(command) + "': " + retCode + ", standard error unavailable");
          } else {
            throw new IOException("Non-zero exit status from '" + getCommandString(command) + "': " + retCode + ", standard error was: " + errorString);
          }
        }
      } catch (InterruptedException err) {
        InterruptedIOException ioErr = new InterruptedIOException("Interrupted while waiting for '" + getCommandString(command) + "'");
        ioErr.initCause(err);
        // Restore the interrupted status
        Thread.currentThread().interrupt();
        throw ioErr;
      }
    } catch (Throwable t) {
      t0 = Throwables.addSuppressed(t0, t);
    }
    if (t0 != null) {
      throw Throwables.wrap(t0, IOException.class, IOException::new);
    } else {
      return result;
    }
  }

  /**
   * Executes a command, performing any arbitrary action with the command's output stream.
   * Command's input is written on the current thread.
   * Command's output is read, and handled, on a different thread.
   * Command's error output is also read on a different thread.
   *
   * <p>The command's standard error is logged to {@link System#err}.</p>
   *
   * <p>Any non-zero exit value will result in an exception, including the standard error output when available.</p>
   */
  @SuppressWarnings("overloads")
  public static <V> V execCall(
      ConsumerE<? super OutputStream, ? extends IOException> stdin,
      FunctionE<? super InputStream, V, ? extends IOException> stdout,
      String... command
  ) throws IOException {
    return execCall(stdin, stdout, (File) null, command);
  }

  /**
   * Executes a command, performing any arbitrary action with the command's output stream.
   * Command's input is written on the current thread.
   * Command's output is read, and handled, on a different thread.
   * Command's error output is also read on a different thread.
   *
   * <p>The command's standard error is logged to {@link System#err}.</p>
   *
   * <p>Any non-zero exit value will result in an exception, including the standard error output when available.</p>
   */
  @SuppressWarnings("overloads")
  public static void execRun(
      ConsumerE<? super OutputStream, ? extends IOException> stdin,
      ConsumerE<? super InputStream, ? extends IOException> stdout,
      File workingDirectory,
      String... command
  ) throws IOException {
    execCall(
        stdin,
        myStdout -> {
          stdout.accept(myStdout);
          return null;
        },
        workingDirectory,
        command
    );
  }

  /**
   * Executes a command, performing any arbitrary action with the command's output stream.
   * Command's input is written on the current thread.
   * Command's output is read, and handled, on a different thread.
   * Command's error output is also read on a different thread.
   *
   * <p>The command's standard error is logged to {@link System#err}.</p>
   *
   * <p>Any non-zero exit value will result in an exception, including the standard error output when available.</p>
   */
  @SuppressWarnings("overloads")
  public static void execRun(
      ConsumerE<? super OutputStream, ? extends IOException> stdin,
      ConsumerE<? super InputStream, ? extends IOException> stdout,
      String... command
  ) throws IOException {
    execRun(stdin, stdout, (File) null, command);
  }

  /**
   * Executes a command, performing any arbitrary action with the command's output stream.
   * Command's input is opened then immediately closed.
   * Command's output is read, and handled, on the current thread.
   * Command's error output is read on a different thread.
   *
   * <p>The command's standard error is logged to {@link System#err}.</p>
   *
   * <p>Any non-zero exit value will result in an exception, including the standard error output when available.</p>
   */
  // TODO: First parameter as PosixPath object?
  @SuppressWarnings({"UseSpecificCatch", "TooBroadCatch", "UseOfSystemOutOrSystemErr", "overloads"})
  public static <V> V execCall(
      FunctionE<? super InputStream, V, ? extends IOException> stdout,
      File workingDirectory,
      String... command
  ) throws IOException {
    if (DEBUG) {
      System.out.print("DEBUG: AoservDaemon.execCall(): ");
      System.out.println(getCommandString(command));
    }
    Process process = new ProcessBuilder(command).directory(workingDirectory).start();
    // Read the standard error concurrently
    Future<String> errorFuture = executorService.submit(() -> {
      try (Reader errIn = new InputStreamReader(process.getErrorStream())) {
        return IoUtils.readFully(errIn);
      }
    });
    Throwable t0;
    // Close the process's stdin
    try {
      process.getOutputStream().close();
      t0 = null;
    } catch (Throwable t) {
      t0 = t;
    }
    // Read and handle the standard output on current thread
    V result = null;
    try {
      try (InputStream in = process.getInputStream()) {
        result = stdout.apply(in);
      }
    } catch (Throwable t) {
      t0 = Throwables.addSuppressed(t0, t);
    }
    // Finish reading the standard error
    String errorString = null;
    try {
      try {
        errorString = errorFuture.get();
        // Write any standard error to standard error
        if (!errorString.isEmpty()) {
          System.err.println("'" + getCommandString(command) + "': " + errorString);
        }
      } catch (ExecutionException e) {
        ExecutionExceptions.wrapAndThrow(e, IOException.class, IOException::new);
      }
    } catch (Throwable t) {
      t0 = Throwables.addSuppressed(t0, t);
    }
    try {
      // Wait for exit status
      try {
        int retCode = process.waitFor();
        if (retCode != 0) {
          if (errorString == null) {
            throw new IOException("Non-zero exit status from '" + getCommandString(command) + "': " + retCode + ", standard error unavailable");
          } else {
            throw new IOException("Non-zero exit status from '" + getCommandString(command) + "': " + retCode + ", standard error was: " + errorString);
          }
        }
      } catch (InterruptedException err) {
        InterruptedIOException ioErr = new InterruptedIOException("Interrupted while waiting for '" + getCommandString(command) + "'");
        ioErr.initCause(err);
        // Restore the interrupted status
        Thread.currentThread().interrupt();
        throw ioErr;
      }
    } catch (Throwable t) {
      t0 = Throwables.addSuppressed(t0, t);
    }
    if (t0 != null) {
      throw Throwables.wrap(t0, IOException.class, IOException::new);
    } else {
      return result;
    }
  }

  /**
   * Executes a command, performing any arbitrary action with the command's output stream.
   * Command's input is opened then immediately closed.
   * Command's output is read, and handled, on the current thread.
   * Command's error output is read on a different thread.
   *
   * <p>The command's standard error is logged to {@link System#err}.</p>
   *
   * <p>Any non-zero exit value will result in an exception, including the standard error output when available.</p>
   */
  @SuppressWarnings("overloads")
  public static <V> V execCall(
      FunctionE<? super InputStream, V, ? extends IOException> stdout,
      String... command
  ) throws IOException {
    return execCall(stdout, (File) null, command);
  }

  /**
   * Executes a command, performing any arbitrary action with the command's output stream.
   * Command's input is opened then immediately closed.
   * Command's output is read, and handled, on the current thread.
   * Command's error output is read on a different thread.
   *
   * <p>The command's standard error is logged to {@link System#err}.</p>
   *
   * <p>Any non-zero exit value will result in an exception, including the standard error output when available.</p>
   */
  @SuppressWarnings("overloads")
  public static void execRun(
      ConsumerE<? super InputStream, ? extends IOException> stdout,
      File workingDirectory,
      String... command
  ) throws IOException {
    execCall(
        myStdout -> {
          stdout.accept(myStdout);
          return null;
        },
        workingDirectory,
        command
    );
  }

  /**
   * Executes a command, performing any arbitrary action with the command's output stream.
   * Command's input is opened then immediately closed.
   * Command's output is read, and handled, on the current thread.
   * Command's error output is read on a different thread.
   *
   * <p>The command's standard error is logged to {@link System#err}.</p>
   *
   * <p>Any non-zero exit value will result in an exception, including the standard error output when available.</p>
   */
  @SuppressWarnings("overloads")
  public static void execRun(
      ConsumerE<? super InputStream, ? extends IOException> stdout,
      String... command
  ) throws IOException {
    execRun(stdout, (File) null, command);
  }

  /**
   * Executes a command, opens then immediately closes the command's input, and discards the output.
   *
   * @see  #execRun(com.aoapps.lang.function.ConsumerE, java.lang.String...)
   */
  public static void exec(File workingDirectory, String... command) throws IOException {
    execRun(
        stdout -> IoUtils.copy(stdout, NullOutputStream.getInstance()), // Do nothing with the output
        workingDirectory,
        command
    );
  }

  /**
   * Executes a command, opens then immediately closes the command's input, and discards the output.
   *
   * @see  #execRun(com.aoapps.lang.function.ConsumerE, java.lang.String...)
   */
  public static void exec(String... command) throws IOException {
    exec((File) null, command);
  }

  /**
   * Executes a command, opens then immediately closes the command's input, and captures the output.
   */
  public static String execAndCapture(File workingDirectory, String... command) throws IOException {
    return execCall(
        stdout -> {
          try (Reader in = new InputStreamReader(stdout)) {
            return IoUtils.readFully(in);
          }
        },
        workingDirectory,
        command
    );
  }

  /**
   * Executes a command, opens then immediately closes the command's input, and captures the output.
   */
  public static String execAndCapture(String... command) throws IOException {
    return execAndCapture((File) null, command);
  }

  /**
   * Executes a command, opens then immediately closes the command's input, and captures the output.
   */
  public static byte[] execAndCaptureBytes(File workingDirectory, String... command) throws IOException {
    return execCall(IoUtils::readFully, workingDirectory, command);
  }

  /**
   * Executes a command, opens then immediately closes the command's input, and captures the output.
   */
  public static byte[] execAndCaptureBytes(String... command) throws IOException {
    return execAndCaptureBytes((File) null, command);
  }

  /**
   * Switches to the specified user and executes a command.
   *
   * @param  nice  a nice level passed to /bin/nice, a value of zero (0) will cause nice to not be called
   */
  // TODO: Use ao-encoding to escape command
  public static void suexec(User.Name username, File workingDirectory, String command, int nice) throws IOException {
    /*
     * Not needed because command is passed as String[] and any funny stuff will
     * be executed as the proper user.
    if (command == null) {
      throw new IllegalArgumentException("command is null");
    }
    int len = command.length();
    if (len == 0) {
      throw new IllegalArgumentException("command is empty");
    }
    for (int c=0;c<len;c++) {
      char ch = command.charAt(c);
      if (
        (ch<'a' || ch>'z')
        && (ch<'A' || ch>'Z')
        && (ch<'0' || ch>'9')
        && ch != ' '
        && ch != '-'
        && ch != '_'
        && ch != '.'
        && ch != '/'
      ) {
        throw new IllegalArgumentException("Invalid command character: "+ch);
      }
    }*/

    String[] cmd;
    if (nice != 0) {
      cmd = new String[]{
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
      cmd = new String[]{
          "/bin/su",
          "-s",
          Shell.BASH.toString(),
          "-c",
          command,
          username.toString()
      };
    }
    exec(workingDirectory, cmd);
  }
}
