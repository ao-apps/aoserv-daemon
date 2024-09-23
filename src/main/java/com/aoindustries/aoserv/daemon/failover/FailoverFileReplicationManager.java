/*
 * aoserv-daemon - Server management daemon for the AOServ Platform.
 * Copyright (C) 2003-2013, 2015, 2017, 2018, 2019, 2020, 2021, 2022, 2024  AO Industries, Inc.
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

package com.aoindustries.aoserv.daemon.failover;

import com.aoapps.collections.AoCollections;
import com.aoapps.cron.CronDaemon;
import com.aoapps.cron.CronJob;
import com.aoapps.cron.Schedule;
import com.aoapps.hodgepodge.io.ParallelDelete;
import com.aoapps.hodgepodge.io.stream.StreamableInput;
import com.aoapps.hodgepodge.io.stream.StreamableOutput;
import com.aoapps.hodgepodge.md5.MD5;
import com.aoapps.io.filesystems.Path;
import com.aoapps.io.filesystems.posix.DedupDataIndex;
import com.aoapps.io.filesystems.posix.DefaultPosixFileSystem;
import com.aoapps.io.filesystems.posix.PosixFileSystem;
import com.aoapps.io.posix.PosixFile;
import com.aoapps.io.posix.Stat;
import com.aoapps.lang.AutoCloseables;
import com.aoapps.lang.Throwables;
import com.aoapps.lang.math.SafeMath;
import com.aoindustries.aoserv.backup.BackupDaemon;
import com.aoindustries.aoserv.client.backup.BackupRetention;
import com.aoindustries.aoserv.client.mysql.Server;
import com.aoindustries.aoserv.client.scm.CvsRepository;
import com.aoindustries.aoserv.daemon.AoservDaemon;
import com.aoindustries.aoserv.daemon.AoservDaemonConfiguration;
import com.aoindustries.aoserv.daemon.backup.LinuxServerEnvironment;
import com.aoindustries.aoserv.daemon.client.AoservDaemonProtocol;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.Socket;
import java.nio.file.Files;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Stack;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.GZIPInputStream;

/**
 * Handles the replication of data for the failover and backup system.
 * <p>
 * In failover mode, only one replication directory is maintained and it is
 * updated in-place.  No space saving techniques are applied.  A data index is
 * never used in failover mode.
 * </p>
 * <p>
 * In backup mode, multiple directories (on per date) are maintained.  Also,
 * regular files are hard linked between directories (and optionally the data
 * index).
 * </p>
 * <p>
 * Compression may be enabled, which will compare chunks of files by MD5 hash and GZIP compress the upload stream.
 * This will save networking at the cost of additional CPU cycles.  This is
 * generally a good thing, but when your network is significantly faster than
 * your processor, it may be better to turn off compression.
 * </p>
 * <p>
 * The compression also assumes that if the MD5 matches in the same chunk location,
 * then chunk has not changed.  To be absolutely sure there is no hash collision
 * the compression must be disabled.
 * </p>
 * <p>
 * Files are compared only by modified time and file length.  If both these are
 * the same, the file is assumed to be the same.  There is currently no facility
 * for full checksumming or copying of data.
 * </p>
 * <p>
 * When the data index is enabled, the underlying filesystem must have the
 * capabilities of <code>ext4</code> or better (support 2^16 sub directories
 * and over <code>DataIndex.FILESYSTEM_MAX_LINK_COUNT</code> links to a file).
 * </p>
 * <p>
 * To minimize the amount of meta data updates, old backup trees are recycled
 * and used as the starting point for new backups.  This dramatically improves
 * the throughput in the normal case where most things do not change.
 * </p>
 * <p>
 * The data index may be turned on and off for a partition.  Newly created backup
 * directory trees will use the format currently set, but will also recognize the
 * existing data in either format.
 * </p>
 * <p>
 * When data index is off, each file is simply stored, in its entirety, directly
 * in-place.  If the file contents (possibly assumed only by length and modified
 * time) and all attributes (ownership, permission, times, ...) match another
 * backup directory, the files will be hard linked together to save space.
 * </p>
 * <p>
 * When the data index is enabled, each file is handled one of three ways:
 * </p>
 * <ol>
 * <li>
 *   If the file is empty, it is stored directly in place not using the
 *   dataindex.  Also, empty files are never hard linked because no space is
 *   saved by doing so.
 * </li>
 * <li>If the filename is less than <code>MAX_NODIR_FILENAME</code> in length:
 *   <ol type="a">
 *     <li>The file is represented by an empty surrogate in it's original location</li>
 *     <li>A series of hard linked data chunks with original filename as prefix</li>
 *   </ol>
 * </li>
 * <li>If the filename is too long:
 *   <ol type="a">
 *     <li>A directory is put in place of the filename</li>
 *     <li>An empty surrogate named "&lt;A&lt;O&lt;SURROGATE&gt;O&gt;A&gt;" is created</li>
 *     <li>A series of hard linked data chunks</li>
 *   </ol>
 * </li>
 * </ol>
 * <p>
 * A surrogate file contain all the ownership, mode, and (in the future) will
 * represent the hard link relationships in the original source tree.
 * </p>
 * <p>
 * During an expansion process, the surrogate might not be empty as data is put
 * back in place.  The restore processes resume where they left off, even when
 * interrupted.
 * </p>
 * <p>
 * Data indexes are verified once per day as well as a quick verification on
 * start-up.
 * </p>
 * <p>
 * depending on the length of the filename.
 *
16 TiB = 2 ^ (10 + 10 + 10 + 10 + 4) = 2 ^ 44

Each chunk is up to 1 MiB: 2 ^ 20

Maximum number of chunks per file: 2 ^ (44 - 20): 2 ^ 24

 * TODO: filename&lt;A&lt;O&lt;S&gt;O&gt;A&gt;...
 * TODO: Can't have any regular filename from client with &lt;A&lt;O&lt;CHUNK&gt;O&gt;A&gt; pattern.
 * TODO: Can't have any regular file exactly named "&lt;A&lt;O&lt;SURROGATE&gt;O&gt;A&gt;"
 * </p>
 * <p>
 * TODO: Handle hard links (pertinence space savings), and also meet expectations.  Our ParallelPack/ParallelUnpack are
 *       a good reference.
 * </p>
 * <p>
 * TODO: Need to do mysqldump and postgresql dump on preBackup
 * </p>
 * <p>
 * TODO: Use LVM snapshots within the client layer
 * </p>
 * <p>
 * TODO: Support chunking from either data set: current file or in linkToRoot, also possibly try to guess the last temp file?
 *       This would allow to not have to resend all data when a chunked transfer is interrupted.  This would have the
 *       cost of additional reads and MD5 CPU, so may not be worth it in the general case; any way to detect when it is
 *       worth it, such as a certain number of chunks transferred?
 * </p>
 * <p>
 * TODO: Support sparse files.  In simplest form, use RandomAccessFile to write new files, and detect sequences of zeros,
 *       possibly only when 4k aligned, and use seek instead of writing the zeros.  Could also build the zero detection
 *       into the protocol, which would put more of the work on the client and remove the need for MD5 and compression
 *       of the zeros, at least in the case of full 1 MiB chunks of zeros.
 * </p>
 *
 * @see  DedupDataIndex
 *
 * @author  AO Industries, Inc.
 */
public final class FailoverFileReplicationManager {

  /** Make no instances. */
  private FailoverFileReplicationManager() {
    throw new AssertionError();
  }

  private static final Logger logger = Logger.getLogger(FailoverFileReplicationManager.class.getName());

  /**
   * The extension added to the directory name when it is a partial pass.
   */
  private static final String PARTIAL_EXTENSION = ".partial";

  /**
   * When using safe delete, the directory is only renamed to "XXX.deleted" instead of actually removed.
   */
  private static final boolean SAFE_DELETE = false;

  private static final String SAFE_DELETE_EXTENSION = ".deleted";

  /**
   * The last two completed deleted passes are kept as "XXX.recycled" instead of actually deleted.  These are then reused
   * in favor of making a new directory.  This allows the system to do fewer links and unlinks to save on disk IO.  This
   * is especially important for meta-data journalling filesystems, such as reiserfs, to maximize scalability.
   */
  private static final String RECYCLED_EXTENSION = ".recycled";

  /**
   * The directory name that contains the data index.
   */
  private static final String DATA_INDEX_DIRECTORY_NAME = "DATA-INDEX";

  /**
   * The time that orphans will be cleaned.
   */
  private static final Schedule CLEAN_ORPHANS_SCHEDULE =
      (int minute, int hour, int dayOfMonth, int month, int dayOfWeek, int year) -> (minute == 49 && hour == 1);

  /**
   * TODO: Move this into a backup settings table.
   */
  private static final Set<String> encryptedLoopFilePaths = new HashSet<>();

  static {
    encryptedLoopFilePaths.add("/ao.aes128.img");
    encryptedLoopFilePaths.add("/ao.aes256.img");
    encryptedLoopFilePaths.add("/ao.copy.aes128.img");
    encryptedLoopFilePaths.add("/ao.copy.aes256.img");
    encryptedLoopFilePaths.add("/encrypted.aes128.img");
    encryptedLoopFilePaths.add("/encrypted.aes256.img");
    encryptedLoopFilePaths.add("/home.aes128.img");
    encryptedLoopFilePaths.add("/home.aes256.img");
    encryptedLoopFilePaths.add("/logs.aes128.img");
    encryptedLoopFilePaths.add("/logs.aes256.img");
    encryptedLoopFilePaths.add(CvsRepository.DEFAULT_CVS_DIRECTORY + ".aes128.img");
    encryptedLoopFilePaths.add(CvsRepository.DEFAULT_CVS_DIRECTORY + ".aes256.img");
    encryptedLoopFilePaths.add("/var/lib/pgsql.aes128.img");
    encryptedLoopFilePaths.add("/var/lib/pgsql.aes256.img");
    encryptedLoopFilePaths.add("/var/spool.aes128.img");
    encryptedLoopFilePaths.add("/var/spool.aes256.img");
    encryptedLoopFilePaths.add("/www.aes128.img");
    encryptedLoopFilePaths.add("/www.aes256.img");
    // AO desktop home directories in Debian seem to update modified times
    //encryptedLoopFilePaths.add("/home/bugnugger.aes256.img");
    //encryptedLoopFilePaths.add("/home/kaori.aes256.img");
    //encryptedLoopFilePaths.add("/home/orion.aes256.img");
  }

  private static boolean isEncryptedLoopFile(String path) {
    return encryptedLoopFilePaths.contains(path);
  }

  /**
   * The number of recycled copies varies based on retention.
   */
  // Matches prices.ods
  private static int getNumberRecycleDirectories(int retention) {
    if (retention <= 7) {
      return 1;
    }
    if (retention <= 31) {
      return 2;
    }
    if (retention <= 92) {
      return 3;
    }
    return 4;
  }

  /**
   * While a recycled directory is being updated to be the current, it is renamed to have this extension.
   * This extension is used instead of simply ".partial" to distinguish when the update is from the most
   * recent pass or when it is from an older pass.
   */
  private static final String RECYCLED_PARTIAL_EXTENSION = ".recycled.partial";

  /**
   * Checks a path for sanity.
   * <ol>
   *   <li>Must not be empty</li>
   *   <li>Must start with '/'</li>
   *   <li>Must not contain null character</li>
   *   <li>Must not contain empty path element "//"</li>
   *   <li>Must not end with '/', unless is the root "/" itself</li>
   *   <li>Must not contain "/../"</li>
   *   <li>Must not end with "/.."</li>
   *   <li>Must not contain "/./"</li>
   *   <li>Must not end with "/."</li>
   * </ol>
   */
  public static void checkPath(String path) throws IOException {
    // Must not be empty
    int pathLen = path.length();
    if (pathLen == 0) {
      throw new IOException("Illegal path: Must not be empty");
    }
    // Must start with '/'
    if (path.charAt(0) != '/') {
      throw new IOException("Illegal path: Must start with '/': " + path);
    }
    // Must not contain null character
    if (path.indexOf(0) != -1) {
      throw new IOException("Illegal path: Must not contain null character: " + path);
    }
    // Must not contain empty path element "//"
    if (path.contains("//")) {
      throw new IOException("Illegal path: Must not contain empty path element \"//\": " + path);
    }
    // Must not end with '/', unless is the root "/" itself
    if (pathLen > 1 && path.charAt(pathLen - 1) == '/') {
      throw new IOException("Illegal path: Must not end with '/', unless is the root \"/\" itself: " + path);
    }
    // Must not contain "/../"
    if (path.contains("/../")) {
      throw new IOException("Illegal path: Must not contain \"/../\": " + path);
    }
    // Must not end with "/.."
    if (
        pathLen >= 3
            && path.charAt(pathLen - 3) == '/'
            && path.charAt(pathLen - 2) == '.'
            && path.charAt(pathLen - 1) == '.'
    ) {
      throw new IOException("Illegal path: Must not end with \"/..\": " + path);
    }
    // Must not contain "/./"
    if (path.contains("/./")) {
      throw new IOException("Illegal path: Must not contain \"/./\": " + path);
    }
    // Must not end with "/."
    if (
        pathLen >= 2
            && path.charAt(pathLen - 2) == '/'
            && path.charAt(pathLen - 1) == '.'
    ) {
      throw new IOException("Illegal path: Must not end with \"/.\": " + path);
    }
  }

  /**
   * Checks a symlink target for sanity.
   * <ol>
   *   <li>Must not be empty</li>
   *   <li>Must not contain null character</li>
   * </ol>
   */
  public static void checkSymlinkTarget(String target) throws IOException {
    // Must not be empty
    int targetLen = target.length();
    if (targetLen == 0) {
      throw new IOException("Illegal target: Must not be empty");
    }
    // Must not contain null character
    if (target.indexOf(0) != -1) {
      throw new IOException("Illegal target: Must not contain null character: " + target);
    }
  }

  /**
   * Keeps track of things that will need to be done after a successful replication pass.
   */
  private static class PostPassChecklist {
    boolean restartMysqls;

    private PostPassChecklist() {
    }
  }

  private static void updated(int retention, PostPassChecklist postPassChecklist, String relativePath) {
    if (
        retention == 1
            && !postPassChecklist.restartMysqls
            && (
            relativePath.startsWith("/etc/rc.d/init.d/mysql-")
                || relativePath.startsWith("/etc/sysconfig/mysql-")
                || relativePath.startsWith("/opt/mysql-")
        )
    ) {
      if (logger.isLoggable(Level.FINE)) {
        logger.fine("Flagging postPassChecklist.restartMySQLs=true for path=" + relativePath);
      }
      postPassChecklist.restartMysqls = true;
    }
  }

