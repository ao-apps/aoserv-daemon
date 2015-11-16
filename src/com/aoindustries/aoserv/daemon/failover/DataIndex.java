/*
 * Copyright 2015 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.aoserv.daemon.failover;

import com.aoindustries.aoserv.daemon.LogFactory;
import com.aoindustries.aoserv.daemon.client.AOServDaemonProtocol;
import com.aoindustries.cron.CronDaemon;
import com.aoindustries.cron.CronJob;
import com.aoindustries.cron.CronJobScheduleMode;
import com.aoindustries.cron.Schedule;
import com.aoindustries.io.unix.Stat;
import com.aoindustries.io.unix.UnixFile;
import com.aoindustries.util.StringUtility;
import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * <p>
 * Each backup partition may optionally use a central index of data chunks for
 * all non-empty regular files.  Files are split into chunks of
 * <code>FAILOVER_FILE_REPLICATION_CHUNK_SIZE</code> bytes.  Each chunk may be
 * reused via hard links in order to achieve space savings.  Each chunk is also
 * optionally gzip compressed for additional space savings.  File tails (partial
 * chunks at the end less than <code>FAILOVER_FILE_REPLICATION_CHUNK_SIZE</code>
 * in length) are also added to the index.  A zero length chunk may never be
 * added.
 * </p>
 * <p>
 * The central index is a single layer directory hash, based on the first four
 * characters of the content's MD5 hash.  Because of this single layer hash, an
 * individual hash directory can easily have enough entries to require a
 * filesystem with support for many files in one directory, such as ext4 or xfs.
 * </p>
 * <p>
 * Files are named based on the lowercase hex-coded MD5 hash of the uncompressed
 * chunk contents.  However, due to both MD5 hash collisions and the per-file
 * hard link limits, there may be more than one file per MD5 hash value.  The
 * files are named as follows:
 * </p>
 * <pre>(/backup_partition)/DATA-INDEX/(directory_hash)/(remaining_hash)-(uncompressed_length)-(collision#)-(link#)[.gz][.corrupt]</pre>
 * <p>
 * The <code>directory_hash</code> is the first four characters of the MD5 sum.
 * </p>
 * <p>
 * The <code>remaining_hash</code> is the remaining 28 characters of the MD5 sum.
 * </p>
 * <p>
 * The <code>uncompressed_length</code> is the hex-coded length of the
 * uncompressed chunk contents.  When the length is a multiple of 0x100000
 * (1 MiB), it is represented with an "M" following the number of mebibytes in
 * hex.  When the length is a multiple of 0x400 (1 kiB), it is represented with
 * a "k" following the number of kibibytes in hex.
 * </p>
 * <p>
 * The uncompressed length is added to the filename to allow the efficient
 * location of candidate contents in the event of an MD5 collision.  Chunks with
 * a different size can be immediately excluded by filename without any
 * additional <code>stat</code> (for uncompressed) or full decompression.  Note
 * that if the file does not end with ".gz", this length will always equal the
 * actual file length.
 * </p>
 * <p>
 * The <code>collision#</code> is a zero-based hex counter for each unique set
 * of data resulting in this MD5 hash.  When locating new data from the index,
 * matches are not done by MD5 alone, the contents will be verified byte-by-byte.
 * When a second set of content must be added for a given MD5 hash, it will be
 * <code>(remaining_hash)-(uncompressed_length)-<em>1</em>-(link#)[.gz]</code>.
 * </p>
 * <p>
 * The <code>link#</code> is a zero-based hex counter to workaround the
 * filesystem limits on the number of hard links allowed to one file (65000 for
 * ext4).  Once the first file is "full", a second copy of the content is stored.
 * The second link file will be named
 * <code>(remaining_hash)-(uncompressed_length)-(collision#)-<em>1</em>[.gz]</code>
 * </p>
 * <p>
 * The <code>.gz</code> extension is added to chunks that have been gzip
 * compressed.  Chunks smaller than <code>FILESYSTEM_BLOCK_SIZE</code> are
 * never compressed as the space reduction will not yield any savings.
 * For larger files, the chunk is compressed, then the compressed version is
 * only used if it is sufficiently smaller to cross a
 * <code>FILESYSTEM_BLOCK_SIZE</code> block boundary in size.  This avoids
 * further compression overhead when the space reduction does not yield any
 * savings.
 * </p>
 * <p>
 * The <code>.corrupt</code> extension indicates that the background verifier
 * detected this chunk to no longer match the expected MD5 sum or chunk length
 * and the chunk could not be restored from another copy (see <code>link#</code>).
 * TODO: Can we restore this from backup and recover in-place and remove .corrupted from the filename?
 * This file will no longer be used for any new links, and links pointing to it
 * will be migrated to another copy of the data (see <code>link#</code>).  If
 * there is no other copy of the link, then the client will be asked to re-upload
 * the chunk.  During restore, an attempt will be made to locate an alternate
 * copy of the chunk.  Once all links are migrated, this corrupt chunk will be
 * deleted as normal when link count reaches one.
 * </p>
 * <p>
 * Both <code>collision#</code> and <code>link#</code> are maintained in sequential
 * order starting at <code>0</code>.  The system renumbers files as-needed as
 * things are removed in order to maintain no gaps in the sequence.  During routine
 * operations, searches are done one-past the end to detect and correct any gaps
 * in the sequence caused by any unclean shutdowns.
 * </p>
 * <p>
 * Files are normally removed from the index immediately as they are removed from
 * the backup directory trees.  However, in the event of an unclean shutdown or
 * manual administrative action, there may be orphaned index files (with a link
 * count of 1).  A cleanup job is ran at startup as well as once per day to find
 * and delete any orphaned index files.  This cleanup job can also be
 * accomplished manually on the shell:
 * </p>
 * <pre>
 * /etc/init.d/aoserv-daemon stop
 * find (/backup-partition)/DATA-INDEX -mindepth 2 -maxdepth 2 -type f -links 1 -print # -delete
 * find (/backup-partition)/DATA-INDEX -mindepth 1 -maxdepth 1 -type d -empty -print # -delete
 * # Note: -delete commented for safety, uncomment to actually delete the orphans.
 * /etc/init.d/aoserv-daemon start
 * </pre>
 * <p>
 * The backup process recreates missing index files from existing hard linked chunks,
 * so the entire index may be discarded and it will be recreated with minimal loss
 * of drive space.  Some links might not be created from new data to old (if not
 * yet put back in the index), but the system will still function and eventually
 * settle to an optimal state once again as backup directories are recycled.
 * </p>
 * <p>
 * The background verifier uses the chunk's modified time to keep track of the
 * last time the chunk was verified.  The chunk will be re-verified
 * approximately once every <code>VERIFICATION_INTERVAL</code> milliseconds.
 * </p>
 * <p>
 * Security: Client-provided MD5 values must never be trusted for what goes into
 * the index.  They can be used to link to existing data within the client's
 * backup, but anything being added to the index must have server-side MD5
 * computed.
 * </p>
 * <p>
 * Locks are maintained on a per-hash-directory basis, so the I/O can be
 * dispatched with up to 2^16 concurrency.
 * </p>
 *
 * @see  AOServDaemonProtocol#FAILOVER_FILE_REPLICATION_CHUNK_SIZE
 *
 * @author  AO Industries, Inc.
 */
