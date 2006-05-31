package com.aoindustries.aoserv.daemon.failover;

/*
 * Copyright 2003-2006 by AO Industries, Inc.,
 * 2200 Dogwood Ct N, Mobile, Alabama, 36693, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.aoserv.client.AOServClientConfiguration;
import com.aoindustries.aoserv.client.AOServer;
import com.aoindustries.aoserv.client.BackupPartition;
import com.aoindustries.aoserv.client.FailoverFileLog;
import com.aoindustries.aoserv.client.FailoverFileReplication;
import com.aoindustries.aoserv.client.FailoverFileSchedule;
import com.aoindustries.aoserv.client.NetBind;
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
import com.aoindustries.io.unix.FilesystemIterator;
import com.aoindustries.io.unix.UnixFile;
import com.aoindustries.md5.MD5;
import com.aoindustries.profiler.Profiler;
import com.aoindustries.util.BufferManager;
import com.aoindustries.util.LongArrayList;
import com.aoindustries.util.LongList;
import com.aoindustries.util.SortedArrayList;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.sql.SQLException;
import java.util.Calendar;
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
 * TODO: Handle hard links (pertinence space savings)
 *
 * TODO: Handle rotated log files more efficiently
 *
 * @author  AO Industries, Inc.
 */
final public class FailoverFileReplicationManager implements Runnable {

    private static final Log log = LogFactory.getLog(FailoverFileReplicationManager.class);

    private static final int BATCH_SIZE=1000;
    
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

    private static final File failoverDir=new File("/var/failover");

    private static Thread thread;

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

