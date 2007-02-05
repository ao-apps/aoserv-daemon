package com.aoindustries.aoserv.daemon.distro;

/*
 * Copyright 2001-2007 by AO Industries, Inc.,
 * 816 Azalea Rd, Mobile, Alabama, 36693, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.aoserv.client.*;
import com.aoindustries.aoserv.daemon.*;
import com.aoindustries.email.*;
import com.aoindustries.io.*;
import com.aoindustries.io.unix.*;
import com.aoindustries.md5.*;
import com.aoindustries.profiler.*;
import com.aoindustries.util.*;
import com.aoindustries.util.sort.*;
import com.oreilly.servlet.*;
import java.io.*;
import java.sql.*;
import java.util.*;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * Verifies the server distribution.
 *
 * @author  AO Industries, Inc.
 */
final public class DistroManager implements Runnable {

    private static final Log log = LogFactory.getLog(DistroManager.class);

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
        Profiler.startProfile(Profiler.UNKNOWN, DistroManager.class, "startDistro(boolean)", null);
        try {
            DistroManager.includeUser=includeUser;
            if(isSleeping && thread!=null) {
                runNow=true;
                thread.interrupt();
            }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    private DistroManager() {
        Profiler.startProfile(Profiler.INSTANTANEOUS, DistroManager.class, "<init>()", null);
        Profiler.endProfile(Profiler.INSTANTANEOUS);
    }

    public static void start() throws IOException {
        Profiler.startProfile(Profiler.UNKNOWN, DistroManager.class, "start()", null);
        try {
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
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    private static boolean isSleeping=false;
    private static boolean includeUser;
    private static boolean runNow=false;

    public void run() {
        Profiler.startProfile(Profiler.UNKNOWN, DistroManager.class, "run()", null);
        try {
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
                        if(log.isDebugEnabled()) {
                            log.debug("runNow="+runNow);
                            log.debug("distroStartTime="+distroStartTime);
                            log.debug("lastDistroTime="+lastDistroTime);
                        }
                        if(runNow || lastDistroTime>distroStartTime || (distroStartTime-lastDistroTime)>=12L*60*60*1000) {
                            int distroHour=thisAOServer.getDistroHour();
                            Calendar cal=Calendar.getInstance();
                            cal.setTimeInMillis(distroStartTime);
                            int currentHour = cal.get(Calendar.HOUR_OF_DAY);
                            if(log.isDebugEnabled()) {
                                log.debug("distroHour="+distroHour);
                                log.debug("currentHour="+currentHour);
                            }
                            if(runNow || currentHour==distroHour) {
                                ProcessTimer timer=new ProcessTimer(
                                    AOServDaemon.getRandom(),
                                    AOServDaemonConfiguration.getWarningSmtpServer(),
                                    AOServDaemonConfiguration.getWarningEmailFrom(),
                                    AOServDaemonConfiguration.getWarningEmailTo(),
                                    "Distro verification taking too long",
                                    "Distro Verification",
                                    12*60*60*1000,
                                    60*60*1000
                                );
                                try {
                                    timer.start();

                                    AOServDaemon.getThisAOServer().setLastDistroTime(distroStartTime);

                                    // Build the list of files that should exist
                                    AOServConnector conn=AOServDaemon.getConnector();
                                    DistroFileTable distroFileTable=conn.distroFiles;
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
                                        Integer.valueOf(AOServDaemon.getThisAOServer().getServer().getOperatingSystemVersion().getPKey()),
                                        distroFiles,
                                        foundFiles,
                                        pathComparator,
                                        new UnixFile("/"),
                                        results,
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
                                                    lastPath=path;
                                                }
                                            }
                                        }
                                    }

                                    // Send the results
                                    sendResults(results);
                                } finally {
                                    timer.stop();
                                }
                            }
                        }
                    }
                } catch(ThreadDeath TD) {
                    throw TD;
                } catch(Throwable T) {
                    AOServDaemon.reportError(T, null);
                    try {
                        Thread.sleep(MAX_SLEEP_TIME);
                    } catch(InterruptedException err) {
                        AOServDaemon.reportWarning(err, null);
                    }
                }
            }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
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
    private static void checkDistroFile(
        AOServer aoServer,
        Integer osVersionPKey,
        List<DistroFile> distroFiles,
        boolean[] foundFiles,
        SQLComparator pathComparator,
        UnixFile file,
        List<String> results,
        int recursionLevel
    ) throws IOException, SQLException {
        Profiler.startProfile(Profiler.IO, DistroManager.class, "checkDistroFile(AOServer,List<DistroFile>,boolean[],SQLComparator,UnixFile,List<String>,int)", recursionLevel==0?null:Integer.valueOf(recursionLevel));
        try {
            // Check for ...
            String name=file.getFile().getName();
            if(
                name.startsWith("...")
                || allSpace(name)
            ) results.add("3D "+file);
            String filename=file.getFilename();
            int index=Collections.binarySearch(distroFiles, new Object[] {filename, osVersionPKey}, pathComparator);
            DistroFile distroFile=index<0?null:distroFiles.get(index);

            // Check for hostname substitution
            String hostname=aoServer.getServer().getHostname();
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

            if(distroFile==null) {
                // Should not be here
                results.add("EX "+filename);
            } else {
                // Flag as found
                foundFiles[index]=true;

                // Stat here for use below
                Stat fileStat = file.getStat();

                // Do not check the ownership of the /usr/serverlocal directory
                if(!filename.equals("/usr/serverlocal")) {
                    // Check owner
                    int fileUID=fileStat.getUID();
                    LinuxAccount la=distroFile.getLinuxAccount();
                    LinuxServerAccount lsa=la.getLinuxServerAccount(aoServer);
                    if(lsa==null) AOServDaemon.reportWarning(new SQLException("Unable to find LinuxServerAccount for "+la+" on "+aoServer), new Object[] {"filename="+filename});
                    int distroUID=lsa==null ? 65535 : lsa.getUID().getID();
                    if(fileUID!=distroUID) {
                        results.add("chown "+distroUID+" '"+filename+"' #"+fileUID+"!="+distroUID);
                    }

                    // Check group
                    int fileGID=fileStat.getGID();
                    LinuxGroup lg = distroFile.getLinuxGroup();
                    LinuxServerGroup lsg = lg.getLinuxServerGroup(aoServer);
                    if(lsg==null) AOServDaemon.reportWarning(new SQLException("Unable to find LinuxServerGroup for "+lg+" on "+aoServer), new Object[] {"filename="+filename});
                    int distroGID=lsg==null ? 65535 : lsg.getGID().getID();
                    if(fileGID!=distroGID) {
                        results.add("chgrp "+distroGID+" '"+filename+"' #"+fileGID+"!="+distroGID);
                    }
                }

                // Type
                long fileMode=fileStat.getRawMode();
                long distroMode=distroFile.getMode();
                long fileType=fileMode&UnixFile.TYPE_MASK;
                long distroType=distroMode&UnixFile.TYPE_MASK;
                if(fileType!=distroType) {
                    results.add("TY "+filename+" "+fileType+"!="+distroType);
                } else {
                    // Do not check the permissions of the /usr/serverlocal directory"
                    if(!filename.equals("/usr/serverlocal")) {
                        // Permissions
                        long filePerms=fileMode&UnixFile.PERMISSION_MASK;
                        long distroPerms=distroMode&UnixFile.PERMISSION_MASK;
                        if(filePerms!=distroPerms) {
                            results.add("chmod "+Long.toOctalString(distroPerms)+" '"+filename+"' #"+Long.toOctalString(filePerms)+"!="+Long.toOctalString(distroPerms));
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
                        if(!found) results.add("rm -f '"+filename+"'; ln -s '"+distroLink+"' '"+filename+"' # "+fileLink+"!="+distroLink);
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
                                // Length
                                long fileLen=file.getFile().length();
                                long distroLen=distroFile.getSize();
                                if(fileLen!=distroLen) {
                                    results.add("LE "+filename+" "+fileLen+"!="+distroLen);
                                } else {
                                    // MD5
                                    long startTime=System.currentTimeMillis();

                                    byte[] fileHash=hashFile(file.getFilename());
                                    long file_md5_hi=MD5.getMD5Hi(fileHash);
                                    long file_md5_lo=MD5.getMD5Lo(fileHash);
                                    long distro_md5_hi=distroFile.getFileMD5Hi();
                                    long distro_md5_lo=distroFile.getFileMD5Lo();
                                    if(
                                        file_md5_hi!=distro_md5_hi
                                        || file_md5_lo!=distro_md5_lo
                                    ) {
                                        results.add("M5 "+filename+" "+MD5.getMD5String(file_md5_hi, file_md5_lo)+"!="+MD5.getMD5String(distro_md5_hi, distro_md5_lo));
                                    }

                                    // Sleep for an amount of time equivilent to half the time it took to process this file
                                    long timeSpan=(System.currentTimeMillis()-startTime)/2;
                                    if(timeSpan<0) timeSpan=0;
                                    else if(timeSpan>MAX_SLEEP_TIME) timeSpan=MAX_SLEEP_TIME;
                                    if(timeSpan!=0) {
                                        try {
                                            Thread.sleep(timeSpan);
                                        } catch(InterruptedException err) {
                                            AOServDaemon.reportWarning(err, null);
                                        }
                                    }
                                }
                            }
                        } else {
                            if(type.equals(DistroFileType.USER)) {
                                // Check as user directory
                                if(includeUser) checkUserDirectory(aoServer, file, results, 0);
                            } else {
                                if(!type.equals(DistroFileType.NO_RECURSE)) {
                                    // Recurse directory
                                    String[] list=file.list();
                                    if(list!=null) {
                                        AutoSort.sortStatic(list);
                                        int newRecursionLevel=recursionLevel+1;
                                        int len=list.length;
                                        if(len>=DIRECTORY_LENGTH_WARNING) results.add("BD "+filename+" "+len+">="+DIRECTORY_LENGTH_WARNING);
                                        for(int c=0;c<len;c++) {
                                            checkDistroFile(
                                                aoServer,
                                                osVersionPKey,
                                                distroFiles,
                                                foundFiles,
                                                pathComparator,
                                                new UnixFile(file, list[c], false),
                                                results,
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
        } finally {
            Profiler.endProfile(Profiler.IO);
        }
    }
    
    public static byte[] hashFile(String filename) throws IOException {
        Profiler.startProfile(Profiler.IO, DistroManager.class, "hashFile(String)", null);
        try {
            MD5InputStream md5in=new MD5InputStream(new FileInputStream(filename));
            try {
                byte[] trashBuffer=BufferManager.getBytes();
                try {
                    int ret;
                    while((ret=md5in.read(trashBuffer, 0, BufferManager.BUFFER_SIZE))!=-1);
                } finally {
                    BufferManager.release(trashBuffer);
                }
            } finally {
                md5in.close();
            }
            return md5in.hash();
        } finally {
            Profiler.endProfile(Profiler.IO);
        }
    }

    private static void checkUserDirectory(
        AOServer aoServer,
        UnixFile file,
        List<String> results,
        int recursionLevel
    ) throws IOException, SQLException {
        Profiler.startProfile(Profiler.IO, DistroManager.class, "checkUserDirectory(AOServer,UnixFile,List<String>,int)", recursionLevel==0?null:Integer.valueOf(recursionLevel));
        try {
            String[] list=file.list();
            if(list!=null) {
                AutoSort.sortStatic(list);
                int len=list.length;
                if(len>=DIRECTORY_LENGTH_WARNING) results.add("BD "+file+" "+len+">="+DIRECTORY_LENGTH_WARNING);
                for(int c=0;c<len;c++) {
                    try {
                        String name=list[c];
                        UnixFile uf=new UnixFile(file, name, false);
                        try {
                            // Check for ...
                            if(
                                name.startsWith("...")
                                || (name.length()>0 && name.charAt(0)==' ')
                            ) results.add("3D "+uf);

                            // Stat here for use below
                            Stat ufStat = uf.getStat();

                            // Make sure is a valid user
                            int uid=ufStat.getUID();
                            if(aoServer.getLinuxServerAccount(uid)==null) {
                                results.add("NO "+uf+" "+uid);
                            }

                            // Make sure is a valid group
                            int gid=ufStat.getGID();
                            if(aoServer.getLinuxServerGroup(gid)==null) {
                                results.add("NG "+uf+" "+gid);
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
                                String filename=uf.getFilename();
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
                                if(!found) results.add("SU "+uf+" "+Long.toOctalString(fileMode));
                            }

                            // Make sure not world writable
                            //if((fileMode&UnixFile.OTHER_WRITE)==UnixFile.OTHER_WRITE) {
                            //    results.add("PR "+uf+" "+Integer.toOctalString(fileMode));
                            //}

                            // Recurse
                            if(includeUser && !ufStat.isSymLink() && ufStat.isDirectory()) {
                                checkUserDirectory(aoServer, uf, results, recursionLevel+1);
                            }
                        } catch(RuntimeException err) {
                            if(log.isErrorEnabled()) log.error("RuntimeException while accessing: "+uf.getFilename());
                            throw err;
                        }
                    } catch(FileNotFoundException err) {
                        // File might be removed during the scan
                    }
                }
            }
        } finally {
            Profiler.endProfile(Profiler.IO);
        }
    }

    private static void sendResults(List<String> results) throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, DistroManager.class, "sendResults(List<String>)", null);
        try {
            int size=results.size();
            if(size>0) {
                // Log full version
                if(log.isInfoEnabled()) {
                    for(int c=0;c<size;c++) log.info(results.get(c));
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
                String from="distro@"+aoServer.getServer().getHostname();

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
                // Send via email
                String smtp=AOServDaemonConfiguration.getSecuritySmtpServer();
                if(smtp!=null && smtp.length()>0) {
                    try {
                        // Full version to support
                        MailMessage msg=new MailMessage(smtp);
                        msg.from(from);
                        msg.to("distro@aoindustries.com");
                        msg.setSubject("Distro Check Failed");
                        PrintStream email=msg.getPrintStream();
                        for(int c=0;c<size;c++) email.println(results.get(c));
                        msg.sendAndClose();
                    } catch(IOException err) {
                        AOServDaemon.reportError(err, null);
                    }
                }
            }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    private static boolean allSpace(String S) throws IOException {
        Profiler.startProfile(Profiler.FAST, DistroManager.class, "allSpace(String)", null);
        try {
            int len=S.length();
            if(len==0) return false;
            for(int c=0;c<len;c++) {
                char ch=S.charAt(c);
                if(ch>' ') return false;
            }
            return true;
        } finally {
            Profiler.endProfile(Profiler.FAST);
        }
    }
}
