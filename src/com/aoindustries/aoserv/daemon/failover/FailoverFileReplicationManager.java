/*
 * Copyright 2003-2013, 2015, 2017 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.aoserv.daemon.failover;

import com.aoindustries.aoserv.backup.BackupDaemon;
import com.aoindustries.aoserv.client.BackupRetention;
import com.aoindustries.aoserv.client.CvsRepository;
import com.aoindustries.aoserv.client.validator.MySQLServerName;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import com.aoindustries.aoserv.daemon.AOServDaemonConfiguration;
import com.aoindustries.aoserv.daemon.LogFactory;
import com.aoindustries.aoserv.daemon.backup.AOServerEnvironment;
import com.aoindustries.aoserv.daemon.client.AOServDaemonProtocol;
import com.aoindustries.cron.CronDaemon;
import com.aoindustries.cron.CronJob;
import com.aoindustries.cron.CronJobScheduleMode;
import com.aoindustries.cron.Schedule;
import com.aoindustries.io.CompressedDataInputStream;
import com.aoindustries.io.CompressedDataOutputStream;
import com.aoindustries.io.ParallelDelete;
import com.aoindustries.io.filesystems.Path;
import com.aoindustries.io.filesystems.unix.DedupDataIndex;
import com.aoindustries.io.filesystems.unix.DefaultUnixFileSystem;
import com.aoindustries.io.filesystems.unix.UnixFileSystem;
import com.aoindustries.io.unix.Stat;
import com.aoindustries.io.unix.UnixFile;
import com.aoindustries.math.SafeMath;
import com.aoindustries.md5.MD5;
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
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Stack;
import java.util.logging.Level;
import java.util.logging.Logger;

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
 * Compression may be enabled, which will compare chunks of files by MD5 hash.
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
 * to for full checksumming or copying of data.
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
 * in-place.  If the file contents (possibly assumed only be length and modified
 * time) and all attributes (ownership, permission, times, ...) match another
 * backup directory, the files will be linked together to save space.
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
 * TODO: Handle hard links (pertinence space savings)
 *
 * TODO: Need to do mysqldump and postgresql dump on preBackup
 *
 * TODO: Use more compression within the protocol (perhaps to byte[] in RAM and then across the network, or an OutputStream "wrapper")
 *
 * TODO: Use LVM snapshots within the client layer
 * </p>
 *
 * @see  DedupDataIndex
 *
 * @author  AO Industries, Inc.
 */
final public class FailoverFileReplicationManager {

	/**
	 * The extension added to the directory name when it is a partial pass.
	 */
	private static final String PARTIAL_EXTENSION = ".partial";

	/**
	 * When using safe delete, the directory is only renamed to "XXX.deleted" instead of actually removed.
	 */
	private static final boolean SAFE_DELETE = false;

	private static final String SAFE_DELETE_EXTENSION=".deleted";

	/**
	 * The last two completed deleted passes are kept as "XXX.recycled" instead of actually deleted.  These are then reused
	 * in favor of making a new directory.  This allows the system to do fewer links and unlinks to save on disk IO.  This
	 * is especially important for meta-data journalling filesystems, such as reiserfs, to maximize scalability.
	 */
	private static final String RECYCLED_EXTENSION=".recycled";

	/**
	 * The directory name that contains the data index.
	 */
	private static final String DATA_INDEX_DIRECTORY_NAME = "DATA-INDEX";