  public static class Activity /*implements Cloneable*/ {

    private long time = -1;
    // When set, this is the complete human-readable form
    private Object message1;
    private Object message2;
    private Object message3;
    private Object message4;

    private Activity() {
    }

    private synchronized void update(
        Object message1,
        Object message2,
        Object message3,
        Object message4
    ) {
      this.time = System.currentTimeMillis();
      this.message1 = message1;
      this.message2 = message2;
      this.message3 = message3;
      this.message4 = message4;
    }

    private void update(Object message) {
      update(
          message,
          null,
          null,
          null
      );
    }

    private void update(Object message1, Object message2) {
      update(
          message1,
          message2,
          null,
          null
      );
    }

    private void update(Object message1, Object message2, Object message3) {
      update(
          message1,
          message2,
          message3,
          null
      );
    }

    /**
     * Gets a copy of this object.
     */
    /*
    @Override
    public synchronized Activity clone() {
      try {
        return (Activity)super.clone();
      } catch (CloneNotSupportedException e) {
        throw new AssertionError("Should not happen, is Cloneable", e);
      }
    }*/

    public long getTime() {
      return time;
    }

    /**
     * Gets the combined message.
     */
    public synchronized String getMessage() {
      int len = 0;
      final String m1 = message1 == null ? null : message1.toString();
      final String m2 = message2 == null ? null : message2.toString();
      final String m3 = message3 == null ? null : message3.toString();
      final String m4 = message4 == null ? null : message4.toString();
      if (m1 != null) {
        len += m1.length();
      }
      if (m2 != null) {
        len += m2.length();
      }
      if (m3 != null) {
        len += m3.length();
      }
      if (m4 != null) {
        len += m4.length();
      }
      StringBuilder result = new StringBuilder(len);
      if (m1 != null) {
        result.append(m1);
      }
      if (m2 != null) {
        result.append(m2);
      }
      if (m3 != null) {
        result.append(m3);
      }
      if (m4 != null) {
        result.append(m4);
      }
      return result.toString();
    }
  }

  /**
   * Tracks the most recent action on a per-FileReplication basis.
   */
  private static final Map<Integer, Activity> activities = new HashMap<>();

  public static Activity getActivity(Integer failoverFileReplicationPkey) {
    synchronized (activities) {
      Activity activity = activities.get(failoverFileReplicationPkey);
      if (activity == null) {
        activity = new Activity();
        activities.put(failoverFileReplicationPkey, activity);
      }
      return activity;
    }
  }

  private static String readLink(Activity activity, PosixFile uf) throws IOException {
    activity.update("file: readLink: ", uf);
    return uf.readLink();
  }

  /**
   * Makes a temporary file based on the given file.
   */
  // TODO: Use TempFileContext to automatically delete when interrupted?
  private static PosixFile mktemp(Activity activity, PosixFile uf) throws IOException {
    File file = uf.getFile();
    File dir = file.getParentFile();
    String name = file.getName();
    if (name.length() > 64) {
      name = name.substring(0, 64);
    }
    activity.update("file: mktemp: ", new File(dir, name));
    return new PosixFile(
        Files.createTempFile(
            dir.toPath(),
            name,
            null
        ).toFile()
    );
  }

  private static void delete(Activity activity, PosixFile uf) throws IOException {
    activity.update("file: delete: ", uf);
    uf.delete();
  }

  private static void deleteRecursive(Activity activity, PosixFile uf) throws IOException {
    activity.update("file: deleteRecursive: ", uf);
    uf.deleteRecursive();
  }

  private static Stat stat(Activity activity, PosixFile uf) throws IOException {
    activity.update("file: stat: ", uf);
    return uf.getStat();
  }

  private static void link(Activity activity, PosixFile from, PosixFile to) throws IOException {
    activity.update("file: link: ", from, " to ", to);
    from.link(to);
  }

  private static void rename(Activity activity, PosixFile from, PosixFile to) throws IOException {
    activity.update("file: rename: ", from, " to ", to);
    from.renameTo(to);
  }

  private static void mkdir(Activity activity, PosixFile uf) throws IOException {
    activity.update("file: mkdir: ", uf);
    uf.mkdir();
  }

  private static void mkdir(Activity activity, PosixFile uf, boolean makeParents, long mode, int uid, int gid) throws IOException {
    activity.update("file: mkdir: ", uf);
    uf.mkdir(
        makeParents,
        mode,
        uid,
        gid
    );
  }

  private static String[] list(Activity activity, PosixFile uf) {
    activity.update("file: list: ", uf);
    return uf.list();
  }

  private static void mknod(Activity activity, PosixFile uf, long mode, long device) throws IOException {
    activity.update("file: mknod: ", uf);
    uf.mknod(mode, device);
  }

  private static void mkfifo(Activity activity, PosixFile uf, long mode) throws IOException {
    activity.update("file: mkfifo: ", uf);
    uf.mkfifo(mode);
  }

  private static void touch(Activity activity, PosixFile uf) throws IOException {
    activity.update("file: touch: ", uf);
    new FileOutputStream(uf.getFile()).close();
  }

  private static FileInputStream openIn(Activity activity, PosixFile uf) throws IOException {
    activity.update("file: open: < ", uf);
    return new FileInputStream(uf.getFile());
  }

  private static RandomAccessFile openInRaf(Activity activity, PosixFile uf) throws IOException {
    activity.update("file: open: < ", uf);
    return new RandomAccessFile(uf.getFile(), "r");
  }

  private static FileOutputStream openOut(Activity activity, PosixFile uf) throws IOException {
    activity.update("file: open: > ", uf);
    return new FileOutputStream(uf.getFile());
  }

  private static void close(Activity activity, PosixFile uf, InputStream in) throws IOException {
    activity.update("file: close: < ", uf);
    in.close();
  }

  private static void close(Activity activity, PosixFile uf, RandomAccessFile raf) throws IOException {
    activity.update("file: close: < ", uf);
    raf.close();
  }

  private static void close(Activity activity, PosixFile uf, OutputStream out) throws IOException {
    activity.update("file: close: > ", uf);
    out.close();
  }

  private static void renameToNoExists(Logger logger, Activity activity, PosixFile from, PosixFile to) throws IOException {
    boolean isFine = logger.isLoggable(Level.FINE);
    if (isFine) {
      logger.fine("Renaming \"" + from + "\" to \"" + to + '"');
    }
    if (stat(activity, to).exists()) {
      throw new IOException("to exists: " + to);
    }
    rename(activity, from, to);
  }

  private static final Map<String, DedupDataIndex> dedupIndexes = new HashMap<>();

  /**
   * Only one data index is created per backup partition, and a cron job is created
   * for daily cleaning.  The cron job is also launched immediately in quick mode.
   */
  private static DedupDataIndex getDedupDataIndex(Activity activity, String backupPartition) throws IOException {
    synchronized (dedupIndexes) {
      DedupDataIndex dedupIndex = dedupIndexes.get(backupPartition);
      if (dedupIndex == null) {
        PosixFileSystem fileSystem = DefaultPosixFileSystem.getInstance();
        Path dataIndexDir = new Path(
            fileSystem.parsePath(backupPartition),
            DATA_INDEX_DIRECTORY_NAME
        );
        activity.update("data-index: Opening data index: ", dataIndexDir);
        DedupDataIndex newDedupIndex = DedupDataIndex.getInstance(fileSystem, dataIndexDir);
        /*
         * Add the CronJob that cleans orphaned data in the background.
         */
        CronJob cleanupJob = new CronJob() {
          @Override
          public Schedule getSchedule() {
            return CLEAN_ORPHANS_SCHEDULE;
          }

          @Override
          public String getName() {
            return DedupDataIndex.class.getName() + ".cleanOrphans()";
          }

          @Override
          public void run(int minute, int hour, int dayOfMonth, int month, int dayOfWeek, int year) {
            try {
              newDedupIndex.verify(false);
            } catch (IOException e) {
              logger.log(Level.SEVERE, "verify index failed", e);
            }
          }
        };
        CronDaemon.addCronJob(cleanupJob, logger);
        // Quick verification once on startup
        new Thread(
            () -> {
              try {
                newDedupIndex.verify(true);
              } catch (IOException e) {
                logger.log(Level.SEVERE, "quick verify index failed", e);
              }
            }
        ).start();
        dedupIndex = newDedupIndex;
        dedupIndexes.put(backupPartition, dedupIndex);
      }
      return dedupIndex;
    }
  }

