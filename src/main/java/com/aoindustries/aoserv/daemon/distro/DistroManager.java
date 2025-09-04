/*
 * aoserv-daemon - Server management daemon for the AOServ Platform.
 * Copyright (C) 2001-2013, 2015, 2016, 2017, 2018, 2019, 2020, 2021, 2022, 2025  AO Industries, Inc.
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

package com.aoindustries.aoserv.daemon.distro;

import com.aoapps.hodgepodge.io.ByteCountInputStream;
import com.aoapps.hodgepodge.logging.ProcessTimer;
import com.aoapps.hodgepodge.util.Tuple2;
import com.aoapps.io.posix.PosixFile;
import com.aoapps.io.posix.Stat;
import com.aoapps.lang.Strings;
import com.aoapps.lang.SysExits;
import com.aoapps.lang.io.IoUtils;
import com.aoapps.lang.util.ErrorPrinter;
import com.aoapps.lang.validation.ValidationException;
import com.aoindustries.aoserv.client.AoservConnector;
import com.aoindustries.aoserv.client.AoservTable;
import com.aoindustries.aoserv.client.distribution.management.DistroFile;
import com.aoindustries.aoserv.client.distribution.management.DistroFileTable;
import com.aoindustries.aoserv.client.distribution.management.DistroFileType;
import com.aoindustries.aoserv.client.distribution.management.DistroReportType;
import com.aoindustries.aoserv.client.linux.Group;
import com.aoindustries.aoserv.client.linux.GroupServer;
import com.aoindustries.aoserv.client.linux.LinuxId;
import com.aoindustries.aoserv.client.linux.PosixPath;
import com.aoindustries.aoserv.client.linux.Server;
import com.aoindustries.aoserv.client.linux.User;
import com.aoindustries.aoserv.client.linux.UserServer;
import com.aoindustries.aoserv.client.sql.SqlColumnValue;
import com.aoindustries.aoserv.client.sql.SqlComparator;
import com.aoindustries.aoserv.client.sql.SqlExpression;
import com.aoindustries.aoserv.daemon.AoservDaemon;
import com.aoindustries.aoserv.daemon.AoservDaemonConfiguration;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintStream;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.IntStream;

/**
 * Verifies the server distribution.
 *
 * @author  AO Industries, Inc.
 */
public final class DistroManager implements Runnable {

  private static final Logger logger = Logger.getLogger(DistroManager.class.getName());

  /**
   * The number of entries in a directory that will be reported as a warning.
   */
  private static final int DIRECTORY_LENGTH_WARNING = 100000;

  /**
   * The amount of time to sleep before running the distro scan.
   */
  private static final int PRERUN_DELAY = 10 * 60 * 1000; // 10 minutes

  private static final int MAX_SLEEP_TIME = 5 * 60 * 1000; // 5 minutes

  private static Thread thread;

  private static final String EOL = System.lineSeparator();

  public static void startDistro(boolean includeUser) {
    DistroManager.includeUser = includeUser;
    Thread t = thread;
    if (t != null) {
      runNow = true;
      synchronized (sleepLock) {
        sleepLock.notify(); // notifyAll() not needed: only one thread
      }
    }
  }

  private DistroManager() {
    // Do nothing
  }

  @SuppressWarnings("UseOfSystemOutOrSystemErr")
  public static void start() {
    if (
        AoservDaemonConfiguration.isManagerEnabled(DistroManager.class)
            && thread == null
    ) {
      synchronized (System.out) {
        if (thread == null) {
          System.out.print("Starting DistroManager: ");
          thread = new Thread(new DistroManager());
          thread.setDaemon(true);
          thread.start();
          System.out.println("Done");
        }
      }
    }
  }

  private static final Object sleepLock = new Object();
  private static volatile boolean includeUser;
  private static volatile boolean runNow;

