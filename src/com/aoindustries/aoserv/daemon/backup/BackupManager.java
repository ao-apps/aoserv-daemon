package com.aoindustries.aoserv.daemon.backup;

/*
 * Copyright 2001-2006 by AO Industries, Inc.,
 * 816 Azalea Rd, Mobile, Alabama, 36693, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.aoserv.client.*;
import com.aoindustries.aoserv.daemon.*;
import com.aoindustries.aoserv.daemon.client.AOServDaemonProtocol;
import com.aoindustries.email.*;
import com.aoindustries.io.*;
import com.aoindustries.io.unix.*;
import com.aoindustries.md5.*;
import com.aoindustries.profiler.*;
import com.aoindustries.sql.*;
import com.aoindustries.util.*;
import com.aoindustries.util.sort.*;
import com.aoindustries.util.zip.*;
import com.oreilly.servlet.*;
import java.io.*;
import java.sql.*;
import java.util.*;

/**
 * The BackupManager handles the storing and retrieval of backup data from
 * the backup partitions.  It also keeps the <code>/var/oldaccounts</code>
 * directory clean.
 *
 * @author  AO Industries, Inc.
 */
final public class BackupManager {

    /**
     * The directory that old files are backed-up to.  This thread cleans up
     * the old backups.
     */
    private static final File oldaccountsDir=new File("/var/oldaccounts");

    /**
     * The maximum age of files in the /var/oldaccounts directory.
     */
    private static final long MAX_OLDACCOUNTS_AGE=7L*24*60*60*1000;

    /**
     * The maximum time that the backup thread will sleep between backup files or databases.
     */
    private static final long MAX_SLEEP_TIME=5L*60*1000;

    private BackupManager() {}

    /**
     * Makes a tarball of the provided files into the provided file.
     */
    public static void backupFiles(List<File> files, File backupFile) throws IOException {
        Profiler.startProfile(Profiler.UNKNOWN, BackupManager.class, "backupFiles(List<File>,File)", null);
        try {
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
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    static void backupInterBaseDatabases() throws IOException, SQLException {
        Profiler.startProfile(Profiler.SLOW, BackupManager.class, "backupInterBaseDatabases()", null);
        try {
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
                    timer.start();
                    try {
                        for(InterBaseDatabase id : AOServDaemon.getThisAOServer().getInterBaseDatabases()) {
                            if(id.getBackupLevel().getLevel()>0) {
                                long startTime=System.currentTimeMillis();
                                try {
                                    id.backup();
                                } catch(RuntimeException err) {
                                    AOServDaemon.reportError(
                                        err,
                                        new Object[] {"id="+id}
                                    );
                                }
                            }
                        }
                    } catch(ThreadDeath TD) {
                        throw TD;
                    } catch(Throwable T) {
                        AOServDaemon.reportError(T, null);
                    }
                } finally {
                    timer.stop();
                }
            }
        } finally {
            Profiler.endProfile(Profiler.SLOW);
        }
    }