  /**
   * Receives incoming data for a failover replication.  The critical information, such as the directory to store to,
   * has been provided by the master server because we can't trust the sending server.
   *
   * @param backupPartition  the full path to the root of the backup partition, without any hostnames, packages, or names
   * @param quotaGid  the quota_gid or <code>-1</code> for no quotas
   */
  @SuppressWarnings({"UnusedAssignment", "UseSpecificCatch", "TooBroadCatch"})
  public static void failoverServer(
      final Socket socket,
      final StreamableInput rawIn,
      final StreamableOutput out,
      final AoservDaemonProtocol.Version protocolVersion,
      final int failoverFileReplicationPkey,
      final String fromServer,
      final boolean useCompression,
      final short retention,
      final String backupPartition, // TODO: Make sure this partition is enabled
      final short fromServerYear,
      final short fromServerMonth,
      final short fromServerDay,
      final List<Server.Name> replicatedMysqlServers,
      final List<String> replicatedMysqlMinorVersions,
      final int quotaGid
  ) throws IOException, SQLException {
    boolean success = false;
    final Activity activity = getActivity(failoverFileReplicationPkey);
    activity.update("logic: init");
    final String toPath = backupPartition + '/' + fromServer;
    try {
      final PostPassChecklist postPassChecklist = new PostPassChecklist();
      boolean isInfo = logger.isLoggable(Level.INFO);
      boolean isFine = logger.isLoggable(Level.FINE);
      boolean isTrace = logger.isLoggable(Level.FINER);
      Throwable t0 = null;
      try {
        if (isInfo) {
          logger.info(
              "Receiving transfer:\n"
                  + "    fromServer=\"" + fromServer + "\"\n"
                  + "    useCompression=" + useCompression + "\n"
                  + "    retention=" + retention + "\n"
                  + "    backupPartition=\"" + backupPartition + "\"\n"
                  + "    fromServerYear=" + fromServerYear + "\n"
                  + "    fromServerMonth=" + fromServerMonth + "\n"
                  + "    fromServerDay=" + fromServerDay + "\n"
                  + "    quota_gid=" + quotaGid + "\n"
                  + "    toPath=\"" + toPath + "\"\n"
                  + "    thread.id=" + Thread.currentThread().getId()
          );
        }
        if (fromServerYear < 1000 || fromServerYear > 9999) {
          throw new IOException("Invalid fromServerYear (1000-9999): " + fromServerYear);
        }
        if (fromServerMonth < 1 || fromServerMonth > 12) {
          throw new IOException("Invalid fromServerMonth (1-12): " + fromServerMonth);
        }
        if (fromServerDay < 1 || fromServerDay > 31) {
          throw new IOException("Invalid fromServerDay (1-31): " + fromServerDay);
        }

        for (Server.Name replicatedMysqlServer : replicatedMysqlServers) {
          if (isFine) {
            logger.fine("failoverServer from \"" + fromServer + "\", replicatedMysqlServer: " + replicatedMysqlServer);
          }
        }
        for (String replicatedMysqlMinorVersion : replicatedMysqlMinorVersions) {
          if (isFine) {
            logger.fine("failoverServer from \"" + fromServer + "\", replicatedMysqlMinorVersion: " + replicatedMysqlMinorVersion);
          }
          if (
              replicatedMysqlMinorVersion.indexOf('/') != -1
                  || replicatedMysqlMinorVersion.contains("..")
          ) {
            throw new IOException("Invalid replicatedMysqlMinorVersion: " + replicatedMysqlMinorVersion);
          }
        }

          // Create the server root if it doesn't exist
          {
            PosixFile toPathPosixFile = new PosixFile(toPath);
            Stat dirStat = stat(activity, toPathPosixFile);
            if (!dirStat.exists()) {
              mkdir(
                  activity,
                  toPathPosixFile,
                  true,
                  quotaGid == -1 ? 0700 : 0750,
                  PosixFile.ROOT_UID,
                  quotaGid == -1 ? PosixFile.ROOT_GID : quotaGid
              );
            } else if (!dirStat.isDirectory()) {
              throw new IOException("toPath exists but is not a directory: " + toPath);
            }
          }

        // Tell the client it is OK to continue
        activity.update("socket: write: AoservDaemonProtocol.NEXT");
        out.write(AoservDaemonProtocol.NEXT);
        out.flush();

        // Determine the directory that is/will be storing the mirror
        final String partialMirrorRoot;
        final String recycledPartialMirrorRoot;
        final String finalMirrorRoot;
        String linkToRoot;
        final PosixFile perDateRoot;
        boolean isRecycling;
        final DedupDataIndex dataIndex;
        if (retention == 1) {
          partialMirrorRoot = finalMirrorRoot = toPath;
          recycledPartialMirrorRoot = null;
          linkToRoot = null;
          perDateRoot = null;
          isRecycling = false;
          dataIndex = null;
        } else {
          if (
              DATA_INDEX_DIRECTORY_NAME.equals(fromServer)
                  || fromServer.startsWith(DATA_INDEX_DIRECTORY_NAME + '/')
          ) {
            throw new IOException("fromServer conflicts with data index: " + fromServer);
          }
          dataIndex = getDedupDataIndex(activity, backupPartition);

          // The directory that holds the different versions
          perDateRoot = new PosixFile(toPath);

          // The directories including the date
          StringBuilder sb = new StringBuilder(toPath);
          sb.append('/').append(fromServerYear).append('-');
          if (fromServerMonth < 10) {
            sb.append('0');
          }
          sb.append(fromServerMonth).append('-');
          if (fromServerDay < 10) {
            sb.append('0');
          }
          sb.append(fromServerDay);
          finalMirrorRoot = sb.toString();
          // The partial directory name used during the transfer
          sb.append(PARTIAL_EXTENSION);
          partialMirrorRoot = sb.toString();
          // The partial directory name used when recycling a previous directory
          sb.setLength(finalMirrorRoot.length());
          sb.append(RECYCLED_PARTIAL_EXTENSION);
          recycledPartialMirrorRoot = sb.toString();

          /*
           * Determine the current state of today's backup.  And it should always be in one of these four
           * possible states:
           *   1) Existing and complete
           *   2) Existing and .recycled.partial
           *   3) Existing and .partial
           *   4) Nonexisting
           *
           * For (1) exiting and complete, rename existing to .partial, set isRecycling to false, no linkTo.
           *
           * For (2) existing and .recycled.partial, we will not rename and set isRecycling to true.
           *
           * For (3) existing and .partial, we will not rename and set isRecycling to false.
           *
           * For (4) nonexisting:
           *   a) Look for the most recent .partial. If found, rename to current date with .recycled.partial or .partial (matching previous extension), set isRecycling as appropriate.
           *   b) Look for the most recent .recycled. If found, we will renamed to .recycled.partial and set isRecycling to true.
           *   c) If neither found, make a new directory called .partial and set isRecycling to false.
           */
          // When the finalMirrorRoot exists, it is assumed to be complete and no linking to other directories will be performed.  This mode
          // is used when multiple passes are performed in a single day, it is basically the same behavior as a failover replication.
          PosixFile finalPosixFile = new PosixFile(finalMirrorRoot);
          if (stat(activity, finalPosixFile).exists()) {
            // See (1) above
            PosixFile partial = new PosixFile(partialMirrorRoot);
            renameToNoExists(logger, activity, finalPosixFile, partial);
            linkToRoot = null;
            isRecycling = false;
          } else {
              {
                PosixFile recycledPartial = new PosixFile(recycledPartialMirrorRoot);
                if (stat(activity, recycledPartial).exists()) {
                  // See (2) above
                  isRecycling = true;
                } else {
                  PosixFile partial = new PosixFile(partialMirrorRoot);
                  if (stat(activity, partial).exists()) {
                    // See (3) above
                    isRecycling = false;
                  } else {
                    // See (4) above
                    boolean foundPartial = false;
                    isRecycling = false;

                    String[] list = list(activity, perDateRoot);
                    if (list != null && list.length > 0) {
                      // This is not y10k compliant - this is assuming lexical order is the same as chronological order.
                      Arrays.sort(list);
                      // Find most recent partial
                      for (int c = list.length - 1; c >= 0; c--) {
                        String filename = list[c];
                        if (filename.endsWith(PARTIAL_EXTENSION)) {
                          isRecycling = filename.endsWith(RECYCLED_PARTIAL_EXTENSION);
                          renameToNoExists(
                              logger,
                              activity,
                              new PosixFile(perDateRoot, filename, false),
                              isRecycling ? recycledPartial : partial
                          );
                          foundPartial = true;
                          break;
                        }
                      }

                      if (!foundPartial) {
                        // Find most recent recycled pass
                        for (int c = list.length - 1; c >= 0; c--) {
                          String filename = list[c];
                          if (filename.endsWith(RECYCLED_EXTENSION)) {
                            renameToNoExists(
                                logger,
                                activity,
                                new PosixFile(perDateRoot, filename, false),
                                recycledPartial
                            );
                            isRecycling = true;
                            break;
                          }
                        }
                      }
                    }
                    if (!foundPartial && !isRecycling) {
                      // Neither found, create new directory
                      mkdir(activity, partial);
                    }
                  }
                }
              }
            // Finds the path that will be linked-to.
            // Find the most recent complete pass that is not today's directory (which should not exist anyways because renamed above)
            linkToRoot = null;
              {
                String[] list = list(activity, perDateRoot);
                if (list != null && list.length > 0) {
                  // This is not y10k compliant - this is assuming lexical order is the same as chronological order.
                  Arrays.sort(list);
                  // Find most recent complete pass
                  for (int c = list.length - 1; c >= 0; c--) {
                    String filename = list[c];
                    String fullFilename = toPath + "/" + filename;
                    if (fullFilename.equals(finalMirrorRoot)) {
                      throw new AssertionError("finalMirrorRoot exists, but should have already been renamed to .partial");
                    }
                    if (
                        filename.length() == 10
                            // && !fullFilename.equals(partialMirrorRoot)
                            // && !fullFilename.equals(recycledPartialMirrorRoot);
                            && !filename.endsWith(PARTIAL_EXTENSION)
                            && !filename.endsWith(SAFE_DELETE_EXTENSION)
                            && !filename.endsWith(RECYCLED_EXTENSION)
                    // && !filename.endsWith(RECYCLED_PARTIAL_EXTENSION)
                    ) {
                      linkToRoot = fullFilename;
                      break;
                    }
                  }
                  /* Update activity if this code is uncommented
                  if (linkToRoot == null) {
                    // When no complete pass is available, find the most recent recycling partial pass
                    for (int c=list.length-1;c >= 0;c--) {
                      String filename = list[c];
                      String fullFilename = serverRoot+"/"+filename;
                      if (
                        !fullFilename.equals(recycledPartialMirrorRoot)
                        && filename.endsWith(RECYCLED_PARTIAL_EXTENSION)
                      ) {
                        linkToRoot = fullFilename;
                        break;
                      }
                    }
                  }*/
                  /*if (linkToRoot == null) {
                    // When no complete pass or recycling partial is available, find the most recent non-recycling partial pass
                    for (int c=list.length-1;c >= 0;c--) {
                      String filename = list[c];
                      String fullFilename = serverRoot+"/"+filename;
                      if (
                        !fullFilename.equals(recycledPartialMirrorRoot)
                        && !fullFilename.equals(partialMirrorRoot)
                        && filename.endsWith(PARTIAL_EXTENSION)
                        // && !filename.endsWith(RECYCLED_PARTIAL_EXTENSION)
                      ) {
                        linkToRoot = fullFilename;
                        break;
                      }
                    }
                  }*/
                }
              }
          }
        }
        if (isFine) {
          logger.fine("partialMirrorRoot=" + partialMirrorRoot);
          logger.fine("recycledPartialMirrorRoot=" + recycledPartialMirrorRoot);
          logger.fine("finalMirrorRoot=" + finalMirrorRoot);
          logger.fine("linkToRoot=" + linkToRoot);
          logger.fine("isRecycling=" + isRecycling);
        }
        // Safety checks to make sure above logic isn't linking in obviously incorrect ways
        if (linkToRoot != null) {
          if (linkToRoot.equals(partialMirrorRoot)) {
            throw new AssertionError("linkToRoot == partialMirrorRoot: " + linkToRoot);
          }
          if (linkToRoot.equals(recycledPartialMirrorRoot)) {
            throw new AssertionError("linkToRoot == recycledPartialMirrorRoot: " + linkToRoot);
          }
          if (linkToRoot.equals(finalMirrorRoot)) {
            throw new AssertionError("linkToRoot == finalMirrorRoot: " + linkToRoot);
          }
        }

        final StreamableInput in =
            useCompression && protocolVersion.compareTo(AoservDaemonProtocol.Version.VERSION_1_84_19) >= 0
                ? new StreamableInput(new GZIPInputStream(rawIn, AoservDaemonProtocol.FAILOVER_FILE_REPLICATION_GZIP_BUFFER_SIZE))
                // ? new StreamableInput(new GZIPInputStream(NoCloseInputStream.wrap(rawIn), BufferManager.BUFFER_SIZE))
                : rawIn;

        String[] paths = null;
        boolean[] isLogDirs = null;
        Map<PosixFile, ModifyTimeAndSizeCache> modifyTimeAndSizeCaches = new HashMap<>();

        PosixFile[] tempNewFiles = null;
        PosixFile[] chunkingFroms = null;
        long[] chunkingSizes = null;
        long[][] chunksMd5His = null;
        long[][] chunksMd5Los = null;
        long[] modifyTimes = null;
        int[] results = null;

        final byte[] chunkBuffer = new byte[AoservDaemonProtocol.FAILOVER_FILE_REPLICATION_CHUNK_SIZE];
        final MD5 md5 = useCompression ? new MD5() : null;
        // The extra files in directories are cleaned once the directory is done
        final Stack<PosixFile> directories = new Stack<>();
        final Stack<PosixFile> directoryLinkTos = linkToRoot == null ? null : new Stack<>();
        final Stack<String> directorRelativePaths = new Stack<>();
        final Stack<Long> directoryModifyTimes = new Stack<>();
        final Stack<Set<String>> directoryContents = new Stack<>();

        // The actual cleaning and modify time setting is delayed to the end of the batch by adding
        // the lists of things to do here.
        final List<PosixFile> finalizeDirectories = new ArrayList<>();
        final List<PosixFile> finalizeLinkToDirectories = linkToRoot == null ? null : new ArrayList<>();
        final List<String> finalizeRelativePaths = new ArrayList<>();
        final List<Long> finalizeModifyTimes = new ArrayList<>();
        final List<Set<String>> finalizeContents = new ArrayList<>();

        // Continue until a batchSize of -1 (end of replication)
        int batchSize;
        activity.update("socket: read: Reading batchSize");
        while ((batchSize = in.readCompressedInt()) != -1) {
          final Integer batchSizeObj = batchSize;
          if (paths == null || paths.length < batchSize) {
            paths = new String[batchSize];
            isLogDirs = new boolean[batchSize];
            tempNewFiles = new PosixFile[batchSize];
            if (useCompression) {
              chunkingFroms = new PosixFile[batchSize];
              chunkingSizes = new long[batchSize];
              chunksMd5His = new long[batchSize][];
              chunksMd5Los = new long[batchSize][];
            }
            modifyTimes = new long[batchSize];
            results = new int[batchSize];
          }
          // Reset the directory finalization for each batch
          finalizeDirectories.clear();
          if (finalizeLinkToDirectories != null) {
            finalizeLinkToDirectories.clear();
          }
          finalizeRelativePaths.clear();
          finalizeModifyTimes.clear();
          finalizeContents.clear();

          for (int c = 0; c < batchSize; c++) {
            final Integer batchPosObj = c + 1;
            activity.update("socket: read: Reading exists ", batchPosObj, " of ", batchSizeObj);
            if (in.readBoolean()) {
              // Read the current file
              final String relativePath = in.readCompressedUTF();
              checkPath(relativePath);
              isLogDirs[c] = relativePath.startsWith("/logs/") || relativePath.startsWith("/var/log/");
              final String path = (isRecycling ? recycledPartialMirrorRoot : partialMirrorRoot) + relativePath;
              paths[c] = path;
              final PosixFile posixFile = new PosixFile(path);
              Stat stat = stat(activity, posixFile);
              final PosixFile parent = posixFile.getParent();
              //String linkToPath;
              final PosixFile linkTo;
              final Stat linkToStat;
              final PosixFile linkToParent;
              if (linkToRoot != null) {
                String linkToPath = linkToRoot + relativePath;
                linkTo = new PosixFile(linkToPath);
                linkToStat = stat(activity, linkTo);
                linkToParent = linkTo.getParent();
              } else {
                //linkToPath = null;
                linkTo = null;
                linkToStat = null;
                linkToParent = null;
              }
              activity.update("socket: read: Reading mode ", batchPosObj, " of ", batchSizeObj);
              final long mode = in.readLong();
              final long length;
              if (PosixFile.isRegularFile(mode)) {
                activity.update("socket: read: Reading length ", batchPosObj, " of ", batchSizeObj);
                length = in.readLong();
              } else {
                length = -1;
              }
              activity.update("socket: read: Reading uid ", batchPosObj, " of ", batchSizeObj);
              final int uid = in.readCompressedInt();
              activity.update("socket: read: Reading gid ", batchPosObj, " of ", batchSizeObj);
              final int gid = in.readCompressedInt();
              long modifyTime;
              // TODO: Once glibc >= 2.6 and kernel >= 2.6.22, can use lutimes call for symbolic links
              if (PosixFile.isSymLink(mode)) {
                modifyTime = -1;
              } else {
                activity.update("socket: read: Reading modifyTime ", batchPosObj, " of ", batchSizeObj);
                modifyTime = in.readLong();
              }
              modifyTimes[c] = modifyTime;
              //if (modifyTime<1000 && !PosixFile.isSymLink(mode) && log.isWarnEnabled()) {
              //  log.warn("Non-symlink modifyTime<1000: "+relativePath+": "+modifyTime);
              //}
              String symlinkTarget;
              if (PosixFile.isSymLink(mode)) {
                activity.update("socket: read: Reading symlinkTarget ", batchPosObj, " of ", batchSizeObj);
                symlinkTarget = in.readCompressedUTF();
                checkSymlinkTarget(symlinkTarget);
              } else {
                symlinkTarget = null;
              }
              long deviceId;
              if (
                  PosixFile.isBlockDevice(mode)
                      || PosixFile.isCharacterDevice(mode)
              ) {
                activity.update("socket: read: Reading deviceId ", batchPosObj, " of ", batchSizeObj);
                deviceId = in.readLong();
              } else {
                deviceId = -1;
              }
              final ModifyTimeAndSize modifyTimeAndSize = new ModifyTimeAndSize(modifyTime, length);

              // Cleanup extra entries in completed directories, setting modifyTime on the directories
              while (!directories.isEmpty()) {
                PosixFile dir = directories.peek();
                String dirPath = dir.getPath();
                if (!dirPath.endsWith("/")) {
                  dirPath += '/';
                }

                // If the current file starts with the current directory, continue
                if (path.startsWith(dirPath)) {
                  break;
                }

                // Otherwise, schedule to clean and complete the directory at the end of this batch
                directories.pop();
                finalizeDirectories.add(dir);
                if (finalizeLinkToDirectories != null) {
                  assert directoryLinkTos != null;
                  finalizeLinkToDirectories.add(directoryLinkTos.pop());
                }
                finalizeRelativePaths.add(directorRelativePaths.pop());
                finalizeModifyTimes.add(directoryModifyTimes.pop());
                finalizeContents.add(directoryContents.pop());
              }

              // Add the current to the directory
              if (!directoryContents.isEmpty()) {
                directoryContents.peek().add(path);
              }

              // Process the current file
              int result = AoservDaemonProtocol.FAILOVER_FILE_REPLICATION_NO_CHANGE;
              tempNewFiles[c] = null;
              if (useCompression) {
                chunkingFroms[c] = null;
                chunkingSizes[c] = Long.MIN_VALUE;
                chunksMd5His[c] = null;
                chunksMd5Los[c] = null;
              }
              if (PosixFile.isBlockDevice(mode)) {
                if (
                    stat.exists()
                        && (
                        !stat.isBlockDevice()
                            || stat.getDeviceIdentifier() != deviceId
                    )
                ) {
                  if (isTrace) {
                    logger.finer("Deleting to create block device: " + posixFile.getPath());
                  }
                  // Update caches
                  removing(modifyTimeAndSizeCaches, posixFile, stat, parent);
                  // Update filesystem
                  deleteRecursive(activity, posixFile);
                  stat = Stat.NOT_EXISTS;
                }
                if (!stat.exists()) {
                  mknod(activity, posixFile, mode, deviceId);
                  stat = stat(activity, posixFile);
                  if (linkTo != null) {
                    assert linkToStat != null;
                    // Only modified if not in last backup set, too
                    if (
                        !linkToStat.exists()
                            || !linkToStat.isBlockDevice()
                            || linkToStat.getDeviceIdentifier() != deviceId
                    ) {
                      result = AoservDaemonProtocol.FAILOVER_FILE_REPLICATION_MODIFIED;
                      updated(retention, postPassChecklist, relativePath);
                    }
                  } else {
                    result = AoservDaemonProtocol.FAILOVER_FILE_REPLICATION_MODIFIED;
                    updated(retention, postPassChecklist, relativePath);
                  }
                }
              } else if (PosixFile.isCharacterDevice(mode)) {
                if (
                    stat.exists()
                        && (
                        !stat.isCharacterDevice()
                            || stat.getDeviceIdentifier() != deviceId
                    )
                ) {
                  if (isTrace) {
                    logger.finer("Deleting to create character device: " + posixFile.getPath());
                  }
                  // Update caches
                  removing(modifyTimeAndSizeCaches, posixFile, stat, parent);
                  // Update filesystem
                  deleteRecursive(activity, posixFile);
                  stat = Stat.NOT_EXISTS;
                }
                if (!stat.exists()) {
                  mknod(activity, posixFile, mode, deviceId);
                  stat = stat(activity, posixFile);
                  if (linkTo != null) {
                    assert linkToStat != null;
                    // Only modified if not in last backup set, too
                    if (
                        !linkToStat.exists()
                            || !linkToStat.isCharacterDevice()
                            || linkToStat.getDeviceIdentifier() != deviceId
                    ) {
                      result = AoservDaemonProtocol.FAILOVER_FILE_REPLICATION_MODIFIED;
                      updated(retention, postPassChecklist, relativePath);
                    }
                  } else {
                    result = AoservDaemonProtocol.FAILOVER_FILE_REPLICATION_MODIFIED;
                    updated(retention, postPassChecklist, relativePath);
                  }
                }
              } else if (PosixFile.isDirectory(mode)) {
                if (
                    stat.exists()
                        && !stat.isDirectory()
                ) {
                  if (isTrace) {
                    logger.finer("Deleting to create directory: " + posixFile.getPath());
                  }
                  // Update caches
                  removing(modifyTimeAndSizeCaches, posixFile, stat, parent);
                  // Update filesystem
                  deleteRecursive(activity, posixFile);
                  stat = Stat.NOT_EXISTS;
                }
                if (!stat.exists()) {
                  mkdir(activity, posixFile);
                  stat = stat(activity, posixFile);
                  if (linkTo != null) {
                    assert linkToStat != null;
                    // Only modified if not in last backup set, too
                    if (
                        !linkToStat.exists()
                            || !linkToStat.isDirectory()
                    ) {
                      result = AoservDaemonProtocol.FAILOVER_FILE_REPLICATION_MODIFIED;
                      updated(retention, postPassChecklist, relativePath);
                    }
                  } else {
                    result = AoservDaemonProtocol.FAILOVER_FILE_REPLICATION_MODIFIED;
                    updated(retention, postPassChecklist, relativePath);
                  }
                } else if (stat.getModifyTime() != modifyTime) {
                  result = AoservDaemonProtocol.FAILOVER_FILE_REPLICATION_MODIFIED;
                  updated(retention, postPassChecklist, relativePath);
                }
                directories.push(posixFile);
                if (directoryLinkTos != null) {
                  directoryLinkTos.push(linkTo);
                }
                directorRelativePaths.push(relativePath);
                directoryModifyTimes.push(modifyTime);
                directoryContents.push(new HashSet<>());
              } else if (PosixFile.isFifo(mode)) {
                if (
                    stat.exists()
                        && !stat.isFifo()
                ) {
                  if (isTrace) {
                    logger.finer("Deleting to create FIFO: " + posixFile.getPath());
                  }
                  // Update caches
                  removing(modifyTimeAndSizeCaches, posixFile, stat, parent);
                  // Update filesystem
                  deleteRecursive(activity, posixFile);
                  stat = Stat.NOT_EXISTS;
                }
                if (!stat.exists()) {
                  mkfifo(activity, posixFile, mode);
                  stat = stat(activity, posixFile);
                  if (linkTo != null) {
                    assert linkToStat != null;
                    // Only modified if not in last backup set, too
                    if (
                        !linkToStat.exists()
                            || !linkToStat.isFifo()
                    ) {
                      result = AoservDaemonProtocol.FAILOVER_FILE_REPLICATION_MODIFIED;
                      updated(retention, postPassChecklist, relativePath);
                    }
                  } else {
                    result = AoservDaemonProtocol.FAILOVER_FILE_REPLICATION_MODIFIED;
                    updated(retention, postPassChecklist, relativePath);
                  }
                }
              } else if (PosixFile.isRegularFile(mode)) {
                /*
                 * When receiving a regular file, we will always look in the current directory and the linkTo directory
                 * for an exact match with the same filename (based on length and mtime only).  If the exact match is in the
                 * current directory, then no data will be transferred.  If the exact match is in the linkTo directory,
                 * then link to the current directory and no data will be transferred.
                 *
                 * There is an exception made for encrypted filesystem blockfiles.  Because the mtime of the underlying
                 * file is not modified when the contents of the file are modified, we must always use the chunking algorithm
                 * described below.
                 *
                 * If an exact match is not found with the current filename, and we are in a log directory, the
                 * entire current directory and the entire linkTo directory will be searched for a match based on
                 * length and mtime.  If found in either place, any existing file will be moved to a temp
                 * filename, and the found one will be linked to the final filename - no data will be sent.
                 *
                 * If an exact match is not found in either regular mode or log directory mode, we will next try
                 * to chunk the contents.  The resolution of what to chunk to depends on if we are currently recycling.
                 * When recycling, we will first try the linkTo file then the current directory.  When not recycling
                 * we will try the current directory and then the linkTo directory.
                 */
                if (stat.exists()) {
                  if (stat.isDirectory()) {
                    // If there is a directory that has now been replaced with a regular file, just delete the directory recursively to avoid confusion in the following code

                    // Update caches
                    removing(modifyTimeAndSizeCaches, posixFile, stat, parent);
                    deleteRecursive(activity, posixFile);
                    stat = Stat.NOT_EXISTS;
                  } else if (!stat.isRegularFile()) {
                    // If there is any non-regular file that has now been replaced with a regular file, just delete the symlink to avoid confusion in the following code

                    // Update caches
                    removing(modifyTimeAndSizeCaches, posixFile, stat, parent);
                    // Update the filesystem
                    delete(activity, posixFile);
                    stat = Stat.NOT_EXISTS;
                  }
                  // At this point, the file either exists and is a regular file, or does not exist
                }
                // Look in the current directory for an exact match
                final boolean isEncryptedLoopFile = isEncryptedLoopFile(relativePath);
                if (
                    !isEncryptedLoopFile
                        && stat.exists()
                        && stat.getSize() == length
                        && stat.getModifyTime() == modifyTime
                ) {
                  assert stat.isRegularFile() : "All non-regular files should have been deleted";
                  // Found in current directory, simply use default result = NO_CHANGE
                } else {
                  // Steps below this need to know if we are in a log directory or not
                  final boolean isLogDir = isLogDirs[c];

                  // Look in the linkTo directory for an exact match
                  if (
                      !isEncryptedLoopFile
                          && linkToStat != null
                          && linkToStat.exists()
                          && linkToStat.isRegularFile()
                          && linkToStat.getSize() == length
                          && linkToStat.getModifyTime() == modifyTime
                  ) {
                    // Found match in linkTo, link to linkTo directory
                    if (stat.exists()) {
                      assert stat.isRegularFile() : "All non-regular files should have been deleted";
                      // If we are in a log directory, move the regular file out of the way into a temp file (for possible later reuse)
                      if (isLogDir) {
                        // Move to a new temp filename for later reuse
                        PosixFile tempPosixFile = mktemp(activity, posixFile);
                        // Update the filesystem
                        rename(activity, posixFile, tempPosixFile);
                        link(activity, posixFile, linkTo);
                        stat = stat(activity, posixFile);
                        // Update caches
                        renamed(modifyTimeAndSizeCaches, posixFile, tempPosixFile, parent);
                        added(activity, modifyTimeAndSizeCaches, posixFile, parent, modifyTimeAndSize);
                      } else {
                        // Delete and link is OK because this is using a linkTo directory (therefore not in failover mode)
                        // Update caches
                        removing(modifyTimeAndSizeCaches, posixFile, stat, parent);
                        // Update the filesystem
                        delete(activity, posixFile);
                        link(activity, posixFile, linkTo);
                        stat = stat(activity, posixFile);
                        // Update caches
                        added(activity, modifyTimeAndSizeCaches, posixFile, parent, modifyTimeAndSize);
                      }
                    } else {
                      // Update the filesystem
                      link(activity, posixFile, linkTo);
                      stat = stat(activity, posixFile);
                      // Update caches
                      added(activity, modifyTimeAndSizeCaches, posixFile, parent, modifyTimeAndSize);
                    }
                  } else {
                    // If we are in a log directory, search all regular files in current directory and linkTo directory for matching length and mtime
                    // link to it if found
                    boolean linkedOldLogFile = false;
                    if (!isEncryptedLoopFile && isLogDir) {
                      // Look for another file with the same size and modify time in this partial directory
                      PosixFile oldLog = null;
                      ModifyTimeAndSizeCache modifyTimeAndSizeCache =
                          // isEmpty checked first to avoid hashing for the common case of no caches
                          modifyTimeAndSizeCaches.isEmpty()
                              ? null
                              : modifyTimeAndSizeCaches.get(parent);
                      if (modifyTimeAndSizeCache == null) {
                        // Not in cache, load from disk
                        modifyTimeAndSizeCaches.put(parent, modifyTimeAndSizeCache = new ModifyTimeAndSizeCache(activity, parent));
                      }
                      List<String> matchedFilenames = modifyTimeAndSizeCache.getFilenamesByModifyTimeAndSize(modifyTimeAndSize);
                      if (matchedFilenames != null && !matchedFilenames.isEmpty()) {
                        oldLog = new PosixFile(parent, matchedFilenames.get(0), false);
                      }

                      if (oldLog == null && linkTo != null) {
                        // Look for another file with the same size and modify time in the link to directory (previous backup pass).

                        // New implementation is used first because it will load the directory physically from
                        // disk first, thus the old implementation will have the advantage of the disk cache.
                        // Therefore, if the new implementation is still faster, it is clearly the winner.
                        ModifyTimeAndSizeCache modifyTimeAndSizeCache2 =
                            // isEmpty checked first to avoid hashing for the common case of no caches
                            modifyTimeAndSizeCaches.isEmpty()
                                ? null
                                : modifyTimeAndSizeCaches.get(linkToParent);
                        if (modifyTimeAndSizeCache2 == null) {
                          // Not in cache, load from disk
                          modifyTimeAndSizeCaches.put(linkToParent, modifyTimeAndSizeCache2 = new ModifyTimeAndSizeCache(activity, linkToParent));
                        }
                        List<String> matchedFilenames2 = modifyTimeAndSizeCache2.getFilenamesByModifyTimeAndSize(modifyTimeAndSize);
                        if (matchedFilenames2 != null && !matchedFilenames2.isEmpty()) {
                          oldLog = new PosixFile(linkToParent, matchedFilenames2.get(0), false);
                        }
                      }
                      if (oldLog != null) {
                        if (stat.exists()) {
                          assert stat.isRegularFile() : "All non-regular files should have been deleted";
                          // Move to a new temp filename for later reuse
                          PosixFile tempPosixFile = mktemp(activity, posixFile);
                          // Update filesystem
                          if (retention == 1) {
                            // Failover mode does a more cautious link to temp and rename over to avoid
                            // any moment where there is no file in the path of uf
                            delete(activity, tempPosixFile);
                            link(activity, tempPosixFile, posixFile);
                            PosixFile tempPosixFile2 = mktemp(activity, posixFile);
                            delete(activity, tempPosixFile2);
                            link(activity, tempPosixFile2, oldLog);
                            rename(activity, tempPosixFile2, posixFile);
                          } else {
                            // Backup mode uses a more efficient approach because partial states are OK
                            rename(activity, posixFile, tempPosixFile);
                            link(activity, posixFile, oldLog);
                          }
                          // Update cache
                          renamed(modifyTimeAndSizeCaches, posixFile, tempPosixFile, parent);
                          added(activity, modifyTimeAndSizeCaches, posixFile, parent, modifyTimeAndSize);
                        } else {
                          // Update filesystem
                          link(activity, posixFile, oldLog);
                          // Update cache
                          added(activity, modifyTimeAndSizeCaches, posixFile, parent, modifyTimeAndSize);
                        }
                        stat = stat(activity, posixFile);
                        result = AoservDaemonProtocol.FAILOVER_FILE_REPLICATION_MODIFIED;
                        updated(retention, postPassChecklist, relativePath);
                        linkedOldLogFile = true;
                      }
                    }
                    if (!linkedOldLogFile) {
                      boolean chunkingFile = false;
                      if (
                          useCompression
                              // File is not so large that chunking can't possibly store md5's in the arrays (larger than 2 ^ (31 + 20) = 2 Pebibytes currently)
                              && (
                              length
                                  < (((long) Integer.MAX_VALUE) << AoservDaemonProtocol.FAILOVER_FILE_REPLICATION_CHUNK_SIZE_BITS)
                          )
                      ) {
                        // Next we will try chunking.  For chunking, we will start by determining what we are chunking from.
                        PosixFile chunkingFrom;
                        Stat chunkingFromStat;
                        if (isRecycling) {
                          // When recycling, try linkToUF then uf
                          if (
                              linkToStat != null
                                  && linkToStat.exists()
                                  && linkToStat.isRegularFile()
                          ) {
                            chunkingFrom = linkTo;
                            chunkingFromStat = linkToStat;
                          } else if (
                              stat.exists()
                          ) {
                            assert stat.isRegularFile() : "All non-regular files should have been deleted";
                            chunkingFrom = posixFile;
                            chunkingFromStat = stat;
                          } else {
                            chunkingFrom = null;
                            chunkingFromStat = null;
                          }
                        } else {
                          // When not recycling, try uf then linkToUF
                          if (
                              stat.exists()
                          ) {
                            assert stat.isRegularFile() : "All non-regular files should have been deleted";
                            chunkingFrom = posixFile;
                            chunkingFromStat = stat;
                          } else if (
                              linkToStat != null
                                  && linkToStat.exists()
                                  && linkToStat.isRegularFile()
                          ) {
                            chunkingFrom = linkTo;
                            chunkingFromStat = linkToStat;
                          } else {
                            chunkingFrom = null;
                            chunkingFromStat = null;
                          }
                        }
                        if (chunkingFrom != null) {
                          assert chunkingFromStat != null;
                          assert md5 != null;
                          // Now we figure out what we are chunking to.
                          if (
                              stat.exists()
                                  || retention == 1
                                  || (!isRecycling && linkTo != null)
                          ) {
                            // When uf exists, chunk to a temp file
                            PosixFile tempPosixFile = mktemp(activity, posixFile);
                            tempNewFiles[c] = tempPosixFile;
                            if (isTrace) {
                              logger.finer("Using temp file (chunked): " + tempPosixFile.getPath());
                            }
                            // modifyTimeAndSizeCaches is not updated here, it will be updated below when the data is received
                          } else {
                            // Chunk in-place
                            if (!stat.exists()) {
                              touch(activity, posixFile);
                              stat = stat(activity, posixFile);
                              // modifyTimeAndSizeCaches is not updated here, it will be updated below when the data is received
                            }
                          }

                          // Build the list of MD5 hashes per chunk
                          final long chunkingSize = Math.min(length, chunkingFromStat.getSize());
                          final int numChunks;
                            {
                              long numChunksL = chunkingSize >> AoservDaemonProtocol.FAILOVER_FILE_REPLICATION_CHUNK_SIZE_BITS;
                              if ((chunkingSize & (AoservDaemonProtocol.FAILOVER_FILE_REPLICATION_CHUNK_SIZE - 1)) != 0) {
                                numChunksL++;
                              }
                              numChunks = SafeMath.castInt(numChunksL);
                            }
                          final long[] md5His = new long[numChunks];
                          final long[] md5Los = new long[numChunks];
                          // Generate the MD5 hashes for the current file
                          final InputStream fileIn = openIn(activity, chunkingFrom);
                          try {
                            long filePos = 0;
                            for (int chunkIndex = 0; chunkIndex < numChunks; chunkIndex++) {
                              int chunkSize;
                              if (chunkIndex < (numChunks - 1)) {
                                // All except last chunk are full sized
                                chunkSize = AoservDaemonProtocol.FAILOVER_FILE_REPLICATION_CHUNK_SIZE;
                              } else {
                                assert chunkIndex == (numChunks - 1);
                                // Last chunk may be partial
                                chunkSize = SafeMath.castInt(chunkingSize - filePos);
                                assert chunkSize > 0 && chunkSize <= AoservDaemonProtocol.FAILOVER_FILE_REPLICATION_CHUNK_SIZE;
                              }
                              // Read chunk fully
                              activity.update("file: md5: ", chunkingFrom, " at ", filePos);
                              int pos = 0;
                              while (pos < chunkSize) {
                                int ret = fileIn.read(chunkBuffer, pos, chunkSize - pos);
                                if (ret == -1) {
                                  throw new EOFException("End of file while reading chunkingFrom: " + chunkingFrom);
                                }
                                filePos += ret;
                                pos += ret;
                              }
                              md5.Init();
                              md5.Update(chunkBuffer, 0, chunkSize);
                              byte[] md5Bytes = md5.Final();
                              md5His[chunkIndex] = MD5.getMD5Hi(md5Bytes);
                              md5Los[chunkIndex] = MD5.getMD5Lo(md5Bytes);
                            }
                            assert filePos == chunkingSize : "Expected chunking must have been read fully";
                          } finally {
                            close(activity, chunkingFrom, fileIn);
                          }
                          chunkingFroms[c] = chunkingFrom;
                          chunkingSizes[c] = chunkingSize;
                          chunksMd5His[c] = md5His;
                          chunksMd5Los[c] = md5Los;
                          chunkingFile = true;
                          result = AoservDaemonProtocol.FAILOVER_FILE_REPLICATION_MODIFIED_REQUEST_DATA_CHUNKED;
                          updated(retention, postPassChecklist, relativePath);
                        }
                      }
                      if (!chunkingFile) {
                        result = AoservDaemonProtocol.FAILOVER_FILE_REPLICATION_MODIFIED_REQUEST_DATA;
                        updated(retention, postPassChecklist, relativePath);
                        // If the file doesn't exist, will download in-place
                        if (!stat.exists()) {
                          touch(activity, posixFile);
                          stat = stat(activity, posixFile);
                        } else {
                          // Build new temp file
                          PosixFile tempPosixFile = mktemp(activity, posixFile);
                          tempNewFiles[c] = tempPosixFile;
                          if (isTrace) {
                            logger.finer("Using temp file (not chunked): " + tempPosixFile.getPath());
                          }
                          // modifyTimeAndSizeCaches is not updated here, it will be updated below when the data is received
                        }
                      }
                    }
                  }
                }
              } else if (PosixFile.isSymLink(mode)) {
                if (
                    stat.exists()
                        && (
                        !stat.isSymLink()
                            || !readLink(activity, posixFile).equals(symlinkTarget)
                    )
                ) {
                  if (isTrace) {
                    logger.finer("Deleting to create sybolic link: " + posixFile.getPath());
                  }
                  // Update cache
                  removing(modifyTimeAndSizeCaches, posixFile, stat, parent);
                  // Update filesystem
                  deleteRecursive(activity, posixFile);
                  stat = Stat.NOT_EXISTS;
                }
                if (!stat.exists()) {
                  activity.update("file: symLink: ", posixFile, " to ", symlinkTarget);
                  posixFile.symLink(symlinkTarget);
                  stat = stat(activity, posixFile);
                  if (linkToStat != null) {
                    // Only modified if not in last backup set, too
                    if (
                        !linkToStat.exists()
                            || !linkToStat.isSymLink()
                            || !readLink(activity, linkTo).equals(symlinkTarget)
                    ) {
                      result = AoservDaemonProtocol.FAILOVER_FILE_REPLICATION_MODIFIED;
                      updated(retention, postPassChecklist, relativePath);
                    }
                  } else {
                    result = AoservDaemonProtocol.FAILOVER_FILE_REPLICATION_MODIFIED;
                    updated(retention, postPassChecklist, relativePath);
                  }
                }
              } else {
                throw new IOException("Unknown mode type: " + Long.toOctalString(mode & PosixFile.TYPE_MASK));
              }

              // Update the permissions (mode)
              PosixFile effectivePosixFile;
              Stat effectiveStat;
              if (tempNewFiles[c] == null) {
                effectivePosixFile = posixFile;
                effectiveStat = stat;
              } else {
                effectivePosixFile = tempNewFiles[c];
                effectiveStat = stat(activity, effectivePosixFile);
              }
              if (
                  !PosixFile.isSymLink(mode)
                      && (
                      (effectiveStat.getRawMode() & (PosixFile.TYPE_MASK | PosixFile.PERMISSION_MASK))
                          != (mode & (PosixFile.TYPE_MASK | PosixFile.PERMISSION_MASK))
                  )
              ) {
                //try {
                if (retention != 1) {
                  copyIfHardLinked(activity, effectivePosixFile, effectiveStat);
                }
                activity.update("file: setMode: ", effectivePosixFile);
                effectivePosixFile.setMode(mode & (PosixFile.TYPE_MASK | PosixFile.PERMISSION_MASK));
                effectiveStat = stat(activity, effectivePosixFile);
                //} catch (FileNotFoundException err) {
                //  logger.log(Level.WARNING, "path="+path+", mode="+Long.toOctalString(mode), err);
                //}
                if (result == AoservDaemonProtocol.FAILOVER_FILE_REPLICATION_NO_CHANGE) {
                  // Only modified if wrong permission in last backup set, too
                  if (
                      linkToStat == null
                          || !linkToStat.exists()
                          || (
                          (linkToStat.getRawMode() & (PosixFile.TYPE_MASK | PosixFile.PERMISSION_MASK))
                              != (mode & (PosixFile.TYPE_MASK | PosixFile.PERMISSION_MASK))
                      )
                  ) {
                    result = AoservDaemonProtocol.FAILOVER_FILE_REPLICATION_MODIFIED;
                    updated(retention, postPassChecklist, relativePath);
                  }
                }
              }

              // Update the ownership
              if (
                  effectiveStat.getUid() != uid
                      // TODO: Store GID in xattr (if not 0)
                      || effectiveStat.getGid() != (quotaGid == -1 ? gid : quotaGid)
              ) {
                if (retention != 1) {
                  copyIfHardLinked(activity, effectivePosixFile, effectiveStat);
                }
                // TODO: Store GID in xattr (if not 0)
                activity.update("file: chown: ", effectivePosixFile);
                effectivePosixFile.chown(uid, (quotaGid == -1 ? gid : quotaGid));
                effectiveStat = stat(activity, effectivePosixFile);
                if (result == AoservDaemonProtocol.FAILOVER_FILE_REPLICATION_NO_CHANGE) {
                  // Only modified if not in last backup set, too
                  if (
                      linkToStat == null
                          || !linkToStat.exists()
                          || linkToStat.getUid() != uid
                          // TODO: Store GID in xattr
                          || linkToStat.getGid() != (quotaGid == -1 ? gid : quotaGid)
                  ) {
                    result = AoservDaemonProtocol.FAILOVER_FILE_REPLICATION_MODIFIED;
                    updated(retention, postPassChecklist, relativePath);
                  }
                }
              }

              // Update the modified time
              if (
                  !PosixFile.isSymLink(mode)
                      && !PosixFile.isRegularFile(mode) // Regular files will be re-transferred below when their modified times don't match, so no need to set the modified time here
                      && !PosixFile.isDirectory(mode) // Directory modification times are set on the way out of the directories
                      && effectiveStat.getModifyTime() != modifyTime
              ) {
                if (retention != 1) {
                  copyIfHardLinked(activity, effectivePosixFile, effectiveStat);
                }
                activity.update("file: utime: ", effectivePosixFile);
                effectivePosixFile.utime(effectiveStat.getAccessTime(), modifyTime);
                effectiveStat = stat(activity, effectivePosixFile);
                if (result == AoservDaemonProtocol.FAILOVER_FILE_REPLICATION_NO_CHANGE) {
                  // Only modified if not in last backup set, too
                  if (
                      linkToStat == null
                          || !linkToStat.exists()
                          || linkToStat.getModifyTime() != modifyTime
                  ) {
                    result = AoservDaemonProtocol.FAILOVER_FILE_REPLICATION_MODIFIED;
                    updated(retention, postPassChecklist, relativePath);
                  }
                }
              }
              results[c] = result;
            } else {
              paths[c] = null;
            }
          }

          // Write the results
          activity.update("socket: write: Writing batch results");
          out.write(AoservDaemonProtocol.NEXT);
          for (int c = 0; c < batchSize; c++) {
            if (paths[c] != null) {
              final Integer batchPosObj = c + 1;
              int result = results[c];
              activity.update("socket: write: Writing result ", batchPosObj, " of ", batchSizeObj);
              out.write(result);
              if (result == AoservDaemonProtocol.FAILOVER_FILE_REPLICATION_MODIFIED_REQUEST_DATA_CHUNKED) {
                long[] md5His = chunksMd5His[c];
                long[] md5Los = chunksMd5Los[c];
                int md5sSize = md5His.length;
                assert md5Los.length == md5sSize;
                activity.update("socket: write: Writing chunk md5s ", batchPosObj, " of ", batchSizeObj);
                out.writeLong(chunkingSizes[c]);
                for (int d = 0; d < md5sSize; d++) {
                  out.writeLong(md5His[d]);
                  out.writeLong(md5Los[d]);
                }
              }
            }
          }

          // Flush the results
          out.flush();

          // Store incoming data
          for (int c = 0; c < batchSize; c++) {
            String path = paths[c];
            if (path != null) {
              int result = results[c];
              if (
                  result == AoservDaemonProtocol.FAILOVER_FILE_REPLICATION_MODIFIED_REQUEST_DATA
                      || result == AoservDaemonProtocol.FAILOVER_FILE_REPLICATION_MODIFIED_REQUEST_DATA_CHUNKED
              ) {
                // tempNewFiles[c] is only possibly set for the data transfer results
                PosixFile tempPosixFile = tempNewFiles[c];
                PosixFile uf = new PosixFile(path);
                final PosixFile ufParent = uf.getParent();

                // Load into the temporary file or directly to the file (based on above calculations)
                PosixFile fileOutPosixFile = tempPosixFile == null ? uf : tempPosixFile;
                OutputStream fileOut = openOut(activity, fileOutPosixFile);
                boolean newFileComplete = false;
                try {
                  long filePos = 0;
                  if (result == AoservDaemonProtocol.FAILOVER_FILE_REPLICATION_MODIFIED_REQUEST_DATA) {
                    // Only the last chunk may be partial
                    int lastChunkSize = AoservDaemonProtocol.FAILOVER_FILE_REPLICATION_CHUNK_SIZE;
                    int response;
                    while (true) {
                      activity.update("socket: read: ", uf, " at ", filePos);
                      response = in.read();
                      if (response != AoservDaemonProtocol.NEXT) {
                        break;
                      }
                      if (lastChunkSize < AoservDaemonProtocol.FAILOVER_FILE_REPLICATION_CHUNK_SIZE) {
                        throw new IOException("Only the last chunk may be partial");
                      }
                      int blockLen = in.readCompressedInt();
                      if (blockLen <= 0 || blockLen > AoservDaemonProtocol.FAILOVER_FILE_REPLICATION_CHUNK_SIZE) {
                        throw new IOException("Invalid block length: " + blockLen);
                      }
                      in.readFully(chunkBuffer, 0, blockLen);
                      activity.update("file: write: ", fileOutPosixFile, " at ", filePos);
                      fileOut.write(chunkBuffer, 0, blockLen);
                      filePos += blockLen;
                      lastChunkSize = blockLen;
                    }
                    if (response == -1) {
                      throw new EOFException();
                    } else if (response != AoservDaemonProtocol.DONE) {
                      throw new IOException("Unexpected response code: " + response);
                    }
                  } else if (result == AoservDaemonProtocol.FAILOVER_FILE_REPLICATION_MODIFIED_REQUEST_DATA_CHUNKED) {
                    if (!useCompression) {
                      throw new IOException("Not using compression, chunked transfer not supported");
                    }
                    assert chunkingFroms != null;
                    PosixFile chunkingFrom = chunkingFroms[c];
                    long chunkingSize = chunkingSizes[c];
                    RandomAccessFile chunkingFromRaf = openInRaf(activity, chunkingFrom);
                    try {
                      int partialChunkPos = 0;
                      int response;
                      while (true) {
                        activity.update("socket: read: chunk result of ", uf, " at ", filePos);
                        response = in.read();
                        if (response == AoservDaemonProtocol.NEXT) {
                          activity.update("socket: read: ", uf, " at ", filePos);
                          int chunkLen = in.readCompressedInt();
                          if (chunkLen < 0 || (partialChunkPos + chunkLen) > AoservDaemonProtocol.FAILOVER_FILE_REPLICATION_CHUNK_SIZE) {
                            throw new IOException("Invalid chunk length: " + chunkLen);
                          }
                          in.readFully(chunkBuffer, partialChunkPos, chunkLen);
                          partialChunkPos += chunkLen;
                          if (partialChunkPos == AoservDaemonProtocol.FAILOVER_FILE_REPLICATION_CHUNK_SIZE) {
                            // Full chunk to write
                            activity.update("file: write: ", fileOutPosixFile, " at ", filePos);
                            fileOut.write(chunkBuffer, 0, AoservDaemonProtocol.FAILOVER_FILE_REPLICATION_CHUNK_SIZE);
                            filePos += AoservDaemonProtocol.FAILOVER_FILE_REPLICATION_CHUNK_SIZE;
                            partialChunkPos = 0;
                          }
                        } else if (response == AoservDaemonProtocol.NEXT_CHUNK) {
                          if (partialChunkPos != 0) {
                            throw new IOException("Chunk matched after partial chunk");
                          }
                            // Get the values from the old file (chunk matches)
                            {
                              long chunkSizeL = chunkingSize - filePos;
                              if (chunkSizeL < 0) {
                                throw new IOException("Client sent chunk beyond end of server chunks");
                              }
                              if (chunkSizeL >= AoservDaemonProtocol.FAILOVER_FILE_REPLICATION_CHUNK_SIZE) {
                                partialChunkPos = AoservDaemonProtocol.FAILOVER_FILE_REPLICATION_CHUNK_SIZE;
                              } else {
                                partialChunkPos = (int) chunkSizeL;
                              }
                            }
                          activity.update("file: read: ", chunkingFrom, " at ", filePos);
                          chunkingFromRaf.seek(filePos);
                          chunkingFromRaf.readFully(chunkBuffer, 0, partialChunkPos);
                          if (partialChunkPos == AoservDaemonProtocol.FAILOVER_FILE_REPLICATION_CHUNK_SIZE) {
                            // Full chunk to write
                            activity.update("file: write: ", fileOutPosixFile, " at ", filePos);
                            fileOut.write(chunkBuffer, 0, AoservDaemonProtocol.FAILOVER_FILE_REPLICATION_CHUNK_SIZE);
                            filePos += AoservDaemonProtocol.FAILOVER_FILE_REPLICATION_CHUNK_SIZE;
                            partialChunkPos = 0;
                          }
                        } else {
                          break;
                        }
                      }
                      // Write any incomplete partial chunk data
                      if (partialChunkPos != 0) {
                        activity.update("file: write: ", fileOutPosixFile, " at ", filePos);
                        fileOut.write(chunkBuffer, 0, partialChunkPos);
                        filePos += partialChunkPos;
                        partialChunkPos = 0;
                      }
                      if (response == -1) {
                        throw new EOFException();
                      } else if (response != AoservDaemonProtocol.DONE) {
                        throw new IOException("Unexpected response code: " + response);
                      }
                    } finally {
                      close(activity, chunkingFrom, chunkingFromRaf);
                    }
                  } else {
                    throw new RuntimeException("Unexpected value for result: " + result);
                  }
                  newFileComplete = true;
                } finally {
                  close(activity, fileOutPosixFile, fileOut);

                  // If the new file is incomplete for any reason (presumably due to an exception)
                  // and we are doing a backup to a temporary file, move the temp file over the old file
                  // if it is longer
                  if (
                      !newFileComplete
                          && retention != 1
                          && tempPosixFile != null
                  ) {
                    Stat stat = stat(activity, uf);
                    if (!stat.exists()) {
                      // If it doesn't exist, can't compare file sizes, just rename
                      if (isFine) {
                        logger.fine("Renaming partial temp file to final filename because final filename doesn't exist: " + uf.getPath());
                      }
                      renameToNoExists(logger, activity, tempPosixFile, uf);
                      // This should only happen during exceptions, so no need to keep directory caches synchronized
                    } else {
                      long size = stat.getSize();
                      long tempLogSize = stat(activity, tempPosixFile).getSize();
                      if (tempLogSize > size) {
                        if (isFine) {
                          logger.fine("Renaming partial temp file to final filename because temp file is longer than the final file: " + uf.getPath());
                        }
                        rename(activity, tempPosixFile, uf);
                        // This should only happen during exceptions, so no need to keep directory caches synchronized
                      }
                    }
                  }
                }
                activity.update("file: utime: ", fileOutPosixFile);
                fileOutPosixFile.utime(stat(activity, fileOutPosixFile).getAccessTime(), modifyTimes[c]);
                Stat ufStat = stat(activity, uf);
                if (tempPosixFile != null) {
                  if (ufStat.exists()) {
                    if (ufStat.isRegularFile()) {
                      if (isLogDirs[c]) {
                        // Move to a new temp filename for later reuse
                        PosixFile tempLog = mktemp(activity, uf);
                        // Update filesystem
                        if (retention == 1) {
                          // Failover mode does a more cautious link to temp and rename over to avoid
                          // any moment where there is no file in the path of uf
                          delete(activity, tempLog);
                          link(activity, tempLog, uf);
                          rename(activity, tempPosixFile, uf);
                          ufStat = stat(activity, uf);
                        } else {
                          // Backup mode uses a more efficient approach because partial states are OK
                          rename(activity, uf, tempLog);
                          rename(activity, tempPosixFile, uf);
                          ufStat = stat(activity, uf);
                        }
                        // Update cache (cache update counted as removeByValue and then add because cache renaming method expects renameTo to not exist
                        renamed(modifyTimeAndSizeCaches, uf, tempLog, ufParent);
                      } else {
                        // Not a log directory, just replace old regular file
                        // Update cache (cache update counted as removeByValue and then add because cache renaming method expects renameTo to not exist
                        removing(modifyTimeAndSizeCaches, uf, ufStat, ufParent);
                        // Update filesystem
                        rename(activity, tempPosixFile, uf);
                        ufStat = stat(activity, uf);
                      }
                    } else {
                      // Update cache
                      removing(modifyTimeAndSizeCaches, uf, ufStat, ufParent);
                      // Update filesystem
                      deleteRecursive(activity, uf);
                      rename(activity, tempPosixFile, uf);
                      ufStat = stat(activity, uf);
                    }
                  } else {
                    rename(activity, tempPosixFile, uf);
                    ufStat = stat(activity, uf);
                  }
                }
                // Update cache (cache update counted as removeByValue and then add because cache renaming method expects renameTo to not exist
                added(activity, modifyTimeAndSizeCaches, uf, ufParent, new ModifyTimeAndSize(ufStat.getModifyTime(), ufStat.getSize()));
              }
            }
          }

          // For any directories that were completed during this batch, removeByValue caches, clean extra files and set its modify time
          for (int c = 0; c < finalizeDirectories.size(); c++) {
            final PosixFile directory = finalizeDirectories.get(c);
            final PosixFile linkToDirectory = finalizeLinkToDirectories == null ? null : finalizeLinkToDirectories.get(c);
            final String relativePath = finalizeRelativePaths.get(c);
            final long dirModifyTime = finalizeModifyTimes.get(c);
            final Set<String> dirContents = finalizeContents.get(c);
            // Remove from the caches since we are done with the directory entirely for this pass
            if (!modifyTimeAndSizeCaches.isEmpty()) {
              modifyTimeAndSizeCaches.remove(directory);
            }
            if (linkToDirectory != null && !modifyTimeAndSizeCaches.isEmpty()) {
              modifyTimeAndSizeCaches.remove(linkToDirectory);
            }
            // Remove extra files
            String dirPath = directory.getPath();
            if (!dirPath.endsWith("/")) {
              dirPath += '/';
            }
            String[] list = list(activity, directory);
            if (list != null) {
              for (String filename : list) {
                String fullpath = dirPath + filename;
                if (!dirContents.contains(fullpath)) {
                  if (deleteOnCleanup(fromServer, retention, relativePath + '/' + filename, replicatedMysqlServers, replicatedMysqlMinorVersions)) {
                    if (isTrace) {
                      logger.finer("Deleting extra file: " + fullpath);
                    }
                    try {
                      PosixFile deleteMe = new PosixFile(fullpath);
                      deleteRecursive(activity, deleteMe);
                    } catch (FileNotFoundException err) {
                      logger.log(Level.SEVERE, "fullpath=" + fullpath, err);
                    }
                  }
                }
              }
            }
            // Set the modified time
            Stat dirStat = stat(activity, directory);
            if (dirStat.getModifyTime() != dirModifyTime) {
              activity.update("file: utime: ", directory);
              directory.utime(dirStat.getAccessTime(), dirModifyTime);
            }
          }
        }

        // modifyTimeAndSizeCaches is no longer used after this, this makes sure
        int modifyTimeAndSizeCachesSize = modifyTimeAndSizeCaches.size();
        modifyTimeAndSizeCaches = null;

        // Clean all remaining directories all the way to /, setting modifyTime on the directories
        while (!directories.isEmpty()) {
          PosixFile directory = directories.peek();
          String dirPath = directory.getPath();
          if (!dirPath.endsWith("/")) {
            dirPath += '/';
          }

          // Otherwise, clean and complete the directory
          directories.pop();
          if (directoryLinkTos != null) {
            // Just to keep the stacks uniform between them
            directoryLinkTos.pop();
          }
          String relativePath = directorRelativePaths.pop();
          long dirModifyTime = directoryModifyTimes.pop();
          Set<String> dirContents = directoryContents.pop();
          String[] list = list(activity, directory);
          if (list != null) {
            for (String filename : list) {
              String fullpath = dirPath + filename;
              if (!dirContents.contains(fullpath)) {
                if (deleteOnCleanup(fromServer, retention, relativePath + '/' + filename, replicatedMysqlServers, replicatedMysqlMinorVersions)) {
                  if (isTrace) {
                    logger.finer("Deleting final clean-up: " + fullpath);
                  }
                  try {
                    PosixFile deleteMe = new PosixFile(fullpath);
                    deleteRecursive(activity, deleteMe);
                  } catch (FileNotFoundException err) {
                    logger.log(Level.SEVERE, "fullpath=" + fullpath, err);
                  }
                }
              }
            }
          }
          Stat dirStat = stat(activity, directory);
          if (dirStat.getModifyTime() != dirModifyTime) {
            activity.update("file: utime: ", directory);
            directory.utime(dirStat.getAccessTime(), dirModifyTime);
          }
        }

        // Log the final stats
        if (isInfo) {
          logger.info("modifyTimeAndSizeCachesSize=" + modifyTimeAndSizeCachesSize);
        }

        if (retention != 1) {
          // The pass was successful, now rename partial to final
          String from = isRecycling ? recycledPartialMirrorRoot : partialMirrorRoot;
          renameToNoExists(logger, activity, new PosixFile(from), new PosixFile(finalMirrorRoot));

          // The pass was successful, now cleanup old directories based on retention settings
          cleanAndRecycleBackups(activity, retention, perDateRoot, fromServerYear, fromServerMonth, fromServerDay);
        }

        // Tell the client we are done OK
        activity.update("socket: write: AoservDaemonProtocol.DONE");
        out.write(AoservDaemonProtocol.DONE);
        out.flush();
      } catch (Throwable t) {
        logger.log(Level.SEVERE, "closing socket due to exception", t);
        t0 = Throwables.addSuppressed(t0, t);
        activity.update("socket: close");
        t0 = AutoCloseables.closeAndCatch(t0, socket);
      }
      try {
        if (postPassChecklist.restartMysqls && retention == 1) {
          for (Server.Name mysqlServer : replicatedMysqlServers) {
            String message = "Restarting MySQL " + mysqlServer + " in \"" + toPath + '"';
            activity.update("logic: ", message);
            if (isFine) {
              logger.fine(message);
            }

            String[] command;
              {
                String serviceName = fromServer + "-mysql-" + mysqlServer + ".service";
                File serviceFile = new File("/etc/systemd/system/" + serviceName);
                if (serviceFile.exists()) {
                  // Run via systemctl
                  command = new String[]{
                      "/usr/bin/systemctl",
                      "try-restart", // Do not start if not currently running
                      serviceName
                  };
                } else {
                  String initPath = "/etc/rc.d/init.d/mysql-" + mysqlServer;
                  File initFile = new File(toPath + initPath);
                  if (initFile.exists()) {
                    // Run via chroot /etc/rc.d/init.d
                    command = new String[]{
                        "/usr/sbin/chroot",
                        toPath,
                        initPath,
                        "restart"
                    };
                  } else {
                    throw new IOException("Unable to restart MySQL via either \"" + serviceFile + "\" or \"" + initFile + "\"");
                  }
                }
              }
            try {
              AoservDaemon.exec(command);
            } catch (IOException err) {
              logger.log(Level.SEVERE, AoservDaemon.getCommandString(command), err);
            }
          }
        }
      } catch (Throwable t) {
        t0 = Throwables.addSuppressed(t0, t);
      }
      if (t0 != null) {
        if (t0 instanceof SQLException) {
          throw (SQLException) t0;
        }
        throw Throwables.wrap(t0, IOException.class, IOException::new);
      } else {
        success = true;
      }
    } finally {
      activity.update(success ? "logic: return: successful" : "logic: return: unsuccessful");
    }
  }