  @Override
  public void run() {
    while (!Thread.currentThread().isInterrupted()) {
      try {
        // Wait before checking again
        includeUser = true;
        runNow = false;
        try {
          synchronized (sleepLock) {
            sleepLock.wait(PRERUN_DELAY);
          }
        } catch (InterruptedException err) {
          // Restore the interrupted status
          Thread.currentThread().interrupt();
          break;
        }

        // It is time to run if it is the backup hour and the backup has not been run for at least 12 hours
        Server thisServer = AoservDaemon.getThisServer();
        long distroStartTime = System.currentTimeMillis();
        Timestamp lastDistroTime = thisServer.getLastDistroTime();
        boolean isFiner = logger.isLoggable(Level.FINER);
        if (isFiner) {
          logger.finer("runNow=" + runNow);
          logger.finer("distroStartTime=" + distroStartTime);
          logger.finer("lastDistroTime=" + lastDistroTime);
        }
        if (
            runNow
                || lastDistroTime == null
                // Last time in the future, assume system time changed and run now
                || lastDistroTime.getTime() > distroStartTime
                // Has been at least 12 hours since the last run
                || (distroStartTime - lastDistroTime.getTime()) >= 12L * 60 * 60 * 1000
        ) {
          int distroHour = thisServer.getDistroHour();
          GregorianCalendar gcal = new GregorianCalendar();
          gcal.setTimeInMillis(distroStartTime);
          int currentHour = gcal.get(Calendar.HOUR_OF_DAY);
          if (isFiner) {
            logger.finer("distroHour=" + distroHour);
            logger.finer("currentHour=" + currentHour);
          }
          if (runNow || currentHour == distroHour) {
            try (
                ProcessTimer timer = new ProcessTimer(
                    logger,
                    DistroManager.class.getName(),
                    "run",
                    "Distro verification taking too long",
                    "Distro Verification",
                    12L * 60 * 60 * 1000, // 12 hours
                    60L * 60 * 1000 // 1 hour
                )
                ) {
              AoservDaemon.executorService.submit(timer);

              AoservDaemon.getThisServer().setLastDistroTime(new Timestamp(distroStartTime));

              DistroReportStats stats = new DistroReportStats();
              List<DistroReportFile> results = checkFilesystem(stats, null);

              // Report the results to the master server
              // TODO: Implement

              // Compile the counters for each of the different codes
              /*
              HashMap codes = new HashMap();
              for (int c = 0; c < size; c++) {
                String code = results.get(c).substring(0, 2);
                int[] count = (int[])codes.get(code);
                if (count == null) {
                  codes.put(code, count  =new int[1]);
                }
                count[0]++;
              }*/
            }
          }
        }
      } catch (ThreadDeath td) {
        throw td;
      } catch (Throwable t) {
        logger.log(Level.SEVERE, null, t);
        try {
          Thread.sleep(MAX_SLEEP_TIME);
        } catch (InterruptedException err) {
          logger.log(Level.WARNING, null, err);
          // Restore the interrupted status
          Thread.currentThread().interrupt();
        }
      }
    }
  }

  private static class DistroReportStats {

    /**
     * The time the scan started.
     */
    private long startTime;

    /**
     * The time the scan ended.
     */
    private long endTime;

    /**
     * Total number of objects checked.
     */
    private long scanned;

    /**
     * Total number of system objects checked.
     */
    private long systemCount;

    /**
     * Total number of user objects checked.
     */
    private long userCount;

    /**
     * Total number of non-recurse directories.
     */
    private long noRecurseCount;

    /**
     * Total number of files PRELINK verified.
     */
    private long prelinkFiles;

    /**
     * Total number of bytes PRELINK verified.
     */
    private long prelinkBytes;

    /**
     * Total number of files SHA-256 verified.
     */
    private long sha256Files;

    /**
     * Total number of bytes SHA-256 verified.
     */
    private long sha256Bytes;
  }

  private static class DistroReportFile {

    private final String type;
    private final PosixPath path;
    private final String actualValue;
    private final String expectedValue;
    private final String recommendedAction;

    private DistroReportFile(String type, PosixPath path, String actualValue, String expectedValue, String recommendedAction) {
      this.type = type;
      this.path = path;
      this.actualValue = actualValue;
      this.expectedValue = expectedValue;
      this.recommendedAction = recommendedAction;
    }
  }

  /**
   * Writes a value, surrounding by quotes if it contains any spaces.
   *
   * @param value  the value to print, nothing is output when null
   */
  private static void writeQuoteIfNeeded(String value, Appendable out) throws IOException {
    if (value != null) {
      boolean hasSpace = value.indexOf(' ') != -1;
      if (hasSpace) {
        out.append('\'');
      }
      out.append(value);
      if (hasSpace) {
        out.append('\'');
      }
    }
  }

