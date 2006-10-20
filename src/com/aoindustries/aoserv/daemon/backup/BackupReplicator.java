package com.aoindustries.aoserv.daemon.backup;

/*
 * Copyright 2001-2006 by AO Industries, Inc.,
 * 816 Azalea Rd, Mobile, Alabama, 36693, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.aoserv.client.*;
import com.aoindustries.aoserv.daemon.*;
import com.aoindustries.email.*;
import com.aoindustries.io.*;
import com.aoindustries.io.unix.*;
import com.aoindustries.profiler.*;
import com.aoindustries.sql.*;
import com.aoindustries.util.*;
import java.io.*;
import java.sql.*;
import java.util.*;
import java.util.zip.*;

/**
 * The BackupReplicator runs on every the offsite backup server
 * and copies all backup data to a repository directory.
 *
 * @author  AO Industries, Inc.
 */
final public class BackupReplicator implements Runnable {

    private static final int MIN_THREAD_BIT_RATE=8192;

    private static final long REPLICATION_INTERVAL=24*60*60*1000;
    private static final long TASK_DELAY_REMINDER_INTERVAL=24*60*60*1000;
    private static final long MIN_SLEEP_TIME=60*60*1000;
    private static final long ERROR_DELAY=10*1000;

    private static final int BLOCK_SIZE=BufferManager.BUFFER_SIZE*4*8;

    private static Thread thread;

    public static void main(String[] args) {
        int len=args.length;
        if(len==0) {
            start();
        } else {
            if(len==1 && args[0].equals("clean")) {
                try {
                    final ChainWriter out=new ChainWriter(System.out);
                    AOServDaemon.getConnector().backupDatas.addTableLoadListener(
                        new TableLoadListener() {
                            int count=0;
                            int blockCount=0;
                            public Object tableLoadStarted(AOServTable table, Object param) {
                                out.println("Getting list off all backup data...\n");
                                out.flush();
                                return null;
                            }
                            public Object tableRowLoaded(AOServTable table, AOServObject object, int rowNumber, Object param) {
                                count++;
                                blockCount++;
                                if(blockCount>=10000) {
                                    out.print("    ").print(count).println(" loaded");
                                    out.flush();
                                    blockCount=0;
                                }
                                return null;
                            }
                            public Object tableLoadCompleted(AOServTable table, Object param) {
                                out.print("    Loaded total of ").print(count).println(" objects");
                                out.flush();
                                return null;
                            }
                        },
                        null
                    );
                    resetBackupDataList();
                    try {
                        removeExpiredBackupData(out);
                    } finally {
                        out.print("Clearing list of backup data...");
                        out.flush();
                        clearBackupDataList();
                        out.println(" Done");
                        out.flush();
                    }
                } catch(IOException err) {
                    ErrorPrinter.printStackTraces(err);
                    System.exit(2);
                } catch(SQLException err) {
                    ErrorPrinter.printStackTraces(err);
                    System.exit(3);
                }
            } else {
                System.err.println("Usage: "+BackupReplicator.class.getName()+"        - Run the backup replicator\n"
                                 + "       "+BackupReplicator.class.getName()+" clean  - Clean the backup repository and exit\n");
                System.exit(1);
            }
        }
    }

    private static int replicationConcurrency=-1;
    private static int getReplicationConcurrency() throws IOException {
        Profiler.startProfile(Profiler.FAST, BackupReplicator.class, "getReplicationConcurrency()", null);
        try {
            if(replicationConcurrency==-1) {
                synchronized(BackupReplicator.class) {
                    replicationConcurrency=AOServDaemonConfiguration.getBackupReplicatorConcurrency();
                }
            }
            return replicationConcurrency;
        } finally {
            Profiler.endProfile(Profiler.FAST);
        }
    }

    private static String replicationPath;
    private static String getReplicationPath() throws IOException {
        Profiler.startProfile(Profiler.FAST, BackupReplicator.class, "getReplicationPath()", null);
        try {
            if(replicationPath==null) {
                synchronized(BackupReplicator.class) {
                    replicationPath=AOServDaemonConfiguration.getBackupReplicatorPath();
                }
            }
            return replicationPath;
        } finally {
            Profiler.endProfile(Profiler.FAST);
        }
    }

