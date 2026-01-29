/*
 * aoserv-daemon - Server management daemon for the AOServ Platform.
 * Copyright (C) 2001-2009, 2016, 2017, 2018, 2019, 2020, 2021, 2022  AO Industries, Inc.
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

package com.aoindustries.aoserv.daemon.backup;

import com.aoapps.io.posix.PosixFile;
import com.aoapps.lang.Strings;
import com.aoindustries.aoserv.client.linux.PosixPath;
import com.aoindustries.aoserv.client.linux.Server;
import com.aoindustries.aoserv.daemon.AoservDaemon;
import com.aoindustries.aoserv.daemon.posix.linux.PackageManager;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.sql.SQLException;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The BackupManager handles the storing and retrieval of backup data from
 * the backup partitions.  It also keeps the <code>/var/opt/aoserv-daemon/oldaccounts</code>
 * directory clean.
 *
 * @author  AO Industries, Inc.
 */
public final class BackupManager {

  private static final Logger logger = Logger.getLogger(BackupManager.class.getName());

  /**
   * The directory that old files are backed-up to.  This thread cleans up
   * the old backups.
   */
  private static final String OLDACCOUNTS_DIR = "/var/opt/aoserv-daemon/oldaccounts";

  /**
   * Gets the oldaccounts directory, creating if necessary.
   */
  private static PosixFile getOldaccountsDir() throws IOException {
    PosixFile oldaccountsDir = new PosixFile(OLDACCOUNTS_DIR);
    if (!oldaccountsDir.getStat().exists()) {
      oldaccountsDir.mkdir(false, 0700);
    }
    return oldaccountsDir;
  }

  /**
   * The maximum age of files in the /var/opt/aoserv-daemon/oldaccounts directory.
   */
  private static final long MAX_OLDACCOUNTS_AGE = 7L * 24 * 60 * 60 * 1000; // One week

  private BackupManager() {
    // Do nothing
  }

  /**
   * Backs-up and then deletes the provided files.
   */
  public static void backupAndDeleteFiles(List<File> deleteFileList) throws IOException, SQLException {
    if (!deleteFileList.isEmpty()) {
      // Get the next backup filename
      File backupFile = getNextTarballBackupFile();
      // Backup
      createTarball(deleteFileList, backupFile);
      // Remove the files that have been backed up.
      Server thisServer = AoservDaemon.getThisServer();
      int uidMin = thisServer.getUidMin().getId();
      int gidMin = thisServer.getGidMin().getId();
      for (File file : deleteFileList) {
        new PosixFile(file).secureDeleteRecursive(uidMin, gidMin);
      }
    }
  }

  /**
   * Makes a tarball of the provided files into the provided file.
   */
  public static void createTarball(List<File> files, File backupFile) throws IOException {
    if (files.isEmpty()) {
      throw new IllegalArgumentException("files.size() = " + files.size());
    }
    PackageManager.installPackages(
        PackageManager.PackageName.TAR,
        PackageManager.PackageName.GZIP
    );
    int len = files.size();
    String[] cmd = new String[len + 5];
    cmd[0] = "/bin/tar";
    cmd[1] = "-C";
    cmd[2] = "/";
    cmd[3] = "-czf";
    cmd[4] = backupFile.getPath();
    // strips the leading / as it builds the command
    for (int c = 0; c < len; c++) {
      cmd[c + 5] = files.get(c).getPath().substring(1);
    }

    AoservDaemon.exec(cmd);
  }