  /**
   * Called before something is removed, to keep the cache in sync.
   */
  private static void removing(Map<PosixFile, ModifyTimeAndSizeCache> modifyTimeAndSizeCaches, PosixFile uf, Stat ufStat, PosixFile ufParent) throws FileNotFoundException {
    if (!modifyTimeAndSizeCaches.isEmpty()) {
      if (ufStat.isRegularFile()) {
        // For a regular file, just removeByValue it from its parent, this is the fastest case
        // because no scan for children directories is required.

        ModifyTimeAndSizeCache modifyTimeAndSizeCache = modifyTimeAndSizeCaches.get(ufParent);
        if (modifyTimeAndSizeCache != null) {
          modifyTimeAndSizeCache.removing(uf.getFile().getName());
        }
      } else if (ufStat.isDirectory()) {
        // For a directory, removeByValue it and any of the directories under it.
        // This is more expensive because we need to search all caches for prefix matches (iteration).

        // Remove any items that are this or are children of this
        String prefix = uf.getPath();
        if (!prefix.endsWith("/")) {
          prefix += '/';
        }
        Iterator<Map.Entry<PosixFile, ModifyTimeAndSizeCache>> iter = modifyTimeAndSizeCaches.entrySet().iterator();
        while (iter.hasNext()) {
          Map.Entry<PosixFile, ModifyTimeAndSizeCache> entry = iter.next();
          PosixFile key = entry.getKey();
          if (
              key.equals(uf)
                  || key.getPath().startsWith(prefix)
          ) {
            iter.remove();
          }
        }
      }
    }
  }

