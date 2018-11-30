/*
 * Copyright 2001-2009, 2016, 2017, 2018 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.aoserv.daemon.backup;

import com.aoindustries.aoserv.client.linux.AOServer;
import com.aoindustries.aoserv.client.validator.UnixPath;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import com.aoindustries.aoserv.daemon.LogFactory;
import com.aoindustries.aoserv.daemon.unix.linux.PackageManager;
import com.aoindustries.io.unix.UnixFile;
import com.aoindustries.util.StringUtility;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.InterruptedIOException;
import java.sql.SQLException;
import java.util.Calendar;
import java.util.List;
import java.util.logging.Level;

/**
 * The BackupManager handles the storing and retrieval of backup data from
 * the backup partitions.  It also keeps the <code>/var/opt/aoserv-daemon/oldaccounts</code>
 * directory clean.
 *
 * @author  AO Industries, Inc.
 */
final public class BackupManager {

	/**
	 * The directory that old files are backed-up to.  This thread cleans up
	 * the old backups.
	 */
	private static final String OLDACCOUNTS_DIR = "/var/opt/aoserv-daemon/oldaccounts";

	/**
	 * Gets the oldaccounts directory, creating if necessary.
	 */
	private static UnixFile getOldaccountsDir() throws IOException {
		UnixFile oldaccountsDir = new UnixFile(OLDACCOUNTS_DIR);
		if(!oldaccountsDir.getStat().exists()) oldaccountsDir.mkdir(false, 0700);
		return oldaccountsDir;
	}

	/**
	 * The maximum age of files in the /var/opt/aoserv-daemon/oldaccounts directory.
	 */
	private static final long MAX_OLDACCOUNTS_AGE = 7L*24*60*60*1000; // One week

	private BackupManager() {}

	/**
	 * Backs-up and then deletes the provided files.
	 */
	public static void backupAndDeleteFiles(List<File> deleteFileList) throws IOException, SQLException {
		if(!deleteFileList.isEmpty()) {
			// Get the next backup filename
			File backupFile = getNextTarballBackupFile();
			// Backup
			createTarball(deleteFileList, backupFile);
			// Remove the files that have been backed up.
			AOServer thisAoServer = AOServDaemon.getThisAOServer();
			int uid_min = thisAoServer.getUidMin().getId();
			int gid_min = thisAoServer.getGidMin().getId();
			for(File file : deleteFileList) {
				new UnixFile(file).secureDeleteRecursive(uid_min, gid_min);
			}
		}
	}

	/**
	 * Makes a tarball of the provided files into the provided file.
	 */
	public static void createTarball(List<File> files, File backupFile) throws IOException {
		if(files.isEmpty()) throw new IllegalArgumentException("files.size() = " + files.size());
		PackageManager.installPackages(
			PackageManager.PackageName.TAR,
			PackageManager.PackageName.GZIP
		);
		int len=files.size();
		String[] cmd=new String[len+5];
		cmd[0]="/bin/tar";
		cmd[1]="-C";
		cmd[2]="/";
		cmd[3]="-czf";
		cmd[4]=backupFile.getPath();
		// strips the leading / as it builds the command
		for(int c=0;c<len;c++) cmd[c+5]=files.get(c).getPath().substring(1);

		AOServDaemon.exec(cmd);
	}