    public static void failoverServer(
        CompressedDataInputStream rawIn,
        CompressedDataOutputStream rawOut,
        String fromServer,
        boolean useCompression
    ) throws IOException {
        Profiler.startProfile(Profiler.IO, FailoverFileReplicationManager.class, "failoverServer(CompressedDataInputStream,CompressedDataOutputStream,String,boolean)", null);
        try {
            // Tell the client it is OK to continue
            rawOut.write(AOServDaemonProtocol.NEXT);
            rawOut.flush();

            CompressedDataInputStream in;
            CompressedDataOutputStream out;
            //boolean closeStreams;
            //if(useCompression) {
            //    in = new CompressedDataInputStream(new GZIPInputStream(new DontCloseInputStream(rawIn), BufferManager.BUFFER_SIZE));
            //    out = new CompressedDataOutputStream(new GZIPOutputStream(new DontCloseOutputStream(rawOut), BufferManager.BUFFER_SIZE));
            //    closeStreams = true;
            //} else {
                in = rawIn;
                out = rawOut;
            //    closeStreams = false;
            //}
            //try {
                String[] paths=new String[BATCH_SIZE];
                boolean[] isLogDirs=new boolean[BATCH_SIZE];
                UnixFile[] tempNewFiles=new UnixFile[BATCH_SIZE];
                Set<String> tempNewFilenames=new HashSet<String>(BATCH_SIZE*4/3+1);
                LongList[] chunksMD5s=useCompression ? new LongList[BATCH_SIZE] : null;
                long[] modifyTimes=new long[BATCH_SIZE];
                int[] results=new int[BATCH_SIZE];

                byte[] buff=BufferManager.getBytes();
                byte[] chunkBuffer = useCompression ? new byte[CHUNK_SIZE] : null;
                MD5 md5 = useCompression ? new MD5() : null;
                try {
                    // The extra files in directories are cleaned once the directory is done
                    Stack<UnixFile> directoryUFs=new Stack<UnixFile>();         // UnixFiles
                    Stack<Long> directoryModifyTimes=new Stack<Long>(); // Longs
                    Stack<Map<String,Object>> directoryContents=new Stack<Map<String,Object>>();    // HashMaps

                    // Continue until a batchSize of -1 (end of replication)
                    int batchSize;
                    while((batchSize=in.readCompressedInt())!=-1) {
                        tempNewFilenames.clear();
                        for(int c=0;c<batchSize;c++) {
                            if(in.readBoolean()) {
                                // Read the current file
                                String relativePath=paths[c]=in.readCompressedUTF();
                                isLogDirs[c]=relativePath.startsWith("/logs/") || relativePath.startsWith("/var/log/");
                                checkPath(relativePath);
                                String path=paths[c]="/var/failover/"+fromServer+relativePath;
                                UnixFile uf=new UnixFile(path);
                                long mode=in.readLong();
                                long length;
                                if(UnixFile.isRegularFile(mode)) length=in.readLong();
                                else length=-1;
                                int uid=in.readCompressedInt();
                                int gid=in.readCompressedInt();
                                long modifyTime=modifyTimes[c]=UnixFile.isSymLink(mode)?-1:in.readLong();
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

                                    // Otherwise, clean and complete the directory
                                    directoryUFs.pop();
                                    long dirModifyTime=directoryModifyTimes.pop().longValue();
                                    Map<String,Object> dirContents=directoryContents.pop();
                                    String[] list=dirUF.list();
                                    for(int d=0;d<list.length;d++) {
                                        String fullpath=dirPath+list[d];
                                        if(!dirContents.containsKey(fullpath)) {
                                            // Make sure it is not one of the temp files
                                            boolean isTemp = tempNewFilenames.contains(fullpath);
                                            if(isTemp) {
                                                if(log.isTraceEnabled()) log.trace("Not deleting temp file: "+fullpath);
                                            } else {
                                                if(log.isTraceEnabled()) log.trace("Deleting extra file: "+fullpath);
                                                try {
                                                    new UnixFile(fullpath).deleteRecursive();
                                                } catch(FileNotFoundException err) {
                                                    AOServDaemon.reportError(err, new Object[] {"fullpath="+fullpath});
                                                }
                                            }
                                        }
                                    }
                                    dirUF.setModifyTime(dirModifyTime);
                                }

                                // Add the current to the directory
                                if(!directoryContents.isEmpty()) {
                                    directoryContents.peek().put(path, null);
                                }

                                // Process the current file
                                int result = NO_CHANGE;
                                tempNewFiles[c]=null;
                                if(useCompression) chunksMD5s[c]=null;
                                if(UnixFile.isBlockDevice(mode)) {
                                    if(
                                        uf.exists()
                                        && (
                                            !uf.isBlockDevice()
                                            || uf.getDeviceIdentifier()!=deviceID
                                        )
                                    ) {
                                        if(log.isTraceEnabled()) log.trace("Deleting to create block device: "+uf.getFilename());
                                        uf.deleteRecursive();
                                        result=MODIFIED;
                                    }
                                    if(!uf.exists()) {
                                        uf.mknod(mode, deviceID);
                                        result=MODIFIED;
                                    }
                                } else if(UnixFile.isCharacterDevice(mode)) {
                                    if(
                                        uf.exists()
                                        && (
                                            !uf.isCharacterDevice()
                                            || uf.getDeviceIdentifier()!=deviceID
                                        )
                                    ) {
                                        if(log.isTraceEnabled()) log.trace("Deleting to create character device: "+uf.getFilename());
                                        uf.deleteRecursive();
                                        result=MODIFIED;

                                    }
                                    if(!uf.exists()) {
                                        uf.mknod(mode, deviceID);
                                        result=MODIFIED;
                                    }
                                } else if(UnixFile.isDirectory(mode)) {
                                    if(
                                        uf.exists()
                                        && !uf.isDirectory()
                                    ) {
                                        if(log.isTraceEnabled()) log.trace("Deleting to create directory: "+uf.getFilename());
                                        uf.deleteRecursive();
                                        result=MODIFIED;
                                    }
                                    if(!uf.exists()) {
                                        uf.mkdir();
                                        result=MODIFIED;
                                    } else if(uf.getModifyTime()!=modifyTime) {
                                        result=MODIFIED;
                                    }
                                    directoryUFs.push(uf);
                                    directoryModifyTimes.push(Long.valueOf(modifyTime));
                                    directoryContents.push(new HashMap<String,Object>());
                                } else if(UnixFile.isFIFO(mode)) {
                                    if(
                                        uf.exists()
                                        && !uf.isFIFO()
                                    ) {
                                        if(log.isTraceEnabled()) log.trace("Deleting to create FIFO: "+uf.getFilename());
                                        uf.deleteRecursive();
                                        result=MODIFIED;
                                    }
                                    if(!uf.exists()) {
                                        uf.mkfifo(mode);
                                        result=MODIFIED;
                                    }
                                } else if(UnixFile.isRegularFile(mode)) {
                                    if(
                                        !uf.exists()
                                        || !uf.isRegularFile()
                                        || uf.getSize()!=length
                                        || uf.getModifyTime()!=modifyTime
                                    ) {
                                        // Always load into a temporary file first
                                        UnixFile tempNewFile = tempNewFiles[c]=UnixFile.mktemp(uf.getFilename()+'.');
                                        tempNewFilenames.add(tempNewFile.getFilename());
                                        if(log.isTraceEnabled()) log.trace("Using temp file: "+tempNewFile.getFilename());
                                        // Is this a log directory
                                        boolean copiedOldLogFile = false;
                                        if(isLogDirs[c]) {
                                            // Look for another file with the same size and modify time
                                            // TODO: Use a cache to reduce CPU consumption here
                                            UnixFile parent = uf.getParent();
                                            String[] list = parent.list();
                                            if(list!=null) {
                                                for(int d=0;d<list.length;d++) {
                                                    UnixFile otherFile = new UnixFile(parent, list[d]);
                                                    if(
                                                        otherFile.exists()
                                                        && otherFile.isRegularFile()
                                                        && otherFile.getSize()==length
                                                        && otherFile.getModifyTime()==modifyTime
                                                    ) {
                                                        if(log.isTraceEnabled()) log.trace("Found matching log file, copying to temp file: old="+otherFile.getFilename()+" new="+tempNewFile.getFilename());
                                                        if(log.isTraceEnabled()) log.trace("Before: otherFile.exists() = "+otherFile.exists());
                                                        if(log.isTraceEnabled()) log.trace("Before: tempNewFile.exists() = "+tempNewFile.exists());
                                                        otherFile.copyTo(tempNewFile, true);
                                                        tempNewFile.setModifyTime(modifyTime);
                                                        if(log.isTraceEnabled()) log.trace("After: otherFile.exists() = "+otherFile.exists());
                                                        if(log.isTraceEnabled()) log.trace("After: tempNewFile.exists() = "+tempNewFile.exists());
                                                        result = MODIFIED;
                                                        copiedOldLogFile = true;
                                                        break;
                                                    }
                                                }
                                            }
                                        }
                                        if(!copiedOldLogFile) {
                                            if(
                                                // Not using compression
                                                !useCompression
                                                // Doesn't exist
                                                || !uf.exists()
                                                // New file is small (<CHUNK_SIZE)
                                                || uf.getSize()<CHUNK_SIZE
                                                // Chunk at best reduces transfer size by CHUNK_SIZE/17 (md5 + 1 byte response)
                                                || uf.getSize()<=(length/(CHUNK_SIZE/17))
                                                // File is so large that chunking can't possibly store md5's in RAM (> 8 Terabytes currently)
                                                || (2*length/CHUNK_SIZE) >= Integer.MAX_VALUE
                                            ) {
                                                result = MODIFIED_REQUEST_DATA;
                                            } else {
                                                result = MODIFIED_REQUEST_DATA_CHUNKED;
                                                // If this is compressed, it will send the md5 hashes of each block after the response code
                                                long sizeToChunk = Math.min(length, uf.getSize());
                                                LongList md5s = chunksMD5s[c] = new LongArrayList((int)(2*sizeToChunk/CHUNK_SIZE));
                                                // Generate the MD5 hashes for the current file
                                                InputStream fileIn = new FileInputStream(uf.getFile());
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
                                            }
                                        }
                                    }
                                } else if(UnixFile.isSymLink(mode)) {
                                    if(
                                        uf.exists()
                                        && (
                                            !uf.isSymLink()
                                            || !uf.readLink().equals(symlinkTarget)
                                        )
                                    ) {
                                        if(log.isTraceEnabled()) log.trace("Deleting to create sybolic link: "+uf.getFilename());
                                        uf.deleteRecursive();
                                        result=MODIFIED;
                                    }
                                    if(!uf.exists()) {
                                        uf.symLink(symlinkTarget);
                                        result=MODIFIED;
                                    }
                                } else throw new IOException("Unknown mode type: "+Long.toOctalString(mode&UnixFile.TYPE_MASK));

                                UnixFile effectiveUF = tempNewFiles[c]==null ? uf : tempNewFiles[c];
                                if(
                                    (effectiveUF.getStatMode() & (UnixFile.TYPE_MASK|UnixFile.PERMISSION_MASK))
                                    != (mode & (UnixFile.TYPE_MASK|UnixFile.PERMISSION_MASK))
                                ) {
                                    try {
                                        effectiveUF.setMode(mode & (UnixFile.TYPE_MASK|UnixFile.PERMISSION_MASK));
                                    } catch(FileNotFoundException err) {
                                        AOServDaemon.reportWarning(err, new Object[] {"path="+path, "mode="+Long.toOctalString(mode)});
                                    }
                                    if(result==NO_CHANGE) result=MODIFIED;
                                }
                                if(effectiveUF.getUID()!=uid) {
                                    effectiveUF.setUID(uid);
                                    if(result==NO_CHANGE) result=MODIFIED;
                                }
                                if(effectiveUF.getGID()!=gid) {
                                    effectiveUF.setGID(gid);
                                    if(result==NO_CHANGE) result=MODIFIED;
                                }
                                if(
                                    !UnixFile.isSymLink(mode)
                                    && !UnixFile.isDirectory(mode)
                                    && effectiveUF.getModifyTime()!=modifyTime
                                ) {
                                    effectiveUF.setModifyTime(modifyTime);
                                    if(result==NO_CHANGE) result=MODIFIED;
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
                                UnixFile uf=new UnixFile(path);
                                if(result==MODIFIED_REQUEST_DATA || result==MODIFIED_REQUEST_DATA_CHUNKED) {

                                    // Load into the temporary file
                                    OutputStream fileOut = new FileOutputStream(tempNewFiles[c].getFile());
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
                                            RandomAccessFile raf=new RandomAccessFile(uf.getFile(), "r");
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
                                }
                                if(tempNewFiles[c]!=null) {
                                    // Move the file into place
                                    if(uf.exists()) {
                                        if(isLogDirs[c] && uf.isRegularFile()) {
                                            // Move to temporary file for later log blocks
                                            UnixFile newTemp = UnixFile.mktemp(uf.getFilename()+'.');
                                            if(log.isTraceEnabled()) log.trace("Moving old log file to new path for later matches after log rotations: from="+uf.getFilename()+" to="+newTemp.getFilename());
                                            long ufModified = uf.getModifyTime();
                                            uf.renameTo(newTemp);
                                            newTemp.setModifyTime(ufModified);
                                        } else if(!uf.isRegularFile()) {
                                            if(log.isTraceEnabled()) log.trace("Deleting non-regular file to move new regular file into place: "+uf.getFilename());
                                            uf.deleteRecursive();
                                        }
                                    }
                                    if(log.isTraceEnabled()) {
                                        log.trace("tempNewFiles[c]="+tempNewFiles[c].getFilename()+" exists()="+tempNewFiles[c].exists());
                                        log.trace("uf="+uf.getFilename()+" exists()="+uf.exists());
                                    }
                                    tempNewFiles[c].renameTo(uf);
                                    // Set modify time is after move because some kernels incorrectly update file modified time when it is moved
                                    uf.setModifyTime(modifyTimes[c]);
                                }
                            }
                        }
                    }

                    // Clean all remaining directories all the way to /, setting modifyTime on the directories
                    while(!directoryUFs.isEmpty()) {
                        UnixFile dirUF=directoryUFs.peek();
                        String dirPath=dirUF.getFilename();
                        if(!dirPath.endsWith("/")) dirPath=dirPath+'/';

                        // Otherwise, clean and complete the directory
                        directoryUFs.pop();
                        long dirModifyTime=directoryModifyTimes.pop().longValue();
                        Map<String,Object> dirContents=directoryContents.pop();
                        String[] list=dirUF.list();
                        for(int c=0;c<list.length;c++) {
                            String fullpath=dirPath+list[c];
                            if(!dirContents.containsKey(fullpath)) {
                                if(log.isTraceEnabled()) log.trace("Deleting final clean-up: "+fullpath);
                                new UnixFile(fullpath).deleteRecursive();
                            }
                        }
                        dirUF.setModifyTime(dirModifyTime);
                    }

                    // Tell the client we are done OK
                    out.write(AOServDaemonProtocol.DONE);
                    out.flush();
                } finally {
                    BufferManager.release(buff);
                }
            //} finally {
            //    if(closeStreams) {
                    // Release compressed stream native resources
            //        in.close();
            //        out.close();
            //    }
            //}
        } finally {
            Profiler.endProfile(Profiler.IO);
        }
    }