  /**
   * Adds a report line, displaying to provided error printer if non-null.
   */
  private static void addResult(
      List<DistroReportFile> results,
      Appendable verboseOut,
      String type,
      PosixPath path,
      String actualValue,
      String expectedValue,
      String recommendedAction
  ) throws IOException {
    DistroReportFile report = new DistroReportFile(type, path, actualValue, expectedValue, recommendedAction);
    results.add(report);
    if (verboseOut != null) {
      if (recommendedAction != null) {
        verboseOut.append(recommendedAction);
      } else {
        verboseOut.append(type);
        verboseOut.append(' ');
        writeQuoteIfNeeded(path.toString(), verboseOut);
      }
      if (actualValue != null || expectedValue != null) {
        verboseOut.append(" # ");
        writeQuoteIfNeeded(actualValue, verboseOut);
        if (expectedValue != null) {
          verboseOut.append(" != ");
          writeQuoteIfNeeded(expectedValue, verboseOut);
        }
      }
      verboseOut.append(EOL);
      // verboseOut.flush();
    }
  }

  private static void addResult(List<DistroReportFile> results, Appendable verboseOut, String type, PosixPath path, String actualValue, String expectedValue) throws IOException {
    addResult(results, verboseOut, type, path, actualValue, expectedValue, null);
  }

  private static void addResult(List<DistroReportFile> results, Appendable verboseOut, String type, PosixPath path) throws IOException {
    addResult(results, verboseOut, type, path, null, null, null);
  }

  private static void addResult(
      List<DistroReportFile> results,
      Appendable verboseOut,
      String type,
      PosixFile file,
      String actualValue,
      String expectedValue,
      String recommendedAction
  ) throws IOException {
    try {
      addResult(results, verboseOut, type, PosixPath.valueOf(file.getPath()), actualValue, expectedValue, recommendedAction);
    } catch (ValidationException e) {
      throw new IOException(e);
    }
  }

  private static void addResult(List<DistroReportFile> results, Appendable verboseOut, String type, PosixFile file, String actualValue, String expectedValue) throws IOException {
    try {
      addResult(results, verboseOut, type, PosixPath.valueOf(file.getPath()), actualValue, expectedValue, null);
    } catch (ValidationException e) {
      throw new IOException(e);
    }
  }

  private static void addResult(List<DistroReportFile> results, Appendable verboseOut, String type, PosixFile file) throws IOException {
    try {
      addResult(results, verboseOut, type, PosixPath.valueOf(file.getPath()), null, null, null);
    } catch (ValidationException e) {
      throw new IOException(e);
    }
  }

  private static List<DistroReportFile> checkFilesystem(DistroReportStats stats, Appendable verboseOut) throws ValidationException, IOException, SQLException {
    stats.startTime = System.currentTimeMillis();
    try {
      // Build the list of files that should exist
      AoservConnector conn = AoservDaemon.getConnector();
      DistroFileTable distroFileTable = conn.getDistribution_management().getDistroFile();
      // Getting this list provides a single, immutable, consistent snap-shot of the information
      List<DistroFile> distroFiles = distroFileTable.getRows();
      boolean[] foundFiles = new boolean[distroFiles.size()];

      // The comparator used for the searches
      SqlComparator<Object> pathComparator = new SqlComparator<>(
          conn,
          new SqlExpression[]{
              new SqlColumnValue(conn, distroFileTable.getTableSchema().getSchemaColumn(conn, DistroFile.COLUMN_PATH)),
              new SqlColumnValue(conn, distroFileTable.getTableSchema().getSchemaColumn(conn, DistroFile.COLUMN_OPERATING_SYSTEM_VERSION))
          },
          new boolean[]{AoservTable.ASCENDING, AoservTable.ASCENDING}
      );

      // Verify all the files, from the root to the lowest directory, accumulating the results in the results List
      List<DistroReportFile> results = new ArrayList<>();
      checkDistroFile(
          AoservDaemon.getThisServer(),
          AoservDaemon.getThisServer().getHost().getOperatingSystemVersion().getPkey(),
          MessageDigestUtils.getSha256(),
          distroFiles,
          foundFiles,
          pathComparator,
          new PosixFile("/"),
          results,
          stats,
          verboseOut
      );

      // Add entries for all the missing files
      PosixPath lastPath = null;
      int size = foundFiles.length;
      for (int c = 0; c < size; c++) {
        if (!foundFiles[c]) {
          DistroFile distroFile = distroFiles.get(c);
          if (!distroFile.isOptional()) {
            PosixPath path = distroFile.getPath();
            if (
                lastPath == null
                    || !path.toString().startsWith(lastPath.toString() + '/') // TODO: Why startsWith here, why did not add '/' before?
            ) {
              addResult(results, verboseOut, DistroReportType.MISSING, path);
              lastPath = path;
            }
          }
        }
      }
      if (stats.scanned != (stats.systemCount + stats.userCount + stats.noRecurseCount)) {
        throw new AssertionError();
      }
      return results;
    } finally {
      stats.endTime = System.currentTimeMillis();
    }
  }

