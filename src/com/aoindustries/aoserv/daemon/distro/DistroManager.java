package com.aoindustries.aoserv.daemon.distro;

/*
 * Copyright 2001-2009 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.aoserv.client.AOServConnector;
import com.aoindustries.aoserv.client.AOServTable;
import com.aoindustries.aoserv.client.AOServer;
import com.aoindustries.aoserv.client.DistroFile;
import com.aoindustries.aoserv.client.DistroFileTable;
import com.aoindustries.aoserv.client.DistroFileType;
import com.aoindustries.aoserv.client.LinuxAccount;
import com.aoindustries.aoserv.client.LinuxGroup;
import com.aoindustries.aoserv.client.LinuxServerAccount;
import com.aoindustries.aoserv.client.LinuxServerGroup;
import com.aoindustries.aoserv.client.SQLColumnValue;
import com.aoindustries.aoserv.client.SQLComparator;
import com.aoindustries.aoserv.client.SQLExpression;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import com.aoindustries.aoserv.daemon.AOServDaemonConfiguration;
import com.aoindustries.aoserv.daemon.LogFactory;
import com.aoindustries.email.ProcessTimer;
import com.aoindustries.io.unix.Stat;
import com.aoindustries.io.unix.UnixFile;
import com.aoindustries.md5.MD5;
import com.aoindustries.md5.MD5InputStream;
import com.aoindustries.util.BufferManager;
import com.aoindustries.util.ErrorPrinter;
import com.aoindustries.util.StringUtility;
import com.aoindustries.util.sort.AutoSort;
import com.oreilly.servlet.MailMessage;
import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.InterruptedIOException;
import java.io.PrintStream;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Verifies the server distribution.
 *
 * @author  AO Industries, Inc.
 */
final public class DistroManager implements Runnable {

    /**
     * The number of entries in a directory that will be reported as a warning.
     */
    private static final int DIRECTORY_LENGTH_WARNING=100000;

    /**
     * The time between distro verifications.
     */
    private static final int DISTRO_INTERVAL=2*24*60*60*1000;

    private static final int MAX_SLEEP_TIME=5*60*1000;

    private static Thread thread;

    public static void startDistro(boolean includeUser) {
        DistroManager.includeUser=includeUser;
        if(isSleeping && thread!=null) {
            runNow=true;
            thread.interrupt();
        }
    }

    private DistroManager() {
    }

    public static void start() throws IOException {
        if(AOServDaemonConfiguration.isManagerEnabled(DistroManager.class) && thread==null) {
            synchronized(System.out) {
                if(thread==null) {
                    System.out.print("Starting DistroManager: ");
                    thread=new Thread(new DistroManager());
                    thread.setDaemon(true);
                    thread.start();
                    System.out.println("Done");
                }
            }
        }
    }

    private static boolean isSleeping=false;
    private static boolean includeUser;
    private static boolean runNow=false;

    public void run() {
        while(true) {
            try {
                while(true) {
                    // Wait 10 minutes before checking again
                    includeUser=true;
                    isSleeping=true;
                    runNow=false;
                    try {
                        Thread.sleep(10L*60*1000);
                    } catch(InterruptedException err) {
                        // Normal from startDistro method
                    }
                    isSleeping=false;

                    // It is time to run if it is the backup hour and the backup has not been run for at least 12 hours
                    AOServer thisAOServer=AOServDaemon.getThisAOServer();
                    long distroStartTime=System.currentTimeMillis();
                    long lastDistroTime=thisAOServer.getLastDistroTime();
                    Logger logger = LogFactory.getLogger(DistroManager.class);
                    boolean isTrace = logger.isLoggable(Level.FINER);
                    if(isTrace) {
                        logger.finer("runNow="+runNow);
                        logger.finer("distroStartTime="+distroStartTime);
                        logger.finer("lastDistroTime="+lastDistroTime);
                    }
                    if(runNow || lastDistroTime>distroStartTime || (distroStartTime-lastDistroTime)>=12L*60*60*1000) {
                        int distroHour=thisAOServer.getDistroHour();
                        Calendar cal=Calendar.getInstance();
                        cal.setTimeInMillis(distroStartTime);
                        int currentHour = cal.get(Calendar.HOUR_OF_DAY);
                        if(isTrace) {
                            logger.finer("distroHour="+distroHour);
                            logger.finer("currentHour="+currentHour);
                        }
                        if(runNow || currentHour==distroHour) {
                            ProcessTimer timer=new ProcessTimer(
                                LogFactory.getLogger(DistroManager.class),
                                AOServDaemon.getRandom(),
                                "Distro verification taking too long",
                                "Distro Verification",
                                12*60*60*1000,
                                60*60*1000
                            );
                            try {
                                AOServDaemon.executorService.submit(timer);

                                AOServDaemon.getThisAOServer().setLastDistroTime(distroStartTime);

                                List<String> results = checkFilesystem(false);

                                // Send the results
                                sendResults(results);
                            } finally {
                                timer.finished();
                            }
                        }
                    }
                }
            } catch(ThreadDeath TD) {
                throw TD;
            } catch(Throwable T) {
                LogFactory.getLogger(DistroManager.class).log(Level.SEVERE, null, T);
                try {
                    Thread.sleep(MAX_SLEEP_TIME);
                } catch(InterruptedException err) {
                    LogFactory.getLogger(DistroManager.class).log(Level.WARNING, null, err);
                }
            }
        }
    }