    private static IntsAndLongs completeDataAndSizeList;
    private static int replicateIndex=0;
    
    private static void clearBackupDataList() {
        Profiler.startProfile(Profiler.FAST, BackupReplicator.class, "clearBackupDataList()", null);
        try {
	    synchronized(BackupReplicator.class) {
		completeDataAndSizeList=null;
		replicateIndex=0;
	    }
        } finally {
            Profiler.endProfile(Profiler.FAST);
        }
    }

    private static void resetBackupDataList() throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, BackupReplicator.class, "resetBackupDataList()", null);
        try {
	    synchronized(BackupReplicator.class) {
                AOServConnector conn=AOServDaemon.getConnector();
                BackupLevel minBackupLevel=conn.backupLevels.get((short)2);
		completeDataAndSizeList=conn.backupDatas.getBackupDataPKeys(true, minBackupLevel);
		replicateIndex=completeDataAndSizeList.size();
	    }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    private static int getNextBackupDataPKey() {
        Profiler.startProfile(Profiler.FAST, BackupReplicator.class, "getNextBackupDataPKey()", null);
        try {
	    synchronized(BackupReplicator.class) {
		if(
		   completeDataAndSizeList!=null
		   && replicateIndex>0
		   ) return completeDataAndSizeList.getInt(--replicateIndex);
		return -1;
	    }
        } finally {
            Profiler.endProfile(Profiler.FAST);
        }
    }

    private static long getFileSize(int pkey) throws SQLException {
        Profiler.startProfile(Profiler.FAST, BackupReplicator.class, "getNextBackupDataPKey()", null);
        try {
	    synchronized(BackupReplicator.class) {
                if(completeDataAndSizeList==null) throw new SQLException("Data not loaded.");
                int index=completeDataAndSizeList.indexOf(pkey);
                if(index==-1) throw new SQLException("Unable to find pkey="+pkey+" in completeDataAndSizeList.");
                return completeDataAndSizeList.getLong(index);
            }
        } finally {
            Profiler.endProfile(Profiler.FAST);
        }
    }

    private static boolean hasMoreBackupData() {
        Profiler.startProfile(Profiler.FAST, BackupReplicator.class, "hasMoreBackupData()", null);
        try {
	    synchronized(BackupReplicator.class) {
		return
		    completeDataAndSizeList!=null
		    && replicateIndex>0
		;
	    }
        } finally {
            Profiler.endProfile(Profiler.FAST);
        }
    }

    private static void removeExpiredBackupData(ChainWriter out) throws IOException {
        Profiler.startProfile(Profiler.FAST, BackupReplicator.class, "removeExpiredBackupData(ChainWriter)", null);
        try {
	    synchronized(BackupReplicator.class) {
		if(completeDataAndSizeList!=null) {
		    removeExpiredBackupData0(completeDataAndSizeList, new File(getReplicationPath()), out);
		}
	    }
        } finally {
            Profiler.endProfile(Profiler.FAST);
        }
    }
    private static void removeExpiredBackupData0(
        IntsAndLongs existingPKeys,
        File directory,
        ChainWriter out
    ) throws IOException {
        Profiler.startProfile(Profiler.UNKNOWN, BackupReplicator.class, "removeExpiredBackupData0(IntsAndLongs,File,ChainWriter)", null);
        try {
            String[] list=directory.list();
            if(list!=null) {
                int len=list.length;
                for(int c=0;c<len;c++) {
                    String filename=list[c];
                    File file=new File(directory, filename);
                    if(file.isDirectory()) {
                        removeExpiredBackupData0(
                            existingPKeys,
                            file,
                            out
                        );
                        String[] dirList=file.list();
                        if(dirList==null || dirList.length==0) {
                            if(out!=null) {
                                out.print("Removing directory: ").println(file.getPath());
                                out.flush();
                            }
                            if(!file.delete()) throw new IOException("Unable to delete directory: "+file.getPath());
                        }
                    } else {
                        // Parse out the pkey
                        int pos=filename.indexOf('_');
                        if(pos==-1) throw new IOException("Unable to parse for pkey: "+file.getPath());
                        else {
                            int pkey=Integer.parseInt(filename.substring(0, pos));
                            if(!existingPKeys.contains(pkey)) {
                                long fileLen;
                                if(out!=null) {
                                    out.print("Deleting file: ").print(file.getPath()).print(" (").print(fileLen=file.length()).print(' ').print(fileLen==1?"byte":"bytes").println(')');
                                    out.flush();
                                }
                                if(!file.delete()) throw new IOException("Unable to delete file: "+file.getPath());
                            }
                        }
                    }
                }
            }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    private static File getBackupDataFile(int pkey) throws IOException {
        Profiler.startProfile(Profiler.UNKNOWN, BackupReplicator.class, "getBackupDataFile(int)", null);
        try {
            // The path strategy is much like the onsite backup partitions.
            StringBuilder path=new StringBuilder();
            path.append(getReplicationPath()).append('/');
            int firstLevel=(pkey/1000000)%100;
            if(firstLevel!=0) {
                if(firstLevel<10) path.append('0');
                path.append(firstLevel).append('/');
            }
            int secondLevel=(pkey/10000)%100;
            if(firstLevel!=0 || secondLevel!=0) {
                if(secondLevel<10) path.append('0');
                path.append(secondLevel).append('/');
            }
            int thirdLevel=(pkey/100)%100;
            if(thirdLevel<10) path.append('0');
            path.append(thirdLevel);

            // Look for the pkey in this directory
            String prefix=pkey+"_";
            int prefixLength=prefix.length();
            String pathString=path.toString();
            String[] list=BackupManager.getCachedDirectory(pathString);
            if(list!=null) {
                int len=list.length;
                for(int c=0;c<len;c++) {
                    String filename=list[c];
                    if(
                        filename.length()>=prefixLength
                        && filename.substring(0, prefixLength).equals(prefix)
                    ) return new File(pathString, filename);
                }
            }
            return null;
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    public static void start() {
        Profiler.startProfile(Profiler.UNKNOWN, BackupReplicator.class, "start()", null);
        try {
            if(thread==null) {
                synchronized(System.out) {
                    if(thread==null) {
                        System.out.print("Starting BackupReplicator: ");
                        (thread=new Thread(new BackupReplicator(), "BackupReplicator")).start();
                        System.out.println("Done");
                    }
                }
            }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    private static int gettingDataCount;
    private static final Object gettingDataLock=new Object();
    private static void incGettingData() {
        synchronized(gettingDataLock) {
            gettingDataCount++;
        }
    }

    private static void decGettingData() {
        synchronized(gettingDataLock) {
            gettingDataCount--;
        }
    }

    public void run() {
        Profiler.startProfile(Profiler.UNKNOWN, BackupReplicator.class, "run()", null);
        try {
            while(true) {
                try {
                    while(true) {
                        ProcessTimer timer=new ProcessTimer(
                            AOServDaemon.getRandom(),
                            AOServDaemonConfiguration.getWarningSmtpServer(),
                            AOServDaemonConfiguration.getWarningEmailFrom(),
                            AOServDaemonConfiguration.getWarningEmailTo(),
                            "Backup Replication Taking Too Long",
                            "Backup Replicator",
                            REPLICATION_INTERVAL,
                            TASK_DELAY_REMINDER_INTERVAL
                        );
                        try {
                            timer.start();

                            long startTime=System.currentTimeMillis();

                            // Get the list of existing backup_data
                            resetBackupDataList();
                            try {
                                // Spawn worker threads, which will stop themselves when all data has been transferred
                                int replicationConcurrency=getReplicationConcurrency();
                                gettingDataCount=0;
                                for(int c=0;c<replicationConcurrency;c++) {
                                    new BackupReplicatorThread().start();
                                }

                                // Wait until all processing is done
                                while(hasMoreBackupData() || gettingDataCount>0) {
                                    try {
                                        Thread.sleep(MIN_SLEEP_TIME);
                                    } catch(InterruptedException err) {
                                        AOServDaemon.reportWarning(err, null);
                                    }
                                }

                                // Remove those files that should no longer exist
                                removeExpiredBackupData(null);

                            } finally {
                                // Free the RAM used
                                clearBackupDataList();
                            }

                            // Sleep until the completion of REPLICATION_INTERVAL
                            long sleepTime=startTime+REPLICATION_INTERVAL-System.currentTimeMillis();
                            if(sleepTime<MIN_SLEEP_TIME) sleepTime=MIN_SLEEP_TIME;
                            else if(sleepTime>REPLICATION_INTERVAL) sleepTime=REPLICATION_INTERVAL;
                            try {
                                Thread.sleep(sleepTime);
                            } catch(InterruptedException err) {
                                AOServDaemon.reportWarning(err, null);
                            }
                        } finally {
                            timer.stop();
                        }
                    }
                } catch(ThreadDeath TD) {
                    throw TD;
                } catch(Throwable T) {
                    AOServDaemon.reportError(T, null);
                    try {
                        Thread.sleep(ERROR_DELAY);
                    } catch(InterruptedException err) {
                        AOServDaemon.reportWarning(err, null);
                    }
                }
            }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    private static final Calendar cal=Calendar.getInstance();
    private static int getCurrentBitRate() {
        Profiler.startProfile(Profiler.FAST, BackupReplicator.class, "getCurrentBitRate()", null);
        try {
            int hour;
            synchronized(cal) {
                cal.setTimeInMillis(System.currentTimeMillis());
                hour=cal.get(Calendar.HOUR_OF_DAY);
            }
            if(hour>=8 && hour<23) return 100000;
            else return 2000000;
            /*
            synchronized(cal) {
                cal.setTimeInMillis(System.currentTimeMillis());
                int dayOfWeek=cal.get(Calendar.DAY_OF_WEEK);
                int hour=cal.get(Calendar.HOUR_OF_DAY);
                if(
                    dayOfWeek!=Calendar.SATURDAY
                    && dayOfWeek!=Calendar.SUNDAY
                    && hour>=2
                    && hour<=4
                ) return 100000;
                switch(hour) {
                    case 0:
                    case 1:
                        return 600000;
                    case 2:
                    case 3:
                    case 4:
                        return (dayOfWeek==Calendar.SATURDAY || dayOfWeek==Calendar.SUNDAY) ? 600000 : 100000;
                    case 5:
                    case 6:
                    case 7:
                        return 600000;
                    case 8:
                        return 500000;
                    case 9:
                    case 10:
                    case 11:
                    case 12:
                    case 13:
                    case 14:
                    case 15:
                    case 16:
                    case 17:
                    case 18:
                    case 19:
                        return 400000;
                    case 20:
                        return 500000;
                    case 21:
                    case 22:
                    case 23:
                        return 600000;
                    default:
                        throw new RuntimeException("Invalid hour: "+hour);
                }
            }
             */
        } finally {
            Profiler.endProfile(Profiler.FAST);
        }
    }

    private static int getSingleThreadBitRate() {
        int threads=gettingDataCount;
        if(threads<1) threads=1;
        int bitRate=getCurrentBitRate()/threads;
        if(bitRate<MIN_THREAD_BIT_RATE) bitRate=MIN_THREAD_BIT_RATE;
        return bitRate;
    }

    static class BackupReplicatorThread extends Thread implements BitRateProvider {
        public void run() {
            Profiler.startProfile(Profiler.UNKNOWN, BackupReplicatorThread.class, "run()", null);
            try {
                int pkey;
                while((pkey=getNextBackupDataPKey())!=-1) {
                    try {
                        long correctSize=getFileSize(pkey);
                        File backupDataFile=getBackupDataFile(pkey);
                        if(backupDataFile==null) {
                            BackupData bd=AOServDaemon.getConnector().backupDatas.get(pkey);
                            // The data could have been deleted when offsite backup lagging behind
                            if(bd!=null) {
                                boolean isStored=bd.isStored();
                                if(isStored) {
                                    StringBuilder SB=new StringBuilder();
                                    SB.append(BackupData.getPathPrefix(getReplicationPath(), pkey)).append(SQLUtility.encodeString(bd.getFilename()));
                                    if(bd.getCompressedSize()!=-1) SB.append(".gz");
                                    String path=SB.toString();

                                    boolean isDone=false;
                                    File file=new File(path);
                                    UnixFile uf=new UnixFile(path);
                                    UnixFile parent=uf.getParent();
                                    if(!parent.getStat().exists()) {
                                        try {
                                            parent.mkdir(true, 0700);
                                        } catch(IOException err) {
                                            // Normal when two threads try to create the directory at once
                                        }
                                    }
                                    FileOutputStream out=new FileOutputStream(file);
                                    uf.setMode(0600);
                                    try {
                                        try {
                                            incGettingData();
                                            try {
                                                // TODO: Use direct daemon connection
                                                bd.getData(out, false, 0, this);
                                            } finally {
                                                decGettingData();
                                            }
                                        } catch(RuntimeException err) {
                                            System.err.println("Error trying to get backup data for pkey="+bd.getPKey());
                                            throw err;
                                        } finally {
					    out.flush();
                                            out.close();
                                        }
                                        isDone=true;
                                        long actualSize=file.length();
                                        if(actualSize!=correctSize) throw new SQLException("Incorrect number of bytes transferred for backup data file ("+file.getAbsolutePath()+").  Should be "+correctSize+", was "+actualSize);
                                    } finally {
                                        if(!isDone) uf.delete();
                                    }
                                }
                            }
                        } else {
                            long actualSize=backupDataFile.length();
                            if(actualSize<correctSize) {
                                BackupData bd=AOServDaemon.getConnector().backupDatas.get(pkey);
                                // The data could have been deleted when offsite backup lagging behind
                                if(bd!=null) {
                                    boolean isStored=bd.isStored();
                                    if(isStored) {
                                        // Resume where the transfer stopped
                                        boolean isDone=false;
                                        RandomAccessFile raf=new RandomAccessFile(backupDataFile, "rw");
                                        raf.seek(actualSize);
                                        try {
                                            try {
                                                incGettingData();
                                                try {
                                                    // TODO: use direct daemon connection
                                                    bd.getData(raf, false, actualSize, this);
                                                } finally {
                                                    decGettingData();
                                                }
                                            } catch(RuntimeException err) {
                                                System.err.println("Error trying to get backup data for pkey="+bd.getPKey());
                                                throw err;
                                            } finally {
                                                raf.close();
                                            }
                                            isDone=true;
                                            actualSize=backupDataFile.length();
                                            if(actualSize!=correctSize) throw new SQLException("Incorrect file size for backup data file ("+backupDataFile.getAbsolutePath()+").  Should be "+correctSize+", is "+actualSize);
                                        } finally {
                                            if(!isDone && !backupDataFile.delete()) throw new IOException("Unable to delete file: "+backupDataFile.getPath());
                                        }
                                    }
                                }
                            } else if(actualSize>correctSize) {
                                throw new SQLException("The backup data file ("+backupDataFile.getAbsolutePath()+") is too big.  Should be "+correctSize+", is "+actualSize);
                            }
                        }
                    } catch(IOException err) {
                        AOServDaemon.reportError(err, null);
                        try {
                            Thread.sleep(ERROR_DELAY);
                        } catch(InterruptedException err2) {
                            AOServDaemon.reportWarning(err2, null);
                        }
                    } catch(SQLException err) {
                        AOServDaemon.reportError(err, null);
                        try {
                            Thread.sleep(ERROR_DELAY);
                        } catch(InterruptedException err2) {
                            AOServDaemon.reportWarning(err2, null);
                        }
                    } catch(ThreadDeath TD) {
                        throw TD;
                    } catch(Throwable T) {
                        AOServDaemon.reportError(T, null);
                        throw new WrappedException(T);
                    }
                }
            } finally {
                Profiler.endProfile(Profiler.UNKNOWN);
            }
        }

        public int getBitRate() {
            Profiler.startProfile(Profiler.INSTANTANEOUS, BackupReplicatorThread.class, "getBitRate()", null);
            try {
                return getSingleThreadBitRate();
            } finally {
                Profiler.endProfile(Profiler.INSTANTANEOUS);
            }
        }

        public int getBlockSize() {
            Profiler.startProfile(Profiler.INSTANTANEOUS, BackupReplicatorThread.class, "getBlockSize()", null);
            try {
                return BLOCK_SIZE;
            } finally {
                Profiler.endProfile(Profiler.INSTANTANEOUS);
            }
        }
    }
}