  /*
  static void backupInterBaseDatabases() throws IOException, SQLException {
    if (AoservDaemon.getThisAOServer().isInterBase()) {
      ProcessTimer timer = new ProcessTimer(
        AoservDaemon.getFastRandom(),
        AoservDaemonConfiguration.getWarningSmtpServer(),
        AoservDaemonConfiguration.getWarningEmailFrom(),
        AoservDaemonConfiguration.getWarningEmailTo(),
        "InterBase backup taking too long",
        "InterBase Backup",
        3L * 60 * 60 * 1000,
        60L * 60 * 1000
      );
      try {
        AoservDaemon.executorService.submit(timer);
        try {
          for (InterBaseDatabase id : AoservDaemon.getThisAOServer().getInterBaseDatabases()) {
            if (id.getBackupLevel().getLevel()>0) {
              long startTime=System.currentTimeMillis();
              try {
                id.backup();
              } catch (Error | RuntimeException err) {
                logger.log(
                  Level.SEVERE,
                  err,
                  new Object[] {"id="+id}
                );
              }
            }
          }
        } catch (ThreadDeath td) {
          throw td;
        } catch (Throwable t) {
          logger.log(Level.SEVERE, null, t);
        }
      } finally {
        timer.stop();
      }
    }
  }
*/
  /*
  static void backupMysqlDatabases() throws IOException, SQLException {
    ProcessTimer timer=new ProcessTimer(
      AoservDaemon.getFastRandom(),
      AoservDaemonConfiguration.getWarningSmtpServer(),
      AoservDaemonConfiguration.getWarningEmailFrom(),
      AoservDaemonConfiguration.getWarningEmailTo(),
      "MySQL backup taking too long",
      "MySQL Backup",
      3L * 60 * 60 * 1000,
      60L * 60 * 1000
    );
    try {
      AoservDaemon.executorService.submit(timer);
      try {
        for (Server ms : AoservDaemon.getThisServer().getMysqlServers()) {
          for (Database md : ms.getMysqlDatabases()) {
            if (md.getBackupLevel().getLevel()>0) {
              long startTime=System.currentTimeMillis();
              try {
                md.backup();
              } catch (Error | RuntimeException err) {
                logger.log(
                  Level.SEVERE,
                  err,
                  new Object[] {"md="+md}
                );
              }
            }
          }
        }
      } catch (ThreadDeath td) {
        throw td;
      } catch (Throwable t) {
        logger.log(Level.SEVERE, null, t);
      }
    } finally {
      timer.stop();
    }
  }
  */

  /*
  static void backupPostgresDatabases() throws IOException, SQLException {
    ProcessTimer timer=new ProcessTimer(
      AoservDaemon.getFastRandom(),
      AoservDaemonConfiguration.getWarningSmtpServer(),
      AoservDaemonConfiguration.getWarningEmailFrom(),
      AoservDaemonConfiguration.getWarningEmailTo(),
      "PostgreSQL backup taking too long",
      "PostgreSQL Backup",
      3L * 60 * 60 * 1000,
      60L * 60 * 1000
    );
    try {
      AoservDaemon.executorService.submit(timer);
      try {
        for (Server ps : AoservDaemon.getThisServer().getPostgresServers()) {
          for (Database pd : ps.getPostgresDatabases()) {
            if (pd.allowsConnections() && pd.getBackupLevel().getLevel()>0) {
              long startTime=System.currentTimeMillis();
              try {
                pd.backup();
              } catch (Error | RuntimeException err) {
                logger.log(
                  Level.SEVERE,
                  err,
                  new Object[] {"pd="+pd}
                );
              }
            }
          }
        }
      } catch (ThreadDeath td) {
        throw td;
      } catch (Throwable t) {
        logger.log(Level.SEVERE, null, t);
      }
    } finally {
      timer.stop();
    }
  }
*/

  /**
   * TODO: Only auto-delete from oldaccounts when all configured and enabled backups have had a full, successful pass that starts after the file was created.
   *       This way we know the files have been carried off the server before deleting this auto-backup.
   */
  @SuppressWarnings({"UseSpecificCatch", "TooBroadCatch"})
  static void cleanVarOldaccounts() {
    try {
      PosixFile oldaccountsDir = getOldaccountsDir();
      String[] files = oldaccountsDir.list();
      if (files != null) {
        for (String filename : files) {
          // Construct the Calendar from the filename
          // Not y10k compatible ;)
          GregorianCalendar fileCal = new GregorianCalendar();
          fileCal.set(Calendar.YEAR, Integer.parseInt(filename.substring(0, 4)));
          fileCal.set(Calendar.MONTH, Integer.parseInt(filename.substring(4, 6)) - 1);
          fileCal.set(Calendar.DAY_OF_MONTH, Integer.parseInt(filename.substring(6, 8)));
          fileCal.set(Calendar.HOUR_OF_DAY, Integer.parseInt(filename.substring(9, 11)));
          fileCal.set(Calendar.MINUTE, Integer.parseInt(filename.substring(11, 13)));
          fileCal.set(Calendar.SECOND, Integer.parseInt(filename.substring(13, 15)));

          // If more than double the max age, we might have a system time problem or an extended down time
          long age = System.currentTimeMillis() - fileCal.getTime().getTime();
          if (age >= (MAX_OLDACCOUNTS_AGE * 2)) {
            logger.warning(
                filename + "\n"
                    + "File date unexpectedly far in the past; refusing to delete.\n"
                    + "This could be due to a system time change or a very long outage.\n"
                    + "Please investigate and removed as-needed."
            );
          } else if (age >= MAX_OLDACCOUNTS_AGE) {
            new PosixFile(oldaccountsDir, filename, true).delete();
          } else if (age < 0) {
            logger.warning(
                filename + "\n"
                    + "File date is in the future.\n"
                    + "This could be due to a system time change or a clock problem.\n"
                    + "Please investigate and removed as-needed."
            );
          }
        }
      }
    } catch (ThreadDeath td) {
      throw td;
    } catch (Throwable t) {
      logger.log(Level.SEVERE, null, t);
    }
  }