    static void backupMySQLDatabases() throws IOException, SQLException {
        Profiler.startProfile(Profiler.SLOW, BackupManager.class, "backupMySQLDatabases()", null);
        try {
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
                timer.start();
                try {
                    for(MySQLServer ms : AOServDaemon.getThisAOServer().getMySQLServers()) {
                        for(MySQLDatabase md : ms.getMySQLDatabases()) {
                            if(md.getBackupLevel().getLevel()>0) {
                                long startTime=System.currentTimeMillis();
                                try {
                                    md.backup();
                                } catch(RuntimeException err) {
                                    AOServDaemon.reportError(
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
                    AOServDaemon.reportError(T, null);
                }
            } finally {
                timer.stop();
            }
        } finally {
            Profiler.endProfile(Profiler.SLOW);
        }
    }
    
    static void backupPostgresDatabases() throws IOException, SQLException {
        Profiler.startProfile(Profiler.SLOW, BackupManager.class, "backupPostgresDatabases()", null);
        try {
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
                timer.start();
                try {
                    for(PostgresServer ps : AOServDaemon.getThisAOServer().getPostgresServers()) {
                        for(PostgresDatabase pd : ps.getPostgresDatabases()) {
                            if(pd.allowsConnections() && pd.getBackupLevel().getLevel()>0) {
                                long startTime=System.currentTimeMillis();
                                try {
                                    pd.backup();
                                } catch(RuntimeException err) {
                                    AOServDaemon.reportError(
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
                    AOServDaemon.reportError(T, null);
                }
            } finally {
                timer.stop();
            }
        } finally {
            Profiler.endProfile(Profiler.SLOW);
        }
    }

    static void cleanVarOldaccounts() {
        Profiler.startProfile(Profiler.UNKNOWN, BackupManager.class, "cleanVarOldaccounts()", null);
        try {
            try {
                String[] files=oldaccountsDir.list();
                if(files!=null) {
                    int len=files.length;
                    for(int c=0;c<len;c++) {
                        String filename=files[c];
                        // Construct the Calendar from the filename
                        Calendar fileCal=Calendar.getInstance();
                        fileCal.set(Calendar.YEAR, Integer.parseInt(filename.substring(0,4)));
                        fileCal.set(Calendar.MONTH, Integer.parseInt(filename.substring(4,6))-1);
                        fileCal.set(Calendar.DAY_OF_MONTH, Integer.parseInt(filename.substring(6,8)));
                        fileCal.set(Calendar.HOUR_OF_DAY, Integer.parseInt(filename.substring(9,11)));
                        fileCal.set(Calendar.MINUTE, Integer.parseInt(filename.substring(11,13)));
                        fileCal.set(Calendar.SECOND, Integer.parseInt(filename.substring(13,15)));

                        if((System.currentTimeMillis()-fileCal.getTime().getTime())>=MAX_OLDACCOUNTS_AGE) {
                            File file=new File(oldaccountsDir, filename);
                            if(!file.delete()) throw new IOException("Unable to delete file: "+file.getPath());
                        }
                    }
                }
            } catch(ThreadDeath TD) {
                throw TD;
            } catch(Throwable T) {
                AOServDaemon.reportError(T, null);
            }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    public static void getBackupData(String pathPrefix, CompressedDataOutputStream masterOut, long skipBytes) throws IOException, SQLException {
        Profiler.startProfile(Profiler.IO, BackupManager.class, "getBackupData(String,CompressedDataOutputStream,long)", null);
        try {
            File file=new File(pathPrefix);
            File directory=file.getParentFile();
            String filenamePrefix=file.getName();
            
            // Find the file
            String[] list=directory.list();
            if(list==null) throw new IOException("Listing of directory was null: "+directory.getPath());

	    // First, look for the file with the _1 added
	    boolean foundUnder=false;
	    String underPrefix=filenamePrefix+"1_";
	    for(int c=0;c<list.length;c++) {
		if(list[c].startsWith(underPrefix)) {
		    foundUnder=true;
		    break;
		}
	    }

	    File[] files;
	    if(foundUnder) {
		List<File> actualFiles=new ArrayList<File>();
		for(int c=0;c<list.length;c++) {
		    String filename=list[c];
		    if(filename.startsWith(filenamePrefix)) {
			actualFiles.add(new File(directory, filename));
		    }
		}
		if(actualFiles.size()==0) throw new FileNotFoundException("Unable to find file for prefix: "+pathPrefix);
            
		// Turn the actual files into an array for reading purposes
		files=new File[actualFiles.size()];
		for(int c=files.length-1;c>=0;c--) {
		    int num=c+1;
		    String desiredPrefix=filenamePrefix+num+"_";
		    String alternatePrefix=c==0?filenamePrefix:null;
		    File foundFile=null;
		    for(int d=0;d<actualFiles.size();d++) {
			File tempFile=actualFiles.get(d);
			String filename=tempFile.getName();
			if(
			   filename.startsWith(desiredPrefix)
			   || (alternatePrefix!=null && filename.startsWith(alternatePrefix))
			   ) {
			    foundFile=tempFile;
			    actualFiles.remove(d);
			    break;
			}
		    }
		    if(foundFile==null) throw new IOException("Unable to find file for prefix="+desiredPrefix);
		    files[c]=foundFile;
		}
	    } else {
		File foundFile=null;
		for(int c=0;c<list.length;c++) {
		    if(list[c].startsWith(filenamePrefix)) {
			foundFile=new File(directory, list[c]);
			break;
		    }
		}
		if(foundFile==null) throw new IOException("Unable to find file for prefix="+filenamePrefix);
		else files=new File[] {foundFile};
	    }

            // Return the file contents
            MultiFileInputStream mufIn=new MultiFileInputStream(files);
            try {
                long skipLeft=skipBytes;
                long skipped;
                while(skipLeft>0 && (skipped=mufIn.skip(skipLeft))!=-1) {
                    skipLeft-=skipped;
                }
                if(skipLeft>0) throw new IOException("Unable to skip the requested number of bytes, requested "+skipBytes+", "+skipLeft+" remaining.");
                masterOut.writeLong(mufIn.length());

                byte[] buff=BufferManager.getBytes();
                try {
                    int ret;
                    while((ret=mufIn.read(buff, 0, BufferManager.BUFFER_SIZE))!=-1) {
                        masterOut.writeByte(AOServDaemonProtocol.NEXT);
                        masterOut.writeShort(ret);
                        masterOut.write(buff, 0, ret);
                    }
                } finally {
                    BufferManager.release(buff);
                }
            } finally {
                mufIn.close();
            }
        } finally {
            Profiler.endProfile(Profiler.IO);
        }
    }

    /**
     * The full path to the df command.
     */
    public static final String DF="/bin/df";
    private static final String[] dfCommand={
        DF,
        "-k"
    };

    public static long getDiskDeviceTotalSize(String device) throws IOException {
        Profiler.startProfile(Profiler.INSTANTANEOUS, BackupManager.class, "getDiskDeviceTotalSize(String)", null);
        try {
            return getDFColumn(device, 1);
        } finally {
            Profiler.endProfile(Profiler.INSTANTANEOUS);
        }
    }
    
    public static long getDiskDeviceUsedSize(String device) throws IOException {
        Profiler.startProfile(Profiler.INSTANTANEOUS, BackupManager.class, "getDiskDeviceUsedSize(String)", null);
        try {
            return getDFColumn(device, 2);
        } finally {
            Profiler.endProfile(Profiler.INSTANTANEOUS);
        }
    }

    private static long getDFColumn(String device, int column) throws IOException {
        Profiler.startProfile(Profiler.UNKNOWN, BackupManager.class, "getDFColumn(String,int)", null);
        try {
            Process P=Runtime.getRuntime().exec(dfCommand);
            boolean found=false;
            long size=0;
            BufferedReader in=new BufferedReader(new InputStreamReader(P.getInputStream()));
	    try {
		String line=in.readLine();
		while((line=in.readLine())!=null) {
		    if(!found) {
			String[] columns=StringUtility.splitString(line);
			if(columns[0].equals(device)) {
			    found=true;
			    size=1024*Long.parseLong(columns[column]);
			}
		    }
		}
	    } finally {
		in.close();
	    }
            try {
                int retCode=P.waitFor();
                if(retCode!=0) throw new IOException(DF+" exited with non-zero return status: "+retCode);
            } catch(InterruptedException err) {
                throw new InterruptedIOException(err.toString());
            }
            if(!found) throw new IOException("Device "+device+" not found using "+DF);
            return size;
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    /**
     * Gets the next backup filename in the <code>/var/oldaccounts</code> directory.
     * The filename format is <code>YYYYMMDD_HHMMSS_C.tgz</code> where <code>C</code> a counter
     * starting at one.
     */
    public static File getNextBackupFile() throws IOException {
        Profiler.startProfile(Profiler.UNKNOWN, BackupManager.class, "getNextBackupFile()", null);
        try {
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

		String prefix=SB.toString();
		for(int c=1;c<Integer.MAX_VALUE;c++) {
		    File file=new File(oldaccountsDir, prefix+c+".tgz");
		    if(!file.exists()) {
			new FileOutputStream(file).close();
			return file;
		    }
		}
		throw new IOException("Unable to allocate backup file for "+oldaccountsDir.getPath()+'/'+prefix);
	    }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    /**
     * Write access to backup_partitions is locked on the BackupPartition level.  Read
     * access is always allowed.
     */
    private static final Map<BackupPartition,Object> backupPartitionLocks=new HashMap<BackupPartition,Object>();
    public static Object getBackupPartitionLock(BackupPartition backupPartition) {
        Profiler.startProfile(Profiler.FAST, BackupManager.class, "getBackupPartitionLock(BackupPartition)", null);
        try {
            if(backupPartition==null) throw new NullPointerException("backupPartition is null");
            synchronized(backupPartitionLocks) {
                Object O=backupPartitionLocks.get(backupPartition);
                if(O==null) backupPartitionLocks.put(backupPartition, O=new Object());
                return O;
            }
        } finally {
            Profiler.endProfile(Profiler.FAST);
        }
    }

    public static void removeBackupData(BackupPartition backupPartition, String relativePath) throws IOException, SQLException {
        Profiler.startProfile(Profiler.IO, BackupManager.class, "removeBackupData(BackupPartition,String)", null);
        try {
            // Trim out just the pkey
            int pos=relativePath.indexOf('_');
            if(pos>0) relativePath=relativePath.substring(0, pos+1);
            else relativePath=relativePath+'_';
            synchronized(getBackupPartitionLock(backupPartition)) {
                // Find the actual file
                File file=new File(backupPartition.getPath()+'/'+relativePath);
                File directory=file.getParentFile();
                String filenamePrefix=file.getName();

                // Find the file(s)
                String[] list=directory.list();
                if(list!=null) {
                    List<File> actualFiles=new ArrayList<File>();
                    for(int c=0;c<list.length;c++) {
                        if(list[c].startsWith(filenamePrefix)) {
                            actualFiles.add(new File(directory, list[c]));
                        }
                    }
                    if(actualFiles.size()>0) {
                        File parent=null;
                        for(int c=0;c<actualFiles.size();c++) {
                            File f=actualFiles.get(c);
                            if(parent==null) parent=f.getParentFile();
                            if(f.exists() && !f.delete()) throw new IOException("Unable to delete file: "+f.getPath());
                        }
                    Loop:
                        while(!parent.getCanonicalPath().equals("/")) {
                            // Break if it is one of the partition paths
                            String filename=parent.getCanonicalPath();
                            if(backupPartition.getPath().equals(filename)) break Loop;

                            if(parent.exists()) {
                                // Break if the directory is not empty
                                list=parent.list();
                                if(list!=null && list.length>0) break Loop;

                                // Remove the directory
                                parent.delete();
                            }

                            // Try the parent
                            parent=parent.getParentFile();
                        }
                    }
                }
            }
        } finally {
            Profiler.endProfile(Profiler.IO);
        }
    }

    public static void removeExpiredInterBaseBackups() throws IOException, SQLException {
        Profiler.startProfile(Profiler.INSTANTANEOUS, BackupManager.class, "removeExpiredInterBaseBackups()", null);
        try {
            AOServDaemon.getThisAOServer().removeExpiredInterBaseBackups();
        } finally {
            Profiler.endProfile(Profiler.INSTANTANEOUS);
        }
    }

    public static void removeExpiredMySQLBackups() throws IOException, SQLException {
        Profiler.startProfile(Profiler.INSTANTANEOUS, BackupManager.class, "removeExpiredMySQLBackups()", null);
        try {
            AOServDaemon.getThisAOServer().removeExpiredMySQLBackups();
        } finally {
            Profiler.endProfile(Profiler.INSTANTANEOUS);
        }
    }

    public static void removeExpiredPostgresBackups() throws IOException, SQLException {
        Profiler.startProfile(Profiler.INSTANTANEOUS, BackupManager.class, "removeExpiredPostgresBackups()", null);
        try {
            AOServDaemon.getThisAOServer().removeExpiredPostgresBackups();
        } finally {
            Profiler.endProfile(Profiler.INSTANTANEOUS);
        }
    }

    private static void skipDumpStream(CompressedDataInputStream in) throws IOException, SQLException {
        Profiler.startProfile(Profiler.IO, BackupManager.class, "skipDumpStream(CompressedDataInputStream)", null);
        try {
            int code;
            while ((code = in.readByte()) == AOServDaemonProtocol.NEXT) {
                int len = in.readShort();
                in.skipBytes(len);
            }
            if (code == AOServDaemonProtocol.DONE) return;
            else if (code == AOServDaemonProtocol.IO_EXCEPTION) throw new IOException(in.readUTF());
            else if (code == AOServDaemonProtocol.SQL_EXCEPTION) throw new SQLException(in.readUTF());
            else throw new IOException("Unknown result: " + code);
        } finally {
            Profiler.endProfile(Profiler.IO);
        }
    }

    /**
     * Even if an error occurs, the input stream must be fully read.
     *
     * @return  new Object[] {path, Long.valueOf(compressedSize)}
     */
    public static String storeBackupData(
        BackupPartition backupPartition,
        String relativePath,
        boolean isCompressed,
        CompressedDataInputStream in,
        long[] byteCount
    ) throws IOException, SQLException {
        Profiler.startProfile(Profiler.IO, BackupManager.class, "storeBackupData(BackupPartition,String,boolean,CompressedDataInputStream,long[])", null);
        try {
            synchronized(getBackupPartitionLock(backupPartition)) {
                // Delete any partial/extra/corrupted filename versions
                removeBackupData(backupPartition, relativePath);

                // Make any directories that do not exist
                File file=new File(backupPartition.getPath()+'/'+SQLUtility.encodeString(relativePath));
                File parent=file.getParentFile();
                if(!parent.exists()) new UnixFile(parent).mkdir(true, 0700);

                boolean streamDone=false;
                try {
                    // Verify our copy as we write it
                    MD5 md5 = new MD5();
                    String filename=file.getName();
                    int pos=filename.indexOf('_');
                    if(pos==-1) throw new IOException("Unable to find '_' in relative filename: "+filename);
                    String prefix=filename.substring(0, pos+1);
                    String suffix=filename.substring(pos);
                    MultiFileOutputStream mufOut=new MultiFileOutputStream(parent, prefix, suffix);
                    try {
                        try {
                            int code;
                            byte[] buff=BufferManager.getBytes();
                            try {
                                while ((code = in.readByte()) == AOServDaemonProtocol.NEXT) {
                                    int len = in.readShort();
                                    in.readFully(buff, 0, len);
                                    mufOut.write(buff, 0, len);
                                    if(!isCompressed) md5.Update(buff, 0, len);
                                    if(byteCount!=null) byteCount[0]+=len;
                                }
                            } finally {
                                BufferManager.release(buff);
                            }
                            streamDone=true;
                            if(code!=AOServDaemonProtocol.DONE) {
                                if (code == AOServDaemonProtocol.IO_EXCEPTION) throw new IOException(in.readUTF());
                                else if (code == AOServDaemonProtocol.SQL_EXCEPTION) throw new SQLException(in.readUTF());
                                else throw new IOException("Unknown result: " + code);
                            }
                        } finally {
			    mufOut.flush();
                            mufOut.close();
                        }
                    } finally {
                        File[] files=mufOut.getFiles();
                        for(int c=0;c<files.length;c++) {
                            UnixFile uf=new UnixFile(files[c]);
                            if(uf.exists()) uf.setMode(0600);
                        }
                    }

                    // When compressed, decompress to verify our copy of the file
                    if(isCompressed) {
                        InputStream md5In = new CorrectedGZIPInputStream(new MultiFileInputStream(mufOut.getFiles()));
                        try {
                            byte[] buff = BufferManager.getBytes();
                            try {
                                int ret;
                                while((ret=md5In.read(buff, 0, BufferManager.BUFFER_SIZE))!=-1) md5.Update(buff, 0, ret);
                            } finally {
                                BufferManager.release(buff);
                            }
                        } finally {
                            md5In.close();
                        }
                    }
                    return MD5.asHex(md5.Final());
                } catch(IOException err) {
                    if(!streamDone) {
                        try {
                            skipDumpStream(in);
                        } catch(IOException err2) {
                            AOServDaemon.reportError(err2, null);
                        }
                    }
                    throw err;
                }
            }
        } finally {
            Profiler.endProfile(Profiler.IO);
        }
    }
    
    private static final int CACHED_DIRECTORY_SIZE=64;
    private static final Object cachedDirectoryLock=new Object();
    private static String[] cachedPaths;
    private static String[][] cachedLists;
    public static String[] getCachedDirectory(String path) throws IOException {
        Profiler.startProfile(Profiler.IO, BackupManager.class, "getCachedDirectory(String)", null);
        try {
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
        } finally {
            Profiler.endProfile(Profiler.IO);
        }
    }

    public static void pruneBackupPartitions() throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, BackupManager.class, "pruneBackupPartitions()", null);
        try {
            for(BackupPartition bp : AOServDaemon.getThisAOServer().getBackupPartitions()) pruneBackupPartition(bp);
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    public static void pruneBackupPartition(BackupPartition backupPartition) throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, BackupManager.class, "pruneBackupPartition(BackupPartition)", null);
        try {
            synchronized(getBackupPartitionLock(backupPartition)) {
		IntsAndLongs completeDataAndSizes=backupPartition.getBackupDataPKeys(true);
                BitSet fileFounds=new BitSet(completeDataAndSizes.size());
                IntList mismatchedPKeys=new IntArrayList();
                pruneBackupPartition0(backupPartition, completeDataAndSizes, fileFounds, mismatchedPKeys, new File(backupPartition.getPath()));
                
                int firstMissingIndex=fileFounds.nextSetBit(0);
                if(mismatchedPKeys.size()>0 || firstMissingIndex!=-1) {
                    String smtp=AOServDaemonConfiguration.getErrorSmtpServer();
                    if(smtp!=null && smtp.length()>0) {
                        String from=AOServDaemonConfiguration.getErrorEmailFrom();
                        MailMessage msg=new MailMessage(smtp);
                        msg.from(from);
                        msg.to("aoserv@aoindustries.com");
                        msg.setSubject("Backup data mismatch on "+backupPartition.getAOServer().getServer().getHostname()+':'+backupPartition.getPath());
                        PrintStream email=msg.getPrintStream();
                        if(firstMissingIndex!=-1) {
                            email.println("-- Missing:");
			    int count=0;
                            int size=completeDataAndSizes.size();
                            for(int c=firstMissingIndex;c<size;c++) {
				if(!fileFounds.get(c)) {
				    if(count==0) email.println("begin;");
				    email.print("update backup_data set is_stored=false where pkey=");
                                    email.print(completeDataAndSizes.getInt(c));
                                    email.println(';');
				    count++;
				    if(count==1000) {
					email.println("commit;");
					count=0;
				    }
				}
                            }
			    if(count>0) email.println("commit;");
                        }
                        int size=mismatchedPKeys.size();
                        if(size>0) {
                            if(firstMissingIndex!=-1) email.println();
			    int count=0;
                            email.println("-- Mismatched file sizes:");
                            for(int c=0;c<size;c++) {
				if(count==0) email.println("begin;");
                                email.print("update backup_data set is_stored=false where pkey=");
                                email.print(mismatchedPKeys.getInt(c));
                                email.println(';');
				count++;
				if(count==1000) {
				    email.println("commit;");
				    count=0;
				}
                            }
			    if(count>0) email.println("commit;");
                        }
                        msg.sendAndClose();
                    }
                }
            }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    private static void pruneBackupPartition0(
        BackupPartition backupPartition,
        IntsAndLongs completeDataAndSizes,
        BitSet fileFounds,
        IntList mismatchedPKeys,
        File directory
    ) throws IOException {
        Profiler.startProfile(Profiler.UNKNOWN, BackupManager.class, "pruneBackupPartition0(BackupPartition,IntsAndLongs,BitSet,IntList,File)", null);
        try {
            String[] list=directory.list();
            if(list!=null) {
                AutoSort.sortStatic(list);

                // Keep track of the total file size for each index
                Map<Integer,Long> totalSizes=new HashMap<Integer,Long>();

                int len=list.length;
                for(int c=0;c<len;c++) {
                    String filename=list[c];
                    File file=new File(directory, filename);
                    if(file.isDirectory()) {
                        pruneBackupPartition0(
                            backupPartition,
                            completeDataAndSizes,
                            fileFounds,
                            mismatchedPKeys,
                            file
                        );
                        String[] dirList=file.list();
                        if(
                            (dirList==null || dirList.length==0)
                            && !file.delete()
                        ) throw new IOException("Unable to delete directory: "+file.getPath());
                    } else {
                        // Parse out the pkey
                        int pos=filename.indexOf('_');
                        if(pos==-1) throw new IOException("Unable to parse for pkey: "+file.getPath());
                        else {
                            int pkey=Integer.parseInt(filename.substring(0, pos));
                            int index=completeDataAndSizes.indexOf(pkey);
                            if(index==-1) {
                                if(!file.delete()) throw new IOException("Unable to delete file: "+file.getPath());
                            } else {
                                fileFounds.set(index);
                                long actualLength=file.length();
                                Integer I=Integer.valueOf(index);
                                Long L=totalSizes.get(I);
                                if(L!=null) actualLength+=L.longValue();
                                totalSizes.put(I, Long.valueOf(actualLength));
                            }
                        }
                    }
                }
                
                Iterator<Integer> indexes=totalSizes.keySet().iterator();
                while(indexes.hasNext()) {
                    Integer I=indexes.next();
                    int index=I.intValue();
                    long totalSize=totalSizes.get(I).longValue();

                    long correctLength=completeDataAndSizes.getLong(index);
                    if(totalSize!=correctLength) mismatchedPKeys.add(completeDataAndSizes.getInt(index));
                }
            }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }
}