    private static List<String> checkFilesystem(boolean displayResults) throws IOException, SQLException {
        // Build the list of files that should exist
        AOServConnector conn=AOServDaemon.getConnector();
        DistroFileTable distroFileTable=conn.getDistroFiles();
        List<String> results;
        // Getting this list provides a single, immutable, consistent snap-shot of the information
        List<DistroFile> distroFiles=distroFileTable.getRows();
        boolean[] foundFiles=new boolean[distroFiles.size()];

        // The comparator used for the searches
        SQLComparator pathComparator=new SQLComparator(
            conn,
            new SQLExpression[] {
                new SQLColumnValue(conn, distroFileTable.getTableSchema().getSchemaColumn(conn, DistroFile.COLUMN_PATH)),
                new SQLColumnValue(conn, distroFileTable.getTableSchema().getSchemaColumn(conn, DistroFile.COLUMN_OPERATING_SYSTEM_VERSION))
            },
            new boolean[] {AOServTable.ASCENDING, AOServTable.ASCENDING}
        );

        // Verify all the files, from the root to the lowest directory, accumulating the results in the results List
        results=new ArrayList<String>();
        checkDistroFile(
            AOServDaemon.getThisAOServer(),
            Integer.valueOf(AOServDaemon.getThisAOServer().getServer().getOperatingSystemVersion().getPkey()),
            distroFiles,
            foundFiles,
            pathComparator,
            new UnixFile("/"),
            results,
            displayResults,
            0
        );

        // Add entries for all the missing files
        String lastPath=null;
        int size=foundFiles.length;
        for(int c=0;c<size;c++) {
            if(!foundFiles[c]) {
                DistroFile distroFile=distroFiles.get(c);
                if(!distroFile.isOptional()) {
                    String path=distroFile.getPath();
                    if(lastPath==null || !path.startsWith(lastPath)) {
                        results.add("MI "+path);
                        if(displayResults) {
                            System.out.println(results.get(results.size()-1));
                            System.out.flush();
                        }
                        lastPath=path;
                    }
                }
            }
        }
        return results;
    }