  /**
   * The full path to the df command.
   */
  public static final String DF = "/bin/df";

  public static long getDiskDeviceTotalSize(PosixPath path) throws IOException {
    return getDfColumn(path, 1);
  }

  public static long getDiskDeviceUsedSize(PosixPath path) throws IOException {
    return getDfColumn(path, 2);
  }

  @SuppressWarnings({"UseSpecificCatch", "TooBroadCatch"})
  private static long getDfColumn(PosixPath path, int column) throws IOException {
    return AoservDaemon.execCall(
        stdout -> {
          try (BufferedReader in = new BufferedReader(new InputStreamReader(stdout))) {
            // The first line is the column labels
            String line = in.readLine();
            if (line == null) {
              throw new IOException("EOF when trying to read column labels");
            }
            line = in.readLine();
            if (line == null) {
              throw new IOException("EOF when trying to read values");
            }
            String[] columns = Strings.split(line);
            return 1024 * Long.parseLong(columns[column]);
          }
        },
        DF,
        "-k",
        "-P",
        path.toString()
    );
  }

  /**
   * Gets the next backup file for ".tgz" extension.
   *
   * @see  BackupManager#getNextBackupFile(java.lang.String)
   */
  public static File getNextTarballBackupFile() throws IOException {
    return getNextBackupFile(".tgz");
  }

  /**
   * Gets the next backup filename in the oldaccounts directory.
   * The filename format is <code>YYYYMMDD_HHMMSS_C<i>extension</i></code> where <code>C</code> a counter
   * starting at one.
   * The file is created empty with permissions 0600.
   */
  public static File getNextBackupFile(String extension) throws IOException {
    synchronized (BackupManager.class) {
      GregorianCalendar gcal = new GregorianCalendar();
      StringBuilder sb = new StringBuilder(11);

      sb.append(gcal.get(Calendar.YEAR));

      int month = gcal.get(Calendar.MONTH) + 1;
      if (month < 10) {
        sb.append('0');
      }
      sb.append(month);

      int day = gcal.get(Calendar.DAY_OF_MONTH);
      if (day < 10) {
        sb.append('0');
      }
      sb.append(day).append('_');

      int hour = gcal.get(Calendar.HOUR_OF_DAY);
      if (hour < 10) {
        sb.append('0');
      }
      sb.append(hour);

      int minute = gcal.get(Calendar.MINUTE);
      if (minute < 10) {
        sb.append('0');
      }
      sb.append(minute);

      int second = gcal.get(Calendar.SECOND);
      if (second < 10) {
        sb.append('0');
      }
      sb.append(second).append('_');

      PosixFile oldaccountsDir = getOldaccountsDir();
      String prefix = sb.toString();
      for (int c = 1; c < Integer.MAX_VALUE; c++) {
        PosixFile unixFile = new PosixFile(oldaccountsDir, prefix + c + extension, true);
        if (!unixFile.getStat().exists()) {
          File file = unixFile.getFile();
          new FileOutputStream(file).close();
          unixFile.setMode(0600);
          return file;
        }
      }
      throw new IOException("Unable to allocate backup file for " + oldaccountsDir.getPath() + '/' + prefix + '*' + extension);
    }
  }

  /*
  private static final int CACHED_DIRECTORY_SIZE=64;
  private static final Object cachedDirectoryLock=new Object();
  private static String[] cachedPaths;
  private static String[][] cachedLists;
  public static String[] getCachedDirectory(String path) throws IOException {
    synchronized (cachedDirectoryLock) {
      if (cachedPaths == null) {
        cachedPaths=new String[CACHED_DIRECTORY_SIZE];
        cachedLists=new String[CACHED_DIRECTORY_SIZE][];
      }
      for (int c=0;c<CACHED_DIRECTORY_SIZE;c++) {
        String cpath=cachedPaths[c];
        if (cpath == null) {
          break;
        }
        if (cpath.equals(path)) {
          return cachedLists[c];
        }
      }
      // Insert at the top of the cache
      String[] list=new File(path).list();
      System.arraycopy(cachedPaths, 0, cachedPaths, 1, CACHED_DIRECTORY_SIZE-1);
      cachedPaths[0]=path;
      System.arraycopy(cachedLists, 0, cachedLists, 1, CACHED_DIRECTORY_SIZE-1);
      cachedLists[0]=list;
      return list;
    }
  }*/
}
