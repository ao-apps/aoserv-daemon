package com.aoindustries.aoserv.daemon.failover;

/*
 * Copyright 2003-2007 by AO Industries, Inc.,
 * 816 Azalea Rd, Mobile, Alabama, 36693, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.aoserv.client.AOServClientConfiguration;
import com.aoindustries.aoserv.client.AOServer;
import com.aoindustries.aoserv.client.BackupPartition;
import com.aoindustries.aoserv.client.BackupRetention;
import com.aoindustries.aoserv.client.FailoverFileLog;
import com.aoindustries.aoserv.client.FailoverFileReplication;
import com.aoindustries.aoserv.client.FailoverFileSchedule;
import com.aoindustries.aoserv.client.FailoverMySQLReplication;
import com.aoindustries.aoserv.client.HttpdServer;
import com.aoindustries.aoserv.client.IPAddress;
import com.aoindustries.aoserv.client.MySQLServer;
import com.aoindustries.aoserv.client.NetBind;
import com.aoindustries.aoserv.client.NetPort;
import com.aoindustries.aoserv.client.Protocol;
import com.aoindustries.aoserv.client.SSLConnector;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import com.aoindustries.aoserv.daemon.AOServDaemonConfiguration;
import com.aoindustries.aoserv.daemon.client.AOServDaemonConnection;
import com.aoindustries.aoserv.daemon.client.AOServDaemonConnector;
import com.aoindustries.aoserv.daemon.client.AOServDaemonProtocol;
import com.aoindustries.email.ProcessTimer;
import com.aoindustries.io.AOPool;
import com.aoindustries.io.BitRateOutputStream;
import com.aoindustries.io.ByteCountInputStream;
import com.aoindustries.io.ByteCountOutputStream;
import com.aoindustries.io.CompressedDataInputStream;
import com.aoindustries.io.CompressedDataOutputStream;
import com.aoindustries.io.DontCloseInputStream;
import com.aoindustries.io.DontCloseOutputStream;
import com.aoindustries.io.unix.FileExistsRule;
import com.aoindustries.io.unix.FilesystemIterator;
import com.aoindustries.io.unix.FilesystemIteratorResult;
import com.aoindustries.io.unix.FilesystemIteratorRule;
import com.aoindustries.io.unix.Stat;
import com.aoindustries.io.unix.UnixFile;
import com.aoindustries.md5.MD5;
import com.aoindustries.profiler.Profiler;
import com.aoindustries.util.BufferManager;
import com.aoindustries.util.LongArrayList;
import com.aoindustries.util.LongList;
import com.aoindustries.util.SortedArrayList;
import com.aoindustries.util.WrappedException;
import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.sql.SQLException;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Stack;
import java.util.zip.Deflater;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;
import java.util.zip.DeflaterOutputStream;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * Handles the replication of data for the failover system.
 *
 * TODO: Some files are being named one day in advance (Still, seems to be problem on client-side)
 *
 * TODO: Handle hard links (pertinence space savings)
 *
 * TODO: Actually do chunkAlways mode
 *
 * TODO: Need to do mysqldump and postgresql dump
 *
 * @author  AO Industries, Inc.
 */
final public class FailoverFileReplicationManager {

    private static final Log log = LogFactory.getLog(FailoverFileReplicationManager.class);

    /**
     * This was the workaround for international filenames.  I am hoping that converting using UTF-8 makes them work properly now.
     */
    private static final boolean CONVERT_PATHS_TO_ASCII = false;

    private static final int
        NO_CHANGE=0,
        MODIFIED=1,
        MODIFIED_REQUEST_DATA=2,
        MODIFIED_REQUEST_DATA_CHUNKED=3
    ;

    /**
     * The number of bytes per block when using chunked transfers.
     * This value is optimal for PostgreSQL because it matches its
     * page size.
     */
    private static final int CHUNK_SIZE=8192;

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
     * The number of recycled copies varies based on retention.
     */
    private static int getNumberRecycleDirectories(int retention) {
        if(retention<=7) return 1;
        if(retention<=31) return 2;
        if(retention<=92) return 3;
        return 4;
    }
    
    /**
     * While a recycled directory is being updated to be the current, it is renamed to have this extension.
     * This extension is used instead of simply ".partial" to distinguish when the update is from the most
     * recent pass or when it is from an older pass.
     */
    private static final String RECYCLED_PARTIAL_EXTENSION=".recycled.partial";

    private static Thread backupThread;
    private static Thread failoverThread;

    private FailoverFileReplicationManager() {
        Profiler.startProfile(Profiler.INSTANTANEOUS, FailoverFileReplicationManager.class, "<init>()", null);
        Profiler.endProfile(Profiler.INSTANTANEOUS);
    }

    public static void checkPath(String path) throws IOException {
        Profiler.startProfile(Profiler.FAST, FailoverFileReplicationManager.class, "checkPath(String)", null);
        try {
            if(
                path.charAt(0)!='/'
                || path.indexOf("/../")!=-1
            ) throw new IOException("Illegal path: "+path);
        } finally {
            Profiler.endProfile(Profiler.FAST);
        }
    }