    public static void start() throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, FailoverFileReplicationManager.class, "start()", null);
        try {
            if(AOServDaemonConfiguration.isManagerEnabled(FailoverFileReplicationManager.class) && thread==null) {
                synchronized(System.out) {
                    if(thread==null) {
                        System.out.print("Starting FailoverFileReplicationManager: ");
                        (thread=new Thread(new FailoverFileReplicationManager())).start();
                        System.out.println("Done");
                    }
                }
            }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }
    
    public void run() {
        Profiler.startProfile(Profiler.UNKNOWN, FailoverFileReplicationManager.class, "run()", null);
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
                            if(log.isDebugEnabled()) log.debug("Sleeping for "+minutes+" minutes");
                            Thread.sleep(minutes*60L*1000L);
                        } catch(InterruptedException err) {
                            AOServDaemon.reportWarning(err, null);
                        }
                        AOServer thisServer=AOServDaemon.getThisAOServer();
                        if(log.isDebugEnabled()) log.debug("thisServer="+thisServer);
                        AOServer failoverServer=thisServer.getFailoverServer();
                        if(log.isDebugEnabled()) log.debug("failoverServer="+failoverServer);
                        List<FailoverFileReplication> ffrs=thisServer.getFailoverFileReplications();
                        for(int c=0;c<ffrs.size();c++) {
                            // Try the next server if an error occurs
                            FailoverFileReplication ffr=ffrs.get(c);
                            if(log.isDebugEnabled()) log.debug("ffr="+ffr);
                            try {
                                // Will not replicate if the to server is our parent server in failover mode
                                AOServer toServer=ffr.getToAOServer();
                                if(log.isDebugEnabled()) log.debug("toServer="+toServer);
                                if(!toServer.equals(failoverServer)) {
                                    // Find the most recent successful failover pass
                                    List<FailoverFileLog> logs = ffr.getFailoverFileLogs(1);
                                    // These are sorted most recent on the bottom
                                    FailoverFileLog lastLog = logs.isEmpty() ? null : logs.get(0);
                                    if(log.isDebugEnabled()) log.debug("lastLog="+lastLog);
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
                                        if(log.isDebugEnabled()) log.debug("The last attempt at this mirror failed");
                                    } else {
                                        if(
                                            // Never ran this mirror
                                            lastLog==null
                                        ) {
                                            shouldRun = true;
                                            if(log.isDebugEnabled()) log.debug("Never ran this mirror");
                                        } else {
                                            if(
                                                // Last pass in the log failed
                                                !lastLog.isSuccessful()
                                            ) {
                                                shouldRun = true;
                                                if(log.isDebugEnabled()) log.debug("Last pass in the log failed");
                                            } else {
                                                if(
                                                    // Last pass in the future (time reset)
                                                    lastLog.getStartTime() > System.currentTimeMillis()
                                                ) {
                                                    shouldRun = true;
                                                    if(log.isDebugEnabled()) log.debug("Last pass in the future (time reset)");
                                                } else {
                                                    if(
                                                        // Last pass more than 24 hours ago
                                                        (System.currentTimeMillis() - lastLog.getStartTime())>=(24*60*60*1000)
                                                    ) {
                                                        shouldRun = true;
                                                        if(log.isDebugEnabled()) log.debug("Last pass more than 24 hours ago");
                                                    } else {
                                                        if(
                                                            // It is the scheduled time and the last logged start time was >= MINIMUM_INTERVAL
                                                            (isScheduled && (System.currentTimeMillis()-lastLog.getStartTime())>=FailoverFileReplication.MINIMUM_INTERVAL)
                                                        ) {
                                                            shouldRun = true;
                                                            if(log.isDebugEnabled()) log.debug("It is the scheduled time and the last logged start time was >= MINIMUM_INTERVAL");
                                                        } else {
                                                            // TODO: Look for more specific missed schedules (down the the hour)
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    if(shouldRun) runFailoverCopy(ffr);
                                }
                                lastFaileds.put(ffr, false);
                            } catch(IOException err) {
                                lastFaileds.put(ffr, true);
                                AOServDaemon.reportError(err, null);
                                try {
                                    Thread.sleep(60*1000);
                                } catch(InterruptedException err2) {
                                    AOServDaemon.reportWarning(err2, null);
                                }
                            } catch(SQLException err) {
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
    
    private static void runFailoverCopy(FailoverFileReplication ffr) throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, FailoverFileReplicationManager.class, "runFailoverCopy(FailoverFileReplication)", null);
        try {
            AOServer thisServer=AOServDaemon.getThisAOServer();
            AOServer toServer=ffr.getToAOServer();
            if(log.isInfoEnabled()) log.info("Running failover from "+thisServer+" to "+toServer);
            ProcessTimer timer=new ProcessTimer(
                AOServDaemon.getRandom(),
                AOServDaemonConfiguration.getWarningSmtpServer(),
                AOServDaemonConfiguration.getWarningEmailFrom(),
                AOServDaemonConfiguration.getWarningEmailTo(),
                "Failover Replication Taking Too Long",
                "Failover Replication from "+thisServer.getServer().getHostname()+" to "+toServer.getServer().getHostname(),
                6L*60*60*1000,
                6L*60*60*1000
            );
            try {
                timer.start();
                // Flag that we have started
                ffr.setLastStartTime(System.currentTimeMillis());

                // Build the skip list
                List<String> noCopyList=new SortedArrayList<String>();
                noCopyList.add("/aquota.user");
                noCopyList.add("/backup");
                noCopyList.add("/boot/lost+found");
                noCopyList.add("/dev/log");
                noCopyList.add("/dev/pts");
                noCopyList.add("/dev/shm");
                noCopyList.add("/distro");
                noCopyList.add("/lost+found");
                noCopyList.add("/mnt/cdrom");
                noCopyList.add("/mnt/floppy");
                noCopyList.add("/proc");
                noCopyList.add("/swapfile.aes128.img");
                noCopyList.add("/sys");
                noCopyList.add("/tmp");
                noCopyList.add("/usr/tmp");
                noCopyList.add("/var/backup");
                noCopyList.add("/var/failover");
                noCopyList.add("/var/failover.aes128.img");
                noCopyList.add("/var/failover.aes256.img");
                noCopyList.add("/var/lib/pgsql.aes128.img");
                noCopyList.add("/var/lib/pgsql.aes256.img");
                noCopyList.add("/var/lib/pgsql/7.1/postmaster.pid");
                noCopyList.add("/var/lib/pgsql/7.2/postmaster.pid");
                noCopyList.add("/var/lib/pgsql/7.3/postmaster.pid");
                noCopyList.add("/var/lib/pgsql/8.0/postmaster.pid");
                noCopyList.add("/var/lib/pgsql/8.1/postmaster.pid");
                noCopyList.add("/var/lib/sasl2/saslauthd.pid");
                noCopyList.add("/var/lock/subsys/aoserv-daemon");
                noCopyList.add("/var/lock/subsys/clear_jvm_stats");
                noCopyList.add("/var/lock/subsys/clear_postgresql_pid");
                noCopyList.add("/var/lock/subsys/crond");
                noCopyList.add("/var/lock/subsys/daemons");
                noCopyList.add("/var/lock/subsys/kheader");
                noCopyList.add("/var/lock/subsys/local");
                noCopyList.add("/var/lock/subsys/mysql");
                noCopyList.add("/var/lock/subsys/network");
                noCopyList.add("/var/lock/subsys/postgresql-7.1");
                noCopyList.add("/var/lock/subsys/postgresql-7.2");
                noCopyList.add("/var/lock/subsys/postgresql-7.3");
                noCopyList.add("/var/lock/subsys/postgresql-8.0");
                noCopyList.add("/var/lock/subsys/postgresql-8.1");
                noCopyList.add("/var/lock/subsys/proftpd");
                noCopyList.add("/var/lock/subsys/route");
                noCopyList.add("/var/lock/subsys/saslauthd");
                noCopyList.add("/var/lock/subsys/sendmail");
                noCopyList.add("/var/lock/subsys/sm-client");
                noCopyList.add("/var/lock/subsys/spamd");
                noCopyList.add("/var/lock/subsys/sshd1");
                noCopyList.add("/var/lock/subsys/syslog");
                noCopyList.add("/var/lock/subsys/xfs");
                noCopyList.add("/var/lock/subsys/xinetd");
                noCopyList.add("/var/lock/subsys/xvfb");
                noCopyList.add("/var/oldaccounts");
                noCopyList.add("/var/run/aoserv-daemon-java.pid");
                noCopyList.add("/var/run/aoserv-daemon.pid");
                noCopyList.add("/var/run/crond.pid");
                noCopyList.add("/var/run/httpd1.pid");
                noCopyList.add("/var/run/httpd2.pid");
                noCopyList.add("/var/run/httpd3.pid");
                noCopyList.add("/var/run/httpd4.pid");
                noCopyList.add("/var/run/httpd5.pid");
                noCopyList.add("/var/run/httpd6.pid");
                noCopyList.add("/var/run/klogd.pid");
                noCopyList.add("/var/run/proftpd.pid");
                noCopyList.add("/var/run/proftpd/proftpd.scoreboard");
                noCopyList.add("/var/run/runlevel.dir");
                noCopyList.add("/var/run/sendmail.pid");
                noCopyList.add("/var/run/sm-client.pid");
                noCopyList.add("/var/run/sshd.pid");
                noCopyList.add("/var/run/syslogd.pid");
                noCopyList.add("/var/run/xfs.pid");
                noCopyList.add("/var/run/xinetd.pid");
                noCopyList.add("/var/run/xvfb.pid");
                noCopyList.add("/var/spool/clientmqueue/sm-client.pid");
                noCopyList.add("/var/tmp");
                noCopyList.add("/www.aes128.img");
                noCopyList.add("/www.aes256.img");
                noCopyList.add("/www/aquota.user");
                noCopyList.add("/www/lost+found");
                for(BackupPartition bp : thisServer.getBackupPartitions()) {
                    noCopyList.add(bp.getPath());
                }

                // Keep statistics during the replication
                final long startTime=System.currentTimeMillis();
                int scanned=0;
                // TODO: int added=0;
                int updated=0;
                // TODO: int removed=0;
                //long bytesOut=0;
                long rawBytesOut=0;
                //long bytesIn=0;
                long rawBytesIn=0;
                boolean isSuccessful=false;
                try {
                    // Get the connection to the daemon
                    long key=toServer.requestDaemonAccess(AOServDaemonProtocol.FAILOVER_FILE_REPLICATION, ffr.getPKey());
                    NetBind daemonBind=toServer.getDaemonConnectBind();
                    AOServDaemonConnector daemonConnector=AOServDaemonConnector.getConnector(
                        toServer.getServer().getPKey(),
                        toServer.getDaemonIPAddress().getIPAddress(),
                        daemonBind.getPort().getPort(),
                        daemonBind.getAppProtocol().getProtocol(),
                        null,
                        toServer.getPoolSize(),
                        AOPool.DEFAULT_MAX_CONNECTION_AGE,
                        SSLConnector.class,
                        SSLConnector.sslProviderLoaded,
                        AOServClientConfiguration.getProperty("aoserv.client.ssl.truststore.path"),
                        AOServClientConfiguration.getProperty("aoserv.client.ssl.truststore.password"),
                        AOServDaemon.getErrorHandler()
                    );
                    AOServDaemonConnection daemonConn=daemonConnector.getConnection();
                    try {
                        // Start the replication
                        CompressedDataOutputStream rawOut=daemonConn.getOutputStream();
                        // TODO: Make an configurable option per replication
                        boolean useCompression = ffr.getUseCompression();
                        if(log.isTraceEnabled()) log.trace("useCompression="+useCompression);

                        MD5 md5 = useCompression ? new MD5() : null;

                        rawOut.writeCompressedInt(AOServDaemonProtocol.FAILOVER_FILE_REPLICATION);
                        rawOut.writeLong(key);
                        rawOut.writeBoolean(useCompression);
                        rawOut.flush();

                        CompressedDataInputStream rawIn=daemonConn.getInputStream();
                        int result=rawIn.read();
                        if(result==AOServDaemonProtocol.NEXT) {
                            // Setup Compression and/or bandwidth limiting
                            CompressedDataOutputStream out;
                            CompressedDataInputStream in;
                            ByteCountOutputStream rawBytesOutStream;
                            ByteCountInputStream rawBytesInStream;
                            //ByteCountOutputStream bytesOutStream;
                            //ByteCountInputStream bytesInStream;
                            //boolean closeStreams;

                            /*if(useCompression) {
                                if(ffr.getBitRate()!=-1) {
                                    // Only the output is limited because input should always be smaller than the output
                                    out = new CompressedDataOutputStream(
                                        bytesOutStream = new ByteCountOutputStream(
                                            new GZIPOutputStream(
                                                rawBytesOutStream = new ByteCountOutputStream(
                                                    new DontCloseOutputStream(
                                                        new BitRateOutputStream(
                                                            rawOut,
                                                            ffr
                                                        )
                                                    )
                                                ),
                                                BufferManager.BUFFER_SIZE
                                            )
                                        )
                                    );
                                } else {
                                    out = new CompressedDataOutputStream(
                                        bytesOutStream = new ByteCountOutputStream(
                                            new GZIPOutputStream(
                                                rawBytesOutStream = new ByteCountOutputStream(
                                                    new DontCloseOutputStream(
                                                        rawOut
                                                    )
                                                ),
                                                BufferManager.BUFFER_SIZE
                                            )
                                        )
                                    );
                                }
                                in = new CompressedDataInputStream(
                                    bytesInStream = new ByteCountInputStream(
                                        new GZIPInputStream(
                                            rawBytesInStream = new ByteCountInputStream(
                                                new DontCloseInputStream(
                                                    rawIn
                                                )
                                            ),
                                            BufferManager.BUFFER_SIZE
                                        )
                                    )
                                );
                                closeStreams = true;
                            } else {*/
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
                                //closeStreams = false;
                            //}
                            try {
                                //if(log.isTraceEnabled()) log.trace("closeStreams="+closeStreams);

                                FilesystemIterator fileIterator=new FilesystemIterator(noCopyList);

                                // Do requests in batches
                                UnixFile[] ufs=new UnixFile[BATCH_SIZE];
                                int[] results=new int[BATCH_SIZE];
                                long[][] md5His = useCompression ? new long[BATCH_SIZE][] : null;
                                long[][] md5Los = useCompression ? new long[BATCH_SIZE][] : null;
                                byte[] buff=BufferManager.getBytes();
                                byte[] chunkBuffer = new byte[CHUNK_SIZE];
                                try {
                                    while(true) {
                                        int batchSize=fileIterator.getNextUnixFiles(ufs, BATCH_SIZE);
                                        if(batchSize==0) break;

                                        out.writeCompressedInt(batchSize);
                                        for(int d=0;d<batchSize;d++) {
                                            scanned++;
                                            UnixFile uf=ufs[d];
                                            try {
                                                long mode=uf.getStatMode();
                                                if(!UnixFile.isSocket(mode)) {
                                                    // Get all the values first to avoid FileNotFoundException in middle of protocol
                                                    String filename=uf.getFilename();
                                                    boolean isRegularFile=UnixFile.isRegularFile(mode);
                                                    long size=isRegularFile?uf.getSize():-1;
                                                    int uid=uf.getUID();
                                                    int gid=uf.getGID();
                                                    boolean isSymLink=UnixFile.isSymLink(mode);
                                                    long modifyTime=isSymLink?-1:uf.getModifyTime();
                                                    String symLinkTarget=isSymLink?uf.readLink():null;
                                                    boolean isDevice=UnixFile.isBlockDevice(mode) || UnixFile.isCharacterDevice(mode);
                                                    long deviceID=isDevice?uf.getDeviceIdentifier():-1;

                                                    out.writeBoolean(true);
                                                    out.writeCompressedUTF(filename, 0);
                                                    out.writeLong(mode);
                                                    if(UnixFile.isRegularFile(mode)) out.writeLong(size);
                                                    if(uid<0 || uid>65535) {
                                                        AOServDaemon.reportWarning(new IOException("UID out of range, converted to 0"), new Object[] {"uid="+uid, "path="+uf.getFilename()});
                                                        uid=0;
                                                    }
                                                    out.writeCompressedInt(uid);
                                                    if(gid<0 || gid>65535) {
                                                        AOServDaemon.reportWarning(new IOException("GID out of range, converted to 0"), new Object[] {"gid="+gid, "path="+uf.getFilename()});
                                                        gid=0;
                                                    }
                                                    out.writeCompressedInt(gid);
                                                    if(!isSymLink) out.writeLong(modifyTime);
                                                    if(isSymLink) out.writeCompressedUTF(symLinkTarget, 1);
                                                    else if(isDevice) out.writeLong(deviceID);
                                                } else {
                                                    ufs[d]=null;
                                                    out.writeBoolean(false);
                                                }
                                            } catch(FileNotFoundException err) {
                                                // Normal because of a dynamic file system
                                                ufs[d]=null;
                                                out.writeBoolean(false);
                                            }
                                        }
                                        out.flush();

                                        // Read the results
                                        result=in.read();
                                        boolean hasRequestData = false;
                                        if(result==AOServDaemonProtocol.NEXT) {
                                            for(int d=0;d<batchSize;d++) {
                                                if(ufs[d]!=null) {
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
                                            //if(useCompression) {
                                            //    deflaterOut = new DeflaterOutputStream(/*new DontCloseOutputStream(*/out/*)*/, new Deflater(), BufferManager.BUFFER_SIZE);
                                            //    outgoing = new DataOutputStream(deflaterOut);
                                            //} else {
                                                deflaterOut = null;
                                                outgoing = out;
                                            //}
                                        } else {
                                            deflaterOut = null;
                                            outgoing = null;
                                        }
                                        for(int d=0;d<batchSize;d++) {
                                            UnixFile uf=ufs[d];
                                            if(uf!=null) {
                                                result=results[d];
                                                if(result==MODIFIED) {
                                                    if(log.isDebugEnabled()) log.debug("File modified: "+uf.getFilename());
                                                    updated++;
                                                } else if(result==MODIFIED_REQUEST_DATA) {
                                                    updated++;
                                                    try {
                                                        if(log.isDebugEnabled()) log.debug("Sending file contents: "+uf.getFilename());
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
                                                    } catch(FileNotFoundException err) {
                                                        // Normal when the file was deleted
                                                    } finally {
                                                        outgoing.write(AOServDaemonProtocol.DONE);
                                                    }
                                                } else if(result==MODIFIED_REQUEST_DATA_CHUNKED) {
                                                    updated++;
                                                    try {
                                                        if(log.isDebugEnabled()) log.debug("Chunking file contents: "+uf.getFilename());
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
                                                            if(log.isDebugEnabled()) log.debug("Chunking file contents: "+uf.getFilename()+": Sent "+sendChunkCount+" out of "+chunkNumber+" chunks");
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
                                            //if(deflaterOut!=null) deflaterOut.finish();
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
                                //bytesOut=bytesOutStream.getCount();
                                //bytesIn=bytesInStream.getCount();
                                //if(closeStreams) {
                                    // Release compressed stream native resources
                                    //in.close();
                                    //out.close();
                                //}
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
}