package com.aoindustries.aoserv.daemon.backup;

/*
 * Copyright 2001-2009 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.aoserv.daemon.AOServDaemon;
import com.aoindustries.aoserv.daemon.LogFactory;
import com.aoindustries.io.unix.UnixFile;
import com.aoindustries.util.StringUtility;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.InterruptedIOException;
import java.util.Calendar;
import java.util.List;
import java.util.logging.Level;

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

    private BackupManager() {}

    /**
     * Backs-up and then deletes the provided files.
     */
    public static void backupAndDeleteFiles(List<File> deleteFileList) throws IOException {
        if(!deleteFileList.isEmpty()) {
            // Get the next backup filename
            File backupFile = getNextBackupFile();
            // Backup
            backupFiles(deleteFileList, backupFile);
            // Remove the files that have been backed up.
            for(File file : deleteFileList) new UnixFile(file).secureDeleteRecursive();
        }
    }

    /**
     * Makes a tarball of the provided files into the provided file.
     */
    public static void backupFiles(List<File> files, File backupFile) throws IOException {
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
    
    static void cleanVarOldaccounts() {
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
            LogFactory.getLogger(BackupManager.class).log(Level.SEVERE, null, T);
        }
    }

    /**
     * The full path to the df command.
     */
    public static final String DF="/bin/df";

    public static long getDiskDeviceTotalSize(String path) throws IOException {
        return getDFColumn(path, 1);
    }
    
    public static long getDiskDeviceUsedSize(String path) throws IOException {
        return getDFColumn(path, 2);
    }

    private static long getDFColumn(String path, int column) throws IOException {
        String[] dfCommand={
            DF,
            "-k",
            "-P",
            path
        };
        long size;
        Process P=Runtime.getRuntime().exec(dfCommand);
        try {
            P.getOutputStream().close();
            BufferedReader in=new BufferedReader(new InputStreamReader(P.getInputStream()));
            try {
                // The first line is the column labels
                String line=in.readLine();
                if(line==null) throw new IOException("EOF when trying to read column labels");
                line=in.readLine();
                if(line==null) throw new IOException("EOF when trying to read values");
                String[] columns=StringUtility.splitString(line);
                size=1024*Long.parseLong(columns[column]);
            } finally {
                in.close();
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
     * Gets the next backup filename in the <code>/var/oldaccounts</code> directory.
     * The filename format is <code>YYYYMMDD_HHMMSS_C.tgz</code> where <code>C</code> a counter
     * starting at one.
     */
    public static File getNextBackupFile() throws IOException {
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
    }
}