  /**
   * BIG_DIRECTORY Big Directory
   * DIGEST_MISMATCH DIGEST
   * EX Extra
   * GROUP_MISMATCH Group Mismatch
   * HIDDEN Hidden "..." or " " directory
   * LENGTH Length
   * MISSING Missing
   * OWNER_MISMATCH Owner Mismatch
   * NO_OWNER No Owner
   * NO_GROUP No Group
   * PERMISSIONS Permissions
   * SETUID User SetUID
   * SYMLINK Symlink
   * TYPE Type
   */
  // @SuppressWarnings({"unchecked"})
  private static void checkDistroFile(
      Server thisServer,
      Integer osVersionPkey,
      MessageDigest digest,
      List<DistroFile> distroFiles,
      boolean[] foundFiles,
      SqlComparator<Object> pathComparator,
      PosixFile file,
      List<DistroReportFile> results,
      DistroReportStats stats,
      Appendable verboseOut
  ) throws ValidationException, IOException, SQLException {
    stats.scanned++;
    stats.systemCount++;
    // Check for ... and other patterns baddies use to hide directories
    String name = file.getFile().getName();
    if (isHidden(name)) {
      addResult(
          results,
          verboseOut,
          DistroReportType.HIDDEN,
          file
      );
    }
    // Find the matching DistroFile
    DistroFile distroFile;
    {
      // First look for exact match
      String filename = file.getPath();
      int index = Collections.binarySearch(distroFiles, new Object[]{PosixPath.valueOf(filename), osVersionPkey}, pathComparator);
      if (index >= 0) {
        distroFile = distroFiles.get(index);
        // Flag as found
        foundFiles[index] = true;
      } else {
        // Check for hostname substitution
        String hostname = thisServer.getHostname().toString();
        int pos = filename.indexOf(hostname);
        if (pos >= 0) {
          filename = filename.substring(0, pos) + "$h" + filename.substring(pos + hostname.length());
          index = Collections.binarySearch(distroFiles, new Object[]{PosixPath.valueOf(filename), osVersionPkey}, pathComparator);
          if (index >= 0) {
            distroFile = distroFiles.get(index);
            // Flag as found
            foundFiles[index] = true;
          } else {
            distroFile = null;
          }
        } else {
          distroFile = null;
        }
      }
    }

    // Stat here for use below
    Stat fileStat = file.getStat();

    if (distroFile == null) {
      // Should not be here
      addResult(
          results,
          verboseOut,
          DistroReportType.EXTRA,
          file,
          null,
          null,
          "rm '" + file + "' # " + (fileStat.isDirectory() ? "-rf" : "-f")
      );
    } else {
      // Check owner
      int fileUid = fileStat.getUid();
      User la = distroFile.getLinuxAccount();
      UserServer lsa = la.getLinuxServerAccount(thisServer);
      if (lsa == null) {
        throw new SQLException("Unable to find UserServer for " + la + " on " + thisServer + ", path=" + file);
      }
      int distroUid = lsa.getUid().getId();
      if (fileUid != distroUid) {
        addResult(
            results,
            verboseOut,
            DistroReportType.OWNER_MISMATCH,
            file,
            Integer.toString(fileUid),
            Integer.toString(distroUid),
            "chown " + distroUid + " '" + file + '\''
        );
      }

      // Check group
      int fileGid = fileStat.getGid();
      Group lg = distroFile.getLinuxGroup();
      GroupServer lsg = lg.getLinuxServerGroup(thisServer);
      if (lsg == null) {
        throw new SQLException("Unable to find GroupServer for " + lg + " on " + thisServer + ", path=" + file);
      }
      int distroGid = lsg.getGid().getId();
      if (fileGid != distroGid) {
        addResult(
            results,
            verboseOut,
            DistroReportType.GROUP_MISMATCH,
            file,
            Integer.toString(fileGid),
            Integer.toString(distroGid),
            "chgrp " + distroGid + " '" + file + '\''
        );
      }

      // Type
      long fileMode = fileStat.getRawMode();
      long distroMode = distroFile.getMode();
      long fileType = fileMode & PosixFile.TYPE_MASK;
      long distroType = distroMode & PosixFile.TYPE_MASK;
      if (fileType != distroType) {
        addResult(
            results,
            verboseOut,
            DistroReportType.TYPE,
            file,
            PosixFile.getModeString(fileType),
            PosixFile.getModeString(distroType)
        );
      } else {
        // Permissions
        long filePerms = fileMode & PosixFile.PERMISSION_MASK;
        long distroPerms = distroMode & PosixFile.PERMISSION_MASK;
        if (filePerms != distroPerms) {
          addResult(
              results,
              verboseOut,
              DistroReportType.PERMISSIONS,
              file,
              Long.toOctalString(filePerms),
              Long.toOctalString(distroPerms),
              "chmod " + Long.toOctalString(distroPerms) + " '" + file + '\''
          );
        }
      }

      // Symlinks
      if (fileStat.isSymLink()) {
        String distroLink = distroFile.getSymlinkTarget();
        if (distroLink != null) {
          String fileLink = file.readLink();
          // Allow multiple destinations separated by |
          if (!Strings.split(distroLink, '|').contains(fileLink)) {
            addResult(
                results,
                verboseOut,
                DistroReportType.SYMLINK,
                file,
                fileLink,
                distroLink,
                "rm -f '" + file + "'; ln -s '" + distroLink + "' '" + file + '\''
            );
          }
        }
      } else {
        if (
            !fileStat.isBlockDevice()
                && !fileStat.isCharacterDevice()
                && !fileStat.isFifo()
                && !fileStat.isSocket()
        ) {
          String type = distroFile.getType().getType();
          if (!fileStat.isDirectory()) {
            if (!type.equals(DistroFileType.CONFIG)) {
              if (type.equals(DistroFileType.PRELINK)) {
                final long startTime = System.currentTimeMillis();

                // Use prelink --verify to get original file length and digest
                byte[] sha256;
                long fileLen;
                {
                  Tuple2<byte[], Long> result = AoservDaemon.execCall(
                      stdout -> {
                        try (ByteCountInputStream countIn = new ByteCountInputStream(stdout)) {
                          return new Tuple2<>(
                              MessageDigestUtils.hashInput(digest, countIn),
                              // Use length of unprelinked file
                              countIn.getCount()
                          );
                        }
                      },
                      "/usr/sbin/prelink",
                      "--verify",
                      file.getPath()
                  );
                  sha256 = result.getElement1();
                  fileLen = result.getElement2();
                  if (sha256.length != 32) {
                    throw new AssertionError();
                  }
                }

                // Prelink MD5
                stats.prelinkFiles++;
                stats.prelinkBytes += file.getFile().length(); // Raw file length
                stats.sha256Files++;
                stats.sha256Bytes += fileLen; // Prelink verified length

                // Length
                long distroLen = distroFile.getSize();
                if (fileLen != distroLen) {
                  addResult(
                      results,
                      verboseOut,
                      DistroReportType.LENGTH,
                      file,
                      Long.toString(fileLen),
                      Long.toString(distroLen)
                  );
                } else {
                  long fileSha0 = IoUtils.bufferToLong(sha256);
                  long fileSha1 = IoUtils.bufferToLong(sha256, 8);
                  long fileSha2 = IoUtils.bufferToLong(sha256, 16);
                  long fileSha3 = IoUtils.bufferToLong(sha256, 24);
                  long distroSha0 = distroFile.getFileSha256_0();
                  long distroSha1 = distroFile.getFileSha256_1();
                  long distroSha2 = distroFile.getFileSha256_2();
                  long distroSha3 = distroFile.getFileSha256_3();
                  if (
                      fileSha0 != distroSha0
                          || fileSha1 != distroSha1
                          || fileSha2 != distroSha2
                          || fileSha3 != distroSha3
                  ) {
                    addResult(
                        results,
                        verboseOut,
                        DistroReportType.DIGEST,
                        file,
                        MessageDigestUtils.getHexChars(fileSha0, fileSha1, fileSha2, fileSha3),
                        MessageDigestUtils.getHexChars(distroSha0, distroSha1, distroSha2, distroSha3)
                    );
                  }
                }

                // Sleep for an amount of time equivalent to half the time it took to process this file
                long timeSpan = (System.currentTimeMillis() - startTime) / 2;
                if (timeSpan < 0) {
                  timeSpan = 0;
                } else if (timeSpan > MAX_SLEEP_TIME) {
                  timeSpan = MAX_SLEEP_TIME;
                }
                if (timeSpan != 0) {
                  try {
                    Thread.sleep(timeSpan);
                  } catch (InterruptedException err) {
                    logger.log(Level.WARNING, null, err);
                    // Restore the interrupted status
                    Thread.currentThread().interrupt();
                  }
                }
              } else if (type.equals(DistroFileType.SYSTEM)) {
                // Length
                long fileLen = file.getFile().length();
                long distroLen = distroFile.getSize();
                if (fileLen != distroLen) {
                  addResult(
                      results,
                      verboseOut,
                      DistroReportType.LENGTH,
                      file,
                      Long.toString(fileLen),
                      Long.toString(distroLen)
                  );
                } else {
                  // SHA-256
                  final long startTime = System.currentTimeMillis();

                  byte[] sha256;
                  try (ByteCountInputStream in = new ByteCountInputStream(new FileInputStream(file.getFile()))) {
                    sha256 = MessageDigestUtils.hashInput(digest, in);
                    // Make sure expected number of bytes read
                    long readLen = in.getCount();
                    if (readLen != fileLen) {
                      throw new IOException("readLen != fileLen: " + readLen + " != " + fileLen);
                    }
                  }
                  if (sha256.length != 32) {
                    throw new AssertionError();
                  }

                  stats.sha256Files++;
                  stats.sha256Bytes += fileLen;

                  long fileSha0 = IoUtils.bufferToLong(sha256);
                  long fileSha1 = IoUtils.bufferToLong(sha256, 8);
                  long fileSha2 = IoUtils.bufferToLong(sha256, 16);
                  long fileSha3 = IoUtils.bufferToLong(sha256, 24);
                  long distroSha0 = distroFile.getFileSha256_0();
                  long distroSha1 = distroFile.getFileSha256_1();
                  long distroSha2 = distroFile.getFileSha256_2();
                  long distroSha3 = distroFile.getFileSha256_3();
                  if (
                      fileSha0 != distroSha0
                          || fileSha1 != distroSha1
                          || fileSha2 != distroSha2
                          || fileSha3 != distroSha3
                  ) {
                    addResult(
                        results,
                        verboseOut,
                        DistroReportType.DIGEST,
                        file,
                        MessageDigestUtils.getHexChars(fileSha0, fileSha1, fileSha2, fileSha3),
                        MessageDigestUtils.getHexChars(distroSha0, distroSha1, distroSha2, distroSha3)
                    );
                  }

                  // Sleep for an amount of time equivalent to half the time it took to process this file
                  long timeSpan = (System.currentTimeMillis() - startTime) / 2;
                  if (timeSpan < 0) {
                    timeSpan = 0;
                  } else if (timeSpan > MAX_SLEEP_TIME) {
                    timeSpan = MAX_SLEEP_TIME;
                  }
                  if (timeSpan != 0) {
                    try {
                      Thread.sleep(timeSpan);
                    } catch (InterruptedException err) {
                      logger.log(Level.WARNING, null, err);
                      // Restore the interrupted status
                      Thread.currentThread().interrupt();
                    }
                  }
                }
              } else {
                throw new RuntimeException("Unexpected value for type: " + type);
              }
            }
          } else {
            if (type.equals(DistroFileType.USER)) {
              // Check as user directory
              stats.systemCount--;
              if (includeUser) {
                stats.userCount++;
                checkUserDirectory(thisServer, file, results, stats, verboseOut);
              } else {
                stats.noRecurseCount++;
              }
            } else {
              if (type.equals(DistroFileType.NO_RECURSE)) {
                stats.systemCount--;
                stats.noRecurseCount++;
              } else {
                // Recurse directory
                String[] list = file.list();
                if (list != null) {
                  Arrays.sort(list);
                  int len = list.length;
                  if (len >= DIRECTORY_LENGTH_WARNING) {
                    addResult(
                        results,
                        verboseOut,
                        DistroReportType.BIG_DIRECTORY,
                        file,
                        null,
                        null,
                        len + " >= " + DIRECTORY_LENGTH_WARNING
                    );
                  }
                  for (int c = 0; c < len; c++) {
                    checkDistroFile(
                        thisServer,
                        osVersionPkey,
                        digest,
                        distroFiles,
                        foundFiles,
                        pathComparator,
                        new PosixFile(file, list[c], false),
                        results,
                        stats,
                        verboseOut
                    );
                  }
                }
              }
            }
          }
        }
      }
    }
  }