    /**
     * TODO: Move this into a backup settings table.
     */
    private static final Set<String> encryptedLoopFilePaths = new HashSet<String>(12*4/3+1);
    static {
        // Please note that the map was initialized with the number of these in mind, please update
        // the initialization parameter for best performance.
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
        encryptedLoopFilePaths.add("/var/cvs.aes128.img");
        encryptedLoopFilePaths.add("/var/cvs.aes256.img");
        encryptedLoopFilePaths.add("/var/lib/pgsql.aes128.img");
        encryptedLoopFilePaths.add("/var/lib/pgsql.aes256.img");
        encryptedLoopFilePaths.add("/var/spool.aes128.img");
        encryptedLoopFilePaths.add("/var/spool.aes256.img");
        encryptedLoopFilePaths.add("/www.aes128.img");
        encryptedLoopFilePaths.add("/www.aes256.img");
    }
    private static boolean isEncryptedLoopFile(String path) {
        return encryptedLoopFilePaths.contains(path);
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
                || relativePath.startsWith("/usr/mysql/")
                || relativePath.startsWith("/opt/mysql-")
            )
        ) {
            if(log.isDebugEnabled()) log.debug("Flagging postPassChecklist.restartMySQLs=true for path="+relativePath);
            postPassChecklist.restartMySQLs=true;
        }
    }

    public static void failoverServer(
        final Socket socket,
        final CompressedDataInputStream rawIn,
        final CompressedDataOutputStream rawOut,
        final String fromServer,
        final boolean useCompression,
        final short retention,
        final String toPath,
        final boolean chunkAlways,
        final short fromServerYear,
        final short fromServerMonth,
        final short fromServerDay,
        final List<String> replicatedMySQLServers,
        final List<String> replicatedMySQLMinorVersions
    ) throws IOException {
        Profiler.startProfile(Profiler.IO, FailoverFileReplicationManager.class, "failoverServer(Socket,CompressedDataInputStream,CompressedDataOutputStream,String,boolean,short,String,boolean,short,short,short,List<String>,List<String>)", null);
        try {
            final PostPassChecklist postPassChecklist = new PostPassChecklist();
            try {
                if(fromServerYear<1000 || fromServerYear>9999) throw new IOException("Invalid fromServerYear (1000-9999): "+fromServerYear);
                if(fromServerMonth<1 || fromServerMonth>12) throw new IOException("Invalid fromServerMonth (1-12): "+fromServerMonth);
                if(fromServerDay<1 || fromServerDay>31) throw new IOException("Invalid fromServerDay (1-31): "+fromServerDay);

                // Make sure no / or .. in these names, so calls as root to the chroot /etc/rc.d/init.d/mysql-... restart aren't exploitable
                for(String replicatedMySQLServer : replicatedMySQLServers) {
                    if(log.isDebugEnabled()) log.debug("failoverServer from "+fromServer+", replicatedMySQLServer: "+replicatedMySQLServer);
                    if(replicatedMySQLServer.indexOf('/')!=-1 || replicatedMySQLServer.indexOf("..")!=-1) throw new IOException("Invalid replicatedMySQLServer: "+replicatedMySQLServer);
                }
                for(String replicatedMySQLMinorVersion : replicatedMySQLMinorVersions) {
                    if(log.isDebugEnabled()) log.debug("failoverServer from "+fromServer+", replicatedMySQLMinorVersion: "+replicatedMySQLMinorVersion);
                    if(replicatedMySQLMinorVersion.indexOf('/')!=-1 || replicatedMySQLMinorVersion.indexOf("..")!=-1) throw new IOException("Invalid replicatedMySQLMinorVersion: "+replicatedMySQLMinorVersion);
                }

                // This Stat may be used for any short-term tempStat
                final Stat tempStat = new Stat();

                // Tell the client it is OK to continue
                rawOut.write(AOServDaemonProtocol.NEXT);
                rawOut.flush();

                // Determine the directory that is/will be storing the mirror
                String partialMirrorRoot;
                String recycledPartialMirrorRoot;
                String finalMirrorRoot;
                String linkToRoot;
                UnixFile serverRootUF;
                boolean isRecycling;
                if(retention==1) {
                    partialMirrorRoot = finalMirrorRoot = toPath+"/"+fromServer;
                    recycledPartialMirrorRoot = null;
                    linkToRoot = null;
                    serverRootUF = null;
                    isRecycling = false;
                } else {
                    // The directory that holds the different versions
                    StringBuilder SB = new StringBuilder(toPath).append('/').append(fromServer);
                    String serverRoot = SB.toString();

                    // Create the server root if it doesn't exist
                    serverRootUF = new UnixFile(serverRoot);
                    serverRootUF.getStat(tempStat);
                    if(!tempStat.exists()) serverRootUF.mkdir().setMode(0700);
                    else if(!tempStat.isDirectory()) throw new IOException("Server Root exists but is not a directory: "+serverRoot);
                    else if(tempStat.getMode()!=0700) serverRootUF.setMode(0700);

                    // The directory including the date
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

                    // When the finalMirrorRoot exists, it is assumed to be complete and no linking to other directories will be performed.  This mode
                    // is used when multiple passes are performed in a single day, it is basically the same behavior as a failover replication.
                    UnixFile finalUF = new UnixFile(finalMirrorRoot);
                    if(finalUF.getStat(tempStat).exists()) {
                        if(log.isDebugEnabled()) log.debug("Renaming existing "+finalMirrorRoot+" to "+partialMirrorRoot);
                        UnixFile partialUF = new UnixFile(partialMirrorRoot);
                        finalUF.renameTo(partialUF);
                        linkToRoot = null;
                        isRecycling = false;
                    } else {
                        // Finds the path that will be linked-to.
                        // Find the most recent complete pass.  If none exists, select the most recent recycled partial.
                        // If none exists, look for the most recent partial.
                        // Skip the new partial directory
                        String[] list = serverRootUF.list();
                        linkToRoot = null;
                        if(list!=null && list.length>0) {
                            // This is not y10k compliant - this is assuming lexical order is the same as chronological order.
                            Arrays.sort(list);
                            // Find most recent complete pass
                            for(int c=list.length-1;c>=0;c--) {
                                String filename = list[c];
                                String fullFilename = serverRoot+"/"+filename;
                                if(
                                    filename.length()==10
                                    && !fullFilename.equals(finalMirrorRoot)
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
                            }
                            if(linkToRoot == null) {
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
                            }
                        }

                        /*
                         * At this point, we know what we are linking to.  We now need to know the current state of today's backup.  And it should always be in one of these four
                         * possible states:
                         *   1) Existing and complete
                         *   2) Existing and .recycled.partial
                         *   3) Existing and .partial
                         *   4) Nonexisting
                         *
                         * (1) is already handled above
                         *
                         * For (2) existing and .recycled.partial, we will not rename and set isRecycling to true.
                         *
                         * For (3) existing and .partial, we will not rename and set isRecycling to false.
                         *
                         * For (4) nonexisting, we will first look for the most recent .recycled.
                         *   If found, we will renamed to .recycled.partial and set isRecycling to true.
                         *   If not found, we will make a new directory called .partial and set isRecycling to false.
                         */
                        UnixFile recycledPartialUF = new UnixFile(recycledPartialMirrorRoot);
                        if(recycledPartialUF.getStat(tempStat).exists()) {
                            // See (2) above
                            isRecycling = true;
                        } else {
                            UnixFile partialUF = new UnixFile(partialMirrorRoot);
                            if(partialUF.getStat(tempStat).exists()) {
                                // See (3) above
                                isRecycling = false;
                            } else {
                                // See (4) above
                                isRecycling = false;

                                if(list!=null && list.length>0) {
                                    // Find most recent recycled pass
                                    for(int c=list.length-1;c>=0;c--) {
                                        String filename = list[c];
                                        if(filename.endsWith(RECYCLED_EXTENSION)) {
                                            new UnixFile(serverRootUF, filename, false).renameTo(recycledPartialUF);
                                            isRecycling = true;
                                            break;
                                        }
                                    }
                                }
                                if(!isRecycling) {
                                    partialUF.mkdir();
                                }
                            }
                        }
                    }
                }
                if(log.isDebugEnabled()) {
                    log.debug("partialMirrorRoot="+partialMirrorRoot);
                    log.debug("recycledPartialMirrorRoot="+recycledPartialMirrorRoot);
                    log.debug("finalMirrorRoot="+finalMirrorRoot);
                    log.debug("linkToRoot="+linkToRoot);
                    log.debug("isRecycling="+isRecycling);
                }
                // Safety checks to make sure above logic isn't linking in obviously incorrect ways
                if(linkToRoot!=null) {
                    if(linkToRoot.equals(partialMirrorRoot)) throw new IOException("linkToRoot==partialMirrorRoot: "+linkToRoot);
                    if(linkToRoot.equals(recycledPartialMirrorRoot)) throw new IOException("linkToRoot==recycledPartialMirrorRoot: "+linkToRoot);
                    if(linkToRoot.equals(finalMirrorRoot)) throw new IOException("linkToRoot==finalMirrorRoot: "+linkToRoot);
                }

                CompressedDataInputStream in = rawIn;
                CompressedDataOutputStream out = rawOut;

                String[] paths=null;
                boolean[] isLogDirs=null;
                UnixFile[] tempNewFiles=null;
                UnixFile[] chunkingFroms=null;
                LongList[] chunksMD5s=null;
                long[] modifyTimes=null;
                int[] results=null;

                // Used in the inner loop
                final Stat ufStat = new Stat();
                final Stat linkToUFStat = new Stat();
                final Stat otherFileStat = new Stat();
                final Stat tempNewFileStat = new Stat();

                byte[] buff=BufferManager.getBytes();
                byte[] chunkBuffer = useCompression ? new byte[CHUNK_SIZE] : null;
                MD5 md5 = useCompression ? new MD5() : null;
                try {
                    // The extra files in directories are cleaned once the directory is done
                    Stack<UnixFile> directoryUFs=new Stack<UnixFile>();
                    Stack<String> directoryUFRelativePaths=new Stack<String>();
                    Stack<Long> directoryModifyTimes=new Stack<Long>();
                    Stack<Set<String>> directoryContents=new Stack<Set<String>>();

                    // The actual cleaning and modify time setting is delayed to the end of the batch by adding
                    // the lists of things to do here.
                    List<UnixFile> directoryFinalizeUFs = new ArrayList<UnixFile>();
                    List<String> directoryFinalizeUFRelativePaths = new ArrayList<String>();
                    List<Long> directoryFinalizeModifyTimes = new ArrayList<Long>();
                    List<Set<String>> directoryFinalizeContents = new ArrayList<Set<String>>();

                    // Continue until a batchSize of -1 (end of replication)
                    int batchSize;
                    while((batchSize=in.readCompressedInt())!=-1) {
                        if(paths==null || paths.length < batchSize) {
                            paths=new String[batchSize];
                            isLogDirs=new boolean[batchSize];
                            tempNewFiles=new UnixFile[batchSize];
                            chunkingFroms=new UnixFile[batchSize];
                            chunksMD5s=useCompression ? new LongList[batchSize] : null;
                            modifyTimes=new long[batchSize];
                            results=new int[batchSize];
                        }
                        // Reset the directory finalization for each batch
                        directoryFinalizeUFs.clear();
                        directoryFinalizeUFRelativePaths.clear();
                        directoryFinalizeModifyTimes.clear();
                        directoryFinalizeContents.clear();

                        for(int c=0;c<batchSize;c++) {
                            if(in.readBoolean()) {
                                // Read the current file
                                final String relativePath=paths[c]=in.readCompressedUTF();
                                isLogDirs[c]=relativePath.startsWith("/logs/") || relativePath.startsWith("/var/log/");
                                checkPath(relativePath);
                                String path=paths[c]=(isRecycling ? recycledPartialMirrorRoot : partialMirrorRoot)+relativePath;
                                UnixFile uf=new UnixFile(path);
                                uf.getStat(ufStat);
                                UnixFile ufParent=uf.getParent();
                                String linkToPath;
                                UnixFile linkToUF;
                                UnixFile linkToParent;
                                if(linkToRoot!=null) {
                                    linkToPath=linkToRoot+relativePath;
                                    linkToUF=new UnixFile(linkToPath);
                                    linkToUF.getStat(linkToUFStat);
                                    linkToParent=linkToUF.getParent();
                                } else {
                                    linkToPath=null;
                                    linkToUF=null;
                                    linkToUFStat.reset();
                                    linkToParent=null;
                                }
                                long mode=in.readLong();
                                long length;
                                if(UnixFile.isRegularFile(mode)) length=in.readLong();
                                else length=-1;
                                int uid=in.readCompressedInt();
                                int gid=in.readCompressedInt();
                                long modifyTime=modifyTimes[c]=UnixFile.isSymLink(mode)?-1:in.readLong();
                                //if(modifyTime<1000 && !UnixFile.isSymLink(mode) && log.isWarnEnabled()) log.warn("Non-symlink modifyTime<1000: "+relativePath+": "+modifyTime);
                                String symlinkTarget;
                                if(UnixFile.isSymLink(mode)) symlinkTarget=in.readCompressedUTF();
                                else symlinkTarget=null;
                                long deviceID;
                                if(
                                    UnixFile.isBlockDevice(mode)
                                    || UnixFile.isCharacterDevice(mode)
                                ) deviceID=in.readLong();
                                else deviceID=-1;

                                // Cleanup extra entries in completed directories, setting modifyTime on the directories
                                while(!directoryUFs.isEmpty()) {
                                    UnixFile dirUF=directoryUFs.peek();
                                    String dirPath=dirUF.getFilename();
                                    if(!dirPath.endsWith("/")) dirPath=dirPath+'/';

                                    // If the current file starts with the current directory, continue
                                    if(path.startsWith(dirPath)) break;

                                    // Otherwise, schedule to clean and complete the directory at the end of this batch
                                    directoryUFs.pop();
                                    directoryFinalizeUFs.add(dirUF);
                                    directoryFinalizeUFRelativePaths.add(directoryUFRelativePaths.pop());
                                    directoryFinalizeModifyTimes.add(directoryModifyTimes.pop());
                                    directoryFinalizeContents.add(directoryContents.pop());
                                }

                                // Add the current to the directory
                                if(!directoryContents.isEmpty()) {
                                    directoryContents.peek().add(path);
                                }

                                // Process the current file
                                int result = NO_CHANGE;
                                tempNewFiles[c]=null;
                                chunkingFroms[c]=null;
                                if(useCompression) chunksMD5s[c]=null;
                                if(UnixFile.isBlockDevice(mode)) {
                                    if(
                                        ufStat.exists()
                                        && (
                                            !ufStat.isBlockDevice()
                                            || ufStat.getDeviceIdentifier()!=deviceID
                                        )
                                    ) {
                                        if(log.isTraceEnabled()) log.trace("Deleting to create block device: "+uf.getFilename());
                                        uf.deleteRecursive();
                                        uf.getStat(ufStat);
                                    }
                                    if(!ufStat.exists()) {
                                        uf.mknod(mode, deviceID);
                                        uf.getStat(ufStat);
                                        if(linkToUF!=null) {
                                            // Only modified if not in last backup set, too
                                            if(
                                                !linkToUFStat.exists()
                                                || !linkToUFStat.isBlockDevice()
                                                || linkToUFStat.getDeviceIdentifier()!=deviceID
                                            ) {
                                                result=MODIFIED;
                                                updated(retention, postPassChecklist, relativePath);
                                            }
                                        } else {
                                            result=MODIFIED;
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
                                        if(log.isTraceEnabled()) log.trace("Deleting to create character device: "+uf.getFilename());
                                        uf.deleteRecursive();
                                        uf.getStat(ufStat);
                                    }
                                    if(!ufStat.exists()) {
                                        uf.mknod(mode, deviceID);
                                        uf.getStat(ufStat);
                                        if(linkToUF!=null) {
                                            // Only modified if not in last backup set, too
                                            if(
                                                !linkToUFStat.exists()
                                                || !linkToUFStat.isCharacterDevice()
                                                || linkToUFStat.getDeviceIdentifier()!=deviceID
                                            ) {
                                                result=MODIFIED;
                                                updated(retention, postPassChecklist, relativePath);
                                            }
                                        } else {
                                            result=MODIFIED;
                                            updated(retention, postPassChecklist, relativePath);
                                        }
                                    }
                                } else if(UnixFile.isDirectory(mode)) {
                                    if(
                                        ufStat.exists()
                                        && !ufStat.isDirectory()
                                    ) {
                                        if(log.isTraceEnabled()) log.trace("Deleting to create directory: "+uf.getFilename());
                                        uf.deleteRecursive();
                                        uf.getStat(ufStat);
                                    }
                                    if(!ufStat.exists()) {
                                        uf.mkdir();
                                        uf.getStat(ufStat);
                                        if(linkToUF!=null) {
                                            // Only modified if not in last backup set, too
                                            if(
                                                !linkToUFStat.exists()
                                                || !linkToUFStat.isDirectory()
                                            ) {
                                                result=MODIFIED;
                                                updated(retention, postPassChecklist, relativePath);
                                            }
                                        } else {
                                            result=MODIFIED;
                                            updated(retention, postPassChecklist, relativePath);
                                        }
                                    } else if(ufStat.getModifyTime()!=modifyTime) {
                                        result=MODIFIED;
                                        updated(retention, postPassChecklist, relativePath);
                                    }
                                    directoryUFs.push(uf);
                                    directoryUFRelativePaths.push(relativePath);
                                    directoryModifyTimes.push(Long.valueOf(modifyTime));
                                    directoryContents.push(new HashSet<String>());
                                } else if(UnixFile.isFIFO(mode)) {
                                    if(
                                        ufStat.exists()
                                        && !ufStat.isFIFO()
                                    ) {
                                        if(log.isTraceEnabled()) log.trace("Deleting to create FIFO: "+uf.getFilename());
                                        uf.deleteRecursive();
                                        uf.getStat(ufStat);
                                    }
                                    if(!ufStat.exists()) {
                                        uf.mkfifo(mode);
                                        uf.getStat(ufStat);
                                        if(linkToUF!=null) {
                                            // Only modified if not in last backup set, too
                                            if(
                                                !linkToUFStat.exists()
                                                || !linkToUFStat.isFIFO()
                                            ) {
                                                result=MODIFIED;
                                                updated(retention, postPassChecklist, relativePath);
                                            }
                                        } else {
                                            result=MODIFIED;
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
                                        // If there is a symlink that has now been replaced with a regular file, just delete the symlink to avoid confusion in the following code
                                        if(ufStat.isSymLink()) {
                                            uf.delete();
                                            uf.getStat(ufStat);
                                        } else if(ufStat.isDirectory()) {
                                            // If there is a directory that has now been replaced with a regular file, just delete the directory recursively to avoid confusion in the following code
                                            uf.deleteRecursive();
                                            uf.getStat(ufStat);
                                        }
                                    }
                                    // Look in the current directory for an exact match
                                    final boolean isEncryptedLoopFile = isEncryptedLoopFile(relativePath);
                                    if(
                                        !isEncryptedLoopFile
                                        && ufStat.exists()
                                        && ufStat.isRegularFile()
                                        && ufStat.getSize()==length
                                        && ufStat.getModifyTime()==modifyTime
                                    ) {
                                        // Found in current directory, simply use default result = NO_CHANGE
                                    } else {
                                        // Steps below this need to know if we are in a log directory or not
                                        final boolean isLogDir = isLogDirs[c];

                                        // Look in the linkTo directory for an exact match
                                        if(
                                            !isEncryptedLoopFile
                                            && linkToUF!=null
                                            && linkToUFStat.exists()
                                            && linkToUFStat.isRegularFile()
                                            && linkToUFStat.getSize()==length
                                            && linkToUFStat.getModifyTime()==modifyTime
                                        ) {
                                            // Found match in linkTo, link to linkTo directory
                                            if(ufStat.exists()) {
                                                // If we are in a log directory, move a regular file out of the way into a temp file (for possible later reuse)
                                                if(ufStat.isRegularFile()) {
                                                    if(isLogDir) {
                                                        // Move to a new temp filename for later reuse
                                                        String name = uf.getFile().getName();
                                                        UnixFile templateUF = name.length()>64 ? new UnixFile(ufParent, name.substring(0, 64), false) : uf;
                                                        UnixFile tempUF = UnixFile.mktemp(templateUF.getFilename()+'.');
                                                        uf.renameTo(tempUF);
                                                        uf.link(linkToPath);
                                                        uf.getStat(ufStat);
                                                    } else {
                                                        // Delete and link is OK because this is using a linkTo directory (therefore not in failover mode)
                                                        uf.delete();
                                                        uf.link(linkToPath);
                                                        uf.getStat(ufStat);
                                                    }
                                                } else {
                                                    uf.deleteRecursive();
                                                    uf.link(linkToPath);
                                                    uf.getStat(ufStat);
                                                }
                                            } else {
                                                uf.link(linkToPath);
                                                uf.getStat(ufStat);
                                            }
                                        } else {
                                            // If we are in a log directory, search all regular files in current directory and linkTo directory for matching length and mtime
                                            // link to it if found
                                            boolean linkedOldLogFile = false;
                                            if(!isEncryptedLoopFile && isLogDir) {
                                                // Look for another file with the same size and modify time in this partial directory
                                                UnixFile oldLogUF = null;
                                                String[] list = ufParent.list();
                                                if(list!=null) {
                                                    for(int d=0;d<list.length;d++) {
                                                        UnixFile otherFile = new UnixFile(ufParent, list[d], false);
                                                        otherFile.getStat(otherFileStat);
                                                        if(
                                                            otherFileStat.exists()
                                                            && otherFileStat.isRegularFile()
                                                            && otherFileStat.getSize()==length
                                                            && otherFileStat.getModifyTime()==modifyTime
                                                        ) {
                                                            oldLogUF = otherFile;
                                                            break;
                                                        }
                                                    }
                                                }
                                                if(oldLogUF==null && linkToUF!=null) {
                                                    // Look for another file with the same size and modify time in the link to directory (previous backup pass).
                                                    String[] linkToList = linkToParent.list();
                                                    if(linkToList!=null) {
                                                        for(int d=0;d<linkToList.length;d++) {
                                                            UnixFile otherFile = new UnixFile(linkToParent, linkToList[d], false);
                                                            otherFile.getStat(otherFileStat);
                                                            if(
                                                                otherFileStat.exists()
                                                                && otherFileStat.isRegularFile()
                                                                && otherFileStat.getSize()==length
                                                                && otherFileStat.getModifyTime()==modifyTime
                                                            ) {
                                                                oldLogUF = otherFile;
                                                                break;
                                                            }
                                                        }
                                                    }
                                                }
                                                if(oldLogUF!=null) {
                                                    if(ufStat.exists()) {
                                                        if(ufStat.isRegularFile()) {
                                                            // Move to a new temp filename for later reuse
                                                            String name = uf.getFile().getName();
                                                            UnixFile templateUF = name.length()>64 ? new UnixFile(ufParent, name.substring(0, 64), false) : uf;
                                                            String tempPath = templateUF.getFilename()+'.';
                                                            UnixFile tempUF = UnixFile.mktemp(tempPath);
                                                            if(retention==1) {
                                                                // Failover mode does a more cautious link to temp and rename over to avoid
                                                                // any moment where there is no file in the path of uf
                                                                tempUF.delete();
                                                                tempUF.link(uf);
                                                                UnixFile tempUF2 = UnixFile.mktemp(tempPath);
                                                                tempUF2.delete();
                                                                tempUF2.link(oldLogUF);
                                                                tempUF2.renameTo(uf);
                                                            } else {
                                                                // Backup mode uses a more efficient approach because partial states are OK
                                                                uf.renameTo(tempUF);
                                                                uf.link(oldLogUF);
                                                            }
                                                        } else {
                                                            uf.deleteRecursive();
                                                            uf.link(oldLogUF);
                                                        }
                                                    } else {
                                                        uf.link(oldLogUF);
                                                    }
                                                    uf.getStat(ufStat);
                                                    result = MODIFIED;
                                                    updated(retention, postPassChecklist, relativePath);
                                                    linkedOldLogFile = true;
                                                }
                                            }
                                            if(!linkedOldLogFile) {
                                                boolean chunkingFile = false;
                                                if(
                                                    useCompression
                                                    // File is not so large that chunking can't possibly store md5's in RAM (> 8 Terabytes currently)
                                                    && (2*length/CHUNK_SIZE) < Integer.MAX_VALUE
                                                ) {
                                                    // Next we will try chunking.  For chunking, we will start by determining what we are chunking from.
                                                    UnixFile chunkingFrom;
                                                    Stat chunkingFromStat;
                                                    if(isRecycling) {
                                                        // When recycling, try linkToUF then uf
                                                        long size;
                                                        if(
                                                            linkToUF!=null
                                                            && linkToUFStat.exists()
                                                            && linkToUFStat.isRegularFile()
                                                            // Old file is not small (<CHUNK_SIZE)
                                                            && (size=linkToUFStat.getSize())>=CHUNK_SIZE
                                                            // Chunk at best reduces transfer size by CHUNK_SIZE/17 (md5 + 1 byte response)
                                                            && size>(length/(CHUNK_SIZE/17))
                                                        ) {
                                                            chunkingFrom = linkToUF;
                                                            chunkingFromStat = linkToUFStat;
                                                        } else if(
                                                            ufStat.exists()
                                                            && ufStat.isRegularFile()
                                                            // Old file is not small (<CHUNK_SIZE)
                                                            && (size=ufStat.getSize())>=CHUNK_SIZE
                                                            // Chunk at best reduces transfer size by CHUNK_SIZE/17 (md5 + 1 byte response)
                                                            && size>(length/(CHUNK_SIZE/17))
                                                        ) {
                                                            chunkingFrom = uf;
                                                            chunkingFromStat = ufStat;
                                                        } else {
                                                            chunkingFrom = null;
                                                            chunkingFromStat = null;
                                                        }
                                                    } else {
                                                        // When not recycling, try uf then linkToUF
                                                        long size;
                                                        if(
                                                            ufStat.exists()
                                                            && ufStat.isRegularFile()
                                                            // Old file is not small (<CHUNK_SIZE)
                                                            && (size=ufStat.getSize())>=CHUNK_SIZE
                                                            // Chunk at best reduces transfer size by CHUNK_SIZE/17 (md5 + 1 byte response)
                                                            && size>(length/(CHUNK_SIZE/17))
                                                        ) {
                                                            chunkingFrom = uf;
                                                            chunkingFromStat = ufStat;
                                                        } else if(
                                                            linkToUF!=null
                                                            && linkToUFStat.exists()
                                                            && linkToUFStat.isRegularFile()
                                                            // Old file is not small (<CHUNK_SIZE)
                                                            && (size=linkToUFStat.getSize())>=CHUNK_SIZE
                                                            // Chunk at best reduces transfer size by CHUNK_SIZE/17 (md5 + 1 byte response)
                                                            && size>(length/(CHUNK_SIZE/17))
                                                        ) {
                                                            chunkingFrom = linkToUF;
                                                            chunkingFromStat = linkToUFStat;
                                                        } else {
                                                            chunkingFrom = null;
                                                            chunkingFromStat = null;
                                                        }
                                                    }
                                                    if(chunkingFrom!=null) {
                                                        // Now we figure out what we are chunking to.
                                                        UnixFile chunkingTo;
                                                        if(
                                                            ufStat.exists()
                                                            || retention==1
                                                            || (!isRecycling && linkToUF!=null)
                                                        ) {
                                                            // When uf exists, chunk to a temp file
                                                            String name = uf.getFile().getName();
                                                            UnixFile templateUF = name.length()>64 ? new UnixFile(ufParent, name.substring(0, 64), false) : uf;
                                                            String tempPath = templateUF.getFilename()+'.';
                                                            UnixFile tempUF = UnixFile.mktemp(tempPath);
                                                            tempNewFiles[c] = tempUF;
                                                            if(log.isTraceEnabled()) log.trace("Using temp file: "+tempUF.getFilename());
                                                            chunkingTo = tempUF;
                                                        } else {
                                                            chunkingTo = uf;
                                                            if(!ufStat.exists()) {
                                                                new FileOutputStream(uf.getFile()).close();
                                                                uf.getStat(ufStat);
                                                            }
                                                        }

                                                        // Build the list of MD5 hashes per chunk
                                                        long sizeToChunk = Math.min(length, chunkingFromStat.getSize());
                                                        LongList md5s = chunksMD5s[c] = new LongArrayList((int)(2*sizeToChunk/CHUNK_SIZE));
                                                        // Generate the MD5 hashes for the current file
                                                        InputStream fileIn = new FileInputStream(chunkingFrom.getFile());
                                                        try {
                                                            while(true) {
                                                                // Read in blocks of CHUNK_SIZE
                                                                int pos=0;
                                                                while(pos<CHUNK_SIZE) {
                                                                    int ret = fileIn.read(chunkBuffer, pos, CHUNK_SIZE-pos);
                                                                    if(ret==-1) break;
                                                                    pos+=ret;
                                                                }
                                                                if(pos==CHUNK_SIZE) {
                                                                    md5.Init();
                                                                    md5.Update(chunkBuffer, 0, CHUNK_SIZE);
                                                                    byte[] md5Bytes=md5.Final();
                                                                    long md5Hi = MD5.getMD5Hi(md5Bytes);
                                                                    long md5Lo = MD5.getMD5Lo(md5Bytes);
                                                                    md5s.add(md5Hi);
                                                                    md5s.add(md5Lo);
                                                                } else {
                                                                    // End of file
                                                                    break;
                                                                }
                                                            }
                                                        } finally {
                                                            fileIn.close();
                                                        }
                                                        chunkingFroms[c]=chunkingFrom;
                                                        chunkingFile = true;
                                                        result = MODIFIED_REQUEST_DATA_CHUNKED;
                                                        updated(retention, postPassChecklist, relativePath);
                                                    }
                                                }
                                                if(!chunkingFile) {
                                                    result = MODIFIED_REQUEST_DATA;
                                                    updated(retention, postPassChecklist, relativePath);
                                                    if(!ufStat.exists()) {
                                                        new FileOutputStream(uf.getFile()).close();
                                                        uf.getStat(ufStat);
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
                                            || !uf.readLink().equals(symlinkTarget)
                                        )
                                    ) {
                                        if(log.isTraceEnabled()) log.trace("Deleting to create sybolic link: "+uf.getFilename());
                                        uf.deleteRecursive();
                                        uf.getStat(ufStat);
                                    }
                                    if(!ufStat.exists()) {
                                        uf.symLink(symlinkTarget);
                                        uf.getStat(ufStat);
                                        if(linkToUF!=null) {
                                            // Only modified if not in last backup set, too
                                            if(
                                                !linkToUFStat.exists()
                                                || !linkToUFStat.isSymLink()
                                                || !linkToUF.readLink().equals(symlinkTarget)
                                            ) {
                                                result=MODIFIED;
                                                updated(retention, postPassChecklist, relativePath);
                                            }
                                        } else {
                                            result=MODIFIED;
                                            updated(retention, postPassChecklist, relativePath);
                                        }
                                    }
                                } else throw new IOException("Unknown mode type: "+Long.toOctalString(mode&UnixFile.TYPE_MASK));

                                // Update the permissions (mode)
                                UnixFile effectiveUF;
                                Stat effectiveUFStat;
                                if(tempNewFiles[c]==null) {
                                    effectiveUF = uf;
                                    effectiveUFStat = ufStat;
                                } else {
                                    effectiveUF = tempNewFiles[c];
                                    effectiveUFStat = tempNewFileStat;
                                    effectiveUF.getStat(effectiveUFStat);
                                }
                                if(
                                    !UnixFile.isSymLink(mode)
                                    && (
                                        (effectiveUFStat.getRawMode() & (UnixFile.TYPE_MASK|UnixFile.PERMISSION_MASK))
                                        != (mode & (UnixFile.TYPE_MASK|UnixFile.PERMISSION_MASK))
                                    )
                                ) {
                                    try {
                                        if(retention!=1) copyIfHardLinked(effectiveUF, effectiveUFStat);
                                        effectiveUF.setMode(mode & (UnixFile.TYPE_MASK|UnixFile.PERMISSION_MASK));
                                        effectiveUF.getStat(effectiveUFStat);
                                    } catch(FileNotFoundException err) {
                                        AOServDaemon.reportWarning(err, new Object[] {"path="+path, "mode="+Long.toOctalString(mode)});
                                    }
                                    if(result==NO_CHANGE) {
                                        if(linkToUF!=null) {
                                            // Only modified if wrong permission in last backup set, too
                                            if(
                                                !linkToUFStat.exists()
                                                || (
                                                    (linkToUFStat.getRawMode() & (UnixFile.TYPE_MASK|UnixFile.PERMISSION_MASK))
                                                    != (mode & (UnixFile.TYPE_MASK|UnixFile.PERMISSION_MASK))
                                                )
                                            ) {
                                                result=MODIFIED;
                                                updated(retention, postPassChecklist, relativePath);
                                            }
                                        } else {
                                            result=MODIFIED;
                                            updated(retention, postPassChecklist, relativePath);
                                        }
                                    }
                                }

                                // Update the ownership
                                if(effectiveUFStat.getUID()!=uid || effectiveUFStat.getGID()!=gid) {
                                    if(retention!=1) copyIfHardLinked(effectiveUF, effectiveUFStat);
                                    effectiveUF.chown(uid, gid);
                                    effectiveUF.getStat(effectiveUFStat);
                                    if(result==NO_CHANGE) {
                                        if(linkToUF!=null) {
                                            // Only modified if not in last backup set, too
                                            if(
                                                !linkToUFStat.exists()
                                                || linkToUFStat.getUID()!=uid
                                                || linkToUFStat.getGID()!=gid
                                            ) {
                                                result=MODIFIED;
                                                updated(retention, postPassChecklist, relativePath);
                                            }
                                        } else {
                                            result=MODIFIED;
                                            updated(retention, postPassChecklist, relativePath);
                                        }
                                    }
                                }

                                // Update the modified time
                                if(
                                    !UnixFile.isSymLink(mode)
                                    && !UnixFile.isDirectory(mode)
                                    && effectiveUFStat.getModifyTime()!=modifyTime
                                ) {
                                    if(retention!=1) copyIfHardLinked(effectiveUF, effectiveUFStat);
                                    try {
                                        effectiveUF.utime(effectiveUFStat.getAccessTime(), modifyTime);
                                        effectiveUF.getStat(effectiveUFStat);
                                    } catch(IOException err) {
                                        throw new WrappedException(err, new Object[] {"effectiveUF="+effectiveUF.getFilename()});
                                    }
                                    if(result==NO_CHANGE) {
                                        if(linkToUF!=null) {
                                            // Only modified if not in last backup set, too
                                            if(
                                                !linkToUFStat.exists()
                                                || linkToUFStat.getModifyTime()!=modifyTime
                                            ) {
                                                result=MODIFIED;
                                                updated(retention, postPassChecklist, relativePath);
                                            }
                                        } else {
                                            result=MODIFIED;
                                            updated(retention, postPassChecklist, relativePath);
                                        }
                                        //result=MODIFIED;
                                    }
                                }
                                results[c]=result;
                            } else paths[c]=null;
                        }

                        // Write the results
                        out.write(AOServDaemonProtocol.NEXT);
                        for(int c=0;c<batchSize;c++) {
                            if(paths[c]!=null) {
                                int result = results[c];
                                out.write(result);
                                if(result==MODIFIED_REQUEST_DATA_CHUNKED) {
                                    LongList md5s = chunksMD5s[c];
                                    out.writeCompressedInt(md5s.size()/2);
                                    for(int d=0;d<md5s.size();d++) out.writeLong(md5s.getLong(d));
                                }
                            }
                        }

                        // Flush the results
                        out.flush();

                        // Store incoming data
                        for(int c=0;c<batchSize;c++) {
                            String path=paths[c];
                            if(path!=null) {
                                int result=results[c];
                                UnixFile tempUF = tempNewFiles[c];
                                UnixFile uf=new UnixFile(path);
                                if(result==MODIFIED_REQUEST_DATA || result==MODIFIED_REQUEST_DATA_CHUNKED) {
                                    // Load into the temporary file or directly to the file (based on above calculations)
                                    UnixFile fileOutUF = tempUF==null ? uf : tempUF;
                                    OutputStream fileOut = new FileOutputStream(fileOutUF.getFile());
                                    try {
                                        if(result==MODIFIED_REQUEST_DATA) {
                                            int response;
                                            while((response=in.read())==AOServDaemonProtocol.NEXT) {
                                                int blockLen=in.readShort();
                                                in.readFully(buff, 0, blockLen);
                                                fileOut.write(buff, 0, blockLen);
                                            }
                                            if(response!=AOServDaemonProtocol.DONE) throw new IOException("Unexpected response code: "+response);
                                        } else if(result==MODIFIED_REQUEST_DATA_CHUNKED) {
                                            RandomAccessFile raf=new RandomAccessFile(chunkingFroms[c].getFile(), "r");
                                            try {
                                                long bytesWritten=0;
                                                int response;
                                                while((response=in.read())==AOServDaemonProtocol.NEXT || response==AOServDaemonProtocol.NEXT_CHUNK) {
                                                    if(response==AOServDaemonProtocol.NEXT) {
                                                        int chunkLen=in.readShort();
                                                        in.readFully(chunkBuffer, 0, chunkLen);
                                                        fileOut.write(chunkBuffer, 0, chunkLen);
                                                        bytesWritten+=chunkLen;
                                                    } else if(response==AOServDaemonProtocol.NEXT_CHUNK) {
                                                        // Get the values from the old file (chunk matches)
                                                        raf.seek(bytesWritten);
                                                        raf.readFully(chunkBuffer, 0, CHUNK_SIZE);
                                                        fileOut.write(chunkBuffer, 0, CHUNK_SIZE);
                                                        bytesWritten+=CHUNK_SIZE;
                                                    } else throw new RuntimeException("Unexpected value for response: "+response);
                                                }
                                                if(response!=AOServDaemonProtocol.DONE) throw new IOException("Unexpected response code: "+response);
                                            } finally {
                                                raf.close();
                                            }
                                        } else throw new RuntimeException("Unexpected value for result: "+result);
                                    } finally {
                                        fileOut.close();
                                    }
                                    fileOutUF.utime(fileOutUF.getStat(tempStat).getAccessTime(), modifyTimes[c]);
                                }
                                if(tempUF!=null) {
                                    uf.getStat(ufStat);
                                    if(ufStat.exists()) {
                                        if(ufStat.isRegularFile()) {
                                            if(isLogDirs[c]) {
                                                // Move to a new temp filename for later reuse
                                                String name = uf.getFile().getName();
                                                UnixFile templateUF = name.length()>64 ? new UnixFile(uf.getParent(), name.substring(0, 64), false) : uf;
                                                String tempPath = templateUF.getFilename()+'.';
                                                UnixFile tempUFLog = UnixFile.mktemp(tempPath);
                                                if(retention==1) {
                                                    // Failover mode does a more cautious link to temp and rename over to avoid
                                                    // any moment where there is no file in the path of uf
                                                    tempUFLog.delete();
                                                    tempUFLog.link(uf);
                                                    tempUF.renameTo(uf);
                                                } else {
                                                    // Backup mode uses a more efficient approach because partial states are OK
                                                    uf.renameTo(tempUFLog);
                                                    tempUF.renameTo(uf);
                                                }
                                            } else {
                                                // Not a log directory, just replace old regular file
                                                tempUF.renameTo(uf);
                                            }
                                        } else {
                                            uf.deleteRecursive();
                                            tempUF.renameTo(uf);
                                        }
                                    } else {
                                        tempUF.renameTo(uf);
                                    }
                                }
                            }
                        }

                        // For any directories that were completed during this batch, clean extra files and set its modify time
                        for(int c=0;c<directoryFinalizeUFs.size();c++) {
                            UnixFile dirUF = directoryFinalizeUFs.get(c);
                            String relativePath = directoryFinalizeUFRelativePaths.get(c);
                            long dirModifyTime = directoryFinalizeModifyTimes.get(c).longValue();
                            Set<String> dirContents = directoryFinalizeContents.get(c);
                            String dirPath=dirUF.getFilename();
                            if(!dirPath.endsWith("/")) dirPath=dirPath+'/';
                            String[] list=dirUF.list();
                            if(list!=null) {
                                for(int d=0;d<list.length;d++) {
                                    String filename = list[d];
                                    String fullpath=dirPath+filename;
                                    if(!dirContents.contains(fullpath)) {
                                        if(deleteOnCleanup(fromServer, retention, relativePath+'/'+filename, replicatedMySQLServers, replicatedMySQLMinorVersions)) {
                                            if(log.isTraceEnabled()) log.trace("Deleting extra file: "+fullpath);
                                            try {
                                                new UnixFile(fullpath).deleteRecursive();
                                            } catch(FileNotFoundException err) {
                                                AOServDaemon.reportError(err, new Object[] {"fullpath="+fullpath});
                                            }
                                        }
                                    }
                                }
                            }
                            dirUF.getStat(tempStat);
                            if(tempStat.getModifyTime()!=dirModifyTime) dirUF.utime(tempStat.getAccessTime(), dirModifyTime);
                        }
                    }

                    // Clean all remaining directories all the way to /, setting modifyTime on the directories
                    while(!directoryUFs.isEmpty()) {
                        UnixFile dirUF=directoryUFs.peek();
                        String dirPath=dirUF.getFilename();
                        if(!dirPath.endsWith("/")) dirPath=dirPath+'/';

                        // Otherwise, clean and complete the directory
                        directoryUFs.pop();
                        String relativePath = directoryUFRelativePaths.pop();
                        long dirModifyTime=directoryModifyTimes.pop().longValue();
                        Set<String> dirContents=directoryContents.pop();
                        String[] list=dirUF.list();
                        if(list!=null) {
                            for(int c=0;c<list.length;c++) {
                                String filename = list[c];
                                String fullpath=dirPath+filename;
                                if(!dirContents.contains(fullpath)) {
                                    if(deleteOnCleanup(fromServer, retention, relativePath+'/'+filename, replicatedMySQLServers, replicatedMySQLMinorVersions)) {
                                        if(log.isTraceEnabled()) log.trace("Deleting final clean-up: "+fullpath);
                                        try {
                                            new UnixFile(fullpath).deleteRecursive();
                                        } catch(FileNotFoundException err) {
                                            AOServDaemon.reportError(err, new Object[] {"fullpath="+fullpath});
                                        }
                                    }
                                }
                            }
                        }
                        dirUF.getStat(tempStat);
                        if(tempStat.getModifyTime()!=dirModifyTime) dirUF.utime(tempStat.getAccessTime(), dirModifyTime);
                    }

                    // The pass was successful, now rename partial to final
                    if(retention!=1) {
                        String from = isRecycling ? recycledPartialMirrorRoot : partialMirrorRoot;
                        if(log.isDebugEnabled()) log.debug("Renaming "+from+" to "+finalMirrorRoot);
                        new UnixFile(from).renameTo(new UnixFile(finalMirrorRoot));
                    }

                    // The pass was successful, now cleanup old directories based on retention settings
                    if(retention>1) cleanAndRecycleBackups(retention, serverRootUF, tempStat, fromServerYear, fromServerMonth, fromServerDay);

                    // Tell the client we are done OK
                    out.write(AOServDaemonProtocol.DONE);
                    out.flush();
                } finally {
                    BufferManager.release(buff);
                }
            } catch(RuntimeException err) {
                socket.close();
                throw err;
            } catch(IOException err) {
                socket.close();
                throw err;
            } finally {
                if(postPassChecklist.restartMySQLs && retention==1) {
                    for(String mysqlServer : replicatedMySQLServers) {
                        if(log.isDebugEnabled()) log.debug("Restarting MySQL "+mysqlServer+" in "+toPath+"/"+fromServer);
                        String[] command = {
                            "/usr/sbin/chroot",
                            toPath+"/"+fromServer,
                            "/etc/rc.d/init.d/mysql-"+mysqlServer,
                            "restart"
                        };
                        try {
                            AOServDaemon.exec(
                                command
                            );
                        } catch(IOException err) {
                            AOServDaemon.reportError(err, command);
                        }
                    }
                }
            }
        } finally {
            Profiler.endProfile(Profiler.IO);
        }
    }

    /**
     * Determines if a specific file may be deleted on clean-up.
     * Don't delete anything in /proc/*, /sys/*, /dev/pts/*, or MySQL replication-related files
     */
    private static boolean deleteOnCleanup(String fromServer, int retention, String relativePath, List<String> replicatedMySQLServers, List<String> replicatedMySQLMinorVersions) {
        if(
            relativePath.equals("/proc")
            || relativePath.startsWith("/proc/")
            || relativePath.equals("/sys")
            || relativePath.startsWith("/sys/")
            || relativePath.equals("/dev/pts")
            || relativePath.startsWith("/dev/pts/")
        ) {
            if(log.isDebugEnabled()) log.debug("Skipping delete on cleanup: "+fromServer+":"+relativePath);
            return false;
        }
        if(retention==1) {
            for(String name : replicatedMySQLServers) {
                if(
                    relativePath.equals("/var/lib/mysql/"+name)
                    || relativePath.startsWith("/var/lib/mysql/"+name+"/")
                ) {
                    if(log.isDebugEnabled()) log.debug("Skipping delete on cleanup: "+fromServer+":"+relativePath);
                    return false;
                }
            }
            for(String minorVersion : replicatedMySQLMinorVersions) {
                if(relativePath.equals("/var/lock/subsys/mysql-"+minorVersion)) {
                    if(log.isDebugEnabled()) log.debug("Skipping delete on cleanup: "+fromServer+":"+relativePath);
                    return false;
                }
            }
        }
        return true;
    }

    private static void cleanAndRecycleBackups(short retention, UnixFile serverRootUF, Stat tempStat, short fromServerYear, short fromServerMonth, short fromServerDay) {
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
                String[] list = serverRootUF.list();
                directoriesByAge = new HashMap<Integer,List<String>>(list.length*4/3 + 1);
                if(list!=null) {
                    for(String filename : list) {
                        if(!filename.endsWith(SAFE_DELETE_EXTENSION) && !filename.endsWith(RECYCLED_EXTENSION)) {
                            if(filename.length()>=10) {
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
                                            long ageL = (fromServerDate-cal.getTimeInMillis())/(24l*60*60*1000);
                                            if(ageL<Integer.MIN_VALUE || ageL>Integer.MAX_VALUE) throw new IOException("Can't convert long to int without loss of data: "+ageL);
                                            int age = (int)ageL;
                                            if(age>=0) {
                                                List<String> directories = directoriesByAge.get(age);
                                                if(directories==null) directoriesByAge.put(age, directories=new ArrayList<String>());
                                                directories.add(filename);
                                            } else {
                                                AOServDaemon.reportWarning(new IOException("Directory date in future: "+filename), null);
                                            }
                                        } else {
                                            AOServDaemon.reportWarning(new IOException("Unable to parse filename: "+filename), null);
                                        }
                                    } else {
                                        AOServDaemon.reportWarning(new IOException("Unable to parse filename: "+filename), null);
                                    }
                                } catch(NumberFormatException err) {
                                    AOServDaemon.reportWarning(new IOException("Unable to parse filename: "+filename), null);
                                }
                            } else {
                                AOServDaemon.reportWarning(new IOException("Filename too short: "+filename), null);
                            }
                        }
                    }
                }
            }

            if(log.isDebugEnabled()) {
                List<Integer> ages = new ArrayList<Integer>(directoriesByAge.keySet());
                Collections.sort(ages);
                for(Integer age : ages) {
                    List<String> directories = directoriesByAge.get(age);
                    for(String directory : directories) {
                        log.debug(age + ": " + directory);
                    }
                }
            }

            // These will be marked for deletion first, recycled where possible, then actually deleted if not recycled
            List<String> deleteFilenames = new ArrayList<String>();

            boolean lastHasSuccess = true;
            if(retention<=7) {
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
                            delete(serverRootUF, directoriesByAge, age, deleteFilenames);
                            directoriesByAge.remove(age);
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
                                    if(hasComplete(directoriesByAge, age, deleteFilenames)) {
                                        foundSuccessful = true;
                                        lastHasSuccess = true;
                                        for(int deleteAge = age+1;deleteAge<currentLevel;deleteAge++) {
                                            if(directoriesByAge.containsKey(deleteAge)) {
                                                delete(serverRootUF, directoriesByAge, deleteAge, deleteFilenames);
                                                directoriesByAge.remove(deleteAge);
                                            }
                                        }
                                    }
                                } else {
                                    if(directoriesByAge.containsKey(age)) {
                                        delete(serverRootUF, directoriesByAge, age, deleteFilenames);
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
            List<Integer> ages = new ArrayList<Integer>(directoriesByAge.keySet());
            Collections.sort(ages);
            for(Integer age : ages) {
                if(age>=retention) {
                    if(!foundSuccessful) {
                        if(hasComplete(directoriesByAge, age, deleteFilenames)) {
                            foundSuccessful = true;
                        }
                    } else {
                        if(directoriesByAge.containsKey(age)) {
                            delete(serverRootUF, directoriesByAge, age, deleteFilenames);
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
                    if(log.isDebugEnabled()) log.debug("Renaming "+currentUF.getFilename()+" to "+newUF.getFilename());
                    if(newUF.getStat(tempStat).exists()) throw new IOException("newUF exists: "+newUF.getFilename());
                    currentUF.renameTo(newUF);
                } else {
                    // 2) Flag all those that where not completed directly as .deleted, schedule for delete
                    if(!directory.endsWith(SAFE_DELETE_EXTENSION)) {
                        final UnixFile currentUF = new UnixFile(serverRootUF, directory, false);
                        final UnixFile newUF = new UnixFile(serverRootUF, directory+SAFE_DELETE_EXTENSION, false);
                        if(log.isDebugEnabled()) log.debug("Renaming "+currentUF.getFilename()+" to "+newUF.getFilename());
                        if(newUF.getStat(tempStat).exists()) throw new IOException("newUF exists: "+newUF.getFilename());
                        currentUF.renameTo(newUF);
                    }
                }
            }

            // 3) Keep X most recent .recycled directories (not partials, though)
            // 4) Rename older .recycled directories to .deleted
            {
                final int numRecycle = getNumberRecycleDirectories(retention);
                String[] list = serverRootUF.list();
                if(list!=null && list.length>0) {
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
                                if(log.isDebugEnabled()) log.debug("Renaming "+currentUF.getFilename()+" to "+newUF.getFilename());
                                if(newUF.getStat(tempStat).exists()) throw new IOException("newUF exists: "+newUF.getFilename());
                                currentUF.renameTo(newUF);
                            }
                        }
                    }
                }
            }
            
            // 5) Delete all those that end in .deleted in one background rm call, from oldest to newest
            if(!SAFE_DELETE) {
                String[] list = serverRootUF.list();
                if(list!=null && list.length>0) {
                    Arrays.sort(list);
                    List<String> command = new ArrayList<String>();
                    command.add("/bin/rm");
                    command.add("-rf");
                    boolean found = false;
                    for(int c=0;c<list.length;c++) {
                        String directory = list[c];
                        if(directory.endsWith(SAFE_DELETE_EXTENSION)) {
                            found=true;
                            String fullPath = new UnixFile(serverRootUF, directory, false).getFilename();
                            if(log.isDebugEnabled()) log.debug("Deleting: "+fullPath);
                            command.add(fullPath);
                        }
                    }
                    if(found) {
                        final String[] cmd = command.toArray(new String[command.size()]);
                        // Delete in the background using rm -rf
                        (
                            new Thread() {
                                public void run() {
                                    try {
                                        AOServDaemon.exec(cmd);
                                    } catch(IOException err) {
                                        AOServDaemon.reportError(err, null);
                                    }
                                }
                            }
                        ).start();
                    }
                }
            }
        } catch(RuntimeException err) {
            AOServDaemon.reportError(err, null);
        } catch(IOException err) {
            AOServDaemon.reportError(err, null);
        }
    }

    private static boolean hasComplete(Map<Integer,List<String>> directoriesByAge, int age, List<String> deleteFilenames) {
        Profiler.startProfile(Profiler.UNKNOWN, FailoverFileReplicationManager.class, "hasComplete(Map<Integer,List<String>>,int,List<String>)", null);
        try {
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
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    private static void delete(UnixFile serverRootUF, Map<Integer,List<String>> directoriesByAge, int age, List<String> deleteFilenames) throws IOException {
        Profiler.startProfile(Profiler.UNKNOWN, FailoverFileReplicationManager.class, "delete(UnixFile,Map<Integer,List<String>>,int,List<String>)", null);
        try {
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
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    public static void start() throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, FailoverFileReplicationManager.class, "start()", null);
        try {
            if(
                AOServDaemonConfiguration.isManagerEnabled(FailoverFileReplicationManager.class)
                && (
                    backupThread==null
                    || failoverThread==null
                )
            ) {
                synchronized(System.out) {
                    if(backupThread==null || failoverThread==null) {
                        if(backupThread==null) {
                            System.out.print("Starting FailoverFileReplicationManager (Backups): ");
                            backupThread=new Thread(
                                new Runnable() {
                                    public void run() {
                                        runBackups();
                                    }
                                }
                            );
                            backupThread.start();
                            System.out.println("Done");
                        }
                        if(failoverThread==null) {
                            System.out.print("Starting FailoverFileReplicationManager (Failovers): ");
                            failoverThread=new Thread(
                                new Runnable() {
                                    public void run() {
                                        runFailovers();
                                    }
                                }
                            );
                            failoverThread.start();
                            System.out.println("Done");
                        }
                    }
                }
            }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }
    
    private static void runFailovers() {
        Profiler.startProfile(Profiler.UNKNOWN, FailoverFileReplicationManager.class, "runFailovers()", null);
        try {
            run(false);
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    private static void runBackups() {
        Profiler.startProfile(Profiler.UNKNOWN, FailoverFileReplicationManager.class, "runBackups()", null);
        try {
            run(true);
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    private static void run(boolean backupMode) {
        Profiler.startProfile(Profiler.UNKNOWN, FailoverFileReplicationManager.class, "run(boolean)", null);
        try {
            boolean isStartup = true;
            Map<FailoverFileReplication,Boolean> lastFaileds = new HashMap<FailoverFileReplication,Boolean>();
            while(true) {
                try {
                    while(true) {
                        try {
                            int minutes;
                            if(isStartup) {
                                minutes = 1;
                                isStartup = false;
                            } else minutes = 15;
                            if(log.isDebugEnabled()) log.debug((backupMode ? "Backup: " : "Failover: ") + "Sleeping for "+minutes+" minutes");
                            Thread.sleep(minutes*60L*1000L);
                        } catch(InterruptedException err) {
                            AOServDaemon.reportWarning(err, null);
                        }
                        AOServer thisServer=AOServDaemon.getThisAOServer();
                        if(log.isDebugEnabled()) log.debug((backupMode ? "Backup: " : "Failover: ") + "thisServer="+thisServer);
                        AOServer failoverServer=thisServer.getFailoverServer();
                        if(log.isDebugEnabled()) log.debug((backupMode ? "Backup: " : "Failover: ") + "failoverServer="+failoverServer);
                        List<FailoverFileReplication> ffrs=new ArrayList<FailoverFileReplication>(thisServer.getFailoverFileReplications());

                        /*
                         * Sorts the replications by bandwidth decreasing, with -1 meaning unlimited bandwidth.  This
                         * causes the fastest (and thus more local, better snapshots, and more likely to complete quickly)
                         * to be processed first when multiple replications are due for a pass.
                         */
                        Collections.sort(
                            ffrs,
                            new Comparator<FailoverFileReplication>() {
                                public int compare(FailoverFileReplication ffr1, FailoverFileReplication ffr2) {
                                    int rate1=ffr1.getBitRate();
                                    int rate2=ffr2.getBitRate();
                                    if(rate1==-1) return (rate2==-1) ? 0 : -1;
                                    else return (rate2==-1) ? 1 : (rate2-rate1);
                                }
                            }
                        );

                        for(int c=0;c<ffrs.size();c++) {
                            // Try the next server if an error occurs
                            FailoverFileReplication ffr=ffrs.get(c);
                            if( ffr.getEnabled() && (ffr.getRetention().getDays()!=1) == backupMode) {
                                if(log.isDebugEnabled()) log.debug((backupMode ? "Backup: " : "Failover: ") + "ffr="+ffr);
                                try {
                                    // Will not replicate if the to server is our parent server in failover mode
                                    AOServer toServer=ffr.getToAOServer();
                                    if(log.isDebugEnabled()) log.debug((backupMode ? "Backup: " : "Failover: ") + "toServer="+toServer);
                                    if(!toServer.equals(failoverServer)) {
                                        // Find the most recent successful failover pass
                                        List<FailoverFileLog> logs = ffr.getFailoverFileLogs(1);
                                        // These are sorted most recent on the bottom
                                        FailoverFileLog lastLog = logs.isEmpty() ? null : logs.get(0);
                                        if(log.isDebugEnabled()) log.debug((backupMode ? "Backup: " : "Failover: ") + "lastLog="+lastLog);
                                        // Should it run now?
                                        // Is it a regularly scheduled time to run?
                                        int hour=Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
                                        boolean isScheduled=false;
                                        for(FailoverFileSchedule ffs : ffr.getFailoverFileSchedules()) {
                                            if(ffs.isEnabled() && ffs.getHour()==hour) {
                                                isScheduled=true;
                                                break;
                                            }
                                        }
                                        boolean shouldRun = false;
                                        if(
                                            // The last attempt at this mirror failed
                                            (lastFaileds.containsKey(ffr) && lastFaileds.get(ffr))
                                        ) {
                                            shouldRun = true;
                                            if(log.isDebugEnabled()) log.debug((backupMode ? "Backup: " : "Failover: ") + "The last attempt at this mirror failed");
                                        } else {
                                            if(
                                                // Never ran this mirror
                                                lastLog==null
                                            ) {
                                                shouldRun = true;
                                                if(log.isDebugEnabled()) log.debug((backupMode ? "Backup: " : "Failover: ") + "Never ran this mirror");
                                            } else {
                                                if(
                                                    // Last pass in the log failed
                                                    !lastLog.isSuccessful()
                                                ) {
                                                    shouldRun = true;
                                                    if(log.isDebugEnabled()) log.debug((backupMode ? "Backup: " : "Failover: ") + "Last pass in the log failed");
                                                } else {
                                                    if(
                                                        // Last pass in the future (time reset)
                                                        lastLog.getStartTime() > System.currentTimeMillis()
                                                    ) {
                                                        shouldRun = true;
                                                        if(log.isDebugEnabled()) log.debug((backupMode ? "Backup: " : "Failover: ") + "Last pass in the future (time reset)");
                                                    } else {
                                                        if(
                                                            // Last pass more than 24 hours ago
                                                            (System.currentTimeMillis() - lastLog.getStartTime())>=(24*60*60*1000)
                                                        ) {
                                                            shouldRun = true;
                                                            if(log.isDebugEnabled()) log.debug((backupMode ? "Backup: " : "Failover: ") + "Last pass more than 24 hours ago");
                                                        } else {
                                                            if(
                                                                // It is the scheduled time and the last logged start time was >= MINIMUM_INTERVAL
                                                                (isScheduled && (System.currentTimeMillis()-lastLog.getStartTime())>=FailoverFileReplication.MINIMUM_INTERVAL)
                                                            ) {
                                                                shouldRun = true;
                                                                if(log.isDebugEnabled()) log.debug((backupMode ? "Backup: " : "Failover: ") + "It is the scheduled time and the last logged start time was >= MINIMUM_INTERVAL");
                                                            } else {
                                                                // TODO: Look for more specific missed schedules (down the the hour)
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                        if(shouldRun) runFailoverCopy(ffr, backupMode);
                                    }
                                    lastFaileds.put(ffr, false);
                                } catch(Exception err) {
                                    lastFaileds.put(ffr, true);
                                    AOServDaemon.reportError(err, null);
                                    try {
                                        Thread.sleep(60*1000);
                                    } catch(InterruptedException err2) {
                                        AOServDaemon.reportWarning(err2, null);
                                    }
                                }
                            }
                        }
                    }
                } catch(ThreadDeath TD) {
                    throw TD;
                } catch(Throwable T) {
                    AOServDaemon.reportError(T, null);
                    try {
                        Thread.sleep(60*1000);
                    } catch(InterruptedException err) {
                        AOServDaemon.reportWarning(err, null);
                    }
                }
            }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    private static void runFailoverCopy(FailoverFileReplication ffr, boolean backupMode) throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, FailoverFileReplicationManager.class, "runFailoverCopy(FailoverFileReplication)", null);
        try {
            AOServer thisServer=AOServDaemon.getThisAOServer();
            String hostname = thisServer.getServer().getHostname();
            final int failoverBatchSize = thisServer.getFailoverBatchSize();
            AOServer toServer=ffr.getToAOServer();
            if(log.isInfoEnabled()) log.info((backupMode ? "Backup: " : "Failover: ") + "Running failover from "+thisServer+" to "+toServer);
            ProcessTimer timer=new ProcessTimer(
                AOServDaemon.getRandom(),
                AOServDaemonConfiguration.getWarningSmtpServer(),
                AOServDaemonConfiguration.getWarningEmailFrom(),
                AOServDaemonConfiguration.getWarningEmailTo(),
                "Failover Replication Taking Too Long",
                "Failover Replication from "+hostname+" to "+toServer.getServer().getHostname(),
                6L*60*60*1000,
                6L*60*60*1000
            );
            try {
                timer.start();
                Calendar cal = Calendar.getInstance();
                final long startTime=cal.getTimeInMillis();
                // Flag that we have started
                ffr.setLastStartTime(startTime);

                boolean useCompression = ffr.getUseCompression();
                if(log.isTraceEnabled()) log.trace((backupMode ? "Backup: " : "Failover: ") + "useCompression="+useCompression);
                short retention = ffr.getRetention().getDays();
                if(log.isTraceEnabled()) log.trace((backupMode ? "Backup: " : "Failover: ") + "retention="+retention);

                // Determine which MySQL Servers are replicated (not mirrored with failover code)
                List<String> replicatedMySQLServers;
                List<String> replicatedMySQLMinorVersions;
                if(retention==1) {
                    List<FailoverMySQLReplication> fmrs = ffr.getFailoverMySQLReplications();
                    replicatedMySQLServers = new ArrayList<String>(fmrs.size());
                    replicatedMySQLMinorVersions = new ArrayList<String>(fmrs.size());
                    for(FailoverMySQLReplication fmr : fmrs) {
                        MySQLServer mysqlServer = fmr.getMySQLServer();
                        String name = mysqlServer.getName();
                        String minorVersion = mysqlServer.getMinorVersion();
                        replicatedMySQLServers.add(name);
                        replicatedMySQLMinorVersions.add(minorVersion);
                        if(log.isDebugEnabled()) {
                            log.debug("runFailoverCopy to "+toServer+", replicatedMySQLServer: "+name);
                            log.debug("runFailoverCopy to "+toServer+", replicatedMySQLMinorVersion: "+minorVersion);
                        }
                    }
                } else {
                    replicatedMySQLServers=Collections.emptyList();
                    replicatedMySQLMinorVersions=Collections.emptyList();
                    if(log.isDebugEnabled()) log.debug("runFailoverCopy to "+toServer+", retention!=1, using empty replicatedMySQLServer and replicatedMySQLMinorVersion");
                }

                // Build the skip list
                Map<String,FilesystemIteratorRule> filesystemRules=new HashMap<String,FilesystemIteratorRule>();
                Map<String,FilesystemIteratorRule> filesystemPrefixRules=new HashMap<String,FilesystemIteratorRule>();
                filesystemRules.put("/.journal", FilesystemIteratorRule.SKIP);
                filesystemRules.put("/aquota.user", FilesystemIteratorRule.SKIP);
                //filesystemRules.put("/backup", FilesystemIteratorRule.NO_RECURSE);
                filesystemRules.put("/boot/.journal", FilesystemIteratorRule.SKIP);
                filesystemRules.put("/boot/lost+found", FilesystemIteratorRule.SKIP);
                filesystemRules.put("/dev/log", FilesystemIteratorRule.SKIP);
                filesystemRules.put("/dev/pts", FilesystemIteratorRule.NO_RECURSE);
                filesystemRules.put("/dev/shm", FilesystemIteratorRule.NO_RECURSE);
                filesystemRules.put("/distro/mandrake/10.1/i586/proc", FilesystemIteratorRule.NO_RECURSE);
                filesystemRules.put("/distro/mandrake/10.1/i586/sys", FilesystemIteratorRule.NO_RECURSE);
                filesystemRules.put("/distro/mandriva/2006.0/i586/proc", FilesystemIteratorRule.NO_RECURSE);
                filesystemRules.put("/distro/mandriva/2006.0/i586/sys", FilesystemIteratorRule.NO_RECURSE);
                filesystemRules.put("/etc/mail/statistics", FilesystemIteratorRule.SKIP);

                // Don't send /etc/lilo.conf for failovers - it can cause severe problems if a nested server has its kernel RPMs upgraded
                if(retention==1) filesystemRules.put("/etc/lilo.conf", FilesystemIteratorRule.SKIP);

                filesystemRules.put(
                    "/ao",
                    new FileExistsRule(
                        new String[] {"/ao.aes128.img", "/ao.aes256.img", "/ao.copy.aes128.img", "/ao.copy.aes256.img"},
                        FilesystemIteratorRule.NO_RECURSE,
                        FilesystemIteratorRule.OK
                    )
                );
                filesystemRules.put(
                    "/ao.copy",
                    new FileExistsRule(
                        new String[] {"/ao.aes128.img", "/ao.aes256.img", "/ao.copy.aes128.img", "/ao.copy.aes256.img"},
                        FilesystemIteratorRule.NO_RECURSE,
                        FilesystemIteratorRule.OK
                    )
                );
                filesystemRules.put(
                    "/encrypted",
                    new FileExistsRule(
                        new String[] {"/encrypted.aes128.img", "/encrypted.aes256.img"},
                        FilesystemIteratorRule.NO_RECURSE,
                        FilesystemIteratorRule.OK
                    )
                );
                filesystemRules.put(
                    "/home",
                    new FileExistsRule(
                        new String[] {"/home.aes128.img", "/home.aes256.img"},
                        FilesystemIteratorRule.NO_RECURSE,
                        FilesystemIteratorRule.OK
                    )
                );
                filesystemRules.put(
                    "/logs",
                    new FileExistsRule(
                        new String[] {"/logs.aes128.img", "/logs.aes256.img"},
                        FilesystemIteratorRule.NO_RECURSE,
                        FilesystemIteratorRule.OK
                    )
                );
                filesystemRules.put("/lost+found", FilesystemIteratorRule.SKIP);
                filesystemRules.put("/mnt/cdrom", FilesystemIteratorRule.SKIP);
                filesystemRules.put("/mnt/floppy", FilesystemIteratorRule.SKIP);
                filesystemRules.put("/proc", FilesystemIteratorRule.NO_RECURSE);
                filesystemRules.put("/swapfile.aes128.img", FilesystemIteratorRule.SKIP);
                filesystemRules.put("/swapfile.aes256.img", FilesystemIteratorRule.SKIP);
                filesystemRules.put("/sys", FilesystemIteratorRule.NO_RECURSE);
                filesystemRules.put("/tmp", FilesystemIteratorRule.NO_RECURSE);
                filesystemRules.put("/usr/tmp", FilesystemIteratorRule.NO_RECURSE);
                filesystemRules.put("/var/apache-mm", FilesystemIteratorRule.NO_RECURSE);
                filesystemRules.put("/var/backup", FilesystemIteratorRule.NO_RECURSE);
                filesystemRules.put("/var/backup1", FilesystemIteratorRule.NO_RECURSE);
                filesystemRules.put("/var/backup2", FilesystemIteratorRule.NO_RECURSE);
                filesystemRules.put("/var/backup3", FilesystemIteratorRule.NO_RECURSE);
                filesystemRules.put("/var/backup4", FilesystemIteratorRule.NO_RECURSE);
                filesystemRules.put("/var/failover", FilesystemIteratorRule.NO_RECURSE);
                filesystemRules.put(
                    "/var/cvs",
                    new FileExistsRule(
                        new String[] {"/var/cvs.aes128.img", "/var/cvs.aes256.img"},
                        FilesystemIteratorRule.NO_RECURSE,
                        FilesystemIteratorRule.OK
                    )
                );
                filesystemRules.put("/var/lib/mysql/.journal", FilesystemIteratorRule.SKIP);
                filesystemRules.put("/var/lib/mysql/4.0/"+hostname+".pid", FilesystemIteratorRule.SKIP);
                filesystemRules.put("/var/lib/mysql/4.1/"+hostname+".pid", FilesystemIteratorRule.SKIP);
                filesystemRules.put("/var/lib/mysql/5.0/"+hostname+".pid", FilesystemIteratorRule.SKIP);
                filesystemPrefixRules.put("/var/lib/mysql/5.0/mysql-bin.", FilesystemIteratorRule.SKIP);
                filesystemPrefixRules.put("/var/lib/mysql/5.0/relay-log.", FilesystemIteratorRule.SKIP);

                // Skip files for any MySQL Server that is being replicated through MySQL replication
                for(String name : replicatedMySQLServers) {
                    String path = "/var/lib/mysql/"+name;
                    filesystemRules.put(path, FilesystemIteratorRule.SKIP);
                    if(log.isDebugEnabled()) log.debug("runFailoverCopy to "+toServer+", added skip rule for "+path);
                }
                filesystemRules.put(
                    "/var/lib/pgsql",
                    new FileExistsRule(
                        new String[] {"/var/lib/pgsql.aes128.img", "/var/lib/pgsql.aes256.img"},
                        FilesystemIteratorRule.NO_RECURSE,
                        FilesystemIteratorRule.OK
                    )
                );
                filesystemRules.put("/var/lib/pgsql/.journal", FilesystemIteratorRule.SKIP);
                filesystemRules.put("/var/lib/pgsql/7.1/postmaster.pid", FilesystemIteratorRule.SKIP);
                filesystemRules.put("/var/lib/pgsql/7.2/postmaster.pid", FilesystemIteratorRule.SKIP);
                filesystemRules.put("/var/lib/pgsql/7.3/postmaster.pid", FilesystemIteratorRule.SKIP);
                filesystemRules.put("/var/lib/pgsql/8.0/postmaster.pid", FilesystemIteratorRule.SKIP);
                filesystemRules.put("/var/lib/pgsql/8.1/postmaster.pid", FilesystemIteratorRule.SKIP);
                filesystemRules.put("/var/lib/sasl2/saslauthd.pid", FilesystemIteratorRule.SKIP);
                filesystemRules.put("/var/lib/sasl2/mux.accept", FilesystemIteratorRule.SKIP);
                filesystemRules.put("/var/lock/subsys/aoserv-daemon", FilesystemIteratorRule.SKIP);
                filesystemRules.put("/var/lock/subsys/aoserv-jilter", FilesystemIteratorRule.SKIP);
                filesystemRules.put("/var/lock/subsys/clear_jvm_stats", FilesystemIteratorRule.SKIP);
                filesystemRules.put("/var/lock/subsys/clear_postgresql_pid", FilesystemIteratorRule.SKIP);
                filesystemRules.put("/var/lock/subsys/crond", FilesystemIteratorRule.SKIP);
                filesystemRules.put("/var/lock/subsys/daemons", FilesystemIteratorRule.SKIP);
                filesystemRules.put("/var/lock/subsys/kheader", FilesystemIteratorRule.SKIP);
                filesystemRules.put("/var/lock/subsys/httpd", FilesystemIteratorRule.SKIP);
                filesystemRules.put("/var/lock/subsys/httpd1", FilesystemIteratorRule.SKIP);
                filesystemRules.put("/var/lock/subsys/httpd2", FilesystemIteratorRule.SKIP);
                filesystemRules.put("/var/lock/subsys/httpd3", FilesystemIteratorRule.SKIP);
                filesystemRules.put("/var/lock/subsys/httpd4", FilesystemIteratorRule.SKIP);
                filesystemRules.put("/var/lock/subsys/httpd5", FilesystemIteratorRule.SKIP);
                filesystemRules.put("/var/lock/subsys/httpd6", FilesystemIteratorRule.SKIP);
                filesystemRules.put("/var/lock/subsys/httpd7", FilesystemIteratorRule.SKIP);
                filesystemRules.put("/var/lock/subsys/httpd8", FilesystemIteratorRule.SKIP);
                filesystemRules.put("/var/lock/subsys/identd", FilesystemIteratorRule.SKIP);
                filesystemRules.put("/var/lock/subsys/local", FilesystemIteratorRule.SKIP);
                filesystemRules.put("/var/lock/subsys/messagebus", FilesystemIteratorRule.SKIP);
                filesystemRules.put("/var/lock/subsys/mysql-4.0-max", FilesystemIteratorRule.SKIP);
                filesystemRules.put("/var/lock/subsys/mysql-4.1", FilesystemIteratorRule.SKIP);
                filesystemRules.put("/var/lock/subsys/mysql-4.1-max", FilesystemIteratorRule.SKIP);
                filesystemRules.put("/var/lock/subsys/mysql-5.0", FilesystemIteratorRule.SKIP);
                filesystemRules.put("/var/lock/subsys/mysql-5.0-max", FilesystemIteratorRule.SKIP);
                filesystemRules.put("/var/lock/subsys/network", FilesystemIteratorRule.SKIP);
                filesystemRules.put("/var/lock/subsys/numlock", FilesystemIteratorRule.SKIP);
                filesystemRules.put("/var/lock/subsys/postgresql-7.1", FilesystemIteratorRule.SKIP);
                filesystemRules.put("/var/lock/subsys/postgresql-7.2", FilesystemIteratorRule.SKIP);
                filesystemRules.put("/var/lock/subsys/postgresql-7.3", FilesystemIteratorRule.SKIP);
                filesystemRules.put("/var/lock/subsys/postgresql-8.0", FilesystemIteratorRule.SKIP);
                filesystemRules.put("/var/lock/subsys/postgresql-8.1", FilesystemIteratorRule.SKIP);
                filesystemRules.put("/var/lock/subsys/proftpd", FilesystemIteratorRule.SKIP);
                filesystemRules.put("/var/lock/subsys/route", FilesystemIteratorRule.SKIP);
                filesystemRules.put("/var/lock/subsys/saslauthd", FilesystemIteratorRule.SKIP);
                filesystemRules.put("/var/lock/subsys/sendmail", FilesystemIteratorRule.SKIP);
                filesystemRules.put("/var/lock/subsys/sm-client", FilesystemIteratorRule.SKIP);
                filesystemRules.put("/var/lock/subsys/spamd", FilesystemIteratorRule.SKIP);
                filesystemRules.put("/var/lock/subsys/spamassassin", FilesystemIteratorRule.SKIP);
                filesystemRules.put("/var/lock/subsys/sshd1", FilesystemIteratorRule.SKIP);
                filesystemRules.put("/var/lock/subsys/syslog", FilesystemIteratorRule.SKIP);
                filesystemRules.put("/var/lock/subsys/xfs", FilesystemIteratorRule.SKIP);
                filesystemRules.put("/var/lock/subsys/xinetd", FilesystemIteratorRule.SKIP);
                filesystemRules.put("/var/lock/subsys/xvfb", FilesystemIteratorRule.SKIP);

                List<HttpdServer> httpdServers = thisServer.getHttpdServers();
                for(HttpdServer hs : httpdServers) {
                    filesystemRules.put("/var/log/httpd"+hs.getNumber()+"/ssl_scache.sem", FilesystemIteratorRule.SKIP);
                }
                filesystemRules.put("/var/oldaccounts", FilesystemIteratorRule.NO_RECURSE);
                filesystemRules.put("/var/run/aoserv-daemon-java.pid", FilesystemIteratorRule.SKIP);
                filesystemRules.put("/var/run/aoserv-daemon.pid", FilesystemIteratorRule.SKIP);
                filesystemRules.put("/var/run/crond.pid", FilesystemIteratorRule.SKIP);
                for(HttpdServer hs : httpdServers) {
                    filesystemRules.put("/var/run/httpd"+hs.getNumber()+".pid", FilesystemIteratorRule.SKIP);
                }
                filesystemRules.put("/var/run/identd.pid", FilesystemIteratorRule.SKIP);
                filesystemRules.put("/var/run/klogd.pid", FilesystemIteratorRule.SKIP);
                filesystemRules.put("/var/run/proftpd.pid", FilesystemIteratorRule.SKIP);
                filesystemRules.put("/var/run/proftpd/proftpd.scoreboard", FilesystemIteratorRule.SKIP);
                filesystemRules.put("/var/run/runlevel.dir", FilesystemIteratorRule.SKIP);
                filesystemRules.put("/var/run/sendmail.pid", FilesystemIteratorRule.SKIP);
                filesystemRules.put("/var/run/sm-client.pid", FilesystemIteratorRule.SKIP);
                filesystemRules.put("/var/run/sshd.pid", FilesystemIteratorRule.SKIP);
                filesystemRules.put("/var/run/syslogd.pid", FilesystemIteratorRule.SKIP);
                filesystemRules.put("/var/run/xfs.pid", FilesystemIteratorRule.SKIP);
                filesystemRules.put("/var/run/xinetd.pid", FilesystemIteratorRule.SKIP);
                filesystemRules.put("/var/run/xvfb.pid", FilesystemIteratorRule.SKIP);
                filesystemRules.put(
                    "/var/spool",
                    new FileExistsRule(
                        new String[] {"/var/spool.aes128.img", "/var/spool.aes256.img"},
                        FilesystemIteratorRule.NO_RECURSE,
                        FilesystemIteratorRule.OK
                    )
                );
                if(backupMode) filesystemRules.put("/var/spool/clientmqueue", FilesystemIteratorRule.NO_RECURSE);
                filesystemRules.put("/var/spool/clientmqueue/sm-client.pid", FilesystemIteratorRule.SKIP);
                if(backupMode) filesystemRules.put("/var/spool/mqueue", FilesystemIteratorRule.NO_RECURSE);
                filesystemRules.put("/var/tmp", FilesystemIteratorRule.NO_RECURSE);
                filesystemRules.put(
                    "/www",
                    new FileExistsRule(
                        new String[] {"/www.aes128.img", "/www.aes256.img"},
                        FilesystemIteratorRule.NO_RECURSE,
                        FilesystemIteratorRule.OK
                    )
                );
                filesystemRules.put("/www/.journal", FilesystemIteratorRule.SKIP);
                filesystemRules.put("/www/aquota.user", FilesystemIteratorRule.SKIP);
                filesystemRules.put("/www/lost+found", FilesystemIteratorRule.SKIP);
                // Do not replicate the backup directories
                for(BackupPartition bp : thisServer.getBackupPartitions()) {
                    filesystemRules.put(bp.getPath(), FilesystemIteratorRule.NO_RECURSE);
                }
                // TODO: Add additional rules based on backup settings (from aoserv tables)
                // TODO: Perhaps even put ALL rules in the database, with a default set for all servers
                // TODO: Matching the set hard-coded here

                // Keep statistics during the replication
                int scanned=0;
                // TODO: int added=0;
                int updated=0;
                // TODO: int removed=0;
                long rawBytesOut=0;
                long rawBytesIn=0;
                boolean isSuccessful=false;
                try {
                    // Get the connection to the daemon
                    long key=toServer.requestDaemonAccess(AOServDaemonProtocol.FAILOVER_FILE_REPLICATION, ffr.getPKey());
                    // Allow the failover connect address to be overridden
                    String ffrConnectAddress = ffr.getConnectAddress();
                    // Allow the address to be overridden
                    String daemonConnectAddress=toServer.getDaemonConnectAddress();
                    String connectAddress = ffrConnectAddress!=null ? ffrConnectAddress : daemonConnectAddress!=null ? daemonConnectAddress : toServer.getDaemonIPAddress().getIPAddress();
                    NetBind daemonBind = toServer.getDaemonConnectBind();
                    NetPort daemonBindPort = daemonBind.getPort();
                    Protocol daemonBindProtocol = daemonBind.getAppProtocol();

                    // First, the specific source address from ffr is used
                    String sourceIPAddress = ffr.getConnectFrom();
                    if(sourceIPAddress==null) {
                        // Next, it will use the daemon bind address
                        sourceIPAddress = thisServer.getDaemonBind().getIPAddress().getIPAddress();
                        // If daemon is binding to wildcard, then use source IP address of primary IP
                        if(sourceIPAddress.equals(IPAddress.WILDCARD_IP)) sourceIPAddress = thisServer.getPrimaryIPAddress().getIPAddress();
                    }

                    AOServDaemonConnector daemonConnector=AOServDaemonConnector.getConnector(
                        toServer.getServer().getPKey(),
                        connectAddress,
                        sourceIPAddress,
                        daemonBindPort.getPort(),
                        daemonBindProtocol.getProtocol(),
                        null,
                        toServer.getPoolSize(),
                        AOPool.DEFAULT_MAX_CONNECTION_AGE,
                        SSLConnector.class,
                        SSLConnector.sslProviderLoaded,
                        AOServClientConfiguration.getSslTruststorePath(),
                        AOServClientConfiguration.getSslTruststorePassword(),
                        AOServDaemon.getErrorHandler()
                    );
                    AOServDaemonConnection daemonConn=daemonConnector.getConnection();
                    try {
                        // Start the replication
                        CompressedDataOutputStream rawOut=daemonConn.getOutputStream();

                        MD5 md5 = useCompression ? new MD5() : null;

                        rawOut.writeCompressedInt(AOServDaemonProtocol.FAILOVER_FILE_REPLICATION);
                        rawOut.writeLong(key);
                        rawOut.writeBoolean(useCompression);
                        rawOut.writeShort(retention);

                        // Determine the date on the from server
                        final int year = cal.get(Calendar.YEAR);
                        final int month = cal.get(Calendar.MONTH)+1;
                        final int day = cal.get(Calendar.DAY_OF_MONTH);
                        rawOut.writeShort(year);
                        rawOut.writeShort(month);
                        rawOut.writeShort(day);
                        if(retention==1) {
                            int len = replicatedMySQLServers.size();
                            rawOut.writeCompressedInt(len);
                            for(int c=0;c<len;c++) {
                                rawOut.writeUTF(replicatedMySQLServers.get(c));
                                rawOut.writeUTF(replicatedMySQLMinorVersions.get(c));
                            }
                        }
                        rawOut.flush();

                        CompressedDataInputStream rawIn=daemonConn.getInputStream();
                        int result=rawIn.read();
                        if(result==AOServDaemonProtocol.NEXT) {
                            // Setup Compression and/or bandwidth limiting
                            CompressedDataOutputStream out;
                            CompressedDataInputStream in;
                            ByteCountOutputStream rawBytesOutStream;
                            ByteCountInputStream rawBytesInStream;

                            if(ffr.getBitRate()!=-1) {
                                // Only the output is limited because input should always be smaller than the output
                                out = new CompressedDataOutputStream(
                                    /*bytesOutStream =*/ rawBytesOutStream = new ByteCountOutputStream(
                                        new BitRateOutputStream(
                                            rawOut,
                                            ffr
                                        )
                                    )
                                );
                            } else {
                                out = new CompressedDataOutputStream(
                                    /*bytesOutStream =*/ rawBytesOutStream = new ByteCountOutputStream(
                                        rawOut
                                    )
                                );
                            }
                            in = new CompressedDataInputStream(
                                /*bytesInStream =*/ rawBytesInStream = new ByteCountInputStream(
                                    rawIn
                                )
                            );
                            try {
                                FilesystemIterator fileIterator=new FilesystemIterator(filesystemRules, filesystemPrefixRules, CONVERT_PATHS_TO_ASCII);

                                // Do requests in batches
                                FilesystemIteratorResult[] filesystemIteratorResults=new FilesystemIteratorResult[failoverBatchSize];
                                int[] results=new int[failoverBatchSize];
                                long[][] md5His = useCompression ? new long[failoverBatchSize][] : null;
                                long[][] md5Los = useCompression ? new long[failoverBatchSize][] : null;
                                byte[] buff=BufferManager.getBytes();
                                byte[] chunkBuffer = new byte[CHUNK_SIZE];
                                final Stat ufStat = new Stat();
                                try {
                                    while(true) {
                                        int batchSize=fileIterator.getNextResults(filesystemIteratorResults, failoverBatchSize);
                                        if(batchSize==0) break;

                                        out.writeCompressedInt(batchSize);
                                        for(int d=0;d<batchSize;d++) {
                                            scanned++;
                                            FilesystemIteratorResult filesystemIteratorResult = filesystemIteratorResults[d];
                                            UnixFile uf=filesystemIteratorResult.getUnixFile();
                                            uf.getStat(ufStat);
                                            String convertedFilename = CONVERT_PATHS_TO_ASCII ? filesystemIteratorResult.getConvertedFilename() : null;
                                            if(CONVERT_PATHS_TO_ASCII && !convertedFilename.equals(uf.getFilename())) {
                                                AOServDaemon.reportWarning(
                                                    new IOException("Warning, filename converted during replication"),
                                                    new Object[] {
                                                        "originalFilename ="+uf.getFilename(),
                                                        "convertedFilename="+convertedFilename
                                                    }
                                                );
                                            }
                                            try {
                                                long mode=ufStat.getRawMode();
                                                if(!UnixFile.isSocket(mode)) {
                                                    // Get all the values first to avoid FileNotFoundException in middle of protocol
                                                    String filename=uf.getFilename();
                                                    boolean isRegularFile=UnixFile.isRegularFile(mode);
                                                    long size=isRegularFile?ufStat.getSize():-1;
                                                    int uid=ufStat.getUID();
                                                    int gid=ufStat.getGID();
                                                    boolean isSymLink=UnixFile.isSymLink(mode);
                                                    long modifyTime=isSymLink?-1:ufStat.getModifyTime();
                                                    //if(modifyTime<1000 && !isSymLink && log.isWarnEnabled()) log.warn("Non-symlink modifyTime<1000: "+filename+": "+modifyTime);
                                                    String symLinkTarget;
                                                    if(isSymLink) {
                                                        try {
                                                            symLinkTarget = uf.readLink();
                                                        } catch(SecurityException err) {
                                                            System.err.println("SecurityException trying to readlink: "+uf.getFilename());
                                                            throw err;
                                                        } catch(IOException err) {
                                                            System.err.println("IOException trying to readlink: "+uf.getFilename());
                                                            throw err;
                                                        }
                                                    } else {
                                                        symLinkTarget = null;
                                                    }
                                                    boolean isDevice=UnixFile.isBlockDevice(mode) || UnixFile.isCharacterDevice(mode);
                                                    long deviceID=isDevice?ufStat.getDeviceIdentifier():-1;

                                                    out.writeBoolean(true);
                                                    out.writeCompressedUTF(CONVERT_PATHS_TO_ASCII ? convertedFilename : filename, 0);
                                                    out.writeLong(mode);
                                                    if(UnixFile.isRegularFile(mode)) out.writeLong(size);
                                                    if(uid<0 || uid>65535) {
                                                        AOServDaemon.reportWarning(new IOException("UID out of range, converted to 0"), new Object[] {"uid="+uid, "path="+filename});
                                                        uid=0;
                                                    }
                                                    out.writeCompressedInt(uid);
                                                    if(gid<0 || gid>65535) {
                                                        AOServDaemon.reportWarning(new IOException("GID out of range, converted to 0"), new Object[] {"gid="+gid, "path="+filename});
                                                        gid=0;
                                                    }
                                                    out.writeCompressedInt(gid);
                                                    if(!isSymLink) out.writeLong(modifyTime);
                                                    if(isSymLink) out.writeCompressedUTF(symLinkTarget, 1);
                                                    else if(isDevice) out.writeLong(deviceID);
                                                } else {
                                                    filesystemIteratorResults[d]=null;
                                                    out.writeBoolean(false);
                                                }
                                            } catch(FileNotFoundException err) {
                                                // Normal because of a dynamic file system
                                                filesystemIteratorResults[d]=null;
                                                out.writeBoolean(false);
                                            }
                                        }
                                        out.flush();

                                        // Read the results
                                        result=in.read();
                                        boolean hasRequestData = false;
                                        if(result==AOServDaemonProtocol.NEXT) {
                                            for(int d=0;d<batchSize;d++) {
                                                if(filesystemIteratorResults[d]!=null) {
                                                    result = in.read();
                                                    results[d]=result;
                                                    if(result==MODIFIED_REQUEST_DATA) {
                                                        hasRequestData = true;
                                                    } else if(result==MODIFIED_REQUEST_DATA_CHUNKED) {
                                                        hasRequestData = true;
                                                        int chunkCount = in.readCompressedInt();
                                                        long[] md5Hi = md5His[d] = new long[chunkCount];
                                                        long[] md5Lo = md5Los[d] = new long[chunkCount];
                                                        for(int e=0;e<chunkCount;e++) {
                                                            md5Hi[e]=in.readLong();
                                                            md5Lo[e]=in.readLong();
                                                        }
                                                    }
                                                }
                                            }
                                        } else {
                                            if (result == AOServDaemonProtocol.IO_EXCEPTION) throw new IOException(in.readUTF());
                                            else if (result == AOServDaemonProtocol.SQL_EXCEPTION) throw new SQLException(in.readUTF());
                                            else throw new IOException("Unknown result: " + result);
                                        }

                                        // Process the results
                                        DeflaterOutputStream deflaterOut;
                                        DataOutputStream outgoing;
                                        
                                        if(hasRequestData) {
                                            deflaterOut = null;
                                            outgoing = out;
                                        } else {
                                            deflaterOut = null;
                                            outgoing = null;
                                        }
                                        for(int d=0;d<batchSize;d++) {
                                            FilesystemIteratorResult filesystemIteratorResult = filesystemIteratorResults[d];
                                            if(filesystemIteratorResult!=null) {
                                                UnixFile uf=filesystemIteratorResult.getUnixFile();
                                                result=results[d];
                                                if(result==MODIFIED) {
                                                    if(log.isTraceEnabled()) log.trace((backupMode ? "Backup: " : "Failover: ") + "File modified: "+uf.getFilename());
                                                    updated++;
                                                } else if(result==MODIFIED_REQUEST_DATA) {
                                                    updated++;
                                                    try {
                                                        if(log.isTraceEnabled()) log.trace((backupMode ? "Backup: " : "Failover: ") + "Sending file contents: "+uf.getFilename());
                                                        // Shortcut for 0 length files (don't open for reading)
                                                        if(uf.getStat(ufStat).getSize()!=0) {
                                                            InputStream fileIn=new FileInputStream(uf.getFile());
                                                            try {
                                                                int blockLen;
                                                                while((blockLen=fileIn.read(buff, 0, BufferManager.BUFFER_SIZE))!=-1) {
                                                                    if(blockLen>0) {
                                                                        outgoing.write(AOServDaemonProtocol.NEXT);
                                                                        outgoing.writeShort(blockLen);
                                                                        outgoing.write(buff, 0, blockLen);
                                                                    }
                                                                }
                                                            } finally {
                                                                fileIn.close();
                                                            }
                                                        }
                                                    } catch(FileNotFoundException err) {
                                                        // Normal when the file was deleted
                                                    } finally {
                                                        outgoing.write(AOServDaemonProtocol.DONE);
                                                    }
                                                } else if(result==MODIFIED_REQUEST_DATA_CHUNKED) {
                                                    updated++;
                                                    try {
                                                        if(log.isTraceEnabled()) log.trace((backupMode ? "Backup: " : "Failover: ") + "Chunking file contents: "+uf.getFilename());
                                                        long[] md5Hi = md5His[d];
                                                        long[] md5Lo = md5Los[d];
                                                        InputStream fileIn=new FileInputStream(uf.getFile());
                                                        try {
                                                            int chunkNumber = 0;
                                                            int sendChunkCount = 0;
                                                            while(true) {
                                                                // Read fully one chunk or to end of file
                                                                int pos=0;
                                                                while(pos<CHUNK_SIZE) {
                                                                    int ret = fileIn.read(chunkBuffer, pos, CHUNK_SIZE-pos);
                                                                    if(ret==-1) break;
                                                                    pos+=ret;
                                                                }
                                                                if(pos>0) {
                                                                    boolean sendData = true;
                                                                    if(pos==CHUNK_SIZE && chunkNumber < md5Hi.length) {
                                                                        // Calculate the MD5 hash
                                                                        md5.Init();
                                                                        md5.Update(chunkBuffer, 0, pos);
                                                                        byte[] md5Bytes = md5.Final();
                                                                        sendData = md5Hi[chunkNumber]!=MD5.getMD5Hi(md5Bytes) || md5Lo[chunkNumber]!=MD5.getMD5Lo(md5Bytes);
                                                                        if(sendData) sendChunkCount++;
                                                                    } else {
                                                                        // Either incomplete chunk or chunk past those sent by client
                                                                        sendData = true;
                                                                    }
                                                                    if(sendData) {
                                                                        outgoing.write(AOServDaemonProtocol.NEXT);
                                                                        outgoing.writeShort(pos);
                                                                        outgoing.write(chunkBuffer, 0, pos);
                                                                    } else {
                                                                        outgoing.write(AOServDaemonProtocol.NEXT_CHUNK);
                                                                    }
                                                                }
                                                                // At end of file when not complete chunk read
                                                                if(pos!=CHUNK_SIZE) break;

                                                                // Increment chunk number for next iteration
                                                                chunkNumber++;
                                                            }
                                                            if(log.isTraceEnabled()) log.trace((backupMode ? "Backup: " : "Failover: ") + "Chunking file contents: "+uf.getFilename()+": Sent "+sendChunkCount+" out of "+chunkNumber+" chunks");
                                                        } finally {
                                                            fileIn.close();
                                                        }
                                                    } catch(FileNotFoundException err) {
                                                        // Normal when the file was deleted
                                                    } finally {
                                                        outgoing.write(AOServDaemonProtocol.DONE);
                                                    }
                                                } else if(result!=NO_CHANGE) throw new IOException("Unknown result: "+result);
                                            }
                                        }

                                        // Flush any file data that was sent
                                        if(hasRequestData) {
                                            outgoing.flush();
                                        }
                                    }
                                } finally {
                                    BufferManager.release(buff);
                                }

                                // Tell the server we are finished
                                out.writeCompressedInt(-1);
                                out.flush();
                                result=in.read();
                                if(result!=AOServDaemonProtocol.DONE) {
                                    if (result == AOServDaemonProtocol.IO_EXCEPTION) throw new IOException(in.readUTF());
                                    else if (result == AOServDaemonProtocol.SQL_EXCEPTION) throw new SQLException(in.readUTF());
                                    else throw new IOException("Unknown result: " + result);
                                }
                            } finally {
                                // Store the bytes transferred
                                rawBytesOut=rawBytesOutStream.getCount();
                                rawBytesIn=rawBytesInStream.getCount();
                            }
                        } else {
                            if (result == AOServDaemonProtocol.IO_EXCEPTION) throw new IOException(rawIn.readUTF());
                            else if (result == AOServDaemonProtocol.SQL_EXCEPTION) throw new SQLException(rawIn.readUTF());
                            else throw new IOException("Unknown result: " + result);
                        }
                    } catch(IOException err) {
                        daemonConn.close();
                        throw err;
                    } catch(SQLException err) {
                        daemonConn.close();
                        throw err;
                    } catch(RuntimeException err) {
                        daemonConn.close();
                        throw err;
                    } finally {
                        daemonConnector.releaseConnection(daemonConn);
                    }
                    isSuccessful=true;
                } finally {
                    // Store the statistics
                    // TODO: Store more advanced statistics
                    ffr.addFailoverFileLog(startTime, System.currentTimeMillis(), scanned, updated, rawBytesOut+rawBytesIn, isSuccessful);
                }
            } finally {
                timer.stop();
            }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    /**
     * If the file is a regular file and is hard-linked, copies the file and renames it over the original (to break the link).
     */
    private static void copyIfHardLinked(UnixFile uf, Stat ufStat) throws IOException {
        uf.getStat(ufStat);
        if(ufStat.isRegularFile() && ufStat.getNumberLinks()>1) {
            if(log.isTraceEnabled()) log.trace("Copying file due to hard link: "+uf);
            UnixFile temp = UnixFile.mktemp(uf.getFilename()+'.');
            uf.copyTo(temp, true);
            temp.chown(ufStat.getUID(), ufStat.getGID());
            temp.setMode(ufStat.getMode());
            long atime = ufStat.getAccessTime();
            long mtime = ufStat.getModifyTime();
            temp.renameTo(uf);
            uf.utime(atime, mtime);
            uf.getStat(ufStat);
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