  /**
   * Called after a file is added, to keep the cache in sync.
   */
  private static void added(Activity activity, Map<PosixFile, ModifyTimeAndSizeCache> modifyTimeAndSizeCaches, PosixFile uf, PosixFile ufParent, ModifyTimeAndSize ufModifyTimeAndSize) {
    if (!modifyTimeAndSizeCaches.isEmpty()) {
      ModifyTimeAndSizeCache modifyTimeAndSizeCache = modifyTimeAndSizeCaches.get(ufParent);
      if (modifyTimeAndSizeCache != null) {
        modifyTimeAndSizeCache.added(
            uf.getFile().getName(),
            ufModifyTimeAndSize
        );
      }
    }
  }

  /**
   * Called after a file is renamed, to keep the cache in sync.
   */
  private static void renamed(Map<PosixFile, ModifyTimeAndSizeCache> modifyTimeAndSizeCaches, PosixFile old, PosixFile newPosixFile, PosixFile parent) {
    if (!modifyTimeAndSizeCaches.isEmpty()) {
      ModifyTimeAndSizeCache modifyTimeAndSizeCache = modifyTimeAndSizeCaches.get(parent);
      if (modifyTimeAndSizeCache != null) {
        modifyTimeAndSizeCache.renamed(
            old.getFile().getName(),
            newPosixFile.getFile().getName()
        );
      }
    }
  }