	/**
	 * The time that orphans will be cleaned.
	 */
	private static final Schedule CLEAN_ORPHANS_SCHEDULE =
		(int minute, int hour, int dayOfMonth, int month, int dayOfWeek, int year) -> (minute == 49 && hour == 1)
	;

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
		//encryptedLoopFilePaths.add("/home/b/bugnugger.aes256.img");
		//encryptedLoopFilePaths.add("/home/k/kaori.aes256.img");
		//encryptedLoopFilePaths.add("/home/o/orion.aes256.img");
	}
	private static boolean isEncryptedLoopFile(String path) {
		return encryptedLoopFilePaths.contains(path);
	}

	/**
	 * The number of recycled copies varies based on retention.
	 */
	private static int getNumberRecycleDirectories(int retention) {
		if(retention <= 7) return 1;
		if(retention <= 31) return 2;
		if(retention <= 92) return 3;
		return 4;
	}

	/**
	 * While a recycled directory is being updated to be the current, it is renamed to have this extension.
	 * This extension is used instead of simply ".partial" to distinguish when the update is from the most
	 * recent pass or when it is from an older pass.
	 */
	private static final String RECYCLED_PARTIAL_EXTENSION=".recycled.partial";

	private FailoverFileReplicationManager() {
	}

	/**
	 * Checks a path for sanity:
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
		if(pathLen == 0) {
			throw new IOException("Illegal path: Must not be empty");
		}
		// Must start with '/'
		if(path.charAt(0) != '/') {
			throw new IOException("Illegal path: Must start with '/': " + path);
		}
		// Must not contain null character
		if(path.indexOf(0) != -1) {
			throw new IOException("Illegal path: Must not contain null character: " + path);
		}
		// Must not contain empty path element "//"
		if(path.contains("//")) {
			throw new IOException("Illegal path: Must not contain empty path element \"//\": " + path);
		}
		// Must not end with '/', unless is the root "/" itself
		if(pathLen > 1 && path.charAt(pathLen - 1) == '/') {
			throw new IOException("Illegal path: Must not end with '/', unless is the root \"/\" itself: " + path);
		}
		// Must not contain "/../"
		if(path.contains("/../")) {
			throw new IOException("Illegal path: Must not contain \"/../\": " + path);
		}
		// Must not end with "/.."
		if(
			pathLen >= 3
			&& path.charAt(pathLen - 3) == '/'
			&& path.charAt(pathLen - 2) == '.'
			&& path.charAt(pathLen - 1) == '.'
		) {
			throw new IOException("Illegal path: Must not end with \"/..\": " + path);
		}
		// Must not contain "/./"
		if(path.contains("/./")) {
			throw new IOException("Illegal path: Must not contain \"/./\": " + path);
		}
		// Must not end with "/."
		if(
			pathLen >= 2
			&& path.charAt(pathLen - 2) == '/'
			&& path.charAt(pathLen - 1) == '.'
		) {
			throw new IOException("Illegal path: Must not end with \"/.\": " + path);
		}
	}

	/**
	 * Checks a symlink target for sanity:
	 * <ol>
	 *   <li>Must not be empty</li>
	 *   <li>Must not contain null character</li>
	 * </ol>
	 */
	public static void checkSymlinkTarget(String target) throws IOException {
		// Must not be empty
		int targetLen = target.length();
		if(targetLen == 0) {
			throw new IOException("Illegal target: Must not be empty");
		}
		// Must not contain null character
		if(target.indexOf(0) != -1) {
			throw new IOException("Illegal target: Must not contain null character: " + target);
		}
	}

	/**
	 * Keeps track of things that will need to be done after a successful replication pass.
	 */
	private static class PostPassChecklist {
		boolean restartMySQLs = false;
	}

	private static void updated(int retention, PostPassChecklist postPassChecklist, String relativePath) {
		if(
			retention==1
			&& !postPassChecklist.restartMySQLs
			&& (
				relativePath.startsWith("/etc/rc.d/init.d/mysql-")
				|| relativePath.startsWith("/opt/mysql-")
			)
		) {
			Logger logger = LogFactory.getLogger(FailoverFileReplicationManager.class);
			if(logger.isLoggable(Level.FINE)) logger.fine("Flagging postPassChecklist.restartMySQLs=true for path="+relativePath);
			postPassChecklist.restartMySQLs=true;
		}
	}

	public static class Activity /*implements Cloneable*/ {

		private long time = -1;
		// When set, this is the complete human-readable form
		private Object message1;
		private Object message2;
		private Object message3;
		private Object message4;

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
			} catch(CloneNotSupportedException e) {
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
			String m1 = message1 == null ? null : message1.toString();
			String m2 = message2 == null ? null : message2.toString();
			String m3 = message3 == null ? null : message3.toString();
			String m4 = message4 == null ? null : message4.toString();
			if(m1 != null) len += m1.length();
			if(m2 != null) len += m2.length();
			if(m3 != null) len += m3.length();
			if(m4 != null) len += m4.length();
			StringBuilder result = new StringBuilder(len);
			if(m1 != null) result.append(m1);
			if(m2 != null) result.append(m2);
			if(m3 != null) result.append(m3);
			if(m4 != null) result.append(m4);
			return result.toString();
		}
	}

	/**
	 * Tracks the most recent action on a per-FailoverFileReplication basis.
	 */
	private static final Map<Integer,Activity> activities = new HashMap<>();
	public static Activity getActivity(Integer failoverFileReplicationPkey) {
		synchronized(activities) {
			Activity activity = activities.get(failoverFileReplicationPkey);
			if(activity == null) {
				activity = new Activity();
				activities.put(failoverFileReplicationPkey, activity);
			}
			return activity;
		}
	}

	private static String readLink(Activity activity, UnixFile uf) throws IOException {
		activity.update("file: readLink: ", uf);
		return uf.readLink();
	}

	/**
	 * Makes a temporary file based on the given file.
	 */
	private static UnixFile mktemp(Activity activity, UnixFile uf) throws IOException {
		String name = uf.getFile().getName();
		UnixFile templateUF = name.length() > 64 ? new UnixFile(uf.getParent(), name.substring(0, 64), false) : uf;
		String tempPath = templateUF.getPath()+'.';
		activity.update("file: mktemp: ", tempPath);
		return UnixFile.mktemp(tempPath);
	}

	private static void delete(Activity activity, UnixFile uf) throws IOException {
		activity.update("file: delete: ", uf);
		uf.delete();
	}

	private static void deleteRecursive(Activity activity, UnixFile uf) throws IOException {
		activity.update("file: deleteRecursive: ", uf);
		uf.deleteRecursive();
	}

	private static Stat stat(Activity activity, UnixFile uf) throws IOException {
		activity.update("file: stat: ", uf);
		return uf.getStat();
	}

	private static void link(Activity activity, UnixFile from, UnixFile to) throws IOException {
		activity.update("file: link: ", from, " to ", to);
		from.link(to);
	}

	private static void rename(Activity activity, UnixFile from, UnixFile to) throws IOException {
		activity.update("file: rename: ", from, " to ", to);
		from.renameTo(to);
	}

	private static void mkdir(Activity activity, UnixFile uf) throws IOException {
		activity.update("file: mkdir: ", uf);
		uf.mkdir();
	}

	private static void mkdir(Activity activity, UnixFile uf, boolean makeParents, long mode, int uid, int gid) throws IOException {
		activity.update("file: mkdir: ", uf);
		uf.mkdir(
			makeParents,
			mode,
			uid,
			gid
		);
	}

	private static String[] list(Activity activity, UnixFile uf) throws IOException {
		activity.update("file: list: ", uf);
		return uf.list();
	}

	private static void mknod(Activity activity, UnixFile uf, long mode, long device) throws IOException {
		activity.update("file: mknod: ", uf);
		uf.mknod(mode, device);
	}

	private static void mkfifo(Activity activity, UnixFile uf, long mode) throws IOException {
		activity.update("file: mkfifo: ", uf);
		uf.mkfifo(mode);
	}

	private static void touch(Activity activity, UnixFile uf) throws IOException {
		activity.update("file: touch: ", uf);
		new FileOutputStream(uf.getFile()).close();
	}

	private static FileInputStream openIn(Activity activity, UnixFile uf) throws IOException {
		activity.update("file: open: < ", uf);
		return new FileInputStream(uf.getFile());
	}

	private static RandomAccessFile openInRaf(Activity activity, UnixFile uf) throws IOException {
		activity.update("file: open: < ", uf);
		return new RandomAccessFile(uf.getFile(), "r");
	}

	private static FileOutputStream openOut(Activity activity, UnixFile uf) throws IOException {
		activity.update("file: open: > ", uf);
		return new FileOutputStream(uf.getFile());
	}

	private static void close(Activity activity, UnixFile uf, InputStream in) throws IOException {
		activity.update("file: close: < ", uf);
		in.close();
	}

	private static void close(Activity activity, UnixFile uf, RandomAccessFile raf) throws IOException {
		activity.update("file: close: < ", uf);
		raf.close();
	}

	private static void close(Activity activity, UnixFile uf, OutputStream out) throws IOException {
		activity.update("file: close: > ", uf);
		out.close();
	}

	private static void renameToNoExists(Logger logger, Activity activity, UnixFile from, UnixFile to) throws IOException {
		boolean isFine = logger.isLoggable(Level.FINE);
		if(isFine) logger.fine("Renaming \""+from+"\" to \""+to+'"');
		if(stat(activity, to).exists()) throw new IOException("to exists: "+to);
		rename(activity, from, to);
	}

	private static final Map<String,DedupDataIndex> dedupIndexes = new HashMap<>();
	/**
	 * Only one data index is created per backup partition, and a cron job is created
	 * for daily cleaning.  The cron job is also launched immediately in quick mode.
	 */
	private static DedupDataIndex getDedupDataIndex(Activity activity, String backupPartition) throws IOException {
		synchronized(dedupIndexes) {
			DedupDataIndex dedupIndex = dedupIndexes.get(backupPartition);
			if(dedupIndex == null) {
				UnixFileSystem fileSystem = DefaultUnixFileSystem.getInstance();
				Path dataIndexDir = new Path(
					fileSystem.parsePath(backupPartition),
					DATA_INDEX_DIRECTORY_NAME
				);
				activity.update("data-index: Opening data index: ", dataIndexDir);
				DedupDataIndex newDedupIndex = DedupDataIndex.getInstance(fileSystem, dataIndexDir);
				Logger logger = LogFactory.getLogger(FailoverFileReplicationManager.class);
				/**
				 * Add the CronJob that cleans orphaned data in the background.
				 */
				CronJob cleanupJob = new CronJob() {
					@Override
					public Schedule getCronJobSchedule() {
						return CLEAN_ORPHANS_SCHEDULE;
					}
					@Override
					public CronJobScheduleMode getCronJobScheduleMode() {
						return CronJobScheduleMode.SKIP;
					}
					@Override
					public String getCronJobName() {
						return DedupDataIndex.class.getName()+".cleanOrphans()";
					}
					@Override
					public int getCronJobThreadPriority() {
						return Thread.NORM_PRIORITY;
					}
					@Override
					public void runCronJob(int minute, int hour, int dayOfMonth, int month, int dayOfWeek, int year) {
						try {
							newDedupIndex.verify(false);
						} catch(IOException e) {
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
						} catch(IOException e) {
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
	 * @param quota_gid  the quota_gid or <code>-1</code> for no quotas
	 */
	public static void failoverServer(
		final Socket socket,
		final CompressedDataInputStream rawIn,
		final CompressedDataOutputStream out,
		final int failoverFileReplicationPkey,
		final String fromServer,
		final boolean useCompression,
		final short retention,
		final String backupPartition, // TODO: Make sure this partition is enabled
		final short fromServerYear,
		final short fromServerMonth,
		final short fromServerDay,
		final List<MySQLServerName> replicatedMySQLServers,
		final List<String> replicatedMySQLMinorVersions,
		final int quota_gid
	) throws IOException, SQLException {
		boolean success = false;
		final Activity activity = getActivity(failoverFileReplicationPkey);
		activity.update("logic: init");
		final String toPath = backupPartition + '/' + fromServer;
		try {
			final PostPassChecklist postPassChecklist = new PostPassChecklist();
			Logger logger = LogFactory.getLogger(FailoverFileReplicationManager.class);
			boolean isInfo = logger.isLoggable(Level.INFO);
			boolean isFine = logger.isLoggable(Level.FINE);
			boolean isTrace = logger.isLoggable(Level.FINER);
			try {
				if(isInfo) {
					logger.info(
						"Receiving transfer:\n"
						+ "    fromServer=\"" + fromServer + "\"\n"
						+ "    useCompression=" + useCompression + "\n"
						+ "    retention=" + retention + "\n"
						+ "    backupPartition=\"" + backupPartition + "\"\n"
						+ "    fromServerYear=" + fromServerYear + "\n"
						+ "    fromServerMonth=" + fromServerMonth + "\n"
						+ "    fromServerDay=" + fromServerDay + "\n"
						+ "    quota_gid=" + quota_gid + "\n"
						+ "    toPath=\"" + toPath + "\"\n"
						+ "    thread.id=" + Thread.currentThread().getId()
					);
				}
				if(fromServerYear<1000 || fromServerYear>9999) throw new IOException("Invalid fromServerYear (1000-9999): "+fromServerYear);
				if(fromServerMonth<1 || fromServerMonth>12) throw new IOException("Invalid fromServerMonth (1-12): "+fromServerMonth);
				if(fromServerDay<1 || fromServerDay>31) throw new IOException("Invalid fromServerDay (1-31): "+fromServerDay);

				for(MySQLServerName replicatedMySQLServer : replicatedMySQLServers) {
					if(isFine) logger.fine("failoverServer from \""+fromServer+"\", replicatedMySQLServer: "+replicatedMySQLServer);
				}
				for(String replicatedMySQLMinorVersion : replicatedMySQLMinorVersions) {
					if(isFine) logger.fine("failoverServer from \""+fromServer+"\", replicatedMySQLMinorVersion: "+replicatedMySQLMinorVersion);
					if(
						replicatedMySQLMinorVersion.indexOf('/')!=-1
						|| replicatedMySQLMinorVersion.contains("..")
					) throw new IOException("Invalid replicatedMySQLMinorVersion: "+replicatedMySQLMinorVersion);
				}

				// Create the server root if it doesn't exist
				{
					UnixFile toPathUF = new UnixFile(toPath);
					Stat dirStat = stat(activity, toPathUF);
					if(!dirStat.exists()) {
						mkdir(
							activity,
							toPathUF,
							true,
							quota_gid==-1 ? 0700 : 0750,
							UnixFile.ROOT_UID,
							quota_gid==-1 ? UnixFile.ROOT_GID : quota_gid
						);
					} else if(!dirStat.isDirectory()) {
						throw new IOException("toPath exists but is not a directory: "+toPath);
					}
				}

				// Tell the client it is OK to continue
				activity.update("socket: write: AOServDaemonProtocol.NEXT");
				out.write(AOServDaemonProtocol.NEXT);
				out.flush();

				// Determine the directory that is/will be storing the mirror
				final String partialMirrorRoot;
				final String recycledPartialMirrorRoot;
				final String finalMirrorRoot;
				String linkToRoot;
				final UnixFile perDateRoot;
				boolean isRecycling;
				final DedupDataIndex dataIndex;
				if(retention == 1) {
					partialMirrorRoot = finalMirrorRoot = toPath;
					recycledPartialMirrorRoot = null;
					linkToRoot = null;
					perDateRoot = null;
					isRecycling = false;
					dataIndex = null;
				} else {
					if(
						DATA_INDEX_DIRECTORY_NAME.equals(fromServer)
						|| fromServer.startsWith(DATA_INDEX_DIRECTORY_NAME + '/')
					) throw new IOException("fromServer conflicts with data index: " + fromServer);
					dataIndex = getDedupDataIndex(activity, backupPartition);

					// The directory that holds the different versions
					perDateRoot = new UnixFile(toPath);

					// The directories including the date
					StringBuilder SB = new StringBuilder(toPath);
					SB.append('/').append(fromServerYear).append('-');
					if(fromServerMonth<10) SB.append('0');
					SB.append(fromServerMonth).append('-');
					if(fromServerDay<10) SB.append('0');
					SB.append(fromServerDay);
					finalMirrorRoot = SB.toString();
					// The partial directory name used during the transfer
					SB.append(PARTIAL_EXTENSION);
					partialMirrorRoot = SB.toString();
					// The partial directory name used when recycling a previous directory
					SB.setLength(finalMirrorRoot.length());
					SB.append(RECYCLED_PARTIAL_EXTENSION);
					recycledPartialMirrorRoot = SB.toString();

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
					UnixFile finalUF = new UnixFile(finalMirrorRoot);
					if(stat(activity, finalUF).exists()) {
						// See (1) above
						UnixFile partialUF = new UnixFile(partialMirrorRoot);
						renameToNoExists(logger, activity, finalUF, partialUF);
						linkToRoot = null;
						isRecycling = false;
					} else {
						{
							UnixFile recycledPartialUF = new UnixFile(recycledPartialMirrorRoot);
							if(stat(activity, recycledPartialUF).exists()) {
								// See (2) above
								isRecycling = true;
							} else {
								UnixFile partialUF = new UnixFile(partialMirrorRoot);
								if(stat(activity, partialUF).exists()) {
									// See (3) above
									isRecycling = false;
								} else {
									// See (4) above
									boolean foundPartial = false;
									isRecycling = false;

									String[] list = list(activity, perDateRoot);
									if(list != null && list.length > 0) {
										// This is not y10k compliant - this is assuming lexical order is the same as chronological order.
										Arrays.sort(list);
										// Find most recent partial
										for(int c=list.length-1;c>=0;c--) {
											String filename = list[c];
											if(filename.endsWith(PARTIAL_EXTENSION)) {
												isRecycling = filename.endsWith(RECYCLED_PARTIAL_EXTENSION);
												renameToNoExists(
													logger,
													activity,
													new UnixFile(perDateRoot, filename, false),
													isRecycling ? recycledPartialUF : partialUF
												);
												foundPartial = true;
												break;
											}
										}

										if(!foundPartial) {
											// Find most recent recycled pass
											for(int c=list.length-1;c>=0;c--) {
												String filename = list[c];
												if(filename.endsWith(RECYCLED_EXTENSION)) {
													renameToNoExists(
														logger,
														activity,
														new UnixFile(perDateRoot, filename, false),
														recycledPartialUF
													);
													isRecycling = true;
													break;
												}
											}
										}
									}
									if(!foundPartial && !isRecycling) {
										// Neither found, create new directory
										mkdir(activity, partialUF);
									}
								}
							}
						}
						// Finds the path that will be linked-to.
						// Find the most recent complete pass that is not today's directory (which should not exist anyways because renamed above)
						linkToRoot = null;
						{
							String[] list = list(activity, perDateRoot);
							if(list != null && list.length > 0) {
								// This is not y10k compliant - this is assuming lexical order is the same as chronological order.
								Arrays.sort(list);
								// Find most recent complete pass
								for(int c = list.length - 1; c >=0; c--) {
									String filename = list[c];
									String fullFilename = toPath+"/"+filename;
									if(fullFilename.equals(finalMirrorRoot)) throw new AssertionError("finalMirrorRoot exists, but should have already been renamed to .partial");
									if(
										filename.length()==10
										//&& !fullFilename.equals(partialMirrorRoot)
										//&& !fullFilename.equals(recycledPartialMirrorRoot);
										&& !filename.endsWith(PARTIAL_EXTENSION)
										&& !filename.endsWith(SAFE_DELETE_EXTENSION)
										&& !filename.endsWith(RECYCLED_EXTENSION)
										//&& !filename.endsWith(RECYCLED_PARTIAL_EXTENSION)
									) {
										linkToRoot = fullFilename;
										break;
									}
								}
								/* Update activity if this code is uncommented
								if(linkToRoot == null) {
									// When no complete pass is available, find the most recent recycling partial pass
									for(int c=list.length-1;c>=0;c--) {
										String filename = list[c];
										String fullFilename = serverRoot+"/"+filename;
										if(
											!fullFilename.equals(recycledPartialMirrorRoot)
											&& filename.endsWith(RECYCLED_PARTIAL_EXTENSION)
										) {
											linkToRoot = fullFilename;
											break;
										}
									}
								}*/
								/*if(linkToRoot == null) {
									// When no complete pass or recycling partial is available, find the most recent non-recycling partial pass
									for(int c=list.length-1;c>=0;c--) {
										String filename = list[c];
										String fullFilename = serverRoot+"/"+filename;
										if(
											!fullFilename.equals(recycledPartialMirrorRoot)
											&& !fullFilename.equals(partialMirrorRoot)
											&& filename.endsWith(PARTIAL_EXTENSION)
											//&& !filename.endsWith(RECYCLED_PARTIAL_EXTENSION)
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
				if(isFine) {
					logger.fine("partialMirrorRoot="+partialMirrorRoot);
					logger.fine("recycledPartialMirrorRoot="+recycledPartialMirrorRoot);
					logger.fine("finalMirrorRoot="+finalMirrorRoot);
					logger.fine("linkToRoot="+linkToRoot);
					logger.fine("isRecycling="+isRecycling);
				}
				// Safety checks to make sure above logic isn't linking in obviously incorrect ways
				if(linkToRoot != null) {
					if(linkToRoot.equals(partialMirrorRoot)) throw new AssertionError("linkToRoot==partialMirrorRoot: "+linkToRoot);
					if(linkToRoot.equals(recycledPartialMirrorRoot)) throw new AssertionError("linkToRoot==recycledPartialMirrorRoot: "+linkToRoot);
					if(linkToRoot.equals(finalMirrorRoot)) throw new AssertionError("linkToRoot==finalMirrorRoot: "+linkToRoot);
				}

				final CompressedDataInputStream in =
					/*useCompression
					? new CompressedDataInputStream(new GZIPInputStream(new DontCloseInputStream(rawIn), BufferManager.BUFFER_SIZE))
					:*/ rawIn
				;

				String[] paths = null;
				boolean[] isLogDirs = null;
				Map<UnixFile,ModifyTimeAndSizeCache> modifyTimeAndSizeCaches = new HashMap<>();

				UnixFile[] tempNewFiles = null;
				UnixFile[] chunkingFroms = null;
				long[] chunkingSizes = null;
				long[][] chunksMD5His = null;
				long[][] chunksMD5Los = null;
				long[] modifyTimes = null;
				int[] results = null;

				final byte[] chunkBuffer = new byte[AOServDaemonProtocol.FAILOVER_FILE_REPLICATION_CHUNK_SIZE];
				final MD5 md5 = useCompression ? new MD5() : null;
				// The extra files in directories are cleaned once the directory is done
				final Stack<UnixFile> directoryUFs = new Stack<>();
				final Stack<UnixFile> directoryLinkToUFs = linkToRoot==null ? null : new Stack<>();
				final Stack<String> directoryUFRelativePaths = new Stack<>();
				final Stack<Long> directoryModifyTimes = new Stack<>();
				final Stack<Set<String>> directoryContents = new Stack<>();

				// The actual cleaning and modify time setting is delayed to the end of the batch by adding
				// the lists of things to do here.
				final List<UnixFile> directoryFinalizeUFs = new ArrayList<>();
				final List<UnixFile> directoryFinalizeLinkToUFs = linkToRoot==null ? null : new ArrayList<>();
				final List<String> directoryFinalizeUFRelativePaths = new ArrayList<>();
				final List<Long> directoryFinalizeModifyTimes = new ArrayList<>();
				final List<Set<String>> directoryFinalizeContents = new ArrayList<>();

				// Continue until a batchSize of -1 (end of replication)
				int batchSize;
				activity.update("socket: read: Reading batchSize");
				while((batchSize = in.readCompressedInt()) != -1) {
					final Integer batchSizeObj = batchSize;
					if(paths == null || paths.length < batchSize) {
						paths = new String[batchSize];
						isLogDirs = new boolean[batchSize];
						tempNewFiles = new UnixFile[batchSize];
						if(useCompression) {
							chunkingFroms = new UnixFile[batchSize];
							chunkingSizes = new long[batchSize];
							chunksMD5His = new long[batchSize][];
							chunksMD5Los = new long[batchSize][];
						}
						modifyTimes = new long[batchSize];
						results = new int[batchSize];
					}
					// Reset the directory finalization for each batch
					directoryFinalizeUFs.clear();
					if(directoryFinalizeLinkToUFs!=null) directoryFinalizeLinkToUFs.clear();
					directoryFinalizeUFRelativePaths.clear();
					directoryFinalizeModifyTimes.clear();
					directoryFinalizeContents.clear();

					for(int c = 0; c < batchSize; c++) {
						final Integer batchPosObj = c + 1;
						activity.update("socket: read: Reading exists ", batchPosObj, " of ", batchSizeObj);
						if(in.readBoolean()) {
							// Read the current file
							final String relativePath = in.readCompressedUTF();
							checkPath(relativePath);
							isLogDirs[c] = relativePath.startsWith("/logs/") || relativePath.startsWith("/var/log/");
							String path = (isRecycling ? recycledPartialMirrorRoot : partialMirrorRoot) + relativePath;
							paths[c] = path;
							UnixFile uf = new UnixFile(path);
							Stat ufStat = stat(activity, uf);
							UnixFile ufParent = uf.getParent();
							//String linkToPath;
							UnixFile linkToUF;
							Stat linkToUFStat;
							UnixFile linkToParent;
							if(linkToRoot != null) {
								String linkToPath = linkToRoot+relativePath;
								linkToUF = new UnixFile(linkToPath);
								linkToUFStat = stat(activity, linkToUF);
								linkToParent = linkToUF.getParent();
							} else {
								//linkToPath = null;
								linkToUF = null;
								linkToUFStat = null;
								linkToParent = null;
							}
							activity.update("socket: read: Reading mode ", batchPosObj, " of ", batchSizeObj);
							long mode = in.readLong();
							long length;
							if(UnixFile.isRegularFile(mode)) {
								activity.update("socket: read: Reading length ", batchPosObj, " of ", batchSizeObj);
								length = in.readLong();
							} else {
								length = -1;
							}
							activity.update("socket: read: Reading uid ", batchPosObj, " of ", batchSizeObj);
							int uid = in.readCompressedInt();
							activity.update("socket: read: Reading gid ", batchPosObj, " of ", batchSizeObj);
							int gid = in.readCompressedInt();
							long modifyTime;
							// TODO: Once glibc >= 2.6 and kernel >= 2.6.22, can use lutimes call for symbolic links
							if(UnixFile.isSymLink(mode)) {
								modifyTime = -1;
							} else {
								activity.update("socket: read: Reading modifyTime ", batchPosObj, " of ", batchSizeObj);
								modifyTime = in.readLong();
							}
							modifyTimes[c] = modifyTime;
							//if(modifyTime<1000 && !UnixFile.isSymLink(mode) && log.isWarnEnabled()) log.warn("Non-symlink modifyTime<1000: "+relativePath+": "+modifyTime);
							String symlinkTarget;
							if(UnixFile.isSymLink(mode)) {
								activity.update("socket: read: Reading symlinkTarget ", batchPosObj, " of ", batchSizeObj);
								symlinkTarget = in.readCompressedUTF();
								checkSymlinkTarget(symlinkTarget);
							} else {
								symlinkTarget = null;
							}
							long deviceID;
							if(
								UnixFile.isBlockDevice(mode)
								|| UnixFile.isCharacterDevice(mode)
							) {
								activity.update("socket: read: Reading deviceID ", batchPosObj, " of ", batchSizeObj);
								deviceID = in.readLong();
							} else {
								deviceID = -1;
							}
							final ModifyTimeAndSize modifyTimeAndSize = new ModifyTimeAndSize(modifyTime, length);

							// Cleanup extra entries in completed directories, setting modifyTime on the directories
							while(!directoryUFs.isEmpty()) {
								UnixFile dirUF = directoryUFs.peek();
								String dirPath = dirUF.getPath();
								if(!dirPath.endsWith("/")) dirPath += '/';

								// If the current file starts with the current directory, continue
								if(path.startsWith(dirPath)) break;

								// Otherwise, schedule to clean and complete the directory at the end of this batch
								directoryUFs.pop();
								directoryFinalizeUFs.add(dirUF);
								if(directoryFinalizeLinkToUFs!=null) {
									assert directoryLinkToUFs != null;
									directoryFinalizeLinkToUFs.add(directoryLinkToUFs.pop());
								}
								directoryFinalizeUFRelativePaths.add(directoryUFRelativePaths.pop());
								directoryFinalizeModifyTimes.add(directoryModifyTimes.pop());
								directoryFinalizeContents.add(directoryContents.pop());
							}

							// Add the current to the directory
							if(!directoryContents.isEmpty()) {
								directoryContents.peek().add(path);
							}

							// Process the current file
							int result = AOServDaemonProtocol.FAILOVER_FILE_REPLICATION_NO_CHANGE;
							tempNewFiles[c] = null;
							if(useCompression) {
								chunkingFroms[c] = null;
								chunkingSizes[c] = Long.MIN_VALUE;
								chunksMD5His[c] = null;
								chunksMD5Los[c] = null;
							}
							if(UnixFile.isBlockDevice(mode)) {
								if(
									ufStat.exists()
									&& (
										!ufStat.isBlockDevice()
										|| ufStat.getDeviceIdentifier()!=deviceID
									)
								) {
									if(isTrace) logger.finer("Deleting to create block device: "+uf.getPath());
									// Update caches
									removing(modifyTimeAndSizeCaches, uf, ufStat, ufParent);
									// Update filesystem
									deleteRecursive(activity, uf);
									ufStat = Stat.NOT_EXISTS;
								}
								if(!ufStat.exists()) {
									mknod(activity, uf, mode, deviceID);
									ufStat = stat(activity, uf);
									if(linkToUF!=null) {
										assert linkToUFStat != null;
										// Only modified if not in last backup set, too
										if(
											!linkToUFStat.exists()
											|| !linkToUFStat.isBlockDevice()
											|| linkToUFStat.getDeviceIdentifier()!=deviceID
										) {
											result=AOServDaemonProtocol.FAILOVER_FILE_REPLICATION_MODIFIED;
											updated(retention, postPassChecklist, relativePath);
										}
									} else {
										result=AOServDaemonProtocol.FAILOVER_FILE_REPLICATION_MODIFIED;
										updated(retention, postPassChecklist, relativePath);
									}
								}
							} else if(UnixFile.isCharacterDevice(mode)) {
								if(
									ufStat.exists()
									&& (
										!ufStat.isCharacterDevice()
										|| ufStat.getDeviceIdentifier()!=deviceID
									)
								) {
									if(isTrace) logger.finer("Deleting to create character device: "+uf.getPath());
									// Update caches
									removing(modifyTimeAndSizeCaches, uf, ufStat, ufParent);
									// Update filesystem
									deleteRecursive(activity, uf);
									ufStat = Stat.NOT_EXISTS;
								}
								if(!ufStat.exists()) {
									mknod(activity, uf, mode, deviceID);
									ufStat = stat(activity, uf);
									if(linkToUF!=null) {
										assert linkToUFStat != null;
										// Only modified if not in last backup set, too
										if(
											!linkToUFStat.exists()
											|| !linkToUFStat.isCharacterDevice()
											|| linkToUFStat.getDeviceIdentifier()!=deviceID
										) {
											result=AOServDaemonProtocol.FAILOVER_FILE_REPLICATION_MODIFIED;
											updated(retention, postPassChecklist, relativePath);
										}
									} else {
										result=AOServDaemonProtocol.FAILOVER_FILE_REPLICATION_MODIFIED;
										updated(retention, postPassChecklist, relativePath);
									}
								}
							} else if(UnixFile.isDirectory(mode)) {
								if(
									ufStat.exists()
									&& !ufStat.isDirectory()
								) {
									if(isTrace) logger.finer("Deleting to create directory: "+uf.getPath());
									// Update caches
									removing(modifyTimeAndSizeCaches, uf, ufStat, ufParent);
									// Update filesystem
									deleteRecursive(activity, uf);
									ufStat = Stat.NOT_EXISTS;
								}
								if(!ufStat.exists()) {
									mkdir(activity, uf);
									ufStat = stat(activity, uf);
									if(linkToUF != null) {
										assert linkToUFStat != null;
										// Only modified if not in last backup set, too
										if(
											!linkToUFStat.exists()
											|| !linkToUFStat.isDirectory()
										) {
											result = AOServDaemonProtocol.FAILOVER_FILE_REPLICATION_MODIFIED;
											updated(retention, postPassChecklist, relativePath);
										}
									} else {
										result = AOServDaemonProtocol.FAILOVER_FILE_REPLICATION_MODIFIED;
										updated(retention, postPassChecklist, relativePath);
									}
								} else if(ufStat.getModifyTime()!=modifyTime) {
									result=AOServDaemonProtocol.FAILOVER_FILE_REPLICATION_MODIFIED;
									updated(retention, postPassChecklist, relativePath);
								}
								directoryUFs.push(uf);
								if(directoryLinkToUFs!=null) directoryLinkToUFs.push(linkToUF);
								directoryUFRelativePaths.push(relativePath);
								directoryModifyTimes.push(modifyTime);
								directoryContents.push(new HashSet<>());
							} else if(UnixFile.isFifo(mode)) {
								if(
									ufStat.exists()
									&& !ufStat.isFifo()
								) {
									if(isTrace) logger.finer("Deleting to create FIFO: "+uf.getPath());
									// Update caches
									removing(modifyTimeAndSizeCaches, uf, ufStat, ufParent);
									// Update filesystem
									deleteRecursive(activity, uf);
									ufStat = Stat.NOT_EXISTS;
								}
								if(!ufStat.exists()) {
									mkfifo(activity, uf, mode);
									ufStat = stat(activity, uf);
									if(linkToUF!=null) {
										assert linkToUFStat != null;
										// Only modified if not in last backup set, too
										if(
											!linkToUFStat.exists()
											|| !linkToUFStat.isFifo()
										) {
											result=AOServDaemonProtocol.FAILOVER_FILE_REPLICATION_MODIFIED;
											updated(retention, postPassChecklist, relativePath);
										}
									} else {
										result=AOServDaemonProtocol.FAILOVER_FILE_REPLICATION_MODIFIED;
										updated(retention, postPassChecklist, relativePath);
									}
								}
							} else if(UnixFile.isRegularFile(mode)) {
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
								if(ufStat.exists()) {
									// If there is a directory that has now been replaced with a regular file, just delete the directory recursively to avoid confusion in the following code
									if(ufStat.isDirectory()) {
										// Update caches
										removing(modifyTimeAndSizeCaches, uf, ufStat, ufParent);
										deleteRecursive(activity, uf);
										ufStat = Stat.NOT_EXISTS;
									}
									// If there is any non-regular file that has now been replaced with a regular file, just delete the symlink to avoid confusion in the following code
									else if(!ufStat.isRegularFile()) {
										// Update caches
										removing(modifyTimeAndSizeCaches, uf, ufStat, ufParent);
										// Update the filesystem
										delete(activity, uf);
										ufStat = Stat.NOT_EXISTS;
									}
									// At this point, the file either exists and is a regular file, or does not exist
								}
								// Look in the current directory for an exact match
								final boolean isEncryptedLoopFile = isEncryptedLoopFile(relativePath);
								if(
									!isEncryptedLoopFile
									&& ufStat.exists()
									&& ufStat.getSize() == length
									&& ufStat.getModifyTime() == modifyTime
								) {
									assert ufStat.isRegularFile() : "All non-regular files should have been deleted";
									// Found in current directory, simply use default result = NO_CHANGE
								} else {
									// Steps below this need to know if we are in a log directory or not
									final boolean isLogDir = isLogDirs[c];

									// Look in the linkTo directory for an exact match
									if(
										!isEncryptedLoopFile
										&& linkToUFStat != null
										&& linkToUFStat.exists()
										&& linkToUFStat.isRegularFile()
										&& linkToUFStat.getSize() == length
										&& linkToUFStat.getModifyTime() == modifyTime
									) {
										// Found match in linkTo, link to linkTo directory
										if(ufStat.exists()) {
											assert ufStat.isRegularFile() : "All non-regular files should have been deleted";
											// If we are in a log directory, move the regular file out of the way into a temp file (for possible later reuse)
											if(isLogDir) {
												// Move to a new temp filename for later reuse
												UnixFile tempUF = mktemp(activity, uf);
												// Update the filesystem
												rename(activity, uf, tempUF);
												link(activity, uf, linkToUF);
												ufStat = stat(activity, uf);
												// Update caches
												renamed(modifyTimeAndSizeCaches, uf, tempUF, ufParent);
												added(activity, modifyTimeAndSizeCaches, uf, ufParent, modifyTimeAndSize);
											} else {
												// Delete and link is OK because this is using a linkTo directory (therefore not in failover mode)
												// Update caches
												removing(modifyTimeAndSizeCaches, uf, ufStat, ufParent);
												// Update the filesystem
												delete(activity, uf);
												link(activity, uf, linkToUF);
												ufStat = stat(activity, uf);
												// Update caches
												added(activity, modifyTimeAndSizeCaches, uf, ufParent, modifyTimeAndSize);
											}
										} else {
											// Update the filesystem
											link(activity, uf, linkToUF);
											ufStat = stat(activity, uf);
											// Update caches
											added(activity, modifyTimeAndSizeCaches, uf, ufParent, modifyTimeAndSize);
										}
									} else {
										// If we are in a log directory, search all regular files in current directory and linkTo directory for matching length and mtime
										// link to it if found
										boolean linkedOldLogFile = false;
										if(!isEncryptedLoopFile && isLogDir) {
											// Look for another file with the same size and modify time in this partial directory
											UnixFile oldLogUF = null;
											ModifyTimeAndSizeCache modifyTimeAndSizeCache =
												// isEmpty checked first to avoid hashing for the common case of no caches
												modifyTimeAndSizeCaches.isEmpty()
												? null
												: modifyTimeAndSizeCaches.get(ufParent)
											;
											if(modifyTimeAndSizeCache == null) {
												// Not in cache, load from disk
												modifyTimeAndSizeCaches.put(ufParent, modifyTimeAndSizeCache = new ModifyTimeAndSizeCache(activity, ufParent));
											}
											List<String> matchedFilenames = modifyTimeAndSizeCache.getFilenamesByModifyTimeAndSize(modifyTimeAndSize);
											if(matchedFilenames != null && !matchedFilenames.isEmpty()) {
												oldLogUF = new UnixFile(ufParent, matchedFilenames.get(0), false);
											}

											if(oldLogUF == null && linkToUF != null) {
												// Look for another file with the same size and modify time in the link to directory (previous backup pass).

												// New implementation is used first because it will load the directory physically from
												// disk first, thus the old implementation will have the advantage of the disk cache.
												// Therefore, if the new implementation is still faster, it is clearly the winner.
												ModifyTimeAndSizeCache modifyTimeAndSizeCache2 =
													// isEmpty checked first to avoid hashing for the common case of no caches
													modifyTimeAndSizeCaches.isEmpty()
													? null
													: modifyTimeAndSizeCaches.get(linkToParent)
												;
												if(modifyTimeAndSizeCache2 == null) {
													// Not in cache, load from disk
													modifyTimeAndSizeCaches.put(linkToParent, modifyTimeAndSizeCache2 = new ModifyTimeAndSizeCache(activity, linkToParent));
												}
												List<String> matchedFilenames2 = modifyTimeAndSizeCache2.getFilenamesByModifyTimeAndSize(modifyTimeAndSize);
												if(matchedFilenames2 != null && !matchedFilenames2.isEmpty()) {
													oldLogUF = new UnixFile(linkToParent, matchedFilenames2.get(0), false);
												}
											}
											if(oldLogUF != null) {
												if(ufStat.exists()) {
													assert ufStat.isRegularFile() : "All non-regular files should have been deleted";
													// Move to a new temp filename for later reuse
													UnixFile tempUF = mktemp(activity, uf);
													// Update filesystem
													if(retention == 1) {
														// Failover mode does a more cautious link to temp and rename over to avoid
														// any moment where there is no file in the path of uf
														delete(activity, tempUF);
														link(activity, tempUF, uf);
														UnixFile tempUF2 = mktemp(activity, uf);
														delete(activity, tempUF2);
														link(activity, tempUF2, oldLogUF);
														rename(activity, tempUF2, uf);
													} else {
														// Backup mode uses a more efficient approach because partial states are OK
														rename(activity, uf, tempUF);
														link(activity, uf, oldLogUF);
													}
													// Update cache
													renamed(modifyTimeAndSizeCaches, uf, tempUF, ufParent);
													added(activity, modifyTimeAndSizeCaches, uf, ufParent, modifyTimeAndSize);
												} else {
													// Update filesystem
													link(activity, uf, oldLogUF);
													// Update cache
													added(activity, modifyTimeAndSizeCaches, uf, ufParent, modifyTimeAndSize);
												}
												ufStat = stat(activity, uf);
												result = AOServDaemonProtocol.FAILOVER_FILE_REPLICATION_MODIFIED;
												updated(retention, postPassChecklist, relativePath);
												linkedOldLogFile = true;
											}
										}
										if(!linkedOldLogFile) {
											boolean chunkingFile = false;
											if(
												useCompression
												// File is not so large that chunking can't possibly store md5's in the arrays (larger than 2 ^ (31 + 20) = 2 Pebibytes currently)
												&& (
													length
													< (((long)Integer.MAX_VALUE) << AOServDaemonProtocol.FAILOVER_FILE_REPLICATION_CHUNK_SIZE_BITS)
												)
											) {
												// Next we will try chunking.  For chunking, we will start by determining what we are chunking from.
												UnixFile chunkingFrom;
												Stat chunkingFromStat;
												if(isRecycling) {
													// When recycling, try linkToUF then uf
													if(
														linkToUFStat != null
														&& linkToUFStat.exists()
														&& linkToUFStat.isRegularFile()
													) {
														chunkingFrom = linkToUF;
														chunkingFromStat = linkToUFStat;
													} else if(
														ufStat.exists()
													) {
														assert ufStat.isRegularFile() : "All non-regular files should have been deleted";
														chunkingFrom = uf;
														chunkingFromStat = ufStat;
													} else {
														chunkingFrom = null;
														chunkingFromStat = null;
													}
												} else {
													// When not recycling, try uf then linkToUF
													if(
														ufStat.exists()
													) {
														assert ufStat.isRegularFile() : "All non-regular files should have been deleted";
														chunkingFrom = uf;
														chunkingFromStat = ufStat;
													} else if(
														linkToUFStat != null
														&& linkToUFStat.exists()
														&& linkToUFStat.isRegularFile()
													) {
														chunkingFrom = linkToUF;
														chunkingFromStat = linkToUFStat;
													} else {
														chunkingFrom = null;
														chunkingFromStat = null;
													}
												}
												if(chunkingFrom != null) {
													assert chunkingFromStat != null;
													assert md5 != null;
													// Now we figure out what we are chunking to.
													if(
														ufStat.exists()
														|| retention == 1
														|| (!isRecycling && linkToUF != null)
													) {
														// When uf exists, chunk to a temp file
														UnixFile tempUF = mktemp(activity, uf);
														tempNewFiles[c] = tempUF;
														if(isTrace) logger.finer("Using temp file (chunked): "+tempUF.getPath());
														// modifyTimeAndSizeCaches is not updated here, it will be updated below when the data is received
													} else {
														// Chunk in-place
														if(!ufStat.exists()) {
															touch(activity, uf);
															ufStat = stat(activity, uf);
															// modifyTimeAndSizeCaches is not updated here, it will be updated below when the data is received
														}
													}

													// Build the list of MD5 hashes per chunk
													final long chunkingSize = Math.min(length, chunkingFromStat.getSize());
													final int numChunks;
													{
														long numChunksL = chunkingSize >> AOServDaemonProtocol.FAILOVER_FILE_REPLICATION_CHUNK_SIZE_BITS;
														if((chunkingSize & (AOServDaemonProtocol.FAILOVER_FILE_REPLICATION_CHUNK_SIZE-1)) != 0) numChunksL++;
														numChunks = SafeMath.castInt(numChunksL);
													}
													final long[] md5His = new long[numChunks];
													final long[] md5Los = new long[numChunks];
													// Generate the MD5 hashes for the current file
													final InputStream fileIn = openIn(activity, chunkingFrom);
													try {
														long filePos = 0;
														for(int chunkIndex = 0; chunkIndex < numChunks; chunkIndex++) {
															int chunkSize;
															if(chunkIndex < (numChunks - 1)) {
																// All except last chunk are full sized
																chunkSize = AOServDaemonProtocol.FAILOVER_FILE_REPLICATION_CHUNK_SIZE;
															} else {
																assert chunkIndex == (numChunks - 1);
																// Last chunk may be partial
																chunkSize = SafeMath.castInt(chunkingSize - filePos);
																assert chunkSize > 0 && chunkSize <= AOServDaemonProtocol.FAILOVER_FILE_REPLICATION_CHUNK_SIZE;
															}
															// Read chunk fully
															activity.update("file: md5: ", chunkingFrom, " at ", filePos);
															int pos = 0;
															while(pos < chunkSize) {
																int ret = fileIn.read(chunkBuffer, pos, chunkSize - pos);
																if(ret == -1) throw new EOFException("End of file while reading chunkingFrom: " + chunkingFrom);
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
													chunksMD5His[c] = md5His;
													chunksMD5Los[c] = md5Los;
													chunkingFile = true;
													result = AOServDaemonProtocol.FAILOVER_FILE_REPLICATION_MODIFIED_REQUEST_DATA_CHUNKED;
													updated(retention, postPassChecklist, relativePath);
												}
											}
											if(!chunkingFile) {
												result = AOServDaemonProtocol.FAILOVER_FILE_REPLICATION_MODIFIED_REQUEST_DATA;
												updated(retention, postPassChecklist, relativePath);
												// If the file doesn't exist, will download in-place
												if(!ufStat.exists()) {
													touch(activity, uf);
													ufStat = stat(activity, uf);
												} else {
													// Build new temp file
													UnixFile tempUF = mktemp(activity, uf);
													tempNewFiles[c] = tempUF;
													if(isTrace) logger.finer("Using temp file (not chunked): "+tempUF.getPath());
													// modifyTimeAndSizeCaches is not updated here, it will be updated below when the data is received
												}
											}
										}
									}
								}
							} else if(UnixFile.isSymLink(mode)) {
								if(
									ufStat.exists()
									&& (
										!ufStat.isSymLink()
										|| !readLink(activity, uf).equals(symlinkTarget)
									)
								) {
									if(isTrace) logger.finer("Deleting to create sybolic link: "+uf.getPath());
									// Update cache
									removing(modifyTimeAndSizeCaches, uf, ufStat, ufParent);
									// Update filesystem
									deleteRecursive(activity, uf);
									ufStat = Stat.NOT_EXISTS;
								}
								if(!ufStat.exists()) {
									activity.update("file: symLink: ", uf, " to ", symlinkTarget);
									uf.symLink(symlinkTarget);
									ufStat = stat(activity, uf);
									if(linkToUFStat != null) {
										// Only modified if not in last backup set, too
										if(
											!linkToUFStat.exists()
											|| !linkToUFStat.isSymLink()
											|| !readLink(activity, linkToUF).equals(symlinkTarget)
										) {
											result = AOServDaemonProtocol.FAILOVER_FILE_REPLICATION_MODIFIED;
											updated(retention, postPassChecklist, relativePath);
										}
									} else {
										result = AOServDaemonProtocol.FAILOVER_FILE_REPLICATION_MODIFIED;
										updated(retention, postPassChecklist, relativePath);
									}
								}
							} else throw new IOException("Unknown mode type: "+Long.toOctalString(mode&UnixFile.TYPE_MASK));

							// Update the permissions (mode)
							UnixFile effectiveUF;
							Stat effectiveUFStat;
							if(tempNewFiles[c] == null) {
								effectiveUF = uf;
								effectiveUFStat = ufStat;
							} else {
								effectiveUF = tempNewFiles[c];
								effectiveUFStat = stat(activity, effectiveUF);
							}
							if(
								!UnixFile.isSymLink(mode)
								&& (
									(effectiveUFStat.getRawMode() & (UnixFile.TYPE_MASK|UnixFile.PERMISSION_MASK))
									!= (mode & (UnixFile.TYPE_MASK|UnixFile.PERMISSION_MASK))
								)
							) {
								//try {
									if(retention!=1) copyIfHardLinked(activity, effectiveUF, effectiveUFStat);
									activity.update("file: setMode: ", effectiveUF);
									effectiveUF.setMode(mode & (UnixFile.TYPE_MASK|UnixFile.PERMISSION_MASK));
									effectiveUFStat = stat(activity, effectiveUF);
								//} catch(FileNotFoundException err) {
									//logger.log(Level.WARNING, "path="+path+", mode="+Long.toOctalString(mode), err);
								//}
								if(result == AOServDaemonProtocol.FAILOVER_FILE_REPLICATION_NO_CHANGE) {
									// Only modified if wrong permission in last backup set, too
									if(
										linkToUFStat == null
										|| !linkToUFStat.exists()
										|| (
											(linkToUFStat.getRawMode() & (UnixFile.TYPE_MASK|UnixFile.PERMISSION_MASK))
											!= (mode & (UnixFile.TYPE_MASK|UnixFile.PERMISSION_MASK))
										)
									) {
										result = AOServDaemonProtocol.FAILOVER_FILE_REPLICATION_MODIFIED;
										updated(retention, postPassChecklist, relativePath);
									}
								}
							}

							// Update the ownership
							if(
								effectiveUFStat.getUid() != uid
								// TODO: Store GID in xattr (if not 0)
								|| effectiveUFStat.getGid() != (quota_gid==-1 ? gid : quota_gid)
							) {
								if(retention!=1) copyIfHardLinked(activity, effectiveUF, effectiveUFStat);
								// TODO: Store GID in xattr (if not 0)
								activity.update("file: chown: ", effectiveUF);
								effectiveUF.chown(uid, (quota_gid==-1 ? gid : quota_gid));
								effectiveUFStat = stat(activity, effectiveUF);
								if(result == AOServDaemonProtocol.FAILOVER_FILE_REPLICATION_NO_CHANGE) {
									// Only modified if not in last backup set, too
									if(
										linkToUFStat == null
										|| !linkToUFStat.exists()
										|| linkToUFStat.getUid() != uid
										// TODO: Store GID in xattr
										|| linkToUFStat.getGid() != (quota_gid==-1 ? gid : quota_gid)
									) {
										result=AOServDaemonProtocol.FAILOVER_FILE_REPLICATION_MODIFIED;
										updated(retention, postPassChecklist, relativePath);
									}
								}
							}

							// Update the modified time
							if(
								!UnixFile.isSymLink(mode)
								&& !UnixFile.isRegularFile(mode) // Regular files will be re-transferred below when their modified times don't match, so no need to set the modified time here
								&& !UnixFile.isDirectory(mode) // Directory modification times are set on the way out of the directories
								&& effectiveUFStat.getModifyTime() != modifyTime
							) {
								if(retention != 1) copyIfHardLinked(activity, effectiveUF, effectiveUFStat);
								activity.update("file: utime: ", effectiveUF);
								effectiveUF.utime(effectiveUFStat.getAccessTime(), modifyTime);
								effectiveUFStat = stat(activity, effectiveUF);
								if(result == AOServDaemonProtocol.FAILOVER_FILE_REPLICATION_NO_CHANGE) {
									// Only modified if not in last backup set, too
									if(
										linkToUFStat == null
										|| !linkToUFStat.exists()
										|| linkToUFStat.getModifyTime() != modifyTime
									) {
										result=AOServDaemonProtocol.FAILOVER_FILE_REPLICATION_MODIFIED;
										updated(retention, postPassChecklist, relativePath);
									}
								}
							}
							results[c]=result;
						} else {
							paths[c] = null;
						}
					}

					// Write the results
					activity.update("socket: write: Writing batch results");
					out.write(AOServDaemonProtocol.NEXT);
					for(int c = 0; c < batchSize; c++) {
						if(paths[c] != null) {
							final Integer batchPosObj = c + 1;
							int result = results[c];
							activity.update("socket: write: Writing result ", batchPosObj, " of ", batchSizeObj);
							out.write(result);
							if(result == AOServDaemonProtocol.FAILOVER_FILE_REPLICATION_MODIFIED_REQUEST_DATA_CHUNKED) {
								long[] md5His = chunksMD5His[c];
								long[] md5Los = chunksMD5Los[c];
								int md5sSize = md5His.length;
								assert md5Los.length == md5sSize;
								activity.update("socket: write: Writing chunk md5s ", batchPosObj, " of ", batchSizeObj);
								out.writeLong(chunkingSizes[c]);
								for(int d = 0; d < md5sSize; d++) {
									out.writeLong(md5His[d]);
									out.writeLong(md5Los[d]);
								}
							}
						}
					}

					// Flush the results
					out.flush();

					// Store incoming data
					for(int c = 0; c < batchSize; c++) {
						String path = paths[c];
						if(path != null) {
							int result = results[c];
							if(
								result == AOServDaemonProtocol.FAILOVER_FILE_REPLICATION_MODIFIED_REQUEST_DATA
								|| result == AOServDaemonProtocol.FAILOVER_FILE_REPLICATION_MODIFIED_REQUEST_DATA_CHUNKED
							) {
								// tempNewFiles[c] is only possibly set for the data transfer results
								UnixFile tempUF = tempNewFiles[c];
								UnixFile uf = new UnixFile(path);
								UnixFile ufParent = uf.getParent();

								// Load into the temporary file or directly to the file (based on above calculations)
								UnixFile fileOutUF = tempUF == null ? uf : tempUF;
								OutputStream fileOut = openOut(activity, fileOutUF);
								boolean newFileComplete = false;
								try {
									long filePos = 0;
									if(result == AOServDaemonProtocol.FAILOVER_FILE_REPLICATION_MODIFIED_REQUEST_DATA) {
										// Only the last chunk may be partial
										int lastChunkSize = AOServDaemonProtocol.FAILOVER_FILE_REPLICATION_CHUNK_SIZE;
										int response;
										while(true) {
											activity.update("socket: read: ", uf, " at ", filePos);
											response = in.read();
											if(response != AOServDaemonProtocol.NEXT) {
												break;
											}
											if(lastChunkSize < AOServDaemonProtocol.FAILOVER_FILE_REPLICATION_CHUNK_SIZE) {
												throw new IOException("Only the last chunk may be partial");
											}
											int blockLen = in.readCompressedInt();
											if(blockLen <= 0 || blockLen > AOServDaemonProtocol.FAILOVER_FILE_REPLICATION_CHUNK_SIZE) {
												throw new IOException("Invalid block length: " + blockLen);
											}
											in.readFully(chunkBuffer, 0, blockLen);
											activity.update("file: write: ", fileOutUF, " at ", filePos);
											fileOut.write(chunkBuffer, 0, blockLen);
											filePos += blockLen;
											lastChunkSize = blockLen;
										}
										if(response == -1) throw new EOFException();
										else if(response != AOServDaemonProtocol.DONE) throw new IOException("Unexpected response code: "+response);
									} else if(result == AOServDaemonProtocol.FAILOVER_FILE_REPLICATION_MODIFIED_REQUEST_DATA_CHUNKED) {
										if(!useCompression) throw new IOException("Not using compression, chunked transfer not supported");
										assert chunkingFroms != null;
										UnixFile chunkingFromUF = chunkingFroms[c];
										long chunkingSize = chunkingSizes[c];
										RandomAccessFile chunkingFromRaf = openInRaf(activity, chunkingFromUF);
										try {
											int partialChunkPos = 0;
											int response;
											while(true) {
												activity.update("socket: read: chunk result of ", uf, " at ", filePos);
												response = in.read();
												if(response == AOServDaemonProtocol.NEXT) {
													activity.update("socket: read: ", uf, " at ", filePos);
													int chunkLen = in.readCompressedInt();
													if(chunkLen < 0 || (partialChunkPos + chunkLen) > AOServDaemonProtocol.FAILOVER_FILE_REPLICATION_CHUNK_SIZE) {
														throw new IOException("Invalid chunk length: " + chunkLen);
													}
													in.readFully(chunkBuffer, partialChunkPos, chunkLen);
													partialChunkPos += chunkLen;
													if(partialChunkPos == AOServDaemonProtocol.FAILOVER_FILE_REPLICATION_CHUNK_SIZE) {
														// Full chunk to write
														activity.update("file: write: ", fileOutUF, " at ", filePos);
														fileOut.write(chunkBuffer, 0, AOServDaemonProtocol.FAILOVER_FILE_REPLICATION_CHUNK_SIZE);
														filePos += AOServDaemonProtocol.FAILOVER_FILE_REPLICATION_CHUNK_SIZE;
														partialChunkPos = 0;
													}
												} else if(response == AOServDaemonProtocol.NEXT_CHUNK) {
													if(partialChunkPos != 0) throw new IOException("Chunk matched after partial chunk");
													// Get the values from the old file (chunk matches)
													{
														long chunkSizeL = chunkingSize - filePos;
														if(chunkSizeL < 0) throw new IOException("Client sent chunk beyond end of server chunks");
														if(chunkSizeL >= AOServDaemonProtocol.FAILOVER_FILE_REPLICATION_CHUNK_SIZE) {
															partialChunkPos = AOServDaemonProtocol.FAILOVER_FILE_REPLICATION_CHUNK_SIZE;
														} else {
															partialChunkPos = (int)chunkSizeL;
														}
													}
													activity.update("file: read: ", chunkingFromUF, " at ", filePos);
													chunkingFromRaf.seek(filePos);
													chunkingFromRaf.readFully(chunkBuffer, 0, partialChunkPos);
													if(partialChunkPos == AOServDaemonProtocol.FAILOVER_FILE_REPLICATION_CHUNK_SIZE) {
														// Full chunk to write
														activity.update("file: write: ", fileOutUF, " at ", filePos);
														fileOut.write(chunkBuffer, 0, AOServDaemonProtocol.FAILOVER_FILE_REPLICATION_CHUNK_SIZE);
														filePos += AOServDaemonProtocol.FAILOVER_FILE_REPLICATION_CHUNK_SIZE;
														partialChunkPos = 0;
													}
												} else {
													break;
												}
											}
											// Write any incomplete partial chunk data
											if(partialChunkPos != 0) {
												activity.update("file: write: ", fileOutUF, " at ", filePos);
												fileOut.write(chunkBuffer, 0, partialChunkPos);
												filePos += partialChunkPos;
												partialChunkPos = 0;
											}
											if(response == -1) throw new EOFException();
											else if(response != AOServDaemonProtocol.DONE) throw new IOException("Unexpected response code: "+response);
										} finally {
											close(activity, chunkingFromUF, chunkingFromRaf);
										}
									} else {
										throw new RuntimeException("Unexpected value for result: "+result);
									}
									newFileComplete = true;
								} finally {
									close(activity, fileOutUF, fileOut);

									// If the new file is incomplete for any reason (presumably due to an exception)
									// and we are doing a backup to a temporary file, move the temp file over the old file
									// if it is longer
									if(
										!newFileComplete
										&& retention != 1
										&& tempUF != null
									) {
										Stat ufStat = stat(activity, uf);
										if(!ufStat.exists()) {
											// If it doesn't exist, can't compare file sizes, just rename
											if(isFine) logger.fine("Renaming partial temp file to final filename because final filename doesn't exist: "+uf.getPath());
											renameToNoExists(logger, activity, tempUF, uf);
											// This should only happen during exceptions, so no need to keep directory caches synchronized
										} else {
											long ufSize = ufStat.getSize();
											long tempUFSize = stat(activity, tempUF).getSize();
											if(tempUFSize > ufSize) {
												if(isFine) logger.fine("Renaming partial temp file to final filename because temp file is longer than the final file: "+uf.getPath());
												rename(activity, tempUF, uf);
												// This should only happen during exceptions, so no need to keep directory caches synchronized
											}
										}
									}
								}
								activity.update("file: utime: ", fileOutUF);
								fileOutUF.utime(stat(activity, fileOutUF).getAccessTime(), modifyTimes[c]);
								Stat ufStat = stat(activity, uf);
								if(tempUF != null) {
									if(ufStat.exists()) {
										if(ufStat.isRegularFile()) {
											if(isLogDirs[c]) {
												// Move to a new temp filename for later reuse
												UnixFile tempUFLog = mktemp(activity, uf);
												// Update filesystem
												if(retention==1) {
													// Failover mode does a more cautious link to temp and rename over to avoid
													// any moment where there is no file in the path of uf
													delete(activity, tempUFLog);
													link(activity, tempUFLog, uf);
													rename(activity, tempUF, uf);
													ufStat = stat(activity, uf);
												} else {
													// Backup mode uses a more efficient approach because partial states are OK
													rename(activity, uf, tempUFLog);
													rename(activity, tempUF, uf);
													ufStat = stat(activity, uf);
												}
												// Update cache (cache update counted as removeByValue and then add because cache renaming method expects renameTo to not exist
												renamed(modifyTimeAndSizeCaches, uf, tempUFLog, ufParent);
											} else {
												// Not a log directory, just replace old regular file
												// Update cache (cache update counted as removeByValue and then add because cache renaming method expects renameTo to not exist
												removing(modifyTimeAndSizeCaches, uf, ufStat, ufParent);
												// Update filesystem
												rename(activity, tempUF, uf);
												ufStat = stat(activity, uf);
											}
										} else {
											// Update cache
											removing(modifyTimeAndSizeCaches, uf, ufStat, ufParent);
											// Update filesystem
											deleteRecursive(activity, uf);
											rename(activity, tempUF, uf);
											ufStat = stat(activity, uf);
										}
									} else {
										rename(activity, tempUF, uf);
										ufStat = stat(activity, uf);
									}
								}
								// Update cache (cache update counted as removeByValue and then add because cache renaming method expects renameTo to not exist
								added(activity, modifyTimeAndSizeCaches, uf, ufParent, new ModifyTimeAndSize(ufStat.getModifyTime(), ufStat.getSize()));
							}
						}
					}

					// For any directories that were completed during this batch, removeByValue caches, clean extra files and set its modify time
					for(int c=0; c<directoryFinalizeUFs.size(); c++) {
						UnixFile dirUF = directoryFinalizeUFs.get(c);
						UnixFile dirLinkToUF = directoryFinalizeLinkToUFs==null ? null : directoryFinalizeLinkToUFs.get(c);
						String relativePath = directoryFinalizeUFRelativePaths.get(c);
						long dirModifyTime = directoryFinalizeModifyTimes.get(c);
						Set<String> dirContents = directoryFinalizeContents.get(c);
						// Remove from the caches since we are done with the directory entirely for this pass
						if(!modifyTimeAndSizeCaches.isEmpty()) modifyTimeAndSizeCaches.remove(dirUF);
						if(dirLinkToUF!=null && !modifyTimeAndSizeCaches.isEmpty()) modifyTimeAndSizeCaches.remove(dirLinkToUF);
						// Remove extra files
						String dirPath = dirUF.getPath();
						if(!dirPath.endsWith("/")) dirPath += '/';
						String[] list = list(activity, dirUF);
						if(list != null) {
							for (String filename : list) {
								String fullpath = dirPath + filename;
								if(!dirContents.contains(fullpath)) {
									if(deleteOnCleanup(fromServer, retention, relativePath+'/'+filename, replicatedMySQLServers, replicatedMySQLMinorVersions)) {
										if(isTrace) logger.finer("Deleting extra file: "+fullpath);
										try {
											UnixFile deleteMe = new UnixFile(fullpath);
											deleteRecursive(activity, deleteMe);
										} catch(FileNotFoundException err) {
											logger.log(Level.SEVERE, "fullpath="+fullpath, err);
										}
									}
								}
							}
						}
						// Set the modified time
						Stat dirStat = stat(activity, dirUF);
						if(dirStat.getModifyTime() != dirModifyTime) {
							activity.update("file: utime: ", dirUF);
							dirUF.utime(dirStat.getAccessTime(), dirModifyTime);
						}
					}
				}

				// modifyTimeAndSizeCaches is no longer used after this, this makes sure
				int modifyTimeAndSizeCachesSize = modifyTimeAndSizeCaches.size();
				modifyTimeAndSizeCaches = null;

				// Clean all remaining directories all the way to /, setting modifyTime on the directories
				while(!directoryUFs.isEmpty()) {
					UnixFile dirUF = directoryUFs.peek();
					String dirPath = dirUF.getPath();
					if(!dirPath.endsWith("/")) dirPath += '/';

					// Otherwise, clean and complete the directory
					directoryUFs.pop();
					if(directoryLinkToUFs!=null) directoryLinkToUFs.pop(); // Just to keep the stacks uniform between them
					String relativePath = directoryUFRelativePaths.pop();
					long dirModifyTime=directoryModifyTimes.pop();
					Set<String> dirContents=directoryContents.pop();
					String[] list = list(activity, dirUF);
					if(list != null) {
						for (String filename : list) {
							String fullpath = dirPath + filename;
							if(!dirContents.contains(fullpath)) {
								if(deleteOnCleanup(fromServer, retention, relativePath+'/'+filename, replicatedMySQLServers, replicatedMySQLMinorVersions)) {
									if(isTrace) logger.finer("Deleting final clean-up: "+fullpath);
									try {
										UnixFile deleteMe = new UnixFile(fullpath);
										deleteRecursive(activity, deleteMe);
									} catch(FileNotFoundException err) {
										logger.log(Level.SEVERE, "fullpath="+fullpath, err);
									}
								}
							}
						}
					}
					Stat dirStat = stat(activity, dirUF);
					if(dirStat.getModifyTime() != dirModifyTime) {
						activity.update("file: utime: ", dirUF);
						dirUF.utime(dirStat.getAccessTime(), dirModifyTime);
					}
				}

				// Log the final stats
				if(isInfo) {
					logger.info("modifyTimeAndSizeCachesSize="+modifyTimeAndSizeCachesSize);
				}

				if(retention!=1) {
					// The pass was successful, now rename partial to final
					String from = isRecycling ? recycledPartialMirrorRoot : partialMirrorRoot;
					renameToNoExists(logger, activity, new UnixFile(from), new UnixFile(finalMirrorRoot));

					// The pass was successful, now cleanup old directories based on retention settings
					cleanAndRecycleBackups(activity, retention, perDateRoot, fromServerYear, fromServerMonth, fromServerDay);
				}

				// Tell the client we are done OK
				activity.update("socket: write: AOServDaemonProtocol.DONE");
				out.write(AOServDaemonProtocol.DONE);
				out.flush();
			} catch(RuntimeException | IOException err) {
				activity.update("socket: close");
				socket.close();
				throw err;
			} finally {
				if(postPassChecklist.restartMySQLs && retention==1) {
					for(MySQLServerName mysqlServer : replicatedMySQLServers) {
						String message = "Restarting MySQL "+mysqlServer+" in \""+toPath+'"';
						activity.update("logic: ", message);
						if(isFine) logger.fine(message);
						String[] command = {
							"/usr/sbin/chroot",
							toPath,
							"/etc/rc.d/init.d/mysql-"+mysqlServer,
							"restart"
						};
						try {
							AOServDaemon.exec(
								command
							);
						} catch(IOException err) {
							logger.log(Level.SEVERE, AOServDaemon.getCommandString(command), err);
						}
					}
				}
			}
			success = true;
		} finally {
			activity.update(success ? "logic: return: successful" : "logic: return: unsuccessful");
		}
	}

	/**
	 * Called before something is removed, to keep the cache in sync.
	 */
	private static void removing(Map<UnixFile,ModifyTimeAndSizeCache> modifyTimeAndSizeCaches, UnixFile uf, Stat ufStat, UnixFile ufParent) throws FileNotFoundException {
		if(!modifyTimeAndSizeCaches.isEmpty()) {
			if(ufStat.isRegularFile()) {
				// For a regular file, just removeByValue it from its parent, this is the fastest case
				// because no scan for children directories is required.

				ModifyTimeAndSizeCache modifyTimeAndSizeCache = modifyTimeAndSizeCaches.get(ufParent);
				if(modifyTimeAndSizeCache!=null) modifyTimeAndSizeCache.removing(uf.getFile().getName());
			} else if(ufStat.isDirectory()) {
				// For a directory, removeByValue it and any of the directories under it.
				// This is more expensive because we need to search all caches for prefix matches (iteration).

				// Remove any items that are this or are children of this
				String prefix = uf.getPath();
				if(!prefix.endsWith("/")) prefix += '/';
				Iterator<Map.Entry<UnixFile,ModifyTimeAndSizeCache>> iter = modifyTimeAndSizeCaches.entrySet().iterator();
				while(iter.hasNext()) {
					Map.Entry<UnixFile,ModifyTimeAndSizeCache> entry = iter.next();
					UnixFile key = entry.getKey();
					if(
						key.equals(uf)
						|| key.getPath().startsWith(prefix)
					) iter.remove();
				}
			}
		}
	}

	/**
	 * Called after a file is added, to keep the cache in sync.
	 */
	private static void added(Activity activity, Map<UnixFile,ModifyTimeAndSizeCache> modifyTimeAndSizeCaches, UnixFile uf, UnixFile ufParent, ModifyTimeAndSize ufModifyTimeAndSize) throws IOException {
		if(!modifyTimeAndSizeCaches.isEmpty()) {
			ModifyTimeAndSizeCache modifyTimeAndSizeCache = modifyTimeAndSizeCaches.get(ufParent);
			if(modifyTimeAndSizeCache!=null) {
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
	private static void renamed(Map<UnixFile,ModifyTimeAndSizeCache> modifyTimeAndSizeCaches, UnixFile oldUF, UnixFile newUF, UnixFile ufParent) {
		if(!modifyTimeAndSizeCaches.isEmpty()) {
			ModifyTimeAndSizeCache modifyTimeAndSizeCache = modifyTimeAndSizeCaches.get(ufParent);
			if(modifyTimeAndSizeCache!=null) {
				modifyTimeAndSizeCache.renamed(
					oldUF.getFile().getName(),
					newUF.getFile().getName()
				);
			}
		}
	}

	/**
	 * Encapsulates a modified time and size.  Is immutable and may be used a as Map key.
	 */
	final static class ModifyTimeAndSize {

		final long modifyTime;
		final long size;

		ModifyTimeAndSize(long modifyTime, long size) {
			this.modifyTime = modifyTime;
			this.size = size;
		}

		@Override
		public int hashCode() {
			return (int)(modifyTime * 31 + size);
		}

		@Override
		public boolean equals(Object O) {
			if(O==null) return false;
			if(!(O instanceof ModifyTimeAndSize)) return false;
			ModifyTimeAndSize other = (ModifyTimeAndSize)O;
			return
				modifyTime==other.modifyTime
				&& size==other.size
			;
		}
	}

	/**
	 * Caches the directory.
	 */
	final static class ModifyTimeAndSizeCache {

		final private UnixFile directory;
		final private Map<String,ModifyTimeAndSize> filenameMap = new HashMap<>();
		final private Map<ModifyTimeAndSize,List<String>> modifyTimeAndSizeMap = new HashMap<>();

		ModifyTimeAndSizeCache(Activity activity, UnixFile directory) throws IOException {
			this.directory = directory;
			// Read all files in the directory to populate the caches
			String[] list = list(activity, directory);
			if(list != null) {
				for(int d = 0, len = list.length; d < len; d++) {
					String filename = list[d];
					UnixFile file = new UnixFile(directory, filename, false);
					Stat stat = stat(activity, file);
					if(
						stat.exists()
						&& stat.isRegularFile()
					) {
						ModifyTimeAndSize modifyTimeAndSize = new ModifyTimeAndSize(stat.getModifyTime(), stat.getSize());
						filenameMap.put(filename, modifyTimeAndSize);
						List<String> fileList = modifyTimeAndSizeMap.get(modifyTimeAndSize);
						if(fileList==null) modifyTimeAndSizeMap.put(modifyTimeAndSize, fileList = new ArrayList<>());
						fileList.add(filename);
					}
				}
			}
		}

		UnixFile getDirectory() {
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
			if(modifyTimeAndSize==null) throw new AssertionError("filenameMap doesn't contain filename: filename="+filename);
			List<String> filenames = modifyTimeAndSizeMap.get(modifyTimeAndSize);
			if(filenames==null) throw new AssertionError("modifyTimeAndSizeMap doesn't contain modifyTimeAndSize");
			if(!filenames.remove(filename)) throw new AssertionError("filenames didn't contain filename: filename="+filename);
			if(filenames.isEmpty()) modifyTimeAndSizeMap.remove(modifyTimeAndSize);
		}

		/**
		 * To maintain correct cache state, this should be called after a regular file in this directory is renamed.
		 */
		void renamed(String oldFilename, String newFilename) {
			// The new filename must not exist
			if(filenameMap.containsKey(newFilename)) throw new AssertionError("filenameMap already contains newFilename: newFilename="+newFilename);
			// Move in the filenameMap
			ModifyTimeAndSize modifyTimeAndSize = filenameMap.remove(oldFilename);
			// The old filename must exist in the cache, otherwise we have a cache coherency problem
			if(modifyTimeAndSize==null) throw new AssertionError("oldFilename not in filenameMap: oldFilename="+oldFilename);
			filenameMap.put(newFilename, modifyTimeAndSize);
			// Update in the modifyTimeAndSizeMap map
			List<String> filenames = modifyTimeAndSizeMap.get(modifyTimeAndSize);
			if(filenames==null) throw new AssertionError("filenames is null");
			int index = filenames.indexOf(oldFilename);
			if(index==-1) throw new AssertionError("index is -1, oldFilename not found in filenames");
			filenames.set(index, newFilename);
		}

		/**
		 * To maintain correct cache state, this should be called whenever a regular file in this directory is linked.
		 * This only works if they are both in the same directory.
		 */
		/*
		TODO: call activity.update if uncomment this code
		void linking(String filename, String linkToFilename) {
			// The filename must not exist in the cache
			if(filenameMap.containsKey(filename)) throw new AssertionError("filenameMap already contains filename: filename="+filename);
			// Add in the filenameMap as duplicate of linkToFilename
			ModifyTimeAndSize modifyTimeAndSize = filenameMap.get(linkToFilename);
			if(modifyTimeAndSize==null) throw new AssertionError("linkToFilename not in filenameMap: linkToFilename="+linkToFilename);
			filenameMap.put(filename, modifyTimeAndSize);
			// Update in the modifyTimeAndSizeMap map
			List<String> filenames = modifyTimeAndSizeMap.get(modifyTimeAndSize);
			if(filenames==null) throw new AssertionError("filenames is null");
			if(USE_OLD_AND_NEW_LOG_DIRECTORY_LINKING) {
				if(!filenames.contains(linkToFilename)) throw new AssertionError("filenames doesn't contain linkToFilename: linkToFilename="+linkToFilename);
				if(filenames.contains(filename)) throw new AssertionError("filenames already contains filename: filename="+filename);
			}
			filenames.add(filename);
		}*/

		/**
		 * To maintain correct cache state, this should be called after a regular file is added to this directory.
		 */
		void added(String filename, ModifyTimeAndSize modifyTimeAndSize) {
			// The filename must not exist in the cache
			if(filenameMap.containsKey(filename)) throw new AssertionError("filenameMap already contains filename: filename="+filename);
			// Add to the maps
			filenameMap.put(filename, modifyTimeAndSize);
			List<String> fileList = modifyTimeAndSizeMap.get(modifyTimeAndSize);
			if(fileList==null) modifyTimeAndSizeMap.put(modifyTimeAndSize, fileList = new ArrayList<>());
			fileList.add(filename);
		}
	}

	/**
	 * Determines if a specific file may be deleted on clean-up.
	 * Don't delete anything in /proc/*, /sys/*, /selinux/*, /dev/pts/*, or MySQL replication-related files
	 */
	private static boolean deleteOnCleanup(String fromServer, int retention, String relativePath, List<MySQLServerName> replicatedMySQLServers, List<String> replicatedMySQLMinorVersions) {
		Logger logger = LogFactory.getLogger(FailoverFileReplicationManager.class);
		boolean isDebug = logger.isLoggable(Level.FINE);
		if(
			relativePath.equals("/proc")
			|| relativePath.startsWith("/proc/")
			|| relativePath.equals("/sys")
			|| relativePath.startsWith("/sys/")
			|| relativePath.equals("/selinux")
			|| relativePath.startsWith("/selinux/")
			|| relativePath.equals("/dev/pts")
			|| relativePath.startsWith("/dev/pts/")
		) {
			if(isDebug) logger.fine("Skipping delete on cleanup: \""+fromServer+"\":"+relativePath);
			return false;
		}
		if(retention==1) {
			for(MySQLServerName name : replicatedMySQLServers) {
				if(
					(
						relativePath.startsWith("/var/lib/mysql/")
						&& (
								   relativePath.equals("/var/lib/mysql/"+name)
							|| relativePath.startsWith("/var/lib/mysql/"+name+"/")
							||     relativePath.equals("/var/lib/mysql/"+name+"-old")
							|| relativePath.startsWith("/var/lib/mysql/"+name+"-old/")
							||     relativePath.equals("/var/lib/mysql/"+name+"-new")
							|| relativePath.startsWith("/var/lib/mysql/"+name+"-new/")
							||     relativePath.equals("/var/lib/mysql/"+name+"-fast")
							|| relativePath.startsWith("/var/lib/mysql/"+name+"-fast/")
							||     relativePath.equals("/var/lib/mysql/"+name+"-slow")
							|| relativePath.startsWith("/var/lib/mysql/"+name+"-slow/")
						)
					) || (
						relativePath.startsWith("/var/lib/mysql-fast/")
						&& (
								   relativePath.equals("/var/lib/mysql-fast/"+name)
							|| relativePath.startsWith("/var/lib/mysql-fast/"+name+"/")
						)
					) || (
						relativePath.startsWith("/var/lib/mysql-slow/")
						&& (
								   relativePath.equals("/var/lib/mysql-slow/"+name)
							|| relativePath.startsWith("/var/lib/mysql-slow/"+name+"/")
						)
					) || relativePath.equals("/var/lock/subsys/mysql-"+name)
				) {
					if(isDebug) logger.fine("Skipping delete on cleanup: \""+fromServer+"\":"+relativePath);
					return false;
				}
			}
		}
		return true;
	}

	private static void cleanAndRecycleBackups(Activity activity, short retention, UnixFile serverRootUF, short fromServerYear, short fromServerMonth, short fromServerDay) throws IOException, SQLException {
		final Logger logger = LogFactory.getLogger(FailoverFileReplicationManager.class);
		final boolean isFine = logger.isLoggable(Level.FINE);
		try {
			// Build the lists of directories based on age, skipping safe deleted and recycled directories
			Calendar cal = Calendar.getInstance();
			cal.set(Calendar.YEAR, fromServerYear);
			cal.set(Calendar.MONTH, fromServerMonth-1);
			cal.set(Calendar.DAY_OF_MONTH, fromServerDay);
			cal.set(Calendar.HOUR_OF_DAY, 0);
			cal.set(Calendar.MINUTE, 0);
			cal.set(Calendar.SECOND, 0);
			cal.set(Calendar.MILLISECOND, 0);
			long fromServerDate = cal.getTimeInMillis();
			Map<Integer,List<String>> directoriesByAge;
			{
				String[] list = list(activity, serverRootUF);
				directoriesByAge = new HashMap<>(list==null ? 0 : (list.length*4/3 + 1));
				if(list != null) {
					for(String filename : list) {
						if(!filename.endsWith(SAFE_DELETE_EXTENSION) && !filename.endsWith(RECYCLED_EXTENSION)) {
							// Not y10k compatible
							if(filename.length() >= 10) {
								try {
									int year = Integer.parseInt(filename.substring(0, 4));
									if(filename.charAt(4)=='-') {
										int month = Integer.parseInt(filename.substring(5, 7));
										if(filename.charAt(7)=='-') {
											int day = Integer.parseInt(filename.substring(8, 10));
											cal.set(Calendar.YEAR, year);
											cal.set(Calendar.MONTH, month-1);
											cal.set(Calendar.DAY_OF_MONTH, day);
											cal.set(Calendar.HOUR_OF_DAY, 0);
											cal.set(Calendar.MINUTE, 0);
											cal.set(Calendar.SECOND, 0);
											cal.set(Calendar.MILLISECOND, 0);
											int age = SafeMath.castInt(
												(fromServerDate - cal.getTimeInMillis())
												/
												(24l * 60 * 60 * 1000)
											);
											if(age >= 0) {
												// Must also be a date directory with no extension, or one of the expected extensions to delete:
												if(
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
													if(directories==null) directoriesByAge.put(age, directories=new ArrayList<>());
													directories.add(filename);
												} else {
													logger.log(Level.WARNING, null, new IOException("Skipping unexpected directory: "+filename));
												}
											} else {
												logger.log(Level.WARNING, null, new IOException("Directory date in future: "+filename));
											}
										} else {
											logger.log(Level.WARNING, null, new IOException("Unable to parse filename: "+filename));
										}
									} else {
										logger.log(Level.WARNING, null, new IOException("Unable to parse filename: "+filename));
									}
								} catch(NumberFormatException err) {
									logger.log(Level.WARNING, null, new IOException("Unable to parse filename: "+filename));
								}
							} else {
								logger.log(Level.WARNING, null, new IOException("Filename too short: "+filename));
							}
						}
					}
				}
			}

			if(isFine) {
				List<Integer> ages = new ArrayList<>(directoriesByAge.keySet());
				Collections.sort(ages);
				for(Integer age : ages) {
					List<String> directories = directoriesByAge.get(age);
					for(String directory : directories) {
						logger.fine(age + ": " + directory);
					}
				}
			}

			// These will be marked for deletion first, recycled where possible, then actually deleted if not recycled
			List<String> deleteFilenames = new ArrayList<>();

			boolean lastHasSuccess = true;
			if(retention <= 7) {
				// These are daily
				lastHasSuccess = false;
				// delete everything after the # of complete passes equalling the retention
				int completeCount = 0;
				for(int age=0;age<=retention;age++) {
					if(completeCount<=retention) {
						if(hasComplete(directoriesByAge, age, deleteFilenames)) {
							completeCount++;
							lastHasSuccess = true;
						}
					} else {
						if(directoriesByAge.containsKey(age)) {
							delete(directoriesByAge, age, deleteFilenames);
							directoriesByAge.remove(age);
						}
					}
				}
			}
			// Go through each retention level >= 14
			List<BackupRetention> brs = AOServDaemon.getConnector().getBackupRetentions().getRows();
			int lastLevel = 0;
			for(BackupRetention br : brs) {
				int currentLevel = br.getDays();
				if(currentLevel>=14) {
					if(retention>=currentLevel) {
						if(lastHasSuccess) {
							lastHasSuccess = false;
							// Delete all but the oldest successful between 8-14
							boolean foundSuccessful = false;
							for(int age=currentLevel;age>=(lastLevel+1);age--) {
								if(!foundSuccessful) {
									if(hasComplete(directoriesByAge, age, deleteFilenames)) {
										foundSuccessful = true;
										lastHasSuccess = true;
										for(int deleteAge = age+1;deleteAge<currentLevel;deleteAge++) {
											if(directoriesByAge.containsKey(deleteAge)) {
												delete(directoriesByAge, deleteAge, deleteFilenames);
												directoriesByAge.remove(deleteAge);
											}
										}
									}
								} else {
									if(directoriesByAge.containsKey(age)) {
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
			for(Integer age : ages) {
				if(age>=retention) {
					if(!foundSuccessful) {
						if(hasComplete(directoriesByAge, age, deleteFilenames)) {
							foundSuccessful = true;
						}
					} else {
						if(directoriesByAge.containsKey(age)) {
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
			for(String directory : deleteFilenames) {
				if(
					directory.length()==10
					&& !directory.endsWith(PARTIAL_EXTENSION)
					&& !directory.endsWith(SAFE_DELETE_EXTENSION)
					&& !directory.endsWith(RECYCLED_EXTENSION)
				) {
					// 1) Flag all those that were completed as recycled
					final UnixFile currentUF = new UnixFile(serverRootUF, directory, false);
					final UnixFile newUF = new UnixFile(serverRootUF, directory+RECYCLED_EXTENSION, false);
					renameToNoExists(logger, activity, currentUF, newUF);
				} else {
					// 2) Flag all those that where not completed directly as .deleted, schedule for delete
					if(!directory.endsWith(SAFE_DELETE_EXTENSION)) {
						final UnixFile currentUF = new UnixFile(serverRootUF, directory, false);
						final UnixFile newUF = new UnixFile(serverRootUF, directory+SAFE_DELETE_EXTENSION, false);
						renameToNoExists(logger, activity, currentUF, newUF);
					}
				}
			}

			// 3) Keep X most recent .recycled directories (not partials, though)
			// 4) Rename older .recycled directories to .deleted
			{
				final int numRecycle = getNumberRecycleDirectories(retention);
				String[] list = list(activity, serverRootUF);
				if(list != null && list.length > 0) {
					Arrays.sort(list);
					int recycledFoundCount = 0;
					for(int c=list.length-1;c>=0;c--) {
						String directory = list[c];
						if(directory.endsWith(RECYCLED_EXTENSION)) {
							if(recycledFoundCount<numRecycle) {
								recycledFoundCount++;
							} else {
								// Rename to .deleted
								String newFilename = directory.substring(0, directory.length()-RECYCLED_EXTENSION.length())+SAFE_DELETE_EXTENSION;
								final UnixFile currentUF = new UnixFile(serverRootUF, directory, false);
								final UnixFile newUF = new UnixFile(serverRootUF, newFilename, false);
								renameToNoExists(logger, activity, currentUF, newUF);
							}
						}
					}
				}
			}

			// 5) Delete all those that end in .deleted, from oldest to newest
			if(!SAFE_DELETE) {
				String[] list = list(activity, serverRootUF);
				if(list != null && list.length > 0) {
					Arrays.sort(list);
					final List<File> directories = new ArrayList<>(list.length);
					for (String directory : list) {
						if(directory.endsWith(SAFE_DELETE_EXTENSION)) {
							//found=true;
							UnixFile deleteUf = new UnixFile(serverRootUF, directory, false);
							if(isFine) logger.fine("Deleting: "+deleteUf.getPath());
							directories.add(deleteUf.getFile());
						}
					}
					if(!directories.isEmpty()) {
						// Delete in the background
						AOServDaemon.executorService.submit(() -> {
							try {
								if(directories.size()==1) {
									// Single directory - no parallel benefits, use system rm command
									AOServDaemon.exec(
										"/bin/rm",
										"-rf",
										directories.get(0).getPath()
									);
								} else {
									ParallelDelete.parallelDelete(directories, null, false);
								}
							} catch(IOException err) {
								logger.log(Level.SEVERE, null, err);
							}
						});
					}
				}
			}
		} catch(RuntimeException | IOException err) {
			logger.log(Level.SEVERE, null, err);
		}
	}

	private static boolean hasComplete(Map<Integer,List<String>> directoriesByAge, int age, List<String> deleteFilenames) {
		List<String> directories = directoriesByAge.get(age);
		if(directories!=null) {
			for(String directory : directories) {
				if(
					directory.length()==10
					&& !deleteFilenames.contains(directory)
					&& !directory.endsWith(PARTIAL_EXTENSION)
					&& !directory.endsWith(SAFE_DELETE_EXTENSION)
					&& !directory.endsWith(RECYCLED_EXTENSION)
				) return true;
			}
		}
		return false;
	}

	private static void delete(Map<Integer,List<String>> directoriesByAge, int age, List<String> deleteFilenames) throws IOException {
		List<String> directories = directoriesByAge.get(age);
		if(directories!=null) {
			for(String directory : directories) {
				if(
					!deleteFilenames.contains(directory)
					&& !directory.endsWith(SAFE_DELETE_EXTENSION)
					&& !directory.endsWith(RECYCLED_EXTENSION)
				) {
					deleteFilenames.add(directory);
				}
			}
		}
	}

	private static boolean started = false;
	public static void start() throws IOException, SQLException {
		if(AOServDaemonConfiguration.isManagerEnabled(FailoverFileReplicationManager.class)) {
			synchronized(System.out) {
				if(!started) {
					System.out.print("Starting FailoverFileReplicationManager: ");
					BackupDaemon daemon = new BackupDaemon(new AOServerEnvironment());
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
	private static boolean copyIfHardLinked(Activity activity, UnixFile uf, Stat ufStat) throws IOException {
		if(ufStat.isRegularFile() && ufStat.getNumberLinks()>1) {
			Logger logger = LogFactory.getLogger(FailoverFileReplicationManager.class);
			if(logger.isLoggable(Level.FINER)) logger.finer("Copying file due to hard link: "+uf);
			UnixFile temp = mktemp(activity, uf);
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
			while(true) {
				System.out.println("Please press enter c for complete day or p for partial day: ");
				String line = in.readLine();
				if(line==null) break;
				boolean successful = !line.startsWith("p");
				System.arraycopy(states, 0, states, 1, states.length-1);
				states[0]=successful ? "complete" : "partial";

				// Clean up algorithm
				boolean lastHasSuccess = true;
				if(retention<=7) {
					// These are daily
					lastHasSuccess = false;
					// delete everything after the # of complete passes equalling the retention
					int completeCount = 0;
					for(int age=0;age<=retention;age++) {
						if(completeCount<=retention) {
							if("complete".equals(states[age])) {
								completeCount++;
								lastHasSuccess = true;
							}
						} else {
							if(states[age]!=null) {
								System.out.println("Deleting "+age);
								states[age]=null;
							}
						}
					}
				}
				// Go through each retention level >= 14
				List<BackupRetention> brs = AOServDaemon.getConnector().backupRetentions.getRows();
				int lastLevel = 0;
				for(BackupRetention br : brs) {
					int currentLevel = br.getDays();
					if(currentLevel>=14) {
						if(retention>=currentLevel) {
							if(lastHasSuccess) {
								lastHasSuccess = false;
								// Delete all but the oldest successful between 8-14
								boolean foundSuccessful = false;
								for(int age=currentLevel;age>=(lastLevel+1);age--) {
									if(!foundSuccessful) {
										if("complete".equals(states[age])) {
											foundSuccessful = true;
											lastHasSuccess = true;
											for(int deleteAge = age+1;deleteAge<currentLevel;deleteAge++) {
												if(states[deleteAge]!=null) {
													System.out.println("Deleting "+deleteAge);
													states[deleteAge]=null;
												}
											}
										}
									} else {
										if(states[age]!=null) {
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
				for(int age=retention;age<states.length;age++) {
					if(!foundSuccessful) {
						if("complete".equals(states[age])) {
							foundSuccessful = true;
						}
					} else {
						if(states[age]!=null) {
							System.out.println("Deleting "+age);
							states[age]=null;
						}
					}
				}

				// Display new data
				for(int c=0;c<states.length;c++) if(states[c]!=null) System.out.println(c+": "+states[c]);
			}
		} catch(IOException err) {
			err.printStackTrace();
		}
	}
	 */
}