  private static void checkUserDirectory(
      Server thisServer,
      PosixFile file,
      List<DistroReportFile> results,
      DistroReportStats stats,
      Appendable verboseOut
  ) throws IOException, SQLException {
    String[] list = file.list();
    if (list != null) {
      Arrays.sort(list);
      int len = list.length;
      if (len >= DIRECTORY_LENGTH_WARNING) {
        addResult(
            results,
            verboseOut,
            DistroReportType.BIG_DIRECTORY,
            file,
            null,
            null,
            len + " >= " + DIRECTORY_LENGTH_WARNING
        );
      }
      for (int c = 0; c < len; c++) {
        try {
          stats.scanned++;
          stats.userCount++;
          String name = list[c];
          PosixFile uf = new PosixFile(file, name, false);
          try {
            // Check for ...
            if (isHidden(name)) {
              addResult(
                  results,
                  verboseOut,
                  DistroReportType.HIDDEN,
                  uf
              );
            }

            // Stat here for use below
            Stat ufStat = uf.getStat();

            // Make sure is a valid user
            int uid = ufStat.getUid();
            if (thisServer.getLinuxServerAccount(LinuxId.valueOf(uid)) == null) {
              addResult(
                  results,
                  verboseOut,
                  DistroReportType.NO_OWNER,
                  uf,
                  Integer.toString(uid),
                  null
              );
            }

            // Make sure is a valid group
            int gid = ufStat.getGid();
            if (thisServer.getLinuxServerGroup(LinuxId.valueOf(gid)) == null) {
              addResult(
                  results,
                  verboseOut,
                  DistroReportType.NO_GROUP,
                  uf,
                  Integer.toString(gid),
                  null
              );
            }

            // Make sure not setUID or setGID
            long fileMode = ufStat.getMode();
            if (
                (fileMode & (PosixFile.SET_UID | PosixFile.SET_GID)) != 0
                    && (
                    uid < thisServer.getUidMin().getId()
                        || gid < thisServer.getGidMin().getId()
                  )
            ) {
              // Allow setUID for /etc/mail/majordomo/*/wrapper 4750 root.mail
              final String majordomoPath = "/etc/mail/majordomo/";
              boolean found = false;
              String filename = uf.getPath();
              if (filename.startsWith(majordomoPath)) {
                int pos = filename.indexOf('/', majordomoPath.length());
                if (pos != -1) {
                  String fname = filename.substring(pos + 1);
                  if (
                      "wrapper".equals(fname)
                          && fileMode == 04750
                          && ufStat.getUid() == PosixFile.ROOT_UID
                          && thisServer.getLinuxServerGroup(LinuxId.valueOf(ufStat.getGid())).getLinuxGroup().getName().equals(Group.MAIL)
                  ) {
                    found = true;
                  }
                }
              }
              if (!found) {
                addResult(
                    results,
                    verboseOut,
                    DistroReportType.SETUID,
                    uf,
                    Long.toOctalString(fileMode),
                    null
                );
              }
            }

            // Make sure not world writable
            // if ((fileMode&PosixFile.OTHER_WRITE) == PosixFile.OTHER_WRITE) {
            //     results.add("PERMISSIONS "+uf+" "+Integer.toOctalString(fileMode));
            //     if (displayResults) {
            //         System.out.println(results.get(results.size()-1));
            //         System.out.flush();
            //     }
            // }

            // Recurse
            if (!ufStat.isSymLink() && ufStat.isDirectory()) {
              if (includeUser) {
                checkUserDirectory(thisServer, uf, results, stats, verboseOut);
              } else {
                stats.userCount--;
                stats.noRecurseCount++;
              }
            }
          } catch (ValidationException e) {
            throw new IOException(e);
          } catch (RuntimeException err) {
            if (logger.isLoggable(Level.SEVERE)) {
              logger.severe("RuntimeException while accessing: " + uf);
            }
            throw err;
          }
        } catch (FileNotFoundException err) {
          // File might be removed during the scan
        }
      }
    }
  }