  /**
   * Encapsulates a modified time and size.  Is immutable and may be used a as Map key.
   */
  static final class ModifyTimeAndSize {

    final long modifyTime;
    final long size;

    private ModifyTimeAndSize(long modifyTime, long size) {
      this.modifyTime = modifyTime;
      this.size = size;
    }

    @Override
    public int hashCode() {
      return (int) (modifyTime * 31 + size);
    }

    @Override
    public boolean equals(Object obj) {
      if (!(obj instanceof ModifyTimeAndSize)) {
        return false;
      }
      ModifyTimeAndSize other = (ModifyTimeAndSize) obj;
      return
          modifyTime == other.modifyTime
              && size == other.size;
    }
  }

  /**
   * Caches the directory.
   */
  static final class ModifyTimeAndSizeCache {

    private final PosixFile directory;
    private final Map<String, ModifyTimeAndSize> filenameMap = new HashMap<>();
    private final Map<ModifyTimeAndSize, List<String>> modifyTimeAndSizeMap = new HashMap<>();

    ModifyTimeAndSizeCache(Activity activity, PosixFile directory) throws IOException {
      this.directory = directory;
      // Read all files in the directory to populate the caches
      String[] list = list(activity, directory);
      if (list != null) {
        for (int d = 0, len = list.length; d < len; d++) {
          String filename = list[d];
          PosixFile file = new PosixFile(directory, filename, false);
          Stat stat = stat(activity, file);
          if (
              stat.exists()
                  && stat.isRegularFile()
          ) {
            ModifyTimeAndSize modifyTimeAndSize = new ModifyTimeAndSize(stat.getModifyTime(), stat.getSize());
            filenameMap.put(filename, modifyTimeAndSize);
            List<String> fileList = modifyTimeAndSizeMap.get(modifyTimeAndSize);
            if (fileList == null) {
              modifyTimeAndSizeMap.put(modifyTimeAndSize, fileList = new ArrayList<>());
            }
            fileList.add(filename);
          }
        }
      }
    }

