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
import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * <p>
 * Each backup partition has a central index of data chunks for files
 * of <code>FAILOVER_FILE_REPLICATION_CHUNK_SIZE</code> or larger.
 * Each chunk is up to <code>FAILOVER_FILE_REPLICATION_CHUNK_SIZE</code>
 * in size and may be reused via hard links in order to achieve space savings.
 * Each chunk is also gzip compressed for additional space savings.  File tails
 * (partial chunks at the end less than <code>FAILOVER_FILE_REPLICATION_CHUNK_SIZE</code>)
 * are also added to the index.
 * </p>
 * <p>
 * The central index is in a flat directory named <code>DATA-INDEX</code>.
 * Because this is a flat directory, a filesystem with support for many files in one
 * directory, such as ext4 or xfs, is required for large backup partitions.
 * </p>
 * <p>
 * Files are named based on the MD5 hash of the uncompressed file contents.  However, due to
 * both MD5 hash collisions and the per-file hard link limits, there may be more
 * than one file per either the unique MD5 hash value or the uncompressed content.
 * The files are named as follows:
 * </p>
 * <pre>(/backup-partition)/DATA-INDEX/(MD5-hash)-(collision#)-(link#).gz</pre>
 * <p>
 * The <code>MD5-hash</code> is the lowercase hex-coded MD5 sum of the
 * uncompressed file contents.
 * </p>
 * <p>
 * The <code>collision#</code> is the unique set of data resulting in this MD5 hash.
 * When locating new data from the index, we do not assume data matches by MD5 alone,
 * the contents will be verified byte-by-byte.  When a second set of content must
 * be added for a given MD5 hash, it will be <code>(MD5-hash)-<em>2</em>-(link#).gz</code>.
 * </p>
 * <p>
 * The <code>link#</code> is a way to workaround the filesystem limits on the number of
 * hard links allowed to one file (65000 for ext4).  Once the first file is "full",
 * a second version of the content is stored.  The second link file will be named
 * <code>(MD5-hash)-(collision#)-<em>2</em>.gz</code>
 * </p>
 * <p>
 * Both <code>collision#</code> and <code>link#</code> are maintained in sequential
 * order starting at <code>1</code>.  The system renumbers files as-needed as
 * things are removed in order to maintain no gaps in the sequence.  During routine
 * operations, searches are done one-past to detect and correct any gaps in the
 * sequence caused by any unclean shutdowns.
 * </p>
 * <p>
 * Files are normally removed from the index immediately as they are removed from
 * the backup directory trees.  However, in the event of an unclean shutdown or
 * manual administrative action, there may be orphaned index files (with a link
 * count of 1).  A cleanup job is ran once per week to find and delete any
 * orphaned index files.  This cleanup job could also be accomplished manually
 * on the shell:
 * </p>
 * <pre>
 * /etc/init.d/aoserv-daemon stop
 * find (/backup-partition)/DATA-INDEX -mindepth 1 -maxdepth 1 -type f -links 1 -print # -delete
 * /etc/init.d/aoserv-daemon start
 * </pre>
 * <p>
 * The backup process will recreate missing index files from existing hard linked files,
 * so the entire index may be discarded and it will be recreated with minimal loss
 * of drive space.  Some links might not be created from new data to old (if not
 * yet put back in the index), but the system will still function and eventually
 * settle to an optimal state once again as backup directories are recycled.
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
	private static final int MAX_LINK_COUNT = 60000;

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

	/** Per-index lock */
	private final Object lock = new Object();

	private DataIndex(File canonicalDirectory) throws IOException {
		this.canonicalDirectory = new UnixFile(canonicalDirectory);

		// Create the index directory if missing
		if(!this.canonicalDirectory.getStat().exists()) {
			this.canonicalDirectory.mkdir(false, 0700);
		}

		/**
		 * Add the CronJob that cleans orphaned data in the background.
		 */
		CronJob cleanupJob = new CronJob() {
			@Override
			public Schedule getCronJobSchedule() {
				return (int minute, int hour, int dayOfMonth, int month, int dayOfWeek, int year) -> minute==49 && hour==1;
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
	 * Cleans all orphaned index files.  The lock is only held for small
	 * sections of the entire list of files, so other I/O can be interleaved
	 * with this cleanup process.  It is possible that new orphans created
	 * during the cleanup will not be cleaned-up on this pass.
	 */
	public void cleanOrphans() throws IOException {
		String[] list;
		synchronized(lock) {
			list = canonicalDirectory.list();
		}
		if(list != null) {
			final Stat stat = new Stat();
			for(String filename : list) {
				UnixFile uf = new UnixFile(canonicalDirectory, filename, false);
				synchronized(lock) {
					uf.getStat(stat);
					if(
						// Must be a regular file
						stat.isRegularFile()
						// Must have a link count of one
						&& stat.getNumberLinks() == 1
					) {
						logger.log(Level.WARNING, "Removing orphan: " + uf);
						uf.delete();
					}
				}
				// We'll play extra nice by letting others grab the lock before
				// going on to the next file.
				Thread.yield();
			}
		}
	}
}