public class DataIndex {

	private static final Logger logger = LogFactory.getLogger(DataIndex.class);

	/**
	 * The maximum link count before creating a new copy of the data.
	 * ext4 has a maximum of 65000, so this leaves some unused link count for
	 * other administrative purposes.
	 */
	private static final int FILESYSTEM_MAX_LINK_COUNT = 60000;

	/**
	 * The page size assumed for the underlying filesystem.  This affects when
	 * gzip compressed may be attempted.
	 */
	private static final int FILESYSTEM_BLOCK_SIZE = 4096;

	/**
	 * The index directory permissions.
	 */
	private static final int DIRECTORY_MODE = 0700;

	/**
	 * The index file permissions.
	 */
	private static final int FILE_MODE = 0600;

	/**
	 * The number of milliseconds between file verifications.
	 */
	private static final long VERIFICATION_INTERVAL = 7L * 24L * 60L * 60L * 1000L; // 7 Days

	/**
	 * The time that orphans will be cleaned.
	 */
	private static final int
		CLEAN_ORPHANS_HOUR = 1,
		CLEAN_ORPHANS_MINUTE = 49
	;

	/**
	 * Only one instance is created per canonical index directory.
	 */
	private static final Map<File,DataIndex> instances = new HashMap<>();

	/**
	 * Gets the index for the given index directory.
	 * Only one instance is created per canonical index directory.
	 */
	public static DataIndex getInstance(File indexDirectory) throws IOException {
		File canonicalDirectory = indexDirectory.getCanonicalFile();
		synchronized(instances) {
			DataIndex instance = instances.get(canonicalDirectory);
			if(instance == null) {
				instance = new DataIndex(canonicalDirectory);
				instances.put(canonicalDirectory, instance);
			}
			return instance;
		}
	}

	private final UnixFile canonicalDirectory;

	/**
	 * Per hash locks (one for each hash sub directory).
	 */
	private final Map<Integer,Object> hashLocks = new HashMap<>();

	/**
	 * Gets the lock for a specific hash directory, never removed once created.
	 */
	private Object getHashLock(Integer hashDir) {
		if(hashDir < 0 || hashDir > 0xffff) throw new IllegalArgumentException("hashDir out of range (0-0xffff): " + hashDir);
		synchronized(hashLocks) {
			Object hashLock = hashLocks.get(hashDir);
			if(hashLock == null) {
				hashLock = new Object() {
					@Override
					public String toString() {
						return
							DataIndex.class.getName()
							+ "("
							+ canonicalDirectory
							+ ").hashLock("
							+ StringUtility.getHexChar(hashDir >>> 12)
							+ StringUtility.getHexChar(hashDir >>> 8)
							+ StringUtility.getHexChar(hashDir >>> 4)
							+ StringUtility.getHexChar(hashDir)
							+ ")"
						;
					}
				};
				hashLocks.put(hashDir, hashLock);
			}
			return hashLock;
		}
	}