    PosixFile getDirectory() {
      return directory;
    }

    /**
     * Gets the list of filenames in this directory that match the provided modified time and size or <code>null</code> if none.
     */
    List<String> getFilenamesByModifyTimeAndSize(ModifyTimeAndSize modifyTimeAndSize) {
      return modifyTimeAndSizeMap.get(modifyTimeAndSize);
    }

    /**
     * Gets the modified time and size for a specific filename within this directory.
     */
    ModifyTimeAndSize getModifyTimeAndSizeByFilename(String filename) {
      return filenameMap.get(filename);
    }

    /**
     * To maintain correct cache state, this should be called before a regular file in this directory is deleted.
     */
    void removing(String filename) {
      ModifyTimeAndSize modifyTimeAndSize = filenameMap.remove(filename);
      if (modifyTimeAndSize == null) {
        throw new AssertionError("filenameMap doesn't contain filename: filename=" + filename);
      }
      List<String> filenames = modifyTimeAndSizeMap.get(modifyTimeAndSize);
      if (filenames == null) {
        throw new AssertionError("modifyTimeAndSizeMap doesn't contain modifyTimeAndSize");
      }
      if (!filenames.remove(filename)) {
        throw new AssertionError("filenames didn't contain filename: filename=" + filename);
      }
      if (filenames.isEmpty()) {
        modifyTimeAndSizeMap.remove(modifyTimeAndSize);
      }
    }

    /**
     * To maintain correct cache state, this should be called after a regular file in this directory is renamed.
     */
    void renamed(String oldFilename, String newFilename) {
      // The new filename must not exist
      if (filenameMap.containsKey(newFilename)) {
        throw new AssertionError("filenameMap already contains newFilename: newFilename=" + newFilename);
      }
      // Move in the filenameMap
      ModifyTimeAndSize modifyTimeAndSize = filenameMap.remove(oldFilename);
      // The old filename must exist in the cache, otherwise we have a cache coherency problem
      if (modifyTimeAndSize == null) {
        throw new AssertionError("oldFilename not in filenameMap: oldFilename=" + oldFilename);
      }
      filenameMap.put(newFilename, modifyTimeAndSize);
      // Update in the modifyTimeAndSizeMap map
      List<String> filenames = modifyTimeAndSizeMap.get(modifyTimeAndSize);
      if (filenames == null) {
        throw new AssertionError("filenames is null");
      }
      int index = filenames.indexOf(oldFilename);
      if (index == -1) {
        throw new AssertionError("index is -1, oldFilename not found in filenames");
      }
      filenames.set(index, newFilename);
    }

    ///**
    // * To maintain correct cache state, this should be called whenever a regular file in this directory is linked.
    // * This only works if they are both in the same directory.
    // */
    // TODO: call activity.update if uncomment this code
    //void linking(String filename, String linkToFilename) {
    //  // The filename must not exist in the cache
    //  if (filenameMap.containsKey(filename)) {
    //    throw new AssertionError("filenameMap already contains filename: filename="+filename);
    //  }
    //  // Add in the filenameMap as duplicate of linkToFilename
    //  ModifyTimeAndSize modifyTimeAndSize = filenameMap.get(linkToFilename);
    //  if (modifyTimeAndSize == null) {
    //    throw new AssertionError("linkToFilename not in filenameMap: linkToFilename="+linkToFilename);
    //  }
    //  filenameMap.put(filename, modifyTimeAndSize);
    //  // Update in the modifyTimeAndSizeMap map
    //  List<String> filenames = modifyTimeAndSizeMap.get(modifyTimeAndSize);
    //  if (filenames == null) {
    //    throw new AssertionError("filenames is null");
    //  }
    //  if (USE_OLD_AND_NEW_LOG_DIRECTORY_LINKING) {
    //    if (!filenames.contains(linkToFilename)) {
    //      throw new AssertionError("filenames doesn't contain linkToFilename: linkToFilename="+linkToFilename);
    //    }
    //    if (filenames.contains(filename)) {
    //      throw new AssertionError("filenames already contains filename: filename="+filename);
    //    }
    //  }
    //  filenames.add(filename);
    //}

    /**
     * To maintain correct cache state, this should be called after a regular file is added to this directory.
     */
    void added(String filename, ModifyTimeAndSize modifyTimeAndSize) {
      // The filename must not exist in the cache
      if (filenameMap.containsKey(filename)) {
        throw new AssertionError("filenameMap already contains filename: filename=" + filename);
      }
      // Add to the maps
      filenameMap.put(filename, modifyTimeAndSize);
      List<String> fileList = modifyTimeAndSizeMap.get(modifyTimeAndSize);
      if (fileList == null) {
        modifyTimeAndSizeMap.put(modifyTimeAndSize, fileList = new ArrayList<>());
      }
      fileList.add(filename);
    }
  }

  /**
   * Determines if a specific file may be deleted on clean-up.
   * Don't delete anything in /proc/*, /sys/*, /selinux/*, /dev/pts/*, or MySQL replication-related files
   */
  private static boolean deleteOnCleanup(String fromServer, int retention, String relativePath, List<Server.Name> replicatedMysqlServers, List<String> replicatedMysqlMinorVersions) {
    boolean isDebug = logger.isLoggable(Level.FINE);
    if (
        "/proc".equals(relativePath)
            || relativePath.startsWith("/proc/")
            || "/sys".equals(relativePath)
            || relativePath.startsWith("/sys/")
            || "/selinux".equals(relativePath)
            || relativePath.startsWith("/selinux/")
            || "/dev/pts".equals(relativePath)
            || relativePath.startsWith("/dev/pts/")
    ) {
      if (isDebug) {
        logger.fine("Skipping delete on cleanup: \"" + fromServer + "\":" + relativePath);
      }
      return false;
    }
    if (retention == 1) {
      for (Server.Name name : replicatedMysqlServers) {
        if (
            (
                relativePath.startsWith("/var/lib/mysql/")
                    && (
                    relativePath.equals("/var/lib/mysql/" + name)
                        || relativePath.startsWith("/var/lib/mysql/" + name + "/")
                        ||     relativePath.equals("/var/lib/mysql/" + name + "-old")
                        || relativePath.startsWith("/var/lib/mysql/" + name + "-old/")
                        ||     relativePath.equals("/var/lib/mysql/" + name + "-new")
                        || relativePath.startsWith("/var/lib/mysql/" + name + "-new/")
                        ||     relativePath.equals("/var/lib/mysql/" + name + "-fast")
                        || relativePath.startsWith("/var/lib/mysql/" + name + "-fast/")
                        ||     relativePath.equals("/var/lib/mysql/" + name + "-slow")
                        || relativePath.startsWith("/var/lib/mysql/" + name + "-slow/")
                )
            ) || (
                relativePath.startsWith("/var/lib/mysql-fast/")
                    && (
                    relativePath.equals("/var/lib/mysql-fast/" + name)
                        || relativePath.startsWith("/var/lib/mysql-fast/" + name + "/")
                )
            ) || (
                relativePath.startsWith("/var/lib/mysql-slow/")
                    && (
                    relativePath.equals("/var/lib/mysql-slow/" + name)
                        || relativePath.startsWith("/var/lib/mysql-slow/" + name + "/")
                )
            ) || relativePath.equals("/var/lock/subsys/mysql-" + name)
        ) {
          if (isDebug) {
            logger.fine("Skipping delete on cleanup: \"" + fromServer + "\":" + relativePath);
          }
          return false;
        }
      }
    }
    return true;
  }