	/*
	static void backupInterBaseDatabases() throws IOException, SQLException {
		if(AOServDaemon.getThisAOServer().isInterBase()) {
			ProcessTimer timer=new ProcessTimer(
				AOServDaemon.getRandom(),
				AOServDaemonConfiguration.getWarningSmtpServer(),
				AOServDaemonConfiguration.getWarningEmailFrom(),
				AOServDaemonConfiguration.getWarningEmailTo(),
				"InterBase backup taking too long",
				"InterBase Backup",
				3*60*60*1000,
				60*60*1000
			);
			try {
				AOServDaemon.executorService.submit(timer);
				try {
					for(InterBaseDatabase id : AOServDaemon.getThisAOServer().getInterBaseDatabases()) {
						if(id.getBackupLevel().getLevel()>0) {
							long startTime=System.currentTimeMillis();
							try {
								id.backup();
							} catch(RuntimeException err) {
								LogFactory.getLogger(this.getClass()).log(Level.SEVERE, 
									err,
									new Object[] {"id="+id}
								);
							}
						}
					}
				} catch(ThreadDeath TD) {
					throw TD;
				} catch(Throwable T) {
					LogFactory.getLogger(this.getClass()).log(Level.SEVERE, null, T);
				}
			} finally {
				timer.stop();
			}
		}
	}
*/
	/*
	static void backupMySQLDatabases() throws IOException, SQLException {
		ProcessTimer timer=new ProcessTimer(
			AOServDaemon.getRandom(),
			AOServDaemonConfiguration.getWarningSmtpServer(),
			AOServDaemonConfiguration.getWarningEmailFrom(),
			AOServDaemonConfiguration.getWarningEmailTo(),
			"MySQL backup taking too long",
			"MySQL Backup",
			3*60*60*1000,
			60*60*1000
		);
		try {
			AOServDaemon.executorService.submit(timer);
			try {
				for(MySQLServer ms : AOServDaemon.getThisAOServer().getMySQLServers()) {
					for(MySQLDatabase md : ms.getMySQLDatabases()) {
						if(md.getBackupLevel().getLevel()>0) {
							long startTime=System.currentTimeMillis();
							try {
								md.backup();
							} catch(RuntimeException err) {
								LogFactory.getLogger(this.getClass()).log(Level.SEVERE, 
									err,
									new Object[] {"md="+md}
								);
							}
						}
					}
				}
			} catch(ThreadDeath TD) {
				throw TD;
			} catch(Throwable T) {
				LogFactory.getLogger(this.getClass()).log(Level.SEVERE, null, T);
			}
		} finally {
			timer.stop();
		}
	}
	*/

	/*
	static void backupPostgresDatabases() throws IOException, SQLException {
		ProcessTimer timer=new ProcessTimer(
			AOServDaemon.getRandom(),
			AOServDaemonConfiguration.getWarningSmtpServer(),
			AOServDaemonConfiguration.getWarningEmailFrom(),
			AOServDaemonConfiguration.getWarningEmailTo(),
			"PostgreSQL backup taking too long",
			"PostgreSQL Backup",
			3*60*60*1000,
			60*60*1000
		);
		try {
			AOServDaemon.executorService.submit(timer);
			try {
				for(PostgresServer ps : AOServDaemon.getThisAOServer().getPostgresServers()) {
					for(PostgresDatabase pd : ps.getPostgresDatabases()) {
						if(pd.allowsConnections() && pd.getBackupLevel().getLevel()>0) {
							long startTime=System.currentTimeMillis();
							try {
								pd.backup();
							} catch(RuntimeException err) {
								LogFactory.getLogger(this.getClass()).log(Level.SEVERE, 
									err,
									new Object[] {"pd="+pd}
								);
							}
						}
					}
				}
			} catch(ThreadDeath TD) {
				throw TD;
			} catch(Throwable T) {
				LogFactory.getLogger(this.getClass()).log(Level.SEVERE, null, T);
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
	static void cleanVarOldaccounts() {
		try {
			UnixFile oldaccountsDir = getOldaccountsDir();
			String[] files = oldaccountsDir.list();
			if(files != null) {
				for(String filename : files) {
					// Construct the Calendar from the filename
					// Not y10k compatible ;)
					Calendar fileCal=Calendar.getInstance();
					fileCal.set(Calendar.YEAR, Integer.parseInt(filename.substring(0,4)));
					fileCal.set(Calendar.MONTH, Integer.parseInt(filename.substring(4,6))-1);
					fileCal.set(Calendar.DAY_OF_MONTH, Integer.parseInt(filename.substring(6,8)));
					fileCal.set(Calendar.HOUR_OF_DAY, Integer.parseInt(filename.substring(9,11)));
					fileCal.set(Calendar.MINUTE, Integer.parseInt(filename.substring(11,13)));
					fileCal.set(Calendar.SECOND, Integer.parseInt(filename.substring(13,15)));

					// If more than double the max age, we might have a system time problem or an extended down time
					long age = (System.currentTimeMillis() - fileCal.getTime().getTime());
					if(age >= (MAX_OLDACCOUNTS_AGE * 2)) {
						LogFactory.getLogger(BackupManager.class).warning(
							filename + "\n"
								+ "File date unexpectedly far in the past; refusing to delete.\n"
								+ "This could be due to a system time change or a very long outage.\n"
								+ "Please investigate and removed as-needed."
						);
					} else if(age >= MAX_OLDACCOUNTS_AGE) {
						new UnixFile(oldaccountsDir, filename, true).delete();
					} else if(age < 0) {
						LogFactory.getLogger(BackupManager.class).warning(
							filename + "\n"
								+ "File date is in the future.\n"
								+ "This could be due to a system time change or a clock problem.\n"
								+ "Please investigate and removed as-needed."
						);
					}
				}
			}
		} catch(ThreadDeath TD) {
			throw TD;
		} catch(Throwable T) {
			LogFactory.getLogger(BackupManager.class).log(Level.SEVERE, null, T);
		}
	}

	/**
	 * The full path to the df command.
	 */
	public static final String DF = "/bin/df";

