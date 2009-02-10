package com.aoindustries.aoserv.daemon.failover;

/*
 * Copyright 2003-2009 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.aoserv.backup.BackupDaemon;
import com.aoindustries.aoserv.client.BackupRetention;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import com.aoindustries.aoserv.daemon.AOServDaemonConfiguration;
import com.aoindustries.aoserv.daemon.backup.AOServerEnvironment;
import com.aoindustries.aoserv.daemon.client.AOServDaemonProtocol;
import com.aoindustries.io.CompressedDataInputStream;
import com.aoindustries.io.CompressedDataOutputStream;
import com.aoindustries.io.ParallelDelete;
import com.aoindustries.io.unix.Stat;
import com.aoindustries.io.unix.UnixFile;
import com.aoindustries.md5.MD5;
import com.aoindustries.util.BufferManager;
import com.aoindustries.util.LongArrayList;
import com.aoindustries.util.LongList;
import com.aoindustries.util.WrappedException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.sql.SQLException;
import java.net.Socket;
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
//import java.util.zip.DeflaterOutputStream;
//import java.util.zip.GZIPInputStream;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * Handles the replication of data for the failover system.
 *
 * TODO: Handle hard links (pertinence space savings)
 *
 * TODO: Need to do mysqldump and postgresql dump on preBackup
 *
 * TODO: Use more compression within the protocol (perhaps to byte[] in RAM and then across the network, or an OutputStream "wrapper")
 *
 * @author  AO Industries, Inc.
 */
final public class FailoverFileReplicationManager {

    private static final Log log = LogFactory.getLog(FailoverFileReplicationManager.class);

    /**
     * When true, runs both the old and new implementations of log directory hard linking and verifies consistent behavior of the two.
     * Also times their performance.
     */
    private static final boolean USE_OLD_AND_NEW_LOG_DIRECTORY_LINKING = false;

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
     * TODO: Move this into a backup settings table.
     */
    private static final Set<String> encryptedLoopFilePaths = new HashSet<String>();
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

    private FailoverFileReplicationManager() {
    }