  @SuppressWarnings({"UseSpecificCatch", "TooBroadCatch"})
  private static void cleanAndRecycleBackups(
      Activity activity,
      short retention,
      PosixFile serverRoot,
      short fromServerYear,
      short fromServerMonth,
      short fromServerDay
  ) throws IOException, SQLException {
    final boolean isFine = logger.isLoggable(Level.FINE);
    try {
      // Build the lists of directories based on age, skipping safe deleted and recycled directories
      GregorianCalendar gcal = new GregorianCalendar();
      gcal.set(Calendar.YEAR, fromServerYear);
      gcal.set(Calendar.MONTH, fromServerMonth - 1);
      gcal.set(Calendar.DAY_OF_MONTH, fromServerDay);
      gcal.set(Calendar.HOUR_OF_DAY, 0);
      gcal.set(Calendar.MINUTE, 0);
      gcal.set(Calendar.SECOND, 0);
      gcal.set(Calendar.MILLISECOND, 0);
      long fromServerDate = gcal.getTimeInMillis();
      Map<Integer, List<String>> directoriesByAge;
        {
          String[] list = list(activity, serverRoot);
          directoriesByAge = AoCollections.newHashMap((list == null) ? -1 : list.length);
          if (list != null) {
            for (String filename : list) {
              if (!filename.endsWith(SAFE_DELETE_EXTENSION) && !filename.endsWith(RECYCLED_EXTENSION)) {
                // Not y10k compatible
                if (filename.length() >= 10) {
                  try {
                    int year = Integer.parseInt(filename.substring(0, 4));
                    if (filename.charAt(4) == '-') {
                      int month = Integer.parseInt(filename.substring(5, 7));
                      if (filename.charAt(7) == '-') {
                        int day = Integer.parseInt(filename.substring(8, 10));
                        gcal.set(Calendar.YEAR, year);
                        gcal.set(Calendar.MONTH, month - 1);
                        gcal.set(Calendar.DAY_OF_MONTH, day);
                        gcal.set(Calendar.HOUR_OF_DAY, 0);
                        gcal.set(Calendar.MINUTE, 0);
                        gcal.set(Calendar.SECOND, 0);
                        gcal.set(Calendar.MILLISECOND, 0);
                        int age = SafeMath.castInt(
                            (fromServerDate - gcal.getTimeInMillis())
                                / (24L * 60 * 60 * 1000)
                        );
                        if (age >= 0) {
                          // Must also be a date directory with no extension, or one of the expected extensions to delete:
                          if (
                              // Is a date only
                              filename.length() == 10
                                  || (
                                  // Is date + partial
                                  filename.length() == (10 + PARTIAL_EXTENSION.length())
                                      && filename.endsWith(PARTIAL_EXTENSION)
                              ) || (
                                  // Is date + recycled + partial
                                  filename.length() == (10 + RECYCLED_PARTIAL_EXTENSION.length())
                                      && filename.endsWith(RECYCLED_PARTIAL_EXTENSION)
                              )
                          ) {
                            List<String> directories = directoriesByAge.get(age);
                            if (directories == null) {
                              directoriesByAge.put(age, directories = new ArrayList<>());
                            }
                            directories.add(filename);
                          } else {
                            logger.log(Level.WARNING, null, new IOException("Skipping unexpected directory: " + filename));
                          }
                        } else {
                          logger.log(Level.WARNING, null, new IOException("Directory date in future: " + filename));
                        }
                      } else {
                        logger.log(Level.WARNING, null, new IOException("Unable to parse filename: " + filename));
                      }
                    } else {
                      logger.log(Level.WARNING, null, new IOException("Unable to parse filename: " + filename));
                    }
                  } catch (NumberFormatException err) {
                    logger.log(Level.WARNING, null, new IOException("Unable to parse filename: " + filename));
                  }
                } else {
                  logger.log(Level.WARNING, null, new IOException("Filename too short: " + filename));
                }
              }
            }
          }
        }

      if (isFine) {
        List<Integer> ages = new ArrayList<>(directoriesByAge.keySet());
        Collections.sort(ages);
        for (Integer age : ages) {
          List<String> directories = directoriesByAge.get(age);
          for (String directory : directories) {
            logger.fine(age + ": " + directory);
          }
        }
      }

      // These will be marked for deletion first, recycled where possible, then actually deleted if not recycled
      List<String> deleteFilenames = new ArrayList<>();

      boolean lastHasSuccess = true;
      if (retention <= 7) {
        // These are daily
        lastHasSuccess = false;
        // delete everything after the # of complete passes equalling the retention
        int completeCount = 0;
        for (int age = 0; age <= retention; age++) {
          if (completeCount <= retention) {
            if (hasComplete(directoriesByAge, age, deleteFilenames)) {
              completeCount++;
              lastHasSuccess = true;
            }
          } else {
            if (directoriesByAge.containsKey(age)) {
              delete(directoriesByAge, age, deleteFilenames);
              directoriesByAge.remove(age);
            }
          }
        }
      }
      // Go through each retention level >= 14
      List<BackupRetention> brs = AoservDaemon.getConnector().getBackup().getBackupRetention().getRows();
      int lastLevel = 0;
      for (BackupRetention br : brs) {
        int currentLevel = br.getDays();
        if (currentLevel >= 14) {
          if (retention >= currentLevel) {
            if (lastHasSuccess) {
              lastHasSuccess = false;
              // Delete all but the oldest successful between 8-14
              boolean foundSuccessful = false;
              for (int age = currentLevel; age >= (lastLevel + 1); age--) {
                if (!foundSuccessful) {
                  if (hasComplete(directoriesByAge, age, deleteFilenames)) {
                    foundSuccessful = true;
                    lastHasSuccess = true;
                    for (int deleteAge = age + 1; deleteAge < currentLevel; deleteAge++) {
                      if (directoriesByAge.containsKey(deleteAge)) {
                        delete(directoriesByAge, deleteAge, deleteFilenames);
                        directoriesByAge.remove(deleteAge);
                      }
                    }
                  }
                } else {
                  if (directoriesByAge.containsKey(age)) {
                    delete(directoriesByAge, age, deleteFilenames);
                    directoriesByAge.remove(age);
                  }
                }
              }
            }
          }
        }
        lastLevel = currentLevel;
      }
      // If there is at least one successful in the final grouping in the configuration, delete all except one after that grouping level
      boolean foundSuccessful = false;
      List<Integer> ages = new ArrayList<>(directoriesByAge.keySet());
      Collections.sort(ages);
      for (Integer age : ages) {
        if (age >= retention) {
          if (!foundSuccessful) {
            if (hasComplete(directoriesByAge, age, deleteFilenames)) {
              foundSuccessful = true;
            }
          } else {
            if (directoriesByAge.containsKey(age)) {
              delete(directoriesByAge, age, deleteFilenames);
              directoriesByAge.remove(age);
            }
          }
        }
      }

      // Sort all those that need deleted in ascending order
      Collections.sort(deleteFilenames);

      // Now that we have the list of items that should be deleted:
      // 1) Flag all those that were completed as recycled
      // 2) Flag all those that where not completed directly as .deleted
      // 3) Keep X most recent .recycled directories (not partials, though)
      // 4) Rename older .recycled directories to .deleted
      // 5) Delete all those that end in .deleted in one background rm call, from oldest to newest

      // 1) and 2) above
      for (String directory : deleteFilenames) {
        if (
            directory.length() == 10
                && !directory.endsWith(PARTIAL_EXTENSION)
                && !directory.endsWith(SAFE_DELETE_EXTENSION)
                && !directory.endsWith(RECYCLED_EXTENSION)
        ) {
          // 1) Flag all those that were completed as recycled
          final PosixFile currentPosixFile = new PosixFile(serverRoot, directory, false);
          final PosixFile newPosixFile = new PosixFile(serverRoot, directory + RECYCLED_EXTENSION, false);
          renameToNoExists(logger, activity, currentPosixFile, newPosixFile);
        } else {
          // 2) Flag all those that where not completed directly as .deleted, schedule for delete
          if (!directory.endsWith(SAFE_DELETE_EXTENSION)) {
            final PosixFile currentPosixFile = new PosixFile(serverRoot, directory, false);
            final PosixFile newPosixFile = new PosixFile(serverRoot, directory + SAFE_DELETE_EXTENSION, false);
            renameToNoExists(logger, activity, currentPosixFile, newPosixFile);
          }
        }
      }

        // 3) Keep X most recent .recycled directories (not partials, though)
        // 4) Rename older .recycled directories to .deleted
        {
          final int numRecycle = getNumberRecycleDirectories(retention);
          String[] list = list(activity, serverRoot);
          if (list != null && list.length > 0) {
            Arrays.sort(list);
            int recycledFoundCount = 0;
            for (int c = list.length - 1; c >= 0; c--) {
              String directory = list[c];
              if (directory.endsWith(RECYCLED_EXTENSION)) {
                if (recycledFoundCount < numRecycle) {
                  recycledFoundCount++;
                } else {
                  // Rename to .deleted
                  String newFilename = directory.substring(0, directory.length() - RECYCLED_EXTENSION.length()) + SAFE_DELETE_EXTENSION;
                  final PosixFile currentPosixFile = new PosixFile(serverRoot, directory, false);
                  final PosixFile newPosixFile = new PosixFile(serverRoot, newFilename, false);
                  renameToNoExists(logger, activity, currentPosixFile, newPosixFile);
                }
              }
            }
          }
        }

      // 5) Delete all those that end in .deleted, from oldest to newest
      if (!SAFE_DELETE) {
        String[] list = list(activity, serverRoot);
        if (list != null && list.length > 0) {
          Arrays.sort(list);
          final List<File> directories = new ArrayList<>(list.length);
          for (String directory : list) {
            if (directory.endsWith(SAFE_DELETE_EXTENSION)) {
              //found=true;
              PosixFile deleteUf = new PosixFile(serverRoot, directory, false);
              if (isFine) {
                logger.fine("Deleting: " + deleteUf.getPath());
              }
              directories.add(deleteUf.getFile());
            }
          }
          if (!directories.isEmpty()) {
            // Delete in the background
            AoservDaemon.executorService.submit(() -> {
              try {
                if (directories.size() == 1) {
                  // Single directory - no parallel benefits, use system rm command
                  AoservDaemon.exec(
                      "/bin/rm",
                      "-rf",
                      directories.get(0).getPath()
                  );
                } else {
                  ParallelDelete.parallelDelete(directories, null, false);
                }
              } catch (IOException err) {
                logger.log(Level.SEVERE, null, err);
              }
            });
          }
        }
      }
    } catch (ThreadDeath td) {
      throw td;
    } catch (Throwable t) {
      logger.log(Level.SEVERE, null, t);
    }
  }

  private static boolean hasComplete(Map<Integer, List<String>> directoriesByAge, int age, List<String> deleteFilenames) {
    List<String> directories = directoriesByAge.get(age);
    if (directories != null) {
      for (String directory : directories) {
        if (
            directory.length() == 10
                && !deleteFilenames.contains(directory)
                && !directory.endsWith(PARTIAL_EXTENSION)
                && !directory.endsWith(SAFE_DELETE_EXTENSION)
                && !directory.endsWith(RECYCLED_EXTENSION)
        ) {
          return true;
        }
      }
    }
    return false;
  }

  private static void delete(Map<Integer, List<String>> directoriesByAge, int age, List<String> deleteFilenames) {
    List<String> directories = directoriesByAge.get(age);
    if (directories != null) {
      for (String directory : directories) {
        if (
            !deleteFilenames.contains(directory)
                && !directory.endsWith(SAFE_DELETE_EXTENSION)
                && !directory.endsWith(RECYCLED_EXTENSION)
        ) {
          deleteFilenames.add(directory);
        }
      }
    }
  }

  private static boolean started;

  @SuppressWarnings("UseOfSystemOutOrSystemErr")
  public static void start() throws IOException, SQLException {
    if (AoservDaemonConfiguration.isManagerEnabled(FailoverFileReplicationManager.class)) {
      synchronized (System.out) {
        if (!started) {
          System.out.print("Starting FailoverFileReplicationManager: ");
          BackupDaemon daemon = new BackupDaemon(new LinuxServerEnvironment());
          daemon.start();
          started = true;
          System.out.println("Done");
        }
      }
    }
  }

  /**
   * If the file is a regular file and is hard-linked, copies the file and renames it over the original (to break the link).
   * ufStat may no longer be correct after this method is called, if needed, restat after this call
   *
   * @param  uf  the file we are checking
   * @param  ufStat  the stat of the current file - it is assumed to match the correct state of uf
   *
   * @return  true if any changes were made.  This could be combined with a restat if necessary
   */
  private static boolean copyIfHardLinked(Activity activity, PosixFile uf, Stat ufStat) throws IOException {
    if (ufStat.isRegularFile() && ufStat.getNumberLinks() > 1) {
      if (logger.isLoggable(Level.FINER)) {
        logger.finer("Copying file due to hard link: " + uf);
      }
      PosixFile temp = mktemp(activity, uf);
      activity.update("file: copy: ", uf, " to ", temp);
      uf.copyTo(temp, true);
      activity.update("file: chown: ", temp);
      temp.chown(ufStat.getUid(), ufStat.getGid());
      activity.update("file: setMode: ", temp);
      temp.setMode(ufStat.getMode());
      long atime = ufStat.getAccessTime();
      long mtime = ufStat.getModifyTime();
      rename(activity, temp, uf);
      activity.update("file: utime: ", uf);
      uf.utime(atime, mtime);
      return true;
    } else {
      return false;
    }
  }

  /*
  public static void main(String[] args) {
    try {
      final int retention = 92;

      String[] states = new String[1000];
      BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
      while (!Thread.currentThread().isInterrupted()) {
        System.out.println("Please press enter c for complete day or p for partial day: ");
        String line = in.readLine();
        if (line == null) {
          break;
        }
        boolean successful = !line.startsWith("p");
        System.arraycopy(states, 0, states, 1, states.length-1);
        states[0]=successful ? "complete" : "partial";

        // Clean up algorithm
        boolean lastHasSuccess = true;
        if (retention <= 7) {
          // These are daily
          lastHasSuccess = false;
          // delete everything after the # of complete passes equalling the retention
          int completeCount = 0;
          for (int age=0;age <= retention;age++) {
            if (completeCount <= retention) {
              if ("complete".equals(states[age])) {
                completeCount++;
                lastHasSuccess = true;
              }
            } else {
              if (states[age] != null) {
                System.out.println("Deleting "+age);
                states[age]=null;
              }
            }
          }
        }
        // Go through each retention level >= 14
        List<BackupRetention> brs = AoservDaemon.getConnector().backupRetentions.getRows();
        int lastLevel = 0;
        for (BackupRetention br : brs) {
          int currentLevel = br.getDays();
          if (currentLevel >= 14) {
            if (retention >= currentLevel) {
              if (lastHasSuccess) {
                lastHasSuccess = false;
                // Delete all but the oldest successful between 8-14
                boolean foundSuccessful = false;
                for (int age=currentLevel;age >= (lastLevel+1);age--) {
                  if (!foundSuccessful) {
                    if ("complete".equals(states[age])) {
                      foundSuccessful = true;
                      lastHasSuccess = true;
                      for (int deleteAge = age+1;deleteAge<currentLevel;deleteAge++) {
                        if (states[deleteAge] != null) {
                          System.out.println("Deleting "+deleteAge);
                          states[deleteAge]=null;
                        }
                      }
                    }
                  } else {
                    if (states[age] != null) {
                      System.out.println("Deleting "+age);
                      states[age]=null;
                    }
                  }
                }
              }
            }
          }
          lastLevel = currentLevel;
        }
        // If there is at least one successful in the final grouping in the configuration, delete all except one after that grouping level
        boolean foundSuccessful = false;
        for (int age=retention;age<states.length;age++) {
          if (!foundSuccessful) {
            if ("complete".equals(states[age])) {
              foundSuccessful = true;
            }
          } else {
            if (states[age] != null) {
              System.out.println("Deleting "+age);
              states[age]=null;
            }
          }
        }

        // Display new data
        for (int c=0;c<states.length;c++) if (states[c] != null) {
          System.out.println(c+": "+states[c]);
        }
      }
    } catch (IOException err) {
      err.printStackTrace();
    }
  }
   */
}