    /**
     * 3D "..." or " " directory
     * BD Big Directory
     * EX Extra
     * GR Group
     * LN Length
     * M5 MD5
     * MI Missing
     * OW Owner
     * NO No Owner
     * NG No Group
     * PR Permissions
     * SU User SetUID
     * SY Symlink
     * TY Type
     */
    @SuppressWarnings({"unchecked"})
    private static void checkDistroFile(
        AOServer aoServer,
        Integer osVersionPKey,
        List<DistroFile> distroFiles,
        boolean[] foundFiles,
        SQLComparator pathComparator,
        UnixFile file,
        List<String> results,
        boolean displayResults,
        int recursionLevel
    ) throws IOException, SQLException {
        // Check for ...
        String name=file.getFile().getName();
        if(
            name.startsWith("...")
            || allSpace(name)
        ) {
            results.add("3D "+file);
            if(displayResults) {
                System.out.println(results.get(results.size()-1));
                System.out.flush();
            }
        }
        String filename=file.getPath();
        int index=Collections.binarySearch(distroFiles, new Object[] {filename, osVersionPKey}, pathComparator);
        DistroFile distroFile=index<0?null:distroFiles.get(index);

        // Check for hostname substitution
        String hostname=aoServer.getHostname();
        int pos=filename.indexOf(hostname);
        if(pos>=0) {
            String newFilename=filename.substring(0, pos)+"$h"+filename.substring(pos+hostname.length());
            int newIndex=Collections.binarySearch(distroFiles, new Object[] {newFilename, osVersionPKey}, pathComparator);
            if(newIndex>=0) {
                DistroFile newDistroFile=distroFiles.get(newIndex);

                // Flag as found
                if(index>=0) foundFiles[index]=true;

                // Use the substituted filename
                filename=newFilename;
                index=newIndex;
                distroFile=newDistroFile;
            }
        }

        // Stat here for use below
        Stat fileStat = file.getStat();

        if(distroFile==null) {
            // Should not be here
            results.add((fileStat.isDirectory() ? "rm -rf '" : "rm -f '") + filename + '\'');
            if(displayResults) {
                System.out.println(results.get(results.size()-1));
                System.out.flush();
            }
        } else {
            // Flag as found
            foundFiles[index]=true;

            // Do not check the ownership of the /usr/serverlocal directory
            if(!filename.equals("/usr/serverlocal")) {
                // Check owner
                int fileUID=fileStat.getUID();
                LinuxAccount la=distroFile.getLinuxAccount();
                LinuxServerAccount lsa=la.getLinuxServerAccount(aoServer);
                if(lsa==null) LogFactory.getLogger(DistroManager.class).log(Level.WARNING, "filename="+filename, new SQLException("Unable to find LinuxServerAccount for "+la+" on "+aoServer));
                int distroUID=lsa==null ? 65535 : lsa.getUID().getID();
                if(fileUID!=distroUID) {
                    results.add("chown "+distroUID+" '"+filename+"' #"+fileUID+"!="+distroUID);
                    if(displayResults) {
                        System.out.println(results.get(results.size()-1));
                        System.out.flush();
                    }
                }

                // Check group
                int fileGID=fileStat.getGID();
                LinuxGroup lg = distroFile.getLinuxGroup();
                LinuxServerGroup lsg = lg.getLinuxServerGroup(aoServer);
                if(lsg==null) LogFactory.getLogger(DistroManager.class).log(Level.WARNING, "filename="+filename, new SQLException("Unable to find LinuxServerGroup for "+lg+" on "+aoServer));
                int distroGID=lsg==null ? 65535 : lsg.getGID().getID();
                if(fileGID!=distroGID) {
                    results.add("chgrp "+distroGID+" '"+filename+"' #"+fileGID+"!="+distroGID);
                    if(displayResults) {
                        System.out.println(results.get(results.size()-1));
                        System.out.flush();
                    }
                }
            }

            // Type
            long fileMode=fileStat.getRawMode();
            long distroMode=distroFile.getMode();
            long fileType=fileMode&UnixFile.TYPE_MASK;
            long distroType=distroMode&UnixFile.TYPE_MASK;
            if(fileType!=distroType) {
                results.add("TY "+filename+" "+fileType+"!="+distroType);
                if(displayResults) {
                    System.out.println(results.get(results.size()-1));
                    System.out.flush();
                }
            } else {
                // Do not check the permissions of the /usr/serverlocal directory"
                if(!filename.equals("/usr/serverlocal")) {
                    // Permissions
                    long filePerms=fileMode&UnixFile.PERMISSION_MASK;
                    long distroPerms=distroMode&UnixFile.PERMISSION_MASK;
                    if(filePerms!=distroPerms) {
                        results.add("chmod "+Long.toOctalString(distroPerms)+" '"+filename+"' #"+Long.toOctalString(filePerms)+"!="+Long.toOctalString(distroPerms));
                        if(displayResults) {
                            System.out.println(results.get(results.size()-1));
                            System.out.flush();
                        }
                    }
                }
            }

            // Symlinks
            if(fileStat.isSymLink()) {
                String distroLink=distroFile.getSymlinkTarget();
                if(distroLink!=null) {
                    String fileLink=file.readLink();
                    // Allow multiple destinations separated by |
                    String[] dests=StringUtility.splitString(distroLink, '|');
                    boolean found=false;
                    for(int c=0;c<dests.length;c++) {
                        if(dests[c].equals(fileLink)) {
                            found=true;
                            break;
                        }
                    }
                    if(!found) {
                        results.add("rm -f '"+filename+"'; ln -s '"+distroLink+"' '"+filename+"' # "+fileLink+"!="+distroLink);
                        if(displayResults) {
                            System.out.println(results.get(results.size()-1));
                            System.out.flush();
                        }
                    }
                }
            } else {
                if(
                    !fileStat.isBlockDevice()
                    && !fileStat.isCharacterDevice()
                    && !fileStat.isFIFO()
                    && !fileStat.isSocket()
                ) {
                    String type=distroFile.getType().getType();
                    if(!fileStat.isDirectory()) {
                        if(!type.equals(DistroFileType.CONFIG)) {
                            if(type.equals(DistroFileType.PRELINK)) {
                                // Prelink MD5
                                long startTime=System.currentTimeMillis();

                                String md5;
                                {
                                    String[] command = {
                                        "/usr/sbin/prelink",
                                        "--verify",
                                        "--md5",
                                        file.getPath()
                                    };
                                    Process P = Runtime.getRuntime().exec(command);
                                    try {
                                        P.getOutputStream().close();
                                        BufferedReader in = new BufferedReader(new InputStreamReader(P.getInputStream()));
                                        try {
                                            String line = in.readLine();
                                            if(line.length()<32) throw new IOException("Line too short, must be at least 32 characters: "+line);
                                            md5 = line.substring(0, 32);
                                        } finally {
                                            in.close();
                                        }
                                    } finally {
                                        try {
                                            int retCode = P.waitFor();
                                            if(retCode!=0) throw new IOException("Non-zero response from command: "+AOServDaemon.getCommandString(command));
                                        } catch(InterruptedException err) {
                                            IOException ioErr = new InterruptedIOException();
                                            ioErr.initCause(err);
                                            throw ioErr;
                                        }
                                    }
                                }
                                long file_md5_hi=MD5.getMD5Hi(md5);
                                long file_md5_lo=MD5.getMD5Lo(md5);
                                long distro_md5_hi=distroFile.getFileMD5Hi();
                                long distro_md5_lo=distroFile.getFileMD5Lo();
                                if(
                                    file_md5_hi!=distro_md5_hi
                                    || file_md5_lo!=distro_md5_lo
                                ) {
                                    results.add("M5 "+filename+" "+MD5.getMD5String(file_md5_hi, file_md5_lo)+"!="+MD5.getMD5String(distro_md5_hi, distro_md5_lo));
                                    if(displayResults) {
                                        System.out.println(results.get(results.size()-1));
                                        System.out.flush();
                                    }
                                }

                                // Sleep for an amount of time equivilent to half the time it took to process this file
                                long timeSpan=(System.currentTimeMillis()-startTime)/2;
                                if(timeSpan<0) timeSpan=0;
                                else if(timeSpan>MAX_SLEEP_TIME) timeSpan=MAX_SLEEP_TIME;
                                if(timeSpan!=0) {
                                    try {
                                        Thread.sleep(timeSpan);
                                    } catch(InterruptedException err) {
                                        LogFactory.getLogger(DistroManager.class).log(Level.WARNING, null, err);
                                    }
                                }
                            } else if(type.equals(DistroFileType.SYSTEM)) {
                                // Length
                                long fileLen=file.getFile().length();
                                long distroLen=distroFile.getSize();
                                if(fileLen!=distroLen) {
                                    results.add("LE "+filename+" "+fileLen+"!="+distroLen);
                                    if(displayResults) {
                                        System.out.println(results.get(results.size()-1));
                                        System.out.flush();
                                    }
                                } else {
                                    // MD5
                                    long startTime=System.currentTimeMillis();

                                    byte[] fileHash=hashFile(file.getPath());
                                    long file_md5_hi=MD5.getMD5Hi(fileHash);
                                    long file_md5_lo=MD5.getMD5Lo(fileHash);
                                    long distro_md5_hi=distroFile.getFileMD5Hi();
                                    long distro_md5_lo=distroFile.getFileMD5Lo();
                                    if(
                                        file_md5_hi!=distro_md5_hi
                                        || file_md5_lo!=distro_md5_lo
                                    ) {
                                        results.add("M5 "+filename+" "+MD5.getMD5String(file_md5_hi, file_md5_lo)+"!="+MD5.getMD5String(distro_md5_hi, distro_md5_lo));
                                        if(displayResults) {
                                            System.out.println(results.get(results.size()-1));
                                            System.out.flush();
                                        }
                                    }

                                    // Sleep for an amount of time equivilent to half the time it took to process this file
                                    long timeSpan=(System.currentTimeMillis()-startTime)/2;
                                    if(timeSpan<0) timeSpan=0;
                                    else if(timeSpan>MAX_SLEEP_TIME) timeSpan=MAX_SLEEP_TIME;
                                    if(timeSpan!=0) {
                                        try {
                                            Thread.sleep(timeSpan);
                                        } catch(InterruptedException err) {
                                            LogFactory.getLogger(DistroManager.class).log(Level.WARNING, null, err);
                                        }
                                    }
                                }
                            } else throw new RuntimeException("Unexpected value for type: "+type);
                        }
                    } else {
                        if(type.equals(DistroFileType.USER)) {
                            // Check as user directory
                            if(includeUser) checkUserDirectory(aoServer, file, results, displayResults, 0);
                        } else {
                            if(!type.equals(DistroFileType.NO_RECURSE)) {
                                // Recurse directory
                                String[] list=file.list();
                                if(list!=null) {
                                    AutoSort.sortStatic(list);
                                    int newRecursionLevel=recursionLevel+1;
                                    int len=list.length;
                                    if(len>=DIRECTORY_LENGTH_WARNING) {
                                        results.add("BD "+filename+" "+len+">="+DIRECTORY_LENGTH_WARNING);
                                        if(displayResults) {
                                            System.out.println(results.get(results.size()-1));
                                            System.out.flush();
                                        }
                                    }
                                    for(int c=0;c<len;c++) {
                                        checkDistroFile(
                                            aoServer,
                                            osVersionPKey,
                                            distroFiles,
                                            foundFiles,
                                            pathComparator,
                                            new UnixFile(file, list[c], false),
                                            results,
                                            displayResults,
                                            newRecursionLevel
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
    
    public static byte[] hashFile(String filename) throws IOException {
        MD5InputStream md5in=new MD5InputStream(new FileInputStream(filename));
        try {
            byte[] trashBuffer=BufferManager.getBytes();
            try {
                int ret;
                while((ret=md5in.read(trashBuffer, 0, BufferManager.BUFFER_SIZE))!=-1) {
                    // Intentional empty block
                }
            } finally {
                BufferManager.release(trashBuffer);
            }
        } finally {
            md5in.close();
        }
        return md5in.hash();
    }

    private static void checkUserDirectory(
        AOServer aoServer,
        UnixFile file,
        List<String> results,
        boolean displayResults,
        int recursionLevel
    ) throws IOException, SQLException {
        String[] list=file.list();
        if(list!=null) {
            AutoSort.sortStatic(list);
            int len=list.length;
            if(len>=DIRECTORY_LENGTH_WARNING) {
                results.add("BD "+file+" "+len+">="+DIRECTORY_LENGTH_WARNING);
                if(displayResults) {
                    System.out.println(results.get(results.size()-1));
                    System.out.flush();
                }
            }
            for(int c=0;c<len;c++) {
                try {
                    String name=list[c];
                    UnixFile uf=new UnixFile(file, name, false);
                    try {
                        // Check for ...
                        if(
                            name.startsWith("...")
                            || (name.length()>0 && name.charAt(0)==' ')
                        ) {
                            results.add("3D "+uf);
                            if(displayResults) {
                                System.out.println(results.get(results.size()-1));
                                System.out.flush();
                            }
                        }

                        // Stat here for use below
                        Stat ufStat = uf.getStat();

                        // Make sure is a valid user
                        int uid=ufStat.getUID();
                        if(aoServer.getLinuxServerAccount(uid)==null) {
                            results.add("NO "+uf+" "+uid);
                            if(displayResults) {
                                System.out.println(results.get(results.size()-1));
                                System.out.flush();
                            }
                        }

                        // Make sure is a valid group
                        int gid=ufStat.getGID();
                        if(aoServer.getLinuxServerGroup(gid)==null) {
                            results.add("NG "+uf+" "+gid);
                            if(displayResults) {
                                System.out.println(results.get(results.size()-1));
                                System.out.flush();
                            }
                        }

                        // Make sure not setUID or setGID
                        long fileMode=ufStat.getMode();
                        if(
                            (fileMode&(UnixFile.SET_UID|UnixFile.SET_GID))!=0
                            && (
                                uid<UnixFile.MINIMUM_USER_UID
                                || gid<UnixFile.MINIMUM_USER_GID
                            )
                        ) {
                            // Allow setUID for /etc/mail/majordomo/*/wrapper 4750 root.mail
                            boolean found=false;
                            String filename=uf.getPath();
                            if(
                                filename.length()>20
                                && filename.substring(0, 20).equals("/etc/mail/majordomo/")
                            ) {
                                int pos=filename.indexOf('/', 20);
                                if(pos!=-1) {
                                    String fname=filename.substring(pos+1);
                                    if(
                                        fname.equals("wrapper")
                                        && fileMode==04750
                                        && ufStat.getUID()==UnixFile.ROOT_UID
                                        && aoServer.getLinuxServerGroup(ufStat.getGID()).getLinuxGroup().getName().equals(LinuxGroup.MAIL)
                                    ) found=true;
                                }
                            }
                            if(!found) {
                                results.add("SU "+uf+" "+Long.toOctalString(fileMode));
                                if(displayResults) {
                                    System.out.println(results.get(results.size()-1));
                                    System.out.flush();
                                }
                            }
                        }

                        // Make sure not world writable
                        //if((fileMode&UnixFile.OTHER_WRITE)==UnixFile.OTHER_WRITE) {
                        //    results.add("PR "+uf+" "+Integer.toOctalString(fileMode));
                        //    if(displayResults) {
                        //        System.out.println(results.get(results.size()-1));
                        //        System.out.flush();
                        //    }
                        //}

                        // Recurse
                        if(includeUser && !ufStat.isSymLink() && ufStat.isDirectory()) {
                            checkUserDirectory(aoServer, uf, results, displayResults, recursionLevel+1);
                        }
                    } catch(RuntimeException err) {
                        LogFactory.getLogger(DistroManager.class).severe("RuntimeException while accessing: "+uf.getPath());
                        throw err;
                    }
                } catch(FileNotFoundException err) {
                    // File might be removed during the scan
                }
            }
        }
    }

    private static void sendResults(List<String> results) throws IOException, SQLException {
        int size=results.size();
        if(size>0) {
            // Log full version
            Logger logger = LogFactory.getLogger(DistroManager.class);
            if(logger.isLoggable(Level.INFO)) {
                for(int c=0;c<size;c++) logger.info(results.get(c));
            }

            // Compile the counters for each of the different codes
            /*
            HashMap codes=new HashMap();
            for(int c=0;c<size;c++) {
                String code=results.get(c).substring(0,2);
                int[] count=(int[])codes.get(code);
                if(count==null) codes.put(code, count=new int[1]);
                count[0]++;
            }*/

            // Generate from address
            AOServer aoServer=AOServDaemon.getThisAOServer();
            String from="distro@"+aoServer.getHostname();

            // Send to cell phones
            /*
            StringBuilder SB=new StringBuilder();
            boolean did=false;
            Iterator I=codes.keySet().iterator();
            while(I.hasNext()) {
                if(did) SB.append(' ');
                else did=true;
                String code=(String)I.next();
                int count=((int[])codes.get(code))[0];
                SB.append(code).append('-').append(count);
            }*/
            // Put into the NOC as ticket or other way.  Send via email.
            StringBuilder details = new StringBuilder();
            details.append("Distro Check Failed\n");
            for(int c=0;c<size;c++) details.append(results.get(c)).append('\n');
            logger.log(Level.WARNING, details.toString());
        }
    }

    private static boolean allSpace(String S) throws IOException {
        int len=S.length();
        if(len==0) return false;
        for(int c=0;c<len;c++) {
            char ch=S.charAt(c);
            if(ch>' ') return false;
        }
        return true;
    }
    
    /**
     * Runs a scan immediately and displays the output to <code>System.out</code>.
     */
    public static void main(String[] args) {
        try {
            List<String> results = checkFilesystem(true);
        } catch(IOException err) {
            ErrorPrinter.printStackTraces(err);
        } catch(SQLException err) {
            ErrorPrinter.printStackTraces(err);
        }
    }
}