    /**
     * Make sure a path is absolute (starts with "/") and doesn't contain "/../"
     */
    public static void checkPath(String path) throws IOException {
        if(
            path.charAt(0)!='/'
            || path.indexOf("/../")!=-1
        ) throw new IOException("Illegal path: "+path);
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

    /**
     * Receives incoming data for a failover replication.  The critical information, such as the directory to store to,
     * has been provided by the master server because we can't trust the sending server.
     * 
     * @param toPath  the full path to the root of the backup, including any hostnames, packages, or names
     * @param quota_gid  the quota_gid or <code>-1</code> for no quotas
     */
    public static void failoverServer(
        final Socket socket,
        final CompressedDataInputStream rawIn,
        final CompressedDataOutputStream out,
        final String fromServer,
        final boolean useCompression,
        final short retention,
        final String toPath,
        final short fromServerYear,
        final short fromServerMonth,
        final short fromServerDay,
        final List<String> replicatedMySQLServers,
        final List<String> replicatedMySQLMinorVersions,
        final int quota_gid
    
    ) throws IOException {
        final PostPassChecklist postPassChecklist = new PostPassChecklist();
        try {
            if(fromServerYear<1000 || fromServerYear>9999) throw new IOException("Invalid fromServerYear (1000-9999): "+fromServerYear);
            if(fromServerMonth<1 || fromServerMonth>12) throw new IOException("Invalid fromServerMonth (1-12): "+fromServerMonth);
            if(fromServerDay<1 || fromServerDay>31) throw new IOException("Invalid fromServerDay (1-31): "+fromServerDay);

            // Make sure no / or .. in these names, so calls as root to the chroot /etc/rc.d/init.d/mysql-... restart aren't exploitable
            for(String replicatedMySQLServer : replicatedMySQLServers) {
                if(log.isDebugEnabled()) log.debug("failoverServer from \""+fromServer+"\", replicatedMySQLServer: "+replicatedMySQLServer);
                if(replicatedMySQLServer.indexOf('/')!=-1 || replicatedMySQLServer.indexOf("..")!=-1) throw new IOException("Invalid replicatedMySQLServer: "+replicatedMySQLServer);
            }
            for(String replicatedMySQLMinorVersion : replicatedMySQLMinorVersions) {
                if(log.isDebugEnabled()) log.debug("failoverServer from \""+fromServer+"\", replicatedMySQLMinorVersion: "+replicatedMySQLMinorVersion);
                if(replicatedMySQLMinorVersion.indexOf('/')!=-1 || replicatedMySQLMinorVersion.indexOf("..")!=-1) throw new IOException("Invalid replicatedMySQLMinorVersion: "+replicatedMySQLMinorVersion);
            }

            // This Stat may be used for any short-term tempStat
            final Stat tempStat = new Stat();

            // Tell the client it is OK to continue
            out.write(AOServDaemonProtocol.NEXT);
            out.flush();

            // Determine the directory that is/will be storing the mirror
            String partialMirrorRoot;
            String recycledPartialMirrorRoot;
            String finalMirrorRoot;
            String linkToRoot;
            UnixFile serverRootUF;
            boolean isRecycling;
            if(retention==1) {
                partialMirrorRoot = finalMirrorRoot = toPath;
                recycledPartialMirrorRoot = null;
                linkToRoot = null;
                serverRootUF = null;
                isRecycling = false;

                // Create the server root if it doesn't exist
                UnixFile dirUF = new UnixFile(toPath);
                dirUF.getStat(tempStat);
                if(!tempStat.exists()) {
                    dirUF.mkdir(
                        true,
                        quota_gid==-1 ? 0700 : 0750,
                        UnixFile.ROOT_UID,
                        quota_gid==-1 ? UnixFile.ROOT_GID : quota_gid
                    );
                } else if(!tempStat.isDirectory()) {
                    throw new IOException("toPath exists but is not a directory: "+toPath);
                }
            } else {
                // The directory that holds the different versions
                StringBuilder SB = new StringBuilder(toPath);
                String serverRoot = SB.toString();

                // Create the server root if it doesn't exist
                serverRootUF = new UnixFile(serverRoot);
                serverRootUF.getStat(tempStat);
                if(!tempStat.exists()) {
                    serverRootUF.mkdir(
                        true,
                        quota_gid==-1 ? 0700 : 0750,
                        UnixFile.ROOT_UID,
                        quota_gid==-1 ? UnixFile.ROOT_GID : quota_gid
                    );
                } else if(!tempStat.isDirectory()) {
                    throw new IOException("Server Root exists but is not a directory: "+serverRoot);
                }

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
                    if(log.isDebugEnabled()) log.debug("Renaming existing \""+finalMirrorRoot+"\" to \""+partialMirrorRoot+'"');
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

            CompressedDataInputStream in =
                /*useCompression
                ? new CompressedDataInputStream(new GZIPInputStream(new DontCloseInputStream(rawIn), BufferManager.BUFFER_SIZE))
                :*/ rawIn
            ;

            String[] paths=null;
            boolean[] isLogDirs=null;
            Map<UnixFile,ModifyTimeAndSizeCache> modifyTimeAndSizeCaches = new HashMap<UnixFile,ModifyTimeAndSizeCache>();
            long totalNewLogDirNanos = 0;
            long totalOldLogDirNanos = 0;
            long lastLogDirNanosDisplayTime = -1;

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
            byte[] chunkBuffer = useCompression ? new byte[AOServDaemonProtocol.FAILOVER_FILE_REPLICATION_CHUNK_SIZE] : null;
            MD5 md5 = useCompression ? new MD5() : null;
            try {
                // The extra files in directories are cleaned once the directory is done
                Stack<UnixFile> directoryUFs=new Stack<UnixFile>();
                Stack<UnixFile> directoryLinkToUFs = linkToRoot==null ? null : new Stack<UnixFile>();
                Stack<String> directoryUFRelativePaths=new Stack<String>();
                Stack<Long> directoryModifyTimes=new Stack<Long>();
                Stack<Set<String>> directoryContents=new Stack<Set<String>>();

                // The actual cleaning and modify time setting is delayed to the end of the batch by adding
                // the lists of things to do here.
                List<UnixFile> directoryFinalizeUFs = new ArrayList<UnixFile>();
                List<UnixFile> directoryFinalizeLinkToUFs = linkToRoot==null ? null : new ArrayList<UnixFile>();
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
                    if(directoryFinalizeLinkToUFs!=null) directoryFinalizeLinkToUFs.clear();
                    directoryFinalizeUFRelativePaths.clear();
                    directoryFinalizeModifyTimes.clear();
                    directoryFinalizeContents.clear();

                    for(int c=0;c<batchSize;c++) {
                        if(in.readBoolean()) {
                            if(USE_OLD_AND_NEW_LOG_DIRECTORY_LINKING && log.isInfoEnabled()) {
                                long currentTime = System.currentTimeMillis();
                                if(lastLogDirNanosDisplayTime==-1 || Math.abs(currentTime-lastLogDirNanosDisplayTime)>60000) {
                                    log.info("modifyTimeAndSizeCachesSize="+modifyTimeAndSizeCaches.size());
                                    log.info("totalNewLogDirNanos="+totalNewLogDirNanos);
                                    log.info("totalOldLogDirNanos="+totalOldLogDirNanos);
                                    lastLogDirNanosDisplayTime = currentTime;
                                }
                            }
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
                            final ModifyTimeAndSize modifyTimeAndSize = new ModifyTimeAndSize(modifyTime, length);

                            // Cleanup extra entries in completed directories, setting modifyTime on the directories
                            while(!directoryUFs.isEmpty()) {
                                UnixFile dirUF=directoryUFs.peek();
                                String dirPath=dirUF.getPath();
                                if(!dirPath.endsWith("/")) dirPath=dirPath+'/';

                                // If the current file starts with the current directory, continue
                                if(path.startsWith(dirPath)) break;

                                // Otherwise, schedule to clean and complete the directory at the end of this batch
                                directoryUFs.pop();
                                directoryFinalizeUFs.add(dirUF);
                                if(directoryFinalizeLinkToUFs!=null) directoryFinalizeLinkToUFs.add(directoryLinkToUFs.pop());
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
                                    if(log.isTraceEnabled()) log.trace("Deleting to create block device: "+uf.getPath());
                                    // Update caches
                                    long startNanos = USE_OLD_AND_NEW_LOG_DIRECTORY_LINKING ? System.nanoTime() : 0;
                                    removing(modifyTimeAndSizeCaches, uf, ufStat, ufParent);
                                    if(USE_OLD_AND_NEW_LOG_DIRECTORY_LINKING) totalNewLogDirNanos += System.nanoTime() - startNanos;
                                    // Update filesystem
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
                                    if(log.isTraceEnabled()) log.trace("Deleting to create character device: "+uf.getPath());
                                    // Update caches
                                    long startNanos = USE_OLD_AND_NEW_LOG_DIRECTORY_LINKING ? System.nanoTime() : 0;
                                    removing(modifyTimeAndSizeCaches, uf, ufStat, ufParent);
                                    if(USE_OLD_AND_NEW_LOG_DIRECTORY_LINKING) totalNewLogDirNanos += System.nanoTime() - startNanos;
                                    // Update filesystem
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
                                    if(log.isTraceEnabled()) log.trace("Deleting to create directory: "+uf.getPath());
                                    // Update caches
                                    long startNanos = USE_OLD_AND_NEW_LOG_DIRECTORY_LINKING ? System.nanoTime() : 0;
                                    removing(modifyTimeAndSizeCaches, uf, ufStat, ufParent);
                                    if(USE_OLD_AND_NEW_LOG_DIRECTORY_LINKING) totalNewLogDirNanos += System.nanoTime() - startNanos;
                                    // Update filesystem
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
                                            result=AOServDaemonProtocol.FAILOVER_FILE_REPLICATION_MODIFIED;
                                            updated(retention, postPassChecklist, relativePath);
                                        }
                                    } else {
                                        result=AOServDaemonProtocol.FAILOVER_FILE_REPLICATION_MODIFIED;
                                        updated(retention, postPassChecklist, relativePath);
                                    }
                                } else if(ufStat.getModifyTime()!=modifyTime) {
                                    result=AOServDaemonProtocol.FAILOVER_FILE_REPLICATION_MODIFIED;
                                    updated(retention, postPassChecklist, relativePath);
                                }
                                directoryUFs.push(uf);
                                if(directoryLinkToUFs!=null) directoryLinkToUFs.push(linkToUF);
                                directoryUFRelativePaths.push(relativePath);
                                directoryModifyTimes.push(Long.valueOf(modifyTime));
                                directoryContents.push(new HashSet<String>());
                            } else if(UnixFile.isFIFO(mode)) {
                                if(
                                    ufStat.exists()
                                    && !ufStat.isFIFO()
                                ) {
                                    if(log.isTraceEnabled()) log.trace("Deleting to create FIFO: "+uf.getPath());
                                    // Update caches
                                    long startNanos = USE_OLD_AND_NEW_LOG_DIRECTORY_LINKING ? System.nanoTime() : 0;
                                    removing(modifyTimeAndSizeCaches, uf, ufStat, ufParent);
                                    if(USE_OLD_AND_NEW_LOG_DIRECTORY_LINKING) totalNewLogDirNanos += System.nanoTime() - startNanos;
                                    // Update filesystem
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
                                    // If there is a symlink that has now been replaced with a regular file, just delete the symlink to avoid confusion in the following code
                                    if(ufStat.isSymLink()) {
                                        // Update caches
                                        long startNanos = USE_OLD_AND_NEW_LOG_DIRECTORY_LINKING ? System.nanoTime() : 0;
                                        removing(modifyTimeAndSizeCaches, uf, ufStat, ufParent);
                                        if(USE_OLD_AND_NEW_LOG_DIRECTORY_LINKING) totalNewLogDirNanos += System.nanoTime() - startNanos;
                                        // Update the filesystem
                                        uf.delete();
                                        uf.getStat(ufStat);
                                    } else if(ufStat.isDirectory()) {
                                        // Update caches
                                        long startNanos = USE_OLD_AND_NEW_LOG_DIRECTORY_LINKING ? System.nanoTime() : 0;
                                        removing(modifyTimeAndSizeCaches, uf, ufStat, ufParent);
                                        if(USE_OLD_AND_NEW_LOG_DIRECTORY_LINKING) totalNewLogDirNanos += System.nanoTime() - startNanos;
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
                                                    UnixFile tempUF = UnixFile.mktemp(templateUF.getPath()+'.', false);
                                                    // Update the filesystem
                                                    uf.renameTo(tempUF);
                                                    uf.link(linkToUF);
                                                    uf.getStat(ufStat);
                                                    // Update caches
                                                    long startNanos = USE_OLD_AND_NEW_LOG_DIRECTORY_LINKING ? System.nanoTime() : 0;
                                                    renamed(modifyTimeAndSizeCaches, uf, tempUF, ufParent);
                                                    added(modifyTimeAndSizeCaches, uf, ufParent, modifyTimeAndSize);
                                                    if(USE_OLD_AND_NEW_LOG_DIRECTORY_LINKING) totalNewLogDirNanos += System.nanoTime() - startNanos;
                                                } else {
                                                    // Delete and link is OK because this is using a linkTo directory (therefore not in failover mode)
                                                    // Update caches
                                                    long startNanos = USE_OLD_AND_NEW_LOG_DIRECTORY_LINKING ? System.nanoTime() : 0;
                                                    removing(modifyTimeAndSizeCaches, uf, ufStat, ufParent);
                                                    if(USE_OLD_AND_NEW_LOG_DIRECTORY_LINKING) totalNewLogDirNanos += System.nanoTime() - startNanos;
                                                    // Update the filesystem
                                                    uf.delete();
                                                    uf.link(linkToUF);
                                                    uf.getStat(ufStat);
                                                    // Update caches
                                                    startNanos = USE_OLD_AND_NEW_LOG_DIRECTORY_LINKING ? System.nanoTime() : 0;
                                                    added(modifyTimeAndSizeCaches, uf, ufParent, modifyTimeAndSize);
                                                    if(USE_OLD_AND_NEW_LOG_DIRECTORY_LINKING) totalNewLogDirNanos += System.nanoTime() - startNanos;
                                                }
                                            } else {
                                                // Update caches
                                                long startNanos = USE_OLD_AND_NEW_LOG_DIRECTORY_LINKING ? System.nanoTime() : 0;
                                                removing(modifyTimeAndSizeCaches, uf, ufStat, ufParent);
                                                if(USE_OLD_AND_NEW_LOG_DIRECTORY_LINKING) totalNewLogDirNanos += System.nanoTime() - startNanos;
                                                // Update the filesystem
                                                uf.deleteRecursive();
                                                uf.link(linkToUF);
                                                uf.getStat(ufStat);
                                                // Update caches
                                                startNanos = USE_OLD_AND_NEW_LOG_DIRECTORY_LINKING ? System.nanoTime() : 0;
                                                added(modifyTimeAndSizeCaches, uf, ufParent, modifyTimeAndSize);
                                                if(USE_OLD_AND_NEW_LOG_DIRECTORY_LINKING) totalNewLogDirNanos += System.nanoTime() - startNanos;
                                            }
                                        } else {
                                            // Update the filesystem
                                            uf.link(linkToUF);
                                            uf.getStat(ufStat);
                                            // Update caches
                                            long startNanos = USE_OLD_AND_NEW_LOG_DIRECTORY_LINKING ? System.nanoTime() : 0;
                                            added(modifyTimeAndSizeCaches, uf, ufParent, modifyTimeAndSize);
                                            if(USE_OLD_AND_NEW_LOG_DIRECTORY_LINKING) totalNewLogDirNanos += System.nanoTime() - startNanos;
                                        }
                                    } else {
                                        // If we are in a log directory, search all regular files in current directory and linkTo directory for matching length and mtime
                                        // link to it if found
                                        boolean linkedOldLogFile = false;
                                        if(!isEncryptedLoopFile && isLogDir) {
                                            // Look for another file with the same size and modify time in this partial directory

                                            // New implementation is used first because it will load the directory physically from
                                            // disk first, thus the old implementation will have the advantage of the disk cache.
                                            // Therefore, if the new implementation is still faster, it is clearly the winner.
                                            long newStartNanos = USE_OLD_AND_NEW_LOG_DIRECTORY_LINKING ? System.nanoTime() : 0;
                                            UnixFile oldLogUF = null;
                                            ModifyTimeAndSizeCache modifyTimeAndSizeCache = modifyTimeAndSizeCaches.isEmpty() ? null : modifyTimeAndSizeCaches.get(ufParent);
                                            if(modifyTimeAndSizeCache==null) {
                                                // Not in cache, load from disk
                                                modifyTimeAndSizeCaches.put(ufParent, modifyTimeAndSizeCache = new ModifyTimeAndSizeCache(ufParent, tempStat));
                                            }
                                            List<String> matchedFilenames = modifyTimeAndSizeCache.getFilenamesByModifyTimeAndSize(modifyTimeAndSize);
                                            if(matchedFilenames!=null && !matchedFilenames.isEmpty()) {
                                                oldLogUF = new UnixFile(ufParent, matchedFilenames.get(0), false);
                                            }
                                            if(USE_OLD_AND_NEW_LOG_DIRECTORY_LINKING) totalNewLogDirNanos += System.nanoTime() - newStartNanos;
                                            
                                            if(USE_OLD_AND_NEW_LOG_DIRECTORY_LINKING) {
                                                // Old implementation
                                                long oldStartNanos = System.nanoTime();
                                                UnixFile oldOldLogUF = null;
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
                                                            oldOldLogUF = otherFile;
                                                            break;
                                                        }
                                                    }
                                                }
                                                totalOldLogDirNanos += System.nanoTime() - oldStartNanos;
                                                // Verify compatible results between implementations between oldOldLogUF (old) and oldLogUF (new)
                                                // Either both are null
                                                if(oldOldLogUF==null && oldLogUF==null) {
                                                    // This is acceptable
                                                } else if(oldOldLogUF!=null && oldLogUF!=null) {
                                                    // Or this is acceptable, but oldOldLogUF must be one of the options in matchedFilenames
                                                    if(!matchedFilenames.contains(oldOldLogUF.getFile().getName())) {
                                                        throw new IOException("matchedFilenames doesn't contain name for oldOldLogUF: oldOldLogUF="+oldOldLogUF);
                                                    }
                                                    // Verify that the file on disk actually matches the size and modifyTime
                                                    oldLogUF.getStat(otherFileStat);
                                                    if(otherFileStat.getModifyTime()!=modifyTime) {
                                                        throw new IOException("oldLogUF.getModifyTime()!=modifyTime, oldLogUF="+oldLogUF);
                                                    }
                                                    if(otherFileStat.getSize()!=length) {
                                                        throw new IOException("oldLogUF.getSize()!=length, oldLogUF="+oldLogUF);
                                                    }
                                                } else {
                                                    throw new IOException("Incompatible results old and new logDir implementation: oldOldLogUF="+oldOldLogUF+" and oldLogUF="+oldLogUF);
                                                }
                                            }
                                            if(oldLogUF==null && linkToUF!=null) {
                                                // Look for another file with the same size and modify time in the link to directory (previous backup pass).
                                                
                                                // New implementation is used first because it will load the directory physically from
                                                // disk first, thus the old implementation will have the advantage of the disk cache.
                                                // Therefore, if the new implementation is still faster, it is clearly the winner.
                                                long newStartNanos2 = USE_OLD_AND_NEW_LOG_DIRECTORY_LINKING ? System.nanoTime() : 0;
                                                ModifyTimeAndSizeCache modifyTimeAndSizeCache2 = modifyTimeAndSizeCaches.isEmpty() ? null : modifyTimeAndSizeCaches.get(linkToParent);
                                                if(modifyTimeAndSizeCache2==null) {
                                                    // Not in cache, load from disk
                                                    modifyTimeAndSizeCaches.put(linkToParent, modifyTimeAndSizeCache2 = new ModifyTimeAndSizeCache(linkToParent, tempStat));
                                                }
                                                List<String> matchedFilenames2 = modifyTimeAndSizeCache2.getFilenamesByModifyTimeAndSize(modifyTimeAndSize);
                                                if(matchedFilenames2!=null && !matchedFilenames2.isEmpty()) {
                                                    oldLogUF = new UnixFile(linkToParent, matchedFilenames2.get(0), false);
                                                }
                                                if(USE_OLD_AND_NEW_LOG_DIRECTORY_LINKING) totalNewLogDirNanos += System.nanoTime() - newStartNanos2;
                                                
                                                if(USE_OLD_AND_NEW_LOG_DIRECTORY_LINKING) {
                                                    // Old implementation
                                                    long oldStartNanos = System.nanoTime();
                                                    UnixFile oldOldLogUF = null;
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
                                                                oldOldLogUF = otherFile;
                                                                break;
                                                            }
                                                        }
                                                    }
                                                    totalOldLogDirNanos += System.nanoTime() - oldStartNanos;
                                                    // Verify compatible results between implementations between oldOldLogUF (old) and oldLogUF (new)
                                                    // Either both are null
                                                    if(oldOldLogUF==null && oldLogUF==null) {
                                                        // This is acceptable
                                                    } else if(oldOldLogUF!=null && oldLogUF!=null) {
                                                        // Or this is acceptable, but oldOldLogUF must be one of the options in matchedFilenames
                                                        if(!matchedFilenames2.contains(oldOldLogUF.getFile().getName())) {
                                                            throw new IOException("matchedFilenames2 doesn't contain name for oldOldLogUF: oldOldLogUF="+oldOldLogUF);
                                                        }
                                                        // Verify that the file on disk actually matches the size and modifyTime
                                                        oldLogUF.getStat(otherFileStat);
                                                        if(otherFileStat.getModifyTime()!=modifyTime) {
                                                            throw new IOException("oldLogUF.getModifyTime()!=modifyTime, oldLogUF="+oldLogUF);
                                                        }
                                                        if(otherFileStat.getSize()!=length) {
                                                            throw new IOException("oldLogUF.getSize()!=length, oldLogUF="+oldLogUF);
                                                        }
                                                    } else {
                                                        throw new IOException("Incompatible results old and new logDir implementation: oldOldLogUF="+oldOldLogUF+" and oldLogUF="+oldLogUF);
                                                    }
                                                }
                                            }
                                            if(oldLogUF!=null) {
                                                if(ufStat.exists()) {
                                                    if(ufStat.isRegularFile()) {
                                                        // Move to a new temp filename for later reuse
                                                        String name = uf.getFile().getName();
                                                        UnixFile templateUF = name.length()>64 ? new UnixFile(ufParent, name.substring(0, 64), false) : uf;
                                                        String tempPath = templateUF.getPath()+'.';
                                                        UnixFile tempUF = UnixFile.mktemp(tempPath, false);
                                                        // Update filesystem
                                                        if(retention==1) {
                                                            // Failover mode does a more cautious link to temp and rename over to avoid
                                                            // any moment where there is no file in the path of uf
                                                            tempUF.delete();
                                                            tempUF.link(uf);
                                                            UnixFile tempUF2 = UnixFile.mktemp(tempPath, false);
                                                            tempUF2.delete();
                                                            tempUF2.link(oldLogUF);
                                                            tempUF2.renameTo(uf);
                                                        } else {
                                                            // Backup mode uses a more efficient approach because partial states are OK
                                                            uf.renameTo(tempUF);
                                                            uf.link(oldLogUF);
                                                        }
                                                        // Update cache
                                                        long startNanos = USE_OLD_AND_NEW_LOG_DIRECTORY_LINKING ? System.nanoTime() : 0;
                                                        renamed(modifyTimeAndSizeCaches, uf, tempUF, ufParent);
                                                        added(modifyTimeAndSizeCaches, uf, ufParent, modifyTimeAndSize);
                                                        if(USE_OLD_AND_NEW_LOG_DIRECTORY_LINKING) totalNewLogDirNanos += System.nanoTime() - startNanos;
                                                    } else {
                                                        // Update cache
                                                        long startNanos = USE_OLD_AND_NEW_LOG_DIRECTORY_LINKING ? System.nanoTime() : 0;
                                                        removing(modifyTimeAndSizeCaches, uf, ufStat, ufParent);
                                                        if(USE_OLD_AND_NEW_LOG_DIRECTORY_LINKING) totalNewLogDirNanos += System.nanoTime() - startNanos;
                                                        // Update filesystem
                                                        uf.deleteRecursive();
                                                        uf.link(oldLogUF);
                                                        // Update cache
                                                        startNanos = USE_OLD_AND_NEW_LOG_DIRECTORY_LINKING ? System.nanoTime() : 0;
                                                        added(modifyTimeAndSizeCaches, uf, ufParent, modifyTimeAndSize);
                                                        if(USE_OLD_AND_NEW_LOG_DIRECTORY_LINKING) totalNewLogDirNanos += System.nanoTime() - startNanos;
                                                    }
                                                } else {
                                                    // Update filesystem
                                                    uf.link(oldLogUF);
                                                    // Update cache
                                                    long startNanos = USE_OLD_AND_NEW_LOG_DIRECTORY_LINKING ? System.nanoTime() : 0;
                                                    added(modifyTimeAndSizeCaches, uf, ufParent, modifyTimeAndSize);
                                                    if(USE_OLD_AND_NEW_LOG_DIRECTORY_LINKING) totalNewLogDirNanos += System.nanoTime() - startNanos;
                                                }
                                                uf.getStat(ufStat);
                                                result = AOServDaemonProtocol.FAILOVER_FILE_REPLICATION_MODIFIED;
                                                updated(retention, postPassChecklist, relativePath);
                                                linkedOldLogFile = true;
                                            }
                                        }
                                        if(!linkedOldLogFile) {
                                            boolean chunkingFile = false;
                                            if(
                                                useCompression
                                                // File is not so large that chunking can't possibly store md5's in RAM (> 8 Terabytes currently)
                                                && (2*length/AOServDaemonProtocol.FAILOVER_FILE_REPLICATION_CHUNK_SIZE) < Integer.MAX_VALUE
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
                                                        && (size=linkToUFStat.getSize())>=AOServDaemonProtocol.FAILOVER_FILE_REPLICATION_CHUNK_SIZE
                                                        // Chunk at best reduces transfer size by CHUNK_SIZE/17 (md5 + 1 byte response)
                                                        && size>(length/(AOServDaemonProtocol.FAILOVER_FILE_REPLICATION_CHUNK_SIZE/17))
                                                    ) {
                                                        chunkingFrom = linkToUF;
                                                        chunkingFromStat = linkToUFStat;
                                                    } else if(
                                                        ufStat.exists()
                                                        && ufStat.isRegularFile()
                                                        // Old file is not small (<CHUNK_SIZE)
                                                        && (size=ufStat.getSize())>=AOServDaemonProtocol.FAILOVER_FILE_REPLICATION_CHUNK_SIZE
                                                        // Chunk at best reduces transfer size by CHUNK_SIZE/17 (md5 + 1 byte response)
                                                        && size>(length/(AOServDaemonProtocol.FAILOVER_FILE_REPLICATION_CHUNK_SIZE/17))
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
                                                        && (size=ufStat.getSize())>=AOServDaemonProtocol.FAILOVER_FILE_REPLICATION_CHUNK_SIZE
                                                        // Chunk at best reduces transfer size by CHUNK_SIZE/17 (md5 + 1 byte response)
                                                        && size>(length/(AOServDaemonProtocol.FAILOVER_FILE_REPLICATION_CHUNK_SIZE/17))
                                                    ) {
                                                        chunkingFrom = uf;
                                                        chunkingFromStat = ufStat;
                                                    } else if(
                                                        linkToUF!=null
                                                        && linkToUFStat.exists()
                                                        && linkToUFStat.isRegularFile()
                                                        // Old file is not small (<CHUNK_SIZE)
                                                        && (size=linkToUFStat.getSize())>=AOServDaemonProtocol.FAILOVER_FILE_REPLICATION_CHUNK_SIZE
                                                        // Chunk at best reduces transfer size by CHUNK_SIZE/17 (md5 + 1 byte response)
                                                        && size>(length/(AOServDaemonProtocol.FAILOVER_FILE_REPLICATION_CHUNK_SIZE/17))
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
                                                    if(
                                                        ufStat.exists()
                                                        || retention==1
                                                        || (!isRecycling && linkToUF!=null)
                                                    ) {
                                                        // When uf exists, chunk to a temp file
                                                        String name = uf.getFile().getName();
                                                        UnixFile templateUF = name.length()>64 ? new UnixFile(ufParent, name.substring(0, 64), false) : uf;
                                                        String tempPath = templateUF.getPath()+'.';
                                                        UnixFile tempUF = UnixFile.mktemp(tempPath, false);
                                                        tempNewFiles[c] = tempUF;
                                                        if(log.isTraceEnabled()) log.trace("Using temp file (chunked): "+tempUF.getPath());
                                                        // modifyTimeAndSizeCaches is not updated here, it will be updated below when the data is received
                                                    } else {
                                                        if(!ufStat.exists()) {
                                                            new FileOutputStream(uf.getFile()).close();
                                                            uf.getStat(ufStat);
                                                            // modifyTimeAndSizeCaches is not updated here, it will be updated below when the data is received
                                                        }
                                                    }

                                                    // Build the list of MD5 hashes per chunk
                                                    long sizeToChunk = Math.min(length, chunkingFromStat.getSize());
                                                    LongList md5s = chunksMD5s[c] = new LongArrayList((int)(2*(sizeToChunk/AOServDaemonProtocol.FAILOVER_FILE_REPLICATION_CHUNK_SIZE)));
                                                    // Generate the MD5 hashes for the current file
                                                    InputStream fileIn = new FileInputStream(chunkingFrom.getFile());
                                                    try {
                                                        while(true) {
                                                            // Read in blocks of CHUNK_SIZE
                                                            int pos=0;
                                                            while(pos<AOServDaemonProtocol.FAILOVER_FILE_REPLICATION_CHUNK_SIZE) {
                                                                int ret = fileIn.read(chunkBuffer, pos, AOServDaemonProtocol.FAILOVER_FILE_REPLICATION_CHUNK_SIZE-pos);
                                                                if(ret==-1) break;
                                                                pos+=ret;
                                                            }
                                                            if(pos==AOServDaemonProtocol.FAILOVER_FILE_REPLICATION_CHUNK_SIZE) {
                                                                md5.Init();
                                                                md5.Update(chunkBuffer, 0, AOServDaemonProtocol.FAILOVER_FILE_REPLICATION_CHUNK_SIZE);
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
                                                    result = AOServDaemonProtocol.FAILOVER_FILE_REPLICATION_MODIFIED_REQUEST_DATA_CHUNKED;
                                                    updated(retention, postPassChecklist, relativePath);
                                                }
                                            }
                                            if(!chunkingFile) {
                                                result = AOServDaemonProtocol.FAILOVER_FILE_REPLICATION_MODIFIED_REQUEST_DATA;
                                                updated(retention, postPassChecklist, relativePath);
                                                // If the file doesn't exist, will download in-place
                                                if(!ufStat.exists()) {
                                                    new FileOutputStream(uf.getFile()).close();
                                                    uf.getStat(ufStat);
                                                } else {
                                                    // Build new temp file
                                                    String name = uf.getFile().getName();
                                                    UnixFile templateUF = name.length()>64 ? new UnixFile(ufParent, name.substring(0, 64), false) : uf;
                                                    String tempPath = templateUF.getPath()+'.';
                                                    UnixFile tempUF = UnixFile.mktemp(tempPath, false);
                                                    tempNewFiles[c] = tempUF;
                                                    if(log.isTraceEnabled()) log.trace("Using temp file (not chunked): "+tempUF.getPath());
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
                                        || !uf.readLink().equals(symlinkTarget)
                                    )
                                ) {
                                    if(log.isTraceEnabled()) log.trace("Deleting to create sybolic link: "+uf.getPath());
                                    // Update cache
                                    long startNanos = USE_OLD_AND_NEW_LOG_DIRECTORY_LINKING ? System.nanoTime() : 0;
                                    removing(modifyTimeAndSizeCaches, uf, ufStat, ufParent);
                                    if(USE_OLD_AND_NEW_LOG_DIRECTORY_LINKING) totalNewLogDirNanos += System.nanoTime() - startNanos;
                                    // Update filesystem
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
                                            result=AOServDaemonProtocol.FAILOVER_FILE_REPLICATION_MODIFIED;
                                            updated(retention, postPassChecklist, relativePath);
                                        }
                                    } else {
                                        result=AOServDaemonProtocol.FAILOVER_FILE_REPLICATION_MODIFIED;
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
                                if(result==AOServDaemonProtocol.FAILOVER_FILE_REPLICATION_NO_CHANGE) {
                                    if(linkToUF!=null) {
                                        // Only modified if wrong permission in last backup set, too
                                        if(
                                            !linkToUFStat.exists()
                                            || (
                                                (linkToUFStat.getRawMode() & (UnixFile.TYPE_MASK|UnixFile.PERMISSION_MASK))
                                                != (mode & (UnixFile.TYPE_MASK|UnixFile.PERMISSION_MASK))
                                            )
                                        ) {
                                            result=AOServDaemonProtocol.FAILOVER_FILE_REPLICATION_MODIFIED;
                                            updated(retention, postPassChecklist, relativePath);
                                        }
                                    } else {
                                        result=AOServDaemonProtocol.FAILOVER_FILE_REPLICATION_MODIFIED;
                                        updated(retention, postPassChecklist, relativePath);
                                    }
                                }
                            }

                            // Update the ownership
                            if(
                                effectiveUFStat.getUID()!=uid
                                // TODO: Store GID in xattr (if not 0)
                                || effectiveUFStat.getGID()!=(quota_gid==-1 ? gid : quota_gid)
                            ) {
                                if(retention!=1) copyIfHardLinked(effectiveUF, effectiveUFStat);
                                // TODO: Store GID in xattr (if not 0)
                                effectiveUF.chown(uid, (quota_gid==-1 ? gid : quota_gid));
                                effectiveUF.getStat(effectiveUFStat);
                                if(result==AOServDaemonProtocol.FAILOVER_FILE_REPLICATION_NO_CHANGE) {
                                    if(linkToUF!=null) {
                                        // Only modified if not in last backup set, too
                                        if(
                                            !linkToUFStat.exists()
                                            || linkToUFStat.getUID()!=uid
                                            // TODO: Store GID in xattr
                                            || linkToUFStat.getGID()!=(quota_gid==-1 ? gid : quota_gid)
                                        ) {
                                            result=AOServDaemonProtocol.FAILOVER_FILE_REPLICATION_MODIFIED;
                                            updated(retention, postPassChecklist, relativePath);
                                        }
                                    } else {
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
                                && effectiveUFStat.getModifyTime()!=modifyTime
                            ) {
                                if(retention!=1) copyIfHardLinked(effectiveUF, effectiveUFStat);
                                try {
                                    effectiveUF.utime(effectiveUFStat.getAccessTime(), modifyTime);
                                    effectiveUF.getStat(effectiveUFStat);
                                } catch(IOException err) {
                                    throw new WrappedException(err, new Object[] {"effectiveUF="+effectiveUF.getPath()});
                                }
                                if(result==AOServDaemonProtocol.FAILOVER_FILE_REPLICATION_NO_CHANGE) {
                                    if(linkToUF!=null) {
                                        // Only modified if not in last backup set, too
                                        if(
                                            !linkToUFStat.exists()
                                            || linkToUFStat.getModifyTime()!=modifyTime
                                        ) {
                                            result=AOServDaemonProtocol.FAILOVER_FILE_REPLICATION_MODIFIED;
                                            updated(retention, postPassChecklist, relativePath);
                                        }
                                    } else {
                                        result=AOServDaemonProtocol.FAILOVER_FILE_REPLICATION_MODIFIED;
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
                            if(result==AOServDaemonProtocol.FAILOVER_FILE_REPLICATION_MODIFIED_REQUEST_DATA_CHUNKED) {
                                LongList md5s = chunksMD5s[c];
                                int md5sSize = md5s.size();
                                if((md5sSize&1)==1) throw new AssertionError("md5sSize has an odd value, should be even: md5sSize="+md5sSize);
                                out.writeCompressedInt(md5sSize/2);
                                for(int d=0;d<md5sSize;d++) out.writeLong(md5s.getLong(d));
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
                            if(result==AOServDaemonProtocol.FAILOVER_FILE_REPLICATION_MODIFIED_REQUEST_DATA || result==AOServDaemonProtocol.FAILOVER_FILE_REPLICATION_MODIFIED_REQUEST_DATA_CHUNKED) {
                                // tempNewFiles[c] is only possibly set for the data transfer results
                                UnixFile tempUF = tempNewFiles[c];
                                UnixFile uf=new UnixFile(path);
                                UnixFile ufParent = uf.getParent();

                                // Load into the temporary file or directly to the file (based on above calculations)
                                UnixFile fileOutUF = tempUF==null ? uf : tempUF;
                                OutputStream fileOut = new FileOutputStream(fileOutUF.getFile());
                                boolean newFileComplete = false;
                                try {
                                    if(result==AOServDaemonProtocol.FAILOVER_FILE_REPLICATION_MODIFIED_REQUEST_DATA) {
                                        int response;
                                        while((response=in.read())==AOServDaemonProtocol.NEXT) {
                                            int blockLen=in.readShort();
                                            in.readFully(buff, 0, blockLen);
                                            fileOut.write(buff, 0, blockLen);
                                        }
                                        if(response!=AOServDaemonProtocol.DONE) throw new IOException("Unexpected response code: "+response);
                                    } else if(result==AOServDaemonProtocol.FAILOVER_FILE_REPLICATION_MODIFIED_REQUEST_DATA_CHUNKED) {
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
                                                    raf.readFully(chunkBuffer, 0, AOServDaemonProtocol.FAILOVER_FILE_REPLICATION_CHUNK_SIZE);
                                                    fileOut.write(chunkBuffer, 0, AOServDaemonProtocol.FAILOVER_FILE_REPLICATION_CHUNK_SIZE);
                                                    bytesWritten+=AOServDaemonProtocol.FAILOVER_FILE_REPLICATION_CHUNK_SIZE;
                                                } else throw new RuntimeException("Unexpected value for response: "+response);
                                            }
                                            if(response!=AOServDaemonProtocol.DONE) throw new IOException("Unexpected response code: "+response);
                                        } finally {
                                            raf.close();
                                        }
                                    } else throw new RuntimeException("Unexpected value for result: "+result);
                                    newFileComplete = true;
                                } finally {
                                    fileOut.close();
                                    
                                    // If the new file is incomplete for any reason (presumably due to an exception)
                                    // and we are doing a backup to a temporary file, move the temp file over the old file
                                    // if it is longer
                                    if(
                                        !newFileComplete
                                        && retention!=1
                                        && tempUF!=null
                                    ) {
                                        uf.getStat(ufStat);
                                        if(!ufStat.exists()) {
                                            // If it doesn't exist, can't compare file sizes, just rename
                                            if(log.isDebugEnabled()) log.debug("Renaming partial temp file to final filename because final filename doesn't exist: "+uf.getPath());
                                            tempUF.renameTo(uf);
                                            // This should only happen during exceptions, so no need to keep directory caches synchronized
                                        } else {
                                            long ufSize = ufStat.getSize();
                                            tempUF.getStat(tempStat);
                                            long tempUFSize = tempStat.getSize();
                                            if(tempUFSize>ufSize) {
                                                if(log.isDebugEnabled()) log.debug("Renaming partial temp file to final filename because temp file is longer than the final file: "+uf.getPath());
                                                tempUF.renameTo(uf);
                                                // This should only happen during exceptions, so no need to keep directory caches synchronized
                                            }
                                        }
                                    }
                                }
                                fileOutUF.utime(fileOutUF.getStat(tempStat).getAccessTime(), modifyTimes[c]);
                                uf.getStat(ufStat);
                                if(tempUF!=null) {
                                    if(ufStat.exists()) {
                                        if(ufStat.isRegularFile()) {
                                            if(isLogDirs[c]) {
                                                // Move to a new temp filename for later reuse
                                                String name = uf.getFile().getName();
                                                UnixFile templateUF = name.length()>64 ? new UnixFile(uf.getParent(), name.substring(0, 64), false) : uf;
                                                String tempPath = templateUF.getPath()+'.';
                                                UnixFile tempUFLog = UnixFile.mktemp(tempPath, false);
                                                // Update filesystem
                                                if(retention==1) {
                                                    // Failover mode does a more cautious link to temp and rename over to avoid
                                                    // any moment where there is no file in the path of uf
                                                    tempUFLog.delete();
                                                    tempUFLog.link(uf);
                                                    tempUF.renameTo(uf);
                                                    uf.getStat(ufStat);
                                                } else {
                                                    // Backup mode uses a more efficient approach because partial states are OK
                                                    uf.renameTo(tempUFLog);
                                                    tempUF.renameTo(uf);
                                                    uf.getStat(ufStat);
                                                }
                                                // Update cache (cache update counted as remove and then add because cache renaming method expects renameTo to not exist
                                                long startNanos = USE_OLD_AND_NEW_LOG_DIRECTORY_LINKING ? System.nanoTime() : 0;
                                                renamed(modifyTimeAndSizeCaches, uf, tempUFLog, ufParent);
                                                if(USE_OLD_AND_NEW_LOG_DIRECTORY_LINKING) totalNewLogDirNanos += System.nanoTime() - startNanos;
                                            } else {
                                                // Not a log directory, just replace old regular file
                                                // Update cache (cache update counted as remove and then add because cache renaming method expects renameTo to not exist
                                                long startNanos = USE_OLD_AND_NEW_LOG_DIRECTORY_LINKING ? System.nanoTime() : 0;
                                                removing(modifyTimeAndSizeCaches, uf, ufStat, ufParent);
                                                if(USE_OLD_AND_NEW_LOG_DIRECTORY_LINKING) totalNewLogDirNanos += System.nanoTime() - startNanos;
                                                // Update filesystem
                                                tempUF.renameTo(uf);
                                                uf.getStat(ufStat);
                                            }
                                        } else {
                                            // Update cache
                                            long startNanos = USE_OLD_AND_NEW_LOG_DIRECTORY_LINKING ? System.nanoTime() : 0;
                                            removing(modifyTimeAndSizeCaches, uf, ufStat, ufParent);
                                            if(USE_OLD_AND_NEW_LOG_DIRECTORY_LINKING) totalNewLogDirNanos += System.nanoTime() - startNanos;
                                            // Update filesystem
                                            uf.deleteRecursive();
                                            tempUF.renameTo(uf);
                                            uf.getStat(ufStat);
                                        }
                                    } else {
                                        tempUF.renameTo(uf);
                                        uf.getStat(ufStat);
                                    }
                                }
                                // Update cache (cache update counted as remove and then add because cache renaming method expects renameTo to not exist
                                long startNanos = USE_OLD_AND_NEW_LOG_DIRECTORY_LINKING ? System.nanoTime() : 0;
                                added(modifyTimeAndSizeCaches, uf, ufParent, new ModifyTimeAndSize(ufStat.getModifyTime(), ufStat.getSize()));
                                if(USE_OLD_AND_NEW_LOG_DIRECTORY_LINKING) totalNewLogDirNanos += System.nanoTime() - startNanos;
                            }
                        }
                    }

                    // For any directories that were completed during this batch, remove caches, clean extra files and set its modify time
                    for(int c=0;c<directoryFinalizeUFs.size();c++) {
                        UnixFile dirUF = directoryFinalizeUFs.get(c);
                        UnixFile dirLinkToUF = directoryFinalizeLinkToUFs==null ? null : directoryFinalizeLinkToUFs.get(c);
                        String relativePath = directoryFinalizeUFRelativePaths.get(c);
                        long dirModifyTime = directoryFinalizeModifyTimes.get(c).longValue();
                        Set<String> dirContents = directoryFinalizeContents.get(c);
                        // Remove from the caches since we are done with the directory entirely for this pass
                        if(!modifyTimeAndSizeCaches.isEmpty()) modifyTimeAndSizeCaches.remove(dirUF);
                        if(dirLinkToUF!=null && !modifyTimeAndSizeCaches.isEmpty()) modifyTimeAndSizeCaches.remove(dirLinkToUF);
                        // Remove extra files
                        String dirPath=dirUF.getPath();
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
                        // Set the modified time
                        dirUF.getStat(tempStat);
                        if(tempStat.getModifyTime()!=dirModifyTime) dirUF.utime(tempStat.getAccessTime(), dirModifyTime);
                    }
                }

                // modifyTimeAndSizeCaches is no longer used after this, this makes sure
                int modifyTimeAndSizeCachesSize = modifyTimeAndSizeCaches.size();
                modifyTimeAndSizeCaches = null;

                // Clean all remaining directories all the way to /, setting modifyTime on the directories
                while(!directoryUFs.isEmpty()) {
                    UnixFile dirUF=directoryUFs.peek();
                    String dirPath=dirUF.getPath();
                    if(!dirPath.endsWith("/")) dirPath=dirPath+'/';

                    // Otherwise, clean and complete the directory
                    directoryUFs.pop();
                    if(directoryLinkToUFs!=null) directoryLinkToUFs.pop(); // Just to keep the stacks uniform between them
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
                
                // Log the final timings
                if(USE_OLD_AND_NEW_LOG_DIRECTORY_LINKING) {
                    log.info("modifyTimeAndSizeCachesSize="+modifyTimeAndSizeCachesSize);
                    log.info("totalNewLogDirNanos="+totalNewLogDirNanos+" (successful pass completed)");
                    log.info("totalOldLogDirNanos="+totalOldLogDirNanos+" (successful pass completed)");
                }

                if(retention!=1) {
                    // The pass was successful, now rename partial to final
                    String from = isRecycling ? recycledPartialMirrorRoot : partialMirrorRoot;
                    if(log.isDebugEnabled()) log.debug("Renaming "+from+" to "+finalMirrorRoot);
                    new UnixFile(from).renameTo(new UnixFile(finalMirrorRoot));

                    // The pass was successful, now cleanup old directories based on retention settings
                    cleanAndRecycleBackups(retention, serverRootUF, tempStat, fromServerYear, fromServerMonth, fromServerDay);
                }

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
                    if(log.isDebugEnabled()) log.debug("Restarting MySQL "+mysqlServer+" in \""+toPath+'"');
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
                        AOServDaemon.reportError(err, command);
                    }
                }
            }
        }
    }

    /**
     * Called before something is removed, to keep the cache in sync.
     */
    private static void removing(Map<UnixFile,ModifyTimeAndSizeCache> modifyTimeAndSizeCaches, UnixFile uf, Stat ufStat, UnixFile ufParent) throws FileNotFoundException {
        if(!modifyTimeAndSizeCaches.isEmpty()) {
            if(ufStat.isRegularFile()) {
                // For a regular file, just remove it from its parent, this is the fastest case
                // because no scan for children directories is required.

                ModifyTimeAndSizeCache modifyTimeAndSizeCache = modifyTimeAndSizeCaches.get(ufParent);
                if(modifyTimeAndSizeCache!=null) modifyTimeAndSizeCache.removing(uf.getFile().getName());
            } else if(ufStat.isDirectory()) {
                // For a directory, remove it and any of the directories under it.
                // This is more expensive because we need to search all caches for prefix matches (iteration).

                // Remove any items that are this or are children of this
                String prefix = uf.getPath();
                if(!prefix.endsWith("/")) prefix = prefix+'/';
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
    private static void added(Map<UnixFile,ModifyTimeAndSizeCache> modifyTimeAndSizeCaches, UnixFile uf, UnixFile ufParent, ModifyTimeAndSize ufModifyTimeAndSize) throws IOException {
        if(USE_OLD_AND_NEW_LOG_DIRECTORY_LINKING) {
            Stat ufStat = uf.getStat();
            if(ufStat.getModifyTime()!=ufModifyTimeAndSize.modifyTime) throw new AssertionError("uf.getStat().getModifyTime()!=ufModifyTimeAndSize.modifyTime");
            if(ufStat.getSize()!=ufModifyTimeAndSize.size) throw new AssertionError("uf.getStat().getSize()!=ufModifyTimeAndSize.size");
        }
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
        final private Map<String,ModifyTimeAndSize> filenameMap = new HashMap<String,ModifyTimeAndSize>();
        final private Map<ModifyTimeAndSize,List<String>> modifyTimeAndSizeMap = new HashMap<ModifyTimeAndSize,List<String>>();

        ModifyTimeAndSizeCache(UnixFile directory, Stat tempStat) throws IOException {
            this.directory = directory;
            // Read all files in the directory to populate the caches
            String[] list = directory.list();
            if(list!=null) {
                for(int d=0, len=list.length;d<len;d++) {
                    String filename = list[d];
                    UnixFile file = new UnixFile(directory, filename, false);
                    file.getStat(tempStat);
                    if(
                        tempStat.exists()
                        && tempStat.isRegularFile()
                    ) {
                        ModifyTimeAndSize modifyTimeAndSize = new ModifyTimeAndSize(tempStat.getModifyTime(), tempStat.getSize());
                        filenameMap.put(filename, modifyTimeAndSize);
                        List<String> fileList = modifyTimeAndSizeMap.get(modifyTimeAndSize);
                        if(fileList==null) modifyTimeAndSizeMap.put(modifyTimeAndSize, fileList = new ArrayList<String>());
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
            if(fileList==null) modifyTimeAndSizeMap.put(modifyTimeAndSize, fileList = new ArrayList<String>());
            fileList.add(filename);
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
            if(log.isDebugEnabled()) log.debug("Skipping delete on cleanup: \""+fromServer+"\":"+relativePath);
            return false;
        }
        if(retention==1) {
            for(String name : replicatedMySQLServers) {
                if(
                    relativePath.equals("/var/lib/mysql/"+name)
                    || relativePath.startsWith("/var/lib/mysql/"+name+"/")
                ) {
                    if(log.isDebugEnabled()) log.debug("Skipping delete on cleanup: \""+fromServer+"\":"+relativePath);
                    return false;
                }
            }
            for(String minorVersion : replicatedMySQLMinorVersions) {
                if(relativePath.equals("/var/lock/subsys/mysql-"+minorVersion)) {
                    if(log.isDebugEnabled()) log.debug("Skipping delete on cleanup: \""+fromServer+"\":"+relativePath);
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
                            delete(directoriesByAge, age, deleteFilenames);
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
                    if(log.isDebugEnabled()) log.debug("Renaming "+currentUF.getPath()+" to "+newUF.getPath());
                    if(newUF.getStat(tempStat).exists()) throw new IOException("newUF exists: "+newUF.getPath());
                    currentUF.renameTo(newUF);
                } else {
                    // 2) Flag all those that where not completed directly as .deleted, schedule for delete
                    if(!directory.endsWith(SAFE_DELETE_EXTENSION)) {
                        final UnixFile currentUF = new UnixFile(serverRootUF, directory, false);
                        final UnixFile newUF = new UnixFile(serverRootUF, directory+SAFE_DELETE_EXTENSION, false);
                        if(log.isDebugEnabled()) log.debug("Renaming "+currentUF.getPath()+" to "+newUF.getPath());
                        if(newUF.getStat(tempStat).exists()) throw new IOException("newUF exists: "+newUF.getPath());
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
                                if(log.isDebugEnabled()) log.debug("Renaming "+currentUF.getPath()+" to "+newUF.getPath());
                                if(newUF.getStat(tempStat).exists()) throw new IOException("newUF exists: "+newUF.getPath());
                                currentUF.renameTo(newUF);
                            }
                        }
                    }
                }
            }
            
            // 5) Delete all those that end in .deleted, from oldest to newest
            if(!SAFE_DELETE) {
                String[] list = serverRootUF.list();
                if(list!=null && list.length>0) {
                    Arrays.sort(list);
                    final List<File> directories = new ArrayList<File>(list.length);
                    for(int c=0;c<list.length;c++) {
                        String directory = list[c];
                        if(directory.endsWith(SAFE_DELETE_EXTENSION)) {
                            //found=true;
                            UnixFile deleteUf = new UnixFile(serverRootUF, directory, false);
                            if(log.isDebugEnabled()) log.debug("Deleting: "+deleteUf.getPath());
                            directories.add(deleteUf.getFile());
                        }
                    }
                    if(!directories.isEmpty()) {
                        // Delete in the background
                        AOServDaemon.executorService.submit(
                            new Runnable() {
                                @Override
                                public void run() {
                                    try {
                                        if(directories.size()==1) {
                                            // Single directory - no parallel benefits, use system rm command
                                            String[] command = {
                                                "/bin/rm",
                                                "-rf",
                                                directories.get(0).getPath()
                                            };
                                            AOServDaemon.exec(command);
                                        } else {
                                            ParallelDelete.parallelDelete(directories, null, false);
                                        }
                                    } catch(IOException err) {
                                        AOServDaemon.reportError(err, null);
                                    }
                                }
                            }
                        );
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
    private static boolean copyIfHardLinked(UnixFile uf, Stat ufStat) throws IOException {
        if(ufStat.isRegularFile() && ufStat.getNumberLinks()>1) {
            if(log.isTraceEnabled()) log.trace("Copying file due to hard link: "+uf);
            UnixFile temp = UnixFile.mktemp(uf.getPath()+'.', false);
            uf.copyTo(temp, true);
            temp.chown(ufStat.getUID(), ufStat.getGID());
            temp.setMode(ufStat.getMode());
            long atime = ufStat.getAccessTime();
            long mtime = ufStat.getModifyTime();
            temp.renameTo(uf);
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