	private DataIndex(File canonicalDirectory) throws IOException {
		this.canonicalDirectory = new UnixFile(canonicalDirectory);

		// Create the index directory if missing
		Stat stat = this.canonicalDirectory.getStat();
		if(!stat.exists()) {
			this.canonicalDirectory.mkdir(false, DIRECTORY_MODE);
		} else if(!stat.isDirectory()) {
			throw new IOException("Not a directory: " + this.canonicalDirectory);
		}

		/**
		 * Add the CronJob that cleans orphaned data in the background.
		 */
		CronJob cleanupJob = new CronJob() {
			@Override
			public Schedule getCronJobSchedule() {
				return
					(int minute, int hour, int dayOfMonth, int month, int dayOfWeek, int year)
					-> minute==CLEAN_ORPHANS_MINUTE && hour==CLEAN_ORPHANS_HOUR
				;
			}
			@Override
			public CronJobScheduleMode getCronJobScheduleMode() {
				return CronJobScheduleMode.SKIP;
			}
			@Override
			public String getCronJobName() {
				return DataIndex.class.getName()+".cleanOrphans()";
			}
			@Override
			public int getCronJobThreadPriority() {
				return Thread.NORM_PRIORITY;
			}
			@Override
			public void runCronJob(int minute, int hour, int dayOfMonth, int month, int dayOfWeek, int year) {
				try {
					cleanOrphans();
				} catch(IOException e) {
					logger.log(Level.SEVERE, "clean orphans failed", e);
				}
			}
		};
		CronDaemon.addCronJob(cleanupJob, logger);
		// Clean once on startup
		CronDaemon.runImmediately(cleanupJob);
	}

	/**
	 * The directory containing this index.
	 */
	public File getCanonicalDirectory() {
		return canonicalDirectory.getFile();
	}

	/**
	 * Parses a hash directory name.
	 */
	private static int parseHashDir(String hex) throws NumberFormatException {
		if(hex.length() != 4) throw new NumberFormatException("Hash directory must be four characters long: " + hex);
		return
			  (StringUtility.getHex(hex.charAt(0)) << 12)
			| (StringUtility.getHex(hex.charAt(1)) << 8)
			| (StringUtility.getHex(hex.charAt(2)) << 4)
			|  StringUtility.getHex(hex.charAt(3))
		;
	}

	/**
	 * Cleans all orphaned index files.  The lock is only held briefly one file
	 * at a time, so other I/O can be interleaved with this cleanup process.
	 * It is possible that new orphans created during the cleanup will not be
	 * cleaned-up on this pass.
	 */
	public void cleanOrphans() throws IOException {
		String[] hashDirs = canonicalDirectory.list();
		if(hashDirs != null) {
			for(String hashDir : hashDirs) {
				try {
					final Object hashDirLock = getHashLock(parseHashDir(hashDir));
					final UnixFile hashDirUF = new UnixFile(canonicalDirectory, hashDir, false);
					String[] list;
					synchronized(hashDirLock) {
						list = hashDirUF.list();
					}
					if(list != null) {
						boolean hasKeptFile = false;
						final Stat stat = new Stat();
						for(String filename : list) {
							UnixFile uf = new UnixFile(hashDirUF, filename, false);
							synchronized(hashDirLock) {
								uf.getStat(stat);
								// Must still exist
								if(stat.exists()) {
									if(
										// Must be a regular file
										stat.isRegularFile()
										// Must have a link count of one
										&& stat.getNumberLinks() == 1
									) {
										logger.log(Level.WARNING, "Removing orphan: " + uf);
										uf.delete();
										// TODO: Renumber any files after this one by both collision# and link#
									} else {
										hasKeptFile = true;
									}
								}
							}
							// We'll play extra nice by letting others grab the lock before
							// going on to the next file.
							Thread.yield();
						}
						list = null; // Done with this potentially long array

						// Remove the hash directory itself if now empty
						if(!hasKeptFile) {
							boolean logSkippedNonDirectory = false;
							synchronized(hashDirLock) {
								hashDirUF.getStat(stat);
								if(stat.exists()) {
									if(stat.isDirectory()) {
										list = hashDirUF.list();
										if(list==null || list.length == 0) {
											logger.log(Level.WARNING, "Removing empty hash directory: " + hashDirUF);
											hashDirUF.delete();
										}
									} else {
										logSkippedNonDirectory = true;
									}
								}
							}
							if(logSkippedNonDirectory) {
								logger.log(Level.WARNING, "Skipping non-directory: " + hashDir);
							}
						}
					}
				} catch(NumberFormatException e) {
					logger.log(Level.WARNING, "Skipping non-hash directory: " + hashDir, e);
				}
			}
		}
	}
}