  /**
   * Checks if a filename is all spaces.
   */
  private static boolean allSpace(String name) {
    if (name.isEmpty()) {
      return false;
    }
    try (IntStream codePoints = name.codePoints()) {
      for (int ch : codePoints.toArray()) {
        if (
            ch > ' '
                && !Character.isSpaceChar(ch)
                && !Character.isWhitespace(ch)
        ) {
          return false;
        }
      }
      return true;
    }
  }

  /**
   * Determines if a filename possibly represents maliciously hidden content.
   */
  private static boolean isHidden(String name) {
    int ch;
    return
        name.startsWith("...")
            || (
            name.startsWith("..")
                && (
                (ch = name.codePointAt(2)) <= ' '
                    || Character.isSpaceChar(ch)
                    || Character.isWhitespace(ch)
            )
        ) || allSpace(name);
  }

  /**
   * Runs a scan immediately and displays the output to <code>System.out</code>.
   * This calls System.exit to provide non-zero return value on exception.
   */
  public static void main(String[] args) {
    int retVal = SysExits.EX_OK;
    DistroReportStats stats = new DistroReportStats();
    PrintStream out = System.out;
    try {
      try {
        /*List<DistroReportFile> results =*/ checkFilesystem(stats, out);
      } finally {
        out.flush();
      }
    } catch (IOException | ValidationException exc) {
      ErrorPrinter.printStackTraces(exc, System.err);
      System.err.flush();
      retVal = SysExits.EX_IOERR;
    } catch (SQLException exc) {
      ErrorPrinter.printStackTraces(exc, System.err);
      System.err.flush();
      retVal = SysExits.EX_DATAERR;
    } finally {
      out.println("Time");
      out.print("  Start.....: ");
      out.println(new Date(stats.startTime).toString());
      out.print("  End.......: ");
      out.println(new Date(stats.endTime).toString());
      out.print("  Duration..: ");
      out.println(Strings.getDecimalTimeLengthString(stats.endTime - stats.startTime));
      out.println("Scan");
      out.print("  Total.....: ");
      out.println(stats.scanned);
      out.print("  System....: ");
      out.println(stats.systemCount);
      out.print("  User......: ");
      out.println(stats.userCount);
      out.print("  No Recurse: ");
      out.println(stats.noRecurseCount);
      out.println("Prelink Verify");
      out.print("  Files.....: ");
      out.println(stats.prelinkFiles);
      out.print("  Bytes.....: ");
      out.print(stats.prelinkBytes);
      out.print(" (");
      out.print(Strings.getApproximateSize(stats.prelinkBytes));
      out.println(')');
      out.println("SHA-256");
      out.print("  Files.....: ");
      out.println(stats.sha256Files);
      out.print("  Bytes.....: ");
      out.print(stats.sha256Bytes);
      out.print(" (");
      out.print(Strings.getApproximateSize(stats.sha256Bytes));
      out.println(')');
    }
    if (retVal != 0) {
      System.exit(retVal);
    }
  }
}