	public static long getDiskDeviceTotalSize(UnixPath path) throws IOException {
		return getDFColumn(path, 1);
	}

	public static long getDiskDeviceUsedSize(UnixPath path) throws IOException {
		return getDFColumn(path, 2);
	}

	private static long getDFColumn(UnixPath path, int column) throws IOException {
		String[] dfCommand={
			DF,
			"-k",
			"-P",
			path.toString()
		};
		long size;
		Process P=Runtime.getRuntime().exec(dfCommand);
		try {
			P.getOutputStream().close();
			try (BufferedReader in = new BufferedReader(new InputStreamReader(P.getInputStream()))) {
				// The first line is the column labels
				String line=in.readLine();
				if(line==null) throw new IOException("EOF when trying to read column labels");
				line=in.readLine();
				if(line==null) throw new IOException("EOF when trying to read values");
				String[] columns=StringUtility.splitString(line);
				size = 1024 * Long.parseLong(columns[column]);
			}
		} finally {
			try {
				int retCode=P.waitFor();
				if(retCode!=0) throw new IOException(DF+" exited with non-zero return status: "+retCode);
			} catch(InterruptedException err) {
				IOException ioErr = new InterruptedIOException();
				ioErr.initCause(err);
				throw ioErr;
			}
		}
		return size;
	}

	/**
	 * Gets the next backup file for ".tgz" extension.
	 *
	 * @see  #getNextBackupFile(java.lang.String)
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
		synchronized(BackupManager.class) {
			Calendar cal=Calendar.getInstance();
			StringBuilder SB=new StringBuilder(11);

			SB.append(cal.get(Calendar.YEAR));

			int month=cal.get(Calendar.MONTH)+1;
			if(month<10) SB.append('0');
			SB.append(month);

			int day=cal.get(Calendar.DAY_OF_MONTH);
			if(day<10) SB.append('0');
			SB.append(day).append('_');

			int hour=cal.get(Calendar.HOUR_OF_DAY);
			if(hour<10) SB.append('0');
			SB.append(hour);

			int minute=cal.get(Calendar.MINUTE);
			if(minute<10) SB.append('0');
			SB.append(minute);

			int second=cal.get(Calendar.SECOND);
			if(second<10) SB.append('0');
			SB.append(second).append('_');

			UnixFile oldaccountsDir = getOldaccountsDir();
			String prefix=SB.toString();
			for(int c=1;c<Integer.MAX_VALUE;c++) {
				UnixFile unixFile=new UnixFile(oldaccountsDir, prefix + c + extension, true);
				if(!unixFile.getStat().exists()) {
					File file = unixFile.getFile();
					new FileOutputStream(file).close();
					unixFile.setMode(0600);
					return file;
				}
			}
			throw new IOException("Unable to allocate backup file for "+oldaccountsDir.getPath()+'/'+prefix + '*' + extension);
		}
	}

	/*
	private static final int CACHED_DIRECTORY_SIZE=64;
	private static final Object cachedDirectoryLock=new Object();
	private static String[] cachedPaths;
	private static String[][] cachedLists;
	public static String[] getCachedDirectory(String path) throws IOException {
		synchronized(cachedDirectoryLock) {
			if(cachedPaths==null) {
				cachedPaths=new String[CACHED_DIRECTORY_SIZE];
				cachedLists=new String[CACHED_DIRECTORY_SIZE][];
			}
			for(int c=0;c<CACHED_DIRECTORY_SIZE;c++) {
				String cpath=cachedPaths[c];
				if(cpath==null) break;
				if(cpath.equals(path)) return cachedLists[c];
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
